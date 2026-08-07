package com.zyz4.gamepademu.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.zyz4.gamepademu.model.AudioOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Singleton

data class AudioTrackInfo(
    val leftVoiceCoilAmplitude: Int = 0,
    val rightVoiceCoilAmplitude: Int = 0,
    val controllerAudioAmplitude: Int = 0,
)

@Singleton
class AudioPlaybackService {

    @Volatile
    private lateinit var androidContext: android.content.Context

    fun initContext(context: android.content.Context) {
        androidContext = context
    }

    companion object {
        private const val TAG = "AudioPlayback"
        // Phone motor smoothing and deadzone
        private const val MOTOR_SMOOTH_FACTOR = 0.65f
        private const val MOTOR_DEADSHELL_THRESHOLD = 0.05f
        private const val MOTOR_VIBRATE_DURATION_MS = 20L
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

    // Phone motor vibration state
    private var motorSmoothTotal = 0f
    private var lastVibrateTime = 0L
    private var lastHasMotorOutput = false

    private val _vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = androidContext.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            androidContext.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

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
        _vibrator.cancel()
        audioTrack = null
    }

    fun resumeIfStopped() {}

    var onVibroOutput: ((strong: Int, weak: Int) -> Unit)? = null

    private fun hasPhoneMotorOutput(): Boolean {
        return leftOutput == AudioOutput.PHONE_MOTOR ||
               rightOutput == AudioOutput.PHONE_MOTOR ||
               controllerAudio == AudioOutput.PHONE_MOTOR
    }

    private fun hasControllerMotorOutput(): Boolean {
        return leftOutput == AudioOutput.CONTROLLER_MOTOR_1 ||
               leftOutput == AudioOutput.CONTROLLER_MOTOR_2 ||
               rightOutput == AudioOutput.CONTROLLER_MOTOR_1 ||
               rightOutput == AudioOutput.CONTROLLER_MOTOR_2 ||
               controllerAudio == AudioOutput.CONTROLLER_MOTOR_1 ||
               controllerAudio == AudioOutput.CONTROLLER_MOTOR_2
    }

    private fun applyControllerMotorOutput(leftAmp: Int, rightAmp: Int, totalAmp: Int) {
        if (!hasControllerMotorOutput()) return

        var strongMotor = 0
        var weakMotor = 0

        fun addMotor(target: AudioOutput, current: Int) {
            if (target == AudioOutput.CONTROLLER_MOTOR_1) strongMotor = maxOf(strongMotor, current)
            else if (target == AudioOutput.CONTROLLER_MOTOR_2) weakMotor = maxOf(weakMotor, current)
        }

        addMotor(leftOutput, leftAmp)
        addMotor(rightOutput, rightAmp)
        addMotor(controllerAudio, totalAmp)

        onVibroOutput?.invoke(strongMotor, weakMotor)
    }

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

        if (pcm.isEmpty()) {
            leftVoiceCoilAmplitude = 0
            rightVoiceCoilAmplitude = 0
            coilSmoothLeft = 0f
            coilSmoothRight = 0f
            coilIndex++
            _trackInfo.value = AudioTrackInfo(
                leftVoiceCoilAmplitude = 0,
                rightVoiceCoilAmplitude = 0,
                controllerAudioAmplitude = 0,
            )
            return
        }

        val inputCh = maxOf(channels, 4)
        val bytesPerFrame = inputCh * 2
        val numSamples = pcm.size / bytesPerFrame

        if (numSamples == 0) return

        // Channel layout (ch0 unused):
        //   ch1 = controller audio (speaker)
        //   ch2 = left voice coil (left motor)
        //   ch3 = right voice coil (right motor)
        val controllerCh = 1
        val leftVcmCh = 2
        val rightVcmCh = 3

        // RMS per channel
        var controllerEnergy = 0.0
        var leftVcmEnergy = 0.0
        var rightVcmEnergy = 0.0
        var energyCount = 0

