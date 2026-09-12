package com.zyz4.gkme.model

enum class ConnectionMode { WIFI, BLUETOOTH }

enum class TargetPlatform { WINDOWS, ANDROID, LINUX, ANDROID_GAMEPAD_ONLY, UNIVERSAL_KM }

enum class DisplayMode { XBOX, PLAYSTATION, SWITCH }

enum class VibrationType { NONE, VIEW, VIBRATION_EFFECT }

/** Which physical actuator receives the game rumble. Controllers are addressed by their
 *  index in the list of currently connected gamepad devices. */
enum class VibrationDeviceType { PHONE, CONTROLLER, NONE }

data class VibrationDevice(
    val type: VibrationDeviceType = VibrationDeviceType.PHONE,
    val controllerIndex: Int = 0,
) {
    companion object {
        val PHONE = VibrationDevice(VibrationDeviceType.PHONE, 0)
        val NONE = VibrationDevice(VibrationDeviceType.NONE, 0)
        fun controller(index: Int) = VibrationDevice(VibrationDeviceType.CONTROLLER, index)
    }
}

/** Target device for the DualSense voice-coil (left/right motor) channels. Controllers are
 *  addressed by their index in the list of currently connected gamepad devices. */
enum class AudioDeviceType { NONE, PHONE_MOTOR, PHONE_SPEAKER, CONTROLLER }

data class AudioDevice(
    val type: AudioDeviceType = AudioDeviceType.PHONE_SPEAKER,
    val controllerIndex: Int = 0,
) {
    companion object {
        val NONE = AudioDevice(AudioDeviceType.NONE, 0)
        val PHONE_MOTOR = AudioDevice(AudioDeviceType.PHONE_MOTOR, 0)
        val PHONE_SPEAKER = AudioDevice(AudioDeviceType.PHONE_SPEAKER, 0)
        fun controller(index: Int) = AudioDevice(AudioDeviceType.CONTROLLER, index)
    }
}

sealed interface AudioOutput {
    val displayName: String
    val ordinal: Int
    val outputType: OutputType
    val index: Int
    val isLeft: Boolean
    
    enum class OutputType { NONE, PHONE, LEFT_SPEAKER, RIGHT_SPEAKER, ALL_SPEAKERS, CONTROLLER }
    
    data class NoneOutput(
        override val displayName: String = "无",
        override val ordinal: Int = 0,
    ) : AudioOutput {
        override val outputType: OutputType get() = OutputType.NONE
        override val index: Int get() = 0
        override val isLeft: Boolean get() = false
    }
    
    data class PhoneMotor(
        val motorIndex: Int,
        override val displayName: String,
        override val ordinal: Int,
    ) : AudioOutput {
        override val outputType: OutputType get() = OutputType.PHONE
        override val index: Int get() = motorIndex
        override val isLeft: Boolean get() = false
    }
    
    data class SpeakerOutput(
        val speakerIndex: Int,
        override val displayName: String,
        override val ordinal: Int,
        override val isLeft: Boolean,
    ) : AudioOutput {
        override val outputType: OutputType get() = OutputType.entries[speakerIndex + 2]
        override val index: Int get() = 0
    }
    
    data class ControllerMotor(
        val motorIndex: Int,
        override val displayName: String,
        override val ordinal: Int,
    ) : AudioOutput {
        override val outputType: OutputType get() = OutputType.CONTROLLER
        override val index: Int get() = motorIndex
        override val isLeft: Boolean get() = false
    }
    
