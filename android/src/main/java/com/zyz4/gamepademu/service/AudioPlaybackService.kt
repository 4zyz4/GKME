package com.zyz4.gamepademu.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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

    companion object {
        private const val TAG = "AudioPlayback"
    }

    private var audioTrack: AudioTrack? = null

    private val _trackInfo = MutableStateFlow(AudioTrackInfo())
    val trackInfo: StateFlow<AudioTrackInfo> = _trackInfo.asStateFlow()

    private var sampleRate = 48000
    private var channels = 4
    private var bitsPerSample = 16

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

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun resumeIfStopped() {}

    fun submitAudio(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val oldRate = this.sampleRate
        val oldCh = this.channels
        val oldBits = this.bitsPerSample

        if (sampleRate > 0) this.sampleRate = sampleRate
        if (channels > 0) this.channels = channels
        if (bitsPerSample > 0) this.bitsPerSample = bitsPerSample

        if (sampleRate > 0 && this.sampleRate != oldRate ||
            channels > 0 && this.channels != oldCh ||
            bitsPerSample > 0 && this.bitsPerSample != oldBits) {
            recreateTrackIfNeeded()
        }

        if (pcm.isEmpty()) return

        val inputCh = maxOf(channels, 4)
        val bytesPerFrame = inputCh * 2
        val numSamples = pcm.size / bytesPerFrame

        if (numSamples == 0) return

        // RMS for voice coil (ch1 = left, ch2 = right, skip ch0)
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        var totalEnergy = 0.0
        var energyCount = 0

        for (s in 0 until numSamples) {
            for (ch in 1 until inputCh) {
                val off = s * bytesPerFrame + ch * 2
                val v = leBytesToShort(pcm, off)
                if (v == 0.toShort()) continue
                when (ch) {
                    1 -> { leftEnergy += v * v.toDouble(); energyCount++ }
                    2 -> { rightEnergy += v * v.toDouble(); energyCount++ }
                    else -> { totalEnergy += v * v.toDouble(); energyCount++ }
                }
            }
        }

        val leftRms = if (energyCount > 0) Math.sqrt(leftEnergy / energyCount) * 2.0 else 0.0
        val rightRms = if (energyCount > 0) Math.sqrt(rightEnergy / energyCount) * 2.0 else 0.0
        val totalRms = if (energyCount > 0) Math.sqrt(totalEnergy / energyCount) * 2.0 else 0.0

        coilSmoothLeft = coilSmoothLeft * 0.7f + (leftRms / 255.0).toFloat() * 0.3f
        coilSmoothRight = coilSmoothRight * 0.7f + (rightRms / 255.0).toFloat() * 0.3f
        val smoothLeft = coilSmoothLeft.coerceIn(0f, 1f) * 255f
        val smoothRight = coilSmoothRight.coerceIn(0f, 1f) * 255f
        val smoothTotal = (totalRms / 255.0).toFloat().coerceIn(0f, 1f) * 255f

        leftVoiceCoilAmplitude = smoothLeft.toInt().coerceIn(0, 255)
        rightVoiceCoilAmplitude = smoothRight.toInt().coerceIn(0, 255)

        val idx = coilIndex % leftVoiceCoilData.size
        leftVoiceCoilData[idx] = (smoothLeft / 255f).coerceIn(0f, 1f)
        rightVoiceCoilData[idx] = (smoothRight / 255f).coerceIn(0f, 1f)
        coilIndex++

        _trackInfo.value = AudioTrackInfo(
            leftVoiceCoilAmplitude = leftVoiceCoilAmplitude,
            rightVoiceCoilAmplitude = rightVoiceCoilAmplitude,
            controllerAudioAmplitude = smoothTotal.toInt().coerceIn(0, 255),
        )

        val play = leftOutput != AudioOutput.NONE || rightOutput != AudioOutput.NONE ||
                   (gameVibrationEnabled && controllerAudio != AudioOutput.NONE)
        if (!play) return

        // Allocate output: numSamples stereo = numSamples * 2 channels * 2 bytes
        val stereoSize = numSamples * 4
        val stereoBuf = ByteArray(stereoSize)

        // Copy ch1 -> left channel, ch2 -> right channel
        for (s in 0 until numSamples) {
            val ch1Off = s * bytesPerFrame + 1 * 2
            val ch2Off = s * bytesPerFrame + 2 * 2
            val outOff = s * 4

            if (ch1Off + 1 < pcm.size) {
                stereoBuf[outOff] = pcm[ch1Off]
                stereoBuf[outOff + 1] = pcm[ch1Off + 1]
            }
            if (ch2Off + 1 < pcm.size) {
                stereoBuf[outOff + 2] = pcm[ch2Off]
                stereoBuf[outOff + 3] = pcm[ch2Off + 1]
            }
        }

        recreateTrackIfNeeded()
        val track = audioTrack ?: return

        val written = track.write(stereoBuf, 0, stereoBuf.size, AudioTrack.WRITE_NON_BLOCKING)
        if (written <= 0) {
            Log.e(TAG, "write failed: pcm=${pcm.size} stereo=$stereoSize written=$written")
        }
    }

    private fun leBytesToShort(bytes: ByteArray, offset: Int): Short {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun recreateTrackIfNeeded() {
        synchronized(this) {
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                return
            }

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {}

            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, format.channelMask, format.encoding)
            if (minBufSize <= 0) {
                Log.e(TAG, "Min buffer size too small: rate=$sampleRate")
                return
            }

            val bufSize = minBufSize
            Log.d(TAG, "AudioTrack: rate=$sampleRate buf=$bufSize min=$minBufSize")

            val track = AudioTrack(attr, format, bufSize, AudioTrack.MODE_STREAM, 0)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack init failed: ${track.state}")
                track.release()
                return
            }

            track.play()
            audioTrack = track
            Log.d(TAG, "AudioTrack created & playing")
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