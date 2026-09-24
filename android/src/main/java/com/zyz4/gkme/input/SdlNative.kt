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

    /** True when the gamepad at [index] exposes trigger rumble (SDL: currently Xbox One). */
    external fun nativeGetControllerHasTriggerRumble(index: Int): Boolean

    external fun nativeGetControllerHasGyro(index: Int): Boolean

    external fun nativeGetControllerHasAccel(index: Int): Boolean

    /** True when the gamepad at [index] exposes analog (linear) triggers. */
    external fun nativeGetControllerHasAnalogTriggers(index: Int): Boolean

    /** True when the gamepad at [index] exposes a touchpad. */
    external fun nativeGetControllerHasTouchpad(index: Int): Boolean

    /**
     * Mask of the buttons the gamepad at [index] physically exposes, using the app's
     * GamepadState bit layout plus the physical-only paddle bits (see PhysicalInputs).
     */
    external fun nativeGetControllerButtonMask(index: Int): Int

    /** SDL_GamepadType value (0=unknown, 1=standard, 2=Xbox360, 3=XboxOne, 4=PS3, 5=PS4, 6=PS5, ...). */
    external fun nativeGetControllerType(index: Int): Int

    /** USB vendor id the gamepad reports (0 when unknown). */
    external fun nativeGetControllerVendor(index: Int): Int

    /** USB product id the gamepad reports (0 when unknown). */
    external fun nativeGetControllerProduct(index: Int): Int

    /**
     * Fills [out] (length >= 16) with the current state of the gamepad at [index]:
     * 0=buttons 1=leftX 2=leftY 3=rightX 4=rightY 5=leftTrigger 6=rightTrigger 7=dpad
     * 8=touchCount 9/10=touch0 x/y 11/12=touch1 x/y 13=touchpadTouch 14=touchpadClick 15=valid.
     */
    external fun nativePollState(index: Int, out: IntArray): Boolean

    /** Fills [out] (length >= 6) with gyro[0..2] and accel[0..2]. */
    external fun nativePollSensor(index: Int, out: FloatArray): Boolean

    external fun nativeRumble(index: Int, low: Int, high: Int, durationMs: Int): Boolean

    /** Drives the trigger rumble motors (Xbox One). 0..65535 each. */
    external fun nativeRumbleTriggers(index: Int, left: Int, right: Int, durationMs: Int): Boolean

    /** True when the gamepad at [index] exposes a (RGB or mono) LED. */
    external fun nativeGetControllerHasLed(index: Int): Boolean

    /** True when the gamepad at [index] exposes a player-indicator LED. */
    external fun nativeGetControllerHasPlayerLed(index: Int): Boolean

    /** Sets the gamepad LED color (RGB, 0..255 each). */
    external fun nativeSetControllerLed(index: Int, red: Int, green: Int, blue: Int): Boolean

    /** Sets the gamepad player index, which drives its player-indicator LEDs. */
    external fun nativeSetControllerPlayerIndex(index: Int, playerIndex: Int): Boolean

    external fun nativeSetSensorEnabled(index: Int, enabled: Boolean)

    /**
     * Publishes the attached USB devices (encoded as (vendorId shl 16) or productId)
     * so the native bridge can prefer the HIDAPI driver for USB gamepads.
     */
    external fun nativeSetUsbDeviceIds(keys: IntArray)

    // ── SDL audio output (phone speaker path) ──

    /** Initialises (or reuses) the SDL audio subsystem. Safe to call repeatedly. */
    external fun nativeAudioInit(): Boolean

    /** Tears the audio subsystem down; only for process-level shutdown. */
    external fun nativeAudioShutdown()

    /**
     * Refreshes the cached playback device list and returns its size. Use
     * [nativeAudioDeviceIdAt] / [nativeAudioDeviceNameAt] to read the entries.
     */
    external fun nativeAudioRefreshDevices(): Int

    /** SDL audio device id of the playback device at [index] (0 when invalid). */
    external fun nativeAudioDeviceIdAt(index: Int): Int

    /** Human-readable name of the playback device at [index]. */
    external fun nativeAudioDeviceNameAt(index: Int): String

    /**
     * Opens a low-latency playback sink identified by [handle] on [deviceId] (use
     * -1 for the system default) using interleaved S16 PCM at [sampleRate] and
     * [channels]. [maxQueuedMs] bounds the queued audio latency. Replaces any sink
     * already open on the same [handle].
     */
    external fun nativeAudioOpen(handle: Int, deviceId: Int, sampleRate: Int, channels: Int, maxQueuedMs: Int): Boolean

    /**
     * Queues [data] on the sink [handle], blocking up to [waitBudgetMs] while the
     * stream is over its latency budget. Returns the number of bytes queued, 0 for
     * an empty buffer or -1 when the sink is not open.
     */
    external fun nativeAudioWrite(handle: Int, data: ByteArray, waitBudgetMs: Int): Int

    /** Stops and releases the sink [handle]. */
    external fun nativeAudioClose(handle: Int)

    /** Stops and releases every open sink. */
    external fun nativeAudioCloseAll()

    /** Approximate number of milliseconds of audio queued on sink [handle]. */
    external fun nativeAudioQueuedMs(handle: Int): Int
}
