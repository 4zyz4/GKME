package com.zyz4.gkme.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.VibrationDevice
import com.zyz4.gkme.model.VibrationDeviceType
import com.zyz4.gkme.model.TouchPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
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

class PhysicalControllerHandler(private val context: Context) {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val connectedDeviceIds = mutableSetOf<Int>()

    private val _controllerState = MutableStateFlow(PhysicalControllerState())
    val controllerState: StateFlow<PhysicalControllerState> = _controllerState.asStateFlow()

    private val buttonState = mutableMapOf<Int, Boolean>()
    private var dpadKeyState = 0
    private var controllerVibratorManager: VibratorManager? = null
    private var controllerVibrator: Vibrator? = null
    private var controllerTypeValue = ControllerType.UNKNOWN

    private var controllerSensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var controllerGyroListener: SensorEventListener? = null

    var controllerGyroEnabled: Boolean = false
    var nonLinearTriggerAdaptation: Boolean = false
    var controllerHasGyro: Boolean = false
    var controllerMotorCount: Int = 0

    /** Index into [connectedControllers] used as the input source; -1 disables controller input. */
    var inputControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            refreshSelectedControllerType()
            resetInputState()
        }

    /** Index into [connectedControllers] whose gyro/accel sensors are read. */
    var gyroControllerIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            refreshSelectedGyroSensor()
        }
    var gameVibrationDevice: VibrationDevice = VibrationDevice.PHONE
        set(value) {
            val previous = field
            field = value
            if (previous != value) stopVibrationForDevice(previous)
        }
    var swapPhoneMotors: Boolean = false
    var swapControllerMotors: Boolean = false
    private var gyroRegistered = false
    private var lastPhoneAmp = -1

    /** A connected physical gamepad that can receive game rumble. */
    data class ControllerInfo(val id: Int, val name: String, val motorCount: Int)

    private val _connectedControllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    val connectedControllers: StateFlow<List<ControllerInfo>> = _connectedControllers.asStateFlow()

    private val deviceVibratorManagers = mutableMapOf<Int, VibratorManager>()
    private val deviceLegacyVibrators = mutableMapOf<Int, Vibrator>()
    private val deviceMotorCounts = mutableMapOf<Int, Int>()

    var onPointerCaptureNeeded: ((Boolean) -> Unit)? = null
    var isPointerCaptureActive: Boolean = false

    private val _gyroData = MutableStateFlow(FloatArray(3))
    val gyroData: StateFlow<FloatArray> = _gyroData.asStateFlow()

    private val _accelData = MutableStateFlow(FloatArray(3))
    val accelData: StateFlow<FloatArray> = _accelData.asStateFlow()



    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            checkDevice(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            connectedDeviceIds.remove(deviceId)
            deviceVibratorManagers.remove(deviceId)
            deviceLegacyVibrators.remove(deviceId)
            deviceMotorCounts.remove(deviceId)
            updateConnectedState()
            updateConnectedControllers()
            if (connectedDeviceIds.isEmpty()) {
                unregisterGyro()
                controllerVibratorManager = null
                controllerVibrator = null
                controllerMotorCount = 0
                controllerSensorManager = null
                gyroSensor = null
                accelSensor = null
                controllerHasGyro = false
                if (controllerTypeValue == ControllerType.PS) {
                    onPointerCaptureNeeded?.invoke(false)
                }
                controllerTypeValue = ControllerType.UNKNOWN
            }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            val selectedId = _connectedControllers.value.getOrNull(gyroControllerIndex)?.id
            if (selectedId == deviceId) {
                refreshSelectedGyroSensor()
            }
        }
    }

    fun start() {
        inputManager.registerInputDeviceListener(deviceListener, null)
        val deviceIds = inputManager.inputDeviceIds
        for (id in deviceIds) {
            checkDevice(id)
        }
        updateConnectedState()
        ensureGyroRegistered()
    }

    fun stop() {
        unregisterGyro()
        inputManager.unregisterInputDeviceListener(deviceListener)
        connectedDeviceIds.clear()
        deviceVibratorManagers.clear()
        deviceLegacyVibrators.clear()
        deviceMotorCounts.clear()
        _connectedControllers.value = emptyList()
        controllerVibratorManager = null
        controllerVibrator = null
        controllerMotorCount = 0
        controllerSensorManager = null
        gyroSensor = null
        accelSensor = null
        controllerHasGyro = false
        controllerTypeValue = ControllerType.UNKNOWN
        _isConnected.value = false
    }

    private fun detectControllerType(device: InputDevice): ControllerType {
        val vid = device.vendorId
        return when (vid) {
            0x045e -> ControllerType.XBOX
            0x054c -> ControllerType.PS
            0x057e -> ControllerType.NINTENDO
            else -> ControllerType.UNKNOWN
        }
    }

    private fun checkDevice(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return
        if (!isGamepadDevice(device)) return

        connectedDeviceIds.add(deviceId)
        registerDeviceVibrator(device)

        if (connectedDeviceIds.size == 1) {
            controllerTypeValue = detectControllerType(device)

            if (controllerTypeValue == ControllerType.PS) {
                onPointerCaptureNeeded?.invoke(true)
            }
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = device.vibratorManager
                    controllerVibratorManager = vm
                    val ids = vm.vibratorIds
                    if (ids.size >= 2) {
                        controllerMotorCount = ids.size
                    }
                }
                if (controllerVibratorManager == null) {
                    @Suppress("DEPRECATION")
                    if (device.vibrator.hasVibrator()) {
                        @Suppress("DEPRECATION")
                        controllerVibrator = device.vibrator
                        controllerMotorCount = 1
                    }
                }
            }

        updateConnectedState()
        updateConnectedControllers()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            refreshSelectedGyroSensor()
        }, 150)
    }

    /** Detects the vibrators of a single gamepad and caches them for game-rumble routing. */
    private fun registerDeviceVibrator(device: InputDevice) {
        var count = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = device.vibratorManager
            val ids = vm.vibratorIds
            if (ids.isNotEmpty()) {
                deviceVibratorManagers[device.id] = vm
                count = ids.size
            }
        }
        if (count == 0) {
            @Suppress("DEPRECATION")
            if (device.vibrator.hasVibrator()) {
                @Suppress("DEPRECATION")
                deviceLegacyVibrators[device.id] = device.vibrator
                count = 1
            }
        }
        deviceMotorCounts[device.id] = count
    }

    private fun updateConnectedControllers() {
        val infos = connectedDeviceIds.mapNotNull { id ->
            val device = inputManager.getInputDevice(id) ?: return@mapNotNull null
            ControllerInfo(id, device.name ?: "手柄", deviceMotorCounts[id] ?: 0)
        }.sortedBy { it.id }
        _connectedControllers.value = infos
        refreshSelectedControllerType()
        if (inputControllerIndex !in infos.indices) {
            resetInputState()
        }
        refreshSelectedGyroSensor()
    }

    /** Device id of the gamepad currently selected as the input source, or null if disabled. */
    private fun selectedDeviceId(): Int? {
        val list = _connectedControllers.value
        val index = inputControllerIndex
        if (index < 0 || index >= list.size) return null
        return list[index].id
    }

    private fun refreshSelectedControllerType() {
        val id = selectedDeviceId()
        controllerTypeValue = if (id != null) {
            inputManager.getInputDevice(id)?.let { detectControllerType(it) } ?: ControllerType.UNKNOWN
        } else {
            ControllerType.UNKNOWN
        }
    }

    private fun resetInputState() {
        buttonState.clear()
        dpadKeyState = 0
        _controllerState.value = PhysicalControllerState()
    }

    private fun updateConnectedState() {
        _isConnected.value = connectedDeviceIds.isNotEmpty()
    }

    private fun isGamepadDevice(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    private fun isTouchpadDevice(device: InputDevice): Boolean {
        val name = device.name ?: return false
        return name.contains("Touchpad", ignoreCase = true) ||
               name.contains("Touch Pad", ignoreCase = true)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val selectedId = selectedDeviceId() ?: return false
        if (event.deviceId != selectedId) return false

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1) {
            if (controllerTypeValue == ControllerType.PS) {
                return handleButtonEvent(event, GamepadState.TOUCHPAD_CLICK)
            }
            return false
        }

        val dpadDir = keyCodeToDpad(event.keyCode)
        if (dpadDir != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                dpadKeyState = dpadKeyState or dpadDir
            } else if (event.action == KeyEvent.ACTION_UP) {
                dpadKeyState = dpadKeyState and dpadDir.inv()
            }
            _controllerState.value = _controllerState.value.copy(
                dpad = resolveDpad(dpadKeyState, 0f, 0f)
            )
            return true
        }

        val bit = keyCodeToBit(event.keyCode) ?: return false
        return handleButtonEvent(event, bit)
    }

    private fun handleButtonEvent(event: KeyEvent, bit: Int): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    buttonState[event.keyCode] = true
                    updateButtonState()
                    when (bit) {
                        GamepadState.TOUCHPAD_CLICK ->
                            _controllerState.value = _controllerState.value.copy(touchpadClick = true)
                        GamepadState.LT ->
                            _controllerState.value = _controllerState.value.copy(leftTrigger = if (nonLinearTriggerAdaptation) 255 else 1)
                        GamepadState.RT ->
                            _controllerState.value = _controllerState.value.copy(rightTrigger = if (nonLinearTriggerAdaptation) 255 else 1)
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                buttonState[event.keyCode] = false
                updateButtonState()
                when (bit) {
                    GamepadState.TOUCHPAD_CLICK ->
                        _controllerState.value = _controllerState.value.copy(touchpadClick = false)
                        GamepadState.LT ->
                            _controllerState.value = _controllerState.value.copy(leftTrigger = 0)
                        GamepadState.RT ->
                            _controllerState.value = _controllerState.value.copy(rightTrigger = 0)
                }
                return true
            }
        }
        return false
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = inputManager.getInputDevice(event.deviceId) ?: return false

        val selectedId = selectedDeviceId() ?: return false
        if (event.deviceId != selectedId) return false

        if (event.source and android.view.InputDevice.SOURCE_TOUCHPAD == android.view.InputDevice.SOURCE_TOUCHPAD) {
            return handleTouchpadMotion(event)
        }

        if (!isGamepadDevice(device)) return false

        val ltRaw = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rtRaw = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)

        val historySize = event.historySize
        for (i in 0 until historySize) {
            processAxes(
                event.getHistoricalAxisValue(MotionEvent.AXIS_X, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_Y, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_Z, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_LTRIGGER, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_RTRIGGER, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, i),
            )
        }
        processAxes(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(MotionEvent.AXIS_Z),
            event.getAxisValue(MotionEvent.AXIS_RZ),
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        return true
    }

    fun setCapturedTouchpadState(normalizedX: Float, normalizedY: Float,
        touches: List<TouchPoint>, touchpadTouch: Boolean, touchpadClick: Boolean) {
        setTouchpadStateWithClick(normalizedX, normalizedY, touchpadTouch, touchpadClick, touches)
    }

    private var lastPointerId = 0

    private fun setTouchpadStateWithClick(
        touchpadX: Float, touchpadY: Float,
        touchpadTouch: Boolean, touchpadClick: Boolean,
        touches: List<TouchPoint>
    ) {
        val current = _controllerState.value
        val newButtons = if (touchpadClick) {
            current.buttons or GamepadState.TOUCHPAD_CLICK.toUInt()
        } else {
            current.buttons and GamepadState.TOUCHPAD_CLICK.toUInt().inv()
        }
        _controllerState.value = current.copy(
            touchpadX = touchpadX, touchpadY = touchpadY,
            touchpadTouch = touchpadTouch, touchpadClick = touchpadClick,
            touches = touches, buttons = newButtons
        )
    }

    private var slot0X = 0f
    private var slot0Y = 0f
    private var slot0Active = false
    private var slot1X = 0f
    private var slot1Y = 0f
    private var slot1Active = false

    private var lastPointerButtonState = 0

    private fun buildTouchPoints(event: MotionEvent): List<TouchPoint> {
        var xRange = event.device?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        var yRange = event.device?.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
        // Fallback: try with event.source in case SOURCE_TOUCHPAD range isn't registered
        if (xRange == null) xRange = event.device?.getMotionRange(MotionEvent.AXIS_X, event.source)
        if (yRange == null) yRange = event.device?.getMotionRange(MotionEvent.AXIS_Y, event.source)
        val rangeX = if (xRange != null && xRange.max - xRange.min > 0) xRange.max - xRange.min else 1920f
        val rangeY = if (yRange != null && yRange.max - yRange.min > 0) yRange.max - yRange.min else 942f
        if (event.pointerCount <= 0) return emptyList()
        return (0 until event.pointerCount).map { i ->
            val px = event.getX(i)
            val py = event.getY(i)
            val nx = ((px - (xRange?.min ?: 0f)) / rangeX).coerceIn(0f, 1f)
            val ny = ((py - (yRange?.min ?: 0f)) / rangeY).coerceIn(0f, 1f)
            TouchPoint(id = event.getPointerId(i),
                x = (nx * 1919).toInt().coerceIn(0, 1919),
                y = (ny * 942).toInt().coerceIn(0, 942), active = true)
        }
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
            val d0 = distSq(old0, c)
            val d1 = distSq(old1, c)
            return if (d0 < d1) (c to null) else (null to c)
        }

        // Two candidates: try both permutations and pick the lowest cost.
        val c0 = candidates[0]
        val c1 = candidates[1]
        var cost00 = distSq(old0, c0)
        var cost11 = distSq(old1, c1)
        var best = cost00 + cost11
        var bestAssign: Pair<TouchPoint?, TouchPoint?> = (c0 to c1)
        val cost10 = distSq(old0, c1)
        val cost01 = distSq(old1, c0)
        val total = cost10 + cost01
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

        val rangeX = if (xRange != null && xRange.max - xRange.min > 0) {
            xRange.max - xRange.min
        } else {
            1920f
        }
        val rangeY = if (yRange != null && yRange.max - yRange.min > 0) {
            yRange.max - yRange.min
        } else {
            942f
        }
        val minX = xRange?.min ?: 0f
        val minY = yRange?.min ?: 0f
        val action = event.actionMasked

        // Read previous slot state so we can track which finger is which
        val oldState = _controllerState.value
        val old0 = oldState.touches.getOrNull(0)
        val old1 = oldState.touches.getOrNull(1)

        // Collect fresh coordinates for all active pointers (independent of pointerId)
        val candidates = (0 until event.pointerCount).map { i ->
            val px = event.getX(i)
            val py = event.getY(i)
            val nx = ((px - minX) / rangeX).coerceIn(0f, 1f)
            val ny = ((py - minY) / rangeY).coerceIn(0f, 1f)
            TouchPoint(
                id = event.getPointerId(i),
                x = (nx * 1919).toInt().coerceIn(0, 1919),
                y = (ny * 942).toInt().coerceIn(0, 942),
                active = true,
            )
        }

        // Assign to slots by nearest-coordinate matching
        val (s0, s1) = assignSlots(old0, old1, candidates)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val px = event.getX(idx)
                val py = event.getY(idx)
                val x = ((px - minX) / rangeX).coerceIn(0f, 1f)
                val y = ((py - minY) / rangeY).coerceIn(0f, 1f)
                _controllerState.value = _controllerState.value.copy(
                    touchpadX = x, touchpadY = y, touchpadTouch = true,
                    touches = listOfNotNull(s0, s1)
                )
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 0) {
                    _controllerState.value = _controllerState.value.copy(
                        touchpadTouch = false,
                        touchpadX = 0f,
                        touchpadY = 0f,
                        touches = emptyList()
                    )
                } else {
                    val primary = s0 ?: s1
                    _controllerState.value = _controllerState.value.copy(
                        touchpadX = if (primary != null) (primary.x / 1919f).coerceIn(0f, 1f) else _controllerState.value.touchpadX,
                        touchpadY = if (primary != null) (primary.y / 942f).coerceIn(0f, 1f) else _controllerState.value.touchpadY,
                        touchpadTouch = true,
                        touches = listOfNotNull(s0, s1)
                    )
                }
            }
            MotionEvent.ACTION_UP -> {
                _controllerState.value = _controllerState.value.copy(
                    touchpadTouch = false,
                    touchpadX = 0f,
                    touchpadY = 0f,
                    touches = emptyList()
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val primary = s0 ?: s1
                if (primary != null) {
                    val x = (primary.x / 1919f).coerceIn(0f, 1f)
                    val y = (primary.y / 942f).coerceIn(0f, 1f)
                    _controllerState.value = _controllerState.value.copy(
                        touchpadX = x, touchpadY = y, touchpadTouch = true,
                        touches = listOfNotNull(s0, s1)
                    )
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                _controllerState.value = _controllerState.value.copy(
                    touchpadTouch = false,
                    touchpadX = 0f,
                    touchpadY = 0f,
                    touches = emptyList()
                )
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    val current = _controllerState.value
                    _controllerState.value = current.copy(
                        touchpadClick = true,
                        buttons = current.buttons or GamepadState.TOUCHPAD_CLICK.toUInt()
                    )
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    val current = _controllerState.value
                    _controllerState.value = current.copy(
                        touchpadClick = false,
                        buttons = current.buttons and GamepadState.TOUCHPAD_CLICK.toUInt().inv()
                    )
                }
            }
        }

        // Fallback: check button state for captured click events
        if ((event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
            val current = _controllerState.value
            _controllerState.value = current.copy(
                touchpadClick = true,
                buttons = current.buttons or GamepadState.TOUCHPAD_CLICK.toUInt()
            )
        }
        return true
    }

    private fun processAxes(
        axisX: Float, axisY: Float,
        axisZ: Float, axisRz: Float,
        axisLt: Float, axisRt: Float,
        hatX: Float, hatY: Float,
    ) {
        val ltOut = (axisLt * 255f).roundToInt().coerceIn(0, 255)
        val rtOut = (axisRt * 255f).roundToInt().coerceIn(0, 255)

        _controllerState.value = _controllerState.value.copy(
            leftStickX = (axisX * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            leftStickY = (axisY * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            rightStickX = (axisZ * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            rightStickY = (axisRz * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            dpad = resolveDpad(dpadKeyState, hatX, hatY),
        )
        if (!nonLinearTriggerAdaptation) {
            _controllerState.value = _controllerState.value.copy(
                leftTrigger = ltOut,
                rightTrigger = rtOut,
            )
        }
    }

    private fun resolveDpad(dpadBits: Int, hatX: Float, hatY: Float): Int {
        val up = (dpadBits and GamepadState.DPAD_UP) != 0 || (hatY < -0.5f)
        val down = (dpadBits and GamepadState.DPAD_DOWN) != 0 || (hatY > 0.5f)
        val left = (dpadBits and GamepadState.DPAD_LEFT) != 0 || (hatX < -0.5f)
        val right = (dpadBits and GamepadState.DPAD_RIGHT) != 0 || (hatX > 0.5f)
        return when {
            up && left -> GamepadState.DPAD_UP_LEFT
            up && right -> GamepadState.DPAD_UP_RIGHT
            down && left -> GamepadState.DPAD_DOWN_LEFT
            down && right -> GamepadState.DPAD_DOWN_RIGHT
            up -> GamepadState.DPAD_UP
            down -> GamepadState.DPAD_DOWN
            left -> GamepadState.DPAD_LEFT
            right -> GamepadState.DPAD_RIGHT
            else -> 0
        }
    }

    private fun keyCodeToDpad(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> GamepadState.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> GamepadState.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GamepadState.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadState.DPAD_RIGHT
            else -> null
        }
    }

    private fun keyCodeToBit(keyCode: Int): Int? {
        return when (keyCode) {
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
            KeyEvent.KEYCODE_MEDIA_RECORD -> GamepadState.MIC_MUTE
            else -> null
        }
    }

    private fun updateButtonState() {
        var bits = 0u
        for ((keyCode, pressed) in buttonState) {
            if (pressed) {
                val bit = keyCodeToBit(keyCode)
                if (bit != null) {
                    bits = bits or bit.toUInt()
                } else if (keyCode == KeyEvent.KEYCODE_BUTTON_1 && controllerTypeValue == ControllerType.PS) {
                    bits = bits or GamepadState.TOUCHPAD_CLICK.toUInt()
                }
            }
        }
        _controllerState.value = _controllerState.value.copy(
            buttons = bits,
            touchpadClick = (bits and GamepadState.TOUCHPAD_CLICK.toUInt()) != 0u,
        )
    }

    /** Drives the two motors of the given controller from the voice-coil left/right channels. */
    fun setControllerMotorsVibration(controllerIndex: Int, leftIntensity: Int, rightIntensity: Int) {
        val info = _connectedControllers.value.getOrNull(controllerIndex) ?: return
        vibrateControllerMotors(info.id, leftIntensity, rightIntensity, false)
    }

    fun rumble(lowFreqMotor: Int, highFreqMotor: Int) {
        val lowNorm = lowFreqMotor.coerceIn(0, 255)
        val highNorm = highFreqMotor.coerceIn(0, 255)

        when (gameVibrationDevice.type) {
            VibrationDeviceType.PHONE -> vibratePhoneMotors(lowNorm, highNorm, swapPhoneMotors)
            VibrationDeviceType.CONTROLLER -> {
                val info = _connectedControllers.value.getOrNull(gameVibrationDevice.controllerIndex)
                if (info != null) {
                    vibrateControllerMotors(info.id, lowNorm, highNorm, swapControllerMotors)
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

    private fun vibrateControllerMotors(deviceId: Int, low: Int, high: Int, swap: Boolean) {
        val motor0 = if (swap) high else low
        val motor1 = if (swap) low else high
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = deviceVibratorManagers[deviceId]
            val ids = vm?.vibratorIds
            if (vm != null && ids != null && ids.isNotEmpty()) {
                vibrateMultiMotor(vm, ids, intArrayOf(motor0, motor1))
                return
            }
        }
        deviceLegacyVibrators[deviceId]?.let { vibrateLegacy(it, maxOf(motor0, motor1)) }
    }

    private fun vibrateMultiMotor(vm: VibratorManager, ids: IntArray, amps: IntArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        var hasActive = false
        for (i in ids.indices) {
            if (i < amps.size && amps[i] > 1) { hasActive = true; break }
        }
        if (!hasActive) {
            try { vm.cancel() } catch (_: Exception) {}
            return
        }
        try {
            vm.cancel()
            val combo = CombinedVibration.startParallel()
            for (i in ids.indices) {
                if (i < amps.size && amps[i] > 1) {
                    combo.addVibrator(ids[i], VibrationEffect.createOneShot(60000, amps[i].coerceIn(0, 255)))
                }
            }
            vm.vibrate(combo.combine())
        } catch (_: Exception) {}
    }

    private fun vibrateLegacy(vibrator: Vibrator, amp: Int) {
        val clamped = amp.coerceIn(0, 255)
        if (clamped < 1) {
            try { vibrator.cancel() } catch (_: Exception) {}
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createOneShot(60000, clamped))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60000)
            }
        } catch (_: Exception) {}
    }

    private fun cancelVibration() {
        controllerVibratorManager?.cancel()
        for (vm in deviceVibratorManagers.values) {
            try { vm.cancel() } catch (_: Exception) {}
        }
        for (v in deviceLegacyVibrators.values) {
            try { v.cancel() } catch (_: Exception) {}
        }
        vibratePhone(0)
    }

    /** Stops the actuators of the device that was just deselected in the game vibration setting. */
    private fun stopVibrationForDevice(device: VibrationDevice) {
        when (device.type) {
            VibrationDeviceType.PHONE -> vibratePhone(0)
            VibrationDeviceType.CONTROLLER -> {
                val info = _connectedControllers.value.getOrNull(device.controllerIndex) ?: return
                try { deviceVibratorManagers[info.id]?.cancel() } catch (_: Exception) {}
                try { deviceLegacyVibrators[info.id]?.cancel() } catch (_: Exception) {}
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
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(50),
                    intArrayOf(clamped),
                    0
                )
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

    fun onControllerGyroSettingChanged(enabled: Boolean) {
        controllerGyroEnabled = enabled
        if (controllerSensorManager == null) return
        if (enabled) registerGyro() else unregisterGyro()
    }

    fun ensureGyroRegistered() {
        if (controllerHasGyro && !gyroRegistered && controllerSensorManager != null) {
            registerGyro()
        }
    }

    /** Points the gyro/accel listener at the currently selected controller's sensors. */
    private fun refreshSelectedGyroSensor() {
        val oldManager = controllerSensorManager
        if (gyroRegistered && oldManager != null) {
            controllerGyroListener?.let { oldManager.unregisterListener(it) }
        }
        gyroRegistered = false
        controllerGyroListener = null

        val device = _connectedControllers.value.getOrNull(gyroControllerIndex)
            ?.let { inputManager.getInputDevice(it.id) }
        var sm: SensorManager? = null
        var gyro: Sensor? = null
        var accel: Sensor? = null
        if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sm = device.sensorManager
            gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        if (sm != null && gyro != null) {
            controllerSensorManager = sm
            gyroSensor = gyro
            accelSensor = accel
            controllerHasGyro = true
            if (controllerGyroEnabled) registerGyro()
        } else {
            controllerSensorManager = null
            gyroSensor = null
            accelSensor = null
            controllerHasGyro = false
        }
        if (!gyroRegistered) {
            _gyroData.value = floatArrayOf(0f, 0f, 0f)
            _accelData.value = floatArrayOf(0f, 0f, 0f)
        }
    }

    private fun registerGyro() {
        val sm = controllerSensorManager ?: return

        if (gyroRegistered) {
            controllerGyroListener?.let { sm.unregisterListener(it) }
            gyroRegistered = false
        }

        controllerGyroListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        _gyroData.value = floatArrayOf(
                            event.values[0], event.values[1], event.values[2]
                        )
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        _accelData.value = floatArrayOf(
                            event.values[0], event.values[1], event.values[2]
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val rate = SensorManager.SENSOR_DELAY_GAME
        val gyroOk = gyroSensor?.let { sm.registerListener(controllerGyroListener, it, rate) } ?: true
        val accelOk = accelSensor?.let { sm.registerListener(controllerGyroListener, it, rate) } ?: true
        gyroRegistered = gyroOk || accelOk
    }

    private fun unregisterGyro() {
        if (!gyroRegistered) return
        val sm = controllerSensorManager ?: return
        controllerGyroListener?.let { sm.unregisterListener(it) }
        controllerGyroListener = null
        _gyroData.value = floatArrayOf(0f, 0f, 0f)
        _accelData.value = floatArrayOf(0f, 0f, 0f)
        gyroRegistered = false
    }
}
