package com.zyz4.gkme.haptic

import com.zyz4.gkme.haptic.RichTapRawCodec.CurvePoint
import com.zyz4.gkme.haptic.RichTapRawCodec.EventParams
import com.zyz4.gkme.haptic.RichTapRawCodec.HeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SDK-compatible HE2.0 conversion primitives. */
class He2ConverterTest {

    private fun point(t: Int, v: Double, f: Int = 0) = CurvePoint(t, v, f)

    private fun continuous(
        rel: Int,
        dur: Int,
        intensity: Int = 100,
        freq: Int = 56,
        curve: List<CurvePoint> = listOf(point(0, 1.0), point(dur, 1.0)),
        index: Int = 0,
    ) = HeEvent(
        type = "continuous",
        relativeTimeMs = rel,
        durationMs = dur,
        vibrationId = index,
        params = EventParams(intensity, freq, curve),
    )

    private fun transient(rel: Int, freq: Int = 56, index: Int = 0) = HeEvent(
        type = "transient",
        relativeTimeMs = rel,
        durationMs = 0,
        vibrationId = index,
        params = EventParams(100, freq, emptyList()),
    )

    @Test
    fun trimCurveTo4pMatchesSdkAveraging() {
        val curve = listOf(
            point(0, 0.0), point(11, 0.38), point(33, 0.044387), point(43, 0.58),
            point(53, 0.12), point(84, 0.93), point(133, 0.15797), point(149, 1.0),
            point(167, 0.33416), point(188, 1.0), point(200, 0.37425), point(220, 1.0),
            point(233, 0.46612), point(250, 0.0),
        )
        val out = He2Converter.trimCurveTo4p(curve)
        assertEquals(4, out.size)
        assertEquals(0, out[0].timeMs)
        assertEquals(59, out[1].timeMs)
        assertEquals(0.3687, out[1].scale, 0.01)
        assertEquals(192, out[2].timeMs)
        assertEquals(0.6958, out[2].scale, 0.01)
        assertEquals(250, out[3].timeMs)
    }

    @Test
    fun sdkTrimDoesNotPadThreePointsButBestEffortDoes() {
        val curve = listOf(point(0, 0.0), point(100, 1.0), point(200, 0.0))
        assertEquals(3, He2Converter.trimCurveTo4p(curve).size)

        val padded = He2Converter.padCurveTo4p(curve, 200)
        assertEquals(4, padded.size)
        assertEquals(0, padded[0].timeMs)
        assertEquals(200, padded[3].timeMs)
        assertEquals(0.0, padded[3].scale, 1e-9)
    }

    @Test
    fun resampleTo16KeepsEndpointsAndMonotonicTime() {
        val curve = (0 until 40).map { point(it * 10, if (it % 2 == 0) 0.0 else 1.0) }
        val out = He2Converter.normalizeCurveForHe20(curve, 390)
        assertEquals(16, out.size)
        assertEquals(0, out.first().timeMs)
        assertEquals(390, out.last().timeMs)
        for (i in 1 until out.size) assertTrue(out[i].timeMs >= out[i - 1].timeMs)
    }

    @Test
    fun overlapContinuousIsTruncatedToRamp() {
        val a = continuous(0, 300)
        val b = continuous(100, 300, curve = listOf(point(0, 0.2), point(300, 0.2)), freq = 80)
        val out = He2Converter.trimOverlaps(listOf(a, b))
        assertEquals(2, out.size)
        val t = out[1]
        assertEquals(300, t.relativeTimeMs)
        assertEquals(100, t.durationMs)
        assertEquals(4, t.params.curve.size)
        assertEquals(0.0, t.params.curve.first().scale, 1e-9)
        assertEquals(1.0, t.params.curve[1].scale, 1e-9)
        assertEquals(1.0, t.params.curve[2].scale, 1e-9)
        assertEquals(0.0, t.params.curve.last().scale, 1e-9)
    }

    @Test
    fun containedOrTransientOverlapIsDropped() {
        val a = continuous(0, 300)
        val contained = continuous(100, 100)
        assertEquals(1, He2Converter.trimOverlaps(listOf(a, contained)).size)

        val tr = transient(100)
        assertEquals(1, He2Converter.trimOverlaps(listOf(a, tr)).size)
    }

    @Test
    fun indexTwoEventsAreDropped() {
        val a = continuous(0, 300)
        val b = continuous(400, 100, index = 2)
        val out = He2Converter.trimOverlaps(listOf(a, b))
        assertEquals(1, out.size)
        assertEquals(0, out[0].relativeTimeMs)
    }

    @Test
    fun nonOverlappingEventsAreKept() {
        val out = He2Converter.trimOverlaps(listOf(continuous(0, 100), continuous(100, 100)))
        assertEquals(2, out.size)
    }

    @Test
    fun bestEffortHe20DropsIndexTwo() {
        val doc = HeDocument(
            HeMetadata(2),
            listOf(
                RichTapRawCodec.HePattern(
                    0,
                    listOf(
                        continuous(0, 100, index = 2),
                        continuous(200, 100, index = 0),
                    ),
                )
            ),
        )
        val he10 = He2Converter.toHe10BestEffort(doc)!!
        assertEquals(1, he10.patterns[0].events.size)
        assertEquals(0, he10.patterns[0].events[0].vibrationId)
        assertEquals(200, he10.patterns[0].events[0].relativeTimeMs)
    }

    @Test
    fun normalizeForHe20FixesTransientDurationFrequencyAndCaps() {
        val events = List(20) { i ->
            if (i == 0) {
                HeEvent(
                    type = "transient",
                    relativeTimeMs = 0,
                    durationMs = 0,
                    vibrationId = 0,
                    params = EventParams(120, 150, emptyList()),
                )
            } else {
                continuous(i * 10, 100, intensity = 120, curve = List(17) { point(it * 5, 1.0) })
            }
        }
        val doc = HeDocument(
            HeMetadata(2),
            listOf(RichTapRawCodec.HePattern(0, events)),
        )
        val out = He2Converter.normalizeForHe20(doc)!!
        assertEquals(16, out.patterns[0].events.size)
        val first = out.patterns[0].events[0]
        assertEquals(48, first.durationMs)
        assertEquals(100, first.params.frequency)
        assertEquals(100, first.params.intensity)
        val cont = out.patterns[0].events[1]
        assertEquals(16, cont.params.curve.size)
    }
}
