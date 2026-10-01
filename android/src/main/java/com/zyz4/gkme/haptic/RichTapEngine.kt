package com.zyz4.gkme.haptic

import kotlin.math.pow

/**
 * 逆向自真实引擎 `libaachaptics.so`（"AAC richtap core v1.0.8"）的精确映射，
 * 用于让 `PCM -> HE -> (官方引擎) -> PCM -> LRA` 的往返尽量还原原 PCM。
 *
 * 依据（见 `docs/richtap-hd-vibration.md` 的逆向章节）：
 * - **强度律**：`FUN_00112820`(日志 `COONVERT_HED`) 里
 *   `amp = I * 1.14^((I-100)/10)`，`I ∈ [0,100]` 为有效强度
 *   （`I = Parameters.Intensity * Curve.Intensity / 100`）。
 *   即曲线强度到驱动幅度是 **次方律（≈平方）**，不是线性。
 *   本机 `Parameters.Intensity=100`，故 `Curve.Intensity=c` 时归一化输出
 *   `a = c * 1.14^(10(c-1))`。
 * - **频率律**：`_Z21aac_vibra_looper_post` 经 `FUN_00112820` 用一张 201 项 u16 表
 *   (`0x1165a4`，索引 = HE 值 + 50) 把 HE Frequency 映射成引擎的载波控制字；
 *   该字单调、在 HE≥84 饱和。以真机标定的 `HE 56 ≈ 170 Hz`（谐振）对齐后
 *   `Hz(HE) = 170 * word(HE) / word(56)`。
 *
 * 纯 JVM，便于单测与离线仿真对拍。
 */
object RichTapEngine {

    /** 谐振点对应的 HE Frequency（引擎缺省，`word=302 ≈ 170 Hz`）。 */
    const val HE_AT_RESONANCE = 56

    /** 谐振频率（Hz），取自真机标定 / `ro.odm.mm.vibrator.resonant_frequency`。 */
    const val RESONANCE_HZ = 170.0

    /**
     * 引擎 HE Frequency(0..100) → 载波控制字，逆向自 `libaachaptics.so` 的
     * 201 项表（索引 HE+50）。HE≥84 饱和于 400。
     */
    private val HE_WORD = intArrayOf(
        154, 159, 159, 164, 164, 169, 169, 174, 174, 179,
        179, 184, 184, 189, 189, 194, 194, 199, 199, 204,
        204, 209, 209, 214, 214, 220, 220, 225, 225, 230,
        230, 235, 235, 240, 240, 245, 245, 250, 250, 255,
        255, 260, 260, 265, 265, 270, 270, 275, 275, 280,
        280, 287, 287, 294, 294, 302, 302, 309, 309, 316,
        316, 323, 323, 330, 330, 338, 338, 345, 345, 352,
        352, 359, 359, 366, 366, 374, 374, 381, 381, 388,
        388, 395, 395, 400, 400, 400, 400, 400, 400, 400,
        400, 400, 400, 400, 400, 400, 400, 400, 400, 400,
        400,
    )

    private val WORD_AT_RESONANCE = HE_WORD[HE_AT_RESONANCE]

    private const val LAW_BASE = 1.14

    /** HE Frequency → 近似载波频率（Hz）。HE≥84 饱和。 */
    fun heToHz(he: Int): Double {
        val h = he.coerceIn(0, 100)
        return RESONANCE_HZ * HE_WORD[h] / WORD_AT_RESONANCE
    }

    /** 期望载波频率（Hz）→ HE Frequency（0..100），对 [heToHz] 求反。 */
    fun hzToHe(hz: Double): Int {
        if (hz.isNaN()) return HE_AT_RESONANCE
        val lo = heToHz(0)
        val hi = heToHz(100)
        val target = hz.coerceIn(lo, hi)
        var best = 0
        var bestErr = Double.MAX_VALUE
        var bestDist = Int.MAX_VALUE
        for (h in 0..100) {
            val err = kotlin.math.abs(heToHz(h) - target)
            val dist = kotlin.math.abs(h - HE_AT_RESONANCE)
            // 表里有连续重复值（如 HE 55/56 同为 302、HE 83..100 同为 400），并列时取
            // 最靠近谐振 HE 56 的那个，保证 `hzToHe(heToHz(56)) == 56`。
            if (err < bestErr - 1e-9 || (err <= bestErr + 1e-9 && dist < bestDist)) {
                bestErr = err
                best = h
                bestDist = dist
            }
        }
        return best
    }

    /** 引擎频率映射的有效范围（Hz）。 */
    val MIN_HZ: Double get() = heToHz(0)
    val MAX_HZ: Double get() = heToHz(100)

    /**
     * 曲线强度 `c`（0..1，相对 `Parameters.Intensity=100`）→ 归一化驱动幅度（0..1）。
     * 对应引擎的 `a = c * 1.14^(10(c-1))`。
     */
    fun curveToAmplitude(c: Double): Double {
        val cc = c.coerceIn(0.0, 1.0)
        return cc * LAW_BASE.pow(10.0 * (cc - 1.0))
    }

    /**
     * 期望归一化驱动幅度 `a`（0..1）→ 曲线强度 `c`（0..1），即 [curveToAmplitude] 的逆。
     * 用二分求解 `c * 1.14^(10(c-1)) = a`（该函数在 [0,1] 上单调）。
     */
    fun amplitudeToCurve(a: Double): Double {
        if (a <= 0.0) return 0.0
        if (a >= 1.0) return 1.0
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) * 0.5
            if (curveToAmplitude(mid) < a) lo = mid else hi = mid
        }
        return (lo + hi) * 0.5
    }
}
