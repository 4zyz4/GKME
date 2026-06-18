package com.zyz4.gamepademu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zyz4.gamepademu.data.LayoutRepository
import com.zyz4.gamepademu.input.SensorHandler
import com.zyz4.gamepademu.input.toProto
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.ControllerMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.LayoutPreset
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.service.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class GamepadViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
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

    private val sensorHandler = SensorHandler(app)
    private var sendJob: Job? = null

    var onHapticFeedbackPress: (() -> Unit)? = null
    var onHapticFeedbackRelease: (() -> Unit)? = null

    init {
        _displayMode.value = settings.value.displayMode
        initializeLayouts()
        connectionManager.onControllerModeChanged = { mode ->
            sendJob?.cancel()
            sensorHandler.stop()
            if (mode == ControllerMode.DS4 && settings.value.connectionMode == ConnectionMode.WIFI) {
                sensorHandler.start()
                startSensorSendLoop()
            } else {
                startPeriodicSendLoop()
            }
        }
    }

    private fun initializeLayouts() {
        if (!layoutRepository.hasAnyPreset()) {
            val defaultPreset = layoutRepository.createDefaultPreset()
            _currentPreset.value = defaultPreset
        } else {
            val name = settings.value.currentPresetName
            val loaded = layoutRepository.loadPreset(name)
            if (loaded != null) {
                _currentPreset.value = ensureDefaultButtons(loaded)
            } else {
                val presets = layoutRepository.listPresets()
                if (presets.isNotEmpty()) {
                    val first = layoutRepository.loadPreset(presets[0])
                    if (first != null) {
                        _currentPreset.value = ensureDefaultButtons(first)
                        connectionManager.updateSettings(
                            settings.value.copy(currentPresetName = presets[0])
                        )
                    }
                }
            }
        }
        refreshPresetList()
    }

    private fun ensureDefaultButtons(preset: LayoutPreset): LayoutPreset {
        val existing = preset.buttons.map { it.id }.toSet()
        val missing = DEFAULT_BUTTON_IDS - existing
        if (missing.isEmpty()) return preset
        val defaults = layoutRepository.createDefaultPreset().buttons.filter { it.id in missing }
        val merged = preset.buttons + defaults
        val updated = preset.copy(buttons = merged)
        layoutRepository.savePreset(settings.value.currentPresetName, updated)
        return updated
    }

    companion object {
        private val DEFAULT_BUTTON_IDS = setOf(
            "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
            "btnY", "btnA", "btnX", "btnB",
            "leftJoystick", "rightJoystick",
            "btnLT", "btnLB", "btnRT", "btnRB",
            "btnSelect", "btnHome", "btnMenu", "centerArea",
        )
    }

    private fun refreshPresetList() {
        _presetInfos.value = layoutRepository.listPresets().map { name ->
            val preset = layoutRepository.loadPreset(name)
            PresetInfo(name = name, buttons = preset?.buttons ?: emptyList())
        }
    }

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

    fun saveCurrentPreset() {
        val name = settings.value.currentPresetName
        layoutRepository.savePreset(name, _currentPreset.value)
        refreshPresetList()
    }

    fun deletePreset(name: String) {
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
        val updated = if (mode == ConnectionMode.BLUETOOTH) {
            settings.value.copy(connectionMode = mode, controllerMode = ControllerMode.XBOX_360)
        } else {
            settings.value.copy(connectionMode = mode)
        }
        connectionManager.updateSettings(updated)
    }

    fun updateTargetPlatform(platform: TargetPlatform) {
        connectionManager.updateSettings(settings.value.copy(targetPlatform = platform))
    }

    fun updateWifiServer(ip: String, port: Int) {
        connectionManager.updateSettings(settings.value.copy(wifiServerIp = ip, wifiServerPort = port))
    }

    fun getServerIp(): String = connectionManager.getServerIp()

    fun unpairDevice() {
        connectionManager.unpairDevice()
    }

    fun updateEditMode(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(isEditMode = enabled))
    }

    fun startServer() {
        connectionManager.startServer(viewModelScope)
        if (settings.value.connectionMode == ConnectionMode.WIFI ||
            settings.value.connectionMode == ConnectionMode.BLUETOOTH
        ) {
            startPeriodicSendLoop()
        }
        if (settings.value.controllerMode == ControllerMode.DS4 &&
            settings.value.connectionMode == ConnectionMode.WIFI
        ) {
            sensorHandler.start()
            startSensorSendLoop()
        }
    }

    private fun startPeriodicSendLoop() {
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            while (true) {
                val frameStart = System.nanoTime()
                val input = _gamepadState.value.toProto()
                connectionManager.sendGamepadState(input)
                val elapsed = (System.nanoTime() - frameStart) / 1_000_000L
                val remaining = connectionManager.pollingIntervalMs.value.toLong() - elapsed
                if (remaining > 0) delay(remaining.milliseconds)
            }
        }
    }

    fun stopServer() {
        sendJob?.cancel()
        sensorHandler.stop()
        connectionManager.stopServer()
    }

    private fun startSensorSendLoop() {
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            while (true) {
                val frameStart = System.nanoTime()
                val sensor = sensorHandler.sensorData.value
                val input = _gamepadState.value.copy(
                    gyroX = sensor.gyroX,
                    gyroY = sensor.gyroY,
                    gyroZ = sensor.gyroZ,
                    accelX = sensor.accelX,
                    accelY = sensor.accelY,
                    accelZ = sensor.accelZ,
                ).toProto()
                connectionManager.sendGamepadState(input)
                val elapsed = (System.nanoTime() - frameStart) / 1_000_000L
                val remaining = connectionManager.pollingIntervalMs.value.toLong() - elapsed
                if (remaining > 0) delay(remaining.milliseconds)
            }
        }
    }

    fun onButtonDown(bit: Int) {
        _gamepadState.value = _gamepadState.value.copy(
            buttons = _gamepadState.value.buttons or bit.toUInt()
        )
        onHapticFeedbackPress?.invoke()
        sendInput()
    }

    fun onButtonUp(bit: Int) {
        _gamepadState.value = _gamepadState.value.copy(
            buttons = _gamepadState.value.buttons and (bit.toUInt().inv())
        )
        onHapticFeedbackRelease?.invoke()
        sendInput()
    }

    fun onLeftStick(x: Short, y: Short) {
        _gamepadState.value = _gamepadState.value.copy(leftStickX = x, leftStickY = y)
        sendInput()
    }

    fun onRightStick(x: Short, y: Short) {
        _gamepadState.value = _gamepadState.value.copy(rightStickX = x, rightStickY = y)
        sendInput()
    }

    fun onDpad(direction: Int) {
        _gamepadState.value = _gamepadState.value.copy(dpad = direction)
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

    fun onTouchpad(x: Int, y: Int, touching: Boolean) {
        _gamepadState.value = _gamepadState.value.copy(
            touchpadX = x, touchpadY = y, touchpadTouch = touching
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
