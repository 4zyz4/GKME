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
        // 490 four-channel frames * 8 bytes = 3920 bytes, the native USB PCM frame limit.
        private const val USB_MAX_FRAMES = 490
        // The DualSense USB audio endpoint is fixed at 48 kHz.
        private const val USB_PCM_RATE = 48000
        // DS4 uses 32 kHz USB audio endpoint (handled separately).
        private const val DS4_USB_PCM_RATE = 32000
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

    // ── USB controller PCM output (voice coil / speaker) ──

    /** True when the controller at [controllerIndex] can play PCM through its voice coil. */
    var supportsVoiceCoilPcm: ((controllerIndex: Int) -> Boolean)? = null

    /** True when the controller at [controllerIndex] exposes a speaker/audio endpoint. */
    var supportsControllerAudio: ((controllerIndex: Int) -> Boolean)? = null

    /** Sends a 4-channel, 48 kHz, S16LE frame to the controller voice coil. */
    var onVoiceCoilPcm: ((controllerIndex: Int, frame: ByteArray) -> Boolean)? = null

    /** Sends a 4-channel, 48 kHz, S16LE frame to the controller speaker. */
    var onControllerAudioPcm: ((controllerIndex: Int, frame: ByteArray) -> Boolean)? = null

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
                val index = voiceCoilDevice.controllerIndex
                if (sampleRate == USB_PCM_RATE && supportsVoiceCoilPcm?.invoke(index) == true) {
                    // Advanced path: the PCM is streamed further below.
                    if (lastControllerMotorActive) {
                        onControllerMotorOutput?.invoke(lastControllerMotorIndex, 0, 0)
                        lastControllerMotorActive = false
                    }
                } else {
                    val m0 = if (voiceCoilSwap) rightAmp else leftAmp
                    val m1 = if (voiceCoilSwap) leftAmp else rightAmp
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
        }

