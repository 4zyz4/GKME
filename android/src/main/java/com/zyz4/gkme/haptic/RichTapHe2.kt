package com.zyz4.gkme.haptic

import com.zyz4.gkme.haptic.RichTapRawCodec.CurvePoint
import com.zyz4.gkme.haptic.RichTapRawCodec.EventParams
import com.zyz4.gkme.haptic.RichTapRawCodec.HeEvent
import com.zyz4.gkme.haptic.RichTapRawCodec.HePattern
import kotlin.math.roundToInt

/**
 * HE2.0 JSON 构造器。
 *
 * 与 [RichTapHe]（只产 HE1.0、每条曲线固定 4 点）不同，这里支持：
 * - 多个 `PatternList` 段，每段带 `AbsoluteTime`；
 * - 每事件 4–16 点曲线；
 * - 每事件 `Index`（多马达/左右执行器）；
 * - `transient` 事件。
 *
 * 设备是否真正支持这些特性取决于 RichTap core：type 2 且 core ≥ 24 才会走原生
 * HE2.0 raw；type 1 或 core ≤ 23 会在 [He2Converter] 中降级成 HE1.0。
 */
object RichTapHe2 {

    /** 一个 HE2.0 事件。`points` 对 transient 忽略。 */
    data class EventSpec(
        val relativeTimeMs: Int,
        val durationMs: Int,
        val baseFreq: Int,
        val points: List<RichTapHe.CurvePoint>,
        val index: Int = 0,
        val type: String = "continuous",
        val intensity: Int = 100,
    )

    /** 一个 HE2.0 pattern 段。 */
    data class PatternSpec(
        val absoluteTimeMs: Int,
        val events: List<EventSpec>,
    )

    /** 单段 HE2.0 效果（兼容 [RichTapHe.Event] 输入）。 */
    fun patternFromEvents(events: List<RichTapHe.Event>, absoluteTimeMs: Int = 0): String =
        pattern(
            events = events.map {
                EventSpec(
                    relativeTimeMs = it.relativeTimeMs,
                    durationMs = it.durationMs,
                    baseFreq = it.baseFreq,
                    points = it.points,
                )
            },
            absoluteTimeMs = absoluteTimeMs,
        )

    /** 单段 HE2.0 效果。 */
    fun pattern(events: List<EventSpec>, absoluteTimeMs: Int = 0): String =
        patternList(listOf(PatternSpec(absoluteTimeMs, events)))

    /** 多段 HE2.0 效果。 */
    fun patternList(patterns: List<PatternSpec>): String {
        val doc = HeDocument(
            metadata = HeMetadata(He2Json.VERSION_2),
            patterns = patterns.map { p ->
                HePattern(
                    absoluteTimeMs = p.absoluteTimeMs.coerceAtLeast(0),
                    events = p.events.map { it.toHeEvent() },
                )
            },
        )
        return He2Json.writeHe20(doc)
    }

    /** 恒定连续的 HE2.0 效果（4 点平坦曲线）。 */
    fun continuous(frequency: Int, durationMs: Int): String {
        val dur = durationMs.coerceAtLeast(1)
        return pattern(
            listOf(
                EventSpec(
                    relativeTimeMs = 0,
                    durationMs = dur,
                    baseFreq = frequency.coerceIn(0, 100),
                    points = flatPoints(dur, 1.0),
                )
            )
        )
    }

    /** 瞬态点击。 */
    fun transient(
        relativeTimeMs: Int = 0,
        strength: Int = 100,
        frequency: Int = RichTapFrequency.HE_AT_RESONANCE,
        index: Int = 0,
    ): EventSpec = EventSpec(
        relativeTimeMs = relativeTimeMs.coerceAtLeast(0),
        durationMs = 0,
        baseFreq = frequency.coerceIn(0, 100),
        points = emptyList(),
        index = index,
        type = "transient",
        intensity = strength.coerceIn(0, 100),
    )

    /** 4 点平坦曲线。 */
    fun flatPoints(durationMs: Int, intensity: Double = 1.0): List<RichTapHe.CurvePoint> {
        val dur = durationMs.coerceAtLeast(1)
        val v = intensity.coerceIn(0.0, 1.0)
        return listOf(
            RichTapHe.CurvePoint(0, v, 0.0),
            RichTapHe.CurvePoint(dur / 3, v, 0.0),
            RichTapHe.CurvePoint(2 * dur / 3, v, 0.0),
            RichTapHe.CurvePoint(dur, v, 0.0),
        )
    }

    private fun EventSpec.toHeEvent(): HeEvent {
        val type = if (this.type == "transient") "transient" else "continuous"
        val curve = if (type == "continuous") {
            points.map {
                CurvePoint(
                    timeMs = it.timeMs.coerceAtLeast(0),
                    scale = it.intensity.coerceIn(0.0, 1.0),
                    freqOffset = it.freqOffset.roundToInt(),
                )
            }
        } else {
            emptyList()
        }
        return HeEvent(
            type = type,
            relativeTimeMs = relativeTimeMs.coerceAtLeast(0),
            durationMs = if (type == "transient") 0 else durationMs.coerceIn(1, 5000),
            vibrationId = index.coerceAtLeast(0),
            params = EventParams(
                intensity = intensity.coerceIn(0, 100),
                frequency = baseFreq.coerceIn(-1, 100),
                curve = curve,
            ),
        )
    }
}
