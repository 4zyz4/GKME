package com.zyz4.gkme.service

import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.zyz4.gkme.input.SdlAudio
import com.zyz4.gkme.input.SdlNative
import com.zyz4.gkme.model.AudioDevice
import com.zyz4.gkme.model.AudioDeviceType
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
        // Locally synthesised 1 kHz test tone (dualsense-tester WAVEOUT_CTRL).
        private const val TEST_TONE_FREQ = 1000.0
        private const val TEST_TONE_AMPLITUDE = 0.25
        private const val TEST_TONE_RATE = 48000
        private const val TEST_TONE_CHANNELS = 4
        private const val TEST_TONE_FRAME_SAMPLES = 480 // 10 ms
        // SDL3 output: cap the queued audio at ~30 ms for low latency, and wait at
        // most this long for the queue to drain before giving up (the producer
        // thread is dedicated, so a bounded block is safe).
        private const val SDL_MAX_QUEUE_MS = 30
        private const val SDL_WRITE_WAIT_MS = 200
    }

    /** One open SDL sink: the device it plays on and the sample rate it was opened at. */
    private class SdlSinkState(val deviceId: Int, val sampleRate: Int)

    // Open SDL sinks keyed by handle (voice coil / controller audio).
    private val sdlSinks = HashMap<Int, SdlSinkState>()

    // Local 1 kHz test-tone generator state (see setTestTone).
    private var testToneThread: Thread? = null

    @Volatile
    private var testToneRunning = false

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

    private var voiceCoilDevice: AudioDevice = AudioDevice.PHONE_MOTOR
    private var voiceCoilSwap = false
    private var controllerAudioDevice: AudioDevice = AudioDevice.AUTO_SOUND_DEVICE

    // Phone motor vibration state
    private var lastVibrateTime = 0L
    private var lastHasMotorOutput = false
    // Controller voice-coil output state (for cancelling when it goes silent)
    private var lastControllerMotorActive = false
    private var lastControllerMotorIndex = 0

    // Streaming resampler for the controller's 48 kHz USB endpoint. Stateful, so
    // consecutive audio frames stay sample-continuous (see ControllerAudioDsp).
    private val controllerResampler = PcmResampler(4)

    // Per-controller URB chunkers: carry the sub-10 ms remainder between frames so
    // the native sender always receives whole 480-frame URBs.
    private val usbChunkers = HashMap<Int, UsbFrameChunker>()

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
        controllerAudioDevice: AudioDevice,
    ) {
        if (this.voiceCoilDevice != voiceCoilDevice && lastControllerMotorActive) {
            onControllerMotorOutput?.invoke(lastControllerMotorIndex, 0, 0)
            lastControllerMotorActive = false
        }
        // A different SDL sound device (or no longer a sound device) was chosen for
        // either lane: drop the old sink so the next frame opens a fresh one.
        if (sdlSinkTarget(this.voiceCoilDevice) != sdlSinkTarget(voiceCoilDevice)) {
            stopSdlSink(SdlAudio.HANDLE_VOICE_COIL)
        }
        if (sdlSinkTarget(this.controllerAudioDevice) != sdlSinkTarget(controllerAudioDevice)) {
            stopSdlSink(SdlAudio.HANDLE_CONTROLLER_AUDIO)
        }
        this.voiceCoilDevice = voiceCoilDevice
        this.voiceCoilSwap = voiceCoilSwap
        this.controllerAudioDevice = controllerAudioDevice
    }

    /** The stored SDL target of [device], or null when it is not an SDL sound device. */
    private fun sdlSinkTarget(device: AudioDevice): Int? =
        if (device.type == AudioDeviceType.SOUND_DEVICE) device.deviceId else null

    fun stop() {
        setTestTone(false)
        stopAllSdlSinks()
        _vibrator.cancel()
    }

    @Synchronized
    private fun stopSdlSink(handle: Int) {
        if (sdlSinks.remove(handle) != null) {
            try {
                SdlNative.nativeAudioClose(handle)
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    private fun stopAllSdlSinks() {
        if (sdlSinks.isNotEmpty()) {
            try {
                SdlNative.nativeAudioCloseAll()
            } catch (_: Throwable) {}
            sdlSinks.clear()
        }
    }

    /**
     * Opens (or reuses) the SDL sink [handle] for [device] at the current sample
     * rate. Returns false when the device cannot be opened.
     */
    @Synchronized
    private fun ensureSdlSink(handle: Int, device: AudioDevice): Boolean {
        if (device.type != AudioDeviceType.SOUND_DEVICE) return false
        val deviceId = SdlAudio.resolveDeviceId(device.deviceId)
        val state = sdlSinks[handle]
        if (state != null && state.deviceId == deviceId && state.sampleRate == sampleRate) {
            return true
        }
        stopSdlSink(handle)
        if (!SdlAudio.ensureInit()) return false
        val ok = try {
            SdlNative.nativeAudioOpen(handle, deviceId, sampleRate, 2, SDL_MAX_QUEUE_MS)
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            sdlSinks[handle] = SdlSinkState(deviceId, sampleRate)
        }
        return ok
    }

    /** Writes interleaved stereo S16 [data] to sink [handle]. False when it closed. */
    private fun writeSdlSink(handle: Int, data: ByteArray): Boolean {
        if (!sdlSinks.containsKey(handle)) return false
        val written = try {
            SdlNative.nativeAudioWrite(handle, data, SDL_WRITE_WAIT_MS)
        } catch (_: Throwable) {
            -1
        }
        if (written < 0) {
            Log.e(TAG, "SDL audio write failed on handle=$handle, closing sink")
            stopSdlSink(handle)
            return false
        }
        return true
    }

    /**
     * Starts or stops the locally synthesised 1 kHz test tone. The PC only sends
     * the on/off state (dualsense-tester WAVEOUT_CTRL); the tone is generated and
     * routed here through the same path as regular controller audio.
     */
    fun setTestTone(enabled: Boolean) {
        synchronized(this) {
            if (enabled) {
                if (testToneThread?.isAlive == true) return
                testToneRunning = true
                testToneThread = Thread { runTestTone() }.apply {
                    name = "GkmeTestTone"
                    isDaemon = true
                    start()
                }
            } else {
                testToneRunning = false
                testToneThread = null
                // The generator stops emitting frames, so clear the last
                // amplitude reading — otherwise the UI keeps showing the
                // previous volume after the test tone ends.
                resetTrackInfo()
            }
        }
    }

    private fun resetTrackInfo() {
        leftVoiceCoilAmplitude = 0
        rightVoiceCoilAmplitude = 0
        coilSmoothLeft = 0f
        coilSmoothRight = 0f
        _trackInfo.value = AudioTrackInfo()
    }

    private fun runTestTone() {
        val framePeriodNs = TEST_TONE_FRAME_SAMPLES.toLong() * 1_000_000_000L / TEST_TONE_RATE
        // dualsense-tester's WAVEOUT_CTRL drives the controller speaker, so the
        // tone only goes on ch1; the voice-coil channels stay silent.
        val generator = PcmToneGenerator(
            sampleRate = TEST_TONE_RATE,
            frequencyHz = TEST_TONE_FREQ,
            amplitude = Short.MAX_VALUE * TEST_TONE_AMPLITUDE,
            channels = TEST_TONE_CHANNELS,
            activeChannels = intArrayOf(1),
        )
        var nextNs = System.nanoTime()
        while (testToneRunning) {
            val frame = generator.nextFrame(TEST_TONE_FRAME_SAMPLES)
            submitAudio(frame, TEST_TONE_RATE, TEST_TONE_CHANNELS, 16)

            nextNs += framePeriodNs
            val sleepMs = (nextNs - System.nanoTime()) / 1_000_000L
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            } else {
                nextNs = System.nanoTime()
            }
        }
        // Safety net for the race where a frame is submitted after the stop
        // reset: if we were stopped (not restarted) clear the reading again.
        synchronized(this) {
            if (!testToneRunning) resetTrackInfo()
        }
    }

    fun resumeIfStopped() {}

    /** (controllerIndex, leftAmp, rightAmp) — controller motor output for the voice coil. */
    var onControllerMotorOutput: ((controllerIndex: Int, leftAmp: Int, rightAmp: Int) -> Unit)? = null

    /** (leftAmp, rightAmp) — latest voice-coil amplitudes for rumble conflict resolution. */
    var onVoiceCoilAmplitudes: ((leftAmp: Int, rightAmp: Int) -> Unit)? = null

    // ── USB controller PCM output (voice coil / speaker) ──

    /** True when the controller at [controllerIndex] can play PCM through its voice coil. */
    var supportsVoiceCoilPcm: ((controllerIndex: Int) -> Boolean)? = null

    /** True when the controller at [controllerIndex] exposes a speaker/audio endpoint. */
    var supportsControllerAudio: ((controllerIndex: Int) -> Boolean)? = null

    /** Sends a 4-channel, 48 kHz, S16LE frame to the controller voice coil. */
    var onVoiceCoilPcm: ((controllerIndex: Int, frame: ByteArray) -> Boolean)? = null

    /** Sends a 4-channel, 48 kHz, S16LE frame to the controller speaker. */
    var onControllerAudioPcm: ((controllerIndex: Int, frame: ByteArray) -> Boolean)? = null

    @Synchronized
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
            // The PCM layout changed: drop the SDL sinks so they reopen with the new
            // format on the next frame instead of replaying stale queued audio.
            stopAllSdlSinks()
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
            onVoiceCoilAmplitudes?.invoke(0, 0)
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
            val v2 = if (ch2Off + 1 < pcm.size) ControllerAudioDsp.readShortLe(pcm, ch2Off) else 0
            leftVcmEnergy += v2.toDouble() * v2.toDouble()

            val ch3Off = s * bytesPerFrame + rightVcmCh * 2
            val v3 = if (ch3Off + 1 < pcm.size) ControllerAudioDsp.readShortLe(pcm, ch3Off) else 0
            rightVcmEnergy += v3.toDouble() * v3.toDouble()

            val ch1Off = s * bytesPerFrame + controllerCh * 2
            val v1 = if (ch1Off + 1 < pcm.size) ControllerAudioDsp.readShortLe(pcm, ch1Off) else 0
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
            AudioDeviceType.NONE, AudioDeviceType.PHONE_MOTOR, AudioDeviceType.PHONE_SPEAKER,
            AudioDeviceType.SOUND_DEVICE -> {
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
                // Prefer the native PCM (audio haptics) path whenever the controller
                // supports it, regardless of the incoming sample rate. The PCM block
                // below resamples to the endpoint's 48 kHz. Gating this on
                // sampleRate == the USB endpoint rate made the PC's stream fall through
                // to the HID rumble path, whose COMPATIBLE_VIBRATION/HAPTICS_SELECT
                // report switches the DualSense out of audio-haptics mode on every
                // frame (noise, dropouts, speaker bleed).
                if (supportsVoiceCoilPcm?.invoke(index) == true) {
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
        // ch2/ch3 = voice coil. The controller's USB audio endpoint runs at 48 kHz,
        // so resample the incoming stream (if needed) and re-chunk it into whole
        // 10 ms URBs before handing it to the native isochronous sender.
        val vcPcmIndex = if (voiceCoilDevice.type == AudioDeviceType.CONTROLLER &&
            supportsVoiceCoilPcm?.invoke(voiceCoilDevice.controllerIndex) == true
        ) voiceCoilDevice.controllerIndex else -1
        val caPcmIndex = if (controllerAudioDevice.type == AudioDeviceType.CONTROLLER &&
            supportsControllerAudio?.invoke(controllerAudioDevice.controllerIndex) == true
        ) controllerAudioDevice.controllerIndex else -1
        if (vcPcmIndex >= 0 || caPcmIndex >= 0) {
            Log.i(TAG, "USB PCM path selected: vcIndex=$vcPcmIndex caIndex=$caPcmIndex rate=$sampleRate ch=$channels")
        }
        if (vcPcmIndex >= 0 || caPcmIndex >= 0) {
            // Both the voice-coil and controller-audio lanes go to the same
            // DualSense USB audio endpoint, which runs at 48 kHz, so resample once
            // per incoming frame and share the result across targets.
            val targetRate = ControllerAudioDsp.USB_PCM_RATE
            controllerResampler.configure(sampleRate, targetRate, channels)
            val resampledPcm = if (sampleRate != targetRate && pcm.size > 0) {
                controllerResampler.process(pcm)
            } else {
                pcm
            }
            val targets = LinkedHashSet<Int>()
            if (vcPcmIndex >= 0) targets.add(vcPcmIndex)
            if (caPcmIndex >= 0) targets.add(caPcmIndex)
            for (idx in targets) {
                val isVoiceCoil = idx == vcPcmIndex
                val chunker = usbChunkers.getOrPut(idx) { UsbFrameChunker() }
                for (chunk in chunker.submit(resampledPcm)) {
                    val frame = ControllerAudioDsp.buildUsbFrame(
                        chunk, channels, 0, chunk.size / ControllerAudioDsp.BYTES_PER_USB_FRAME,
                        1, 2, 3,
                        includeControllerAudio = idx == caPcmIndex,
                        includeVoiceCoil = idx == vcPcmIndex,
                        swap = voiceCoilSwap,
                    )
                    val ok = if (isVoiceCoil) {
                        onVoiceCoilPcm?.invoke(idx, frame) ?: false
                    } else {
                        onControllerAudioPcm?.invoke(idx, frame) ?: false
                    }
                    logUsbPcmDiag(frame, isVoiceCoil, idx == caPcmIndex, ok)
                }
                // Never fall back to the HID rumble report here: the target
                // controller exposes the audio-haptics endpoint, and a HID motor
                // report would switch it out of audio-haptics mode every time it
                // briefly failed to accept a frame (audio/rumble ping-pong).
            }
            // Notify backend about voice-coil activity for rumble conflict resolution.
            onVoiceCoilAmplitudes?.invoke(leftAmp, rightAmp)
        }

        // ── SDL sound-device outputs (voice coil and controller audio) ──
        // The two lanes are independent: each can target a different enumerated
        // sound device (or none). They share the same 48 kHz resampled stream only
        // on the USB controller path; here they are mixed down to stereo S16.
        val playVoiceCoilSdl = voiceCoilDevice.type == AudioDeviceType.SOUND_DEVICE
        val playControllerAudioSdl = controllerAudioDevice.type == AudioDeviceType.SOUND_DEVICE
        if (!playVoiceCoilSdl && !playControllerAudioSdl) return

        if (playVoiceCoilSdl && ensureSdlSink(SdlAudio.HANDLE_VOICE_COIL, voiceCoilDevice)) {
            val voiceCoilBytes = buildVoiceCoilStereo(
                pcm, numSamples, bytesPerFrame, leftVcmCh, rightVcmCh, voiceCoilSwap,
            )
            writeSdlSink(SdlAudio.HANDLE_VOICE_COIL, voiceCoilBytes)
        }

        if (playControllerAudioSdl && ensureSdlSink(SdlAudio.HANDLE_CONTROLLER_AUDIO, controllerAudioDevice)) {
            val controllerBytes = buildMonoToStereo(pcm, numSamples, bytesPerFrame, controllerCh)
            writeSdlSink(SdlAudio.HANDLE_CONTROLLER_AUDIO, controllerBytes)
        }
    }

    /** Voice-coil left/right channels as interleaved stereo S16, with optional swap. */
    private fun buildVoiceCoilStereo(
        pcm: ByteArray,
        numSamples: Int,
        bytesPerFrame: Int,
        leftCh: Int,
        rightCh: Int,
        swap: Boolean,
    ): ByteArray {
        val out = ByteArray(numSamples * 4)
        for (s in 0 until numSamples) {
            val base = s * bytesPerFrame
            val l = if (base + leftCh * 2 + 1 < pcm.size) ControllerAudioDsp.readShortLe(pcm, base + leftCh * 2) else 0
            val r = if (base + rightCh * 2 + 1 < pcm.size) ControllerAudioDsp.readShortLe(pcm, base + rightCh * 2) else 0
            val left = if (swap) r else l
            val right = if (swap) l else r
            ControllerAudioDsp.writeShortLe(out, s * 4, left)
            ControllerAudioDsp.writeShortLe(out, s * 4 + 2, right)
        }
        return out
    }

    /** One PCM channel duplicated into interleaved stereo S16. */
    private fun buildMonoToStereo(
        pcm: ByteArray,
        numSamples: Int,
        bytesPerFrame: Int,
        channel: Int,
    ): ByteArray {
        val out = ByteArray(numSamples * 4)
        for (s in 0 until numSamples) {
            val off = s * bytesPerFrame + channel * 2
            val v = if (off + 1 < pcm.size) ControllerAudioDsp.readShortLe(pcm, off) else 0
            ControllerAudioDsp.writeShortLe(out, s * 4, v)
            ControllerAudioDsp.writeShortLe(out, s * 4 + 2, v)
        }
        return out
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

    private var lastUsbDiagAt = 0L

    private fun logUsbPcmDiag(frame: ByteArray, voiceCoil: Boolean, controllerAudio: Boolean, accepted: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastUsbDiagAt < 1000) return
        lastUsbDiagAt = now
        var peak = 0
        var i = 0
        while (i + 1 < frame.size) {
            val v = kotlin.math.abs(ControllerAudioDsp.readShortLe(frame, i))
            if (v > peak) peak = v
            i += 2
        }
        Log.i(
            TAG,
            "USB PCM: len=${frame.size} peak=$peak vc=$voiceCoil ca=$controllerAudio accepted=$accepted " +
                "rate=$sampleRate vcAmp=($leftVoiceCoilAmplitude,$rightVoiceCoilAmplitude)"
        )
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