        for (s in 0 until numSamples) {
            val ch2Off = s * bytesPerFrame + leftVcmCh * 2
            val v2 = if (ch2Off + 1 < pcm.size) leBytesToShort(pcm, ch2Off) else 0
            if (v2 != 0.toShort()) {
                leftVcmEnergy += v2 * v2.toDouble()
                energyCount++
            }

            val ch3Off = s * bytesPerFrame + rightVcmCh * 2
            val v3 = if (ch3Off + 1 < pcm.size) leBytesToShort(pcm, ch3Off) else 0
            if (v3 != 0.toShort()) {
                rightVcmEnergy += v3 * v3.toDouble()
                energyCount++
            }

            val ch1Off = s * bytesPerFrame + controllerCh * 2
            val v1 = if (ch1Off + 1 < pcm.size) leBytesToShort(pcm, ch1Off) else 0
            if (v1 != 0.toShort()) {
                controllerEnergy += v1 * v1.toDouble()
                energyCount++
            }
        }

        val leftRms = if (energyCount > 0) Math.sqrt(leftVcmEnergy / energyCount) * 2.0 else 0.0
        val rightRms = if (energyCount > 0) Math.sqrt(rightVcmEnergy / energyCount) * 2.0 else 0.0
        val totalRms = if (energyCount > 0) Math.sqrt(controllerEnergy / energyCount) * 2.0 else 0.0

        val instantLeft = (leftRms / 255.0).toFloat().coerceIn(0f, 1f) * 255f
        val instantRight = (rightRms / 255.0).toFloat().coerceIn(0f, 1f) * 255f
        val instantTotal = (totalRms / 255.0).toFloat().coerceIn(0f, 1f) * 255f

        leftVoiceCoilAmplitude = instantLeft.toInt().coerceIn(0, 255)
        rightVoiceCoilAmplitude = instantRight.toInt().coerceIn(0, 255)

        val idx = coilIndex % leftVoiceCoilData.size
        leftVoiceCoilData[idx] = (instantLeft / 255f).coerceIn(0f, 1f)
        rightVoiceCoilData[idx] = (instantRight / 255f).coerceIn(0f, 1f)
        coilIndex++

        _trackInfo.value = AudioTrackInfo(
            leftVoiceCoilAmplitude = leftVoiceCoilAmplitude,
            rightVoiceCoilAmplitude = rightVoiceCoilAmplitude,
            controllerAudioAmplitude = instantTotal.toInt().coerceIn(0, 255),
        )

        val leftAmp = leftVoiceCoilAmplitude
        val rightAmp = rightVoiceCoilAmplitude
        val totalAmp = instantTotal.toInt().coerceIn(0, 255)

        // Phone motor output (before play check, independent of speaker output)
        var phoneMotorIntensity = 0
        if (leftOutput == AudioOutput.PHONE_MOTOR) {
            phoneMotorIntensity = maxOf(phoneMotorIntensity, leftAmp)
        }
        if (rightOutput == AudioOutput.PHONE_MOTOR) {
            phoneMotorIntensity = maxOf(phoneMotorIntensity, rightAmp)
        }
        if (controllerAudio == AudioOutput.PHONE_MOTOR && gameVibrationEnabled) {
            phoneMotorIntensity = maxOf(phoneMotorIntensity, totalAmp)
        }
        if (phoneMotorIntensity > 0) {
            triggerPhoneVibrator(phoneMotorIntensity)
        }

        // Controller motor output
        applyControllerMotorOutput(leftAmp, rightAmp, totalAmp)

        val play = (leftOutput != AudioOutput.NONE && leftOutput != AudioOutput.PHONE_MOTOR) ||
                   (rightOutput != AudioOutput.NONE && rightOutput != AudioOutput.PHONE_MOTOR) ||
                   (gameVibrationEnabled && controllerAudio != AudioOutput.NONE && controllerAudio != AudioOutput.PHONE_MOTOR)
        if (!play) return

