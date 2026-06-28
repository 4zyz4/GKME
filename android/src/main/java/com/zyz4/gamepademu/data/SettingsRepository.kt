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
import com.zyz4.gamepademu.model.TargetPlatform
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
            currentPresetName = prefs[Keys.CURRENT_PRESET_NAME] ?: "Default",
            vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
            gameVibrationEnabled = prefs[Keys.GAME_VIBRATION_ENABLED] ?: true,
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
        }
    }
}
