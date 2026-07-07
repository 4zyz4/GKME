package com.zyz4.gamepademu.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.ControllerMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.HapticEffect
import com.zyz4.gamepademu.model.TargetPlatform
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
        val CONTROLLER_MODE = intPreferencesKey("controller_mode")
        val CONNECTION_MODE = intPreferencesKey("connection_mode")
        val TARGET_PLATFORM = intPreferencesKey("target_platform")
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
        val GYRO_ENABLED = booleanPreferencesKey("gyro_enabled")
        val GYRO_SENSITIVITY_X = intPreferencesKey("gyro_sensitivity_x")
        val GYRO_SENSITIVITY_Y = intPreferencesKey("gyro_sensitivity_y")
        val GYRO_SENSITIVITY_Z = intPreferencesKey("gyro_sensitivity_z")
        val GYRO_ORIENTATION = intPreferencesKey("gyro_orientation")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            displayMode = DisplayMode.entries.getOrElse(
                prefs[Keys.DISPLAY_MODE] ?: 0
            ) { DisplayMode.XBOX },
            controllerMode = ControllerMode.entries.getOrElse(
                prefs[Keys.CONTROLLER_MODE] ?: 0
            ) { ControllerMode.XBOX_360 },
            connectionMode = ConnectionMode.entries.getOrElse(
                prefs[Keys.CONNECTION_MODE] ?: 0
            ) { ConnectionMode.WIFI },
            targetPlatform = TargetPlatform.entries.getOrElse(
                prefs[Keys.TARGET_PLATFORM] ?: 0
            ) { TargetPlatform.WINDOWS },
            deviceName = prefs[Keys.DEVICE_NAME] ?: "Gamepad Emu",
            currentPresetName = prefs[Keys.CURRENT_PRESET_NAME] ?: "完整布局",
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
            gyroEnabled = prefs[Keys.GYRO_ENABLED] ?: true,
            gyroSensitivityX = prefs[Keys.GYRO_SENSITIVITY_X] ?: 100,
            gyroSensitivityY = prefs[Keys.GYRO_SENSITIVITY_Y] ?: 100,
            gyroSensitivityZ = prefs[Keys.GYRO_SENSITIVITY_Z] ?: 100,
            gyroOrientation = GyroOrientation.entries.getOrElse(
                prefs[Keys.GYRO_ORIENTATION] ?: 0
            ) { GyroOrientation.LANDSCAPE },
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DISPLAY_MODE] = settings.displayMode.ordinal
            prefs[Keys.CONTROLLER_MODE] = settings.controllerMode.ordinal
            prefs[Keys.CONNECTION_MODE] = settings.connectionMode.ordinal
            prefs[Keys.TARGET_PLATFORM] = settings.targetPlatform.ordinal
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
            prefs[Keys.GYRO_ENABLED] = settings.gyroEnabled
            prefs[Keys.GYRO_SENSITIVITY_X] = settings.gyroSensitivityX
            prefs[Keys.GYRO_SENSITIVITY_Y] = settings.gyroSensitivityY
            prefs[Keys.GYRO_SENSITIVITY_Z] = settings.gyroSensitivityZ
            prefs[Keys.GYRO_ORIENTATION] = settings.gyroOrientation.ordinal
        }
    }
}
