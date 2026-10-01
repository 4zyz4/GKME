package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the low-frequency pulse-train simulator. */
class RichTapLowFreqTest {

    @Test
    fun supports_onlyBelowMotorRange() {
        assertTrue(RichTapLowFreq.supports(40.0))
        assertTrue(RichTapLowFreq.supports(86.0))
        assertFalse(RichTapLowFreq.supports(87.0))
        assertFalse(RichTapLowFreq.supports(170.0))
        assertFalse(RichTapLowFreq.supports(0.0))
        assertFalse(RichTapLowFreq.supports(-10.0))
        assertFalse(RichTapLowFreq.supports(Double.NaN))
    }

    @Test
    fun periodAndPulseFollowTargetFrequency() {
        assertEquals(25.0, RichTapLowFreq.periodMs(40.0), 1e-9)
        assertEquals(12.5, RichTapLowFreq.periodMs(80.0), 1e-9)
        assertEquals(9, RichTapLowFreq.pulseMs(40.0))
        assertEquals(4, RichTapLowFreq.pulseMs(80.0))
    }

    @Test
    fun pulseCountCapsAtMaxEvents() {
        assertEquals(16, RichTapLowFreq.pulseCount(40.0, 4000))
        assertEquals(3, RichTapLowFreq.pulseCount(40.0, 50))
        assertEquals(1, RichTapLowFreq.pulseCount(40.0, 0))
    }

    @Test
    fun coverageIsStartOfLastPulsePlusPulse() {
        // 40Hz -> 25ms 周期；50ms -> 3 个脉冲（起点 0/25/50），末脉冲 9ms。
        assertEquals(59, RichTapLowFreq.coverageMs(40.0, 50))
    }

    @Test
    fun pattern_isBalancedAndHasFourPointsPerPulse() {
        val json = RichTapLowFreq.pattern(40.0, 80, 200)
        assertEquals("braces", json.count { it == '{' }, json.count { it == '}' })
        assertEquals("brackets", json.count { it == '[' }, json.count { it == ']' })
        // 80ms @ 40Hz -> 4 个脉冲（0/25/50/75）。
        assertEquals("events", 4, Regex("\"Event\":").findAll(json).count())
        assertEquals("points", 16, Regex("\"Time\":").findAll(json).count())
        assertTrue(json.contains("\"RelativeTime\":0"))
        assertTrue(json.contains("\"RelativeTime\":25"))
        assertTrue(json.contains("\"RelativeTime\":50"))
        assertTrue(json.contains("\"RelativeTime\":75"))
        assertTrue(json.contains("\"Frequency\":${RichTapEngine.HE_AT_RESONANCE}"))
    }

    @Test
    fun pattern_neverExceedsMaxEvents() {
        val json = RichTapLowFreq.pattern(40.0, 4000, 255)
        assertEquals(16, Regex("\"Event\":").findAll(json).count())
    }

    @Test
    fun strongerStrengthYieldsHigherPeakIntensity() {
        val weak = RichTapLowFreq.pattern(40.0, 80, 32)
        val strong = RichTapLowFreq.pattern(40.0, 80, 255)
        // 峰值强度是曲线里的最大值；Parameters.Intensity 恒为整数 100，曲线值带小数点。
        val weakPeak = Regex("\"Intensity\":([0-9]+\\.[0-9]+)").findAll(weak)
            .map { it.groupValues[1].toDouble() }.max()
        val strongPeak = Regex("\"Intensity\":([0-9]+\\.[0-9]+)").findAll(strong)
            .map { it.groupValues[1].toDouble() }.max()
        assertTrue(strongPeak > weakPeak)
    }
}
