package com.zyz4.gamepademu.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.zyz4.gamepademu.model.AudioOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AudioTrackInfo(
    val leftVoiceCoilAmplitude: Int = 0,
    val rightVoiceCoilAmplitude: Int = 0,
    val controllerAudioAmplitude: Int = 0,
)

@Singleton
class AudioPlaybackService @Inject constructor() {

    private var audioTrack: AudioTrack? = null
    private var isStarted = false
    private var lastPcmQueue = ByteArray(0)
    private var writeOffset = 0

    private val _trackInfo = MutableStateFlow(AudioTrackInfo())
    val trackInfo: StateFlow<AudioTrackInfo> = _trackInfo.asStateFlow()

    private var sampleRate = 48000
    private var channels = 4
    private var bitsPerSample = 16
    private var frameSize = channels * (bitsPerSample / 8)
    private var leftVoiceCoilData = FloatArray(64)
    private var rightVoiceCoilData = FloatArray(64)
    private var coilIndex = 0
    private var coilSmoothLeft = 0f
    private var coilSmoothRight = 0f
    private var leftVoiceCoilAmplitude = 0
    private var rightVoiceCoilAmplitude = 0

    private var leftOutput = AudioOutput.LEFT_SPEAKER
    private var rightOutput = AudioOutput.RIGHT_SPEAKER
    private var controllerAudio = AudioOutput.ALL_SPEAKERS
    private var gameVibrationEnabled = true
    var onVoiceCoilClick: ((strong: Boolean) -> Unit)? = null
    var onControllerAudioClick: (() -> Unit)? = null

    fun setSettings(
        leftOutput: AudioOutput,
        rightOutput: AudioOutput,
        controllerAudio: AudioOutput,
        gameVibrationEnabled: Boolean,
    ) {
        this.leftOutput = leftOutput
        this.rightOutput = rightOutput
        this.controllerAudio = controllerAudio
        this.gameVibrationEnabled = gameVibrationEnabled
    }

    fun startIfneeded() {
        if (isStarted) return
        val shouldPlay = shouldPlayControllerAudio() || leftOutput != AudioOutput.NONE || rightOutput != AudioOutput.NONE
        if (!shouldPlay) return
        ensureAudioTrack()
    }

