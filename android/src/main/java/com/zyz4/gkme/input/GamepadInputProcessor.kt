package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.proto.GamepadInput

internal fun GamepadInput.withExternalInput(report: InputReport, includeMouseDelta: Boolean): GamepadInput =
    withKeyboard(report.keyboard, includeMouseDelta).toBuilder()
        .setMouseButtons(mouseButtons or report.mouse.buttons)
        .setMouseDx((if (includeMouseDelta) mouseDx else 0) + report.mouse.dx)
        .setMouseDy((if (includeMouseDelta) mouseDy else 0) + report.mouse.dy)
        .setMouseWheel((if (includeMouseDelta) mouseWheel else 0) + report.mouse.wheel)
        .setMousePan((if (includeMouseDelta) mousePan else 0) + report.mouse.pan)
        .build()

/** Merge keyboard transitions into a complete input frame without repeating relative mouse deltas. */
internal fun GamepadInput.withKeyboard(report: KeyboardReport, includeMouseDelta: Boolean): GamepadInput {
    val builder = toBuilder()
        .setKeyboardModifiers(report.modifier)
        .clearPressedScanCodes()
        .addAllPressedScanCodes(report.keys)
    if (!includeMouseDelta) builder.setMouseDx(0).setMouseDy(0).setMouseWheel(0).setMousePan(0)
    return builder.build()
}

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
        .setIsCharging(isCharging)
        .setMouseButtons(mouseButtons or mouseGestureButtons)
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
