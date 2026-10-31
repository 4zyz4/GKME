package com.zyz4.gkme.model

import androidx.annotation.StringRes
import com.zyz4.gkme.R

enum class ConnectionMode { WIFI, BLUETOOTH, USB, LOCAL }

/**
 * WiFi 模式下本机承担的角色：
 * - [CONTROLLER] 作为控制端：把本机触摸/手柄输入发送给远端主机（默认，保持原有行为）。
 * - [CONTROLLED] 作为被控端：接收远端控制端的输入，并通过 Shizuku + uinput 在本地
 *   创建一个虚拟手柄。
 */
enum class ControlType(@StringRes val displayNameRes: Int) {
    CONTROLLER(R.string.i18n_controller_mode),
    CONTROLLED(R.string.i18n_controlled_mode),
}

enum class TargetPlatform { WINDOWS, ANDROID, LINUX, ANDROID_GAMEPAD_ONLY, UNIVERSAL_KM, WINDOWS_GAMEPAD_ONLY }

/** Shizuku 被控端/本机模式模拟的手柄类型。Xbox 走 uinput 后端，其余走 uhid 真实 HID。 */
enum class VirtualGamepadType(
    val displayName: String,
    /** Native 后端 id：0 = uinput，1 = uhid（与 IGamepadService.create 的 backend 对应）。 */
    val nativeBackend: Int,
    /** uhid 身份 id：1 = DualShock 4，2 = DualSense，3 = Switch Pro；uinput 时忽略。 */
    val uhidProfileId: Int,
) {
    XBOX_ONE_S("Xbox One S", 0, 0),
    DS4("DualShock 4", 1, 1),
    DUALSENSE("DualSense", 1, 2),
    SWITCH_PRO("Switch Pro", 1, 3),
}

enum class DisplayMode { XBOX, PLAYSTATION, SWITCH }

/** Which low-level stack drives the physical gamepads. */
enum class ControllerDriver(@StringRes val displayNameRes: Int) {
    SDL3(R.string.i18n_sdl3),
    AXIXI2233_USB(R.string.i18n_usb_driver_axixi),
    /** 直接使用 Android 系统 InputManager 读取手柄，VibratorManager 驱动震动。 */
    INPUT_MANAGER(R.string.i18n_input_manager),
    /** 仅处理系统蓝牙连接的 PS5 DualSense：InputManager 读取输入，隐藏 HID Host 直写震动/扳机/LED。 */
    DUALSENSE_BLUETOOTH(R.string.i18n_dualsense_bluetooth),
}

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

/** Target actuator for PC adaptive-trigger effects.
 *  [PHONE_MOTOR]/[CONTROLLER_MOTOR] render the effect as motor vibration,
 *  [CONTROLLER_TRIGGER] forwards it natively when the controller supports adaptive
 *  triggers, or falls back to trigger rumble / motors. */
enum class AdaptiveTriggerTargetType { NONE, PHONE_MOTOR, CONTROLLER_MOTOR, CONTROLLER_TRIGGER }

/** Which actuator receives PC adaptive-trigger effects. Controllers are addressed by their
 *  index in the list of currently connected gamepad devices. The same controller can appear
 *  both as a motor target and a trigger target. */
data class AdaptiveTriggerDevice(
    val type: AdaptiveTriggerTargetType = AdaptiveTriggerTargetType.NONE,
    val controllerIndex: Int = 0,
) {
    companion object {
        val NONE = AdaptiveTriggerDevice(AdaptiveTriggerTargetType.NONE, 0)
        val PHONE_MOTOR = AdaptiveTriggerDevice(AdaptiveTriggerTargetType.PHONE_MOTOR, 0)
        fun controllerMotor(index: Int) = AdaptiveTriggerDevice(AdaptiveTriggerTargetType.CONTROLLER_MOTOR, index)
        fun controllerTrigger(index: Int) = AdaptiveTriggerDevice(AdaptiveTriggerTargetType.CONTROLLER_TRIGGER, index)
    }
}

/** Target device for the DualSense voice-coil (left/right motor) channels. Controllers are
 *  addressed by their index in the list of currently connected gamepad devices.
 *
 *  [persistId] is a stable on-disk id: enum reordering or removing an entry must not
 *  reinterpret previously saved values. 旧版本按 ordinal 存储，二者当前一致，可平滑迁移。 */
enum class AudioDeviceType(val persistId: Int) {
    NONE(0),
    PHONE_MOTOR(1),
    PHONE_SPEAKER(2),
    CONTROLLER(3),
    SOUND_DEVICE(4);

