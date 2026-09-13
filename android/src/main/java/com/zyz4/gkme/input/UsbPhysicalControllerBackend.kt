package com.zyz4.gkme.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.input.usb.AbstractController
import com.zyz4.gkme.input.usb.GkmeBridge
import com.zyz4.gkme.input.usb.UsbDriverListener
import com.zyz4.gkme.input.usb.UsbDriverService
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.TouchPoint
import com.zyz4.gkme.model.VibrationDevice
import com.zyz4.gkme.model.VibrationDeviceType
import com.zyz4.gkme.service.ControllerAudioDsp
import com.zyz4.gkme.service.PcmToneGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Physical-controller backend built on the ported "Axixi2233的USB驱动".
 *
 * It binds to [UsbDriverService], decodes raw USB HID reports for DualSense /
 * DualShock 4 / Xbox / Switch Pro controllers, and exposes the same state surface
 * as the SDL3 backend. It additionally supports voice-coil PCM and controller
 * audio output through the controller's USB audio endpoint.
 */
class UsbPhysicalControllerBackend(private val context: Context) : PhysicalControllerBackend, UsbDriverListener {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _controllerState = MutableStateFlow(PhysicalControllerState())
    override val controllerState: StateFlow<PhysicalControllerState> = _controllerState.asStateFlow()

    private val _connectedControllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    override val connectedControllers: StateFlow<List<ControllerInfo>> = _connectedControllers.asStateFlow()

    private val _gyroData = MutableStateFlow(FloatArray(3))
    override val gyroData: StateFlow<FloatArray> = _gyroData.asStateFlow()

    private val _accelData = MutableStateFlow(FloatArray(3))
    override val accelData: StateFlow<FloatArray> = _accelData.asStateFlow()

    @Volatile
    override var controllerGyroEnabled: Boolean = false

    @Volatile
    override var nonLinearTriggerAdaptation: Boolean = false

    @Volatile
    override var controllerHasGyro: Boolean = false

