package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the reverse-engineered RichTap engine laws. */
class RichTapEngineTest {

    @Test
    fun intensityLawEndpoints() {
        assertEquals(0.0, RichTapEngine.curveToAmplitude(0.0), 1e-9)
        assertEquals(1.0, RichTapEngine.curveToAmplitude(1.0), 1e-9)
        assertEquals(0.0, RichTapEngine.amplitudeToCurve(0.0), 1e-9)
        assertEquals(1.0, RichTapEngine.amplitudeToCurve(1.0), 1e-9)
    }

    @Test
    fun intensityLawIsNotLinear() {
        // 引擎的次方律：曲线 0.5 只给 ~26% 幅度，因此要达到 50% 需要曲线 ≈0.72。
        assertEquals(0.260, RichTapEngine.curveToAmplitude(0.5), 0.01)
        assertEquals(0.72, RichTapEngine.amplitudeToCurve(0.5), 0.01)
    }

    @Test
    fun intensityLawRoundTrips() {
        var a = 0.05
        while (a <= 1.0) {
            val c = RichTapEngine.amplitudeToCurve(a)
            assertEquals("amplitude $a", a, RichTapEngine.curveToAmplitude(c), 1e-3)
            a += 0.05
        }
    }

    @Test
    fun frequencyTableMatchesMeasuredCalibration() {
        // 真机加速度计逐点标定（见 RichTapEngine.HE_HZ）。
        assertEquals(169.1, RichTapEngine.heToHz(56), 1e-6)
        assertEquals(107.2, RichTapEngine.heToHz(0), 1e-3)
        assertEquals(241.1, RichTapEngine.heToHz(84), 1e-3)
        assertEquals(280.3, RichTapEngine.heToHz(100), 1e-3)
        assertEquals(56, RichTapEngine.hzToHe(170.0))
    }

    @Test
    fun frequencyTableIsMonotonic() {
        var prev = -1.0
        for (he in 0..100) {
            val hz = RichTapEngine.heToHz(he)
            assertTrue("non-decreasing at $he", hz >= prev - 1e-9)
            prev = hz
        }
    }

    @Test
    fun resonanceResponseIsUnityAtResonance() {
        assertEquals(1.0, RichTapEngine.resonanceResponse(RichTapEngine.HE_AT_RESONANCE), 1e-9)
        assertEquals(1.0, RichTapEngine.frequencyCompensation(RichTapEngine.HE_AT_RESONANCE), 1e-9)
    }

    @Test
    fun compensationBoostsAwayFromResonanceAndStaysBounded() {
        assertTrue(RichTapEngine.frequencyCompensation(30) > 1.0)
        assertTrue(RichTapEngine.frequencyCompensation(80) > 1.0)
        for (he in 0..100) {
            val g = RichTapEngine.frequencyCompensation(he)
            assertTrue("gain >= 1 at $he: $g", g >= 1.0 - 1e-9)
            assertTrue("gain <= max at $he: $g", g <= RichTapEngine.MAX_FREQ_COMPENSATION + 1e-9)
        }
    }

    @Test
    fun compensateNormalizedCapsAtOne() {
        assertEquals(0.0, RichTapEngine.compensateNormalized(0.0, 30), 1e-9)
        assertEquals(1.0, RichTapEngine.compensateNormalized(1.0, 30), 1e-9)
        assertTrue(RichTapEngine.compensateNormalized(0.5, 30) > 0.5)
        // 谐振处不补偿。
        assertEquals(0.5, RichTapEngine.compensateNormalized(0.5, RichTapEngine.HE_AT_RESONANCE), 1e-9)
    }

    @Test
    fun compensate255IsMonotonicAndBounded() {
        assertEquals(128, RichTapEngine.compensate255(128, RichTapEngine.HE_AT_RESONANCE))
        assertTrue(RichTapEngine.compensate255(128, 30) > 128)
        assertEquals(255, RichTapEngine.compensate255(255, 30))
    }
}
