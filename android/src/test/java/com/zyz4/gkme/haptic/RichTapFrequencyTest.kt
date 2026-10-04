package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the engine-derived RichTap HE frequency mapping. */
class RichTapFrequencyTest {

    @Test
    fun breakpoints_matchMeasuredCalibration() {
        // 谐振点：引擎 HE 56 ↔ ≈169Hz。
        assertEquals(56, RichTapFrequency.hzToHe(170.0))
        // 低频端：HE 0 = 107.2Hz，更低的频率钳到 HE 0。
        assertEquals(0, RichTapFrequency.hzToHe(80.0))
        assertEquals(0, RichTapFrequency.hzToHe(107.0))
        // 高频端：频率表单调到 HE 100 = 280.3Hz，超出钳到 HE 100。
        assertEquals(95, RichTapFrequency.hzToHe(271.0))
        // Out-of-range clamps.
        assertEquals(0, RichTapFrequency.hzToHe(10.0))
        assertEquals(100, RichTapFrequency.hzToHe(2000.0))
    }

    @Test
    fun resonantPointRoundTrips() {
        assertEquals(RichTapEngine.RESONANCE_HZ, RichTapFrequency.heToHz(56), 1e-9)
        assertEquals(RichTapEngine.MIN_HZ, RichTapFrequency.heToHz(0), 1e-9)
        assertEquals(RichTapEngine.MAX_HZ, RichTapFrequency.heToHz(100), 1e-9)
    }

    @Test
    fun mappingIsMonotonic() {
        var prevHz = -1.0
        for (he in 0..100) {
            val hz = RichTapFrequency.heToHz(he)
            assertTrue("Hz should be non-decreasing at HE $he: $hz >= $prevHz", hz >= prevHz)
            prevHz = hz
        }
    }

    @Test
    fun roundTrips() {
        for (he in 0..100) {
            val back = RichTapFrequency.hzToHe(RichTapFrequency.heToHz(he))
            // 表里有相邻重复值（成对），往返可能落在相邻 HE，容差 1。
            assertEquals("round trip HE $he", he.toDouble(), back.toDouble(), 1.0)
        }
    }

    @Test
    fun shiftIntoRangeMovesOctavesOnly() {
        // 低频素材上移进马达频段 (107.2..280.3)。
        assertEquals(200.0, RichTapFrequency.shiftIntoRange(50.0), 1e-6)
        assertEquals(180.0, RichTapFrequency.shiftIntoRange(45.0), 1e-6)
        assertEquals(160.0, RichTapFrequency.shiftIntoRange(40.0), 1e-6)
        // 已在范围内的频率不动。
        assertEquals(170.0, RichTapFrequency.shiftIntoRange(170.0), 1e-6)
        // 过高的频率下移。
        assertEquals(150.0, RichTapFrequency.shiftIntoRange(600.0), 1e-6)
        // 非法输入回落到谐振点。
        assertEquals(RichTapEngine.RESONANCE_HZ, RichTapFrequency.shiftIntoRange(0.0), 1e-6)
    }
}
