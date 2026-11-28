package com.zyz4.gkme.view

import org.junit.Assert.assertEquals
import org.junit.Test

class PrecisionTouchpadTest {

    private fun assertEqualsPair(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-6f)
        assertEquals(expected.second, actual.second, 1e-6f)
    }

    @Test
    fun rotate_followsTheControlRotation() {
        assertEqualsPair(0.25f to 0.75f, PrecisionTouchpad.rotate(0.25f, 0.75f, 0))
        // 90°: a rightward screen move becomes upward on the pad.
        assertEqualsPair(0.75f to 0.75f, PrecisionTouchpad.rotate(0.25f, 0.75f, 90))
        assertEqualsPair(0.75f to 0.25f, PrecisionTouchpad.rotate(0.25f, 0.75f, 180))
        assertEqualsPair(0.25f to 0.25f, PrecisionTouchpad.rotate(0.25f, 0.75f, 270))
        assertEqualsPair(0.25f to 0.75f, PrecisionTouchpad.rotate(0.25f, 0.75f, -360))
    }

    @Test
    fun contact_mapsToLogicalRangeAndClamps() {
        val c = PrecisionTouchpad.contact(id = 3, nx = 0.5f, ny = 1f)
        assertEquals(3, c.id)
        assertEquals(2048, c.x)
        assertEquals(4095, c.y)

        val out = PrecisionTouchpad.contact(id = 2, nx = -1f, ny = 2f)
        assertEquals(0, out.x)
        assertEquals(4095, out.y)
    }
}
