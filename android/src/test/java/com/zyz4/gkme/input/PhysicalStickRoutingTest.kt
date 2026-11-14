package com.zyz4.gkme.input

import com.zyz4.gkme.model.PhysicalStickDirection
import com.zyz4.gkme.model.PhysicalStickMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for [PhysicalStickRouting]: axis routing and 4/8-way direction detection. */
class PhysicalStickRoutingTest {

    private val full = 32767
    private val half = 18000 // > DIRECTION_THRESHOLD after normalization
    private val low = 10000  // < DIRECTION_THRESHOLD after normalization

    // ── Axis routing ──

    @Test
    fun nativeMode_keepsEachStickOnItsOwnAxis() {
        val routed = PhysicalStickRouting.route(
            null, 1000, -2000,
            null, 3000, 4000,
        )
        assertEquals(1000, routed.leftX)
        assertEquals(-2000, routed.leftY)
        assertEquals(3000, routed.rightX)
        assertEquals(4000, routed.rightY)
        assertEquals(0f, routed.mouseX, 0f)
    }

    @Test
    fun explicitStickMode_crossesToTheRequestedAxis() {
        val routed = PhysicalStickRouting.route(
            PhysicalStickMode.RIGHT_STICK, 5000, 6000,
            PhysicalStickMode.LEFT_STICK, 7000, 8000,
        )
        // Both sticks feed the requested axis and nothing else.
        assertEquals(7000, routed.leftX)
        assertEquals(8000, routed.leftY)
        assertEquals(5000, routed.rightX)
        assertEquals(6000, routed.rightY)
    }

    @Test
    fun axisContributionsAreSummedAndClamped() {
        val routed = PhysicalStickRouting.route(
            null, 30000, -30000,
            PhysicalStickMode.LEFT_STICK, 30000, -30000,
        )
        assertEquals(32767, routed.leftX)
        assertEquals(-32768, routed.leftY)
        assertEquals(0, routed.rightX)
    }

    @Test
    fun mouseMode_normalizesAndClearsAxisOutput() {
        val routed = PhysicalStickRouting.route(
            PhysicalStickMode.MOUSE, full, -full,
            null, 0, 0,
        )
        assertEquals(0, routed.leftX)
        assertEquals(0, routed.leftY)
        assertEquals(1f, routed.mouseX, 0.0001f)
        // Stick Y up is negative, which is also HID mouse "up".
        assertEquals(-1f, routed.mouseY, 0.0001f)
    }

    @Test
    fun mouseMode_appliesPerStickSensitivity() {
        val routed = PhysicalStickRouting.route(
            PhysicalStickMode.MOUSE, full, -full,
            PhysicalStickMode.MOUSE, full, -full,
            2f, 0.5f,
        )
        // 2.0x + 0.5x = 2.5x on each axis.
        assertEquals(2.5f, routed.mouseX, 0.0001f)
        assertEquals(-2.5f, routed.mouseY, 0.0001f)
    }

    @Test
    fun mouseMode_ignoresSensitivityWhenNotMappedToMouse() {
        val routed = PhysicalStickRouting.route(
            null, full, 0,
            null, 0, 0,
            3f, 3f,
        )
        assertEquals(0f, routed.mouseX, 0f)
        assertEquals(full, routed.leftX)
    }

    @Test
    fun directionalMode_doesNotDriveAnAxis() {
        val routed = PhysicalStickRouting.route(
            PhysicalStickMode.FOUR_WAY, full, full,
            PhysicalStickMode.EIGHT_WAY, -full, -full,
        )
        assertEquals(0, routed.leftX)
        assertEquals(0, routed.rightX)
        assertEquals(0f, routed.mouseX, 0f)
    }

    // ── 4-way detection ──

    @Test
    fun fourWay_usesDominantAxis() {
        assertEquals(PhysicalStickDirection.UP, PhysicalStickRouting.fourWayDirection(low, -half))
        assertEquals(PhysicalStickDirection.DOWN, PhysicalStickRouting.fourWayDirection(low, half))
        assertEquals(PhysicalStickDirection.LEFT, PhysicalStickRouting.fourWayDirection(-half, low))
        assertEquals(PhysicalStickDirection.RIGHT, PhysicalStickRouting.fourWayDirection(half, low))
    }

    @Test
    fun fourWay_centerZoneIsInactive() {
        assertNull(PhysicalStickRouting.fourWayDirection(0, 0))
        assertNull(PhysicalStickRouting.fourWayDirection(low, low))
    }

    @Test
    fun fourWay_neverProducesDiagonals() {
        assertEquals(PhysicalStickDirection.UP_RIGHT, PhysicalStickRouting.eightWayDirection(half, -half))
        // 完全对角时按水平优先（|x| >= |y| 取主导轴）。
        assertEquals(PhysicalStickDirection.RIGHT, PhysicalStickRouting.fourWayDirection(half, -half))
        assertEquals(PhysicalStickDirection.LEFT, PhysicalStickRouting.fourWayDirection(-half, -half))
    }

    // ── 8-way detection ──

    @Test
    fun eightWay_cardinalsAndDiagonals() {
        assertEquals(PhysicalStickDirection.UP, PhysicalStickRouting.eightWayDirection(0, -half))
        assertEquals(PhysicalStickDirection.DOWN, PhysicalStickRouting.eightWayDirection(0, half))
        assertEquals(PhysicalStickDirection.LEFT, PhysicalStickRouting.eightWayDirection(-half, 0))
        assertEquals(PhysicalStickDirection.RIGHT, PhysicalStickRouting.eightWayDirection(half, 0))
        assertEquals(PhysicalStickDirection.UP_LEFT, PhysicalStickRouting.eightWayDirection(-half, -half))
        assertEquals(PhysicalStickDirection.UP_RIGHT, PhysicalStickRouting.eightWayDirection(half, -half))
        assertEquals(PhysicalStickDirection.DOWN_LEFT, PhysicalStickRouting.eightWayDirection(-half, half))
        assertEquals(PhysicalStickDirection.DOWN_RIGHT, PhysicalStickRouting.eightWayDirection(half, half))
    }

    @Test
    fun eightWay_ignoresAxisBelowThreshold() {
        // 以水平为主：竖直分量未过阈值时只输出右方向。
        assertEquals(PhysicalStickDirection.RIGHT, PhysicalStickRouting.eightWayDirection(half, -low))
        assertNull(PhysicalStickRouting.eightWayDirection(low, -low))
    }

    @Test
    fun directionOf_respectsMode() {
        assertNull(PhysicalStickRouting.directionOf(null, full, 0))
        assertNull(PhysicalStickRouting.directionOf(PhysicalStickMode.MOUSE, full, 0))
        assertEquals(
            PhysicalStickDirection.RIGHT,
            PhysicalStickRouting.directionOf(PhysicalStickMode.FOUR_WAY, full, 0),
        )
        assertEquals(
            PhysicalStickDirection.UP_RIGHT,
            PhysicalStickRouting.directionOf(PhysicalStickMode.EIGHT_WAY, full, -full),
        )
    }
}
