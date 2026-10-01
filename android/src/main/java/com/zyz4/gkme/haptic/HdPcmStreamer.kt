package com.zyz4.gkme.haptic

import com.zyz4.gkme.controlled.HapticInjector
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把语音线圈 PCM 的逐帧音高/幅度编码成 **HE 1.0 多事件分块效果**，从手机马达“近乎无损”地还原。
 *
 * 背景（真机实测，见 docs/richtap-hd-vibration.md）：
 * - 反复 `stop()+start()` 分段投递会在每次切换处掉幅（原 SEGMENT_MS=500 的实现）；
 * - 单条 `DynamicEffect` 支持多个 `continuous` 事件，由引擎内部调度，段间无切换凹陷；
 * - 但单条效果最多 **16 个事件**，每个事件的 `Curve` 必须 **恰好 4 点**，单事件 ≤5000ms。
 *
 * 因此这里按 [EVENT_MS] 切事件、[EVENTS_PER_CHUNK] 个事件合成一条效果（约 0.2s），
 * 每帧的（幅度, HE 频率）写进对应事件的控制点桶，凑满一块后再通过
 * [HapticInjector.startEffect] 以“效果自身参数”投递（无参 `start()`，避免全局
 * amplitude/freq 覆盖事件参数）。
 *
 * 关键点：
 * - **静音时冲刷**（而不是直接丢弃）已累积的分块，短促音效也能出声；
 * - 以 [HapticSource.AUDIO] 参与 `HapticInjector` 的优先级仲裁：被更高优先级的自适应扳机
 *   占用时静默并清空缓冲，避免与 `PhoneHdHaptics`（游戏 rumble）抢占同一个 HapticPlayer；
 * - 只有幅度达到 [AUDIO_ACTIVE_AMP] 才算“正在输出触觉”，并据此刷新优先级租约；静音流/
 *   本底噪声不刷新它。优先级不是粘性的：超过 [AUDIO_RELEASE_MS] 无有效输出（哪怕仍在
 *   下发无声帧，甚至彻底停发）就由 [watchdog] 释放，避免无声音频流阻塞其他震动。
 *
 * 延迟：分块时长就是**每次起振的首帧延迟**。原来 `16×50ms=0.8s` 太大，
 * 现在 `[EVENTS_PER_CHUNK]×[EVENT_MS]=4×50ms=200ms`，把起振延迟压到 1/4；
 * 代价是每 200ms 一次 `stop()+start()` 边界（原来每 0.8s）。见 [EVENTS_PER_CHUNK]。
 */
object HdPcmStreamer {

    /** 每个事件的时长（ms）。4 个控制点即约 [EVENT_MS]/3 ≈ 16.7ms 分辨率。 */
    private const val EVENT_MS = 50

    /**
     * 单条效果的事件数（真机上限 16）。
     *
     * 分块时长 = [EVENT_MS] × 本值 = **首帧延迟**。用满 16（=0.8s）会让每次起振都等
     * 0.8s；降到 4（=200ms）把起振延迟压到 1/4，同时保持分辨率不变（仍 4 点/事件）。
     * 代价：分块边界（内部 `stop()+start()`，有轻微掉幅）从每 0.8s 一次变成每 0.2s
     * 一次。这是**延迟↔平滑**的取舍——块越短延迟越低、边界越频繁；可按需在此调。
     */
    private const val EVENTS_PER_CHUNK = 4

    /**
     * 视为“正在输出触觉”的幅度下限（0-255，对应语音线圈幅度）。
     *
     * 低于它的音频（静音流、本底噪声、DC 载波）不产生有效触觉，也**不刷新** [lastActiveNs]，
     * 因此不会占用 [HapticSource.AUDIO] 优先级。否则 PC 端持续下发的“无声”音频流会一直
     * 压制游戏震动与按钮震动。可按手感微调。
     */
    private const val AUDIO_ACTIVE_AMP = 4

    /**
     * 相邻提交之间最长计时间隔，避免线程卡顿导致事件时间轴跳变。
     * 必须小于 [EVENT_MS]，否则一帧迟到会直接跳过某个事件（该事件被上一值填充）。
     */
    private const val MAX_DT_MS = 40

    /**
     * 距上一次“有效输出”超过此值即释放 [HapticSource.AUDIO] 优先级。
     *
     * 关键：优先级不是“粘性”的——只有持续有有效输出才持有；一旦静音（无论是否仍在下发
     * 无声帧，甚至彻底停发）都由 [watchdog] 主动释放，避免无声音频长期阻塞其他震动。
     */
    private const val AUDIO_RELEASE_MS = 120L

    /** 看门狗检查周期。 */
    private const val WATCHDOG_INTERVAL_MS = 50L

