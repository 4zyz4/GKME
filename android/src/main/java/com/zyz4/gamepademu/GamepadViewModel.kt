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
import com.zyz4.gamepademu.model.AudioOutput
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.FillType
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.GyroMode
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.GyroActivateMode
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalUnsignedTypes::class)
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

    internal val _gamepadState = MutableStateFlow(GamepadState())
    internal val gamepadState: StateFlow<GamepadState> = _gamepadState

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
    private var _gyroActivateCount: Int = 0
    private val _gyroOverrideEnabled = MutableStateFlow(false)
    val gyroOverrideEnabled: StateFlow<Boolean> = _gyroOverrideEnabled.asStateFlow()

    private fun updateGyroOverrideFromCount() {
        _gyroOverrideEnabled.value = _gyroActivateCount > 0
    }

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
    private var _physicalDpadBits = 0
    private var phoneButtons: UInt = 0u
    private var phoneTouches: List<TouchPoint> = emptyList()
    private var phoneStickX: Short = 0
    private var phoneStickY: Short = 0
    private var phoneRStickX: Short = 0
    private var phoneRStickY: Short = 0
    private var phoneLT: Short = 0
    private var phoneRT: Short = 0
    private var _keyboardModifier = 0u
    private var _keyboardKeys = UShortArray(6)

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
        val existingPresets = layoutRepository.listPresets()
        var finalName = name
        if (finalName in existingPresets) {
            var suffix = 1
            while ("${finalName}_${suffix}" in existingPresets) suffix++
            finalName = "${finalName}_${suffix}"
        }
        _currentPreset.value = preset
        layoutRepository.savePreset(finalName, preset)
        connectionManager.updateSettings(settings.value.copy(currentPresetName = finalName))
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

    fun applyLayoutGyroSettings(preset: LayoutPreset) {
        preset.gyroOrientation?.let {
            currentPresetGyroOrientation = it
        }
        preset.gyroActivateMode?.let {
            val updated = settings.value.copy(gyroActivateMode = it)
            connectionManager.updateSettings(updated)
        }
        preset.gyroMode?.let {
            val updated = settings.value.copy(gyroMode = it)
            connectionManager.updateSettings(updated)
        }
        preset.gyroModeSensitivity?.let {
            val updated = settings.value.copy(gyroModeSensitivity = it)
            connectionManager.updateSettings(updated)
        }
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

    fun updatePollingRate(rate: Int) {
        connectionManager.updateSettings(settings.value.copy(pollingRate = rate))
    }

    fun updateAutoStartEnabled(enabled: Boolean) {
        connectionManager.updateSettings(settings.value.copy(autoStartEnabled = enabled))
    }

    fun updateTargetPlatform(platform: TargetPlatform) {
        connectionManager.updateSettings(settings.value.copy(targetPlatform = platform))
    }

    fun switchTargetPlatform(platform: TargetPlatform) {
        connectionManager.switchTargetPlatform(platform)
    }

    fun createDefaultLayout(): LayoutPreset {
        return layoutRepository.getDefaultPreset()
    }

    fun getServerIp(): String = connectionManager.getAllLocalIpAddresses().joinToString(", ")

    fun getServerIpFirst(): String = connectionManager.getServerIp()

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
        val mode = settings.value.gyroActivateMode
        val canGyro = if (mode == GyroActivateMode.BUTTON) {
            enabled
        } else {
            enabled
        }
        if (canGyro || settings.value.gyroMode != GyroMode.NONE) {
            startSensorDisplay()
            if (settings.value.connectionMode == ConnectionMode.WIFI ||
                settings.value.connectionMode == ConnectionMode.BLUETOOTH
            ) {
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

    fun updateGyroMode(mode: GyroMode) {
        val updated = settings.value.copy(gyroMode = mode)
        connectionManager.updateSettings(updated)
        if (settings.value.connectionMode == ConnectionMode.WIFI ||
            settings.value.connectionMode == ConnectionMode.BLUETOOTH
        ) {
            if (mode != GyroMode.NONE || settings.value.gyroEnabled) {
                startSensorSendLoop()
            } else {
                sendJob?.cancel()
                startPeriodicSendLoop()
            }
        }
    }

    fun updateGyroModeSensitivity(value: Int) {
        val updated = settings.value.copy(gyroModeSensitivity = value)
        connectionManager.updateSettings(updated)
    }

    fun updateGyroActivateMode(mode: com.zyz4.gamepademu.model.GyroActivateMode) {
        val updated = settings.value.copy(gyroActivateMode = mode)
        connectionManager.updateSettings(updated)
        if (mode == com.zyz4.gamepademu.model.GyroActivateMode.ALWAYS) {
            _gyroActivateCount = 0
            updateGyroOverrideFromCount()
        }
    }

    fun onGyroActivateButtonDown() {
        _gyroActivateCount = _gyroActivateCount + 1
        updateGyroOverrideFromCount()
    }

    fun onGyroActivateButtonUp() {
        _gyroActivateCount = maxOf(0, _gyroActivateCount - 1)
        updateGyroOverrideFromCount()
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

    fun updateLeftVoiceCoilOutput(output: AudioOutput) {
        connectionManager.updateSettings(settings.value.copy(leftVoiceCoilOutput = output))
    }

    fun updateRightVoiceCoilOutput(output: AudioOutput) {
        connectionManager.updateSettings(settings.value.copy(rightVoiceCoilOutput = output))
    }

    fun updateControllerAudioOutput(output: AudioOutput) {
        connectionManager.updateSettings(settings.value.copy(controllerAudioOutput = output))
    }

    // ── Appearance updates ──
    fun updateAppearance(transform: (AppSettings) -> AppSettings) {
        connectionManager.updateSettings(transform(settings.value))
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
        val hasPhoneLT = phoneLT.toInt() > 0
        val hasPhoneRT = phoneRT.toInt() > 0
        _physicalDpadBits = dpad and 0x0F
        val combinedBits = _dpadBits or _physicalDpadBits
        val hatValue = when (combinedBits) {
            0 -> 0
            GamepadState.DPAD_UP -> GamepadState.DPAD_UP
            GamepadState.DPAD_DOWN -> GamepadState.DPAD_DOWN
            GamepadState.DPAD_LEFT -> GamepadState.DPAD_LEFT
            GamepadState.DPAD_RIGHT -> GamepadState.DPAD_RIGHT
            GamepadState.DPAD_UP or GamepadState.DPAD_LEFT -> GamepadState.DPAD_UP_LEFT
            GamepadState.DPAD_UP or GamepadState.DPAD_RIGHT -> GamepadState.DPAD_UP_RIGHT
            GamepadState.DPAD_DOWN or GamepadState.DPAD_LEFT -> GamepadState.DPAD_DOWN_LEFT
            GamepadState.DPAD_DOWN or GamepadState.DPAD_RIGHT -> GamepadState.DPAD_DOWN_RIGHT
            else -> 0
        }
        
        _gamepadState.value = _gamepadState.value.copy(
            buttons = phoneButtons or buttons,
            leftStickX = (phoneStickX.toInt() + leftStickX.toInt()).coerceIn(-32768, 32767).toShort(),
            leftStickY = (phoneStickY.toInt() + leftStickY.toInt()).coerceIn(-32768, 32767).toShort(),
            rightStickX = (phoneRStickX.toInt() + rightStickX.toInt()).coerceIn(-32768, 32767).toShort(),
            rightStickY = (phoneRStickY.toInt() + rightStickY.toInt()).coerceIn(-32768, 32767).toShort(),
            leftTrigger = if (hasPhoneLT) maxOf(phoneLT.toInt(), leftTrigger) else leftTrigger,
            rightTrigger = if (hasPhoneRT) maxOf(phoneRT.toInt(), rightTrigger) else rightTrigger,
            dpad = hatValue,
            touchpadX = if (hasPhoneTouch) _gamepadState.value.touchpadX else tx,
            touchpadY = if (hasPhoneTouch) _gamepadState.value.touchpadY else ty,
            touchpadTouch = if (hasPhoneTouch) _gamepadState.value.touchpadTouch else touchpadTouch,
            touchpadClick = touchpadClick || hasPhoneTouch,
            touches = if (hasPhoneTouch) phoneTouches else touches,
        )
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
        connectionManager.onMouseReport = { button, dx, dy, wheel, hWheel ->
            _gamepadState.value = _gamepadState.value.copy(
                mouseButtons = button,
                mouseDx = (dx.toInt() + _gamepadState.value.mouseDx.toInt()).coerceIn(-127, 127).toShort(),
                mouseDy = (dy.toInt() + _gamepadState.value.mouseDy.toInt()).coerceIn(-127, 127).toShort(),
                mouseWheel = (wheel.toInt() + _gamepadState.value.mouseWheel.toInt()).coerceIn(-127, 127).toShort(),
                mousePan = (hWheel.toInt() + _gamepadState.value.mousePan.toInt()).coerceIn(-127, 127).toShort(),
            )
        }
        if (settings.value.connectionMode == ConnectionMode.WIFI ||
            settings.value.connectionMode == ConnectionMode.BLUETOOTH
        ) {
            if (settings.value.gyroEnabled || settings.value.gyroMode != GyroMode.NONE) {
                startSensorSendLoop()
            } else {
                startPeriodicSendLoop()
            }
        }
        if (settings.value.gyroEnabled || settings.value.gyroMode != GyroMode.NONE) {
            startSensorDisplay()
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
        sendJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var nextSendTime = System.currentTimeMillis()
            while (true) {
                if (System.currentTimeMillis() - lastBatteryRead > 2000) {
                    readBattery()
                    lastBatteryRead = System.currentTimeMillis()
                }
                val input = _gamepadState.value.toProto()
                connectionManager.sendGamepadState(input)
                val pMx = _gamepadState.value.mouseDx
                val pMy = _gamepadState.value.mouseDy
                val pMw = _gamepadState.value.mouseWheel
                val pMp = _gamepadState.value.mousePan
                if (settings.value.connectionMode == ConnectionMode.BLUETOOTH && (pMx != 0.toShort() || pMy != 0.toShort() || pMw != 0.toShort() || pMp != 0.toShort() || _gamepadState.value.mouseButtons != 0)) {
                    viewModelScope.launch {
                        connectionManager.sendMouseReport(
                            button = _gamepadState.value.mouseButtons.toByte(),
                            dx = pMx.toByte(),
                            dy = pMy.toByte(),
                            wheel = pMw.toByte(),
                            hWheel = pMp.toByte(),
                        )
                    }
                }
                if (pMx != 0.toShort() || pMy != 0.toShort() || pMw != 0.toShort() || pMp != 0.toShort()) {
                    _gamepadState.value = _gamepadState.value.copy(
                        mouseDx = 0, mouseDy = 0,
                        mouseWheel = 0, mousePan = 0,
                    )
                }
                val intervalMs = kotlin.math.round(1000.0 / settings.value.pollingRate).toLong().coerceAtLeast(1L)
                nextSendTime += intervalMs
                val waitTime = nextSendTime - System.currentTimeMillis()
                if (waitTime > 0) {
                    delay(waitTime)
                } else {
                    nextSendTime = System.currentTimeMillis()
                }
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
        sendJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var nextSendTime = System.currentTimeMillis()
            while (true) {
                if (System.currentTimeMillis() - lastBatteryRead > 2000) {
                    readBattery()
                    lastBatteryRead = System.currentTimeMillis()
                }
                val s = settings.value
                val sensor = sensorHandler.sensorData.value
                _gyroDisplay.value = Triple(sensor.gyroX, sensor.gyroY, sensor.gyroZ)
                val useControllerGyro = if (_physicalControllerConnected.value) s.controllerGyroEnabledConnected else s.controllerGyroEnabled
                val actualGyroEnabled = if (s.gyroActivateMode == com.zyz4.gamepademu.model.GyroActivateMode.BUTTON) {
                    _gyroOverrideEnabled.value
                } else {
                    s.gyroEnabled
                }
                if (!useControllerGyro && actualGyroEnabled) {
                    _gamepadState.value = _gamepadState.value.copy(
                        gyroX = sensor.gyroX * s.gyroSensitivityX / 100f,
                        gyroY = sensor.gyroY * s.gyroSensitivityY / 100f,
                        gyroZ = sensor.gyroZ * s.gyroSensitivityZ / 100f,
                        accelX = sensor.accelX,
                        accelY = sensor.accelY,
                        accelZ = sensor.accelZ,
                    )
                }

                // Apply gyro mapping mode (world coordinate system)
                if (actualGyroEnabled && !useControllerGyro) {
                    val sens = s.gyroModeSensitivity / 100f
                    when (s.gyroMode) {
                        GyroMode.MOUSE -> {
                            val gyroMx = (-sensor.gyroY * sens * 50f).toInt().coerceIn(-127, 127)
                            val gyroMy = (-sensor.gyroX * sens * 50f).toInt().coerceIn(-127, 127)
                            val totalDx = (gyroMx + _gamepadState.value.mouseDx.toInt()).coerceIn(-127, 127).toShort()
                            val totalDy = (gyroMy + _gamepadState.value.mouseDy.toInt()).coerceIn(-127, 127).toShort()
                            _gamepadState.value = _gamepadState.value.copy(
                                mouseDx = totalDx,
                                mouseDy = totalDy,
                            )
                        }
                        GyroMode.LEFT_STICK -> {
                            val lx = (-sensor.gyroY * sens * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            val ly = (-sensor.gyroX * sens * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            _gamepadState.value = _gamepadState.value.copy(
                                leftStickX = lx,
                                leftStickY = ly,
                            )
                        }
                        GyroMode.RIGHT_STICK -> {
                            val rx = (-sensor.gyroY * sens * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            val ry = (-sensor.gyroX * sens * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            _gamepadState.value = _gamepadState.value.copy(
                                rightStickX = rx,
                                rightStickY = ry,
                            )
                        }
                        else -> {}
                    }
                }

                val input = _gamepadState.value.toProto()
                connectionManager.sendGamepadState(input)
                val mDx = _gamepadState.value.mouseDx
                val mDy = _gamepadState.value.mouseDy
                val mWheel = _gamepadState.value.mouseWheel
                val mPan = _gamepadState.value.mousePan
                if (s.connectionMode == ConnectionMode.BLUETOOTH && (mDx != 0.toShort() || mDy != 0.toShort() || mWheel != 0.toShort() || mPan != 0.toShort() || _gamepadState.value.mouseButtons != 0)) {
                    viewModelScope.launch {
                        connectionManager.sendMouseReport(
                            button = _gamepadState.value.mouseButtons.toByte(),
                            dx = mDx.toByte(),
                            dy = mDy.toByte(),
                            wheel = mWheel.toByte(),
                            hWheel = mPan.toByte(),
                        )
                    }
                }
                if (mDx != 0.toShort() || mDy != 0.toShort() || mWheel != 0.toShort() || mPan != 0.toShort()) {
                    _gamepadState.value = _gamepadState.value.copy(
                        mouseDx = 0, mouseDy = 0,
                        mouseWheel = 0, mousePan = 0,
                    )
                }
                if (!actualGyroEnabled) {
                    _gamepadState.value = _gamepadState.value.copy(
                        leftStickX = 0, leftStickY = 0,
                        rightStickX = 0, rightStickY = 0,
                    )
                }
                val intervalMs = kotlin.math.round(1000.0 / settings.value.pollingRate).toLong().coerceAtLeast(1L)
                nextSendTime += intervalMs
                val waitTime = nextSendTime - System.currentTimeMillis()
                if (waitTime > 0) {
                    delay(waitTime)
                } else {
                    nextSendTime = System.currentTimeMillis()
                }
            }
        }
    }

    fun onButtonDown(bit: Int) {
        val wasDown = (_gamepadState.value.buttons and bit.toUInt()) != 0u
        phoneButtons = phoneButtons or bit.toUInt()
        val newButtons = _gamepadState.value.buttons or bit.toUInt()
        val newMouseButtons = when (bit) {
            GamepadState.MOUSE_LMB -> _gamepadState.value.mouseButtons or 1
            GamepadState.MOUSE_RMB -> _gamepadState.value.mouseButtons or 2
            GamepadState.MOUSE_MMB -> _gamepadState.value.mouseButtons or 4
            else -> _gamepadState.value.mouseButtons
        }
        _gamepadState.value = _gamepadState.value.copy(
            buttons = newButtons,
            mouseButtons = newMouseButtons,
        )
        val isMouseButton = (bit == GamepadState.MOUSE_LMB || bit == GamepadState.MOUSE_RMB || bit == GamepadState.MOUSE_MMB)
        if (settings.value.connectionMode == ConnectionMode.BLUETOOTH && isMouseButton) {
            viewModelScope.launch {
                connectionManager.sendMouseReport(
                    button = newMouseButtons.toByte(),
                    dx = 0, dy = 0, wheel = 0, hWheel = 0,
                )
            }
        }
    }

    fun onButtonUp(bit: Int) {
        val wasUp = (_gamepadState.value.buttons and bit.toUInt()) == 0u
        phoneButtons = phoneButtons and (bit.toUInt().inv())
        val newButtons = _gamepadState.value.buttons and (bit.toUInt().inv())
        val newMouseButtons = when (bit) {
            GamepadState.MOUSE_LMB -> _gamepadState.value.mouseButtons and 1.inv()
            GamepadState.MOUSE_RMB -> _gamepadState.value.mouseButtons and 2.inv()
            GamepadState.MOUSE_MMB -> _gamepadState.value.mouseButtons and 4.inv()
            else -> _gamepadState.value.mouseButtons
        }
        _gamepadState.value = _gamepadState.value.copy(
            buttons = newButtons,
            mouseButtons = newMouseButtons,
        )
        val isMouseButton = (bit == GamepadState.MOUSE_LMB || bit == GamepadState.MOUSE_RMB || bit == GamepadState.MOUSE_MMB)
        if (settings.value.connectionMode == ConnectionMode.BLUETOOTH && isMouseButton) {
            viewModelScope.launch {
                connectionManager.sendMouseReport(
                    button = newMouseButtons.toByte(),
                    dx = 0, dy = 0, wheel = 0, hWheel = 0,
                )
            }
        }
    }

    fun triggerHapticPress() {
        onHapticFeedbackPress?.invoke()
    }

    fun triggerHapticRelease() {
        onHapticFeedbackRelease?.invoke()
    }

    fun onCustomButtonDown(bits: List<Int>) {
        var pb = phoneButtons
        for (bit in bits) { pb = pb or bit.toUInt() }
        phoneButtons = pb
        var prevButtons = _gamepadState.value.buttons
        var b = prevButtons
        for (bit in bits) { b = b or bit.toUInt() }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
        if (bits.any { dpadDirOf(it) != null }) syncDpadFromButtons()
        for (bit in bits) {
            val wasDown = (prevButtons and bit.toUInt()) != 0u
            if (!wasDown) {
                onHapticFeedbackPress?.invoke()
                break
            }
        }
    }

    fun onCustomButtonUp(bits: List<Int>) {
        var prevButtons = _gamepadState.value.buttons
        var pb = phoneButtons
        for (bit in bits) { pb = pb and (bit.toUInt().inv()) }
        phoneButtons = pb
        var b = _gamepadState.value.buttons
        for (bit in bits) { b = b and (bit.toUInt().inv()) }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
        if (bits.any { dpadDirOf(it) != null }) syncDpadFromButtons()
        var anyReleased = false
        for (bit in bits) {
            if (((prevButtons and bit.toUInt()) != 0u) && ((b and bit.toUInt()) == 0u)) {
                anyReleased = true
                break
            }
        }
        if (anyReleased) {
            onHapticFeedbackRelease?.invoke()
        }
    }

    /** D-pad hat value (GamepadState.DPAD_UP/DOWN/LEFT/�?or 0) for a single output bit,
     *  or null when the bit is not a D-pad direction bit. */
    private fun dpadDirOf(bit: Int): Int? {
        return when (bit) {
            GamepadState.DPAD_BIT_UP -> GamepadState.DPAD_UP
            GamepadState.DPAD_BIT_DOWN -> GamepadState.DPAD_DOWN
            GamepadState.DPAD_BIT_LEFT -> GamepadState.DPAD_LEFT
            GamepadState.DPAD_BIT_RIGHT -> GamepadState.DPAD_RIGHT
            else -> null
        }
    }

    /** Recomputes the D-pad hat value from the currently held output bits. Any bit that maps to a
     *  D-pad direction (DPAD_BIT_UP/DOWN/LEFT/RIGHT, held by any button or keypad region) is folded
     *  into the combined hat value so Android/Linux HID outputs react despite a single-flight
     *  `dpad` field. Corner states (e.g. up|left) produce their diagonal hat value. */
    private fun syncDpadFromButtons() {
        val b = _gamepadState.value.buttons
        var combined = 0
        if ((b and GamepadState.DPAD_BIT_UP.toUInt()) != 0u) combined = combined or GamepadState.DPAD_UP
        if ((b and GamepadState.DPAD_BIT_DOWN.toUInt()) != 0u) combined = combined or GamepadState.DPAD_DOWN
        if ((b and GamepadState.DPAD_BIT_LEFT.toUInt()) != 0u) combined = combined or GamepadState.DPAD_LEFT
        if ((b and GamepadState.DPAD_BIT_RIGHT.toUInt()) != 0u) combined = combined or GamepadState.DPAD_RIGHT
        _gamepadState.value = _gamepadState.value.copy(dpad = when (combined) {
            0 -> 0
            GamepadState.DPAD_UP -> GamepadState.DPAD_UP
            GamepadState.DPAD_DOWN -> GamepadState.DPAD_DOWN
            GamepadState.DPAD_LEFT -> GamepadState.DPAD_LEFT
            GamepadState.DPAD_RIGHT -> GamepadState.DPAD_RIGHT
            GamepadState.DPAD_UP or GamepadState.DPAD_LEFT -> GamepadState.DPAD_UP_LEFT
            GamepadState.DPAD_UP or GamepadState.DPAD_RIGHT -> GamepadState.DPAD_UP_RIGHT
            GamepadState.DPAD_DOWN or GamepadState.DPAD_LEFT -> GamepadState.DPAD_DOWN_LEFT
            GamepadState.DPAD_DOWN or GamepadState.DPAD_RIGHT -> GamepadState.DPAD_DOWN_RIGHT
            else -> 0
        })
    }

    fun onVolumeKeyDown(bits: List<Int>) {
        var pb = phoneButtons
        for (bit in bits) { pb = pb or bit.toUInt() }
        phoneButtons = pb
        var b = _gamepadState.value.buttons
        for (bit in bits) { b = b or bit.toUInt() }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
    }

    fun onVolumeKeyUp(bits: List<Int>) {
        var pb = phoneButtons
        for (bit in bits) { pb = pb and (bit.toUInt().inv()) }
        phoneButtons = pb
        var b = _gamepadState.value.buttons
        for (bit in bits) { b = b and (bit.toUInt().inv()) }
        _gamepadState.value = _gamepadState.value.copy(buttons = b)
    }

    

    fun onLeftStick(x: Short, y: Short) {
        phoneStickX = x
        phoneStickY = y
        _gamepadState.value = _gamepadState.value.copy(leftStickX = x, leftStickY = y)
    }

    fun onRightStick(x: Short, y: Short) {
        phoneRStickX = x
        phoneRStickY = y
        _gamepadState.value = _gamepadState.value.copy(rightStickX = x, rightStickY = y)
    }

    fun onDpad(dir: Int, pressed: Boolean) {
        val dirValue = when (dir) {
            GamepadState.DPAD_BIT_UP -> GamepadState.DPAD_UP
            GamepadState.DPAD_BIT_DOWN -> GamepadState.DPAD_DOWN
            GamepadState.DPAD_BIT_LEFT -> GamepadState.DPAD_LEFT
            GamepadState.DPAD_BIT_RIGHT -> GamepadState.DPAD_RIGHT
            else -> dir
        }
        if (pressed) {
            _dpadBits = _dpadBits or dirValue
            onHapticFeedbackPress?.invoke()
        } else {
            _dpadBits = _dpadBits and dirValue.inv()
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
    }

    /** Batched D-pad update for multi-direction controls (e.g. the integrated d-pad). Fires the
     *  press haptic whenever the reported direction changes to another non-zero direction
     *  (including corner �?cardinal switches), never on release to zero. */
    fun updateDpad(pressed: Int, released: Int) {
        _dpadBits = (_dpadBits or pressed) and released.inv()
        if (_dpadBits != 0) onHapticFeedbackPress?.invoke()
        val hat = when (_dpadBits) {
            GamepadState.DPAD_UP, GamepadState.DPAD_DOWN,
            GamepadState.DPAD_LEFT, GamepadState.DPAD_RIGHT,
            GamepadState.DPAD_UP_LEFT, GamepadState.DPAD_UP_RIGHT,
            GamepadState.DPAD_DOWN_LEFT, GamepadState.DPAD_DOWN_RIGHT -> _dpadBits
            else -> 0
        }
        _gamepadState.value = _gamepadState.value.copy(dpad = hat)
    }

    /** Full release of a multi-direction control: plays the key-release haptic and clears all
     *  D-pad bits. Only invoked by the control when a direction was actually being held. */
    fun updateDpadRelease() {
        onHapticFeedbackRelease?.invoke()
        if (_dpadBits != 0) {
            _dpadBits = 0
            _gamepadState.value = _gamepadState.value.copy(dpad = 0)
        }
    }

    fun onLeftTrigger(value: Int) {
        phoneLT = value.toShort()
        _gamepadState.value = _gamepadState.value.copy(leftTrigger = value)
    }

    fun onRightTrigger(value: Int) {
        phoneRT = value.toShort()
        _gamepadState.value = _gamepadState.value.copy(rightTrigger = value)
    }

    fun onKeyDown(code: Int) {
        when (code) {
            in 0xE0..0xE7 -> {
                _keyboardModifier = _keyboardModifier or (1u shl (code - 0xE0))
            }
            0xE8 -> {
                _keyboardModifier = _keyboardModifier or 0x10u
            }
            0xE9 -> {
                _keyboardModifier = _keyboardModifier or 0x20u
            }
            0xEA -> {
                _keyboardModifier = _keyboardModifier or 0x40u
            }
            0xEB -> {
                _keyboardModifier = _keyboardModifier or 0x80u
            }
            else -> {
                for (i in _keyboardKeys.indices) {
                    if (_keyboardKeys[i].toInt() == 0) {
                        _keyboardKeys[i] = code.toUShort()
                        break
                    }
                }
            }
        }
        sendKeyboardReport()
    }

    fun onKeyUp(code: Int) {
        when (code) {
            in 0xE0..0xE7 -> {
                _keyboardModifier = _keyboardModifier and (1u shl (code - 0xE0)).inv()
            }
            0xE8 -> {
                _keyboardModifier = _keyboardModifier and 0x10u.inv()
            }
            0xE9 -> {
                _keyboardModifier = _keyboardModifier and 0x20u.inv()
            }
            0xEA -> {
                _keyboardModifier = _keyboardModifier and 0x40u.inv()
            }
            0xEB -> {
                _keyboardModifier = _keyboardModifier and 0x80u.inv()
            }
            else -> {
                for (i in _keyboardKeys.indices) {
                    if (_keyboardKeys[i] == code.toUShort()) {
                        _keyboardKeys[i] = 0u
                        break
                    }
                }
            }
        }
        sendKeyboardReport()
    }

    fun clearAllKeyboardKeys() {
        _keyboardModifier = 0u
        _keyboardKeys = UShortArray(6)
        sendKeyboardReport()
    }

    private fun sendKeyboardReport() {
        if (settings.value.connectionMode != com.zyz4.gamepademu.model.ConnectionMode.BLUETOOTH) return
        val modifier = _keyboardModifier.toByte()
        val keys = ByteArray(6)
        for (i in _keyboardKeys.indices) {
            keys[i] = _keyboardKeys[i].toByte()
        }
        viewModelScope.launch {
            connectionManager.sendKeyboardReport(modifier, keys)
        }
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
    }

    
}
