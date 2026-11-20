package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoyConSimpleHidKeysTest {
    @Test
    fun rightSideUsesThePhysicalLabelsFromTheCapturedScanCodes() {
        val right = JoyConSide.RIGHT
        assertEquals(GamepadState.A, simpleJoyConButton(right, 304))
        assertEquals(GamepadState.X, simpleJoyConButton(right, 305))
        assertEquals(GamepadState.B, simpleJoyConButton(right, 306))
        assertEquals(GamepadState.Y, simpleJoyConButton(right, 307))
        assertEquals(GamepadState.START, simpleJoyConButton(right, 313))
        assertEquals(GamepadState.RB, simpleJoyConButton(right, 318))
        assertEquals(GamepadState.RT, simpleJoyConButton(right, 319))
    }

    @Test
    fun theSameScanCodeCanHaveDifferentFunctionsOnTheLeft() {
        val left = JoyConSide.LEFT
        assertEquals(GamepadState.SELECT, simpleJoyConButton(left, 312))
        assertEquals(GamepadState.L3, simpleJoyConButton(left, 314))
        assertEquals(GamepadState.LB, simpleJoyConButton(left, 318))
        assertEquals(GamepadState.LT, simpleJoyConButton(left, 319))
        assertEquals(0, simpleJoyConButton(left, 317))
        assertNull(simpleJoyConButton(left, 0x220))
    }
}
