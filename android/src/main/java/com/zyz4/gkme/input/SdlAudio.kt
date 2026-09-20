package com.zyz4.gkme.input

import com.zyz4.gkme.model.AudioDevice

/**
 * Kotlin wrapper around the native SDL3 audio output bridge (see sdl_bridge.cpp).
 *
 * SDL is used for the phone-speaker playback path so the app can enumerate the
 * system's sound devices (built-in speaker, USB DAC, Bluetooth, ...) and play the
 * controller/voice-coil PCM on a chosen one with low latency.
 */
object SdlAudio {

    /** SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK (0xFFFFFFFF) as a signed int. */
    const val DEFAULT_DEVICE = -1

    /** Native sink for the DualSense voice-coil (left/right motor) channels. */
    const val HANDLE_VOICE_COIL = 1

    /** Native sink for the controller speaker (game audio) channel. */
    const val HANDLE_CONTROLLER_AUDIO = 2

    data class Device(val id: Int, val name: String)

    @Volatile
    private var initialized = false

    /** Initialises the SDL audio subsystem once. Returns false when unavailable. */
    fun ensureInit(): Boolean {
        if (initialized) return true
        initialized = try {
            SdlNative.nativeAudioInit()
        } catch (_: Throwable) {
            false
        }
        return initialized
    }

    /**
     * Returns the currently connected playback devices. Refreshes the native cache
     * so hotplugged devices (wired headset, Bluetooth) show up when the list is
     * re-read.
     */
    fun devices(): List<Device> {
        if (!ensureInit()) return emptyList()
        val count = try {
            SdlNative.nativeAudioRefreshDevices()
        } catch (_: Throwable) {
            0
        }
        if (count <= 0) return emptyList()
        return (0 until count).mapNotNull { index ->
            val id = try {
                SdlNative.nativeAudioDeviceIdAt(index)
            } catch (_: Throwable) {
                0
            }
            if (id == 0) {
                null
            } else {
                val name = try {
                    SdlNative.nativeAudioDeviceNameAt(index)
                } catch (_: Throwable) {
                    ""
                }
                Device(id, name.ifBlank { "声音设备${index + 1}" })
            }
        }
    }

    /**
     * Resolves a stored [AudioDevice.deviceId] to a concrete SDL device id. The
     * [AudioDevice.AUTO_SOUND_DEVICE_ID] sentinel maps to the first enumerated
     * device, or the system default when none is available.
     */
    fun resolveDeviceId(deviceId: Int): Int {
        if (deviceId != AudioDevice.AUTO_SOUND_DEVICE_ID) return deviceId
        return devices().firstOrNull()?.id ?: DEFAULT_DEVICE
    }
}
