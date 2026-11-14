package com.zyz4.gkme.model

import androidx.annotation.StringRes
import com.zyz4.gkme.R

/** Which kind of physical input a [PhysicalInput] describes. */
enum class PhysicalInputKind { BUTTON, JOYSTICK, TOUCHPAD }

/**
 * A remappable physical-controller input.
 *
 * [bitMask] is the bit in [com.zyz4.gkme.input.PhysicalControllerState.buttons] used to
 * detect a pressed [PhysicalInputKind.BUTTON]; it is 0 for joysticks and the touchpad.
 * [defaultOutputs] is what the input emits before the user changes anything: the button
 * itself for most buttons, empty for the back paddles (which have no natural output) and
 * for joysticks/touchpads (gyro-only).
 */
data class PhysicalInput(
    val key: String,
    val label: String,
    val kind: PhysicalInputKind,
    val defaultOutputs: List<Int> = emptyList(),
    val bitMask: Int = 0,
    @StringRes val labelRes: Int = 0,
)

/**
 * The user-configurable mapping of one physical input.
 *
 * An absent key in [LayoutPreset.physicalInputMappings] means "use the default": the input's
 * own [PhysicalInput.defaultOutputs] and gyro activation off. A stored mapping always wins,
 * so an empty [outputs] explicitly disables the input.
 */
data class PhysicalInputMapping(
    val outputs: List<Int> = emptyList(),
    val gyroActivate: Boolean = false,
    /** Timing used when [outputs] contains a mapped chord. */
    val comboTiming: ComboTiming = ComboTiming.SIMULTANEOUS,
    val comboIntervalMs: Int = ComboTiming.DEFAULT_INTERVAL_MS,
)

/**
 * 实体摇杆的映射模式。
 *
 * `null`（不写入预设）表示保持原生行为：左摇杆输出左摇杆、右摇杆输出右摇杆。
 * 一旦选择某个模式，该摇杆的原始模拟量就不再隐式写入自己的轴，而是由模式决定去向。
 */
enum class PhysicalStickMode(@StringRes val labelRes: Int) {
    /** 4 向按钮：按主导轴判定上/下/左/右，每个方向单独配置输出。 */
    FOUR_WAY(R.string.i18n_4_way_buttons),

    /** 8 向按钮：两根轴各自过阈值时判定斜向，每个方向单独配置输出。 */
    EIGHT_WAY(R.string.i18n_8_way_buttons),

    /** 无论插在哪一侧，都输出到左摇杆轴。 */
    LEFT_STICK(R.string.i18n_left_stick),

    /** 无论插在哪一侧，都输出到右摇杆轴。 */
    RIGHT_STICK(R.string.i18n_right_stick),

    /** 作为鼠标：按时间积分产生 `mouseDx/mouseDy`。 */
    MOUSE(R.string.i18n_mouse);

    /** True for [FOUR_WAY] / [EIGHT_WAY], which emit per-direction outputs instead of an axis. */
    val isDirectional: Boolean get() = this == FOUR_WAY || this == EIGHT_WAY
}

/**
 * 摇杆 4 / 8 向模式下的一个方向。
 *
 * [ordinal] 同时是 [PhysicalStickSettings.directions] 中的存储索引，因此新增方向只能
 * 追加在末尾，不能调整顺序。
 */
enum class PhysicalStickDirection(@StringRes val labelRes: Int) {
    UP(R.string.i18n_up_3f99c7),
    DOWN(R.string.i18n_down_c761f5),
    LEFT(R.string.i18n_left_3e0a82),
    RIGHT(R.string.i18n_right_b45922),
    UP_LEFT(R.string.i18n_top_left_07092c),
    UP_RIGHT(R.string.i18n_top_right_a52f17),
    DOWN_LEFT(R.string.i18n_bottom_left_a289e9),
    DOWN_RIGHT(R.string.i18n_bottom_right_553067);

    companion object {
        /** 某个模式可编辑的方向；非方向模式返回空列表。 */
        fun forMode(mode: PhysicalStickMode?): List<PhysicalStickDirection> = when (mode) {
            PhysicalStickMode.FOUR_WAY -> listOf(UP, DOWN, LEFT, RIGHT)
            PhysicalStickMode.EIGHT_WAY -> entries
            else -> emptyList()
        }
    }
}

