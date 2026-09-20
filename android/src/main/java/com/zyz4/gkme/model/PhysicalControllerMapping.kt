package com.zyz4.gkme.model

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
)

/**
 * The user-configurable mapping of one physical input.
 *
 * An absent key in [AppSettings.physicalInputMappings] means "use the default": the input's
 * own [PhysicalInput.defaultOutputs] and gyro activation off. A stored mapping always wins,
 * so an empty [outputs] explicitly disables the input.
 */
data class PhysicalInputMapping(
    val outputs: List<Int> = emptyList(),
    val gyroActivate: Boolean = false,
)

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
    const val KEY_TOUCHPAD_CLICK = "TOUCHPAD_CLICK"
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
        PhysicalInput(KEY_SELECT, "选择", PhysicalInputKind.BUTTON, listOf(GamepadState.SELECT), GamepadState.SELECT),
        PhysicalInput(KEY_START, "菜单", PhysicalInputKind.BUTTON, listOf(GamepadState.START), GamepadState.START),
        PhysicalInput(KEY_L3, "左摇杆按下", PhysicalInputKind.BUTTON, listOf(GamepadState.L3), GamepadState.L3),
        PhysicalInput(KEY_R3, "右摇杆按下", PhysicalInputKind.BUTTON, listOf(GamepadState.R3), GamepadState.R3),
        PhysicalInput(KEY_DPAD_UP, "方向键上", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_UP), GamepadState.DPAD_BIT_UP),
        PhysicalInput(KEY_DPAD_DOWN, "方向键下", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_DOWN), GamepadState.DPAD_BIT_DOWN),
        PhysicalInput(KEY_DPAD_LEFT, "方向键左", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_LEFT), GamepadState.DPAD_BIT_LEFT),
        PhysicalInput(KEY_DPAD_RIGHT, "方向键右", PhysicalInputKind.BUTTON, listOf(GamepadState.DPAD_BIT_RIGHT), GamepadState.DPAD_BIT_RIGHT),
        PhysicalInput(KEY_HOME, "主页", PhysicalInputKind.BUTTON, listOf(GamepadState.HOME), GamepadState.HOME),
        PhysicalInput(KEY_TOUCHPAD_CLICK, "触摸板按下", PhysicalInputKind.BUTTON, listOf(GamepadState.TOUCHPAD_CLICK), GamepadState.TOUCHPAD_CLICK),
        PhysicalInput(KEY_MIC_MUTE, "麦克风静音", PhysicalInputKind.BUTTON, listOf(GamepadState.MIC_MUTE), GamepadState.MIC_MUTE),
        PhysicalInput(KEY_PADDLE_R1, "背键右上 (P1)", PhysicalInputKind.BUTTON, emptyList(), PADDLE_R1),
        PhysicalInput(KEY_PADDLE_L1, "背键左上 (P3)", PhysicalInputKind.BUTTON, emptyList(), PADDLE_L1),
        PhysicalInput(KEY_PADDLE_R2, "背键右下 (P2)", PhysicalInputKind.BUTTON, emptyList(), PADDLE_R2),
        PhysicalInput(KEY_PADDLE_L2, "背键左下 (P4)", PhysicalInputKind.BUTTON, emptyList(), PADDLE_L2),
    )

    /** Inputs that only gate the gyro (no key remapping). */
    val GYRO_ONLY: List<PhysicalInput> = listOf(
        PhysicalInput(KEY_LEFT_JOYSTICK, "左摇杆", PhysicalInputKind.JOYSTICK),
        PhysicalInput(KEY_RIGHT_JOYSTICK, "右摇杆", PhysicalInputKind.JOYSTICK),
        PhysicalInput(KEY_TOUCHPAD, "触摸板", PhysicalInputKind.TOUCHPAD),
    )

    /** Every configurable input, buttons first. */
    val ALL: List<PhysicalInput> = BUTTONS + GYRO_ONLY

    private val byKey: Map<String, PhysicalInput> = ALL.associateBy { it.key }

    fun byKey(key: String): PhysicalInput? = byKey[key]

    fun defaultOutputsFor(key: String): List<Int> = byKey[key]?.defaultOutputs ?: emptyList()

    /** True when [key] names one of the back paddles. */
    fun isPaddle(key: String): Boolean = key == KEY_PADDLE_R1 || key == KEY_PADDLE_L1 ||
        key == KEY_PADDLE_R2 || key == KEY_PADDLE_L2

    /** Bit mask of all four paddles. */
    val PADDLE_MASK: Int = PADDLE_R1 or PADDLE_L1 or PADDLE_R2 or PADDLE_L2
}
