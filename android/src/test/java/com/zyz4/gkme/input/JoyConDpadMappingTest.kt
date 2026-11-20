package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState
import org.junit.Assert.assertEquals
import org.junit.Test

class JoyConDpadMappingTest {
    @Test
    fun mapsBothAndroidGenericHidAndNintendoKernelDirections() {
        assertEquals(GamepadState.DPAD_BIT_UP, joyConLeftDpadBit(306))
        assertEquals(GamepadState.DPAD_BIT_DOWN, joyConLeftDpadBit(305))
        assertEquals(GamepadState.DPAD_BIT_LEFT, joyConLeftDpadBit(304))
        assertEquals(GamepadState.DPAD_BIT_RIGHT, joyConLeftDpadBit(307))
        assertEquals(GamepadState.DPAD_BIT_UP, joyConLeftDpadBit(0x220))
        assertEquals(GamepadState.DPAD_BIT_DOWN, joyConLeftDpadBit(0x221))
        assertEquals(GamepadState.DPAD_BIT_LEFT, joyConLeftDpadBit(0x222))
        assertEquals(GamepadState.DPAD_BIT_RIGHT, joyConLeftDpadBit(0x223))
        assertEquals(0, joyConLeftDpadBit(312))
    }
}
