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
    private var coreInitialized = false

    /**
     * Initialises the SDL Java glue and stores the Activity context without
     * touching USB/HID. This is enough for the SDL audio subsystem (device
     * enumeration needs an Android context) and is safe to call with any
     * controller driver selected.
     */
    @Synchronized
    fun ensureCore(activity: Activity) {
        if (coreInitialized) return
        // Loading gkme_sdl pulls in libSDL3.so, whose JNI_OnLoad registers the
        // native methods on org.libsdl.app.*.
        SdlNative.nativeGetControllerCount()

        SDL.setupJNI()
        SDL.initialize()
        SDL.setContext(activity)
        coreInitialized = true
    }

    @Synchronized
    fun setup(activity: Activity) {
        ensureCore(activity)
        if (hidDeviceManager == null) {
            hidDeviceManager = HIDDeviceManager.acquire(activity)
        }
    }

    @Synchronized
    fun shutdown() {
        // The SDL core/context stays initialised (the audio output may still use it);
        // only the HID device manager is released with the gamepad backend.
        hidDeviceManager?.let { HIDDeviceManager.release(it) }
        hidDeviceManager = null
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