    @Volatile
    override var inputControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            resetInputState()
        }

    @Volatile
    override var gyroControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            _gyroData.value = floatArrayOf(0f, 0f, 0f)
            _accelData.value = floatArrayOf(0f, 0f, 0f)
        }

    @Volatile
    override var gameVibrationDevice: VibrationDevice = VibrationDevice.PHONE
        set(value) {
            val previous = field
            field = value
            if (previous != value) stopVibrationForDevice(previous)
        }

    @Volatile
    override var swapPhoneMotors: Boolean = false

    @Volatile
    override var swapControllerMotors: Boolean = false

    override var onPointerCaptureNeeded: ((Boolean) -> Unit)? = null
    override var isPointerCaptureActive: Boolean = false

    // ── USB service plumbing ───────────────────────────────

    private var binder: UsbDriverService.UsbDriverBinder? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val driverBinder = service as? UsbDriverService.UsbDriverBinder ?: return
            binder = driverBinder
            driverBinder.setListener(this@UsbPhysicalControllerBackend)
            driverBinder.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    // ── Controller bookkeeping ─────────────────────────────

    private val lock = Any()
    private val controllers = LinkedHashMap<Int, AbstractController>()
    private var controllerList: List<AbstractController> = emptyList()

    // Touchpad state of the active controller (normalized 0..1).
    private val touchPoints = LinkedHashMap<Int, TouchPoint>()
    private var touchX = 0f
    private var touchY = 0f

    // Motion state of the active controller.
    private var gyro = FloatArray(3)
    private var accel = FloatArray(3)

    // ── Lifecycle ──────────────────────────────────────────

    override fun start() {
        if (bound) return
        val intent = Intent(context, UsbDriverService::class.java)
        bound = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            false
        }
    }

    override fun stop() {
        binder?.setListener(null)
        binder?.stop()
        binder = null
        if (bound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            bound = false
        }
        synchronized(lock) {
            controllers.clear()
            controllerList = emptyList()
        }
        _connectedControllers.value = emptyList()
        _isConnected.value = false
        controllerHasGyro = false
        resetInputState()
        _gyroData.value = floatArrayOf(0f, 0f, 0f)
        _accelData.value = floatArrayOf(0f, 0f, 0f)
    }

    override fun onControllerGyroSettingChanged(enabled: Boolean) {
        controllerGyroEnabled = enabled
    }

    override fun ensureGyroRegistered() {
        // USB controllers are polled directly; nothing to register.
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean = false

    override fun handleMotionEvent(event: MotionEvent): Boolean = false

    override fun setCapturedTouchpadState(
        normalizedX: Float, normalizedY: Float,
        touches: List<TouchPoint>, touchpadTouch: Boolean, touchpadClick: Boolean,
    ) {
        // USB controllers report their own touchpad; Android-captured events are ignored.
    }

    // ── UsbDriverListener ──────────────────────────────────

    override fun deviceAdded(controller: AbstractController) {
        synchronized(lock) {
            controllers[controller.getControllerId()] = controller
            rebuildControllerListLocked()
        }
        publishControllers()
    }

    override fun deviceRemoved(controller: AbstractController) {
        synchronized(lock) {
            controllers.remove(controller.getControllerId())
            rebuildControllerListLocked()
        }
        publishControllers()
    }

    override fun reportControllerState(
        controllerId: Int, buttonFlags: Int,
        leftStickX: Float, leftStickY: Float,
        rightStickX: Float, rightStickY: Float,
        leftTrigger: Float, rightTrigger: Float,
    ) {
        val active = activeInputController() ?: return
        if (active.getControllerId() != controllerId) return
        updateInputState(buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger)
    }

    override fun reportControllerMotion(controllerId: Int, motionType: Byte, motionX: Float, motionY: Float, motionZ: Float) {
        val active = activeInputController() ?: return
        if (active.getControllerId() != controllerId) return
        when (motionType) {
            GkmeBridge.LI_MOTION_TYPE_GYRO -> gyro = floatArrayOf(motionX, motionY, motionZ)
            GkmeBridge.LI_MOTION_TYPE_ACCEL -> accel = floatArrayOf(motionX, motionY, motionZ)
        }
        if (controllerGyroEnabled) {
            _gyroData.value = gyro.copyOf()
            _accelData.value = accel.copyOf()
        } else {
            _gyroData.value = floatArrayOf(0f, 0f, 0f)
            _accelData.value = floatArrayOf(0f, 0f, 0f)
        }
    }

    override fun reportControllerTouchpadEvent(
        controllerId: Int, eventType: Byte, pointerId: Int,
        x: Float, y: Float, pressure: Float,
    ) {
        val active = activeInputController() ?: return
        if (active.getControllerId() != controllerId) return
        synchronized(lock) {
            when (eventType) {
                GkmeBridge.LI_TOUCH_EVENT_CANCEL_ALL, GkmeBridge.LI_TOUCH_EVENT_CANCEL -> {
                    touchPoints.clear()
                }
                GkmeBridge.LI_TOUCH_EVENT_UP -> {
                    touchPoints.remove(pointerId)
                }
                GkmeBridge.LI_TOUCH_EVENT_DOWN, GkmeBridge.LI_TOUCH_EVENT_MOVE,
                GkmeBridge.LI_TOUCH_EVENT_HOVER, GkmeBridge.LI_TOUCH_EVENT_BUTTON_ONLY -> {
                    val nx = x.coerceIn(0f, 1f)
                    val ny = y.coerceIn(0f, 1f)
                    touchX = nx
                    touchY = ny
                    touchPoints[pointerId] = TouchPoint(
                        id = pointerId,
                        x = (nx * 1919f).toInt().coerceIn(0, 1919),
                        y = (ny * 942f).toInt().coerceIn(0, 942),
                        active = true,
                    )
                }
            }
            val current = _controllerState.value
            _controllerState.value = current.copy(
                touchpadX = touchX,
                touchpadY = touchY,
                touchpadTouch = touchPoints.isNotEmpty(),
                touches = touchPoints.values.toList(),
            )
        }
    }

    // ── State assembly ─────────────────────────────────────

    private fun updateInputState(
        flags: Int, lsx: Float, lsy: Float, rsx: Float, rsy: Float, lt: Float, rt: Float,
    ) {
        val buttons = mapButtons(flags, lt, rt)
        val dpad = (if ((flags and USB_UP) != 0) GamepadState.DPAD_UP else 0) or
                (if ((flags and USB_DOWN) != 0) GamepadState.DPAD_DOWN else 0) or
                (if ((flags and USB_LEFT) != 0) GamepadState.DPAD_LEFT else 0) or
                (if ((flags and USB_RIGHT) != 0) GamepadState.DPAD_RIGHT else 0)

        val left = if (nonLinearTriggerAdaptation) (if (lt > 0.5f) 255 else 0) else (lt * 255f).toInt().coerceIn(0, 255)
        val right = if (nonLinearTriggerAdaptation) (if (rt > 0.5f) 255 else 0) else (rt * 255f).toInt().coerceIn(0, 255)

        _controllerState.value = PhysicalControllerState(
            buttons = buttons,
            leftStickX = (lsx * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            leftStickY = (lsy * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            rightStickX = (rsx * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            rightStickY = (rsy * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            leftTrigger = left,
            rightTrigger = right,
            dpad = dpad,
            touchpadX = touchX,
            touchpadY = touchY,
            touchpadTouch = touchPoints.isNotEmpty(),
            touchpadClick = (buttons and GamepadState.TOUCHPAD_CLICK.toUInt()) != 0u,
            touches = touchPoints.values.toList(),
        )
    }

    private fun mapButtons(flags: Int, lt: Float, rt: Float): UInt {
        var b = 0
        if (flags and USB_A != 0) b = b or GamepadState.A
        if (flags and USB_B != 0) b = b or GamepadState.B
        if (flags and USB_X != 0) b = b or GamepadState.X
        if (flags and USB_Y != 0) b = b or GamepadState.Y
        if (flags and USB_LB != 0) b = b or GamepadState.LB
        if (flags and USB_RB != 0) b = b or GamepadState.RB
        if (lt > TRIGGER_DIGITAL_THRESHOLD) b = b or GamepadState.LT
        if (rt > TRIGGER_DIGITAL_THRESHOLD) b = b or GamepadState.RT
        if (flags and USB_BACK != 0) b = b or GamepadState.SELECT
        if (flags and USB_PLAY != 0) b = b or GamepadState.START
        if (flags and USB_LS != 0) b = b or GamepadState.L3
        if (flags and USB_RS != 0) b = b or GamepadState.R3
        if (flags and USB_UP != 0) b = b or GamepadState.DPAD_BIT_UP
        if (flags and USB_DOWN != 0) b = b or GamepadState.DPAD_BIT_DOWN
        if (flags and USB_LEFT != 0) b = b or GamepadState.DPAD_BIT_LEFT
        if (flags and USB_RIGHT != 0) b = b or GamepadState.DPAD_BIT_RIGHT
        if (flags and USB_SPECIAL != 0) b = b or GamepadState.HOME
        if (flags and USB_TOUCHPAD != 0) b = b or GamepadState.TOUCHPAD_CLICK
        if (flags and USB_MISC != 0) b = b or GamepadState.MIC_MUTE
        return b.toUInt()
    }

    private fun resetInputState() {
        synchronized(lock) {
            touchPoints.clear()
            touchX = 0f
            touchY = 0f
        }
        gyro = FloatArray(3)
        accel = FloatArray(3)
        _controllerState.value = PhysicalControllerState()
        _gyroData.value = floatArrayOf(0f, 0f, 0f)
        _accelData.value = floatArrayOf(0f, 0f, 0f)
    }

    private fun rebuildControllerListLocked() {
        controllerList = controllers.values.toList()
    }

    private fun publishControllers() {
        val list: List<AbstractController>
        synchronized(lock) { list = controllerList }
        val infos = list.map { c ->
            ControllerInfo(
                id = c.getControllerId(),
                name = displayName(c),
                motorCount = if ((c.getCapabilities().toInt() and GkmeBridge.LI_CCAP_RUMBLE.toInt()) != 0) 2 else 0,
            )
        }
        _connectedControllers.value = infos
        _isConnected.value = infos.isNotEmpty()
        if (infos.isEmpty()) {
            controllerHasGyro = false
            resetInputState()
        } else {
            val active = activeInputController()
            controllerHasGyro = active != null &&
                (active.getCapabilities().toInt() and GkmeBridge.LI_CCAP_GYRO.toInt()) != 0
        }
    }

    private fun activeInputController(): AbstractController? {
        if (inputControllerIndex < 0) return null
        synchronized(lock) {
            return controllerList.getOrNull(inputControllerIndex)
        }
    }

    // ── LED / vibration ────────────────────────────────────

    override fun setLedColor(color: Int, playerLed: Int) {
        val list: List<AbstractController>
        synchronized(lock) { list = controllerList }
        if (list.isEmpty()) return
        val r = ((color shr 16) and 0xFF).toByte()
        val g = ((color shr 8) and 0xFF).toByte()
        val b = (color and 0xFF).toByte()
        val playerPattern = if (playerLed != 0) {
            PLAYER_LED_PATTERNS[(Integer.bitCount(playerLed) - 1).coerceIn(0, 4)]
        } else {
            0
        }
        for (c in list) {
            c.setControllerLED(r, g, b)
            c.setPlayerIndicator(playerPattern.toByte())
        }
    }

    @Volatile
    private var _voiceCoilLeftAmp = 0

    @Volatile
    private var _voiceCoilRightAmp = 0

    @Volatile
    private var _lastVoiceCoilActivityNs = 0L

    override fun setVoiceCoilMotorOutput(leftAmp: Int, rightAmp: Int) {
        _voiceCoilLeftAmp = leftAmp
        _voiceCoilRightAmp = rightAmp
        _lastVoiceCoilActivityNs = System.nanoTime()
    }

    override fun setControllerMotorsVibration(controllerIndex: Int, leftIntensity: Int, rightIntensity: Int) {
        val controller = synchronized(lock) { controllerList.getOrNull(controllerIndex) } ?: return
        controller.rumble(
            (leftIntensity.coerceIn(0, 255) * 257).toShort(),
            (rightIntensity.coerceIn(0, 255) * 257).toShort(),
        )
    }

    override fun rumble(lowFreqMotor: Int, highFreqMotor: Int) {
        val low = lowFreqMotor.coerceIn(0, 255)
        val high = highFreqMotor.coerceIn(0, 255)
        when (gameVibrationDevice.type) {
            VibrationDeviceType.PHONE -> vibratePhoneMotors(low, high, swapPhoneMotors)
            VibrationDeviceType.CONTROLLER -> {
                val controller = synchronized(lock) {
                    controllerList.getOrNull(gameVibrationDevice.controllerIndex)
                } ?: return
                // On a DualSense the compatible-vibration HID report and the
                // audio-haptics PCM are mutually exclusive: the HID report
                // (HAPTICS_SELECT) switches the pad out of audio-haptics mode.
                // When voice-coil channels are silent (no audio playing or
                // channels muted) base rumble is allowed to play through the
                // HID report; otherwise the voice-coil path carries the
                // vibration and HID rumble is skipped to avoid conflict.
                // If no PCM activity has been seen for >200ms, the PC has
                // likely stopped sending audio, so fall back to base rumble.
                if (controller.hasAdvancedAudioHapticsSupport() && controller.isAdvancedAudioHapticsActive()) {
                    val timeSinceLastActivity = System.nanoTime() - _lastVoiceCoilActivityNs
                    val isStale = _lastVoiceCoilActivityNs == 0L || timeSinceLastActivity > VOICE_COIL_SILENCE_TIMEOUT_NS
                    val vcLeft = _voiceCoilLeftAmp
                    val vcRight = _voiceCoilRightAmp
                    if (!isStale && (vcLeft > 1 || vcRight > 1)) return
                }
                val motor0 = if (swapControllerMotors) high else low
                val motor1 = if (swapControllerMotors) low else high
                controller.rumble((motor0 * 257).toShort(), (motor1 * 257).toShort())
            }
            VibrationDeviceType.NONE -> {}
        }
    }

    private fun stopVibrationForDevice(device: VibrationDevice) {
        when (device.type) {
            VibrationDeviceType.PHONE -> vibratePhone(0)
            VibrationDeviceType.CONTROLLER -> {
                val controller = synchronized(lock) { controllerList.getOrNull(device.controllerIndex) }
                // A zero HID rumble would knock an audio-haptics pad out of the
                // voice-coil path, so leave it alone.
                if (controller != null && !controller.hasAdvancedAudioHapticsSupport()) {
                    controller.rumble(0, 0)
                }
            }
            VibrationDeviceType.NONE -> {}
        }
    }

    // ── Controller audio / voice coil ──────────────────────

    override fun controllerSupportsVoiceCoilPcm(controllerIndex: Int): Boolean {
        val controller = synchronized(lock) { controllerList.getOrNull(controllerIndex) } ?: return false
        return controller.hasAdvancedAudioHapticsSupport()
    }

    override fun controllerSupportsAudio(controllerIndex: Int): Boolean =
        controllerSupportsVoiceCoilPcm(controllerIndex)

    override fun submitVoiceCoilFrame(controllerIndex: Int, frame: ByteArray): Boolean {
        _lastVoiceCoilActivityNs = System.nanoTime()
        val controller = synchronized(lock) { controllerList.getOrNull(controllerIndex) } ?: return false
        return controller.submitNativeAudioHapticsFrame(frame)
    }

    override fun submitControllerAudioFrame(controllerIndex: Int, frame: ByteArray): Boolean =
        submitVoiceCoilFrame(controllerIndex, frame)

    override fun playVoiceCoilTest(controllerIndex: Int) {
        val controller = synchronized(lock) { controllerList.getOrNull(controllerIndex) }
        android.util.Log.i(
            "Axixi2233Usb",
            "playVoiceCoilTest index=$controllerIndex controller=$controller " +
                "hasHaptics=${controller?.hasAdvancedAudioHapticsSupport()} listSize=${controllerList.size}"
        )
        if (controller == null || !controller.hasAdvancedAudioHapticsSupport()) return
        Thread({
            val sampleRate = ControllerAudioDsp.USB_PCM_RATE
            val framesPerChunk = 480
            val totalFrames = sampleRate * 2
            // Drive the voice-coil channels (ch2/ch3) only, so the test exercises
            // the motors rather than the controller speaker.
            val generator = PcmToneGenerator(
                sampleRate = sampleRate,
                frequencyHz = 220.0,
                amplitude = 20000.0,
                channels = 4,
                activeChannels = intArrayOf(2, 3),
            )

            // The native sender submits one isochronous URB at a time and blocks
            // until it completes, so it starves whenever the producer's queue is
            // empty at a URB boundary. Pre-fill a few chunks, then pace against the
            // audio clock (nanoTime) instead of Thread.sleep's ~10 ms granularity,
            // which would otherwise feed slower than real time and drop out.
            val leadFrames = framesPerChunk * 5
            val startNs = System.nanoTime()
            var written = 0
            while (written < totalFrames) {
                val count = minOf(framesPerChunk, totalFrames - written)
                controller.submitNativeAudioHapticsFrame(generator.nextFrame(count))
                written += count

                val ahead = written - leadFrames
                if (ahead > 0) {
                    val targetNs = startNs + ahead.toLong() * 1_000_000_000L / sampleRate
                    val waitNs = targetNs - System.nanoTime()
                    if (waitNs > 0) {
                        try {
                            Thread.sleep(waitNs / 1_000_000L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
        }, "VoiceCoilTest").start()
    }

    // ── Adaptive triggers / trigger rumble (reserved) ──────

    override fun setAdaptiveTriggerEffects(
        controllerIndex: Int, eventFlags: Byte, typeLeft: Byte, typeRight: Byte,
        left: ByteArray?, right: ByteArray?,
    ) {
        val controller = synchronized(lock) { controllerList.getOrNull(controllerIndex) } ?: return
        controller.setAdaptiveTriggerEffects(eventFlags, typeLeft, typeRight, left, right)
    }

    override fun setTriggerRumble(controllerIndex: Int, leftTrigger: Int, rightTrigger: Int) {
        val controller = synchronized(lock) { controllerList.getOrNull(controllerIndex) } ?: return
        controller.rumbleTriggers(
            (leftTrigger.coerceIn(0, 255) * 257).toShort(),
            (rightTrigger.coerceIn(0, 255) * 257).toShort(),
        )
    }

    // ── Phone vibration (mirrors the SDL backend) ──────────

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var lastPhoneAmp = -1

    private fun vibratePhoneMotors(low: Int, high: Int, swap: Boolean) {
        val motor0 = if (swap) high else low
        val motor1 = if (swap) low else high
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val ids = vm?.vibratorIds
            if (vm != null && ids != null && ids.size >= 2) {
                var hasActive = false
                for (i in ids.indices) {
                    if ((if (i == 0) motor0 else motor1) > 1) { hasActive = true; break }
                }
                try {
                    vm.cancel()
                    if (hasActive) {
                        val combo = android.os.CombinedVibration.startParallel()
                        if (motor0 > 1) combo.addVibrator(ids[0], VibrationEffect.createOneShot(60000, motor0.coerceIn(0, 255)))
                        if (motor1 > 1) combo.addVibrator(ids[1], VibrationEffect.createOneShot(60000, motor1.coerceIn(0, 255)))
                        vm.vibrate(combo.combine())
                    }
                } catch (_: Exception) {
                }
                return
            }
        }
        vibratePhone(maxOf(motor0, motor1))
    }

    private fun vibratePhone(amp: Int) {
        val clamped = amp.coerceIn(0, 255)
        if (clamped < 1) {
            try { vibrator.cancel() } catch (_: Exception) {}
            lastPhoneAmp = -1
            return
        }
        if (clamped == lastPhoneAmp) return
        lastPhoneAmp = clamped
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(50), intArrayOf(clamped), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60000)
            }
        } catch (_: Exception) {
        }
    }

    private fun displayName(controller: AbstractController): String {
        return when (controller.javaClass.simpleName) {
            "DualSenseController" -> "DualSense"
            "Dualshock4Controller" -> "DualShock 4"
            "XboxOneController" -> "Xbox One"
            "Xbox360Controller" -> "Xbox 360"
            "ProConController" -> "Switch Pro"
            "ProCon2Controller" -> "Switch Pro 2"
            else -> when (controller.getType()) {
                GkmeBridge.LI_CTYPE_XBOX -> "Xbox 手柄"
                GkmeBridge.LI_CTYPE_PS -> "DS 手柄"
                GkmeBridge.LI_CTYPE_NINTENDO -> "Switch 手柄"
                else -> controller.javaClass.simpleName
            }
        }
    }

    companion object {
        private const val TRIGGER_DIGITAL_THRESHOLD = 0.5f
        private const val VOICE_COIL_SILENCE_TIMEOUT_NS = 200_000_000L // 200ms

        // Sunshine / Limelight button flags produced by the USB driver.
        private const val USB_UP = 0x0001
        private const val USB_DOWN = 0x0002
        private const val USB_LEFT = 0x0004
        private const val USB_RIGHT = 0x0008
        private const val USB_PLAY = 0x0010
        private const val USB_BACK = 0x0020
        private const val USB_LS = 0x0040
        private const val USB_RS = 0x0080
        private const val USB_LB = 0x0100
        private const val USB_RB = 0x0200
        private const val USB_SPECIAL = 0x0400
        private const val USB_A = 0x1000
        private const val USB_B = 0x2000
        private const val USB_X = 0x4000
        private const val USB_Y = 0x8000
        private const val USB_TOUCHPAD = 0x100000
        private const val USB_MISC = 0x200000

        private val PLAYER_LED_PATTERNS = intArrayOf(0x04, 0x0A, 0x15, 0x1B, 0x1F)
    }
}