/**
 * 一个方向的输出配置，语义与 [PhysicalInputMapping] 一致：非负值是手柄/鼠标位，
 * 负值是键盘 scan code（`-code`）；[comboTiming] 控制同一方向内多个输出是同时按下
 * 还是分帧按下。
 */
data class PhysicalStickDirectionSettings(
    val outputs: List<Int> = emptyList(),
    val comboTiming: ComboTiming = ComboTiming.SIMULTANEOUS,
    val comboIntervalMs: Int = ComboTiming.DEFAULT_INTERVAL_MS,
) {
    /** True when the direction is unbound and uses the neutral timing. */
    fun isDefault(): Boolean = outputs.isEmpty() &&
        comboTiming == ComboTiming.SIMULTANEOUS &&
        comboIntervalMs == ComboTiming.DEFAULT_INTERVAL_MS
}

/**
 * Per-layout analog shaping applied to a physical-controller joystick before it is merged with
 * the on-screen stick: a radial dead zone, an anti (reverse) dead zone and an optional
 * sensitivity curve. The same pipeline is used by the on-screen joystick and the gyro/accel
 * stick modes.
 *
 * [mode] / [directions] 是摇杆映射：塑形之后仍可把摇杆改作 4/8 向按钮、另一侧的摇杆或鼠标。
 */
data class PhysicalStickSettings(
    val deadZone: Int = 0,
    val reverseDeadZone: Int = 0,
    /** Flat `[x0, y0, x1, y1, ...]` curve points in `[0, 1]`; null/empty = identity. */
    val curve: List<Float>? = null,
    /** 摇杆映射模式；null = 原生（保持原有输出轴）。 */
    val mode: PhysicalStickMode? = null,
    /**
     * 映射为鼠标时的灵敏度倍率（%）；100 = 1.0 倍。
     *
     * 与屏幕鼠标板的 `ButtonPosition.mouseSensitivity` 语义一致，只影响鼠标模式。
     */
    val mouseSensitivity: Int = DEFAULT_MOUSE_SENSITIVITY,
    /**
     * 方向输出，索引与 [PhysicalStickDirection.ordinal] 对应；缺失的索引按
     * [PhysicalStickDirectionSettings] 默认值处理，因此旧预设无需迁移。
     */
    val directions: List<PhysicalStickDirectionSettings>? = null,
) {
    /** True when every field is at its neutral (no-op) value. */
    fun isDefault(): Boolean = deadZone <= 0 && reverseDeadZone <= 0 && curve.isNullOrEmpty() &&
        mode == null && mouseSensitivity == DEFAULT_MOUSE_SENSITIVITY &&
        directions.isNullOrEmpty()

    /** 读取某个方向的输出；未配置时返回默认（未绑定）。 */
    fun directionAt(direction: PhysicalStickDirection): PhysicalStickDirectionSettings =
        directions?.getOrNull(direction.ordinal) ?: PhysicalStickDirectionSettings()

    /** [mouseSensitivity] 换算成倍率，并限制在 UI 允许的范围内。 */
    val mouseSensitivityScale: Float
        get() = mouseSensitivity.coerceIn(MIN_MOUSE_SENSITIVITY, MAX_MOUSE_SENSITIVITY) / 100f

    /** 写入某个方向的输出，同时裁掉末尾的默认项以避免 JSON 冗余。 */
    fun withDirection(
        direction: PhysicalStickDirection,
        value: PhysicalStickDirectionSettings,
    ): PhysicalStickSettings {
        val list = MutableList(PhysicalStickDirection.entries.size) { index ->
            directions?.getOrNull(index) ?: PhysicalStickDirectionSettings()
        }
        list[direction.ordinal] = value
        while (list.isNotEmpty() && list.last().isDefault()) list.removeAt(list.lastIndex)
        return copy(directions = list.ifEmpty { null })
    }

    companion object {
        /** 鼠标灵敏度默认值（%）。 */
        const val DEFAULT_MOUSE_SENSITIVITY = 100
        const val MIN_MOUSE_SENSITIVITY = 10
        const val MAX_MOUSE_SENSITIVITY = 300
    }
}

/**
 * Catalogue of every physical controller input the settings page can configure.
 *
 * The paddle bits live above the [GamepadState] output bits because paddles never map to a
 * fixed output; they only exist as physical inputs that the user can re-assign.
 */
object PhysicalInputs {