    companion object {
        val entries: List<AudioOutput> = mutableListOf<AudioOutput>().apply {
            add(NoneOutput())
            add(PhoneMotor(0, "手机马达1", 1))
            add(PhoneMotor(1, "手机马达2", 2))
            add(SpeakerOutput(0, "左扬声器", 3, true))
            add(SpeakerOutput(1, "右扬声器", 4, false))
            add(SpeakerOutput(2, "全部扬声器", 5, false))
            add(ControllerMotor(0, "手柄马达1", 6))
            add(ControllerMotor(1, "手柄马达2", 7))
            add(ControllerMotor(2, "手柄马达3", 8))
            add(ControllerMotor(3, "手柄马达4", 9))
        }
        
        val NONE = NoneOutput()
        val PHONE_MOTOR_1 = PhoneMotor(0, "手机马达1", 1)
        val PHONE_MOTOR_2 = PhoneMotor(1, "手机马达2", 2)
        val LEFT_SPEAKER = SpeakerOutput(0, "左扬声器", 3, true)
        val RIGHT_SPEAKER = SpeakerOutput(1, "右扬声器", 4, false)
        val ALL_SPEAKERS = SpeakerOutput(2, "全部扬声器", 5, false)
        val CONTROLLER_MOTOR_1 = ControllerMotor(0, "手柄马达1", 6)
        val CONTROLLER_MOTOR_2 = ControllerMotor(1, "手柄马达2", 7)
        val CONTROLLER_MOTOR_3 = ControllerMotor(2, "手柄马达3", 8)
        val CONTROLLER_MOTOR_4 = ControllerMotor(3, "手柄马达4", 9)
        
        fun fromOrdinalSafe(ordinal: Int, isLeft: Boolean = false): AudioOutput {
            return entries.find { it.ordinal == ordinal } ?: NONE
        }
        
        fun controllerMotor(index: Int): AudioOutput {
            return ControllerMotor(index, "手柄马达${index + 1}", index + 6)
        }
    }
}

enum class GyroOrientation(val displayName: String) {
    LANDSCAPE("横屏"),
    PORTRAIT("竖屏"),
    PORTRAIT_INVERTED("倒置竖屏"),
}

/** Which sensor feeds the gyro. Controllers are addressed by their index in the connected list. */
enum class GyroSourceType { CONTROLLER, PHONE, NONE }

data class GyroSource(
    val type: GyroSourceType = GyroSourceType.PHONE,
    val controllerIndex: Int = 0,
) {
    companion object {
        val PHONE = GyroSource(GyroSourceType.PHONE, 0)
        val NONE = GyroSource(GyroSourceType.NONE, 0)
        fun controller(index: Int) = GyroSource(GyroSourceType.CONTROLLER, index)
    }
}

enum class GyroCoordinateSystem(val displayName: String) {
    YAW("偏航"),
    ROLL("滚转"),
    YAW_ROLL("偏航+滚转"),
    WORLD("世界空间"),
}

enum class GyroMode(val displayName: String) {
    NONE("关闭"),
    HANDHELD("手柄陀螺仪"),
    MOUSE("陀螺仪转鼠标"),
    LEFT_STICK("陀螺仪转左摇杆"),
    RIGHT_STICK("陀螺仪转右摇杆"),
}

