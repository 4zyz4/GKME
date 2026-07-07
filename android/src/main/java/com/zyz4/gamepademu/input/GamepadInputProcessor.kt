package com.zyz4.gamepademu.input

import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.proto.GamepadInput

fun GamepadState.toProto(): GamepadInput {
    val builder = GamepadInput.newBuilder()
        .setButtons(buttons.toInt())
        .setLeftStickX(leftStickX.toInt())
        .setLeftStickY(leftStickY.toInt())
        .setRightStickX(rightStickX.toInt())
        .setRightStickY(rightStickY.toInt())
        .setLeftTrigger(leftTrigger)
        .setRightTrigger(rightTrigger)
        .setDpad(dpad)
        .setBatteryLevel(batteryLevel)
        .setTouchpadX(touchpadX)
        .setTouchpadY(touchpadY)
        .setTouchpadTouch(touchpadTouch)
        .setTouchpadClick(touchpadClick)
        .setIsCharging(isCharging)
    if (gyroX != 0f || gyroY != 0f || gyroZ != 0f) {
        builder.setGyroX(gyroX).setGyroY(gyroY).setGyroZ(gyroZ)
    }
    if (accelX != 0f || accelY != 0f || accelZ != 0f) {
        builder.setAccelX(accelX).setAccelY(accelY).setAccelZ(accelZ)
    }
    return builder.build()
}

object GamepadInputProcessor {

}