    // Physical-only back (paddle) button bits; must match sdl_bridge.cpp.
    const val PADDLE_R1 = 0x400000   // bit 22  Xbox Elite P1 / DualSense Edge RB
    const val PADDLE_L1 = 0x800000   // bit 23  Xbox Elite P3 / DualSense Edge LB
    const val PADDLE_R2 = 0x1000000  // bit 24  Xbox Elite P2 / DualSense Edge Fn(right)
    const val PADDLE_L2 = 0x2000000  // bit 25  Xbox Elite P4 / DualSense Edge Fn(left)

    const val KEY_A = "A"
    const val KEY_B = "B"
    const val KEY_X = "X"
    const val KEY_Y = "Y"
    const val KEY_LB = "LB"
    const val KEY_RB = "RB"
    const val KEY_LT = "LT"
    const val KEY_RT = "RT"
    const val KEY_SELECT = "SELECT"
    const val KEY_START = "START"
    const val KEY_L3 = "L3"
    const val KEY_R3 = "R3"
    const val KEY_DPAD_UP = "DPAD_UP"
    const val KEY_DPAD_DOWN = "DPAD_DOWN"
    const val KEY_DPAD_LEFT = "DPAD_LEFT"
    const val KEY_DPAD_RIGHT = "DPAD_RIGHT"
    const val KEY_HOME = "HOME"
    const val KEY_SHARE = "SHARE"
    /** 旧版按键 id；读取旧预设时会被归一化到 [KEY_SHARE]。 */
    const val KEY_TOUCHPAD_CLICK_LEGACY = "TOUCHPAD_CLICK"
    const val KEY_MIC_MUTE = "MIC_MUTE"
    const val KEY_PADDLE_R1 = "PADDLE_R1"
    const val KEY_PADDLE_R2 = "PADDLE_R2"
    const val KEY_PADDLE_L1 = "PADDLE_L1"
    const val KEY_PADDLE_L2 = "PADDLE_L2"
    const val KEY_LEFT_JOYSTICK = "LEFT_JOYSTICK"
    const val KEY_RIGHT_JOYSTICK = "RIGHT_JOYSTICK"
    const val KEY_TOUCHPAD = "TOUCHPAD"

    /** Standard buttons every gamepad is assumed to expose (used when capability is unknown). */
    val STANDARD_BUTTON_MASK: Int =
        GamepadState.A or GamepadState.B or GamepadState.X or GamepadState.Y or
            GamepadState.LB or GamepadState.RB or GamepadState.LT or GamepadState.RT or
            GamepadState.SELECT or GamepadState.START or GamepadState.L3 or GamepadState.R3 or
            GamepadState.DPAD_BIT_UP or GamepadState.DPAD_BIT_DOWN or
            GamepadState.DPAD_BIT_LEFT or GamepadState.DPAD_BIT_RIGHT or
            GamepadState.HOME or GamepadState.TOUCHPAD_CLICK or GamepadState.MIC_MUTE