        // Allocate output: numSamples stereo = numSamples * 2 channels * 2 bytes
        val stereoSize = numSamples * 4
        val stereoBuf = IntArray(stereoSize / 2)

        // Determine which audio sources to play and where
        val playCtrlAudio = gameVibrationEnabled && controllerAudio != AudioOutput.NONE && controllerAudio != AudioOutput.PHONE_MOTOR
        val playLeftCh2 = leftOutput == AudioOutput.LEFT_SPEAKER
        val playRightCh3 = rightOutput == AudioOutput.RIGHT_SPEAKER

        for (s in 0 until numSamples) {
            val outOff = s * 2

            if (playCtrlAudio) {
                val ch1Off = s * bytesPerFrame + controllerCh * 2
                if (ch1Off + 1 < pcm.size) {
                    val s1 = leBytesToShort(pcm, ch1Off)
                    stereoBuf[outOff] += s1.toInt()
                    stereoBuf[outOff + 1] += s1.toInt()
                }
            }

            if (playLeftCh2) {
                val ch2Off = s * bytesPerFrame + leftVcmCh * 2
                if (ch2Off + 1 < pcm.size) {
                    val s2 = leBytesToShort(pcm, ch2Off)
                    stereoBuf[outOff] += s2.toInt()
                    stereoBuf[outOff + 1] += s2.toInt()
                }
            }

            if (playRightCh3) {
                val ch3Off = s * bytesPerFrame + rightVcmCh * 2
                if (ch3Off + 1 < pcm.size) {
                    val s3 = leBytesToShort(pcm, ch3Off)
                    stereoBuf[outOff] += s3.toInt()
                    stereoBuf[outOff + 1] += s3.toInt()
                }
            }
        }

        val outBytes = ByteArray(stereoSize)
        for (i in stereoBuf.indices) {
            val v = stereoBuf[i].toShort()
            outBytes[i * 2] = v.toInt().toByte()
            outBytes[i * 2 + 1] = (v.toInt() shr 8).toByte()
        }

        recreateTrackIfNeeded()
        val track = audioTrack ?: return

        val written = track.write(outBytes, 0, outBytes.size, AudioTrack.WRITE_NON_BLOCKING)
        if (written <= 0) {
            Log.e(TAG, "write failed: pcm=${pcm.size} stereo=$stereoSize written=$written")
        }
    }

    private fun triggerPhoneVibrator(intensity: Int) {
        if (intensity <= 5) return
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < MOTOR_VIBRATE_DURATION_MS) return

        val motorIntensity = intensity.coerceIn(15, 255)

        try {
            val effect = VibrationEffect.createOneShot(MOTOR_VIBRATE_DURATION_MS, motorIntensity)
            _vibrator.vibrate(effect)
            lastVibrateTime = now
            lastHasMotorOutput = true
        } catch (_: Exception) {}
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
        for (i in leftVoiceCoilData.indices) {
            if (leftVoiceCoilData[i] > 0f) {
                leftVoiceCoilData[i] *= 0.9f
                if (leftVoiceCoilData[i] < 0.001f) leftVoiceCoilData[i] = 0f
            }
        }
        var sum = 0f
        var count = 0
        for (v in leftVoiceCoilData) {
            if (v > 0f) { sum += v; count++ }
        }
        return if (count > 0) sum / count else 0f
    }

    fun getVoiceCoilEnvelopeRight(): Float {
        for (i in rightVoiceCoilData.indices) {
            if (rightVoiceCoilData[i] > 0f) {
                rightVoiceCoilData[i] *= 0.9f
                if (rightVoiceCoilData[i] < 0.001f) rightVoiceCoilData[i] = 0f
            }
        }
        var sum = 0f
        var count = 0
        for (v in rightVoiceCoilData) {
            if (v > 0f) { sum += v; count++ }
        }
        return if (count > 0) sum / count else 0f
    }
}