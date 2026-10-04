package com.zyz4.gkme.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.haptic.HapticSource
import com.zyz4.gkme.haptic.PhoneHdHaptics
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.PhysicalInputs
import com.zyz4.gkme.model.TouchPoint
import com.zyz4.gkme.model.VibrationDevice
import com.zyz4.gkme.model.VibrationDeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** 触摸板归一化输出域：x ∈ 0..1919、y ∈ 0..942（与 uhid/AIDL 约定一致）。 */
private const val TOUCHPAD_MAX_X = 1919
private const val TOUCHPAD_MAX_Y = 942
private const val TOUCHPAD_MAX_XF = 1919f
private const val TOUCHPAD_MAX_YF = 942f

/**
 * Physical-controller backend that talks to Android directly.
 *
 * Input devices are enumerated and monitored through [InputManager]; the raw key/motion
 * events delivered to the Activity are decoded here with the platform's standard gamepad
 * mapping (no SDL, no USB HID parsing). Rumble is driven through the pad's
 * [VibratorManager] (or the legacy [InputDevice.getVibrator] below API 31), gyro/accel
 * through the pad's per-device [InputDevice.getSensorManager], and the touchpad through
 * the [InputDevice.SOURCE_TOUCHPAD] events.
 */
class InputManagerPhysicalControllerBackend(private val context: Context) : PhysicalControllerBackend {

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

