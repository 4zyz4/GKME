package com.zyz4.gkme.haptic

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * RichTap HE `Frequency`（0-100）与实际驱动频率（Hz）之间的换算。
 *
 * **已由逆向取代经验标定**：真机引擎 `libaachaptics.so` 内部用一张 201 项 u16 表
 * （索引 = HE+50）把 HE Frequency 映射成载波控制字；该表单调、在 HE≥84 饱和于 400。
 * 以真机标定的谐振点 `HE 56 ≈ 170Hz` 对齐后，映射见 [RichTapEngine]。
 *
 * 对比旧的经验线性剖面（89/170/271Hz）：引擎实际在 HE≥84 饱和，最高只到
 * `170·400/302 ≈ 225Hz`，且中段斜率不均（50 以上更陡）。
 *
 * `shiftIntoRange` 仍按可用范围做八度搬移，范围取引擎的有效区间。
 */
object RichTapFrequency {

    /** 设备频率剖面：最小/谐振/最大频率（Hz）。 */
    data class Profile(val minHz: Double, val resonantHz: Double, val maxHz: Double)

    /** 设备剖面，取自引擎频率表的有效范围。 */
    val DEVICE: Profile = Profile(
        minHz = RichTapEngine.MIN_HZ,
        resonantHz = RichTapEngine.RESONANCE_HZ,
        maxHz = RichTapEngine.MAX_HZ,
    )

    /** 谐振频率对应的 HE 频率（RichTap 缺省值）。 */
    const val HE_AT_RESONANCE = RichTapEngine.HE_AT_RESONANCE

    /** 期望频率（Hz）→ HE Frequency（0-100），使用引擎频率表。 */
    fun hzToHe(hz: Double, profile: Profile = DEVICE): Int = RichTapEngine.hzToHe(hz)

    /** HE Frequency → 驱动频率（Hz），使用引擎频率表。 */
    fun heToHz(he: Int, profile: Profile = DEVICE): Double = RichTapEngine.heToHz(he)

    private val LN2 = ln(2.0)

    /** 连续（对数）刻度版本：`Hz = F0 · 2^((HE-56)/65)`，用于需要平滑外推的场景。 */
    fun hzToHeLog(hz: Double, f0: Double = DEVICE.resonantHz, unitsPerOctave: Double = 65.0): Int {
        if (hz <= 0.0) return 0
        val he = HE_AT_RESONANCE + unitsPerOctave * ln(hz / f0) / LN2
        return he.roundToInt().coerceIn(0, 100)
    }

    /**
     * 把频率按整数倍八度搬移到 [profile] 的可用范围内（保持音高类别）。
     *
     * 马达只能复现约 [Profile.minHz]..[Profile.maxHz]，低于/高于该范围的音高直接传给
     * HE 会被截断成同一个值（丢失音高变化）。按八度搬移能把例如 50-100 Hz 的低频素材
     * 移入马达最佳频段，同时保留音高轮廓。
     */
    fun shiftIntoRange(hz: Double, profile: Profile = DEVICE): Double {
        if (hz <= 0.0 || hz.isNaN() || hz.isInfinite()) return profile.resonantHz
        var f = hz
        while (f < profile.minHz) f *= 2.0
        while (f > profile.maxHz) f /= 2.0
        return f.coerceIn(profile.minHz, profile.maxHz)
    }
}
