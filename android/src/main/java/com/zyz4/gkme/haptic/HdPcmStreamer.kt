package com.zyz4.gkme.haptic

import com.zyz4.gkme.controlled.HapticInjector
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把语音线圈 PCM 的逐帧音高/幅度编码成 **HE 1.0 多事件分块效果**，从手机马达“近乎无损”地还原。
 *
 * 背景（真机加速度计实测，见 docs/richtap-hd-vibration.md §13）：
 * - `stop()+start()` 分段投递在每次切换处掉幅，且 `stop()` 是**全局取消**（停旧会连新一起停）；
 * - 单条 `DynamicEffect` 支持多个 `continuous` 事件，但**每个事件边界都会掉幅**——除非把每个
 *   事件的控制点**聚到两端**（`0, ε, eventMs-ε, eventMs`）；均匀分布的控制点会被引擎加上一段
 *   与其时长成比例的起振/衰减（约 25–30%），造成明显断点；
 * - 单条效果最多 **16 个事件**，每个事件的 `Curve` 必须 **恰好 4 点**，单事件 ≤5000ms。
 *
 * 因此这里按 [EVENT_MS] 切事件、[EVENTS_PER_CHUNK] 个事件合成一条效果（约 0.2s），
 * 每帧的（幅度, HE 频率）写进对应事件，凑满一块后再通过 [HapticInjector.startEffect]
 * 以“效果自身参数”投递（无参 `start()`，避免全局 amplitude/freq 覆盖事件参数）。
 * 分块之间**不再 `stop()`**（见 `RemoteHapticService.startTencentEffect`）：新效果直接接管，
 * 配合边缘化控制点实现无断点衔接。
 *
 * 关键点：
 * - **静音时冲刷**（而不是直接丢弃）已累积的分块，短促音效也能出声；
 * - **短促瞬态响应**：PC 的 ≤50ms 短音（总是从静音突变而来）除包络外，再于分块内对应时刻
 *   并入一条 [BURST_MS] 的满强度事件，还原成单次短促强震；瞬态进行中即使出现短暂凹陷
 *   （主峰后的余响/回声）也继续喂入静音样本、待编码器判定其结束再冲刷，避免一次短音被拆成两条；
 * - 以 [HapticSource.AUDIO] 参与 `HapticInjector` 的优先级仲裁：被更高优先级的自适应扳机
 *   占用时静默并清空缓冲，避免与 `PhoneHdHaptics`（游戏 rumble）抢占同一个 HapticPlayer；
 * - 只有幅度达到 [AUDIO_ACTIVE_AMP] 才算“正在输出触觉”，并据此刷新优先级租约；静音流/
 *   本底噪声不刷新它。优先级不是粘性的：超过 [AUDIO_RELEASE_MS] 无有效输出（哪怕仍在
 *   下发无声帧，甚至彻底停发）就由 [watchdog] 释放，避免无声音频流阻塞其他震动。
 *
 * 延迟：分块时长就是**每次起振的首帧延迟**。`[EVENTS_PER_CHUNK]×[EVENT_MS]=2×100ms=200ms`，
 * 既保持较低的首帧延迟，又避免 50ms 事件导致 LRA 未起振、主频被拉向共振（真机实测）。
 * 见 [EVENTS_PER_CHUNK] 与 [EVENT_MS]。
 */
object HdPcmStreamer {

    /**
     * 每个事件的时长（ms），也是包络的时间分辨率。
     *
     * **真机（加速度计）实测**：事件时长 [EVENT_MS]=50ms 时 LRA 来不及起振到位，主频被拉向
     * 机身共振（HE56 目标 170Hz 实测 ~179Hz）且输出明显偏弱；[EVENT_MS]≥100ms 时主频准确
     * （170.0Hz）且幅度满额。故取 100ms。
     */
    private const val EVENT_MS = 100

    /**
     * 单条效果的事件数（真机上限 16）。
     *
     * 分块时长 = [EVENT_MS] × 本值 = **首帧延迟**。保持 2×100ms = **200ms** 以压低延迟。
     * 真机实测分块越大，分块边界（无 stop 接管仍有残余抖动的）越少、包络越平：窗口幅度
     * 变异系数 200ms≈6.4%、400ms≈4.4%、800ms≈3.2%。若更看重平滑而能接受更长首帧延迟，
     * 可增大本值（如 8 → 800ms）。
     */
    private const val EVENTS_PER_CHUNK = 2

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