    val BUTTONS: List<PhysicalInput> = listOf(
        PhysicalInput(KEY_A, "A", PhysicalInputKind.BUTTON, listOf(GamepadState.A), GamepadState.A),
        PhysicalInput(KEY_B, "B", PhysicalInputKind.BUTTON, listOf(GamepadState.B), GamepadState.B),
        PhysicalInput(KEY_X, "X", PhysicalInputKind.BUTTON, listOf(GamepadState.X), GamepadState.X),
        PhysicalInput(KEY_Y, "Y", PhysicalInputKind.BUTTON, listOf(GamepadState.Y), GamepadState.Y),
        PhysicalInput(KEY_LB, "LB", PhysicalInputKind.BUTTON, listOf(GamepadState.LB), GamepadState.LB),
        PhysicalInput(KEY_RB, "RB", PhysicalInputKind.BUTTON, listOf(GamepadState.RB), GamepadState.RB),
        PhysicalInput(KEY_LT, "LT", PhysicalInputKind.BUTTON, listOf(GamepadState.LT), GamepadState.LT),
        PhysicalInput(KEY_RT, "RT", PhysicalInputKind.BUTTON, listOf(GamepadState.RT), GamepadState.RT),
        PhysicalInput(KEY_SELECT, "", PhysicalInputKind.BUTTON, listOf(GamepadState.SELECT), GamepadState.SELECT, labelRes = R.string.i18n_select),
        PhysicalInput(KEY_START, "", PhysicalInputKind.BUTTON, listOf(GamepadState.START), GamepadState.START, labelRes = R.string.i18n_menu),
        PhysicalInput(KEY_L3, "", PhysicalInputKind.BUTTON, listOf(GamepadState.L3), GamepadState.L3, labelRes = R.string.i18n_left_stick_click),
        PhysicalInput(KEY_R3, "", PhysicalInputKind.BUTTON, listOf(GamepadState.R3), GamepadState.R3, labelRes = R.string.i18n_right_stick_click),
        PhysicalInput(KEY_DPAD_UP, "", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_UP), GamepadState.DPAD_BIT_UP, labelRes = R.string.i18n_d_pad_up),
        PhysicalInput(KEY_DPAD_DOWN, "", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_DOWN), GamepadState.DPAD_BIT_DOWN, labelRes = R.string.i18n_d_pad_down),
        PhysicalInput(KEY_DPAD_LEFT, "", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_LEFT), GamepadState.DPAD_BIT_LEFT, labelRes = R.string.i18n_d_pad_left),
        PhysicalInput(KEY_DPAD_RIGHT, "", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_RIGHT), GamepadState.DPAD_BIT_RIGHT, labelRes = R.string.i18n_d_pad_right),
        PhysicalInput(KEY_HOME, "", PhysicalInputKind.BUTTON, listOf(GamepadState.HOME), GamepadState.HOME, labelRes = R.string.i18n_home),
        PhysicalInput(KEY_SHARE, "", PhysicalInputKind.BUTTON, listOf(GamepadState.TOUCHPAD_CLICK), GamepadState.TOUCHPAD_CLICK, labelRes = R.string.i18n_share),
        PhysicalInput(KEY_MIC_MUTE, "", PhysicalInputKind.BUTTON, listOf(GamepadState.MIC_MUTE), GamepadState.MIC_MUTE, labelRes = R.string.i18n_mic_mute),
        PhysicalInput(KEY_PADDLE_R1, "", PhysicalInputKind.BUTTON, emptyList(), PADDLE_R1, labelRes = R.string.i18n_back_button_top_right_p1),
        PhysicalInput(KEY_PADDLE_L1, "", PhysicalInputKind.BUTTON, emptyList(), PADDLE_L1, labelRes = R.string.i18n_back_button_top_left_p3),
        PhysicalInput(KEY_PADDLE_R2, "", PhysicalInputKind.BUTTON, emptyList(), PADDLE_R2, labelRes = R.string.i18n_back_button_bottom_right_p2),
        PhysicalInput(KEY_PADDLE_L2, "", PhysicalInputKind.BUTTON, emptyList(), PADDLE_L2, labelRes = R.string.i18n_back_button_bottom_left_p4),
    )

    /** Inputs that only gate the gyro (no key remapping). */
    val GYRO_ONLY: List<PhysicalInput> = listOf(
        PhysicalInput(KEY_LEFT_JOYSTICK, "", PhysicalInputKind.JOYSTICK, labelRes = R.string.i18n_left_stick),
        PhysicalInput(KEY_RIGHT_JOYSTICK, "", PhysicalInputKind.JOYSTICK, labelRes = R.string.i18n_right_stick),
        PhysicalInput(KEY_TOUCHPAD, "", PhysicalInputKind.TOUCHPAD, labelRes = R.string.i18n_touchpad),
    )

    /** Every configurable input, buttons first. */
    val ALL: List<PhysicalInput> = BUTTONS + GYRO_ONLY

    private val byKey: Map<String, PhysicalInput> = ALL.associateBy { it.key }

    /** 将旧版按键 id 归一化为当前 id，用于兼容旧预设。 */
    fun normalizeKey(key: String): String =
        if (key == KEY_TOUCHPAD_CLICK_LEGACY) KEY_SHARE else key

    fun byKey(key: String): PhysicalInput? = byKey[normalizeKey(key)]

    fun defaultOutputsFor(key: String): List<Int> = byKey(key)?.defaultOutputs ?: emptyList()

    /** True when [key] names one of the back paddles. */
    fun isPaddle(key: String): Boolean = key == KEY_PADDLE_R1 || key == KEY_PADDLE_L1 ||
        key == KEY_PADDLE_R2 || key == KEY_PADDLE_L2

    /** Bit mask of all four paddles. */
    val PADDLE_MASK: Int = PADDLE_R1 or PADDLE_L1 or PADDLE_R2 or PADDLE_L2
}
