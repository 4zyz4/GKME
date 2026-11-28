package com.zyz4.gkme.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Captures the phone microphone as mono 16-bit PCM and hands short frames to
 * [onFrame]. Used by the controller role to forward the microphone to the PC's
 * virtual DualShock 4 / DualSense microphone endpoint.
 *
 * The capture thread only runs while [setEnabled] is true and RECORD_AUDIO is
 * granted; every failure path degrades to "no microphone" instead of throwing.
 */
class MicrophoneForwarder(
    private val context: Context,
    private val onFrame: (pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int, frameIndex: Int) -> Unit,
) {

    companion object {
        private const val TAG = "GkmeMic"
        const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        /** 10 ms of mono S16: 960 bytes stays below a typical 1500-byte UDP MTU. */
        private const val FRAME_BYTES = SAMPLE_RATE / 100 * CHANNELS * (BITS_PER_SAMPLE / 8)

        private fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private val lock = Any()
    private var enabled = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var smoothedLevel = 0f

    /** Latest captured level: 0..255, RMS with a perceptual curve and smoothing. */
    @Volatile
    var amplitude: Int = 0
        private set

    /** Starts or stops capture. Safe to call from any thread and repeatedly. */
    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (enabled == value) return
            enabled = value
            if (value) startLocked() else stopLocked()
        }
    }

    private fun startLocked() {
        amplitude = 0
        smoothedLevel = 0f
        if (!hasPermission(context)) {
            Log.w(TAG, "microphone forwarding enabled without RECORD_AUDIO")
            return
        }
        val rec = openRecord()
        if (rec == null) {
            Log.e(TAG, "AudioRecord could not be opened at ${SAMPLE_RATE}Hz mono")
            return
        }
        record = rec
        val worker = Thread({ readLoop(rec) }, "GkmeMicCapture").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
        thread = worker
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord.startRecording failed: ${t.message}")
            thread = null
            record = null
            runCatching { rec.release() }
            return
        }
        worker.start()
    }

    private fun stopLocked() {
        amplitude = 0
        smoothedLevel = 0f
        val rec = record
        record = null
        val worker = thread
        thread = null
        // stop() unblocks a pending read(); the worker then releases the record.
        runCatching { rec?.stop() }
        if (worker != null) {
            try {
                worker.join(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (worker == null) runCatching { rec?.release() }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val bufferBytes = maxOf(minBuffer, FRAME_BYTES * 4)
        // VOICE_COMMUNICATION enables the platform echo canceller / noise
        // suppressor on most devices, which is what a controller microphone
        // wants; fall back to the raw MIC source when a ROM rejects it.
        return buildRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufferBytes)
            ?: buildRecord(MediaRecorder.AudioSource.MIC, bufferBytes)
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(source: Int, bufferBytes: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord build (source=$source) failed: ${t.message}")
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return null
        }
        return rec
    }

    private fun readLoop(rec: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }
        val buffer = ByteArray(FRAME_BYTES)
        var frameIndex = 0
        try {
            while (true) {
                val running = synchronized(lock) { enabled && record === rec }
                if (!running) break
                val read = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read < 0) break
                if (read == 0) continue
                updateAmplitude(buffer, read)
                try {
                    onFrame(
                        buffer.copyOf(read),
                        SAMPLE_RATE,
                        CHANNELS,
                        BITS_PER_SAMPLE,
                        frameIndex++,
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "microphone frame callback failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "microphone capture stopped: ${t.message}")
        } finally {
            amplitude = 0
            smoothedLevel = 0f
            runCatching { rec.stop() }
            runCatching { rec.release() }
            synchronized(lock) {
                if (record === rec) {
                    record = null
                    thread = null
                }
            }
        }
    }

    /** Converts one 16-bit frame into a 0..255 meter value. RMS keeps quiet
     * noise from flickering the bar; the square-root curve matches how loudness
     * is perceived, so normal speech still moves it. */
    private fun updateAmplitude(pcm: ByteArray, length: Int) {
        val samples = length / 2
        if (samples <= 0) {
            amplitude = 0
            return
        }
        var energy = 0.0
        var offset = 0
        while (offset + 1 < length) {
            val sample = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
            energy += sample.toDouble() * sample
            offset += 2
        }
        val rms = sqrt(energy / samples)
        val normalized = (rms / Short.MAX_VALUE.toDouble()).coerceIn(0.0, 1.0)
        val instant = (sqrt(normalized) * 255.0).toFloat()
        // Fast attack, slower release: a meter that jumps instantly but falls
        // gradually is easier to read at the 50 ms UI polling rate.
        smoothedLevel = if (instant > smoothedLevel) {
            smoothedLevel + (instant - smoothedLevel) * 0.65f
        } else {
            smoothedLevel + (instant - smoothedLevel) * 0.25f
        }
        amplitude = smoothedLevel.toInt().coerceIn(0, 255)
    }
}
