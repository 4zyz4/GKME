package com.zyz4.gkme.haptic

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 用「快速连续发送短促震动」来模拟**低频震动**（频率低于马达下限的场合）。
 *
 * 线性马达（LRA）是窄带谐振器，本机可用频段约 [RichTapEngine.MIN_HZ]..[RichTapEngine.MAX_HZ]
 * （≈87..225Hz，见 [RichTapEngine]）。低于下限的目标频率无法用连续驱动复现，直接交给引擎只会被
 * 截断成同一个最低频，丢失想要的“低频/慢震”手感。
 *
 * 模拟方法：以目标频率的**周期**为间隔，重复发送**短促的脉冲**（每个脉冲是一条很短的
 * `continuous` 事件，快速起振后立即衰减）。脉冲的重复频率即目标低频，因此听感/触感是
 * “每 [periodMs] ms 来一下”，而不是连续的高频嗡嗡声。
 *
 * 约束来自真机 HAL（见 `docs/richtap-hd-vibration.md` §9）：单条效果最多 16 个事件、每事件
 * 曲线恰好 4 点、单事件时长 ≤ 5000ms。因此单个 [pattern] 最多覆盖 [MAX_PULSES] 个脉冲；
 * 持续播放需由调用方（如 `PhoneHdHaptics`）按 [coverageMs] 定时重投递。
 */
object RichTapLowFreq {

    /** 单个 HE 效果最多的事件数（真机 HAL 上限）。 */
    const val MAX_PULSES = 16

    /** 单个脉冲占空比：脉冲时长 = 周期 × 该比例。 */
    const val PULSE_RATIO = 0.35

    /** 脉冲最小时长，避免过短的攻击/衰减包络取整为 0。 */
    private const val MIN_PULSE_MS = 3

    /** 周期下限，防止异常高频输入产生 0ms 周期。 */
    private const val MIN_PERIOD_MS = 4.0

    /** [freqHz] 是否落在马达可用频段之下、需要用脉冲串模拟的低频。 */
    fun supports(freqHz: Double): Boolean =
        freqHz > 0.0 && freqHz.isFinite() && freqHz < RichTapEngine.MIN_HZ

    /** 目标低频对应的脉冲周期（ms）。 */
    fun periodMs(freqHz: Double): Double =
        (1000.0 / freqHz.coerceAtLeast(1.0)).coerceAtLeast(MIN_PERIOD_MS)

    /** 单个脉冲时长（ms）。 */
    fun pulseMs(freqHz: Double): Int =
        max(MIN_PULSE_MS, (periodMs(freqHz) * PULSE_RATIO).roundToInt())

    /** [durationMs] 内可容纳的脉冲数，钳制到 [MAX_PULSES]。 */
    fun pulseCount(freqHz: Double, durationMs: Int): Int {
        if (durationMs <= 0) return 1
        val period = periodMs(freqHz)
        val raw = floor(durationMs / period).toInt() + 1
        return min(MAX_PULSES, max(1, raw))
    }

    /** 单条 [pattern] 实际覆盖的时长（ms）：最后一个脉冲的起点 + 脉冲时长。 */
    fun coverageMs(freqHz: Double, durationMs: Int): Int {
        val count = pulseCount(freqHz, durationMs)
        val startOfLast = (count - 1) * periodMs(freqHz)
        return startOfLast.roundToInt() + pulseMs(freqHz)
    }

    /**
     * 构造一段模拟低频的脉冲串 HE 1.0 效果。
     *
     * @param freqHz     目标低频（Hz），应满足 [supports]。
     * @param durationMs 期望时长；单条效果最多覆盖 [MAX_PULSES] 个脉冲。
     * @param strength   强度 0-255（按引擎强度律换算为曲线峰值）。
     * @param carrierHe  每个脉冲的载波频率（HE 0-100）；缺省用谐振点以获得最大能量。
     */
    fun pattern(
        freqHz: Double,
        durationMs: Int,
        strength: Int,
        carrierHe: Int = RichTapEngine.HE_AT_RESONANCE,
    ): String {
        val period = periodMs(freqHz)
        val pulse = pulseMs(freqHz)
        val count = pulseCount(freqHz, durationMs)
        // 幅度-频率补偿：载波偏离谐振（HE 56）时抬升峰值，使不同载波下的实际位移一致。
        val peak = RichTapEngine.amplitudeToCurve(
            RichTapEngine.compensateNormalized(
                strength.coerceIn(0, 255) / 255.0,
                carrierHe.coerceIn(0, 100),
            ),
        )
        val attack = max(1, pulse / 4)
        val decay = max(attack + 1, (pulse * 7) / 10).coerceAtMost(pulse)
        val events = (0 until count).map { i ->
            RichTapHe.Event(
                relativeTimeMs = (i * period).roundToInt(),
                durationMs = pulse,
                baseFreq = carrierHe.coerceIn(0, 100),
                points = listOf(
                    RichTapHe.CurvePoint(0, 0.0, 0.0),
                    RichTapHe.CurvePoint(attack, peak, 0.0),
                    RichTapHe.CurvePoint(decay, peak * 0.35, 0.0),
                    RichTapHe.CurvePoint(pulse, 0.0, 0.0),
                ),
            )
        }
        return RichTapHe.pattern(events)
    }
}