    /** 被更高优先级占用时最多保留缓冲的时长；超过则丢弃，避免解禁后补发陈旧音频。 */
    private const val HOLD_MAX_MS = 300L

    /** 看门狗检查周期。 */
    private const val WATCHDOG_INTERVAL_MS = 50L

    /**
     * 音量突增时额外插入的**满强度**短事件时长（ms）。让 LRA 在声音骤响时强震一下。
     * 0 = 关闭；越小越“脆”，但过短可能不被 HAL 接受（需真机验证）。
     */
    private const val ONSET_ACCENT_MS = 5

    /**
     * 短促瞬态响应时长（ms）：PC 会下发 ≤50ms、总是从静音突变而来的音频，把它还原成
     * 单次短促强震，并入当前 200ms 分块。0 = 关闭；见 [PcmHeEncoder] 的 `burstMs`。
     */
    private const val BURST_MS = 8

    private val lock = Any()
    private val encoder = PcmHeEncoder(EVENTS_PER_CHUNK, EVENT_MS, ONSET_ACCENT_MS, BURST_MS)
    private var lastNs = 0L
    private var pending = false
    private var heldSinceNs = 0L

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

        // 被更高优先级来源（自适应扳机）占用：暂停累积并保留缓冲，解禁后继续，
        // 避免直接丢弃已累积的分块（原实现损失约 0.2s）；但保留时长有上界，
        // 超过 [HOLD_MAX_MS] 则丢弃，防止长时间占满后突然补发一段陈旧音频。
        // 返回 true 让 AudioPlaybackService 不要回退系统震动。
        if (!HapticInjector.canPlay(HapticSource.AUDIO)) {
            val nowNs = System.nanoTime()
            synchronized(lock) {
                if (pending || encoder.isBurstActive) {
                    if (heldSinceNs == 0L) heldSinceNs = nowNs
                    if (nowNs - heldSinceNs > HOLD_MAX_MS * 1_000_000L) {
                        encoder.resetStream()
                        pending = false
                        lastNs = 0L
                        heldSinceNs = 0L
                    } else {
                        lastNs = nowNs
                    }
                }
            }
            return true
        }
        heldSinceNs = 0L

        val amp = maxOf(left, right).coerceIn(0, 255)
        val now = System.nanoTime()

        // 静音/本底噪声：不刷新租约、不生成新效果；只看门狗的租约到期释放优先级（旧实现里
        // 这里会因冲刷尾音而重新获取优先级，导致无声流长期阻塞）。
        if (amp < AUDIO_ACTIVE_AMP) {
            synchronized(lock) {
                if (pending && encoder.isBurstActive) {
                    // 瞬态进行中（主峰后出现短暂凹陷）：继续喂入静音样本，让编码器跨过
                    // “主峰 → 凹陷 → 余响”的间隙，把整段 ≤50ms 的短音当作一次瞬态。
                    // 待编码器判定瞬态结束（间隙超过容限）后，再由下一静音帧冲刷投递。
                    val dtMs = (((now - lastNs) / 1_000_000L).toInt()).coerceIn(1, MAX_DT_MS)
                    lastNs = now
                    val json = encoder.addSample(dtMs, 0f, RichTapFrequency.HE_AT_RESONANCE)
                    if (json != null) {
                        pending = false
                        HapticInjector.startEffect(json, HapticSource.AUDIO)
                    }
                } else if (pending) {
                    pending = false
                    val json = encoder.flush()
                    if (json != null) HapticInjector.startEffect(json, HapticSource.AUDIO)
                    lastNs = 0L
                } else {
                    lastNs = 0L
                }
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
            encoder.resetStream()
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
            // 音频在瞬态/分块中途停发（之后不再有静音帧来冲刷）：先把已累积的分块投递出去，
            // 避免漏掉这一下；延后一个周期再释放优先级。
            var flushed = false
            synchronized(lock) {
                if (pending) {
                    pending = false
                    val json = encoder.flush()
                    if (json != null) {
                        HapticInjector.startEffect(json, HapticSource.AUDIO)
                        flushed = true
                    }
                }
            }
            if (flushed) {
                lastActiveNs = System.nanoTime()
                return
            }
            lastActiveNs = 0L
            HapticInjector.stopOwnedBy(HapticSource.AUDIO)
        }
    }
}
