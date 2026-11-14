package com.zyz4.gkme.haptic

/**
 * 把 HE 文档（HE1.0 / HE2.0）转换成 **Android 16（API 36）PWLE 包络控制点**。
 *
 * PWLE = Piece-Wise Linear Envelope：`VibrationEffect.WaveformEnvelopeBuilder` 用
 * `(amplitude 0..1, frequencyHz, durationMs)` 描述一条分段线性包络，由系统/HAL 直接驱动
 * LRA，不经过 RichTap 的 HE 引擎。相比 HE：
 * - **频率是真实 Hz**，不再经过 HE 0..100 的机型私有标定；这里按设备
 *   `VibratorFrequencyProfile`（min/max/resonant）重新映射 HE 的音高轮廓；
 * - **振幅是线性输出强度**，不需要 RichTap 的 `1.14^…` 次方律与机械 Q 补偿；
 * - 没有“每事件必须 4 点 / 16 事件”的厂商限制，控制点数量/时长由
 *   `VibratorEnvelopeEffectInfo` 给出，框架会自动拆分超限效果。
 *
 * 因此本类负责：
 * 1. 把 HE 事件时间轴（多 pattern + `AbsoluteTime` + 事件内曲线）拍平成一条绝对时间轴；
 * 2. 事件之间的空隙插入 0 幅控制点，避免线性插值把静音段“填平”；
 * 3. 把 `Curve.Intensity`（相对 0..1）按引擎强度律还原成实际归一化幅度；
 * 4. 把 HE 频率按设备 `min/resonant/max` 重新映射（HE 56 = 谐振点）；
 * 5. 处理重叠事件（PCM 起点过冲、低频脉冲等）取同一时刻的最大幅度；
 * 6. 收尾淡出、控制点时长上下限、总时长上限与点数上限。
 *
 * 纯 JVM，可单测。
 */
object PwleEncoder {

    /** 一个 PWLE 控制点：目标幅度 0..1、目标频率 Hz、到达该点所需时长 ms。 */
    data class ControlPoint(
        val amplitude: Float,
        val frequencyHz: Float,
        val durationMs: Long,
    )

    /**
     * 设备能力与编码约束，来自 `Vibrator.getFrequencyProfile()` /
     * `Vibrator.getEnvelopeEffectInfo()`。
     *
     * @param minFrequencyHz      设备支持的频率下限（Hz）。
     * @param maxFrequencyHz      设备支持的频率上限（Hz）。
     * @param resonanceHz         设备谐振频率（Hz）；未知传 NaN，则 HE 0..100 线性映射到 [min,max]。
     * @param minPointDurationMs  单个控制点的最短过渡时长。
     * @param maxPointDurationMs  单个控制点的最长过渡时长。
     * @param maxDurationMs       单条效果的总时长上限；≤0 表示不限制。
     * @param maxPoints           单条效果的建议控制点数上限；≤0 表示不限制（交给框架拆分）。
     */
    data class Limits(
        val minFrequencyHz: Float,
        val maxFrequencyHz: Float,
        val resonanceHz: Float = Float.NaN,
        val minPointDurationMs: Long = 1L,
        val maxPointDurationMs: Long = 10_000L,
        val maxDurationMs: Long = 60_000L,
        val maxPoints: Int = 0,
    ) {
        fun isValid(): Boolean =
            minFrequencyHz.isFinite() && maxFrequencyHz.isFinite() &&
                minFrequencyHz > 0f && maxFrequencyHz > minFrequencyHz
    }

    /** HE2.0 transient 省略 Duration 时的默认值（SDK 语义）。 */
    private const val TRANSIENT_MS = 48L

    /** 效果收尾淡出时长（ms），避免在非零幅度处突然停止产生“咔哒”。 */
    private const val FADE_OUT_MS = 5L

    /** HE 频率刻度上限。 */
    private const val HE_MAX = 100

    /**
     * HE 频率（0..100）→ 设备频率（Hz）。
     *
     * HE 56 是 RichTap 的谐振点；有设备谐振频率时按 `[min, resonance]` / `[resonance, max]`
     * 两段线性映射，使音高轮廓与“谐振最强”语义在任意机型上保持一致。未知谐振频率时
     * 退化为 `[min, max]` 的线性映射。
     */
    fun heToDeviceHz(he: Int, limits: Limits): Float {
        val h = he.coerceIn(0, HE_MAX)
        val min = limits.minFrequencyHz
        val max = limits.maxFrequencyHz
        val res = limits.resonanceHz
        return if (res.isFinite() && res > min && res < max) {
            if (h <= RichTapEngine.HE_AT_RESONANCE) {
                min + (res - min) * (h.toFloat() / RichTapEngine.HE_AT_RESONANCE)
            } else {
                res + (max - res) *
                    ((h - RichTapEngine.HE_AT_RESONANCE).toFloat() /
                        (HE_MAX - RichTapEngine.HE_AT_RESONANCE))
            }
        } else {
            min + (max - min) * (h.toFloat() / HE_MAX)
        }
    }

