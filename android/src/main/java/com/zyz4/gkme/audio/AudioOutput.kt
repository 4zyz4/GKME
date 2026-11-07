package com.zyz4.gkme.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.zyz4.gkme.R
import com.zyz4.gkme.i18n.AppStrings
import com.zyz4.gkme.model.AudioDevice

/**
 * Phone audio output for the controller voice-coil and speaker lanes.
 *
 * The preferred path is a **direct low-latency AAudio stream** opened by the
 * native bridge in `audio_out.cpp`. When that fails (device busy or missing,
 * unsupported format, AAudio unavailable) the sink transparently falls back to
 * an [AudioTrack] with [AudioTrack.PERFORMANCE_MODE_LOW_LATENCY].
 *
 * SDL3 is not involved anywhere in this path; it is only used for gamepads.
 * Playback devices are enumerated through [AudioManager], whose device ids are
 * accepted by both AAudio and [AudioTrack.setPreferredDevice]. The built-in
 * earpiece is filtered out and the built-in speaker is shown as "phone speaker".
 */
object AudioOutput {

    private const val TAG = "AudioOutput"

    /** Native sink for the DualSense voice-coil (left/right motor) channels. */
    const val HANDLE_VOICE_COIL = 1

    /** Native sink for the controller speaker (game audio) channel. */
    const val HANDLE_CONTROLLER_AUDIO = 2

    /** System default routing (AAudio AAUDIO_UNSPECIFIED / AudioTrack default). */
    const val DEFAULT_DEVICE = 0

    /**
     * After an AAudio open or write failure, try AudioTrack first for this long
     * instead of re-attempting AAudio on every audio frame. A different output
     * device gets a fresh AAudio attempt immediately.
     */
    private const val AAUDIO_RETRY_COOLDOWN_MS = 10_000L

    data class Device(val id: Int, val name: String)

    private data class SinkKey(val handle: Int, val deviceId: Int)

    private class SinkState(
        val deviceId: Int,
        /** Non-null when AAudio was unavailable and this sink runs on AudioTrack. */
        val track: AudioTrack?,
    )

    @Volatile
    private var appContext: Context? = null

    private var audioManager: AudioManager? = null
    private val deviceInfos = HashMap<Int, AudioDeviceInfo>()
    private var deviceOrder: List<Int> = emptyList()
    private val sinks = HashMap<Int, SinkState>()
    private val aaCooldownUntil = HashMap<SinkKey, Long>()

    /** Stores the application context used for device enumeration. Safe to repeat. */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Returns the currently connected playback devices. The list is refreshed on
     * every call so hotplugged devices (wired headset, Bluetooth, USB DAC) show
     * up immediately.
     */
    @Synchronized
    fun devices(): List<Device> = refreshDevicesLocked()

    /**
     * Resolves a stored [AudioDevice.deviceId] to an output device id. The
     * [AudioDevice.AUTO_SOUND_DEVICE_ID] sentinel, and an id that no longer
     * resolves, map to the first enumerated output shown in the settings list.
     */
    @Synchronized
    fun resolveDeviceId(deviceId: Int): Int = resolveDeviceIdLocked(deviceId)

    /**
     * Opens (or replaces) the sink [handle]: AAudio first, then AudioTrack on
     * failure. Returns false only when neither backend can be opened.
     */
    @Synchronized
    fun open(
        handle: Int,
        deviceId: Int,
        sampleRate: Int,
        channels: Int,
        maxQueuedMs: Int,
    ): Boolean {
        if (sampleRate <= 0 || channels <= 0) return false
        closeLocked(handle)

        val resolved = resolveDeviceIdLocked(deviceId)
        val tryAAudio = AudioNative.available && aaAllowedLocked(handle, resolved)
        if (tryAAudio && AudioNative.open(handle, resolved, sampleRate, channels, maxQueuedMs)) {
            clearAaFailureLocked(handle, resolved)
            sinks[handle] = SinkState(resolved, track = null)
            Log.i(TAG, "AAudio sink $handle opened: device=$resolved rate=$sampleRate ch=$channels")
            return true
        }
        if (tryAAudio) {
            markAaFailureLocked(handle, resolved)
        }

        val track = createAudioTrackLocked(resolved, sampleRate, channels)
        if (track == null) {
            Log.e(TAG, "no audio sink for handle=$handle device=$resolved rate=$sampleRate ch=$channels")
            return false
        }
        try {
            track.play()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack.play failed: ${t.message}")
            runCatching { track.release() }
            return false
        }
        sinks[handle] = SinkState(resolved, track)
        Log.w(TAG, "AAudio unavailable, AudioTrack fallback: handle=$handle device=$resolved rate=$sampleRate ch=$channels")
        return true
    }