    companion object {
        /** 按持久化 id 还原；未知/已删除的 id 回退到 [default]。 */
        fun fromPersistId(id: Int, default: AudioDeviceType = PHONE_MOTOR): AudioDeviceType =
            entries.firstOrNull { it.persistId == id } ?: default
    }
}

/**
 * A selectable audio target. [PHONE_SPEAKER] is retained only so previously saved
 * ordinals stay valid; the phone speaker is now an enumerated [SOUND_DEVICE] and the
 * type is never offered in the UI.
 */
data class AudioDevice(
    val type: AudioDeviceType = AudioDeviceType.PHONE_MOTOR,
    val controllerIndex: Int = 0,
    /** SDL playback device id, only meaningful for [AudioDeviceType.SOUND_DEVICE]. */
    val deviceId: Int = AUTO_SOUND_DEVICE_ID,
    /** Cached SDL device name for display/persistence, only for [AudioDeviceType.SOUND_DEVICE]. */
    val deviceName: String = "",
) {
    companion object {
        /** Sentinel device id meaning "first enumerated SDL playback device". */
        const val AUTO_SOUND_DEVICE_ID = 0

        val NONE = AudioDevice(AudioDeviceType.NONE, 0)
        val PHONE_MOTOR = AudioDevice(AudioDeviceType.PHONE_MOTOR, 0)
        @Deprecated("Phone speaker is enumerated as a sound device now")
        val PHONE_SPEAKER = AudioDevice(AudioDeviceType.PHONE_SPEAKER, 0)
        /** "First available / default" sound device, resolved when playback starts. */
        val AUTO_SOUND_DEVICE = AudioDevice(AudioDeviceType.SOUND_DEVICE, 0, AUTO_SOUND_DEVICE_ID, "")
        fun controller(index: Int) = AudioDevice(AudioDeviceType.CONTROLLER, index)
        fun soundDevice(deviceId: Int, deviceName: String) =
            AudioDevice(AudioDeviceType.SOUND_DEVICE, 0, deviceId, deviceName)
    }
}