    /** Index into [connectedControllers] used as the input source; -1 disables controller input. */
    @Volatile
    override var inputControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            inputDeviceId = devices.getOrNull(value)?.id ?: -1
            updatePointerCapture()
            resetInputState()
        }

    /** Index into [connectedControllers] whose gyro/accel sensors are read. */
    @Volatile
    override var gyroControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            sensorAppliedDeviceId = -1
            applySensorSetting()
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

    // ── Device enumeration ─────────────────────────────────

    private val inputManager by lazy {
        context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    }

    /** Attached gamepads, in the same order as [connectedControllers]. */
    @Volatile
    private var devices: List<InputDevice> = emptyList()

    /** Android device id of the currently selected input controller (-1 = none). */
    @Volatile
    private var inputDeviceId: Int = -1

    /** Per-device resolved axis roles, keyed by InputDevice id; invalidated on device events. */
    private val axisMaps = HashMap<Int, AxisMap>()

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Whether pointer capture was last requested from the layout for touchpad input. */
    private var lastTouchpadPresent = false

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            vibratorCache.remove(deviceId)
            refreshDevices()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            vibratorCache.remove(deviceId)?.let { cancelBinding(it) }
            refreshDevices()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            vibratorCache.remove(deviceId)
            refreshDevices()
        }
    }

    // ── Input state ────────────────────────────────────────

    private var keyBits = 0
    private var dpadBits = 0
    private var leftTrigger = 0
    private var rightTrigger = 0
    private var leftStickX = 0
    private var leftStickY = 0
    private var rightStickX = 0
    private var rightStickY = 0

    // Touchpad state (normalized 0..1). Written by touchpad events, read while
    // assembling the gamepad state.
    private var localTouchX = 0f
    private var localTouchY = 0f
    private var localTouchActive = false
    private var localClick = false
    private var localTouches: List<TouchPoint> = emptyList()

    // ── Lifecycle ──────────────────────────────────────────

    override fun start() {
        if (running.get()) return
        running.set(true)
        try { inputManager?.registerInputDeviceListener(inputDeviceListener, null) } catch (_: Exception) {}
        refreshDevices()
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        try { inputManager?.unregisterInputDeviceListener(inputDeviceListener) } catch (_: Exception) {}
        unregisterSensors()
        stopAllVibration()
        vibratorCache.clear()
        devices = emptyList()
        inputDeviceId = -1
        _connectedControllers.value = emptyList()
        _isConnected.value = false
        controllerHasGyro = false
        sensorAppliedDeviceId = -1
        if (lastTouchpadPresent) {
            lastTouchpadPresent = false
            mainHandler.post { onPointerCaptureNeeded?.invoke(false) }
        }
        resetInputState()
    }

    override fun onControllerGyroSettingChanged(enabled: Boolean) {
        controllerGyroEnabled = enabled
        applySensorSetting()
    }

    override fun ensureGyroRegistered() {
        applySensorSetting()
    }

    private fun refreshDevices() {
        val list = ArrayList<InputDevice>()
        try {
            val ids = inputManager?.inputDeviceIds ?: InputDevice.getDeviceIds()
            for (id in ids) {
                val device = InputDevice.getDevice(id) ?: continue
                if (VirtualGamepad.matches(device)) continue
                val sources = device.sources
                if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                ) {
                    list.add(device)
                }
            }
        } catch (_: Exception) {
        }
        devices = list
        axisMaps.clear()
        inputDeviceId = list.getOrNull(inputControllerIndex)?.id ?: -1
        _connectedControllers.value = list.map { toInfo(it) }
        _isConnected.value = list.isNotEmpty()
        controllerHasGyro = list.getOrNull(gyroControllerIndex)?.let { hasGyroSensor(it) } ?: false
        // A device change can swap the sensor source under the same index; re-resolve.
        sensorAppliedDeviceId = -1
        applySensorSetting()
        updatePointerCapture()
        if (list.isEmpty()) resetInputState()
    }

    /**
     * Asks the layout to capture the pointer while a touchpad-capable controller is the input
     * source. Android's gamepad touchpad is delivered as a captured pointer, so the layout's
     * `onTouchpadEvent` is the path that actually feeds [setCapturedTouchpadState].
     */
    private fun updatePointerCapture() {
        val present = devices.getOrNull(inputControllerIndex)?.let { hasTouchpad(it) } ?: false
        if (present == lastTouchpadPresent) return
        lastTouchpadPresent = present
        mainHandler.post { onPointerCaptureNeeded?.invoke(present) }
    }

    private fun toInfo(device: InputDevice): ControllerInfo {
        val binding = bindingFor(device)
        // 用解析后的轴映射判断扳机是否为模拟量：device.getMotionRange(axis, device.sources)
        // 传入的是合并后的 source 掩码，无法匹配到单一 source 的 range，会恒为 null。
        // 轴解析（resolveAxes）会分别按 SOURCE_JOYSTICK / SOURCE_GAMEPAD 查找，结果可靠。
        val axes = axisMapFor(device)
        val analogTriggers = axes.leftTrigger >= 0 && axes.rightTrigger >= 0
        return ControllerInfo(
            id = device.id,
            name = device.name?.takeIf { it.isNotBlank() } ?: "手柄",
            motorCount = binding?.motorCount ?: 0,
            hasTriggerRumble = false,
            hasAdaptiveTrigger = false,
            hasGyro = hasGyroSensor(device),
            hasAnalogTrigger = analogTriggers,
            hasTouchpad = hasTouchpad(device),
            supportedButtons = PhysicalInputs.STANDARD_BUTTON_MASK,
        )
    }

    /** True when the pad (or a sibling input device with the same vendor/product) exposes a gyro. */
    private fun hasGyroSensor(device: InputDevice): Boolean {
        if (!canQuerySensors(device)) return false
        return try {
            device.sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Whether it is safe and useful to query this device's motion sensors.
     *
     * Android 12/12L has an `InputDeviceSensorManager` NPE bug triggered by the first
     * `getSensorManager()` call, so it is only queried for gamepads that plausibly carry
     * motion sensors (Sony / Nintendo). Android 13+ is unrestricted (mirrors Moonlight).
     */
    private fun canQuerySensors(device: InputDevice): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        return device.vendorId == SONY_VENDOR_ID || device.vendorId == NINTENDO_VENDOR_ID
    }

    /** True when the pad itself or a sibling input device is an Android touchpad. */
    private fun hasTouchpad(device: InputDevice): Boolean {
        if (device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD) != null &&
            device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD) != null
        ) {
            return true
        }
        if (device.sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) return true
        return try {
            InputDevice.getDeviceIds().any { id ->
                val sibling = InputDevice.getDevice(id) ?: return@any false
                sibling.id != device.id &&
                    sibling.vendorId == device.vendorId &&
                    sibling.productId == device.productId &&
                    (sibling.sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD ||
                        sibling.name?.endsWith("Touchpad") == true)
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── Motion sensors (gyro / accel) ──────────────────────

    private var sensorManager: SensorManager? = null
    private var sensorAppliedDeviceId = -1
    private var sensorAppliedEnabled = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE ->
                    _gyroData.value = floatArrayOf(event.values[0], event.values[1], event.values[2])
                Sensor.TYPE_ACCELEROMETER ->
                    _accelData.value = floatArrayOf(event.values[0], event.values[1], event.values[2])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun applySensorSetting() {
        val device = devices.getOrNull(gyroControllerIndex)
        val deviceId = device?.id ?: -1
        val enabled = controllerGyroEnabled
        if (deviceId == sensorAppliedDeviceId && enabled == sensorAppliedEnabled) return
        unregisterSensors()
        sensorAppliedDeviceId = deviceId
        sensorAppliedEnabled = enabled
        if (!enabled || device == null || !canQuerySensors(device)) return
        try {
            val manager = device.sensorManager ?: return
            val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
            val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            manager.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_FASTEST)
            if (accel != null) {
                manager.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_FASTEST)
            }
            sensorManager = manager
            controllerHasGyro = true
        } catch (_: Exception) {
            controllerHasGyro = false
        }
    }

    private fun unregisterSensors() {
        try { sensorManager?.unregisterListener(sensorListener) } catch (_: Exception) {}
        sensorManager = null
        _gyroData.value = floatArrayOf(0f, 0f, 0f)
        _accelData.value = floatArrayOf(0f, 0f, 0f)
    }

    // ── Input forwarding (called from MainActivity dispatch) ──

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.deviceId != inputDeviceId || inputDeviceId < 0) return false
        val bit = keyCodeToBit(event.keyCode)
        if (bit == 0) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyBits = keyBits or bit
            KeyEvent.ACTION_UP -> keyBits = keyBits and bit.inv()
            else -> return false
        }
        publishState()
        return true
    }

    override fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) {
            return handleTouchpadMotion(event)
        }
        if (event.deviceId != inputDeviceId || inputDeviceId < 0) return false
        val sources = event.source
        if (sources and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            sources and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }
        val device = devices.getOrNull(inputControllerIndex) ?: return false
        val axes = axisMapFor(device)

        leftStickX = normalizedStick(device, axes.leftX, axisValue(event, axes.leftX))
        leftStickY = normalizedStick(device, axes.leftY, axisValue(event, axes.leftY))
        rightStickX = normalizedStick(device, axes.rightX, axisValue(event, axes.rightX))
        rightStickY = normalizedStick(device, axes.rightY, axisValue(event, axes.rightY))

        leftTrigger = normalizedTrigger(device, axes.leftTrigger, axisValue(event, axes.leftTrigger))
        rightTrigger = normalizedTrigger(device, axes.rightTrigger, axisValue(event, axes.rightTrigger))

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        dpadBits = hatToBits(hatX, hatY)

        publishState()
        return true
    }

    override fun setCapturedTouchpadState(
        normalizedX: Float, normalizedY: Float,
        touches: List<TouchPoint>, touchpadTouch: Boolean, touchpadClick: Boolean,
    ) {
        localTouchX = normalizedX
        localTouchY = normalizedY
        localTouchActive = touchpadTouch
        localClick = touchpadClick
        localTouches = touches
        _controllerState.update { current ->
            val newButtons = if (touchpadClick) {
                current.buttons or GamepadState.TOUCHPAD_CLICK.toUInt()
            } else {
                current.buttons and GamepadState.TOUCHPAD_CLICK.toUInt().inv()
            }
            current.copy(
                touchpadX = normalizedX, touchpadY = normalizedY,
                touchpadTouch = touchpadTouch, touchpadClick = touchpadClick,
                touches = touches, buttons = newButtons,
            )
        }
    }

    // ── Touchpad (Android SOURCE_TOUCHPAD, e.g. Bluetooth DualShock 4) ──

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

        // ACTION_POINTER_UP 时 event.pointerCount 仍包含即将抬起的手指；必须把它
        // 从候选中排除，否则它占用的槽位不会被释放。
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val candidates = (0 until event.pointerCount)
            .filter { it != liftedIndex }
            .map { i ->
                val nx = ((event.getX(i) - minX) / rangeX).coerceIn(0f, 1f)
                val ny = ((event.getY(i) - minY) / rangeY).coerceIn(0f, 1f)
                TouchPoint(
                    id = event.getPointerId(i),
                    x = (nx * TOUCHPAD_MAX_X).toInt().coerceIn(0, TOUCHPAD_MAX_X),
                    y = (ny * TOUCHPAD_MAX_Y).toInt().coerceIn(0, TOUCHPAD_MAX_Y),
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
                val primary = s0 ?: s1
                if (primary != null) {
                    commitTouchpad(
                        (primary.x / TOUCHPAD_MAX_XF).coerceIn(0f, 1f),
                        (primary.y / TOUCHPAD_MAX_YF).coerceIn(0f, 1f),
                        true, localClick, canonicalSlots(s0, s1),
                    )
                } else {
                    commitTouchpad(0f, 0f, false, localClick, emptyList())
                }
            }
            MotionEvent.ACTION_UP -> commitTouchpad(0f, 0f, false, localClick, emptyList())
            MotionEvent.ACTION_MOVE -> {
                val primary = s0 ?: s1
                if (primary != null) {
                    commitTouchpad(
                        (primary.x / TOUCHPAD_MAX_XF).coerceIn(0f, 1f),
                        (primary.y / TOUCHPAD_MAX_YF).coerceIn(0f, 1f),
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
        _controllerState.update { current ->
            val newButtons = if (click) {
                current.buttons or GamepadState.TOUCHPAD_CLICK.toUInt()
            } else {
                current.buttons and GamepadState.TOUCHPAD_CLICK.toUInt().inv()
            }
            current.copy(
                touchpadX = x, touchpadY = y,
                touchpadTouch = touch, touchpadClick = click,
                touches = touches, buttons = newButtons,
            )
        }
    }

    /** Currently active touch occupying the given stable slot (0 or 1), if any. */
    private fun activeSlot(index: Int): TouchPoint? =
        _controllerState.value.touches.getOrNull(index)?.takeIf { it.active }

    /**
     * Always emits exactly two positional slots so the downstream DSU encoder can map each
     * touch to a stable slot instead of collapsing the list.
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
            return if (distSq(old0, c) <= distSq(old1, c)) (c to null) else (null to c)
        }
        val c0 = candidates[0]
        val c1 = candidates[1]
        val cost00 = distSq(old0, c0)
        val cost11 = distSq(old1, c1)
        val best = cost00 + cost11
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

    // ── State assembly ─────────────────────────────────────

    private fun publishState() {
        val triggerBits = (if (leftTrigger >= TRIGGER_DIGITAL_THRESHOLD) GamepadState.LT else 0) or
            (if (rightTrigger >= TRIGGER_DIGITAL_THRESHOLD) GamepadState.RT else 0)
        val clickBit = if (localClick) GamepadState.TOUCHPAD_CLICK else 0
        val buttons = (keyBits or dpadBits or triggerBits or clickBit).toUInt()
        _controllerState.value = PhysicalControllerState(
            buttons = buttons,
            leftStickX = leftStickX.toShort(),
            leftStickY = leftStickY.toShort(),
            rightStickX = rightStickX.toShort(),
            rightStickY = rightStickY.toShort(),
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            dpad = dpadHatValue(),
            touchpadX = localTouchX,
            touchpadY = localTouchY,
            touchpadTouch = localTouchActive,
            touchpadClick = localClick,
            touches = localTouches,
        )
    }

    private fun resetInputState() {
        keyBits = 0
        dpadBits = 0
        leftTrigger = 0
        rightTrigger = 0
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
        localTouchX = 0f
        localTouchY = 0f
        localTouchActive = false
        localClick = false
        localTouches = emptyList()
        _controllerState.value = PhysicalControllerState()
    }

    private fun dpadHatValue(): Int {
        val up = dpadBits and GamepadState.DPAD_BIT_UP != 0
        val down = dpadBits and GamepadState.DPAD_BIT_DOWN != 0
        val left = dpadBits and GamepadState.DPAD_BIT_LEFT != 0
        val right = dpadBits and GamepadState.DPAD_BIT_RIGHT != 0
        return (if (up) GamepadState.DPAD_UP else 0) or
            (if (down) GamepadState.DPAD_DOWN else 0) or
            (if (left) GamepadState.DPAD_LEFT else 0) or
            (if (right) GamepadState.DPAD_RIGHT else 0)
    }

    private fun keyCodeToBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> GamepadState.A
        KeyEvent.KEYCODE_BUTTON_B -> GamepadState.B
        KeyEvent.KEYCODE_BUTTON_X -> GamepadState.X
        KeyEvent.KEYCODE_BUTTON_Y -> GamepadState.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> GamepadState.LB
        KeyEvent.KEYCODE_BUTTON_R1 -> GamepadState.RB
        KeyEvent.KEYCODE_BUTTON_L2 -> GamepadState.LT
        KeyEvent.KEYCODE_BUTTON_R2 -> GamepadState.RT
        KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadState.SELECT
        KeyEvent.KEYCODE_BUTTON_START -> GamepadState.START
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadState.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadState.R3
        KeyEvent.KEYCODE_BUTTON_MODE -> GamepadState.HOME
        KeyEvent.KEYCODE_MEDIA_RECORD -> GamepadState.TOUCHPAD_CLICK
        KeyEvent.KEYCODE_DPAD_UP -> GamepadState.DPAD_BIT_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GamepadState.DPAD_BIT_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GamepadState.DPAD_BIT_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadState.DPAD_BIT_RIGHT
        else -> 0
    }

    private fun hatToBits(hatX: Float, hatY: Float): Int {
        var bits = 0
        if (hatY < -HAT_THRESHOLD) bits = bits or GamepadState.DPAD_BIT_UP
        if (hatY > HAT_THRESHOLD) bits = bits or GamepadState.DPAD_BIT_DOWN
        if (hatX < -HAT_THRESHOLD) bits = bits or GamepadState.DPAD_BIT_LEFT
        if (hatX > HAT_THRESHOLD) bits = bits or GamepadState.DPAD_BIT_RIGHT
        return bits
    }

    /** Resolved axis ids for one device. -1 = axis not present. */
    private class AxisMap(
        val leftX: Int,
        val leftY: Int,
        val rightX: Int,
        val rightY: Int,
        val leftTrigger: Int,
        val rightTrigger: Int,
    )

    private fun axisMapFor(device: InputDevice): AxisMap =
        axisMaps.getOrPut(device.id) { resolveAxes(device) }

    private fun axisValue(event: MotionEvent, axis: Int): Float =
        if (axis < 0) 0f else event.getAxisValue(axis)

    private fun motionRangeForJoystickAxis(device: InputDevice, axis: Int): InputDevice.MotionRange? =
        try {
            device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
                ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
        } catch (_: Exception) {
            null
        }

    /**
     * Resolves which physical axis feeds each logical control, mirroring Moonlight's
     * `createInputDeviceContextForDevice`. Triggers are detected first; when none of the
     * known trigger axis pairs exist, RX/RY is taken as the right stick and Z/RZ as the
     * triggers. Otherwise the right stick is Z/RZ (falling back to RX/RY).
     */
    private fun resolveAxes(device: InputDevice): AxisMap {
        fun range(axis: Int) = motionRangeForJoystickAxis(device, axis)

        var rightX = -1
        var rightY = -1
        var leftTrigger = -1
        var rightTrigger = -1

        val lt = range(MotionEvent.AXIS_LTRIGGER)
        val rt = range(MotionEvent.AXIS_RTRIGGER)
        val brake = range(MotionEvent.AXIS_BRAKE)
        val gas = range(MotionEvent.AXIS_GAS)
        val throttle = range(MotionEvent.AXIS_THROTTLE)
        when {
            lt != null && rt != null -> {
                leftTrigger = MotionEvent.AXIS_LTRIGGER
                rightTrigger = MotionEvent.AXIS_RTRIGGER
            }
            brake != null && gas != null -> {
                leftTrigger = MotionEvent.AXIS_BRAKE
                rightTrigger = MotionEvent.AXIS_GAS
            }
            brake != null && throttle != null -> {
                leftTrigger = MotionEvent.AXIS_BRAKE
                rightTrigger = MotionEvent.AXIS_THROTTLE
            }
            else -> {
                val rx = range(MotionEvent.AXIS_RX)
                val ry = range(MotionEvent.AXIS_RY)
                if (rx != null && ry != null) {
                    val nonStandardDs4 = device.vendorId == SONY_VENDOR_ID &&
                        device.hasKeys(KeyEvent.KEYCODE_BUTTON_C).firstOrNull() == true
                    if (nonStandardDs4) {
                        // 老 DS4 驱动把 RX/RY 当作扳机，右摇杆回落到 Z/RZ。
                        leftTrigger = MotionEvent.AXIS_RX
                        rightTrigger = MotionEvent.AXIS_RY
                    } else {
                        rightX = MotionEvent.AXIS_RX
                        rightY = MotionEvent.AXIS_RY
                        if (range(MotionEvent.AXIS_Z) != null && range(MotionEvent.AXIS_RZ) != null) {
                            leftTrigger = MotionEvent.AXIS_Z
                            rightTrigger = MotionEvent.AXIS_RZ
                        }
                    }
                }
            }
        }

        if (rightX == -1 && rightY == -1) {
            if (range(MotionEvent.AXIS_Z) != null && range(MotionEvent.AXIS_RZ) != null) {
                rightX = MotionEvent.AXIS_Z
                rightY = MotionEvent.AXIS_RZ
            } else {
                val rx = range(MotionEvent.AXIS_RX)
                val ry = range(MotionEvent.AXIS_RY)
                if (rx != null && ry != null) {
                    rightX = MotionEvent.AXIS_RX
                    rightY = MotionEvent.AXIS_RY
                }
            }
        }

        return AxisMap(
            leftX = MotionEvent.AXIS_X,
            leftY = MotionEvent.AXIS_Y,
            rightX = rightX,
            rightY = rightY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
        )
    }

    /** Maps an axis to the signed 16-bit stick range used by the rest of the app. */
    private fun normalizedStick(device: InputDevice, axis: Int, value: Float): Int {
        if (axis < 0) return 0
        val range = motionRangeForJoystickAxis(device, axis)
        val min = range?.min ?: -1f
        val max = range?.max ?: 1f
        val normalized = if (max > min) (value - min) / (max - min) * 2f - 1f else value
        return (normalized.coerceIn(-1f, 1f) * 32767f).toInt()
    }

    private fun normalizedTrigger(device: InputDevice, axis: Int, value: Float): Int {
        if (axis < 0) return 0
        val range = motionRangeForJoystickAxis(device, axis)
        val min = range?.min ?: 0f
        val max = range?.max ?: 1f
        val normalized = if (max > min) (value - min) / (max - min) else value
        return (normalized.coerceIn(0f, 1f) * 255f).toInt()
    }

    // ── Vibration ──────────────────────────────────────────

    private class AndroidVibrators(
        val manager: VibratorManager?,
        val quad: Boolean,
        val legacy: Vibrator?,
        val motorCount: Int,
    )

    private class ControllerOutputs(
        @Volatile var low: Int = 0,
        @Volatile var high: Int = 0,
        @Volatile var auxLow: Int = 0,
        @Volatile var auxHigh: Int = 0,
    )

    private val outputsLock = Any()
    private val controllerOutputs = HashMap<Int, ControllerOutputs>()

    private val vibratorCache = Collections.synchronizedMap(HashMap<Int, AndroidVibrators?>())

    override fun setLedColor(color: Int, playerLed: Int) {
        // Android's public input API does not expose gamepad LEDs.
    }

    override fun setControllerMotorsVibration(controllerIndex: Int, leftIntensity: Int, rightIntensity: Int) {
        val outputs = outputsFor(controllerIndex)
        synchronized(outputsLock) {
            outputs.auxLow = leftIntensity.coerceIn(0, 255)
            outputs.auxHigh = rightIntensity.coerceIn(0, 255)
        }
        applyControllerOutput(controllerIndex)
    }

    override fun rumble(lowFreqMotor: Int, highFreqMotor: Int) {
        val low = lowFreqMotor.coerceIn(0, 255)
        val high = highFreqMotor.coerceIn(0, 255)
        when (gameVibrationDevice.type) {
            VibrationDeviceType.PHONE -> {
                if (low == 0 && high == 0) vibratePhone(0)
                else vibratePhoneMotors(low, high, swapPhoneMotors)
            }
            VibrationDeviceType.CONTROLLER -> {
                val index = gameVibrationDevice.controllerIndex
                val motor0 = if (swapControllerMotors) high else low
                val motor1 = if (swapControllerMotors) low else high
                val outputs = outputsFor(index)
                synchronized(outputsLock) {
                    outputs.low = motor0
                    outputs.high = motor1
                }
                applyControllerOutput(index)
            }
            VibrationDeviceType.NONE -> {}
        }
    }

    override fun stopAllVibration() {
        for (index in synchronized(outputsLock) { controllerOutputs.keys.toList() }) {
            cancelControllerVibration(index)
        }
        vibratePhone(0)
    }

    private fun outputsFor(index: Int): ControllerOutputs =
        synchronized(outputsLock) { controllerOutputs.getOrPut(index) { ControllerOutputs() } }

    private fun applyControllerOutput(index: Int) {
        val outputs = outputsFor(index)
        val low = maxOf(outputs.low, outputs.auxLow)
        val high = maxOf(outputs.high, outputs.auxHigh)
        if (low == 0 && high == 0) {
            cancelControllerVibration(index)
            return
        }
        val device = devices.getOrNull(index) ?: return
        driveVibrators(bindingFor(device), low, high)
    }

    private fun cancelControllerVibration(index: Int) {
        devices.getOrNull(index)?.let { device ->
            bindingFor(device)?.let { cancelBinding(it) }
        }
        synchronized(outputsLock) {
            controllerOutputs[index]?.let {
                it.low = 0
                it.high = 0
                it.auxLow = 0
                it.auxHigh = 0
            }
        }
    }

    private fun stopVibrationForDevice(device: VibrationDevice) {
        when (device.type) {
            VibrationDeviceType.PHONE -> vibratePhone(0)
            VibrationDeviceType.CONTROLLER -> cancelControllerVibration(device.controllerIndex)
            VibrationDeviceType.NONE -> {}
        }
    }

    private fun bindingFor(device: InputDevice): AndroidVibrators? {
        if (vibratorCache.containsKey(device.id)) return vibratorCache[device.id]
        return resolveAndroidVibrators(device).also { vibratorCache[device.id] = it }
    }

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
        val legacy = device.vibrator
        @Suppress("DEPRECATION")
        if (legacy != null && legacy.hasVibrator()) {
            return AndroidVibrators(manager = null, quad = false, legacy = legacy, motorCount = 1)
        }
        return null
    }

    private fun cancelBinding(binding: AndroidVibrators) {
        try { binding.manager?.cancel() } catch (_: Exception) {}
        try { binding.legacy?.cancel() } catch (_: Exception) {}
    }

    private fun driveVibrators(binding: AndroidVibrators?, low: Int, high: Int) {
        if (binding == null) return
        val vm = binding.manager
        if (vm != null) {
            val ids = vm.vibratorIds
            // 执行器顺序对齐 GKME 的 SDL 后端：id[0] = 低频（大/左）/强，id[1] = 高频（小/右）/弱，
            // 2/3 = 左右扳机（本后端不驱动扳机，置 0）。注意这与 Moonlight 默认顺序相反
            // （Moonlight 是 [high, low]），因为 Android VibratorManager 与 SDL 的枚举顺序相反。
            if (binding.quad && ids.size >= 4) {
                rumbleCombined(vm, ids, intArrayOf(low, high, 0, 0))
            } else {
                rumbleCombined(vm, ids, intArrayOf(low, high))
            }
            return
        }
        val legacy = binding.legacy ?: return
        val amp = singleAmplitude(low, high)
        try {
            if (amp == 0) {
                legacy.cancel()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && legacy.hasAmplitudeControl()) {
                legacy.cancel()
                legacy.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION_MS, amp))
            } else {
                // 无幅度控制时用 PWM 模拟（Moonlight 的做法）。
                val pwmPeriod = 20L
                val onTime = (amp / 255.0 * pwmPeriod).toLong().coerceAtLeast(1L)
                val offTime = (pwmPeriod - onTime).coerceAtLeast(1L)
                legacy.cancel()
                legacy.vibrate(VibrationEffect.createWaveform(longArrayOf(0, onTime, offTime), 0))
            }
        } catch (_: Exception) {
        }
    }

    /** 单马达模拟幅度：80% 强（低频）+ 33% 弱（高频），与 Moonlight 一致。 */
    private fun singleAmplitude(low: Int, high: Int): Int =
        minOf(255, (low * 0.80f + high * 0.33f).toInt()).coerceIn(0, 255)

    /**
     * Drives a set of actuators in parallel. Mirrors Moonlight: when every amplitude is zero it
     * calls [VibratorManager.cancel]; otherwise it starts a fresh [CombinedVibration], which
     * replaces (and therefore stops) the vibration previously issued for this app. Amplitudes of
     * 0 are omitted because [VibrationEffect] rejects them. Note: a non-zero [VibrationManager]
     * `vibrate()` must NOT be preceded by `cancel()`, otherwise a high-rate rumble stream can
     * leave a stale effect running.
     */
    private fun rumbleCombined(vm: VibratorManager, ids: IntArray, amps: IntArray) {
        try {
            var active = false
            val combo = CombinedVibration.startParallel()
            for (i in ids.indices) {
                val amp = amps.getOrElse(i) { 0 }
                if (amp > 0) {
                    combo.addVibrator(
                        ids[i],
                        VibrationEffect.createOneShot(VIBRATION_DURATION_MS, amp.coerceIn(1, 255)),
                    )
                    active = true
                }
            }
            if (!active) {
                vm.cancel()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 默认 usage 可能被系统当作通知震动压低；游戏震动标记为 USAGE_MEDIA。
                val attributes = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build()
                vm.vibrate(combo.combine(), attributes)
            } else {
                vm.vibrate(combo.combine())
            }
        } catch (_: Exception) {
        }
    }

    // ── Phone vibration ────────────────────────────────────

    private var lastPhoneAmp = -1
    private var phoneHdOwned = false

    private fun vibratePhoneMotors(low: Int, high: Int, swap: Boolean) {
        val motor0 = if (swap) high else low
        val motor1 = if (swap) low else high
        if (PhoneHdHaptics.playMotors(motor0, motor1, source = HapticSource.GAME_RUMBLE)) {
            if (!phoneHdOwned) {
                phoneHdOwned = true
                // HD 接管前清掉普通/多马达通路，避免旧波形残留。
                try { phoneVibratorManager()?.cancel() } catch (_: Exception) {}
                try { phoneVibrator()?.cancel() } catch (_: Exception) {}
            }
            lastPhoneAmp = maxOf(motor0, motor1)
            return
        }
        phoneHdOwned = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = phoneVibratorManager()
            val ids = vm?.vibratorIds
            if (vm != null && ids != null && ids.size >= 2) {
                rumbleCombined(vm, ids, intArrayOf(motor0, motor1))
                return
            }
        }
        vibratePhone(maxOf(motor0, motor1))
    }

    private fun vibratePhone(amp: Int) {
        val clamped = amp.coerceIn(0, 255)
        if (clamped < 1) {
            phoneHdOwned = false
            PhoneHdHaptics.stop(HapticSource.GAME_RUMBLE)
            // 多马达路径是在 VibratorManager 上启动的：必须用 manager.cancel() 才能停掉
            // 全部马达，只 cancel 默认马达会遗漏其余马达，直到 60s one-shot 自行过期。
            try { phoneVibratorManager()?.cancel() } catch (_: Exception) {}
            try { phoneVibrator()?.cancel() } catch (_: Exception) {}
            lastPhoneAmp = -1
            return
        }
        if (clamped == lastPhoneAmp) return
        val vibrator = phoneVibrator() ?: return
        lastPhoneAmp = clamped
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(50), intArrayOf(clamped), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATION_DURATION_MS)
            }
        } catch (_: Exception) {
        }
    }

    private fun phoneVibratorManager(): VibratorManager? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        }
        return null
    }

    private fun phoneVibrator(): Vibrator? {
        phoneVibratorManager()?.let { return it.defaultVibrator }
        @Suppress("DEPRECATION")
        return context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    companion object {
        private const val SONY_VENDOR_ID = 0x054c
        private const val NINTENDO_VENDOR_ID = 0x057e
        private const val HAT_THRESHOLD = 0.5f
        private const val TRIGGER_DIGITAL_THRESHOLD = 128
        private const val VIBRATION_DURATION_MS = 60000L
    }
}
