package com.zyz4.gamepademu.input

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
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.VibrationMotor
import com.zyz4.gamepademu.model.TouchPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _controllerName = MutableStateFlow("")
    val controllerName: StateFlow<String> = _controllerName.asStateFlow()

    private val _controllerType = MutableStateFlow(ControllerType.UNKNOWN)
    val controllerType: StateFlow<ControllerType> = _controllerType.asStateFlow()

    private val connectedDeviceIds = mutableSetOf<Int>()

    private val _controllerState = MutableStateFlow(PhysicalControllerState())
    val controllerState: StateFlow<PhysicalControllerState> = _controllerState.asStateFlow()

    private val buttonState = mutableMapOf<Int, Boolean>()
    private var controllerVibratorManager: VibratorManager? = null
    private var controllerVibrator: Vibrator? = null
    private var controllerTypeValue = ControllerType.UNKNOWN

    private var controllerSensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var controllerGyroListener: SensorEventListener? = null

    var controllerGyroEnabled: Boolean = false
    var controllerHasGyro: Boolean = false
    var controllerMotorCount: Int = 0
    var strongVibrationMapping: VibrationMotor = VibrationMotor.CONTROLLER_MOTOR_1
    var weakVibrationMapping: VibrationMotor = VibrationMotor.CONTROLLER_MOTOR_2
    private var gyroRegistered = false
    private var lastPhoneAmp = -1

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
            updateConnectedState()
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

        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    fun start() {
        inputManager.registerInputDeviceListener(deviceListener, null)
        val deviceIds = inputManager.inputDeviceIds
        for (id in deviceIds) {
            checkDevice(id)
        }
        updateConnectedState()
    }

    fun stop() {
        unregisterGyro()
        inputManager.unregisterInputDeviceListener(deviceListener)
        connectedDeviceIds.clear()
        controllerVibratorManager = null
        controllerVibrator = null
        controllerMotorCount = 0
        controllerSensorManager = null
        gyroSensor = null
        accelSensor = null
        controllerHasGyro = false
        controllerTypeValue = ControllerType.UNKNOWN
        _isConnected.value = false
        _controllerName.value = ""
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

        if (connectedDeviceIds.size == 1) {
            controllerTypeValue = detectControllerType(device)

            if (controllerTypeValue == ControllerType.PS) {
                onPointerCaptureNeeded?.invoke(true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = device.vibratorManager
                if (vm != null) {
                    val ids = vm.vibratorIds
                    if (ids.size >= 2) {
                        controllerVibratorManager = vm
                        controllerMotorCount = ids.size
                    }
                }
            }
            if (controllerVibratorManager == null && device.vibrator.hasVibrator()) {
                controllerVibrator = device.vibrator
                controllerMotorCount = 1
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sm = device.sensorManager
                if (sm != null) {
                    val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                    if (gyro != null) {
                        controllerSensorManager = sm
                        gyroSensor = gyro
                        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                        controllerHasGyro = true
                        registerGyro()
                    }
                }
            }
        }

        updateConnectedState()
    }

    private fun updateConnectedState() {
        val connected = connectedDeviceIds.isNotEmpty()
        _isConnected.value = connected
        if (connected) {
            val firstId = connectedDeviceIds.first()
            val device = inputManager.getInputDevice(firstId)
            _controllerName.value = device?.name ?: "手柄"
            _controllerType.value = if (device != null) detectControllerType(device) else ControllerType.UNKNOWN
        } else {
            _controllerName.value = ""
            _controllerType.value = ControllerType.UNKNOWN
        }
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
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1) {
            if (controllerTypeValue == ControllerType.PS) {
                return handleButtonEvent(event, GamepadState.TOUCHPAD_CLICK)
            }
            return false
        }

        if (!_isConnected.value) return false

        val dpadDir = keyCodeToDpad(event.keyCode)
        if (dpadDir != null) {
            return handleDpadKey(event, dpadDir)
        }

        val bit = keyCodeToBit(event.keyCode) ?: return false
        return handleButtonEvent(event, bit)
    }

    private fun handleDpadKey(event: KeyEvent, dir: Int): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            _controllerState.value = _controllerState.value.copy(dpad = dir)
        } else if (event.action == KeyEvent.ACTION_UP) {
            _controllerState.value = _controllerState.value.copy(dpad = 0)
        }
        return true
    }

    private fun handleButtonEvent(event: KeyEvent, bit: Int): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    buttonState[event.keyCode] = true
                    updateButtonState()
                    if (bit == GamepadState.TOUCHPAD_CLICK) {
                        _controllerState.value = _controllerState.value.copy(touchpadClick = true)
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                buttonState[event.keyCode] = false
                updateButtonState()
                if (bit == GamepadState.TOUCHPAD_CLICK) {
                    _controllerState.value = _controllerState.value.copy(touchpadClick = false)
                }
                return true
            }
        }
        return false
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = inputManager.getInputDevice(event.deviceId) ?: return false

        if (isTouchpadDevice(device)) {
            return handleTouchpadMotion(event)
        }

        if (!_isConnected.value) return false

        if (!isGamepadDevice(device)) return false

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

    /** Assign new touch points to existing slots by nearest-coordinate matching. */
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
        _controllerState.value = _controllerState.value.copy(
            leftStickX = (axisX * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            leftStickY = (axisY * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            rightStickX = (axisZ * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            rightStickY = (axisRz * 32767f).toInt().coerceIn(-32768, 32767).toShort(),
            leftTrigger = (axisLt * 255f).toInt().coerceIn(0, 255),
            rightTrigger = (axisRt * 255f).toInt().coerceIn(0, 255),
            dpad = computeDpad(hatX, hatY),
        )
    }

    private fun computeDpad(hatX: Float, hatY: Float): Int {
        return when {
            hatY < -0.5f && hatX < -0.5f -> GamepadState.DPAD_UP_LEFT
            hatY < -0.5f && hatX > 0.5f -> GamepadState.DPAD_UP_RIGHT
            hatY > 0.5f && hatX < -0.5f -> GamepadState.DPAD_DOWN_LEFT
            hatY > 0.5f && hatX > 0.5f -> GamepadState.DPAD_DOWN_RIGHT
            hatY < -0.5f -> GamepadState.DPAD_UP
            hatY > 0.5f -> GamepadState.DPAD_DOWN
            hatX < -0.5f -> GamepadState.DPAD_LEFT
            hatX > 0.5f -> GamepadState.DPAD_RIGHT
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
            KeyEvent.KEYCODE_MEDIA_RECORD -> GamepadState.SHARE
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

    fun rumble(lowFreqMotor: Int, highFreqMotor: Int) {
        val lowNorm = lowFreqMotor.coerceIn(0, 255)
        val highNorm = highFreqMotor.coerceIn(0, 255)

        // Resolve mapping — fall back to phone when target motor doesn't exist
        fun resolveMotor(m: VibrationMotor): VibrationMotor {
            if (m == VibrationMotor.PHONE_MOTOR) return m
            return if (m.ordinal < controllerMotorCount) m else VibrationMotor.PHONE_MOTOR
        }

        val strongEff = resolveMotor(strongVibrationMapping)
        val weakEff = resolveMotor(weakVibrationMapping)

        // Phone path — use maxOf matching original triggerVibration feel
        var phoneAmp = 0
        if (strongEff == VibrationMotor.PHONE_MOTOR && lowNorm > 0) phoneAmp = maxOf(phoneAmp, lowNorm)
        if (weakEff == VibrationMotor.PHONE_MOTOR && highNorm > 0) phoneAmp = maxOf(phoneAmp, highNorm)
        if (phoneAmp > 0) {
            vibratePhone(phoneAmp)
        } else if (lastPhoneAmp >= 0) {
            vibratePhone(0)
        }

        // Controller path — only when connected
        if (!_isConnected.value) return
        val ctrlVib = mutableMapOf<Int, Int>()
        if (strongEff == VibrationMotor.CONTROLLER_MOTOR_1 && lowNorm > 0) ctrlVib.merge(0, lowNorm, Int::plus)
        if (strongEff == VibrationMotor.CONTROLLER_MOTOR_2 && lowNorm > 0) ctrlVib.merge(1, lowNorm, Int::plus)
        if (weakEff == VibrationMotor.CONTROLLER_MOTOR_1 && highNorm > 0) ctrlVib.merge(0, highNorm, Int::plus)
        if (weakEff == VibrationMotor.CONTROLLER_MOTOR_2 && highNorm > 0) ctrlVib.merge(1, highNorm, Int::plus)

        if (ctrlVib.isEmpty()) {
            controllerVibratorManager?.cancel()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = controllerVibratorManager
            if (vm != null) {
                val ids = vm.vibratorIds
                val combo = CombinedVibration.startParallel()
                var hasMotor = false
                for ((idx, intensity) in ctrlVib) {
                    if (idx < ids.size) {
                        combo.addVibrator(ids[idx], VibrationEffect.createOneShot(60000, intensity.coerceIn(0, 255)))
                        hasMotor = true
                    }
                }
                if (hasMotor) {
                    try {
                        vm.cancel()
                        vm.vibrate(combo.combine())
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun cancelVibration() {
        controllerVibratorManager?.cancel()
        vibratePhone(0)
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
        if (controllerSensorManager != null) {
            registerGyro()
        }
    }

    private fun registerGyro() {
        if (gyroRegistered) return
        val sm = controllerSensorManager ?: return

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
        gyroSensor?.let { sm.registerListener(controllerGyroListener, it, rate) }
        accelSensor?.let { sm.registerListener(controllerGyroListener, it, rate) }
        gyroRegistered = true
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