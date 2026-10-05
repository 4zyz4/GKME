package com.zyz4.gkme.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** JVM tests for the physical-controller stick shaping pipeline. */
class StickShapingTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-3f, msg: String = "") {
        assertTrue("$msg expected≈$expected but was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun noShaping_passesVectorThrough() {
        val (x, y) = StickShaping.apply(10000f, -5000f, 32767f, 0, 0, null)
        assertClose(10000f, x)
        assertClose(-5000f, y)
    }

    @Test
    fun noShaping_clampsDiagonalToCircle() {
        val (x, y) = StickShaping.apply(32767f, 32767f, 32767f, 0, 0, null)
        val mag = kotlin.math.sqrt(x * x + y * y)
        assertClose(32767f, mag, 1f)
    }

    @Test
    fun deadZone_suppressesSmallInput() {
        // 25% of full scale with a 30% dead zone -> zero.
        val (x, y) = StickShaping.apply(8192f, 0f, 32767f, 30, 0, null)
        assertClose(0f, x)
        assertClose(0f, y)
    }

    @Test
    fun deadZone_remapsOutsideRange() {
        // 75% deflection with a 50% dead zone maps to 50% output: (0.75 - 0.5) / (1 - 0.5).
        val (x, _) = StickShaping.apply(32767f * 0.75f, 0f, 32767f, 50, 0, null)
        assertClose(32767f * 0.5f, x, 2f)
    }

    @Test
    fun reverseDeadZone_raisesFloorOutsideDeadZone() {
        // Full deflection still reaches full output with a 50% anti dead zone.
        val (x, _) = StickShaping.apply(32767f, 0f, 32767f, 50, 50, null)
        assertClose(32767f, x, 2f)
    }

    @Test
    fun curve_shapesMagnitudeKeepingDirection() {
        // Convex "即时"-style curve makes half deflection output more than half.
        val curve = listOf(0.5f, 0.75f)
        val (x, y) = StickShaping.apply(16383f, 16383f, 32767f, 0, 0, curve)
        val mag = kotlin.math.sqrt(x * x + y * y)
        assertTrue("curved magnitude should exceed input", mag > 16383f * kotlin.math.sqrt(2f))
        assertClose(1f, x / y, 1e-3f, "direction preserved: ")
    }

    @Test
    fun isDefault_onlyWhenAllNeutral() {
        assertTrue(StickShaping.isDefault(0, 0, null))
        assertTrue(StickShaping.isDefault(0, 0, emptyList()))
        assertTrue(!StickShaping.isDefault(5, 0, null))
        assertTrue(!StickShaping.isDefault(0, 5, null))
        assertTrue(!StickShaping.isDefault(0, 0, listOf(0.5f, 0.5f)))
    }
}
