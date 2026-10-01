package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the engine-derived RichTap HE frequency mapping. */
class RichTapFrequencyTest {

    @Test
    fun breakpoints_matchEngineTable() {
        // 谐振点：引擎 HE 56 ↔ 170Hz。
        assertEquals(56, RichTapFrequency.hzToHe(170.0))
        // 低频端：HE 0 ≈ 86.7Hz（89Hz 落在 HE 1/2 附近，表里二者同为 159）。
        assertEquals(0, RichTapFrequency.hzToHe(80.0))
        assertEquals(2, RichTapFrequency.hzToHe(89.0))
        // 高频端：引擎在 HE≥83 饱和于 ~225Hz，故更高的频率钳到饱和点 HE 83。
        assertEquals(83, RichTapFrequency.hzToHe(271.0))
        // Out-of-range clamps.
        assertEquals(0, RichTapFrequency.hzToHe(10.0))
        assertEquals(83, RichTapFrequency.hzToHe(2000.0))
    }

    @Test
    fun resonantPointRoundTrips() {
        assertEquals(170.0, RichTapFrequency.heToHz(56), 0.5)
        assertEquals(RichTapEngine.MIN_HZ, RichTapFrequency.heToHz(0), 1e-9)
        // 引擎频率表在 HE≥84 饱和（400 = 400）。
        assertEquals(RichTapFrequency.heToHz(84), RichTapFrequency.heToHz(100), 1e-9)
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
    fun roundTripsBeforeSaturation() {
        for (he in 0..84) {
            val back = RichTapFrequency.hzToHe(RichTapFrequency.heToHz(he))
            assertEquals("round trip HE $he", he.toDouble(), back.toDouble(), 1.0)
        }
    }

    @Test
    fun shiftIntoRangeMovesOctavesOnly() {
        // 低频素材上移进马达频段 (~87..225)。
        assertEquals(100.0, RichTapFrequency.shiftIntoRange(50.0), 1e-6)
        assertEquals(90.0, RichTapFrequency.shiftIntoRange(45.0), 1e-6)
        assertEquals(160.0, RichTapFrequency.shiftIntoRange(40.0), 1e-6)
        // 已在范围内的频率不动。
        assertEquals(170.0, RichTapFrequency.shiftIntoRange(170.0), 1e-6)
        // 过高的频率下移。
        assertEquals(150.0, RichTapFrequency.shiftIntoRange(600.0), 1e-6)
        // 非法输入回落到谐振点。
        assertEquals(170.0, RichTapFrequency.shiftIntoRange(0.0), 1e-6)
    }
}
