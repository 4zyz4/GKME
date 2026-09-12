package com.zyz4.gkme.input

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

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
class PhysicalControllerHandler(private val context: Context) {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _controllerState = MutableStateFlow(PhysicalControllerState())
    val controllerState: StateFlow<PhysicalControllerState> = _controllerState.asStateFlow()

    private val _connectedControllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    val connectedControllers: StateFlow<List<ControllerInfo>> = _connectedControllers.asStateFlow()

    private val _gyroData = MutableStateFlow(FloatArray(3))
    val gyroData: StateFlow<FloatArray> = _gyroData.asStateFlow()

    private val _accelData = MutableStateFlow(FloatArray(3))
    val accelData: StateFlow<FloatArray> = _accelData.asStateFlow()

    /** A connected physical gamepad that can receive game rumble. */
    data class ControllerInfo(val id: Int, val name: String, val motorCount: Int)

    @Volatile
    var controllerGyroEnabled: Boolean = false

    @Volatile
    var nonLinearTriggerAdaptation: Boolean = false

    @Volatile
    var controllerHasGyro: Boolean = false

    /** Index into [connectedControllers] used as the input source; -1 disables controller input. */
    @Volatile
    var inputControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            resetInputState()
        }

    /** Index into [connectedControllers] whose gyro/accel sensors are read. */
    @Volatile
    var gyroControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            applySensorSetting()
            _gyroData.value = floatArrayOf(0f, 0f, 0f)
            _accelData.value = floatArrayOf(0f, 0f, 0f)
        }

    @Volatile
    var gameVibrationDevice: VibrationDevice = VibrationDevice.PHONE
        set(value) {
            val previous = field
            field = value
            if (previous != value) stopVibrationForDevice(previous)
        }

    @Volatile
    var swapPhoneMotors: Boolean = false

    @Volatile
    var swapControllerMotors: Boolean = false

    var onPointerCaptureNeeded: ((Boolean) -> Unit)? = null
    var isPointerCaptureActive: Boolean = false

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

    fun start() {
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
        running.set(true)
        pollThread = Thread({ pollLoop() }, "GkmeSdlPoll").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        pollThread?.join(500)
        pollThread = null
        SdlPlatform.shutdown()
        SdlNative.nativeShutdown()
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
        val infos = (0 until count).map { i ->
            ControllerInfo(
                id = SdlNative.nativeGetControllerInstanceId(i),
                name = SdlNative.nativeGetControllerName(i).ifBlank { "手柄${i + 1}" },
                motorCount = SdlNative.nativeGetControllerMotorCount(i),
            )
        }
        // StateFlow conflates equal lists, so this only emits on real changes.
        _connectedControllers.value = infos
        _isConnected.value = count > 0
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

    fun onControllerGyroSettingChanged(enabled: Boolean) {
        controllerGyroEnabled = enabled
        applySensorSetting()
    }

    fun ensureGyroRegistered() {
        applySensorSetting()
    }

    // ── Input forwarding (called from MainActivity dispatch) ──

    fun handleKeyEvent(event: KeyEvent): Boolean {
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

    fun handleMotionEvent(event: MotionEvent): Boolean {
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
            val list = ArrayList<TouchPoint>(touchCount)
            for (i in 0 until touchCount) {
                list.add(
                    TouchPoint(
                        id = i,
                        x = v[9 + i * 2].coerceIn(0, 1919),
                        y = v[10 + i * 2].coerceIn(0, 942),
                        active = true,
                    )
                )
            }
            touches = list
            tx = v[9] / 1919f
            ty = v[10] / 942f
            touchActive = true
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

        val lt = if (nonLinearTriggerAdaptation) {
            if (v[5] > 128) 255 else 0
        } else {
            v[5]
        }
        val rt = if (nonLinearTriggerAdaptation) {
            if (v[6] > 128) 255 else 0
        } else {
            v[6]
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

    fun setCapturedTouchpadState(
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
            return if (distSq(old0, c) < distSq(old1, c)) (c to null) else (null to c)
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

        val old0 = _controllerState.value.touches.getOrNull(0)
        val old1 = _controllerState.value.touches.getOrNull(1)

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
                commitTouchpad(x, y, true, localClick, listOfNotNull(s0, s1))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 0) {
                    commitTouchpad(0f, 0f, false, localClick, emptyList())
                } else {
                    val primary = s0 ?: s1
                    commitTouchpad(
                        if (primary != null) (primary.x / 1919f).coerceIn(0f, 1f) else localTouchX,
                        if (primary != null) (primary.y / 942f).coerceIn(0f, 1f) else localTouchY,
                        true, localClick, listOfNotNull(s0, s1),
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
                        true, localClick, listOfNotNull(s0, s1),
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

    // ── Vibration ──────────────────────────────────────────

    /** Drives the two motors of the given controller from the voice-coil left/right channels. */
    fun setControllerMotorsVibration(controllerIndex: Int, leftIntensity: Int, rightIntensity: Int) {
        val count = SdlNative.nativeGetControllerCount()
        if (controllerIndex < 0 || controllerIndex >= count) return
        SdlNative.nativeRumble(
            controllerIndex,
            leftIntensity.coerceIn(0, 255) * 257,
            rightIntensity.coerceIn(0, 255) * 257,
            RUMBLE_DURATION_MS,
        )
    }

    fun rumble(lowFreqMotor: Int, highFreqMotor: Int) {
        val low = lowFreqMotor.coerceIn(0, 255)
        val high = highFreqMotor.coerceIn(0, 255)

        when (gameVibrationDevice.type) {
            VibrationDeviceType.PHONE -> vibratePhoneMotors(low, high, swapPhoneMotors)
            VibrationDeviceType.CONTROLLER -> {
                val index = gameVibrationDevice.controllerIndex
                if (index in 0 until SdlNative.nativeGetControllerCount()) {
                    val motor0 = if (swapControllerMotors) high else low
                    val motor1 = if (swapControllerMotors) low else high
                    SdlNative.nativeRumble(index, motor0 * 257, motor1 * 257, RUMBLE_DURATION_MS)
                }
            }
            VibrationDeviceType.NONE -> {}
        }
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
    }
}
