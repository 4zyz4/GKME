package com.zyz4.gamepademu

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zyz4.gamepademu.data.LayoutRepository
import com.zyz4.gamepademu.input.SensorHandler
import com.zyz4.gamepademu.input.toProto
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.HapticEffect
import com.zyz4.gamepademu.model.LayoutPreset
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.model.VibrationMotor
import com.zyz4.gamepademu.model.TouchPoint
import com.zyz4.gamepademu.model.VibrationType
import com.zyz4.gamepademu.service.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class GamepadViewModel @Inject constructor(
    val connectionManager: ConnectionManager,
    private val layoutRepository: LayoutRepository,
    app: Application,
) : AndroidViewModel(app) {

    val connectionState = connectionManager.connectionState
    val settings = connectionManager.settings
    val pairedDeviceName = connectionManager.pairedDeviceName
    val isBluetoothRunning: Boolean get() = connectionManager.isBluetoothRunning

    private val _gamepadState = MutableStateFlow(GamepadState())

    private val _displayMode = MutableStateFlow(DisplayMode.XBOX)
    val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

    private val _currentPreset = MutableStateFlow(LayoutPreset())
    val currentPreset: StateFlow<LayoutPreset> = _currentPreset.asStateFlow()

    data class PresetInfo(val name: String, val buttons: List<ButtonPosition>)

    private val _presetInfos = MutableStateFlow<List<PresetInfo>>(emptyList())
    val presetInfos: StateFlow<List<PresetInfo>> = _presetInfos.asStateFlow()

    private val _selectedButtonId = MutableStateFlow<String?>(null)

    private val _gyroDisplay = MutableStateFlow(Triple(0f, 0f, 0f))
    val gyroDisplay: StateFlow<Triple<Float, Float, Float>> = _gyroDisplay.asStateFlow()

    private val _physicalControllerConnected = MutableStateFlow(false)
    val physicalControllerConnected: StateFlow<Boolean> = _physicalControllerConnected.asStateFlow()

    private val sensorHandler = SensorHandler(app)
    private var sendJob: Job? = null
    private var sensorDisplayJob: Job? = null

    var currentPresetGyroOrientation: GyroOrientation? = null
        set(value) {
            field = value
            val orientation = value ?: settings.value.gyroOrientation
            sensorHandler.gyroOrientation = orientation
        }

    fun setDeviceInverted(inverted: Boolean) {
        sensorHandler.isDeviceInverted = inverted
    }

    fun setPhysicalControllerConnected(connected: Boolean) {
        _physicalControllerConnected.value = connected
    }
    private var _dpadBits = 0
    private var phoneButtons: UInt = 0u
    private var phoneTouches: List<TouchPoint> = emptyList()

    var onHapticFeedbackPress: (() -> Unit)? = null
    var onHapticFeedbackRelease: (() -> Unit)? = null

    init {
        _displayMode.value = settings.value.displayMode
        initializeLayouts()
        if (settings.value.gyroEnabled) {
            startSensorDisplay()
        }
    }

    private fun initializeLayouts() {
        layoutRepository.createAllBuiltInPresets()
        val name = settings.value.currentPresetName
        val loaded = layoutRepository.loadPreset(name)
        if (loaded != null) {
            _currentPreset.value = loaded
        } else {
            val presets = layoutRepository.listPresets()
            if (presets.isNotEmpty()) {
                val first = layoutRepository.loadPreset(presets[0])
                if (first != null) {
                    _currentPreset.value = first
                    connectionManager.updateSettings(
                        settings.value.copy(currentPresetName = presets[0])
                    )
                }
            }
        }
        refreshPresetList()
    }

    private fun refreshPresetList() {
        _presetInfos.value = layoutRepository.listPresets().map { name ->
            val preset = layoutRepository.loadPreset(name)
            PresetInfo(name = name, buttons = preset?.buttons ?: emptyList())
        }
    }

    fun isBuiltInPreset(name: String): Boolean = layoutRepository.isBuiltInPreset(name)

    fun loadPreset(name: String): Boolean {
        val loaded = layoutRepository.loadPreset(name) ?: return false
        _currentPreset.value = loaded
        connectionManager.updateSettings(settings.value.copy(currentPresetName = name))
        return true
    }

    fun savePreset(name: String, preset: LayoutPreset) {
        _currentPreset.value = preset
        layoutRepository.savePreset(name, preset)
        connectionManager.updateSettings(settings.value.copy(currentPresetName = name))
        refreshPresetList()
    }

    fun saveCurrentPreset(preset: LayoutPreset) {
        val name = settings.value.currentPresetName
        _currentPreset.value = preset
        layoutRepository.savePreset(name, preset)
        refreshPresetList()
    }

    fun deletePreset(name: String) {
        if (layoutRepository.isBuiltInPreset(name)) return
        layoutRepository.deletePreset(name)
        refreshPresetList()
        val current = settings.value.currentPresetName
        if (current == name) {
            val presets = layoutRepository.listPresets()
            if (presets.isNotEmpty()) {
                loadPreset(presets[0])
            }
        }
    }

    fun renamePreset(oldName: String, newName: String) {
        if (layoutRepository.isBuiltInPreset(oldName)) return
        layoutRepository.renamePreset(oldName, newName)
        refreshPresetList()
        if (settings.value.currentPresetName == oldName) {
            connectionManager.updateSettings(settings.value.copy(currentPresetName = newName))
        }
    }

    fun updatePresetButtons(preset: LayoutPreset) {
        _currentPreset.value = preset
    }

    fun setSelectedButtonId(id: String?) {
        _selectedButtonId.value = id
    }

    fun updateDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
        connectionManager.updateSettings(settings.value.copy(displayMode = mode))
    }

    fun updateConnectionMode(mode: ConnectionMode) {
        connectionManager.updateSettings(settings.value.copy(connectionMode = mode))
    }

    fun updateAutoStartEnabled(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(autoStartEnabled = enabled))
    }

    fun updateTargetPlatform(platform: TargetPlatform) {
        connectionManager.updateSettings(settings.value.copy(targetPlatform = platform))
    }

    fun createDefaultLayout(): LayoutPreset {
        return layoutRepository.getDefaultPreset()
    }

    fun getServerIp(): String = connectionManager.getServerIp()

    fun unpairDevice() {
        connectionManager.unpairDevice()
    }

    fun updateEditMode(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(isEditMode = enabled))
    }

    fun updateVibrationEnabled(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(vibrationEnabled = enabled))
    }

    fun updateGameVibrationEnabled(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(gameVibrationEnabled = enabled))
    }

    fun updateVibrationPressType(type: VibrationType) {
        connectionManager.updateSettings(settings.value.copy(vibrationPressType = type))
    }

    fun updateVibrationReleaseType(type: VibrationType) {
        connectionManager.updateSettings(settings.value.copy(vibrationReleaseType = type))
    }

    fun updateVibrationPressViewEffect(effect: HapticEffect) {
        connectionManager.updateSettings(settings.value.copy(vibrationPressViewEffect = effect))
    }

    fun updateVibrationReleaseViewEffect(effect: HapticEffect) {
        connectionManager.updateSettings(settings.value.copy(vibrationReleaseViewEffect = effect))
    }

    fun updateVibrationPressDuration(duration: Int) {
        connectionManager.updateSettings(settings.value.copy(vibrationPressDuration = duration))
    }

    fun updateVibrationReleaseDuration(duration: Int) {
        connectionManager.updateSettings(settings.value.copy(vibrationReleaseDuration = duration))
    }

    fun updateVibrationPressIntensity(intensity: Int) {
        connectionManager.updateSettings(settings.value.copy(vibrationPressIntensity = intensity))
    }

    fun updateVibrationReleaseIntensity(intensity: Int) {
        connectionManager.updateSettings(settings.value.copy(vibrationReleaseIntensity = intensity))
    }

    fun updateGyroEnabled(enabled: Boolean) {
        val updated = settings.value.copy(gyroEnabled = enabled)
        connectionManager.updateSettings(updated)
        if (enabled) {
            startSensorDisplay()
            if (settings.value.connectionMode == ConnectionMode.WIFI) {
                startSensorSendLoop()
            }
        } else {
            stopSensorDisplay()
            sendJob?.cancel()
            _gamepadState.value = _gamepadState.value.copy(
                gyroX = 0f, gyroY = 0f, gyroZ = 0f,
                accelX = 0f, accelY = 0f, accelZ = 0f,
            )
            startPeriodicSendLoop()
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(keepScreenOn = enabled))
    }

    fun updateVolumeUpBits(bits: List<Int>) {
        connectionManager.updateSettings(settings.value.copy(volumeUpBits = bits))
    }

    fun updateVolumeDownBits(bits: List<Int>) {
        connectionManager.updateSettings(settings.value.copy(volumeDownBits = bits))
    }

    fun updateGyroOrientation(orientation: GyroOrientation) {
        val updated = settings.value.copy(gyroOrientation = orientation)
        connectionManager.updateSettings(updated)
        if (currentPresetGyroOrientation == null) {
            sensorHandler.gyroOrientation = orientation
        }
    }

    fun updateStrongVibrationMapping(mapping: VibrationMotor) {
        connectionManager.updateSettings(settings.value.copy(strongVibrationMapping = mapping))
    }

    fun updateStrongVibrationMappingConnected(mapping: VibrationMotor) {
        connectionManager.updateSettings(settings.value.copy(strongVibrationMappingConnected = mapping))
    }

    fun updateWeakVibrationMapping(mapping: VibrationMotor) {
        connectionManager.updateSettings(settings.value.copy(weakVibrationMapping = mapping))
    }

    fun updateWeakVibrationMappingConnected(mapping: VibrationMotor) {
        connectionManager.updateSettings(settings.value.copy(weakVibrationMappingConnected = mapping))
    }

    fun updateControllerGyroEnabled(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(controllerGyroEnabled = enabled))
    }

    fun updateControllerGyroEnabledConnected(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(controllerGyroEnabledConnected = enabled))
    }

    fun updateNonLinearTriggerAdaptation(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(nonLinearTriggerAdaptation = enabled))
    }

    fun onPhysicalControllerInput(
        buttons: UInt,
        leftStickX: Short, leftStickY: Short,
        rightStickX: Short, rightStickY: Short,
        leftTrigger: Int, rightTrigger: Int,
        dpad: Int,
        touchpadX: Float = 0f, touchpadY: Float = 0f,
        touchpadTouch: Boolean = false,
        touchpadClick: Boolean = false,
        touches: List<TouchPoint> = emptyList(),
    ) {

        val tx = (touchpadX * 1919).toInt().coerceIn(0, 1919)
        val ty = (touchpadY * 942).toInt().coerceIn(0, 942)
        val hasPhoneTouch = phoneTouches.any { it.active }
        _gamepadState.value = _gamepadState.value.copy(
            buttons = phoneButtons or buttons,
            leftStickX = leftStickX,
            leftStickY = leftStickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            dpad = dpad,
            touchpadX = if (hasPhoneTouch) _gamepadState.value.touchpadX else tx,
            touchpadY = if (hasPhoneTouch) _gamepadState.value.touchpadY else ty,
            touchpadTouch = if (hasPhoneTouch) _gamepadState.value.touchpadTouch else touchpadTouch,
            touchpadClick = touchpadClick,
            touches = if (hasPhoneTouch) phoneTouches else touches,
        )
        sendInput()
    }

    fun onPhysicalControllerGyro(gyroX: Float, gyroY: Float, gyroZ: Float, accelX: Float, accelY: Float, accelZ: Float) {
        val s = settings.value
        _gamepadState.value = _gamepadState.value.copy(
            gyroX = gyroX * s.gyroSensitivityX / 100f,
            gyroY = gyroY * s.gyroSensitivityY / 100f,
            gyroZ = gyroZ * s.gyroSensitivityZ / 100f,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
        )
    }

    fun startServer() {
        connectionManager.startServer(viewModelScope)
        if (settings.value.connectionMode == ConnectionMode.WIFI ||
            settings.value.connectionMode == ConnectionMode.BLUETOOTH
        ) {
            startPeriodicSendLoop()
        }
        if (settings.value.gyroEnabled) {
            startSensorDisplay()
            if (settings.value.connectionMode == ConnectionMode.WIFI) {
                startSensorSendLoop()
            }
        }
    }

    private fun readBattery() {
        val intent = getApplication<Application>().registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = (level * 100) / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        _gamepadState.value = _gamepadState.value.copy(
            batteryLevel = pct,
            isCharging = plugged != 0,
        )
    }

    private fun startPeriodicSendLoop() {
        sendJob?.cancel()
        var lastBatteryRead = 0L
        sendJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (now - lastBatteryRead > 2000) {
                    readBattery()
                    lastBatteryRead = now
                }
                val input = _gamepadState.value.toProto()
                connectionManager.sendGamepadState(input)
                delay(8)
            }
        }
    }

    fun stopServer() {
        sendJob?.cancel()
        connectionManager.stopServer()
    }

    private fun startSensorDisplay() {
        sensorHandler.gyroOrientation = currentPresetGyroOrientation ?: settings.value.gyroOrientation
        sensorHandler.start()
        sensorDisplayJob?.cancel()
        sensorDisplayJob = viewModelScope.launch {
            while (true) {
                val sensor = sensorHandler.sensorData.value
                _gyroDisplay.value = Triple(sensor.gyroX, sensor.gyroY, sensor.gyroZ)
                delay(8)
            }
        }
    }

    private fun stopSensorDisplay() {
        sensorDisplayJob?.cancel()
        sensorDisplayJob = null
        sensorHandler.stop()
    }

    private fun startSensorSendLoop() {
        sendJob?.cancel()
        var lastBatteryRead = 0L
        sendJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                if (now - lastBatteryRead > 2000) {
                    readBattery()
                    lastBatteryRead = now
                }
                val s = settings.value
                val sensor = sensorHandler.sensorData.value
                _gyroDisplay.value = Triple(sensor.gyroX, sensor.gyroY, sensor.gyroZ)
                val useControllerGyro = if (_physicalControllerConnected.value) s.controllerGyroEnabledConnected else s.controllerGyroEnabled
                if (!useControllerGyro) {
                    _gamepadState.value = _gamepadState.value.copy(
                        gyroX = sensor.gyroX * s.gyroSensitivityX / 100f,
                        gyroY = sensor.gyroY * s.gyroSensitivityY / 100f,
                        gyroZ = sensor.gyroZ * s.gyroSensitivityZ / 100f,
                        accelX = sensor.accelX,
                        accelY = sensor.accelY,
                        accelZ = sensor.accelZ,
                    )
                }
                val input = _gamepadState.value.toProto()
                connectionManager.sendGamepadState(input)
                delay(8)
            }
        }
    }

    fun onButtonDown(bit: Int) {
        phoneButtons = phoneButtons or bit.toUInt()
        _gamepadState.value = _gamepadState.value.copy(
            buttons = _gamepadState.value.buttons or bit.toUInt()
        )
        onHapticFeedbackPress?.invoke()
        sendInput()
    }

    fun onButtonUp(bit: Int) {
        phoneButtons = phoneButtons and (bit.toUInt().inv())
        _gamepadState.value = _gamepadState.value.copy(
            buttons = _gamepadState.value.buttons and (bit.toUInt().inv())
        )
        onHapticFeedbackRelease?.invoke()
        sendInput()
    }

    fun onCustomButtonDown(bits: List<Int>) {
        var pb = phoneButtons
        for (bit in bits) { pb = pb or bit.toUInt() }
        phoneButtons = pb
        var b = _gamepadState.value.buttons
        for (bit in bits) { b = b or bit.toUInt() }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
        onHapticFeedbackPress?.invoke()
        sendInput()
    }

    fun onCustomButtonUp(bits: List<Int>) {
        var pb = phoneButtons
        for (bit in bits) { pb = pb and (bit.toUInt().inv()) }
        phoneButtons = pb
        var b = _gamepadState.value.buttons
        for (bit in bits) { b = b and (bit.toUInt().inv()) }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
        onHapticFeedbackRelease?.invoke()
        sendInput()
    }

    fun onVolumeKeyDown(bits: List<Int>) {
        var pb = phoneButtons
        for (bit in bits) { pb = pb or bit.toUInt() }
        phoneButtons = pb
        var b = _gamepadState.value.buttons
        for (bit in bits) { b = b or bit.toUInt() }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
        fastSend()
    }

    fun onVolumeKeyUp(bits: List<Int>) {
        var pb = phoneButtons
        for (bit in bits) { pb = pb and (bit.toUInt().inv()) }
        phoneButtons = pb
        var b = _gamepadState.value.buttons
        for (bit in bits) { b = b and (bit.toUInt().inv()) }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
        fastSend()
    }

    private fun fastSend() {
        if (settings.value.connectionMode == ConnectionMode.BLUETOOTH) return
        viewModelScope.launch(Dispatchers.IO) {
            val input = _gamepadState.value.toProto()
            connectionManager.sendGamepadState(input)
        }
    }

    fun onLeftStick(x: Short, y: Short) {
        _gamepadState.value = _gamepadState.value.copy(leftStickX = x, leftStickY = y)
        sendInput()
    }

    fun onRightStick(x: Short, y: Short) {
        _gamepadState.value = _gamepadState.value.copy(rightStickX = x, rightStickY = y)
        sendInput()
    }

    fun onDpad(dir: Int, pressed: Boolean) {
        if (pressed) {
            _dpadBits = _dpadBits or dir
            onHapticFeedbackPress?.invoke()
        } else {
            _dpadBits = _dpadBits and dir.inv()
            onHapticFeedbackRelease?.invoke()
        }
        val hat = when (_dpadBits) {
            GamepadState.DPAD_UP, GamepadState.DPAD_DOWN,
            GamepadState.DPAD_LEFT, GamepadState.DPAD_RIGHT,
            GamepadState.DPAD_UP_LEFT, GamepadState.DPAD_UP_RIGHT,
            GamepadState.DPAD_DOWN_LEFT, GamepadState.DPAD_DOWN_RIGHT -> _dpadBits
            else -> 0
        }
        _gamepadState.value = _gamepadState.value.copy(dpad = hat)
        sendInput()
    }

    fun onLeftTrigger(value: Int) {
        _gamepadState.value = _gamepadState.value.copy(leftTrigger = value)
        sendInput()
    }

    fun onRightTrigger(value: Int) {
        _gamepadState.value = _gamepadState.value.copy(rightTrigger = value)
        sendInput()
    }

    fun onTouchpadTouches(touches: List<TouchPoint>) {
        phoneTouches = touches
        val primary = touches.firstOrNull { it.active }
        _gamepadState.value = _gamepadState.value.copy(
            touches = touches,
            touchpadX = primary?.x ?: 0,
            touchpadY = primary?.y ?: 0,
            touchpadTouch = primary != null
        )
        sendInput()
    }

    private fun sendInput() {
        if (settings.value.connectionMode == ConnectionMode.BLUETOOTH) return
        viewModelScope.launch {
            val input = _gamepadState.value.toProto()
            connectionManager.sendGamepadState(input)
        }
    }
}
