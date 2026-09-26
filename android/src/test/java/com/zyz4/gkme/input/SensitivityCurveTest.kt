package com.zyz4.gkme.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** JVM tests for the shared gyro/accel stick sensitivity curve. */
class SensitivityCurveTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-3f, msg: String = "") {
        assertTrue("$msg expected≈$expected but was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun nullCurve_isIdentity() {
        assertClose(0f, SensitivityCurve.evaluate(null, 0f))
        assertClose(0.37f, SensitivityCurve.evaluate(null, 0.37f))
        assertClose(1f, SensitivityCurve.evaluate(null, 1f))
    }

    @Test
    fun emptyCurve_isIdentity() {
        assertClose(0.5f, SensitivityCurve.evaluate(emptyList(), 0.5f))
        // A single point is too short to interpolate -> identity.
        assertClose(0.5f, SensitivityCurve.evaluate(listOf(0.5f), 0.5f))
    }

    @Test
    fun curvePassesThroughItsKnot() {
        val curve = listOf(0.5f, 0.25f)
        assertClose(0f, SensitivityCurve.evaluate(curve, 0f))
        assertClose(0.25f, SensitivityCurve.evaluate(curve, 0.5f))
        assertClose(1f, SensitivityCurve.evaluate(curve, 1f))
    }

    @Test
    fun curveShapesMidResponse() {
        val curve = listOf(0.5f, 0.25f)
        // Below the knot the response is reduced, above it is boosted.
        assertTrue(SensitivityCurve.evaluate(curve, 0.25f) < 0.25f)
        assertTrue(SensitivityCurve.evaluate(curve, 0.75f) > 0.5f)
    }

    @Test
    fun flatPlateauDoesNotDip() {
        // Two equal knots must stay flat between them (Catmull-Rom used to undershoot).
        val curve = listOf(0.3f, 0.6f, 0.7f, 0.6f)
        for (x in listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f)) {
            assertClose(0.6f, SensitivityCurve.evaluate(curve, x), 5e-3f, "plateau at $x")
        }
    }

    @Test
    fun upwardBulgeIsMonotonicAndAboveLine() {
        // A single knot above the diagonal must produce a clean monotone bulge.
        val curve = listOf(0.5f, 0.8f)
        var prev = 0f
        var x = 0f
        while (x <= 1.0001f) {
            val y = SensitivityCurve.evaluate(curve, x)
            assertTrue("y must not decrease at $x ($prev -> $y)", y >= prev - 1e-4f)
            assertTrue("y must stay above the line at $x ($y)", y >= x - 1e-4f)
            prev = y
            x += 0.1f
        }
        assertClose(0.8f, SensitivityCurve.evaluate(curve, 0.5f), 5e-3f)
    }

    @Test
    fun applyRadial_preservesDirectionAndFullScale() {
        val curve = listOf(0.5f, 0.25f)
        val (ix, iy) = SensitivityCurve.applyRadial(null, 3f, 4f, 10f)
        assertClose(3f, ix); assertClose(4f, iy)

        // At the full-scale input the implicit (1, 1) knot keeps the magnitude unchanged.
        val (fx, fy) = SensitivityCurve.applyRadial(curve, 6f, 8f, 10f)
        assertClose(6f, fx); assertClose(8f, fy)
    }

    @Test
    fun applyRadial_scalesByCurveRatio() {
        val curve = listOf(0.5f, 0.25f)
        // mag = 5, fullScale = 10 -> normalized 0.5 -> shaped 0.25 -> factor 0.5.
        val (x, y) = SensitivityCurve.applyRadial(curve, 3f, 4f, 10f)
        assertClose(1.5f, x)
        assertClose(2f, y)
    }

    @Test
    fun applyRadial_keepsBeyondFullScaleMagnitude() {
        val curve = listOf(0.5f, 0.25f)
        val (x, y) = SensitivityCurve.applyRadial(curve, 3f, 4f, 2f)
        assertClose(3f, x)
        assertClose(4f, y)
    }
}
