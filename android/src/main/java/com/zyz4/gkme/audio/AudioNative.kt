package com.zyz4.gkme.audio

/**
 * Thin wrapper around the native AAudio output bridge (see audio_out.cpp).
 *
 * This is the preferred low-latency path. Everything is failure-tolerant: when
 * the library cannot be loaded or AAudio cannot open the device, [open] returns
 * false and [AudioOutput] falls back to an [android.media.AudioTrack].
 */
internal object AudioNative {

    private val loaded: Boolean = try {
        System.loadLibrary("gkme_audio")
        true
    } catch (_: Throwable) {
        false
    }

    /** True when the native AAudio library is present on this device. */
    val available: Boolean get() = loaded

    /**
     * Opens a low-latency AAudio sink identified by [handle] on [deviceId]
     * (0 = system default) using interleaved S16 PCM. Returns false when the
     * stream could not be opened so the caller can fall back to AudioTrack.
     */
    fun open(handle: Int, deviceId: Int, sampleRate: Int, channels: Int, maxQueuedMs: Int): Boolean {
        if (!loaded) return false
        return try {
            nativeOpen(handle, deviceId, sampleRate, channels, maxQueuedMs)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Writes interleaved S16 [data] to the sink, blocking up to [waitBudgetMs].
     * Returns the number of bytes written, 0 for an empty buffer or -1 when the
     * sink is closed or failed.
     */
    fun write(handle: Int, data: ByteArray, waitBudgetMs: Int): Int {
        if (!loaded) return -1
        return try {
            nativeWrite(handle, data, waitBudgetMs)
        } catch (_: Throwable) {
            -1
        }
    }

    fun close(handle: Int) {
        if (!loaded) return
        try {
            nativeClose(handle)
        } catch (_: Throwable) {
        }
    }

    fun closeAll() {
        if (!loaded) return
        try {
            nativeCloseAll()
        } catch (_: Throwable) {
        }
    }

    private external fun nativeOpen(handle: Int, deviceId: Int, sampleRate: Int, channels: Int, maxQueuedMs: Int): Boolean

    private external fun nativeWrite(handle: Int, data: ByteArray, waitBudgetMs: Int): Int

    private external fun nativeClose(handle: Int)

    private external fun nativeCloseAll()
}