    fun stop() {
        isStarted = true
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun resumeIfStopped() {
        if (audioTrack == null) {
            isStarted = false
        }
    }

    fun submitAudio(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        if (sampleRate > 0) this.sampleRate = sampleRate
        if (channels > 0) this.channels = channels
        if (bitsPerSample > 0) this.bitsPerSample = bitsPerSample
        frameSize = this.channels * (this.bitsPerSample / 8)
        if (frameSize <= 0) return
        if (pcm.isEmpty()) return

        val playControllerAudio = gameVibrationEnabled && shouldPlayControllerAudio()
        val playSpeakers = leftOutput != AudioOutput.NONE || rightOutput != AudioOutput.NONE

        if (!playControllerAudio && !playSpeakers) return

        var leftEnergy = 0.0
        var rightEnergy = 0.0
        var totalEnergy = 0.0
        var count = 0

        if (channels >= 3 && pcm.size >= frameSize) {
            val numSamples = pcm.size / frameSize
            for (i in 0 until numSamples) {
                val base = i * frameSize
                if (base + 7 >= pcm.size) break
                val v1 = leBytesToShort(pcm, base)
                val v3 = leBytesToShort(pcm, base + 4)
                val v4 = leBytesToShort(pcm, base + 6)
                val zero = 0.toShort()
                if (v3 != zero) { leftEnergy += v3 * v3.toDouble(); count++ }
                if (v4 != zero) { rightEnergy += v4 * v4.toDouble(); count++ }
                if (v1 != zero) { totalEnergy += v1 * v1.toDouble(); count++ }
            }
        } else {
            for (i in 0 until pcm.size - 1 step 2) {
                val v = leBytesToShort(pcm, i)
                val zero = 0.toShort()
                if (v != zero) { totalEnergy += v * v.toDouble(); count++ }
            }
        }

        val leftRms = if (count > 0) Math.sqrt(leftEnergy / count) * 2.0 else 0.0
        val rightRms = if (count > 0) Math.sqrt(rightEnergy / count) * 2.0 else 0.0
        val totalRms = if (count > 0) Math.sqrt(totalEnergy / count) * 2.0 else 0.0

        coilSmoothLeft = coilSmoothLeft * 0.7f + (leftRms / 255.0).toFloat().coerceIn(0f, 1f) * 0.3f
        coilSmoothRight = coilSmoothRight * 0.7f + (rightRms / 255.0).toFloat().coerceIn(0f, 1f) * 0.3f
        val smoothLeft = (coilSmoothLeft * 255f).toInt().coerceIn(0, 255)
        val smoothRight = (coilSmoothRight * 255f).toInt().coerceIn(0, 255)
        val smoothTotal = (totalRms / 255.0).toFloat().coerceIn(0f, 1f) * 255f

        leftVoiceCoilAmplitude = smoothLeft
        rightVoiceCoilAmplitude = smoothRight

        val idx = coilIndex % leftVoiceCoilData.size
        leftVoiceCoilData[idx] = if (smoothLeft > 0) smoothLeft.toFloat() / 255f else 0f
        rightVoiceCoilData[idx] = if (smoothRight > 0) smoothRight.toFloat() / 255f else 0f
        coilIndex++

        if (!playControllerAudio && !playSpeakers) return

        val shouldPlay = (playControllerAudio || playSpeakers)
        if (!shouldPlay) {
            try { audioTrack?.pause() } catch (_: Exception) {}
            return
        }

        // Downmix 4ch interleaved -> 1ch mono (sum and divide by channel count)
        var outBuf = pcm
        if (this.channels > 2) {
            val monoSize = pcm.size / this.channels
            val monoBuf = ByteArray(monoSize)
            val step = this.channels * 2 // bytes per sample
            for (i in 0 until monoSize / 2) {
                var sum = 0
                for (ch in 0 until this.channels) {
                    val off = i * step + ch * 2
                    if (off + 1 < pcm.size) {
                        sum += leBytesToShort(pcm, off).toInt()
                    }
                }
                val avg = sum / this.channels
                monoBuf[i * 2] = (avg and 0xFF).toByte()
                monoBuf[i * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
            }
            outBuf = monoBuf
        }

        // Append to ring buffer
        val newBuf = java.io.ByteArrayOutputStream().apply {
            if (lastPcmQueue.isNotEmpty()) write(lastPcmQueue)
            write(outBuf)
        }.toByteArray()
        lastPcmQueue = ByteArray(0)

        // Write in chunks to AudioTrack
        ensureAudioTrack()
        val audio = audioTrack ?: return
        try {
            if (audio.state != AudioTrack.STATE_INITIALIZED) {
                audio.release()
                ensureAudioTrack()
                val audio2 = audioTrack ?: return
                audio2.play()
                audio2.write(newBuf, 0, newBuf.size, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                audio.play()
                audio.write(newBuf, 0, newBuf.size, AudioTrack.WRITE_NON_BLOCKING)
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayback", "write exception", e)
        }

        _trackInfo.value = AudioTrackInfo(
            leftVoiceCoilAmplitude = smoothLeft,
            rightVoiceCoilAmplitude = smoothRight,
            controllerAudioAmplitude = smoothTotal.toInt().coerceIn(0, 255),
        )
    }

    private fun leBytesToShort(bytes: ByteArray, offset: Int): Short {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun ensureAudioTrack() {
        synchronized(this) {
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {}
                audioTrack = null
            }

            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .apply {
                    when (channels) {
                        1 -> setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        2 -> setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        else -> setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    }
                }
                .build()

            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, format.channelMask, format.encoding)
            if (minBufSize <= 0) return

            val track = AudioTrack(
                attr,
                format,
                minBufSize * 4,
                AudioTrack.MODE_STREAM,
                0
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) return

            try {
                audioTrack?.release()
            } catch (_: Exception) {}
            audioTrack = track
            isStarted = true
        }
    }

    private fun shouldPlayControllerAudio(): Boolean {
        return controllerAudio != AudioOutput.NONE
    }

    fun getVoiceCoilEnvelopeLeft(): Float {
        var sum = 0f
        var count = 0
        for (v in leftVoiceCoilData) {
            if (v > 0f) { sum += v; count++ }
        }
        return if (count > 0) sum / count else 0f
    }

    fun getVoiceCoilEnvelopeRight(): Float {
        var sum = 0f
        var count = 0
        for (v in rightVoiceCoilData) {
            if (v > 0f) { sum += v; count++ }
        }
        return if (count > 0) sum / count else 0f
    }
}