    /**
     * 把 HE 文档编码成 PWLE 控制点列表。
     *
     * @param document HE1.0/HE2.0 文档。
     * @param limits   设备能力与约束。
     * @param gain     全局幅度缩放（0..1，对应 HapticPlayer 的 0..255 全局 amplitude）；
     *                 `startEffect` 传 1。
     * @return 控制点列表；没有有效事件或设备能力无效时返回空列表。
     */
    fun encode(document: HeDocument, limits: Limits, gain: Float = 1f): List<ControlPoint> {
        if (!limits.isValid()) return emptyList()
        val g = gain.coerceIn(0f, 1f)
        val segments = buildSegments(document, limits, g)
        if (segments.isEmpty()) return emptyList()

        val intervals = mergeCoverage(segments)
        val minPoint = limits.minPointDurationMs.coerceAtLeast(1L)
        val maxPoint = limits.maxPointDurationMs.coerceAtLeast(minPoint)

        val out = ArrayList<ControlPoint>()
        var cursorMs = 0L
        var lastFreq = heToDeviceHz(RichTapEngine.HE_AT_RESONANCE, limits)

        for ((index, interval) in intervals.withIndex()) {
            val points = segments
                .filter { it.startMs >= interval.startMs && it.startMs < interval.endMs }
                .flatMap { it.points }
                .sortedWith(compareBy<RawPoint> { it.timeMs }.thenByDescending { it.amplitude })
                .distinctBy { it.timeMs }
            if (points.isEmpty()) continue

            // 间隔/开头：先保持 0 幅度，避免从上一条效果的电平一路线性爬到本节开头。
            val gap = interval.startMs - cursorMs
            if (index > 0 || gap > 0L) {
                val duration = maxOf(gap, minPoint)
                out.add(ControlPoint(0f, lastFreq, duration.coerceAtMost(maxPoint)))
                cursorMs += duration
            }

            for (p in points) {
                var delta = p.timeMs - cursorMs
                if (delta <= 0L) delta = minPoint
                val duration = delta.coerceIn(minPoint, maxPoint)
                out.add(ControlPoint(p.amplitude, p.frequencyHz, duration))
                cursorMs += duration
                lastFreq = p.frequencyHz
            }

            // 有下一条覆盖区间时先淡出；没有空隙（下一段紧邻）则交给下一段的 0 点处理。
            val nextStart = intervals.getOrNull(index + 1)?.startMs
            if (nextStart != null) {
                val available = nextStart - cursorMs
                val fade = minOf(FADE_OUT_MS, available)
                if (fade >= minPoint) {
                    out.add(ControlPoint(0f, lastFreq, fade.coerceAtMost(maxPoint)))
                    cursorMs += fade
                }
            }
        }

        if (out.isEmpty()) return emptyList()
        // 收尾：PWLE 不会自动淡出，最后一点必须回到 0。
        if (out.last().amplitude > 0f) {
            val fade = maxOf(FADE_OUT_MS, minPoint).coerceAtMost(maxPoint)
            out.add(ControlPoint(0f, lastFreq, fade))
        }

        var result = splitLongPoints(out, maxPoint)
        result = capDuration(result, limits.maxDurationMs, minPoint)
        result = downsample(result, limits.maxPoints)
        return result
    }

    // ── HE 事件 → 绝对时间轴 ─────────────────────────────────────────────

    private data class RawPoint(val timeMs: Long, val amplitude: Float, val frequencyHz: Float)

    private data class Segment(
        val startMs: Long,
        val endMs: Long,
        val points: List<RawPoint>,
    )

    private fun buildSegments(document: HeDocument, limits: Limits, gain: Float): List<Segment> {
        val segments = ArrayList<Segment>()
        for (pattern in document.patterns) {
            val baseMs = pattern.absoluteTimeMs.toLong().coerceAtLeast(0L)
            for (event in pattern.events) {
                val start = baseMs + event.relativeTimeMs.toLong().coerceAtLeast(0L)
                val duration = if (event.type == "transient") {
                    event.durationMs.toLong().takeIf { it > 0L } ?: TRANSIENT_MS
                } else {
                    event.durationMs.toLong().coerceAtLeast(1L)
                }
                val end = start + duration
                val baseHe = if (event.params.frequency < 0) {
                    RichTapEngine.HE_AT_RESONANCE
                } else {
                    event.params.frequency.coerceIn(0, HE_MAX)
                }
                val baseLevel = event.params.intensity.coerceIn(0, 100) / 100.0

                val points = ArrayList<RawPoint>()
                if (event.type == "transient" || event.params.curve.isEmpty()) {
                    // transient：单次冲击；无曲线的 continuous：保持到事件结束。
                    points.add(RawPoint(start, amplitude(baseLevel, 1.0, gain), hz(baseHe, limits)))
                    if (event.type == "transient") {
                        points.add(RawPoint(end, 0f, hz(baseHe, limits)))
                    } else {
                        points.add(RawPoint(end, amplitude(baseLevel, 1.0, gain), hz(baseHe, limits)))
                    }
                } else {
                    val curve = event.params.curve
                    for (p in curve) {
                        val t = start + p.timeMs.toLong().coerceIn(0L, duration)
                        val he = (baseHe + p.freqOffset).coerceIn(0, HE_MAX)
                        points.add(RawPoint(t, amplitude(baseLevel, p.scale, gain), hz(he, limits)))
                    }
                    // 曲线最后一点若早于事件结束，补一个保持点，让电平维持到 end。
                    val last = curve.last()
                    if (start + last.timeMs.toLong() < end) {
                        val he = (baseHe + last.freqOffset).coerceIn(0, HE_MAX)
                        points.add(RawPoint(end, amplitude(baseLevel, last.scale, gain), hz(he, limits)))
                    }
                }
                segments.add(Segment(start, end, points))
            }
        }
        return segments.sortedBy { it.startMs }
    }

