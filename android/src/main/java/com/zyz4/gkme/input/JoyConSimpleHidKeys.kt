package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState

/** Linux scan codes for the generic HID decoder of the original Joy-Con's 0x3F report. */
internal fun simpleJoyConButton(side: JoyConSide, scanCode: Int): Int? {
    if (scanCode !in 0x130..0x13f) return null

    return when (side) {
        JoyConSide.LEFT -> when (scanCode) {
            in 0x130..0x133 -> joyConLeftDpadBit(scanCode)
            0x138 -> GamepadState.SELECT
            0x13a -> GamepadState.L3
            0x13e -> GamepadState.LB
            0x13f -> GamepadState.LT
            else -> 0
        }
        JoyConSide.RIGHT -> when (scanCode) {
            0x130 -> GamepadState.A
            0x131 -> GamepadState.X
            0x132 -> GamepadState.B
            0x133 -> GamepadState.Y
            0x139 -> GamepadState.START
            0x13b -> GamepadState.R3
            0x13c -> GamepadState.HOME
            0x13e -> GamepadState.RB
            0x13f -> GamepadState.RT
            else -> 0
        }
    }
}
