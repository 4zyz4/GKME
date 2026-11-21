package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState

internal enum class JoyConSide { LEFT, RIGHT }

internal class JoyConPairState {
    private class Half {
        var deviceId: Int? = null
        var keys = 0
        var hat = 0
        var stickX: Short = 0
        var stickY: Short = 0
        var trigger = 0

        fun clear() {
            keys = 0
            hat = 0
            stickX = 0
            stickY = 0
            trigger = 0
        }
    }

    private val left = Half()
    private val right = Half()

    private fun half(side: JoyConSide): Half = if (side == JoyConSide.LEFT) left else right

    fun bind(side: JoyConSide, deviceId: Int?) {
        val half = half(side)
        if (half.deviceId == deviceId) return
        half.deviceId = deviceId
        half.clear()
    }

    fun updateKey(side: JoyConSide, bit: Int, pressed: Boolean) {
        val half = half(side)
        half.keys = if (pressed) half.keys or bit else half.keys and bit.inv()
    }

    fun updateMotion(side: JoyConSide, x: Short, y: Short, hat: Int, trigger: Int) {
        val half = half(side)
        half.stickX = x
        half.stickY = y
        half.hat = hat
        half.trigger = trigger
    }

    fun clear() {
        left.clear()
        right.clear()
    }

    fun snapshot(): PhysicalControllerState {
        val leftTrigger = if (left.keys and GamepadState.LT != 0) 255 else left.trigger
        val rightTrigger = if (right.keys and GamepadState.RT != 0) 255 else right.trigger
        val triggerBits = (if (leftTrigger >= 128) GamepadState.LT else 0) or
            (if (rightTrigger >= 128) GamepadState.RT else 0)
        val buttons = left.keys or right.keys or left.hat or triggerBits
        return PhysicalControllerState(
            buttons = buttons.toUInt(),
            leftStickX = left.stickX,
            leftStickY = left.stickY,
            rightStickX = right.stickX,
            rightStickY = right.stickY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            dpad = dpadHatValue(buttons),
        )
    }
}
