package com.zyz4.gkme.haptic

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 *   (`0x1165a4`，索引 = HE 值 + 50) 把 HE Frequency 映射成引擎的载波控制字。早期按
 *   `Hz(HE) = 170 * word(HE) / word(56)` 线性逆推，但**真机加速度计逐点标定**显示两端
 *   偏差很大（HE0 实测 107Hz 而非 87Hz、HE100 实测 280Hz 而非饱和 225Hz），故改为直接
 *   使用实测 [HE_HZ] 表（见下）。
 *
 * 纯 JVM，便于单测与离线仿真对拍。
 */
object RichTapEngine {

    /** 谐振点对应的 HE Frequency（引擎缺省，实测 ≈169Hz）。 */
    const val HE_AT_RESONANCE = 56

    /**
     * 谐振频率（Hz）= `heToHz([HE_AT_RESONANCE])`（实测标定：HE 56 ≈ 169.1Hz）。
     * 用于幅度-频率补偿的 f0，使补偿在 HE 56 处恰好为 1。
     */
    const val RESONANCE_HZ = 169.1

    /**
     * 引擎 HE Frequency(0..100) → **实测机械频率**（Hz），索引即 HE。
     *
     * **真机加速度计逐点标定**（设备 `manet` / Snapdragon 8 Gen 3）：单条稳态
     * `continuous` 效果 + 加速度计（与 LRA 刚性耦合，fs≈486Hz）测每个 HE 的主频。
     * HE>~82 处基频超过加速度计 Nyquist（≈243Hz），按 `f_true = fs − f_peak` 还原混叠，
     * 并与设备侧麦克风标定交叉验证（HE90≈255、HE95≈270、HE100≈280）。
     */
    private val HE_HZ = doubleArrayOf(
        107.2, 109.0, 109.0, 111.1, 111.1, 113.1, 113.1, 114.9, 114.9, 116.1,
        116.1, 118.0, 118.0, 120.1, 120.1, 121.8, 121.9, 124.1, 124.1, 125.8,
        125.8, 127.8, 128.0, 129.8, 130.0, 131.1, 131.1, 132.8, 132.9, 135.0,
        135.1, 136.8, 136.8, 138.8, 139.1, 141.1, 141.1, 142.8, 142.9, 145.0,
        145.1, 145.8, 146.0, 148.0, 148.1, 149.8, 149.8, 152.0, 152.0, 153.7,
        153.7, 158.9, 159.0, 163.7, 164.0, 169.1, 169.1, 173.8, 173.8, 178.9,
        178.9, 183.8, 183.9, 189.1, 189.1, 193.9, 193.9, 199.0, 199.0, 203.9,
        204.0, 209.0, 209.0, 214.0, 214.0, 220.2, 220.2, 224.8, 224.8, 230.1,
        230.1, 234.9, 235.1, 238.1, 241.1, 244.0, 247.0, 249.9, 249.9, 255.0,
        255.0, 260.0, 260.0, 265.0, 265.0, 270.0, 270.0, 275.1, 275.1, 280.0,
        280.3,
    )

    private const val LAW_BASE = 1.14

    /** HE Frequency → 实测机械频率（Hz），见 [HE_HZ]。 */
    fun heToHz(he: Int): Double = HE_HZ[he.coerceIn(0, 100)]

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
            // 表里有连续重复值（如 HE 55/56 同为 169.1、HE 99/100 同为 280），并列时取
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

    // ── 幅度-频率补偿 ──
    //
    // LRA 是窄带谐振器：同样的驱动强度，**偏离谐振**（HE 56 ≈ 170Hz）时机械位移会显著下降，
    // 于是同一个振幅数值在不同 HE 频率下实际强弱不一（HE 56 最大，更高/更低都变小）。
    // 这里用欠阻尼受迫振动的位移响应**取反**做预补偿，把目标幅度折算成“实际位移尽量与频率
    // 无关”的驱动幅度。补偿后仍在曲线强度上限（1.0）处封顶——谐振点满幅时没有余量，
    // 因此高幅 + 偏离谐振无法完全拉平（见 docs 的说明）。

    /** 机械品质因数 Q 的默认估计（真机可标定；越大谐振越尖锐、偏离时衰减越快）。 */
    const val MECHANICAL_Q = 10.0

    /** 频率补偿增益上限，避免偏离谐振时把驱动推向失真/不可控区。 */
    const val MAX_FREQ_COMPENSATION = 2.0

    /**
     * LRA 归一化位移响应（相对谐振点）。恒定驱动力下，欠阻尼受迫振动位移
     * `X(r) ∝ 1 / sqrt((1-r²)² + (2ζr)²)`，`r = f/f0`、`2ζ = 1/Q`；归一化到谐振
     * （`r = 1`）为 **1.0**，其余处 < 1（本机在 HE [HE_AT_RESONANCE] 最大）。
     *
     * @param he HE 频率 0-100（经 [heToHz] 换算为 f，f0 = [RESONANCE_HZ]）。
     * @param q  机械品质因数 [MECHANICAL_Q]。
     */
    fun resonanceResponse(he: Int, q: Double = MECHANICAL_Q): Double {
        val r = heToHz(he) / RESONANCE_HZ
        val zeta2 = 1.0 / q.coerceAtLeast(1e-3)
        val denom = sqrt((1.0 - r * r).pow(2) + (zeta2 * r).pow(2))
        if (denom <= 1e-12) return 1.0
        return (zeta2 / denom).coerceIn(0.0, 1.0)
    }

    /**
     * 幅度频率补偿增益：`1 / [resonanceResponse]`，谐振处 = 1，偏离谐振 > 1，上限 [maxBoost]。
     * 用于把目标驱动幅度折算成“实际位移恒定”的驱动幅度。
     */
    fun frequencyCompensation(
        he: Int,
        q: Double = MECHANICAL_Q,
        maxBoost: Double = MAX_FREQ_COMPENSATION,
    ): Double {
        val resp = resonanceResponse(he, q).coerceAtLeast(1e-3)
        return (1.0 / resp).coerceIn(1.0, maxBoost.coerceAtLeast(1.0))
    }

    /**
     * 对归一化目标幅度（0..1）做频率补偿，返回补偿后的归一化幅度（0..1，曲线强度上限决定封顶）。
     * 谐振处不变；偏离谐振处按 [frequencyCompensation] 抬升，达到上限后封顶。
     */
    fun compensateNormalized(
        a: Double,
        he: Int,
        q: Double = MECHANICAL_Q,
        maxBoost: Double = MAX_FREQ_COMPENSATION,
    ): Double = (a * frequencyCompensation(he, q, maxBoost)).coerceIn(0.0, 1.0)

    /** 对 0-255 目标幅度做频率补偿（见 [compensateNormalized]）。 */
    fun compensate255(
        amplitude: Int,
        he: Int,
        q: Double = MECHANICAL_Q,
        maxBoost: Double = MAX_FREQ_COMPENSATION,
    ): Int = (compensateNormalized(amplitude.coerceIn(0, 255) / 255.0, he, q, maxBoost) * 255.0)
        .roundToInt()
        .coerceIn(0, 255)
}
