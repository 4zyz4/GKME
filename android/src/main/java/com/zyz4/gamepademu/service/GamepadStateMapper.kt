package com.zyz4.gamepademu.service

import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.proto.GamepadInput

object GamepadStateMapper {

    fun map(input: GamepadInput, target: TargetPlatform = TargetPlatform.WINDOWS): ByteArray {
        return when (target) {
            TargetPlatform.WINDOWS -> mapWindows(input)
            TargetPlatform.ANDROID -> mapClassicAndroid(input)
        }
    }

    private fun mapWindows(input: GamepadInput): ByteArray {
        var buttons = input.buttons.toInt() and 0x1FFFF
        buttons = buttons or mapDpadBits(input.dpad)
        val report = ByteArray(11)
        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()
        report[2] = ((buttons shr 16) and 0xFF).toByte()

        val lx = input.leftStickX; report[3] = (lx and 0xFF).toByte(); report[4] = ((lx shr 8) and 0xFF).toByte()
        val ly = input.leftStickY; report[5] = (ly and 0xFF).toByte(); report[6] = ((ly shr 8) and 0xFF).toByte()
        val rx = input.rightStickX; report[7] = (rx and 0xFF).toByte(); report[8] = ((rx shr 8) and 0xFF).toByte()
        val ry = input.rightStickY; report[9] = (ry and 0xFF).toByte(); report[10] = ((ry shr 8) and 0xFF).toByte()
        return report
    }

    private fun mapClassicAndroid(input: GamepadInput): ByteArray {
        val report = ByteArray(8)
        val bits = input.buttons.toInt() and 0x3FF  // 10 buttons

        report[0] = (bits and 0xFF).toByte()
        report[1] = ((bits shr 8) and 0x03).toByte()

        report[2] = scaleAxis(input.leftStickX)
        report[3] = scaleAxis(input.leftStickY)
        report[4] = scaleAxis(input.rightStickX)
        report[5] = scaleAxis(input.rightStickY)

        // Ry: triggers combined (RT - LT), scaled to [-127, 127]
        val lt = input.leftTrigger.toInt().coerceIn(0, 255)
        val rt = input.rightTrigger.toInt().coerceIn(0, 255)
        val triggerAxis = (rt - lt) * 127 / 255
        report[6] = triggerAxis.coerceIn(-127, 127).toByte()

        // Hat switch: 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW, 15=null
        report[7] = mapDpadHatAndroid(input.dpad).toByte()
        return report
    }

    private fun scaleAxis(value: Int): Byte {
        return (value * 127 / 32767).coerceIn(-127, 127).toByte()
    }

    /** 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW, 15=null */
    private fun mapDpadHatAndroid(dpad: Int): Int {
        return when (dpad) {
            GamepadState.DPAD_UP -> 0
            GamepadState.DPAD_UP_RIGHT -> 1
            GamepadState.DPAD_RIGHT -> 2
            GamepadState.DPAD_DOWN_RIGHT -> 3
            GamepadState.DPAD_DOWN -> 4
            GamepadState.DPAD_DOWN_LEFT -> 5
            GamepadState.DPAD_LEFT -> 6
            GamepadState.DPAD_UP_LEFT -> 7
            else -> 15
        }
    }

    private fun mapDpadBits(dpad: Int): Int {
        return when (dpad) {
            GamepadState.DPAD_UP -> GamepadState.DPAD_BIT_UP
            GamepadState.DPAD_UP_RIGHT -> GamepadState.DPAD_BIT_UP or GamepadState.DPAD_BIT_RIGHT
            GamepadState.DPAD_RIGHT -> GamepadState.DPAD_BIT_RIGHT
            GamepadState.DPAD_DOWN_RIGHT -> GamepadState.DPAD_BIT_DOWN or GamepadState.DPAD_BIT_RIGHT
            GamepadState.DPAD_DOWN -> GamepadState.DPAD_BIT_DOWN
            GamepadState.DPAD_DOWN_LEFT -> GamepadState.DPAD_BIT_DOWN or GamepadState.DPAD_BIT_LEFT
            GamepadState.DPAD_LEFT -> GamepadState.DPAD_BIT_LEFT
            GamepadState.DPAD_UP_LEFT -> GamepadState.DPAD_BIT_UP or GamepadState.DPAD_BIT_LEFT
            else -> 0
        }
    }

}