    /**
     * 分块边界「起振补偿」增益：每次 `stop()+start()` 重启处的掉幅，用抬升首事件领先控制点来填。
     * 1.0 = 关闭；建议范围 1.1–1.5，需真机（加速度计/手感）标定。块越短边界越频繁，越依赖它。
     */
    private const val SEAM_BOOST = 1.3

    /**
     * 音量突增时额外插入的**满强度**短事件时长（ms）。让 LRA 在声音骤响时强震一下。
     * 0 = 关闭；越小越“脆”，但过短可能不被 HAL 接受（需真机验证）。
     */
    private const val ONSET_ACCENT_MS = 5

    private val lock = Any()
    private val encoder = PcmHeEncoder(EVENTS_PER_CHUNK, EVENT_MS, SEAM_BOOST, ONSET_ACCENT_MS)
    private var lastNs = 0L
    private var pending = false

    /** 上一次有效输出（[amp] ≥ [AUDIO_ACTIVE_AMP]）的时间戳；看门狗据此释放优先级。 */
    @Volatile
    private var lastActiveNs = 0L

    private val watchdogStarted = AtomicBoolean(false)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GkmeAudioHdWatchdog").apply { isDaemon = true }
    }

    /**
     * 提交一帧 PCM 的主导音高与左右幅度（0-255）。低于 [AUDIO_ACTIVE_AMP] 视为静音：
     * 冲刷已累积的分块，但不刷新优先级租约。
     * @return true 表示本次由 HD 处理（调用方无需回退普通震动）；false 表示 HD 不可用。
     */
    fun submit(left: Int, right: Int, pitchHz: Double): Boolean {
        if (!PhoneHdHaptics.enabled) return false
        if (!HapticInjector.isHapticReady()) return false
        ensureWatchdog()

        // 被更高优先级来源（自适应扳机）占用：静默并清空缓冲，避免解禁后补发陈旧音频；
        // 返回 true 让 AudioPlaybackService 不要回退系统震动。
        if (!HapticInjector.canPlay(HapticSource.AUDIO)) {
            resetBuffer()
            return true
        }

        val amp = maxOf(left, right).coerceIn(0, 255)
        val now = System.nanoTime()

        // 静音/本底噪声：不刷新租约、不生成新效果；只看门狗的租约到期释放优先级（旧实现里
        // 这里会因冲刷尾音而重新获取优先级，导致无声流长期阻塞）。
        if (amp < AUDIO_ACTIVE_AMP) {
            synchronized(lock) {
                if (pending) {
                    pending = false
                    val json = encoder.flush()
                    if (json != null) HapticInjector.startEffect(json, HapticSource.AUDIO)
                }
                lastNs = 0L
            }
            return true
        }

        // 有效输出：刷新租约并（重新）获取 AUDIO 优先级。
        lastActiveNs = now
        if (!HapticInjector.acquire(HapticSource.AUDIO)) return true

        synchronized(lock) {
            if (lastNs == 0L) lastNs = now
            val dtMs = (((now - lastNs) / 1_000_000L).toInt()).coerceIn(1, MAX_DT_MS)
            lastNs = now
            val he = if (pitchHz > 0.0) {
                RichTapFrequency.hzToHe(RichTapFrequency.shiftIntoRange(pitchHz))
            } else {
                RichTapFrequency.HE_AT_RESONANCE
            }
            val json = encoder.addSample(dtMs, amp / 255f, he)
            pending = true
            if (json != null) {
                pending = false
                HapticInjector.startEffect(json, HapticSource.AUDIO)
            }
        }
        return true
    }

    /** 停止并清空缓冲（硬停止：仅当 AUDIO 持有优先级时才取消效果）。 */
    fun stop() {
        resetBuffer()
        lastActiveNs = 0L
        HapticInjector.stopOwnedBy(HapticSource.AUDIO)
    }

    private fun resetBuffer() {
        synchronized(lock) {
            encoder.reset()
            lastNs = 0L
            pending = false
        }
    }

    private fun ensureWatchdog() {
        if (watchdogStarted.compareAndSet(false, true)) {
            watchdog.scheduleWithFixedDelay(
                ::onWatchdog, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS,
            )
        }
    }

    /** 租约到期：距上次有效输出超过 [AUDIO_RELEASE_MS] 就放弃 AUDIO 优先级，让下级震动接管。 */
    private fun onWatchdog() {
        if (!PhoneHdHaptics.enabled) return
        if (lastActiveNs == 0L) return
        if (HapticInjector.owner() !== HapticSource.AUDIO) return
        val idleMs = (System.nanoTime() - lastActiveNs) / 1_000_000L
        if (idleMs >= AUDIO_RELEASE_MS) {
            lastActiveNs = 0L
            HapticInjector.stopOwnedBy(HapticSource.AUDIO)
        }
    }
}