enum class GyroActivateMode(val displayName: String) {
    ALWAYS("始终开启"),
    BUTTON("按下特定按钮开启"),
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
    val pollingRate: Int = 120,
    val wifiServerIp: String = "",
    val deviceName: String = "Gamepad Emu",
    val currentPresetName: String = "完整控制器",
    val isEditMode: Boolean = false,
    val vibrationPressType: VibrationType = VibrationType.VIEW,
    val vibrationReleaseType: VibrationType = VibrationType.VIEW,
    val vibrationPressViewEffect: HapticEffect = HapticEffect.CONFIRM,
    val vibrationReleaseViewEffect: HapticEffect = HapticEffect.KEYBOARD_TAP,
    val vibrationPressDuration: Int = 50,
    val vibrationReleaseDuration: Int = 20,
    val vibrationPressIntensity: Int = 128,
    val vibrationReleaseIntensity: Int = 64,
    val gameVibrationDevice: VibrationDevice = VibrationDevice.PHONE,
    /** Game-rumble target used while a physical controller is connected (defaults to it). */
    val gameVibrationDeviceConnected: VibrationDevice = VibrationDevice.controller(0),
    val swapPhoneMotors: Boolean = false,
    val swapControllerMotors: Boolean = false,
    val autoStartEnabled: Boolean = false,
    val gyroEnabled: Boolean = true,
    val gyroSensitivityX: Int = 100,
    val gyroSensitivityY: Int = 100,
    val gyroSensitivityZ: Int = 100,
    val gyroOrientation: GyroOrientation = GyroOrientation.LANDSCAPE,
    // ── Gyro mapping mode ──
    val gyroCoordinateSystem: GyroCoordinateSystem = GyroCoordinateSystem.YAW_ROLL,
    val gyroMode: GyroMode = GyroMode.HANDHELD,
    val gyroModeSensitivity: Int = 20,
    val gyroDeadZone: Int = 0,
    val gyroReverseDeadZone: Int = 0,
    val keepScreenOn: Boolean = false,
    // Disconnected state
    val controllerGyroEnabled: Boolean = false,
    // Connected state
    val gyroActivateMode: GyroActivateMode = GyroActivateMode.ALWAYS,
    val controllerGyroEnabledConnected: Boolean = true,
    /** Master gyro flag used while a physical controller is connected (see [gyroEnabled]). */
    val gyroEnabledConnected: Boolean = true,
    /** Index into the connected gamepads whose gyro is used when the source is a controller. */
    val gyroControllerIndex: Int = 0,
    /** Controller gyro index used while a physical controller is connected. */
    val gyroControllerIndexConnected: Int = 0,
    val volumeUpBits: List<Int> = emptyList(),
    val volumeDownBits: List<Int> = emptyList(),
    val nonLinearTriggerAdaptation: Boolean = false,
    /** Index into the list of currently connected gamepads used as the input source.
     *  -1 means the physical controller input is disabled ("不使用手柄"). */
    val inputControllerIndex: Int = 0,
    // ── Audio (DualSense Voice Coil + Speaker) ──
    val voiceCoilDevice: AudioDevice = AudioDevice.PHONE_SPEAKER,
    /** Voice-coil target used while a physical controller is connected (defaults to it). */
    val voiceCoilDeviceConnected: AudioDevice = AudioDevice.controller(0),
    val swapVoiceCoilMotors: Boolean = false,
    val controllerAudioOutput: AudioOutput = AudioOutput.ALL_SPEAKERS,
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
    val linearTriggerBoxOutlineColor: Int = 0xFF888888.toInt(),
    val linearTriggerBoxOutlineWidth: Int = 4,
    val tpFillType: FillType = FillType.SOLID_COLOR,
    val tpColor: Int = 0xFF121212.toInt(),
    val tpImagePath: String? = null,
    val tpOutlineColor: Int = 0xFF666666.toInt(),
    val tpOutlineWidth: Int = 4,
    // ── Trigger area appearance (dpadPad / customKeypad) ──
    val dpadPadFillType: FillType = FillType.SOLID_COLOR,
    val dpadPadColor: Int = 0xFF1A1A1A.toInt(),
    val dpadPadImagePath: String? = null,
    val dpadPadOutlineColor: Int = 0xFF666666.toInt(),
    val dpadPadOutlineWidth: Int = 4,
    val dpadPadTriggerOutlineColor: Int = -0x666667,
    val dpadPadTriggerOutlineWidth: Int = 4,

    // Max size of text and icons in sp (same unit as the old button textSize=20f).
    // 0..99 caps content, 100 = unlimited (content fills the button). Content always
    // keeps a min(width,height) x 10% padding.
    val iconMaxSize: Int = 24,

    /** Appearance color fields currently bound to the live controller LED color
     *  (see [com.zyz4.gkme.model.LedAppearance]). Values are the AppSettings field
     *  names, e.g. "btnColor". The touchpad outline follows the controller LED by default. */
    val ledBoundColors: Set<String> = LedAppearance.DEFAULT_BOUND_COLORS,
)

/**
 * The three device settings (game rumble, DualSense voice coil, gyro source) each keep an
 * independent set for the "physical controller connected" and "disconnected" states. These
 * helpers pick the active set; [connected] comes from the physical controller handler.
 */
fun AppSettings.gameVibrationDeviceFor(connected: Boolean): VibrationDevice =
    if (connected) gameVibrationDeviceConnected else gameVibrationDevice

fun AppSettings.voiceCoilDeviceFor(connected: Boolean): AudioDevice =
    if (connected) voiceCoilDeviceConnected else voiceCoilDevice

fun AppSettings.gyroControllerIndexFor(connected: Boolean): Int =
    if (connected) gyroControllerIndexConnected else gyroControllerIndex

/** Master gyro flag of the active set (see [gyroEnabled] / [gyroEnabledConnected]). */
fun AppSettings.gyroMasterEnabledFor(connected: Boolean): Boolean =
    if (connected) gyroEnabledConnected else gyroEnabled

/** The gyro source selected in the active (connected or disconnected) set. */
fun AppSettings.gyroSourceFor(connected: Boolean): GyroSource {
    val useController = if (connected) controllerGyroEnabledConnected else controllerGyroEnabled
    return when {
        useController -> GyroSource.controller(gyroControllerIndexFor(connected))
        gyroMasterEnabledFor(connected) -> GyroSource.PHONE
        else -> GyroSource.NONE
    }
}
