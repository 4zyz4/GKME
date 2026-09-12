package com.zyz4.gkme.input

import android.app.Activity
import android.view.MotionEvent
import org.libsdl.app.HIDDeviceManager
import org.libsdl.app.SDL
import org.libsdl.app.SDLControllerManager

/**
 * Initialises the SDL3 Java glue layer (org.libsdl.app.*) so that SDL's native
 * gamepad driver can enumerate Android input devices and HIDAPI (USB/BLE) devices.
 *
 * The app deliberately does not extend SDLActivity; instead the public SDL helper
 * classes are initialised as a library and the raw key/motion events already
 * delivered to MainActivity are forwarded into SDL.
 */
object SdlPlatform {

    private var hidDeviceManager: HIDDeviceManager? = null
    private var initialized = false

    @Synchronized
    fun setup(activity: Activity) {
        if (initialized) return
        // Loading gkme_sdl pulls in libSDL3.so, whose JNI_OnLoad registers the
        // native methods on org.libsdl.app.*.
        SdlNative.nativeGetControllerCount()

        SDL.setupJNI()
        SDL.initialize()
        SDL.setContext(activity)
        hidDeviceManager = HIDDeviceManager.acquire(activity)
        initialized = true
    }

    @Synchronized
    fun shutdown() {
        if (!initialized) return
        hidDeviceManager?.let { HIDDeviceManager.release(it) }
        hidDeviceManager = null
        initialized = false
    }

    fun isJoystickDevice(deviceId: Int): Boolean =
        SDLControllerManager.isDeviceSDLJoystick(deviceId)

    fun handleJoystickMotionEvent(event: MotionEvent): Boolean =
        SDLControllerManager.handleJoystickMotionEvent(event)

    fun onPadDown(deviceId: Int, keyCode: Int, scanCode: Int): Boolean =
        SDLControllerManager.onNativePadDown(deviceId, keyCode, scanCode)

    fun onPadUp(deviceId: Int, keyCode: Int, scanCode: Int): Boolean =
        SDLControllerManager.onNativePadUp(deviceId, keyCode, scanCode)
}
