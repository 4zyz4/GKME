package com.zyz4.gamepademu.model

enum class ConnectionMode { WIFI, BLUETOOTH }

enum class TargetPlatform { WINDOWS, ANDROID }

enum class DisplayMode { XBOX, PLAYSTATION, SWITCH }

enum class VibrationType { NONE, VIEW, VIBRATION_EFFECT }

enum class GyroOrientation(val displayName: String) {
    LANDSCAPE("横屏"),
    PORTRAIT("竖屏"),
    PORTRAIT_INVERTED("倒置竖屏"),
}

enum class HapticEffect(val displayName: String) {
    KEYBOARD_TAP("轻触"),
    KEYBOARD_PRESS("按键按下"),
    KEYBOARD_RELEASE("按键抬起"),
    CONFIRM("确认"),
    REJECT("拒绝"),
    CLOCK_TICK("滴答"),
    CONTEXT_CLICK("上下文"),
    LONG_PRESS("长按"),
    GESTURE_START("手势开始"),
    GESTURE_END("手势结束"),
    VIRTUAL_KEY("虚拟键"),
    VIRTUAL_KEY_RELEASE("虚拟键释放"),
}

data class AppSettings(
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    val targetPlatform: TargetPlatform = TargetPlatform.WINDOWS,
    val displayMode: DisplayMode = DisplayMode.XBOX,
    val wifiServerIp: String = "",
    val deviceName: String = "Gamepad Emu",
    val currentPresetName: String = "完整布局",
    val isEditMode: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val gameVibrationEnabled: Boolean = true,
    val vibrationPressType: VibrationType = VibrationType.VIEW,
    val vibrationReleaseType: VibrationType = VibrationType.VIEW,
    val vibrationPressViewEffect: HapticEffect = HapticEffect.CONFIRM,
    val vibrationReleaseViewEffect: HapticEffect = HapticEffect.KEYBOARD_TAP,
    val vibrationPressDuration: Int = 50,
    val vibrationReleaseDuration: Int = 20,
    val vibrationPressIntensity: Int = 128,
    val vibrationReleaseIntensity: Int = 64,
    val autoStartEnabled: Boolean = false,
    val gyroEnabled: Boolean = true,
    val gyroSensitivityX: Int = 100,
    val gyroSensitivityY: Int = 100,
    val gyroSensitivityZ: Int = 100,
    val gyroOrientation: GyroOrientation = GyroOrientation.LANDSCAPE,
    val keepScreenOn: Boolean = false,
    val controllerVibrationEnabled: Boolean = true,
    val controllerGyroEnabled: Boolean = true,
    val volumeUpBits: List<Int> = emptyList(),
    val volumeDownBits: List<Int> = emptyList(),
)
