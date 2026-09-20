package com.zyz4.gkme.input

import android.app.Activity
import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.TouchPoint
import com.zyz4.gkme.model.VibrationDevice
import com.zyz4.gkme.model.VibrationDeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

data class PhysicalControllerState(
    val buttons: UInt = 0u,
    val leftStickX: Short = 0,
    val leftStickY: Short = 0,
    val rightStickX: Short = 0,
    val rightStickY: Short = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val dpad: Int = 0,
    val touchpadX: Float = 0f,
    val touchpadY: Float = 0f,
    val touchpadTouch: Boolean = false,
    val touchpadClick: Boolean = false,
    val touches: List<TouchPoint> = emptyList(),
)

enum class ControllerType { UNKNOWN, XBOX, PS, NINTENDO }

/**
 * Handles physical gamepads through SDL3.
 *
 * SDL owns device enumeration, input decoding, rumble and motion sensors. The app
 * only forwards raw Android key/motion events into SDL (see MainActivity) and polls
 * the resulting gamepad state from the native bridge on a background thread.
 */
class SdlPhysicalControllerBackend(private val context: Context) : PhysicalControllerBackend {

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
    override var controllerHasGyro: Boolean = false

    /** True when the input controller exposes analog triggers; otherwise its triggers are
     *  treated as digital (thresholded to 0/255). Updated from the detected capabilities. */
    @Volatile
    private var inputHasAnalogTrigger: Boolean = true

