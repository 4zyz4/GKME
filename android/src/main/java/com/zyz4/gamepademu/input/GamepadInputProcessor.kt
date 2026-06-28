package com.zyz4.gamepademu.input

import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.proto.GamepadInput

fun GamepadState.toProto(): GamepadInput {
    return GamepadInput.newBuilder()
        .setButtons(buttons.toInt())
        .setLeftStickX(leftStickX.toInt())
        .setLeftStickY(leftStickY.toInt())
        .setRightStickX(rightStickX.toInt())
        .setRightStickY(rightStickY.toInt())
        .setLeftTrigger(leftTrigger)
        .setRightTrigger(rightTrigger)
        .setDpad(dpad)
        .setBatteryLevel(batteryLevel)
        .setGyroX(gyroX)
        .setGyroY(gyroY)
        .setGyroZ(gyroZ)
        .setAccelX(accelX)
        .setAccelY(accelY)
        .setAccelZ(accelZ)
        .setTouchpadX(touchpadX)
        .setTouchpadY(touchpadY)
        .setTouchpadTouch(touchpadTouch)
        .setTouchpadClick(touchpadClick)
        .setIsCharging(isCharging)
        .build()
}

object GamepadInputProcessor {

}
