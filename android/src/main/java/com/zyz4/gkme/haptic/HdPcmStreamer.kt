package com.zyz4.gkme.haptic

import com.zyz4.gkme.controlled.HapticInjector

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
 * - 用**所有权 token** 停止，避免与 `PhoneHdHaptics`（游戏 rumble）共用同一个
 *   HapticPlayer 时互相误杀；
 * - 静音超过 [SILENCE_STOP_MS] 才真正停止，避免相邻块/短暂静音把效果掐掉。
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

    /** 判定为静音的幅度阈值（0-255，对应语音线圈幅度）。 */
    private const val SILENCE = 1

    /**
     * 相邻提交之间最长计时间隔，避免线程卡顿导致事件时间轴跳变。
     * 必须小于 [EVENT_MS]，否则一帧迟到会直接跳过某个事件（该事件被上一值填充）。
     */
    private const val MAX_DT_MS = 40

    /** 连续静音超过此值才真正停止 HD 效果，避免短暂静音掐断。 */
    private const val SILENCE_STOP_MS = 200

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

    /** 效果所有权 token：与 PhoneHdHaptics 区分，避免互相误杀。 */
    private val TOKEN = Any()

    private val lock = Any()
    private val encoder = PcmHeEncoder(EVENTS_PER_CHUNK, EVENT_MS, SEAM_BOOST, ONSET_ACCENT_MS)
    private var lastNs = 0L
    private var pending = false
    private var silenceSinceNs = 0L

    @Volatile
    private var active = false

    /**
     * 提交一帧 PCM 的主导音高与左右幅度（0-255）。静音（≤1）时冲刷已累积的分块。
     * @return true 表示本次由 HD 处理（调用方无需回退普通震动）；false 表示 HD 不可用。
     */
    fun submit(left: Int, right: Int, pitchHz: Double): Boolean {
        if (!PhoneHdHaptics.enabled) return false
        if (!HapticInjector.isHapticReady()) return false

        val amp = maxOf(left, right).coerceIn(0, 255)
        val now = System.nanoTime()

        if (amp <= SILENCE) {
            synchronized(lock) {
                if (pending) {
                    pending = false
                    val json = encoder.flush()
                    if (json != null) HapticInjector.startEffect(json, TOKEN)
                }
                if (silenceSinceNs == 0L) silenceSinceNs = now
                val quietMs = (now - silenceSinceNs) / 1_000_000L
                if (active && quietMs >= SILENCE_STOP_MS) {
                    active = false
                    HapticInjector.stopOwnedBy(TOKEN)
                }
                lastNs = 0L
            }
            return true
        }

        synchronized(lock) {
            silenceSinceNs = 0L
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
                HapticInjector.startEffect(json, TOKEN)
            }
        }
        active = true
        return true
    }

    /** 停止并清空缓冲（硬停止：取消正在播放的效果）。 */
    fun stop() {
        synchronized(lock) {
            encoder.reset()
            lastNs = 0L
            pending = false
            silenceSinceNs = 0L
        }
        if (active) {
            active = false
            HapticInjector.stopOwnedBy(TOKEN)
        }
    }
}