    /** Index into [connectedControllers] used as the input source; -1 disables controller input. */
    @Volatile
    override var inputControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            refreshInputTriggerCapability()
            resetInputState()
        }

    /** Index into [connectedControllers] whose gyro/accel sensors are read. */
    @Volatile
    override var gyroControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            applySensorSetting()
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }
    private val running = AtomicBoolean(false)
    private var pollThread: Thread? = null
    private var lastPsPresent = false

    private var sensorAppliedIndex = -1
    private var sensorAppliedEnabled = false

    // Touchpad state captured from Android MotionEvents (Bluetooth DS4/DS5), merged
    // with the touchpad state SDL reports for HIDAPI controllers.
    private var localTouchX = 0f
    private var localTouchY = 0f
    private var localTouchActive = false
    private var localClick = false
    private var localTouches: List<TouchPoint> = emptyList()

    private var lastPhoneAmp = -1

    // ── Lifecycle ──────────────────────────────────────────

    override fun start() {
        if (running.get()) return
        val activity = context as? Activity
        if (activity != null) {
            SdlPlatform.setup(activity)
        }
        // Publish the USB device list before SDL enumerates, so USB gamepads are
        // recognised as HIDAPI-preferred from the very first detection.
        refreshUsbDevices()
        if (!SdlNative.nativeInit()) {
            return
        }
        // Track InputDevice add/remove so the cached vibrator bindings are invalidated
        // exactly when Android reports a change (Moonlight does the same). Registered
        // only after nativeInit succeeds so an early return cannot leak the listener.
        try { inputManager?.registerInputDeviceListener(inputDeviceListener, null) } catch (_: Exception) {}
        running.set(true)
        pollThread = Thread({ pollLoop() }, "GkmeSdlPoll").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        pollThread?.join(500)
        pollThread = null
        try { inputManager?.unregisterInputDeviceListener(inputDeviceListener) } catch (_: Exception) {}
        // Command every motor off while the SDL/HIDAPI connection is still open,
        // then cancel the Android-side one-shots. SDL only auto-stops a rumble it
        // tracks with an expiration, so a motor driven through the Android
        // vibrator path (or one whose slot was closed mid-rumble) would otherwise
        // latch on until the pad is reset. Moonlight's drivers do the same by
        // calling rumble(0, 0) in stop() before releasing the USB interface.
        stopAllVibration()
        SdlPlatform.shutdown()
        SdlNative.nativeShutdown()
        cancelAllControllerVibration()
        vibratorCache.clear()
        _connectedControllers.value = emptyList()
        _isConnected.value = false
        controllerHasGyro = false
        sensorAppliedIndex = -1
        sensorAppliedEnabled = false
        resetInputState()
        _gyroData.value = floatArrayOf(0f, 0f, 0f)
        _accelData.value = floatArrayOf(0f, 0f, 0f)
    }

    private fun pollLoop() {
        val state = IntArray(16)
        val sensor = FloatArray(6)
        var frame = 0
        while (running.get()) {
            try {
                if (frame % 50 == 0) {
                    refreshUsbDevices()
                }
                if (frame % 125 == 0) {
                    refreshConnectedControllers()
                }
                applySensorSetting()

                val count = SdlNative.nativeGetControllerCount()
                val inputIndex = inputControllerIndex
                if (inputIndex in 0 until count && SdlNative.nativePollState(inputIndex, state)) {
                    updateStateFromNative(state)
                } else {
                    clearInputState()
                }

                val gyroIndex = gyroControllerIndex
                val hasGyro = gyroIndex in 0 until count &&
                    SdlNative.nativeGetControllerHasGyro(gyroIndex)
                controllerHasGyro = hasGyro
                if (hasGyro && controllerGyroEnabled && SdlNative.nativePollSensor(gyroIndex, sensor)) {
                    _gyroData.value = floatArrayOf(sensor[0], sensor[1], sensor[2])
                    _accelData.value = floatArrayOf(sensor[3], sensor[4], sensor[5])
                } else {
                    _gyroData.value = floatArrayOf(0f, 0f, 0f)
                    _accelData.value = floatArrayOf(0f, 0f, 0f)
                }
            } catch (_: Throwable) {
            }
            frame++
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** Publishes the attached USB device IDs so the bridge can prefer HIDAPI for USB pads. */
    private fun refreshUsbDevices() {
        val manager = usbManager ?: return
        val keys = try {
            manager.deviceList.values.map { (it.vendorId shl 16) or it.productId }.toIntArray()
        } catch (_: Exception) {
            IntArray(0)
        }
        SdlNative.nativeSetUsbDeviceIds(keys)
    }

    private fun refreshConnectedControllers() {
        val count = SdlNative.nativeGetControllerCount()
        val androidMotorCounts = refreshControllerVibrators(count)
        val infos = (0 until count).map { i ->
            val type = SdlNative.nativeGetControllerType(i)
            // Prefer the actuator count Android exposes for this pad; SDL does not report a
            // motor count (its native value is a placeholder), so only fall back to it when
            // no Android InputDevice was matched.
            val androidCount = androidMotorCounts.getOrElse(i) { 0 }
            ControllerInfo(
                id = SdlNative.nativeGetControllerInstanceId(i),
                name = SdlNative.nativeGetControllerName(i).ifBlank { "手柄${i + 1}" },
                motorCount = if (androidCount > 0) androidCount else SdlNative.nativeGetControllerMotorCount(i),
                hasTriggerRumble = SdlNative.nativeGetControllerHasTriggerRumble(i),
                hasAdaptiveTrigger = false,
                hasGyro = SdlNative.nativeGetControllerHasGyro(i),
                hasAnalogTrigger = SdlNative.nativeGetControllerHasAnalogTriggers(i),
                // Bluetooth DualShock/DualSense touchpad input is forwarded through Android
                // SOURCE_TOUCHPAD events, which SDL's Android driver does not expose. Fall back
                // to the controller type so the touchpad is still reported as supported.
                hasTouchpad = SdlNative.nativeGetControllerHasTouchpad(i) || isPlayStationTouchpad(type),
                supportedButtons = SdlNative.nativeGetControllerButtonMask(i),
            )
        }
        // StateFlow conflates equal lists, so this only emits on real changes.
        _connectedControllers.value = infos
        _isConnected.value = count > 0
        inputHasAnalogTrigger = infos.getOrNull(inputControllerIndex)?.hasAnalogTrigger ?: true
        if (count == 0) {
            resetInputState()
            _gyroData.value = floatArrayOf(0f, 0f, 0f)
            _accelData.value = floatArrayOf(0f, 0f, 0f)
            controllerHasGyro = false
            if (lastPsPresent) {
                lastPsPresent = false
                mainHandler.post { onPointerCaptureNeeded?.invoke(false) }
            }
        }

        // A PlayStation controller exposes a touchpad; enable pointer capture for it.
        var psPresent = false
        for (i in 0 until count) {
            val type = SdlNative.nativeGetControllerType(i)
            if (type == 4 || type == 5 || type == 6) { // PS3 / PS4 / PS5
                psPresent = true
                break
            }
        }
        if (psPresent != lastPsPresent) {
            lastPsPresent = psPresent
            mainHandler.post { onPointerCaptureNeeded?.invoke(psPresent) }
        }
    }

    /** Recomputes whether the input controller's triggers are analog from the current list. */
    private fun refreshInputTriggerCapability() {
        inputHasAnalogTrigger =
            _connectedControllers.value.getOrNull(inputControllerIndex)?.hasAnalogTrigger ?: true
    }

    /** True for PlayStation controllers that always carry a touchpad (PS4 / PS5). */
    private fun isPlayStationTouchpad(type: Int): Boolean =
        type == SDL_GAMEPAD_TYPE_PS4 || type == SDL_GAMEPAD_TYPE_PS5

    /**
     * The vibrators bound to one Android InputDevice. Mirrors Moonlight's
     * `InputDeviceContext`: the binding is resolved **once per device** (and kept in
     * [vibratorCache]), so the same [VibratorManager] instance is used for both
     * `vibrate()` and `cancel()`. Re-resolving `device.vibratorManager` on every poll
     * produced a fresh wrapper each time and let a `cancel()` on a later wrapper miss
     * the effect started by an earlier one.
     */
    private class AndroidVibrators(
        val manager: VibratorManager?,
        val quad: Boolean,
        val legacy: Vibrator?,
        val motorCount: Int,
    )

    /** Bindings aligned with the SDL controller list; null = no Android vibrator. */
    @Volatile
    private var controllerBindings: List<AndroidVibrators?> = emptyList()

    /**
     * Last motor/trigger values sent for a given controller index. Kept so the quad
     * path can drive all four actuators together: a trigger update must not discard
     * the motor rumble (and vice versa). Mutated under [outputsLock]; the fields are
     * volatile so the unlocked reads in [applyControllerOutput] stay coherent.
     */
    private class ControllerOutputs(
        @Volatile var low: Int = 0,
        @Volatile var high: Int = 0,
        @Volatile var leftTrigger: Int = 0,
        @Volatile var rightTrigger: Int = 0,
    )

    private val outputsLock = Any()
    private val controllerOutputs = HashMap<Int, ControllerOutputs>()

    /** Cache of [AndroidVibrators] keyed by InputDevice id, invalidated on device events. */
    private val vibratorCache = Collections.synchronizedMap(HashMap<Int, AndroidVibrators?>())

    private val inputManager by lazy {
        context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            vibratorCache.remove(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            vibratorCache.remove(deviceId)?.let { cancelBinding(it) }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            vibratorCache.remove(deviceId)
        }
    }

    /**
     * Resolves a device's rumble capability exactly like Moonlight: a [VibratorManager]
     * is only used when it exposes exactly 4 (quad) or 2 (dual) vibrators that all
     * support amplitude control; otherwise the legacy single [Vibrator] is used.
     */
    private fun resolveAndroidVibrators(device: InputDevice): AndroidVibrators? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = device.vibratorManager
            val ids = vm.vibratorIds
            if (ids.isNotEmpty()) {
                val allAmplitude = ids.all { vm.getVibrator(it).hasAmplitudeControl() }
                if (allAmplitude && ids.size == 4) {
                    return AndroidVibrators(vm, quad = true, legacy = null, motorCount = 4)
                }
                if (allAmplitude && ids.size == 2) {
                    return AndroidVibrators(vm, quad = false, legacy = null, motorCount = 2)
                }
            }
        }
        @Suppress("DEPRECATION")
        val vib = device.vibrator
        @Suppress("DEPRECATION")
        if (vib != null && vib.hasVibrator()) {
            return AndroidVibrators(manager = null, quad = false, legacy = vib, motorCount = 1)
        }
        return null
    }

    /**
     * Binds the Android vibrators of the connected gamepads (matched by vendor/product id)
     * and returns the actuator count Android exposes for each SDL controller. SDL does not
     * report a motor count, and its Android rumble path may collapse a dual-motor pad into a
     * single actuator, so this drives the motor decision.
     */
    private fun refreshControllerVibrators(count: Int): List<Int> {
        val pool = ArrayList<InputDevice>()
        try {
            for (id in InputDevice.getDeviceIds()) {
                val device = InputDevice.getDevice(id) ?: continue
                val sources = device.sources
                if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                ) {
                    pool.add(device)
                }
            }
        } catch (_: Exception) {
        }

        val newBindings = ArrayList<AndroidVibrators?>(count)
        val counts = IntArray(count)
        val used = BooleanArray(pool.size)
        for (i in 0 until count) {
            val vendor = SdlNative.nativeGetControllerVendor(i)
            val product = SdlNative.nativeGetControllerProduct(i)
            var chosen = -1
            for (j in pool.indices) {
                if (used[j]) continue
                val device = pool[j]
                if (device.vendorId == vendor && device.productId == product) {
                    chosen = j
                    break
                }
            }
            if (chosen < 0) {
                newBindings.add(null)
                continue
            }
            used[chosen] = true
            val device = pool[chosen]
            val binding = if (vibratorCache.containsKey(device.id)) {
                vibratorCache[device.id]
            } else {
                resolveAndroidVibrators(device).also { vibratorCache[device.id] = it }
            }
            newBindings.add(binding)
            counts[i] = binding?.motorCount ?: 0
        }
        applyControllerBindings(newBindings)
        return counts.toList()
    }

    /**
     * Swaps in the freshly matched bindings. A binding that is no longer used (its gamepad
     * was removed or remapped) is cancelled so a pending 60s one-shot cannot keep running.
     * Bindings that are unchanged keep the exact same object, so their motors stay under
     * one owner.
     */
    private fun applyControllerBindings(newBindings: List<AndroidVibrators?>) {
        for (i in controllerBindings.indices) {
            val old = controllerBindings[i] ?: continue
            if (newBindings.getOrNull(i) !== old) cancelBinding(old)
        }
        controllerBindings = newBindings
    }

    private fun cancelBinding(binding: AndroidVibrators) {
        try { binding.manager?.cancel() } catch (_: Exception) {}
        try { binding.legacy?.cancel() } catch (_: Exception) {}
    }

    /**
     * Drives the gamepad's motors with the best available path.
     *
     * The Android vibrator is preferred whenever the InputDevice is visible (i.e. the
     * USB interface is not held exclusively by SDL/HIDAPI). SDL's HIDAPI rumble is
     * queued on a background thread whose writes are fire-and-forget, so a single
     * dropped stop report can latch the motor on forever while the host keeps
     * streaming zeros. The Android `cancel()` path is synchronous and reliable, and is
     * exactly how Moonlight drives controller rumble.
     *
     * SDL's low/high rumble is only used as a fallback when no Android vibrator exists
     * (e.g. HIDAPI has claimed the pad and Android's input driver was detached).
     */
    private fun driveControllerMotors(index: Int, low: Int, high: Int) {
        val outputs = outputsFor(index)
        synchronized(outputsLock) {
            outputs.low = low
            outputs.high = high
        }
        applyControllerOutput(index, outputs)
    }

    private fun outputsFor(index: Int): ControllerOutputs =
        synchronized(outputsLock) { controllerOutputs.getOrPut(index) { ControllerOutputs() } }

    /**
     * Drives all outputs of one pad from its last known motor + trigger values, so the
     * two channels never overwrite each other (a quad pad owns four actuators).
     */
    private fun applyControllerOutput(index: Int, outputs: ControllerOutputs) {
        val binding = controllerBindings.getOrNull(index)
        val low = outputs.low
        val high = outputs.high
        val lt = outputs.leftTrigger
        val rt = outputs.rightTrigger
        val motorActive = low != 0 || high != 0
        val triggerActive = lt != 0 || rt != 0

        if (!motorActive && !triggerActive) {
            // Everything off: silence both SDL paths and cancel the Android one-shot.
            try { SdlNative.nativeRumble(index, 0, 0, 0) } catch (_: Exception) {}
            try { SdlNative.nativeRumbleTriggers(index, 0, 0, 0) } catch (_: Exception) {}
            binding?.let { cancelBinding(it) }
            return
        }

        if (binding?.manager != null) {
            // The Android path owns this pad: clear any SDL rumble a path switch left
            // running, then drive the actuators directly.
            try { SdlNative.nativeRumble(index, 0, 0, 0) } catch (_: Exception) {}
            try { SdlNative.nativeRumbleTriggers(index, 0, 0, 0) } catch (_: Exception) {}
            if (binding.quad) {
                rumbleQuadVibrators(binding.manager, low, high, lt, rt)
            } else {
                rumbleDualVibrators(binding.manager, low, high)
            }
            return
        }
        if (binding?.legacy != null) {
            try { SdlNative.nativeRumble(index, 0, 0, 0) } catch (_: Exception) {}
            rumbleSingleVibrator(binding.legacy, low, high)
            return
        }

        // No Android vibrator available: fall back to SDL's low/high and trigger rumble.
        SdlNative.nativeRumble(index, low * 257, high * 257, RUMBLE_DURATION_MS)
        SdlNative.nativeRumbleTriggers(index, lt * 257, rt * 257, RUMBLE_DURATION_MS)
    }

    private fun cancelControllerVibration(index: Int) {
        controllerBindings.getOrNull(index)?.let { cancelBinding(it) }
        synchronized(outputsLock) {
            controllerOutputs[index]?.let {
                it.low = 0
                it.high = 0
                it.leftTrigger = 0
                it.rightTrigger = 0
            }
        }
    }

    private fun cancelAllControllerVibration() {
        for (binding in controllerBindings) {
            binding?.let { cancelBinding(it) }
        }
    }

    /** Sends an explicit zero rumble (and trigger rumble) to every pad while its
     *  connection is still open, so no motor is left latched on. */
    private fun stopAllControllerRumble() {
        val count = try { SdlNative.nativeGetControllerCount() } catch (_: Exception) { 0 }
        for (i in 0 until count) {
            try { SdlNative.nativeRumble(i, 0, 0, 0) } catch (_: Exception) {}
            try { SdlNative.nativeRumbleTriggers(i, 0, 0, 0) } catch (_: Exception) {}
        }
    }

    override fun stopAllVibration() {
        stopAllControllerRumble()
        cancelAllControllerVibration()
        vibratePhone(0)
        _lastRumbleLow = 0
        _lastRumbleHigh = 0
        synchronized(outputsLock) {
            for (outputs in controllerOutputs.values) {
                outputs.low = 0
                outputs.high = 0
                outputs.leftTrigger = 0
                outputs.rightTrigger = 0
            }
        }
    }

    // ── Moonlight-style vibrator driving (values are 0..255) ──

    private fun rumbleDualVibrators(vm: VibratorManager, lowFreqMotor: Int, highFreqMotor: Int) {
        val low = lowFreqMotor.coerceIn(0, 255)
        val high = highFreqMotor.coerceIn(0, 255)
        if (low == 0 && high == 0) {
            try { vm.cancel() } catch (_: Exception) {}
            return
        }
        val ids = vm.vibratorIds
        // Enumerated order is low then high on most devices.
        val amps = intArrayOf(low, high)
        val combo = CombinedVibration.startParallel()
        for (i in ids.indices) {
            if (i < amps.size && amps[i] != 0) {
                combo.addVibrator(ids[i], VibrationEffect.createOneShot(60000, amps[i]))
            }
        }
        vibrateCombined(vm, combo)
    }

    private fun rumbleQuadVibrators(
        vm: VibratorManager, lowFreqMotor: Int, highFreqMotor: Int,
        leftTrigger: Int, rightTrigger: Int,
    ) {
        val low = lowFreqMotor.coerceIn(0, 255)
        val high = highFreqMotor.coerceIn(0, 255)
        val lt = leftTrigger.coerceIn(0, 255)
        val rt = rightTrigger.coerceIn(0, 255)
        if (low == 0 && high == 0 && lt == 0 && rt == 0) {
            try { vm.cancel() } catch (_: Exception) {}
            return
        }
        val ids = vm.vibratorIds
        val amps = intArrayOf(low, high, lt, rt)
        val combo = CombinedVibration.startParallel()
        for (i in ids.indices) {
            if (i < amps.size && amps[i] != 0) {
                combo.addVibrator(ids[i], VibrationEffect.createOneShot(60000, amps[i]))
            }
        }
        vibrateCombined(vm, combo)
    }

    private fun vibrateCombined(vm: VibratorManager, combo: CombinedVibration.ParallelCombination) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_MEDIA)
                    .build()
                vm.vibrate(combo.combine(), attrs)
            } else {
                vm.vibrate(combo.combine())
            }
        } catch (_: Exception) {}
    }

    private fun rumbleSingleVibrator(vibrator: Vibrator, lowFreqMotor: Int, highFreqMotor: Int) {
        val low = lowFreqMotor.coerceIn(0, 255)
        val high = highFreqMotor.coerceIn(0, 255)
        // 80% of the big motor + 33% of the small motor, capped at 255 (Moonlight).
        val simulatedAmplitude = minOf(255, (low * 0.80 + high * 0.33).toInt())
        if (simulatedAmplitude == 0) {
            try { vibrator.cancel() } catch (_: Exception) {}
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createOneShot(60000, simulatedAmplitude))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // No amplitude control: emulate with a PWM waveform.
                val pwmPeriod = 20L
                val onTime = (simulatedAmplitude / 255.0 * pwmPeriod).toLong()
                val offTime = (pwmPeriod - onTime).coerceAtLeast(1L)
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, onTime, offTime), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60000)
            }
        } catch (_: Exception) {}
    }

    private fun resetInputState() {
        _controllerState.value = PhysicalControllerState()
    }

    private fun applySensorSetting() {
        val index = gyroControllerIndex
        val enabled = controllerGyroEnabled
        if (index == sensorAppliedIndex && enabled == sensorAppliedEnabled) return
        if (sensorAppliedIndex >= 0) {
            SdlNative.nativeSetSensorEnabled(sensorAppliedIndex, false)
        }
        if (index >= 0 && enabled) {
            SdlNative.nativeSetSensorEnabled(index, true)
        }
        sensorAppliedIndex = index
        sensorAppliedEnabled = enabled
    }

    override fun onControllerGyroSettingChanged(enabled: Boolean) {
        controllerGyroEnabled = enabled
        applySensorSetting()
    }

    override fun ensureGyroRegistered() {
        applySensorSetting()
    }

    // ── Input forwarding (called from MainActivity dispatch) ──

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!SdlPlatform.isJoystickDevice(event.deviceId)) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    SdlPlatform.onPadDown(event.deviceId, event.keyCode, event.scanCode)
                } else {
                    true
                }
            }
            KeyEvent.ACTION_UP ->
                SdlPlatform.onPadUp(event.deviceId, event.keyCode, event.scanCode)
            else -> false
        }
    }

    override fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) {
            return handleTouchpadMotion(event)
        }
        if (SdlPlatform.isJoystickDevice(event.deviceId)) {
            return SdlPlatform.handleJoystickMotionEvent(event)
        }
        return false
    }

    // ── State assembly ─────────────────────────────────────

    private fun updateStateFromNative(v: IntArray) {
        val sdlButtons = v[0].toUInt()
        val touchCount = v[8]
        val useSdlTouch = touchCount > 0

        val touches: List<TouchPoint>
        val tx: Float
        val ty: Float
        val touchActive: Boolean
        if (useSdlTouch) {
            val candidates = ArrayList<TouchPoint>(touchCount)
            for (i in 0 until touchCount) {
                candidates.add(
                    TouchPoint(
                        id = i,
                        x = v[9 + i * 2].coerceIn(0, 1919),
                        y = v[10 + i * 2].coerceIn(0, 942),
                        active = true,
                    )
                )
            }
            val (s0, s1) = assignSlots(activeSlot(0), activeSlot(1), candidates)
            touches = canonicalSlots(s0, s1)
            val primary = s0 ?: s1
            tx = if (primary != null) primary.x / 1919f else 0f
            ty = if (primary != null) primary.y / 942f else 0f
            touchActive = primary != null
        } else {
            touches = localTouches
            tx = localTouchX
            ty = localTouchY
            touchActive = localTouchActive
        }

        val click = (v[14] != 0) || localClick
        val buttons = if (click) {
            sdlButtons or GamepadState.TOUCHPAD_CLICK.toUInt()
        } else {
            sdlButtons and GamepadState.TOUCHPAD_CLICK.toUInt().inv()
        }

        val lt = if (inputHasAnalogTrigger) {
            v[5]
        } else {
            if (v[5] > 128) 255 else 0
        }
        val rt = if (inputHasAnalogTrigger) {
            v[6]
        } else {
            if (v[6] > 128) 255 else 0
        }

        _controllerState.value = PhysicalControllerState(
            buttons = buttons,
            leftStickX = v[1].toShort(),
            leftStickY = v[2].toShort(),
            rightStickX = v[3].toShort(),
            rightStickY = v[4].toShort(),
            leftTrigger = lt,
            rightTrigger = rt,
            dpad = v[7],
            touchpadX = tx,
            touchpadY = ty,
            touchpadTouch = touchActive,
            touchpadClick = click,
            touches = touches,
        )
    }

    private fun clearInputState() {
        val click = localClick
        val buttons = if (click) GamepadState.TOUCHPAD_CLICK.toUInt() else 0u
        _controllerState.value = PhysicalControllerState(
            buttons = buttons,
            touchpadX = localTouchX,
            touchpadY = localTouchY,
            touchpadTouch = localTouchActive,
            touchpadClick = click,
            touches = localTouches,
        )
    }

    // ── Touchpad (Android SOURCE_TOUCHPAD, e.g. Bluetooth DualShock 4) ──

    override fun setCapturedTouchpadState(
        normalizedX: Float, normalizedY: Float,
        touches: List<TouchPoint>, touchpadTouch: Boolean, touchpadClick: Boolean,
    ) {
        localTouchX = normalizedX
        localTouchY = normalizedY
        localTouchActive = touchpadTouch
        localClick = touchpadClick
        localTouches = touches
        val current = _controllerState.value
        val newButtons = if (touchpadClick) {
            current.buttons or GamepadState.TOUCHPAD_CLICK.toUInt()
        } else {
            current.buttons and GamepadState.TOUCHPAD_CLICK.toUInt().inv()
        }
        _controllerState.value = current.copy(
            touchpadX = normalizedX, touchpadY = normalizedY,
            touchpadTouch = touchpadTouch, touchpadClick = touchpadClick,
            touches = touches, buttons = newButtons,
        )
    }

    /** Currently active touch occupying the given stable slot (0 or 1), if any. */
    private fun activeSlot(index: Int): TouchPoint? =
        _controllerState.value.touches.getOrNull(index)?.takeIf { it.active }

    /**
     * Always emits exactly two positional slots so the downstream DSU encoder can
     * map each touch to a stable slot instead of collapsing the list (which would
     * shift a surviving finger into slot 0 when the other finger lifts).
     */
    private fun canonicalSlots(s0: TouchPoint?, s1: TouchPoint?): List<TouchPoint> = listOf(
        s0?.copy(id = 0, active = true) ?: TouchPoint(id = 0, active = false),
        s1?.copy(id = 1, active = true) ?: TouchPoint(id = 1, active = false),
    )

    /**
     * DualSense touchpad slot assignment adapted from
     * Moonlight Android (https://github.com/moonlight-stream/moonlight-android).
     * Licensed under GPLv3.
     */
    private fun assignSlots(
        old0: TouchPoint?, old1: TouchPoint?,
        candidates: List<TouchPoint>,
    ): Pair<TouchPoint?, TouchPoint?> {
        if (candidates.isEmpty()) return null to null
        if (candidates.size == 1) {
            val c = candidates[0]
            // `<=` keeps the first touch in slot 0 when both slots are empty.
            return if (distSq(old0, c) <= distSq(old1, c)) (c to null) else (null to c)
        }
        val c0 = candidates[0]
        val c1 = candidates[1]
        val cost00 = distSq(old0, c0)
        val cost11 = distSq(old1, c1)
        var best = cost00 + cost11
        var bestAssign: Pair<TouchPoint?, TouchPoint?> = (c0 to c1)
        val total = distSq(old0, c1) + distSq(old1, c0)
        if (total < best) {
            bestAssign = (c1 to c0)
        }
        return bestAssign
    }

    private fun distSq(ref: TouchPoint?, pt: TouchPoint): Float {
        if (ref == null) return 1e8f
        val dx = (ref.x - pt.x) * 1f
        val dy = (ref.y - pt.y) * 1f
        return dx * dx + dy * dy
    }

    private fun handleTouchpadMotion(event: MotionEvent): Boolean {
        var xRange = event.device?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        var yRange = event.device?.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
        if (xRange == null) xRange = event.device?.getMotionRange(MotionEvent.AXIS_X, event.source)
        if (yRange == null) yRange = event.device?.getMotionRange(MotionEvent.AXIS_Y, event.source)

        val rangeX = if (xRange != null && xRange.max - xRange.min > 0) xRange.max - xRange.min else 1920f
        val rangeY = if (yRange != null && yRange.max - yRange.min > 0) yRange.max - yRange.min else 942f
        val minX = xRange?.min ?: 0f
        val minY = yRange?.min ?: 0f
        val action = event.actionMasked

        val old0 = activeSlot(0)
        val old1 = activeSlot(1)

        val candidates = (0 until event.pointerCount).map { i ->
            val nx = ((event.getX(i) - minX) / rangeX).coerceIn(0f, 1f)
            val ny = ((event.getY(i) - minY) / rangeY).coerceIn(0f, 1f)
            TouchPoint(
                id = event.getPointerId(i),
                x = (nx * 1919).toInt().coerceIn(0, 1919),
                y = (ny * 942).toInt().coerceIn(0, 942),
                active = true,
            )
        }
        val (s0, s1) = assignSlots(old0, old1, candidates)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = ((event.getX(idx) - minX) / rangeX).coerceIn(0f, 1f)
                val y = ((event.getY(idx) - minY) / rangeY).coerceIn(0f, 1f)
                commitTouchpad(x, y, true, localClick, canonicalSlots(s0, s1))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 0) {
                    commitTouchpad(0f, 0f, false, localClick, emptyList())
                } else {
                    val primary = s0 ?: s1
                    commitTouchpad(
                        if (primary != null) (primary.x / 1919f).coerceIn(0f, 1f) else localTouchX,
                        if (primary != null) (primary.y / 942f).coerceIn(0f, 1f) else localTouchY,
                        true, localClick, canonicalSlots(s0, s1),
                    )
                }
            }
            MotionEvent.ACTION_UP -> commitTouchpad(0f, 0f, false, localClick, emptyList())
            MotionEvent.ACTION_MOVE -> {
                val primary = s0 ?: s1
                if (primary != null) {
                    commitTouchpad(
                        (primary.x / 1919f).coerceIn(0f, 1f),
                        (primary.y / 942f).coerceIn(0f, 1f),
                        true, localClick, canonicalSlots(s0, s1),
                    )
                }
            }
            MotionEvent.ACTION_CANCEL -> commitTouchpad(0f, 0f, false, localClick, emptyList())
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    commitTouchpad(localTouchX, localTouchY, localTouchActive, true, localTouches)
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    commitTouchpad(localTouchX, localTouchY, localTouchActive, false, localTouches)
                }
            }
        }

        if ((event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
            commitTouchpad(localTouchX, localTouchY, localTouchActive, true, localTouches)
        }
        return true
    }

    private fun commitTouchpad(
        x: Float, y: Float, touch: Boolean, click: Boolean, touches: List<TouchPoint>,
    ) {
        localTouchX = x
        localTouchY = y
        localTouchActive = touch
        localClick = click
        localTouches = touches
        val current = _controllerState.value
        val newButtons = if (click) {
            current.buttons or GamepadState.TOUCHPAD_CLICK.toUInt()
        } else {
            current.buttons and GamepadState.TOUCHPAD_CLICK.toUInt().inv()
        }
        _controllerState.value = current.copy(
            touchpadX = x, touchpadY = y,
            touchpadTouch = touch, touchpadClick = click,
            touches = touches, buttons = newButtons,
        )
    }

    // ── LED passthrough ────────────────────────────────────

    @Volatile
    private var lastAppliedLedColor = Int.MIN_VALUE

    @Volatile
    private var lastAppliedPlayerLed = Int.MIN_VALUE

    /**
     * Forwards the emulated controller's LED state to every attached physical gamepad
     * that exposes an LED. [color] is 0xRRGGBB; [playerLed] is the player-indicator
     * bitmask (bit0 = LED 1 …).
     */
    override fun setLedColor(color: Int, playerLed: Int) {
        if (color == lastAppliedLedColor && playerLed == lastAppliedPlayerLed) return
        lastAppliedLedColor = color
        lastAppliedPlayerLed = playerLed

        val count = SdlNative.nativeGetControllerCount()
        if (count <= 0) return
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        for (i in 0 until count) {
            if (SdlNative.nativeGetControllerHasLed(i)) {
                SdlNative.nativeSetControllerLed(i, r, g, b)
            }
            if (SdlNative.nativeGetControllerHasPlayerLed(i)) {
                // DualSense 的 playerIndicator 是“点亮灯数”模式：玩家 N 亮 N 个灯
                // （P1=0x04, P2=0x0A, P3=0x15, P4=0x1B, P5=0x1F）。SDL 的 player index
                // 0..4 对应玩家 1..5，所以用点亮灯数减一得到正确索引。
                val playerIndex = if (playerLed != 0) {
                    (Integer.bitCount(playerLed) - 1).coerceIn(0, 4)
                } else {
                    -1 // 清除玩家指示灯
                }
                SdlNative.nativeSetControllerPlayerIndex(i, playerIndex)
            }
        }
    }

    // ── Vibration ──────────────────────────────────────────

    /** Drives the two motors of the given controller from the voice-coil left/right channels. */
    override fun setControllerMotorsVibration(controllerIndex: Int, leftIntensity: Int, rightIntensity: Int) {
        val count = SdlNative.nativeGetControllerCount()
        if (controllerIndex < 0 || controllerIndex >= count) return
        driveControllerMotors(
            controllerIndex,
            leftIntensity.coerceIn(0, 255),
            rightIntensity.coerceIn(0, 255),
        )
    }

    @Volatile
    private var _lastRumbleLow = 0

    @Volatile
    private var _lastRumbleHigh = 0

    override fun rumble(lowFreqMotor: Int, highFreqMotor: Int) {
        val low = lowFreqMotor.coerceIn(0, 255)
        val high = highFreqMotor.coerceIn(0, 255)

        when (gameVibrationDevice.type) {
            VibrationDeviceType.PHONE -> {
                val wasActive = _lastRumbleLow != 0 || _lastRumbleHigh != 0
                val isNowActive = low != 0 || high != 0
                if (wasActive && !isNowActive) vibratePhone(0)
                if (isNowActive) vibratePhoneMotors(low, high, swapPhoneMotors)
            }
            VibrationDeviceType.CONTROLLER -> {
                val index = gameVibrationDevice.controllerIndex
                if (index in 0 until SdlNative.nativeGetControllerCount()) {
                    val motor0 = if (swapControllerMotors) high else low
                    val motor1 = if (swapControllerMotors) low else high
                    driveControllerMotors(index, motor0, motor1)
                }
            }
            VibrationDeviceType.NONE -> {}
        }

        _lastRumbleLow = low
        _lastRumbleHigh = high
    }

    override fun setVoiceCoilMotorOutput(leftAmp: Int, rightAmp: Int) {
        // SDL backend has no voice-coil path.
    }

    override fun setTriggerRumble(controllerIndex: Int, leftTrigger: Int, rightTrigger: Int) {
        val count = SdlNative.nativeGetControllerCount()
        if (controllerIndex < 0 || controllerIndex >= count) return
        val outputs = outputsFor(controllerIndex)
        synchronized(outputsLock) {
            outputs.leftTrigger = leftTrigger.coerceIn(0, 255)
            outputs.rightTrigger = rightTrigger.coerceIn(0, 255)
        }
        // Quad pads expose the two trigger actuators as vibrators 3 and 4, so route the
        // trigger values through the same Android path that drives the grip motors.
        applyControllerOutput(controllerIndex, outputs)
    }

    /** 强震动(low) → 马达1，弱震动(high) → 马达2；单马达设备取两者较大值。 */
    private fun vibratePhoneMotors(low: Int, high: Int, swap: Boolean) {
        val motor0 = if (swap) high else low
        val motor1 = if (swap) low else high
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val ids = vm?.vibratorIds
            if (vm != null && ids != null && ids.size >= 2) {
                vibrateMultiMotor(vm, ids, intArrayOf(motor0, motor1))
                return
            }
        }
        vibratePhone(maxOf(motor0, motor1))
    }

    private fun vibrateMultiMotor(vm: VibratorManager, ids: IntArray, amps: IntArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        var hasActive = false
        for (i in ids.indices) {
            if (i < amps.size && amps[i] > 1) {
                hasActive = true
                break
            }
        }
        if (!hasActive) {
            try { vm.cancel() } catch (_: Exception) {}
            return
        }
        try {
            vm.cancel()
            val combo = android.os.CombinedVibration.startParallel()
            for (i in ids.indices) {
                if (i < amps.size && amps[i] > 1) {
                    combo.addVibrator(ids[i], VibrationEffect.createOneShot(60000, amps[i].coerceIn(0, 255)))
                }
            }
            vm.vibrate(combo.combine())
        } catch (_: Exception) {}
    }

    /** Stops the actuators of the device that was just deselected in the game vibration setting. */
    private fun stopVibrationForDevice(device: VibrationDevice) {
        when (device.type) {
            VibrationDeviceType.PHONE -> vibratePhone(0)
            VibrationDeviceType.CONTROLLER -> {
                val index = device.controllerIndex
                cancelControllerVibration(index)
                if (index in 0 until SdlNative.nativeGetControllerCount()) {
                    SdlNative.nativeRumble(index, 0, 0, 0)
                }
            }
            VibrationDeviceType.NONE -> {}
        }
    }

    private fun vibratePhone(amp: Int) {
        val vibrator = phoneVibrator() ?: return
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
                val effect = VibrationEffect.createWaveform(longArrayOf(50), intArrayOf(clamped), 0)
                vibrator.cancel()
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60000)
            }
        } catch (_: Exception) {}
    }

    private fun phoneVibrator(): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            if (vm != null) return vm.defaultVibrator
        }
        @Suppress("DEPRECATION")
        return context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    companion object {
        private const val RUMBLE_DURATION_MS = 1000

        // SDL_GamepadType values (see SDL_gamepad.h).
        private const val SDL_GAMEPAD_TYPE_PS4 = 5
        private const val SDL_GAMEPAD_TYPE_PS5 = 6
    }
}
