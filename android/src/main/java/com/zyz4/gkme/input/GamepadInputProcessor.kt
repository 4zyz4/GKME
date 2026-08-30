package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.proto.GamepadInput

fun GamepadState.toProto(
    keyboardModifier: UInt = 0u,
    keyboardKeys: List<UInt> = emptyList(),
): GamepadInput {
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
        .setMouseButtons(mouseButtons)
        .setMouseDx(mouseDx.toInt())
        .setMouseDy(mouseDy.toInt())
        .setMouseWheel(mouseWheel.toInt())
        .setMousePan(mousePan.toInt())
    for (t in touches) {
        builder.addTouches(
            com.zyz4.gkme.proto.TouchPoint.newBuilder()
                .setId(t.id)
                .setX(t.x)
                .setY(t.y)
                .setActive(t.active)
                .build()
        )
    }
    if (gyroX != 0f || gyroY != 0f || gyroZ != 0f) {
        builder.setGyroX(gyroX).setGyroY(gyroY).setGyroZ(gyroZ)
    }
    if (accelX != 0f || accelY != 0f || accelZ != 0f) {
        builder.setAccelX(accelX).setAccelY(accelY).setAccelZ(accelZ)
    }
    for (key in keyboardKeys) {
        builder.addPressedScanCodes(key.toInt())
    }
    builder.setKeyboardModifiers(keyboardModifier.toInt())
    return builder.build()
}

object GamepadInputProcessor {

}
