package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the HE 1.0 JSON builder. */
class RichTapHeTest {

    @Test
    fun curve_hasBaseFrequencyAndOffsets() {
        val json = RichTapHe.curve(
            500, 56,
            listOf(
                RichTapHe.CurvePoint(125, 1.0, 10.0),
                RichTapHe.CurvePoint(250, 0.5, -20.0),
                RichTapHe.CurvePoint(375, 0.8, 30.0),
                RichTapHe.CurvePoint(500, 0.0, 0.0),
            ),
        )
        assertTrue(json.contains("\"Duration\":500"))
        assertTrue(json.contains("\"Frequency\":56"))
        assertTrue(json.contains("\"Type\":\"continuous\""))
        // 4 个控制点
        assertTrue(json.contains("\"Frequency\":10.0"))
        assertTrue(json.contains("\"Frequency\":-20.0"))
        assertTrue(json.contains("\"Intensity\":0.5"))
    }

    @Test
    fun curve_trimsToFourPoints() {
        val points = (0 until 8).map { RichTapHe.CurvePoint((it + 1) * 100, 1.0, it.toDouble()) }
        val json = RichTapHe.curve(800, 56, points)
        // 只有曲线控制点带 "Time"，最多 4 个
        val timeCount = Regex("\"Time\":").findAll(json).count()
        assertTrue("should trim to 4 control points, got $timeCount", timeCount == 4)
        assertFalse(json.contains("\"Frequency\":7.0"))
    }

    @Test
    fun continuous_isFlat() {
        val json = RichTapHe.continuous(70, 1000)
        assertTrue(json.contains("\"Duration\":1000"))
        assertTrue(json.contains("\"Frequency\":70"))
    }

    @Test
    fun pattern_isBalancedJson() {
        val events = (0 until 16).map { i ->
            RichTapHe.Event(
                relativeTimeMs = i * 200,
                durationMs = 200,
                baseFreq = 40 + (i % 40),
                points = listOf(
                    RichTapHe.CurvePoint(0, 0.5, 1.0),
                    RichTapHe.CurvePoint(66, 0.6, -2.0),
                    RichTapHe.CurvePoint(133, 0.7, 3.0),
                    RichTapHe.CurvePoint(200, 0.4, 0.0),
                ),
            )
        }
        val json = RichTapHe.pattern(events)
        // 结构必须配平：16 个事件的 HE JSON 曾少写右花括号，导致 HAL `first frame get fail` 无声。
        assertEquals("braces", json.count { it == '{' }, json.count { it == '}' })
        assertEquals("brackets", json.count { it == '[' }, json.count { it == ']' })
        assertEquals("16 events", 16, Regex("\"Event\":").findAll(json).count())
        assertEquals("64 points", 64, Regex("\"Time\":").findAll(json).count())
    }
}
