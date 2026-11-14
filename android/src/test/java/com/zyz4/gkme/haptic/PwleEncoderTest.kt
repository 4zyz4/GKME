package com.zyz4.gkme.haptic

import com.zyz4.gkme.haptic.RichTapRawCodec.CurvePoint
import com.zyz4.gkme.haptic.RichTapRawCodec.EventParams
import com.zyz4.gkme.haptic.RichTapRawCodec.HeEvent
import com.zyz4.gkme.haptic.RichTapRawCodec.HePattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** JVM tests for the HE -> Android 16 PWLE control-point adapter. */
class PwleEncoderTest {

    private val limits = PwleEncoder.Limits(
        minFrequencyHz = 50f,
        maxFrequencyHz = 300f,
        resonanceHz = 150f,
        minPointDurationMs = 1L,
        maxPointDurationMs = 10_000L,
        maxDurationMs = 60_000L,
        maxPoints = 0,
    )

    private fun point(t: Int, scale: Double, offset: Int = 0) = CurvePoint(t, scale, offset)

    private fun continuous(
        rel: Int,
        duration: Int,
        frequency: Int,
        curve: List<CurvePoint>,
        intensity: Int = 100,
    ) = HeEvent(
        type = "continuous",
        relativeTimeMs = rel,
        durationMs = duration,
        vibrationId = 0,
        params = EventParams(intensity, frequency, curve),
    )

    private fun document(vararg events: HeEvent) = HeDocument(
        HeMetadata(1, "test", "pwle"),
        listOf(HePattern(0, events.toList())),
    )

    private fun flat(duration: Int, value: Double = 1.0) =
        listOf(point(0, value), point(duration / 3, value), point(duration * 2 / 3, value), point(duration, value))

    @Test
    fun mapsHeFrequencyThroughDeviceProfile() {
        assertEquals(50f, PwleEncoder.heToDeviceHz(0, limits), 0.01f)
        assertEquals(150f, PwleEncoder.heToDeviceHz(56, limits), 0.01f)
        assertEquals(300f, PwleEncoder.heToDeviceHz(100, limits), 0.01f)
        // 0..56 线性映射，28 恰好在 min 与 resonant 的中点。
        assertEquals(100f, PwleEncoder.heToDeviceHz(28, limits), 0.01f)
    }

    @Test
    fun constantContinuousBecomesFlatEnvelopeWithFadeOut() {
        val points = PwleEncoder.encode(
            document(continuous(0, 100, 56, flat(100))),
            limits,
        )
        assertTrue(points.size >= 3)
        assertEquals(1f, points.first().amplitude, 0.001f)
        assertEquals(150f, points.first().frequencyHz, 0.01f)
        assertEquals(0f, points.last().amplitude, 0.001f)
        assertEquals(105L, points.sumOf { it.durationMs })
    }

    @Test
    fun gainScalesAmplitude() {
        val points = PwleEncoder.encode(
            document(continuous(0, 100, 56, flat(100))),
            limits,
            gain = 0.5f,
        )
        assertEquals(0.5f, points.first().amplitude, 0.001f)
    }

    @Test
    fun separatedEventsGetSilenceInTheGap() {
        val points = PwleEncoder.encode(
            document(
                continuous(0, 50, 56, flat(50)),
                continuous(50, 50, 56, flat(50)),
            ),
            limits,
        )
        // 两个事件相接（0-50、50-100）应合并为一条连续覆盖，不插入静音。
        assertTrue(points.none { it.amplitude == 0f && it.durationMs > 10L })
        assertEquals(105L, points.sumOf { it.durationMs })

        val gapped = PwleEncoder.encode(
            document(
                continuous(0, 50, 56, flat(50)),
                continuous(100, 50, 56, flat(50)),
            ),
            limits,
        )
        // 50..100ms 的空隙必须用 0 幅度填充，总时长约 105 + 50 + 5。
        assertEquals(155L, gapped.sumOf { it.durationMs })
        assertTrue(gapped.count { it.amplitude == 0f } >= 2)
    }

    @Test
    fun transientBecomesPeakThenZero() {
        val transient = HeEvent(
            type = "transient",
            relativeTimeMs = 0,
            durationMs = 48,
            vibrationId = 0,
            params = EventParams(100, 56, emptyList()),
        )
        val points = PwleEncoder.encode(document(transient), limits)
        assertEquals(1f, points.first().amplitude, 0.001f)
        assertEquals(0f, points.last().amplitude, 0.001f)
        assertEquals(48L, points.sumOf { it.durationMs })
    }

    @Test
    fun curveFrequencyOffsetStaysInsideDeviceRange() {
        val points = PwleEncoder.encode(
            document(
                continuous(
                    0,
                    100,
                    56,
                    listOf(
                        point(0, 1.0, 0),
                        point(33, 1.0, 60),
                        point(66, 1.0, 60),
                        point(100, 1.0, 60),
                    ),
                )
            ),
            limits,
        )
        assertTrue(points.all { it.frequencyHz in 50f..300f })
    }

    @Test
    fun invalidLimitsYieldNoPoints() {
        assertTrue(
            PwleEncoder.encode(
                document(continuous(0, 100, 56, flat(100))),
                PwleEncoder.Limits(Float.NaN, Float.NaN),
            ).isEmpty()
        )
    }

    @Test
    fun maxDurationIsRespected() {
        val points = PwleEncoder.encode(
            document(continuous(0, 5000, 56, flat(5000))),
            limits.copy(maxDurationMs = 1000L),
        )
        assertTrue(points.sumOf { it.durationMs } <= 1001L)
        assertTrue(abs(points.last().amplitude) < 1e-6f)
    }
}
