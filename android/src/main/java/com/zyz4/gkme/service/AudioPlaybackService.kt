package com.zyz4.gkme.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.zyz4.gkme.model.AudioDevice
import com.zyz4.gkme.model.AudioDeviceType
import com.zyz4.gkme.model.AudioOutput
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

    private var voiceCoilDevice: AudioDevice = AudioDevice.PHONE_SPEAKER
    private var voiceCoilSwap = false
    private var controllerAudio: AudioOutput = AudioOutput.ALL_SPEAKERS
    private var motorOutputEnabled = true

    // Phone motor vibration state
    private var lastVibrateTime = 0L
    private var lastHasMotorOutput = false
    // Controller voice-coil output state (for cancelling when it goes silent)
    private var lastControllerMotorActive = false
    private var lastControllerMotorIndex = 0

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
        voiceCoilDevice: AudioDevice,
        voiceCoilSwap: Boolean,
        controllerAudio: AudioOutput,
        motorOutputEnabled: Boolean,
    ) {
        if (this.voiceCoilDevice != voiceCoilDevice && lastControllerMotorActive) {
            onControllerMotorOutput?.invoke(lastControllerMotorIndex, 0, 0)
            lastControllerMotorActive = false
        }
        this.voiceCoilDevice = voiceCoilDevice
        this.voiceCoilSwap = voiceCoilSwap
        this.controllerAudio = controllerAudio
        this.motorOutputEnabled = motorOutputEnabled
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

    /** (controllerIndex, leftAmp, rightAmp) — controller motor output for the voice coil. */
    var onControllerMotorOutput: ((controllerIndex: Int, leftAmp: Int, rightAmp: Int) -> Unit)? = null

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

        for (s in 0 until numSamples) {
            val ch2Off = s * bytesPerFrame + leftVcmCh * 2
            val v2 = if (ch2Off + 1 < pcm.size) leBytesToShort(pcm, ch2Off) else 0.toShort()
            leftVcmEnergy += v2.toDouble() * v2.toDouble()

            val ch3Off = s * bytesPerFrame + rightVcmCh * 2
            val v3 = if (ch3Off + 1 < pcm.size) leBytesToShort(pcm, ch3Off) else 0.toShort()
            rightVcmEnergy += v3.toDouble() * v3.toDouble()

            val ch1Off = s * bytesPerFrame + controllerCh * 2
            val v1 = if (ch1Off + 1 < pcm.size) leBytesToShort(pcm, ch1Off) else 0.toShort()
            controllerEnergy += v1.toDouble() * v1.toDouble()
        }

        val leftRms = Math.sqrt(leftVcmEnergy / numSamples)
        val rightRms = Math.sqrt(rightVcmEnergy / numSamples)
        val totalRms = Math.sqrt(controllerEnergy / numSamples)

        val instantLeft = (leftRms / Short.MAX_VALUE.toDouble()).toFloat() * 255f
        val instantRight = (rightRms / Short.MAX_VALUE.toDouble()).toFloat() * 255f
        val instantTotal = (totalRms / Short.MAX_VALUE.toDouble()).toFloat() * 255f

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

        // ── Voice coil output routing ──
        when (voiceCoilDevice.type) {
            AudioDeviceType.NONE, AudioDeviceType.PHONE_MOTOR, AudioDeviceType.PHONE_SPEAKER -> {
                if (lastControllerMotorActive) {
                    onControllerMotorOutput?.invoke(lastControllerMotorIndex, 0, 0)
                    lastControllerMotorActive = false
                }
                if (voiceCoilDevice.type == AudioDeviceType.PHONE_MOTOR) {
                    vibratePhoneMotors(leftAmp, rightAmp, voiceCoilSwap)
                }
            }
            AudioDeviceType.CONTROLLER -> {
                val m0 = if (voiceCoilSwap) rightAmp else leftAmp
                val m1 = if (voiceCoilSwap) leftAmp else rightAmp
                val index = voiceCoilDevice.controllerIndex
                if (m0 > 1 || m1 > 1) {
                    if (lastControllerMotorActive && lastControllerMotorIndex != index) {
                        onControllerMotorOutput?.invoke(lastControllerMotorIndex, 0, 0)
                    }
                    onControllerMotorOutput?.invoke(index, m0, m1)
                    lastControllerMotorActive = true
                    lastControllerMotorIndex = index
                } else if (lastControllerMotorActive) {
                    onControllerMotorOutput?.invoke(lastControllerMotorIndex, 0, 0)
                    lastControllerMotorActive = false
                }
            }
        }

        // ── Phone speaker output ──
        val playVoiceCoil = voiceCoilDevice.type == AudioDeviceType.PHONE_SPEAKER
        val playControllerAudio = motorOutputEnabled && controllerAudio == AudioOutput.ALL_SPEAKERS
        if (!playVoiceCoil && !playControllerAudio) return

        // Allocate output: numSamples stereo = numSamples * 2 channels * 2 bytes
        val stereoSize = numSamples * 4
        val stereoBuf = IntArray(stereoSize / 2)

        for (s in 0 until numSamples) {
            val outOff = s * 2

            if (playControllerAudio) {
                val ch1Off = s * bytesPerFrame + controllerCh * 2
                if (ch1Off + 1 < pcm.size) {
                    val s1 = leBytesToShort(pcm, ch1Off).toInt()
                    stereoBuf[outOff] += s1
                    stereoBuf[outOff + 1] += s1
                }
            }

            if (playVoiceCoil) {
                val ch2Off = s * bytesPerFrame + leftVcmCh * 2
                val s2 = if (ch2Off + 1 < pcm.size) leBytesToShort(pcm, ch2Off).toInt() else 0
                val ch3Off = s * bytesPerFrame + rightVcmCh * 2
                val s3 = if (ch3Off + 1 < pcm.size) leBytesToShort(pcm, ch3Off).toInt() else 0
                stereoBuf[outOff] += if (voiceCoilSwap) s3 else s2
                stereoBuf[outOff + 1] += if (voiceCoilSwap) s2 else s3
            }
        }

        val outBytes = ByteArray(stereoSize)
        for (i in stereoBuf.indices) {
            val v = stereoBuf[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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

    /** Drives the phone motors from the voice-coil channels; motor0 = left, motor1 = right. */
    private fun vibratePhoneMotors(left: Int, right: Int, swap: Boolean) {
        val m0 = (if (swap) right else left).coerceIn(0, 255)
        val m1 = (if (swap) left else right).coerceIn(0, 255)
        if (m0 <= 1 && m1 <= 1) return
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < MOTOR_VIBRATE_DURATION_MS) return
        lastVibrateTime = now
        lastHasMotorOutput = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = androidContext.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val ids = vm?.vibratorIds
            if (vm != null && ids != null && ids.size >= 2) {
                try {
                    vm.cancel()
                    val combo = CombinedVibration.startParallel()
                    if (m0 > 1) combo.addVibrator(
                        ids[0], VibrationEffect.createOneShot(MOTOR_VIBRATE_DURATION_MS, m0.coerceIn(2, 255)))
                    if (m1 > 1) combo.addVibrator(
                        ids[1], VibrationEffect.createOneShot(MOTOR_VIBRATE_DURATION_MS, m1.coerceIn(2, 255)))
                    vm.vibrate(combo.combine())
                    return
                } catch (_: Exception) {}
            }
        }

        try {
            _vibrator.vibrate(
                VibrationEffect.createOneShot(MOTOR_VIBRATE_DURATION_MS, maxOf(m0, m1).coerceIn(2, 255)))
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
                .setFlags(0x2000000)
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