enum class GyroOrientation(@StringRes val displayNameRes: Int) {
    LANDSCAPE(R.string.i18n_landscape),
    PORTRAIT(R.string.i18n_portrait),
    PORTRAIT_INVERTED(R.string.i18n_inverted_portrait),
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

enum class GyroBaseDirection(@StringRes val displayNameRes: Int) {
    VERTICAL(R.string.i18n_vertical),
    HORIZONTAL(R.string.i18n_flat),
}

enum class GyroCoordinateSystem(@StringRes val displayNameRes: Int) {
    YAW(R.string.i18n_yaw),
    ROLL(R.string.i18n_roll),
    YAW_ROLL(R.string.i18n_yaw_roll),
    WORLD(R.string.i18n_world_space),
}

enum class GyroMode(@StringRes val displayNameRes: Int) {
    NONE(R.string.i18n_close),
    HANDHELD(R.string.i18n_gamepad_gyro),
    MOUSE(R.string.i18n_gyro_to_mouse),
    LEFT_STICK(R.string.i18n_gyro_to_left_stick_view),
    RIGHT_STICK(R.string.i18n_gyro_to_right_stick_view),
    ACCELEROMETER_LEFT_STICK(R.string.i18n_gyro_to_left_stick_direction),
    ACCELEROMETER_RIGHT_STICK(R.string.i18n_gyro_to_right_stick_direction),
}

enum class GyroActivateMode(@StringRes val displayNameRes: Int) {
    ALWAYS(R.string.i18n_always_on),
    BUTTON(R.string.i18n_enable_while_a_specific_button_is_held),
}

/**
 * 按键震动可选效果。
 *
 * [prebakedId] 是启用高清震动（RichTap）后用于替换系统 `HapticFeedbackConstants` 的
 * RichTap PrebakedEffect ID（10001-10050）；HD 不可用时仍回退系统效果，该字段不生效。
 */
enum class HapticEffect(
    @StringRes val displayNameRes: Int,
    /** 高清震动下的 RichTap PrebakedEffect ID（对应 SDK `PrebakedEffectId`）。 */
    val prebakedId: Int,
) {
    KEYBOARD_TAP(R.string.i18n_tap, 10012),
    KEYBOARD_PRESS(R.string.i18n_key_press, 10011),
    KEYBOARD_RELEASE(R.string.i18n_key_release, 10016),
    CONFIRM(R.string.i18n_confirm, 10022),
    REJECT(R.string.i18n_reject, 10023),
    CLOCK_TICK(R.string.i18n_tick, 10013),
    CONTEXT_CLICK(R.string.i18n_context, 10015),
    LONG_PRESS(R.string.i18n_long_press, 10010),
    GESTURE_START(R.string.i18n_gesture_start, 10021),
    GESTURE_END(R.string.i18n_gesture_end, 10020),
    VIRTUAL_KEY(R.string.i18n_virtual_key, 10011),
    VIRTUAL_KEY_RELEASE(R.string.i18n_virtual_key_release, 10017),
}

enum class FillType { SOLID_COLOR, IMAGE }

/**
 * 设置按钮轮盘菜单的滑动方向。
 *
 * [angleDeg] 为屏幕坐标系中的角度：0 = 向右，90 = 向上（逆时针为正），用于把手指相对
 * 设置按钮的位移换算成方向；[diagonal] 表示该方向只在「8 方向」模式下可用。
 */
enum class SettingsButtonDirection(
    val angleDeg: Float,
    @StringRes val labelRes: Int,
    val diagonal: Boolean = false,
) {
    UP(90f, R.string.i18n_up),
    DOWN(270f, R.string.i18n_down),
    LEFT(180f, R.string.i18n_left_d2aff1),
    RIGHT(0f, R.string.i18n_right),
    UP_LEFT(135f, R.string.i18n_top_left, diagonal = true),
    UP_RIGHT(45f, R.string.i18n_top_right, diagonal = true),
    DOWN_LEFT(225f, R.string.i18n_bottom_left, diagonal = true),
    DOWN_RIGHT(315f, R.string.i18n_bottom_right, diagonal = true),
}

/**
 * 设置按钮轮盘菜单可绑定的操作。
 *
 * [displayNameRes] 用于设置页下拉框，[wheelLabelRes] 是轮盘上的短标签。
 */
enum class SettingsButtonAction(
    @StringRes val displayNameRes: Int,
    @StringRes val wheelLabelRes: Int,
) {
    NONE(R.string.i18n_none, R.string.i18n_none),
    PREVIOUS_LAYOUT(R.string.i18n_action_previous_layout, R.string.i18n_wheel_action_previous_layout),
    NEXT_LAYOUT(R.string.i18n_action_next_layout, R.string.i18n_wheel_action_next_layout),
    OPEN_LAYOUT_PICKER(R.string.i18n_action_open_layout_picker, R.string.i18n_wheel_action_open_layout_picker),
    OPEN_SETTINGS(R.string.i18n_action_open_settings, R.string.i18n_wheel_action_open_settings),
    TOGGLE_SERVICE(R.string.i18n_action_toggle_service, R.string.i18n_wheel_action_toggle_service),
    EDIT_LAYOUT(R.string.i18n_action_edit_layout, R.string.i18n_wheel_action_edit_layout),
}

data class AppSettings(
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    /** WiFi 模式下的角色（控制端 / 被控端）。 */
    val controlType: ControlType = ControlType.CONTROLLER,
    val targetPlatform: TargetPlatform = TargetPlatform.WINDOWS,
    /** Shizuku 模拟的手柄类型（仅影响本机模式与被控端模式）。 */
    val virtualGamepadType: VirtualGamepadType = VirtualGamepadType.XBOX_ONE_S,
    val displayMode: DisplayMode = DisplayMode.XBOX,
    val pollingRate: Int = 120,
    val wifiServerIp: String = "",
    val deviceName: String = "GKME",
    val currentPresetName: String = "完整控制器",
    val isEditMode: Boolean = false,
    val vibrationPressType: VibrationType = VibrationType.VIEW,
    val vibrationReleaseType: VibrationType = VibrationType.VIEW,
    val vibrationPressViewEffect: HapticEffect = HapticEffect.KEYBOARD_TAP,
    val vibrationReleaseViewEffect: HapticEffect = HapticEffect.CLOCK_TICK,
    val vibrationPressDuration: Int = 50,
    val vibrationReleaseDuration: Int = 20,
    val vibrationPressIntensity: Int = 128,
    val vibrationReleaseIntensity: Int = 64,
    /** 自定义按钮震动的频率（RichTap HE Frequency 0-100，56 ≈ 170Hz 谐振点）。 */
    val vibrationPressFrequency: Int = 56,
    val vibrationReleaseFrequency: Int = 56,
    val gameVibrationDevice: VibrationDevice = VibrationDevice.PHONE,
    /** Game-rumble target used while a physical controller is connected (defaults to it). */
    val gameVibrationDeviceConnected: VibrationDevice = VibrationDevice.controller(0),
    val swapPhoneMotors: Boolean = false,
    val swapControllerMotors: Boolean = false,
    /** 手机马达是否使用 RichTap 高清震动（通过 Shizuku 用户服务调用隐藏 API）。 */
    val hdVibrationEnabled: Boolean = true,
    /** Actuator that receives PC adaptive-trigger effects while no physical controller is connected. */
    val adaptiveTriggerDevice: AdaptiveTriggerDevice = AdaptiveTriggerDevice.PHONE_MOTOR,
    /** Adaptive-trigger target used while a physical controller is connected. */
    val adaptiveTriggerDeviceConnected: AdaptiveTriggerDevice = AdaptiveTriggerDevice.controllerTrigger(0),
    /** Swaps the left/right output channels of the adaptive-trigger effect. */
    val swapAdaptiveTriggers: Boolean = false,
    val autoStartEnabled: Boolean = false,
    val gyroEnabled: Boolean = true,
    val gyroSensitivityX: Int = 100,
    val gyroSensitivityY: Int = 100,
    val gyroSensitivityZ: Int = 100,
    val gyroOrientation: GyroOrientation = GyroOrientation.LANDSCAPE,
    // ── Gyro mapping mode ──
    val gyroBaseDirection: GyroBaseDirection = GyroBaseDirection.VERTICAL,
    val gyroCoordinateSystem: GyroCoordinateSystem = GyroCoordinateSystem.YAW_ROLL,
    val gyroMode: GyroMode = GyroMode.HANDHELD,
    val gyroModeSensitivity: Int = 20,
    val gyroDeadZone: Int = 0,
    val gyroReverseDeadZone: Int = 0,
    /** Shared sensitivity curve (flat [x0,y0,x1,y1,...]) for the gyro/accel -> stick modes. */
    val gyroStickCurve: List<Float>? = null,
    val keepScreenOn: Boolean = false,
    /** Opacity (0-100) applied to all floating-mode buttons (multiplied with each button's
     *  own idle/active opacity). */
    val floatingOpacity: Int = 50,
    /** 被控端是否启用「悬浮窗保活」（挂一个透明 1×1 悬浮窗降低后台被杀概率）。 */
    val floatingKeepAlive: Boolean = false,
    /** 设置按钮轮盘菜单的方向模式：true = 4 方向，false = 8 方向。 */
    val settingsButtonFourWay: Boolean = true,
    /** 设置按钮轮盘菜单：滑动方向 -> 操作；为空表示不启用轮盘（设置按钮保持普通点击）。 */
    val settingsButtonActions: Map<SettingsButtonDirection, SettingsButtonAction> = emptyMap(),
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
    /** Low-level driver used for physical gamepads (SDL3 or the Axixi2233 USB driver). */
    val controllerDriver: ControllerDriver = ControllerDriver.SDL3,
    /** Index into the list of currently connected gamepads used as the input source.
     *  -1 means the physical controller input is disabled ("不使用手柄"). */
    val inputControllerIndex: Int = 0,
    // ── Audio (DualSense Voice Coil + Speaker) ──
    val voiceCoilDevice: AudioDevice = AudioDevice.PHONE_MOTOR,
    /** Voice-coil target used while a physical controller is connected (defaults to it). */
    val voiceCoilDeviceConnected: AudioDevice = AudioDevice.controller(0),
    val swapVoiceCoilMotors: Boolean = false,
    /** Controller-audio target; defaults to the first enumerated sound device. */
    val controllerAudioDevice: AudioDevice = AudioDevice.AUTO_SOUND_DEVICE,
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

    // ── Icon colors (per control type) ──
    /** 按钮上文字/图标的颜色。 */
    val btnIconColor: Int = 0xFFCCCCCC.toInt(),
    /** 摇杆帽上 L/R 标签的颜色。 */
    val joyIconColor: Int = 0xFFCCCCCC.toInt(),
    /** 触摸板（含鼠标板）状态文本的颜色。 */
    val tpIconColor: Int = 0xFF666666.toInt(),
    /** 一体十字键方向箭头 / 自定义按键盘文本的颜色。 */
    val dpadPadIconColor: Int = 0xFFCCCCCC.toInt(),

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

/** The adaptive-trigger target of the active (connected vs disconnected) set. */
fun AppSettings.adaptiveTriggerDeviceFor(connected: Boolean): AdaptiveTriggerDevice =
    if (connected) adaptiveTriggerDeviceConnected else adaptiveTriggerDevice

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

/** Native 后端 id：0 = uinput，1 = uhid（与 IGamepadService.create 的 backend 对应）。 */
fun AppSettings.virtualGamepadNativeBackend(): Int = virtualGamepadType.nativeBackend

/** uhid 身份 id（与 C 端常量对应）。 */
fun AppSettings.virtualGamepadUhidProfile(): Int = virtualGamepadType.uhidProfileId