    /** `Curve.Intensity`（相对 0..1）→ 归一化驱动幅度，再乘全局 gain。 */
    private fun amplitude(intensity: Double, scale: Double, gain: Float): Float =
        (RichTapEngine.curveToAmplitude(intensity * scale) * gain)
            .toFloat()
            .coerceIn(0f, 1f)

    private fun hz(he: Int, limits: Limits): Float = heToDeviceHz(he, limits)

    /** 合并重叠/相接的覆盖区间，得到“马达应有输出”的时间段。 */
    private fun mergeCoverage(segments: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        var current: Segment? = null
        for (s in segments.sortedBy { it.startMs }) {
            val c = current
            if (c == null) {
                current = s
                continue
            }
            if (s.startMs <= c.endMs) {
                if (s.endMs > c.endMs) current = Segment(c.startMs, s.endMs, emptyList())
            } else {
                out.add(c)
                current = s
            }
        }
        current?.let(out::add)
        return out
    }

    // ── 约束处理 ─────────────────────────────────────────────────────────

    /** 单点过渡时长超过设备上限时均分，并线性插值幅度/频率。 */
    private fun splitLongPoints(points: List<ControlPoint>, maxPoint: Long): List<ControlPoint> {
        if (maxPoint <= 0L) return points
        val out = ArrayList<ControlPoint>(points.size)
        var prevAmp = 0f
        var prevFreq = points.firstOrNull()?.frequencyHz ?: 0f
        for (p in points) {
            if (p.durationMs <= maxPoint) {
                out.add(p)
            } else {
                val n = ((p.durationMs + maxPoint - 1) / maxPoint).toInt().coerceAtLeast(1)
                var remain = p.durationMs
                for (k in 1..n) {
                    val f = k.toFloat() / n
                    val d = if (k == n) remain else p.durationMs / n
                    out.add(
                        ControlPoint(
                            prevAmp + (p.amplitude - prevAmp) * f,
                            prevFreq + (p.frequencyHz - prevFreq) * f,
                            d,
                        )
                    )
                    remain -= d
                }
            }
            prevAmp = p.amplitude
            prevFreq = p.frequencyHz
        }
        return out
    }

    /** 截断到设备总时长上限；截断后若仍非零则补一个淡出点。 */
    private fun capDuration(points: List<ControlPoint>, maxDuration: Long, minPoint: Long): List<ControlPoint> {
        if (maxDuration <= 0L) return points
        val out = ArrayList<ControlPoint>(points.size)
        var total = 0L
        for (p in points) {
            if (total >= maxDuration) break
            val d = minOf(p.durationMs, maxDuration - total)
            if (d < minPoint) break
            out.add(p.copy(durationMs = d))
            total += d
        }
        if (out.isNotEmpty() && out.last().amplitude > 0f) {
            out.add(ControlPoint(0f, out.last().frequencyHz, minPoint))
        }
        return out
    }

    /** 控制点超过设备建议上限时均匀抽稀，把被丢弃点的时长并入保留点。 */
    private fun downsample(points: List<ControlPoint>, maxPoints: Int): List<ControlPoint> {
        if (maxPoints <= 0 || points.size <= maxPoints) return points
        if (maxPoints == 1) return listOf(points.last())
        val keep = sortedSetOf<Int>()
        val last = points.size - 1
        val stride = last.toDouble() / (maxPoints - 1)
        for (k in 0 until maxPoints) keep.add((k * stride).toInt().coerceIn(0, last))
        keep.add(last)
        val out = ArrayList<ControlPoint>(keep.size)
        var acc = 0L
        for (i in points.indices) {
            acc += points[i].durationMs
            if (i in keep) {
                out.add(points[i].copy(durationMs = acc.coerceAtLeast(1L)))
                acc = 0L
            }
        }
        return out
    }
}
