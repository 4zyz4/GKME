package com.zyz4.gkme.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for time-normalized mouse pointer acceleration. */
class PointerAccelerationTest {

    @Test
    fun disabledWhenCoefficientIsZeroOrNegative() {
        assertEquals(1f, PointerAcceleration.multiplier(0f, 2f), 0f)
        assertEquals(1f, PointerAcceleration.multiplier(-0.5f, 2f), 0f)
    }

    @Test
    fun disabledWhenPointerIsStill() {
        assertEquals(1f, PointerAcceleration.multiplier(0.5f, 0f), 0f)
    }

    @Test
    fun matchesLegacyFeelAtReferenceRate() {
        // 1px/ms == 8.333px per 120Hz frame；系数 0.5 -> 1 + 0.5 * 8.333。
        val expected = 1f + 0.5f * (1000f / PointerAcceleration.REFERENCE_HZ)
        assertEquals(expected, PointerAcceleration.multiplier(0.5f, 1f), 1e-5f)
    }

    @Test
    fun boostScalesLinearlyWithSpeed() {
        val slow = PointerAcceleration.multiplier(0.4f, 0.5f) - 1f
        val fast = PointerAcceleration.multiplier(0.4f, 1.0f) - 1f
        assertEquals(slow * 2f, fast, 1e-5f)
    }

    @Test
    fun samePhysicalSpeedGivesSameMultiplierRegardlessOfChunking() {
        // 速度是时间归一化量：把同一段手势拆成多少个事件，倍率都一样。
        val speedPxPerMs = 1.5f
        val expected = PointerAcceleration.multiplier(0.5f, speedPxPerMs)
        // 120Hz 下每个事件 12.5px、240Hz 下 6.25px，换算出的速度同为 1.5px/ms。
        assertEquals(expected, PointerAcceleration.multiplier(0.5f, 12.5f / (1000f / 120f)), 1e-5f)
        assertEquals(expected, PointerAcceleration.multiplier(0.5f, 6.25f / (1000f / 240f)), 1e-5f)
    }
}
