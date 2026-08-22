package com.zyz4.gamepademu.service

import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.proto.GamepadInput

object GamepadStateMapper {

    fun map(input: GamepadInput, target: TargetPlatform = TargetPlatform.WINDOWS): ByteArray {
        return when (target) {
            TargetPlatform.WINDOWS -> mapWindows(input)
            TargetPlatform.ANDROID -> mapAndroid(input)
            TargetPlatform.LINUX -> mapLinux(input)
        }
    }

    private fun mapWindows(input: GamepadInput): ByteArray {
        var buttons = input.buttons.toInt() and 0x3FFFF
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

    private fun mapAndroid(input: GamepadInput): ByteArray {
        val report = ByteArray(9)

        // This device's HID→Android: 1:A 2:B 3:TP 4:X 5:Y 6:— 7:LB 8:RB 9:LT 10:RT 11:SEL 12:STA 13:HOME 14:L3 15:R3
        val raw = input.buttons.toInt()
        val buttons = (raw and 0x03) or ((raw and 0x20000) shr 15) or ((raw and 0x0C) shl 1) or ((raw and 0x3F0) shl 2) or ((raw and 0x10000) shr 4) or ((raw and 0x400) shl 3) or ((raw and 0x800) shl 3)
        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()

        report[2] = scaleAxis(input.leftStickX)
        report[3] = scaleAxis(input.leftStickY)

        // Hat switch: 1=N, 2=NE, 3=E, 4=SE, 5=S, 6=SW, 7=W, 8=NW, 0=null
        report[4] = mapDpadHatAndroid(input.dpad).toByte()

        report[5] = scaleAxis(input.rightStickX)
        report[6] = scaleAxis(input.rightStickY)

        report[7] = input.rightTrigger.coerceIn(0, 255).toByte()
        report[8] = input.leftTrigger.coerceIn(0, 255).toByte()
        return report
    }

    private fun scaleAxis(value: Int): Byte {
        return (value * 127 / 32767).coerceIn(-127, 127).toByte()
    }

    private fun mapLinux(input: GamepadInput): ByteArray {
        val report = ByteArray(9)

        // X and Y buttons swapped vs Android
        // Android: X(bit2)→HID4, Y(bit3)→HID5
        // Linux:   X(bit2)→HID5, Y(bit3)→HID4
        val raw = input.buttons.toInt()
        val buttons = (raw and 0x03) or ((raw and 0x20000) shr 15) or ((raw and 0x04) shl 2) or (raw and 0x08) or ((raw and 0x3F0) shl 2) or ((raw and 0x10000) shr 4) or ((raw and 0x400) shl 3) or ((raw and 0x800) shl 3)
        report[0] = (buttons and 0xFF).toByte()
        report[1] = ((buttons shr 8) and 0xFF).toByte()

        report[2] = scaleAxis(input.leftStickX)
        report[3] = scaleAxis(input.leftStickY)

        report[4] = mapDpadHatAndroid(input.dpad).toByte()

        // Right stick: Rx/Ry
        report[5] = scaleAxis(input.rightStickX)
        report[6] = scaleAxis(input.rightStickY)

        // Linux: Brake=RT (right trigger), Accelerator=LT (left trigger)
        report[7] = input.rightTrigger.coerceIn(0, 255).toByte()
        report[8] = input.leftTrigger.coerceIn(0, 255).toByte()
        return report
    }

    /** 1=N, 2=NE, 3=E, 4=SE, 5=S, 6=SW, 7=W, 8=NW, 0=null */
    private fun mapDpadHatAndroid(dpad: Int): Int {
        return when (dpad) {
            GamepadState.DPAD_UP -> 1
            GamepadState.DPAD_UP_RIGHT -> 2
            GamepadState.DPAD_RIGHT -> 3
            GamepadState.DPAD_DOWN_RIGHT -> 4
            GamepadState.DPAD_DOWN -> 5
            GamepadState.DPAD_DOWN_LEFT -> 6
            GamepadState.DPAD_LEFT -> 7
            GamepadState.DPAD_UP_LEFT -> 8
            else -> 0
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
