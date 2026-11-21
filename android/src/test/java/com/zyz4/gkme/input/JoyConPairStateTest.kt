package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState
import org.junit.Assert.assertEquals
import org.junit.Test

class JoyConPairStateTest {
    @Test
    fun bothHalvesRetainTheirStateWhenTheOtherHalfMovesOrReleases() {
        val pair = JoyConPairState()
        pair.bind(JoyConSide.LEFT, 10)
        pair.bind(JoyConSide.RIGHT, 20)
        pair.updateMotion(JoyConSide.LEFT, 12000, -5000, GamepadState.DPAD_BIT_UP, 0)
        pair.updateKey(JoyConSide.LEFT, GamepadState.LT, pressed = true)
        pair.updateKey(JoyConSide.RIGHT, GamepadState.A, pressed = true)
        pair.updateMotion(JoyConSide.RIGHT, -9000, 7000, 0, 180)

        val combined = pair.snapshot()
        assertEquals(
            GamepadState.DPAD_BIT_UP or GamepadState.LT or GamepadState.A or GamepadState.RT,
            combined.buttons.toInt(),
        )
        assertEquals(
            PhysicalControllerState(
                leftStickX = 12000,
                leftStickY = -5000,
                rightStickX = -9000,
                rightStickY = 7000,
                leftTrigger = 255,
                rightTrigger = 180,
                dpad = GamepadState.DPAD_UP,
            ),
            combined.copy(buttons = 0u),
        )

        pair.updateKey(JoyConSide.RIGHT, GamepadState.A, pressed = false)
        assertEquals(12000.toShort(), pair.snapshot().leftStickX)
        assertEquals(GamepadState.DPAD_UP, pair.snapshot().dpad)
        assertEquals(0, pair.snapshot().buttons.toInt() and GamepadState.A)
    }

    @Test
    fun detachingAndRebindingOnlyClearsThatHalf() {
        val pair = JoyConPairState()
        pair.bind(JoyConSide.LEFT, 10)
        pair.bind(JoyConSide.RIGHT, 20)
        pair.updateMotion(JoyConSide.LEFT, 12000, 0, 0, 0)
        pair.updateKey(JoyConSide.LEFT, GamepadState.DPAD_BIT_LEFT, pressed = true)
        pair.updateKey(JoyConSide.RIGHT, GamepadState.B, pressed = true)

        pair.bind(JoyConSide.LEFT, 10)
        assertEquals(GamepadState.DPAD_LEFT, pair.snapshot().dpad)
        pair.bind(JoyConSide.LEFT, null)
        pair.bind(JoyConSide.LEFT, 30)
        assertEquals(GamepadState.B, pair.snapshot().buttons.toInt())
        assertEquals(PhysicalControllerState(), pair.snapshot().copy(buttons = 0u))

        pair.clear()
        assertEquals(PhysicalControllerState(), pair.snapshot())
    }
}
