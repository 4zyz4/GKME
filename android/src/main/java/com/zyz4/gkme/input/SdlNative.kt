package com.zyz4.gkme.input

/**
 * Thin Kotlin wrapper around the native SDL3 bridge (libgkme_sdl.so).
 *
 * The SDL3 shared library itself is provided prebuilt by SDL3-3.4.16.aar and is
 * loaded automatically as a dependency of gkme_sdl. All gamepad state, rumble and
 * sensor access goes through SDL here.
 */
object SdlNative {

    init {
        System.loadLibrary("gkme_sdl")
    }

    /** True once the SDL gamepad + sensor subsystems are initialised. */
    external fun nativeInit(): Boolean

    external fun nativeShutdown()

    external fun nativeGetControllerCount(): Int

    external fun nativeGetControllerName(index: Int): String

    external fun nativeGetControllerInstanceId(index: Int): Int

    external fun nativeGetControllerMotorCount(index: Int): Int

    external fun nativeGetControllerHasGyro(index: Int): Boolean

    external fun nativeGetControllerHasAccel(index: Int): Boolean

    /** SDL_GamepadType value (0=unknown, 1=standard, 2=Xbox360, 3=XboxOne, 4=PS3, 5=PS4, 6=PS5, ...). */
    external fun nativeGetControllerType(index: Int): Int

    /**
     * Fills [out] (length >= 16) with the current state of the gamepad at [index]:
     * 0=buttons 1=leftX 2=leftY 3=rightX 4=rightY 5=leftTrigger 6=rightTrigger 7=dpad
     * 8=touchCount 9/10=touch0 x/y 11/12=touch1 x/y 13=touchpadTouch 14=touchpadClick 15=valid.
     */
    external fun nativePollState(index: Int, out: IntArray): Boolean

    /** Fills [out] (length >= 6) with gyro[0..2] and accel[0..2]. */
    external fun nativePollSensor(index: Int, out: FloatArray): Boolean

    external fun nativeRumble(index: Int, low: Int, high: Int, durationMs: Int): Boolean

    external fun nativeSetSensorEnabled(index: Int, enabled: Boolean)

    /**
     * Publishes the attached USB devices (encoded as (vendorId shl 16) or productId)
     * so the native bridge can prefer the HIDAPI driver for USB gamepads.
     */
    external fun nativeSetUsbDeviceIds(keys: IntArray)
}
