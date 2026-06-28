package com.zyz4.gamepademu.model

enum class ConnectionMode { WIFI, BLUETOOTH }

enum class TargetPlatform { WINDOWS, ANDROID }

enum class DisplayMode { XBOX, PLAYSTATION, SWITCH }

enum class ControllerMode { XBOX_360, DS4 }

data class AppSettings(
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    val targetPlatform: TargetPlatform = TargetPlatform.WINDOWS,
    val displayMode: DisplayMode = DisplayMode.XBOX,
    val controllerMode: ControllerMode = ControllerMode.XBOX_360,
    val wifiServerIp: String = "",
    val deviceName: String = "Gamepad Emu",
    val currentPresetName: String = "Default",
    val isEditMode: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val gameVibrationEnabled: Boolean = true,
)