    /**
     * Writes interleaved S16 [data] to sink [handle], blocking up to
     * [waitBudgetMs] when the queue is full. Returns bytes written or -1 when
     * the sink is no longer open.
     */
    @Synchronized
    fun write(handle: Int, data: ByteArray, waitBudgetMs: Int): Int {
        val sink = sinks[handle] ?: return -1
        val track = sink.track
        if (track == null) {
            val written = AudioNative.write(handle, data, waitBudgetMs)
            if (written < 0) {
                // The AAudio stream died after a successful open: prefer the
                // AudioTrack path next time instead of retrying every frame.
                markAaFailureLocked(handle, sink.deviceId)
                closeLocked(handle)
            }
            return written
        }
        return try {
            val written = track.write(data, 0, data.size)
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write failed: $written")
                closeLocked(handle)
            }
            written
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack.write threw: ${t.message}")
            closeLocked(handle)
            -1
        }
    }

    /** Stops and releases sink [handle]. */
    @Synchronized
    fun close(handle: Int) {
        closeLocked(handle)
    }

    /** Stops and releases every open sink. */
    @Synchronized
    fun closeAll() {
        for (sink in sinks.values) {
            releaseTrack(sink.track)
        }
        sinks.clear()
        aaCooldownUntil.clear()
        AudioNative.closeAll()
    }

    private fun aaAllowedLocked(handle: Int, deviceId: Int): Boolean {
        val key = SinkKey(handle, deviceId)
        val until = aaCooldownUntil[key] ?: return true
        if (System.nanoTime() < until) return false
        aaCooldownUntil.remove(key)
        return true
    }

    private fun markAaFailureLocked(handle: Int, deviceId: Int) {
        aaCooldownUntil[SinkKey(handle, deviceId)] =
            System.nanoTime() + AAUDIO_RETRY_COOLDOWN_MS * 1_000_000L
    }

    private fun clearAaFailureLocked(handle: Int, deviceId: Int) {
        aaCooldownUntil.remove(SinkKey(handle, deviceId))
    }

    @Synchronized
    private fun closeLocked(handle: Int) {
        val sink = sinks.remove(handle)
        if (sink?.track != null) {
            releaseTrack(sink.track)
        } else {
            // Close the native handle even when Kotlin has no state for it.
            AudioNative.close(handle)
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    @Synchronized
    private fun refreshDevicesLocked(): List<Device> {
        val manager = audioManager ?: return emptyList()
        val infos = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (t: Throwable) {
            Log.e(TAG, "getDevices failed: ${t.message}")
            emptyArray()
        }

        // Rebuild the id -> AudioDeviceInfo map for the current device set. Ids
        // that disappear (unplugged) must fall back to the first output.
        deviceInfos.clear()
        val result = ArrayList<Device>(infos.size)
        for (info in infos) {
            if (!info.isSink) continue
            when (info.type) {
                // The earpiece is technically an output, but routing haptics or
                // controller audio to it is never useful (and it is too quiet).
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
                AudioDeviceInfo.TYPE_TELEPHONY -> continue
            }
            if (info.id <= 0) continue
            deviceInfos[info.id] = info
            // The raw product name for the built-in speaker is often a model code
            // or the phone's own name; label it explicitly instead.
            val name = if (info.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                AppStrings.get(R.string.i18n_phone_speaker)
            } else {
                info.productName?.toString()?.trim().orEmpty()
            }
            result.add(
                Device(
                    info.id,
                    name.ifBlank { AppStrings.get(R.string.i18n_sound_device_0, result.size + 1) },
                ),
            )
        }
        deviceOrder = result.map { it.id }
        return result
    }

    @Synchronized
    private fun resolveDeviceIdLocked(deviceId: Int): Int {
        if (deviceInfos.isEmpty()) refreshDevicesLocked()
        if (deviceId != AudioDevice.AUTO_SOUND_DEVICE_ID && deviceInfos.containsKey(deviceId)) {
            return deviceId
        }
        // AUTO (and ids that no longer exist) resolve to the first enumerated
        // output, matching the order shown in the settings spinner.
        return deviceOrder.firstOrNull() ?: DEFAULT_DEVICE
    }

    private fun createAudioTrackLocked(deviceId: Int, sampleRate: Int, channels: Int): AudioTrack? {
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return null
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val preferred = deviceInfos[deviceId]
        buildAudioTrack(attributes, format, minBuffer, preferred)?.let { return it }
        // A stale/unavailable route must not break playback: retry on the default
        // device before giving up.
        if (preferred != null) {
            Log.w(TAG, "AudioTrack route ${preferred.id} failed, retrying system default")
            return buildAudioTrack(attributes, format, minBuffer, null)
        }
        return null
    }

    private fun buildAudioTrack(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferBytes: Int,
        preferred: AudioDeviceInfo?,
    ): AudioTrack? {
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack build failed: ${t.message}")
            return null
        }
        if (preferred != null) {
            val routed = try {
                track.setPreferredDevice(preferred)
            } catch (_: Throwable) {
                false
            }
            if (!routed) {
                // The route is unavailable, but playback can still continue on the
                // system default device; do not drop audio because of routing.
                Log.w(TAG, "AudioTrack could not select device ${preferred.id}; using default route")
            }
        }
        return track
    }
}