// ── USB controller PCM output (voice coil + speaker) ──
        // One merged four-channel frame per target controller: ch0/ch1 = speaker,
        // ch2/ch3 = voice coil. This keeps the controller's native frame rate intact
        // even when both the voice coil and the speaker target the same device.
        // PC always sends 44100Hz — resample to 48000Hz (DS5) or 32000Hz (DS4) on the fly.
        val needsResampleToDS5 = voiceCoilDevice.type == AudioDeviceType.CONTROLLER &&
            supportsVoiceCoilPcm?.invoke(voiceCoilDevice.controllerIndex) == true &&
            sampleRate != USB_PCM_RATE
        val needsResampleToDS4 = controllerAudio.outputType == AudioOutput.OutputType.CONTROLLER &&
            supportsControllerAudio?.invoke(controllerAudio.index) == true &&
            sampleRate != DS4_USB_PCM_RATE
        val vcPcmIndex = if (voiceCoilDevice.type == AudioDeviceType.CONTROLLER &&
            supportsVoiceCoilPcm?.invoke(voiceCoilDevice.controllerIndex) == true
        ) voiceCoilDevice.controllerIndex else -1
        val caPcmIndex = if (controllerAudio.outputType == AudioOutput.OutputType.CONTROLLER &&
            supportsControllerAudio?.invoke(controllerAudio.index) == true
        ) controllerAudio.index else -1
        if (vcPcmIndex >= 0 || caPcmIndex >= 0) {
            Log.i(TAG, "USB PCM path selected: vcIndex=$vcPcmIndex caIndex=$caPcmIndex rate=$sampleRate ch=$channels needsResample=$needsResampleToDS5")
        }
        if (vcPcmIndex >= 0 || caPcmIndex >= 0) {
            val targets = LinkedHashSet<Int>()
            if (vcPcmIndex >= 0) targets.add(vcPcmIndex)
            if (caPcmIndex >= 0) targets.add(caPcmIndex)
            for (idx in targets) {
                val isVoiceCoil = idx == vcPcmIndex
                val targetRate = if (isVoiceCoil) USB_PCM_RATE else DS4_USB_PCM_RATE
                val resampledPcm = if (sampleRate != targetRate && pcm.size > 0) {
                    resamplePcm(pcm, sampleRate, targetRate, channels)
                } else {
                    pcm
                }
                val accepted = submitUsbFrames(
                    idx, resampledPcm, channels, resampledPcm.size / (4 * 2),
                    1, 2, 3,
                    includeControllerAudio = idx == caPcmIndex,
                    includeVoiceCoil = idx == vcPcmIndex,
                    swap = voiceCoilSwap,
                    voiceCoil = isVoiceCoil,
                )
                if (!accepted && isVoiceCoil) {
                    val m0 = if (voiceCoilSwap) rightAmp else leftAmp
                    val m1 = if (voiceCoilSwap) leftAmp else rightAmp
                    if (m0 > 1 || m1 > 1) {
                        onControllerMotorOutput?.invoke(idx, m0, m1)
                        lastControllerMotorActive = true
                        lastControllerMotorIndex = idx
                    } else if (lastControllerMotorActive) {
                        onControllerMotorOutput?.invoke(lastControllerMotorIndex, 0, 0)
                        lastControllerMotorActive = false
                    }
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

    /**
     * Re-packs the incoming four-channel frame into the DualSense USB audio layout
     * (ch0/ch1 = speaker, ch2/ch3 = voice coil) and submits it in native-PCM sized chunks.
     */
    private fun submitUsbFrames(
        index: Int, pcm: ByteArray, inputCh: Int, numSamples: Int,
        controllerCh: Int, leftVcmCh: Int, rightVcmCh: Int,
        includeControllerAudio: Boolean, includeVoiceCoil: Boolean,
        swap: Boolean, voiceCoil: Boolean,
    ): Boolean {
        var accepted = false
        var start = 0
        var lastDiagIndex = -1
        if (voiceCoil && onVoiceCoilPcm == null) {
            Log.w(TAG, "onVoiceCoilPcm callback is NULL — USB PCM will be dropped!")
        }
        if (!voiceCoil && onControllerAudioPcm == null) {
            Log.w(TAG, "onControllerAudioPcm callback is NULL — USB PCM will be dropped!")
        }
        while (start < numSamples) {
            val count = minOf(USB_MAX_FRAMES, numSamples - start)
            val frame = buildUsbFrame(
                pcm, inputCh, start, count,
                controllerCh, leftVcmCh, rightVcmCh,
                includeControllerAudio, includeVoiceCoil, swap,
            )
            val ok = if (voiceCoil) {
                onVoiceCoilPcm?.invoke(index, frame) ?: false
            } else {
                onControllerAudioPcm?.invoke(index, frame) ?: false
            }
            if (ok) accepted = true
            if (index != lastDiagIndex) {
                logUsbPcmDiag(frame, includeVoiceCoil, includeControllerAudio, ok)
                lastDiagIndex = index
            }
            start += count
        }
        return accepted
    }

    private var lastUsbDiagAt = 0L

    private fun logUsbPcmDiag(frame: ByteArray, voiceCoil: Boolean, controllerAudio: Boolean, accepted: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastUsbDiagAt < 1000) return
        lastUsbDiagAt = now
        var peak = 0
        var i = 0
        while (i + 1 < frame.size) {
            val v = kotlin.math.abs(leBytesToShort(frame, i).toInt())
            if (v > peak) peak = v
            i += 2
        }
        Log.i(
            TAG,
            "USB PCM: len=${frame.size} peak=$peak vc=$voiceCoil ca=$controllerAudio accepted=$accepted " +
                "rate=$sampleRate vcAmp=($leftVoiceCoilAmplitude,$rightVoiceCoilAmplitude)"
        )
    }

    private fun buildUsbFrame(
        pcm: ByteArray, inputCh: Int, startSample: Int, sampleCount: Int,
        controllerCh: Int, leftVcmCh: Int, rightVcmCh: Int,
        includeControllerAudio: Boolean, includeVoiceCoil: Boolean, swap: Boolean,
    ): ByteArray {
        val out = ByteArray(sampleCount * 8)
        for (i in 0 until sampleCount) {
            val s = startSample + i
            val inBase = s * inputCh * 2
            val outOff = i * 8

            if (includeControllerAudio) {
                val off = inBase + controllerCh * 2
                val v = if (off + 1 < pcm.size) leBytesToShort(pcm, off).toInt() else 0
                writeShortLe(out, outOff, v)
                writeShortLe(out, outOff + 2, v)
            }

            if (includeVoiceCoil) {
                val offL = inBase + leftVcmCh * 2
                val offR = inBase + rightVcmCh * 2
                val l = if (offL + 1 < pcm.size) leBytesToShort(pcm, offL).toInt() else 0
                val r = if (offR + 1 < pcm.size) leBytesToShort(pcm, offR).toInt() else 0
                writeShortLe(out, outOff + 4, if (swap) r else l)
                writeShortLe(out, outOff + 6, if (swap) l else r)
            }
        }
        return out
    }

    private fun writeShortLe(bytes: ByteArray, offset: Int, value: Int) {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[offset] = v.toByte()
        bytes[offset + 1] = (v shr 8).toByte()
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

    /**
     * Linear resampler for PCM audio. Converts interleaved S16LE from [fromRate] to [toRate].
     * Preserves channel count. Returns a new ByteArray.
     */
    private fun resamplePcm(input: ByteArray, fromRate: Int, toRate: Int, channels: Int): ByteArray {
        if (fromRate == toRate) return input
        if (input.isEmpty()) return input

        val inputFrames = input.size / (channels * 2)
        if (inputFrames <= 0) return input

        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outputFrames = maxOf(1, (inputFrames * ratio).toInt())
        val outputSize = outputFrames * channels * 2
        val output = ByteArray(outputSize)

        for (frame in 0 until outputFrames) {
            val srcFloat = (frame / ratio).toDouble()
            val srcIdx = srcFloat.toInt()
            val frac = srcFloat - srcIdx

            for (ch in 0 until channels) {
                val off = frame * channels * 2 + ch * 2
                val s0 = if (srcIdx + 1 < inputFrames) {
                    leBytesToShort(input, (srcIdx * channels + ch) * 2).toDouble()
                } else {
                    0.0
                }
                val s1 = if (srcIdx + 1 < inputFrames) {
                    leBytesToShort(input, ((srcIdx + 1) * channels + ch) * 2).toDouble()
                } else {
                    s0
                }
                val mixed = ((1.0 - frac) * s0 + frac * s1).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                val v = mixed.toInt().toShort()
                output[off] = v.toByte()
                output[off + 1] = ((v.toInt() ushr 8) and 0xFF).toByte()
            }
        }
        return output
    }
}