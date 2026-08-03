package com.zyz4.gamepademu.model

enum class ConnectionMode { WIFI, BLUETOOTH }

enum class TargetPlatform { WINDOWS, ANDROID, LINUX }

enum class DisplayMode { XBOX, PLAYSTATION, SWITCH }

enum class VibrationType { NONE, VIEW, VIBRATION_EFFECT }

enum class VibrationMotor(val displayName: String) {
    CONTROLLER_MOTOR_1("手柄马达1"),
    CONTROLLER_MOTOR_2("手柄马达2"),
    PHONE_MOTOR("手机马达"),
    NONE("无"),
}

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

enum class FillType { SOLID_COLOR, IMAGE }

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
    // Disconnected state
    val controllerGyroEnabled: Boolean = false,
    val strongVibrationMapping: VibrationMotor = VibrationMotor.PHONE_MOTOR,
    val weakVibrationMapping: VibrationMotor = VibrationMotor.PHONE_MOTOR,
    // Connected state
    val controllerGyroEnabledConnected: Boolean = true,
    val strongVibrationMappingConnected: VibrationMotor = VibrationMotor.CONTROLLER_MOTOR_1,
    val weakVibrationMappingConnected: VibrationMotor = VibrationMotor.CONTROLLER_MOTOR_2,
    val volumeUpBits: List<Int> = emptyList(),
    val volumeDownBits: List<Int> = emptyList(),
    val nonLinearTriggerAdaptation: Boolean = false,
    // ── Appearance ──
    val bgFillType: FillType = FillType.SOLID_COLOR,
    val bgColor: Int = 0xFF000000.toInt(),
    val bgImagePath: String? = null,
    val btnFillType: FillType = FillType.SOLID_COLOR,
    val btnColor: Int = 0xFF1A1A1A.toInt(),
    val btnImagePath: String? = null,
    val btnOutlineColor: Int = 0xFF666666.toInt(),
    val btnOutlineWidth: Int = 4,
    val joyBaseFillType: FillType = FillType.SOLID_COLOR,
    val joyBaseColor: Int = -0xdddddd,
    val joyBaseImagePath: String? = null,
    val joyBaseOutlineColor: Int = -0xaaaaab,
    val joyBaseOutlineWidth: Int = 4,
    val joyCapFillType: FillType = FillType.SOLID_COLOR,
    val joyCapColor: Int = -0xaaaaab,
    val joyCapImagePath: String? = null,
    val joyCapOutlineColor: Int = -0x888889,
    val joyCapOutlineWidth: Int = 4,
    val joyTriggerOutlineColor: Int = -0x666667,
    val joyTriggerOutlineWidth: Int = 4,
    val tpTriggerOutlineColor: Int = -0x666667,
    val tpTriggerOutlineWidth: Int = 4,
    val tpFillType: FillType = FillType.SOLID_COLOR,
    val tpColor: Int = 0xFF121212.toInt(),
    val tpImagePath: String? = null,
    val tpOutlineColor: Int = 0xFF666666.toInt(),
    val tpOutlineWidth: Int = 4,
    // ── Integrated D-pad pad appearance ──
    val padFillType: FillType = FillType.SOLID_COLOR,
    val padColor: Int = 0xFF1A1A1A.toInt(),
    val padImagePath: String? = null,
    val padBorderColor: Int = 0xFF666666.toInt(),
    val padBorderWidth: Int = 4,

    // Max size of text and icons in sp (same unit as the old button textSize=20f).
    // 0..99 caps content, 100 = unlimited (content fills the button). Content always
    // keeps a min(width,height) x 10% padding.
    val iconMaxSize: Int = 24,
)
