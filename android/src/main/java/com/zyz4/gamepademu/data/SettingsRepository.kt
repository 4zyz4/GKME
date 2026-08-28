package com.zyz4.gamepademu.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.FillType
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.GyroMode
import com.zyz4.gamepademu.model.GyroActivateMode
import com.zyz4.gamepademu.model.HapticEffect
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.model.AudioOutput
import com.zyz4.gamepademu.model.VibrationMotor
import com.zyz4.gamepademu.model.VibrationType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val DISPLAY_MODE = intPreferencesKey("display_mode")
        val CONNECTION_MODE = intPreferencesKey("connection_mode")
        val TARGET_PLATFORM = intPreferencesKey("target_platform")
        val POLLING_RATE = intPreferencesKey("polling_rate")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val CURRENT_PRESET_NAME = stringPreferencesKey("current_preset_name")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val GAME_VIBRATION_ENABLED = booleanPreferencesKey("game_vibration_enabled")
        val VIBRATION_PRESS_TYPE = intPreferencesKey("vibration_press_type")
        val VIBRATION_RELEASE_TYPE = intPreferencesKey("vibration_release_type")
        val VIBRATION_PRESS_VIEW_EFFECT = intPreferencesKey("vibration_press_view_effect")
        val VIBRATION_RELEASE_VIEW_EFFECT = intPreferencesKey("vibration_release_view_effect")
        val VIBRATION_PRESS_DURATION = intPreferencesKey("vibration_press_duration")
        val VIBRATION_RELEASE_DURATION = intPreferencesKey("vibration_release_duration")
        val VIBRATION_PRESS_INTENSITY = intPreferencesKey("vibration_press_intensity")
        val VIBRATION_RELEASE_INTENSITY = intPreferencesKey("vibration_release_intensity")
        val AUTO_START_ENABLED = booleanPreferencesKey("auto_start_enabled")
        val GYRO_ENABLED = booleanPreferencesKey("gyro_enabled")
        val GYRO_SENSITIVITY_X = intPreferencesKey("gyro_sensitivity_x")
        val GYRO_SENSITIVITY_Y = intPreferencesKey("gyro_sensitivity_y")
        val GYRO_SENSITIVITY_Z = intPreferencesKey("gyro_sensitivity_z")
        val GYRO_ORIENTATION = intPreferencesKey("gyro_orientation")
        val GYRO_MODE = intPreferencesKey("gyro_mode")
        val GYRO_MODE_SENSITIVITY = intPreferencesKey("gyro_mode_sensitivity")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val GYRO_ACTIVATE_MODE = intPreferencesKey("gyro_activate_mode")
        val CONTROLLER_GYRO_ENABLED = booleanPreferencesKey("controller_gyro_enabled")
        val STRONG_VIBRATION_MAPPING = intPreferencesKey("strong_vibration_mapping")
        val WEAK_VIBRATION_MAPPING = intPreferencesKey("weak_vibration_mapping")
        val CONTROLLER_GYRO_ENABLED_CONNECTED = booleanPreferencesKey("controller_gyro_enabled_connected")
        val STRONG_VIBRATION_MAPPING_CONNECTED = intPreferencesKey("strong_vibration_mapping_connected")
        val WEAK_VIBRATION_MAPPING_CONNECTED = intPreferencesKey("weak_vibration_mapping_connected")
        val VOLUME_UP_BITS = stringPreferencesKey("volume_up_bits")
        val VOLUME_DOWN_BITS = stringPreferencesKey("volume_down_bits")
        val NON_LINEAR_TRIGGER_ADAPTATION = booleanPreferencesKey("non_linear_trigger_adaptation")
        // Audio
        val LEFT_VOICE_COIL_OUTPUT = intPreferencesKey("left_voice_coil_output")
        val RIGHT_VOICE_COIL_OUTPUT = intPreferencesKey("right_voice_coil_output")
        val CONTROLLER_AUDIO_OUTPUT = intPreferencesKey("controller_audio_output")
        // Appearance
        val BG_FILL_TYPE = intPreferencesKey("bg_fill_type")
        val BG_COLOR = intPreferencesKey("bg_color")
        val BG_IMAGE_PATH = stringPreferencesKey("bg_image_path")
        val BTN_FILL_TYPE = intPreferencesKey("btn_fill_type")
        val BTN_COLOR = intPreferencesKey("btn_color")
        val BTN_IMAGE_PATH = stringPreferencesKey("btn_image_path")
        val BTN_OUTLINE_COLOR = intPreferencesKey("btn_outline_color")
        val BTN_OUTLINE_WIDTH = intPreferencesKey("btn_outline_width")
        val JOY_BASE_FILL_TYPE = intPreferencesKey("joy_base_fill_type")
        val JOY_BASE_COLOR = intPreferencesKey("joy_base_color")
        val JOY_BASE_IMAGE_PATH = stringPreferencesKey("joy_base_image_path")
        val JOY_BASE_OUTLINE_COLOR = intPreferencesKey("joy_base_outline_color")
        val JOY_BASE_OUTLINE_WIDTH = intPreferencesKey("joy_base_outline_width")
        val JOY_CAP_FILL_TYPE = intPreferencesKey("joy_cap_fill_type")
        val JOY_CAP_COLOR = intPreferencesKey("joy_cap_color")
        val JOY_CAP_IMAGE_PATH = stringPreferencesKey("joy_cap_image_path")
        val JOY_CAP_OUTLINE_COLOR = intPreferencesKey("joy_cap_outline_color")
        val JOY_CAP_OUTLINE_WIDTH = intPreferencesKey("joy_cap_outline_width")
        val JOY_TRIGGER_OUTLINE_COLOR = intPreferencesKey("joy_trigger_outline_color")
        val JOY_TRIGGER_OUTLINE_WIDTH = intPreferencesKey("joy_trigger_outline_width")
        val TP_TRIGGER_OUTLINE_COLOR = intPreferencesKey("tp_trigger_outline_color")
        val TP_TRIGGER_OUTLINE_WIDTH = intPreferencesKey("tp_trigger_outline_width")
        val TP_FILL_TYPE = intPreferencesKey("tp_fill_type")
        val TP_COLOR = intPreferencesKey("tp_color")
        val TP_IMAGE_PATH = stringPreferencesKey("tp_image_path")
        val TP_OUTLINE_COLOR = intPreferencesKey("tp_outline_color")
        val TP_OUTLINE_WIDTH = intPreferencesKey("tp_outline_width")
        val ICON_MAX_SIZE = intPreferencesKey("icon_max_size")
    }

    private val gson = Gson()
    private val listIntType = object : TypeToken<List<Int>>() {}.type

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            displayMode = DisplayMode.entries.getOrElse(
                prefs[Keys.DISPLAY_MODE] ?: 0
            ) { DisplayMode.XBOX },
            connectionMode = ConnectionMode.entries.getOrElse(
                prefs[Keys.CONNECTION_MODE] ?: 0
            ) { ConnectionMode.WIFI },
            targetPlatform = TargetPlatform.entries.getOrElse(
                prefs[Keys.TARGET_PLATFORM] ?: 0
            ) { TargetPlatform.WINDOWS },
            pollingRate = prefs[Keys.POLLING_RATE] ?: 120,
            deviceName = prefs[Keys.DEVICE_NAME] ?: "Gamepad Emu",
            currentPresetName = prefs[Keys.CURRENT_PRESET_NAME] ?: "完整控制器",
            vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
            gameVibrationEnabled = prefs[Keys.GAME_VIBRATION_ENABLED] ?: true,
            vibrationPressType = VibrationType.entries.getOrElse(
                prefs[Keys.VIBRATION_PRESS_TYPE] ?: VibrationType.VIEW.ordinal
            ) { VibrationType.VIEW },
            vibrationReleaseType = VibrationType.entries.getOrElse(
                prefs[Keys.VIBRATION_RELEASE_TYPE] ?: VibrationType.VIEW.ordinal
            ) { VibrationType.VIEW },
            vibrationPressViewEffect = HapticEffect.entries.getOrElse(
                prefs[Keys.VIBRATION_PRESS_VIEW_EFFECT] ?: HapticEffect.CONFIRM.ordinal
            ) { HapticEffect.CONFIRM },
            vibrationReleaseViewEffect = HapticEffect.entries.getOrElse(
                prefs[Keys.VIBRATION_RELEASE_VIEW_EFFECT] ?: HapticEffect.KEYBOARD_TAP.ordinal
            ) { HapticEffect.KEYBOARD_TAP },
            vibrationPressDuration = prefs[Keys.VIBRATION_PRESS_DURATION] ?: 50,
            vibrationReleaseDuration = prefs[Keys.VIBRATION_RELEASE_DURATION] ?: 20,
            vibrationPressIntensity = prefs[Keys.VIBRATION_PRESS_INTENSITY] ?: 128,
            vibrationReleaseIntensity = prefs[Keys.VIBRATION_RELEASE_INTENSITY] ?: 64,
            autoStartEnabled = prefs[Keys.AUTO_START_ENABLED] ?: false,
            gyroEnabled = prefs[Keys.GYRO_ENABLED] ?: true,
            gyroSensitivityX = prefs[Keys.GYRO_SENSITIVITY_X] ?: 100,
            gyroSensitivityY = prefs[Keys.GYRO_SENSITIVITY_Y] ?: 100,
            gyroSensitivityZ = prefs[Keys.GYRO_SENSITIVITY_Z] ?: 100,
            gyroOrientation = GyroOrientation.entries.getOrElse(
                prefs[Keys.GYRO_ORIENTATION] ?: 0
            ) { GyroOrientation.LANDSCAPE },
            gyroMode = GyroMode.entries.getOrElse(
                prefs[Keys.GYRO_MODE] ?: GyroMode.HANDHELD.ordinal
            ) { GyroMode.HANDHELD },
            gyroModeSensitivity = prefs[Keys.GYRO_MODE_SENSITIVITY] ?: 20,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            gyroActivateMode = GyroActivateMode.entries.getOrElse(
                prefs[Keys.GYRO_ACTIVATE_MODE] ?: 0
            ) { GyroActivateMode.ALWAYS },
            controllerGyroEnabled = prefs[Keys.CONTROLLER_GYRO_ENABLED] ?: false,
            strongVibrationMapping = VibrationMotor.entries.getOrElse(
                prefs[Keys.STRONG_VIBRATION_MAPPING] ?: VibrationMotor.PHONE_MOTOR.ordinal
            ) { VibrationMotor.PHONE_MOTOR },
            weakVibrationMapping = VibrationMotor.entries.getOrElse(
                prefs[Keys.WEAK_VIBRATION_MAPPING] ?: VibrationMotor.PHONE_MOTOR.ordinal
            ) { VibrationMotor.PHONE_MOTOR },
            controllerGyroEnabledConnected = prefs[Keys.CONTROLLER_GYRO_ENABLED_CONNECTED] ?: true,
            strongVibrationMappingConnected = VibrationMotor.entries.getOrElse(
                prefs[Keys.STRONG_VIBRATION_MAPPING_CONNECTED] ?: VibrationMotor.CONTROLLER_MOTOR_1.ordinal
            ) { VibrationMotor.CONTROLLER_MOTOR_1 },
            weakVibrationMappingConnected = VibrationMotor.entries.getOrElse(
                prefs[Keys.WEAK_VIBRATION_MAPPING_CONNECTED] ?: VibrationMotor.CONTROLLER_MOTOR_2.ordinal
            ) { VibrationMotor.CONTROLLER_MOTOR_2 },
            volumeUpBits = parseBitList(prefs[Keys.VOLUME_UP_BITS]),
            volumeDownBits = parseBitList(prefs[Keys.VOLUME_DOWN_BITS]),
            nonLinearTriggerAdaptation = prefs[Keys.NON_LINEAR_TRIGGER_ADAPTATION] ?: false,
            bgFillType = FillType.entries.getOrElse(prefs[Keys.BG_FILL_TYPE] ?: 0) { FillType.SOLID_COLOR },
            bgColor = prefs[Keys.BG_COLOR] ?: 0xFF000000.toInt(),
            bgImagePath = prefs[Keys.BG_IMAGE_PATH],
            btnFillType = FillType.entries.getOrElse(prefs[Keys.BTN_FILL_TYPE] ?: 0) { FillType.SOLID_COLOR },
            btnColor = prefs[Keys.BTN_COLOR] ?: 0xFF1A1A1A.toInt(),
            btnImagePath = prefs[Keys.BTN_IMAGE_PATH],
            btnOutlineColor = prefs[Keys.BTN_OUTLINE_COLOR] ?: 0xFF666666.toInt(),
            btnOutlineWidth = prefs[Keys.BTN_OUTLINE_WIDTH] ?: 4,
            joyBaseFillType = FillType.entries.getOrElse(prefs[Keys.JOY_BASE_FILL_TYPE] ?: 0) { FillType.SOLID_COLOR },
            joyBaseColor = prefs[Keys.JOY_BASE_COLOR] ?: -0xdddddd,
            joyBaseImagePath = prefs[Keys.JOY_BASE_IMAGE_PATH],
            joyBaseOutlineColor = prefs[Keys.JOY_BASE_OUTLINE_COLOR] ?: -0xaaaaab,
            joyBaseOutlineWidth = prefs[Keys.JOY_BASE_OUTLINE_WIDTH] ?: 4,
            joyCapFillType = FillType.entries.getOrElse(prefs[Keys.JOY_CAP_FILL_TYPE] ?: 0) { FillType.SOLID_COLOR },
            joyCapColor = prefs[Keys.JOY_CAP_COLOR] ?: -0xaaaaab,
            joyCapImagePath = prefs[Keys.JOY_CAP_IMAGE_PATH],
            joyCapOutlineColor = prefs[Keys.JOY_CAP_OUTLINE_COLOR] ?: -0x888889,
            joyCapOutlineWidth = prefs[Keys.JOY_CAP_OUTLINE_WIDTH] ?: 4,
            joyTriggerOutlineColor = prefs[Keys.JOY_TRIGGER_OUTLINE_COLOR] ?: -0x666667,
            joyTriggerOutlineWidth = prefs[Keys.JOY_TRIGGER_OUTLINE_WIDTH] ?: 4,
            tpTriggerOutlineColor = prefs[Keys.TP_TRIGGER_OUTLINE_COLOR] ?: -0x666667,
            tpTriggerOutlineWidth = prefs[Keys.TP_TRIGGER_OUTLINE_WIDTH] ?: 4,
            tpFillType = FillType.entries.getOrElse(prefs[Keys.TP_FILL_TYPE] ?: 0) { FillType.SOLID_COLOR },
            tpColor = prefs[Keys.TP_COLOR] ?: 0xFF121212.toInt(),
            tpImagePath = prefs[Keys.TP_IMAGE_PATH],
            tpOutlineColor = prefs[Keys.TP_OUTLINE_COLOR] ?: 0xFF666666.toInt(),
            tpOutlineWidth = prefs[Keys.TP_OUTLINE_WIDTH] ?: 4,
            iconMaxSize = prefs[Keys.ICON_MAX_SIZE] ?: 24,
            leftVoiceCoilOutput = AudioOutput.fromOrdinalSafe(
                prefs[Keys.LEFT_VOICE_COIL_OUTPUT] ?: AudioOutput.LEFT_SPEAKER.ordinal
            ),
            rightVoiceCoilOutput = AudioOutput.fromOrdinalSafe(
                prefs[Keys.RIGHT_VOICE_COIL_OUTPUT] ?: AudioOutput.RIGHT_SPEAKER.ordinal
            ),
            controllerAudioOutput = AudioOutput.fromOrdinalSafe(
                prefs[Keys.CONTROLLER_AUDIO_OUTPUT] ?: AudioOutput.ALL_SPEAKERS.ordinal
            ),
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DISPLAY_MODE] = settings.displayMode.ordinal
            prefs[Keys.CONNECTION_MODE] = settings.connectionMode.ordinal
            prefs[Keys.TARGET_PLATFORM] = settings.targetPlatform.ordinal
            prefs[Keys.POLLING_RATE] = settings.pollingRate
            prefs[Keys.DEVICE_NAME] = settings.deviceName
            prefs[Keys.CURRENT_PRESET_NAME] = settings.currentPresetName
            prefs[Keys.VIBRATION_ENABLED] = settings.vibrationEnabled
            prefs[Keys.GAME_VIBRATION_ENABLED] = settings.gameVibrationEnabled
            prefs[Keys.VIBRATION_PRESS_TYPE] = settings.vibrationPressType.ordinal
            prefs[Keys.VIBRATION_RELEASE_TYPE] = settings.vibrationReleaseType.ordinal
            prefs[Keys.VIBRATION_PRESS_VIEW_EFFECT] = settings.vibrationPressViewEffect.ordinal
            prefs[Keys.VIBRATION_RELEASE_VIEW_EFFECT] = settings.vibrationReleaseViewEffect.ordinal
            prefs[Keys.VIBRATION_PRESS_DURATION] = settings.vibrationPressDuration
            prefs[Keys.VIBRATION_RELEASE_DURATION] = settings.vibrationReleaseDuration
            prefs[Keys.VIBRATION_PRESS_INTENSITY] = settings.vibrationPressIntensity
            prefs[Keys.VIBRATION_RELEASE_INTENSITY] = settings.vibrationReleaseIntensity
            prefs[Keys.AUTO_START_ENABLED] = settings.autoStartEnabled
            prefs[Keys.GYRO_ENABLED] = settings.gyroEnabled
            prefs[Keys.GYRO_SENSITIVITY_X] = settings.gyroSensitivityX
            prefs[Keys.GYRO_SENSITIVITY_Y] = settings.gyroSensitivityY
            prefs[Keys.GYRO_SENSITIVITY_Z] = settings.gyroSensitivityZ
            prefs[Keys.GYRO_ORIENTATION] = settings.gyroOrientation.ordinal
            prefs[Keys.GYRO_MODE] = settings.gyroMode.ordinal
            prefs[Keys.GYRO_MODE_SENSITIVITY] = settings.gyroModeSensitivity
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[Keys.GYRO_ACTIVATE_MODE] = settings.gyroActivateMode.ordinal
            prefs[Keys.CONTROLLER_GYRO_ENABLED] = settings.controllerGyroEnabled
            prefs[Keys.STRONG_VIBRATION_MAPPING] = settings.strongVibrationMapping.ordinal
            prefs[Keys.WEAK_VIBRATION_MAPPING] = settings.weakVibrationMapping.ordinal
            prefs[Keys.CONTROLLER_GYRO_ENABLED_CONNECTED] = settings.controllerGyroEnabledConnected
            prefs[Keys.STRONG_VIBRATION_MAPPING_CONNECTED] = settings.strongVibrationMappingConnected.ordinal
            prefs[Keys.WEAK_VIBRATION_MAPPING_CONNECTED] = settings.weakVibrationMappingConnected.ordinal
            prefs[Keys.VOLUME_UP_BITS] = gson.toJson(settings.volumeUpBits)
            prefs[Keys.VOLUME_DOWN_BITS] = gson.toJson(settings.volumeDownBits)
            prefs[Keys.NON_LINEAR_TRIGGER_ADAPTATION] = settings.nonLinearTriggerAdaptation
            prefs[Keys.BG_FILL_TYPE] = settings.bgFillType.ordinal
            prefs[Keys.BG_COLOR] = settings.bgColor
            if (settings.bgImagePath != null) prefs[Keys.BG_IMAGE_PATH] = settings.bgImagePath else prefs.remove(Keys.BG_IMAGE_PATH)
            prefs[Keys.BTN_FILL_TYPE] = settings.btnFillType.ordinal
            prefs[Keys.BTN_COLOR] = settings.btnColor
            if (settings.btnImagePath != null) prefs[Keys.BTN_IMAGE_PATH] = settings.btnImagePath else prefs.remove(Keys.BTN_IMAGE_PATH)
            prefs[Keys.BTN_OUTLINE_COLOR] = settings.btnOutlineColor
            prefs[Keys.BTN_OUTLINE_WIDTH] = settings.btnOutlineWidth
            prefs[Keys.JOY_BASE_FILL_TYPE] = settings.joyBaseFillType.ordinal
            prefs[Keys.JOY_BASE_COLOR] = settings.joyBaseColor
            if (settings.joyBaseImagePath != null) prefs[Keys.JOY_BASE_IMAGE_PATH] = settings.joyBaseImagePath else prefs.remove(Keys.JOY_BASE_IMAGE_PATH)
            prefs[Keys.JOY_BASE_OUTLINE_COLOR] = settings.joyBaseOutlineColor
            prefs[Keys.JOY_BASE_OUTLINE_WIDTH] = settings.joyBaseOutlineWidth
            prefs[Keys.JOY_CAP_FILL_TYPE] = settings.joyCapFillType.ordinal
            prefs[Keys.JOY_CAP_COLOR] = settings.joyCapColor
            if (settings.joyCapImagePath != null) prefs[Keys.JOY_CAP_IMAGE_PATH] = settings.joyCapImagePath else prefs.remove(Keys.JOY_CAP_IMAGE_PATH)
            prefs[Keys.JOY_CAP_OUTLINE_COLOR] = settings.joyCapOutlineColor
            prefs[Keys.JOY_CAP_OUTLINE_WIDTH] = settings.joyCapOutlineWidth
            prefs[Keys.JOY_TRIGGER_OUTLINE_COLOR] = settings.joyTriggerOutlineColor
            prefs[Keys.JOY_TRIGGER_OUTLINE_WIDTH] = settings.joyTriggerOutlineWidth
            prefs[Keys.TP_TRIGGER_OUTLINE_COLOR] = settings.tpTriggerOutlineColor
            prefs[Keys.TP_TRIGGER_OUTLINE_WIDTH] = settings.tpTriggerOutlineWidth
            prefs[Keys.TP_FILL_TYPE] = settings.tpFillType.ordinal
            prefs[Keys.TP_COLOR] = settings.tpColor
            if (settings.tpImagePath != null) prefs[Keys.TP_IMAGE_PATH] = settings.tpImagePath else prefs.remove(Keys.TP_IMAGE_PATH)
            prefs[Keys.TP_OUTLINE_COLOR] = settings.tpOutlineColor
            prefs[Keys.TP_OUTLINE_WIDTH] = settings.tpOutlineWidth
            prefs[Keys.ICON_MAX_SIZE] = settings.iconMaxSize
            prefs[Keys.LEFT_VOICE_COIL_OUTPUT] = settings.leftVoiceCoilOutput.ordinal
            prefs[Keys.RIGHT_VOICE_COIL_OUTPUT] = settings.rightVoiceCoilOutput.ordinal
            prefs[Keys.CONTROLLER_AUDIO_OUTPUT] = settings.controllerAudioOutput.ordinal
        }
    }

    private fun parseBitList(json: String?): List<Int> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            gson.fromJson(json, listIntType) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}
