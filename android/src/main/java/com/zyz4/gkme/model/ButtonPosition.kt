package com.zyz4.gkme.model

data class ButtonPosition(
    val id: String,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 10,
    val height: Int = 10,
    // ── 自由/高精度持久化几何 ──
    // x / y 为屏幕宽 / 高的百分比；width / height / travelDistance 为屏幕长边的百分比（float）。
    // 关闭网格时作为权威几何；开启网格时也写入，用于保留密度 > 120 时的坐标精度。
    val freeX: Float? = null,
    val freeY: Float? = null,
    val freeWidth: Float? = null,
    val freeHeight: Float? = null,
    val freeFollowAreaX: Float? = null,
    val freeFollowAreaY: Float? = null,
    val freeFollowAreaW: Float? = null,
    val freeFollowAreaH: Float? = null,
    val freeTravelDistance: Float? = null,
    val visible: Boolean = true,
    val lockAspect: Boolean = false,
    val swipeTrigger: Boolean = false,
    val rotation: Int = 0,
    val isCustom: Boolean = false,
    val customText: String? = null,
    val customBits: List<Int>? = null,
    // 组合键时序：同时按下 / 分帧按下 / 自定义间隔（customBits 使用）
    val comboTiming: ComboTiming = ComboTiming.SIMULTANEOUS,
    val comboIntervalMs: Int = ComboTiming.DEFAULT_INTERVAL_MS,
    val roundShape: Boolean = true,
    val doubleClickEnable: Boolean = true,
    val isKeypad: Boolean = false,
    val keypadTexts: List<String>? = null,
    val keypadBits: List<List<Int>>? = null,
    // 每个方向区域的组合键时序（按 KEYPAD_EIGHT_WAY_COUNT 个区域索引存放）
    val keypadComboTimings: List<ComboTiming>? = null,
    val keypadComboIntervals: List<Int>? = null,
    // true = 8 方向模式（含 4 个斜向），false = 4 方向模式
    val keypadEightWay: Boolean = false,
    // 中心形状：SQUARE = 方形中心，CIRCLE = 圆形中心（周围区域变径向扇环）
    val centerShape: CenterShape = CenterShape.SQUARE,
    // 中心区域大小百分比 (CENTER_SIZE_MIN..CENTER_SIZE_MAX)，0 = 不显示中心，
    // CENTER_SIZE_AUTO = 自动（沿用各控件的传统默认值）。
    // 圆形中心：半径 = 控件半径 × 百分比；方形中心：边长 = 控件直径 × 百分比。
    val centerSize: Int = -1,
    val sensitivityCurve: List<Float>? = null,
    // 摇杆灵敏度百分比 (10-300)，100 = 手指位移与输出距离 1:1；在死区之前应用
    val joystickSensitivity: Int = 100,
    // 触摸板模式：不以手指绝对位置映射摇杆，而以手指速度映射；手指停下即回中。
    // 灵敏度/死区/反死区/曲线仍照常生效（作用于速度换算出的归一化偏移）。
    val touchpadMode: Boolean = false,
    // 预测：用 history + EMA 速度外推下一帧位置，加快响应。适用于摇杆、触摸板、鼠标板、线性扳机。
    val prediction: Boolean = false,
    val deadZone: Int = 0,
    val reverseDeadZone: Int = 0,
    // 不透明度百分比 (0-100)，100 = 完全不透明
    val idleOpacity: Int = 100,
    val activeOpacity: Int = 100,
    val followAreaOpacity: Int = 100,
    val followAreaEnabled: Boolean = false,
    val followAreaX: Int = 0,
    val followAreaY: Int = 0,
    val followAreaW: Int = 0,
    val followAreaH: Int = 0,
    val overlapTrigger: Boolean = true,
    val followAreaOverlapTrigger: Boolean = false,
    val mouseSensitivity: Float = 1f,
    val mouseAcceleration: List<Float>? = null,
    val scrollSensitivity: Float = 0.1f,
    val mouseMoveSlop: Int = 4,
    val invertScrollV: Boolean = false,
    val invertScrollH: Boolean = false,
    // ── 鼠标触控板手势动作 ──
    val singleTapAction: MouseGestureAction = MouseGestureAction.LEFT_CLICK,
    val twoFingerTapAction: MouseGestureAction = MouseGestureAction.RIGHT_CLICK,
    val threeFingerTapAction: MouseGestureAction = MouseGestureAction.MIDDLE_CLICK,
    val doubleTapDragAction: MouseGestureAction = MouseGestureAction.LEFT_DRAG,
    val oneFingerSwipeAction: MouseGestureAction = MouseGestureAction.MOVE_CURSOR,
    val twoFingerSwipeAction: MouseGestureAction = MouseGestureAction.SCROLL,
    val threeFingerSwipeAction: MouseGestureAction = MouseGestureAction.SCROLL,
    val linearTriggerEnabled: Boolean = false,
    val slideDirection: SlideDirection = SlideDirection.DOWN,
    val travelDistance: Int = 10,
    // ── Per-control gyro activation ──
    val gyroActivate: Boolean = false,
    // ── Auto hold: one tap holds, second tap releases ──
    val autoHold: Boolean = false,
    // ── Keyboard control ──
    val isKeyboard: Boolean = false,
) {
    companion object {
        // 预测外推时间（约一帧，60fps）：预测位置 = 当前位置 + 速度 × 该时间。
        const val PREDICTION_FRAME_MS = 16f
        const val KEYPAD_BASE_ID = "customKeypad"
        // 基础区域：0=上, 1=下, 2=左, 3=右, 4=中心
        const val KEYPAD_COUNT = 5
        const val KEYPAD_CENTER_INDEX = 4
        // 8 方向区域：5=左上, 6=右上, 7=左下, 8=右下
        const val KEYPAD_EIGHT_WAY_COUNT = 9

        // ── 中心区域大小（%）──
        // CENTER_SIZE_AUTO 表示自动：一体十字键 33%，自定义按键盘 4 方向 40% / 8 方向 33%。
        // 0 表示显式关闭中心区域。
        const val CENTER_SIZE_AUTO = -1
        const val CENTER_SIZE_MIN = 0
        const val CENTER_SIZE_MAX = 70
        const val DPAD_PAD_CENTER_SIZE_DEFAULT = 33
        const val KEYPAD_CENTER_SIZE_DEFAULT_FOUR = 40
        const val KEYPAD_CENTER_SIZE_DEFAULT_EIGHT = 33

        /** 控件实际使用的中心区域大小百分比，把 [CENTER_SIZE_AUTO] 解析为各控件的传统默认值。 */
        fun centerSizeOf(p: ButtonPosition): Int {
            if (p.centerSize >= CENTER_SIZE_MIN) return p.centerSize.coerceIn(CENTER_SIZE_MIN, CENTER_SIZE_MAX)
            val base = p.id.substringBefore("_")
            return when (base) {
                KEYPAD_BASE_ID ->
                    if (p.keypadEightWay) KEYPAD_CENTER_SIZE_DEFAULT_EIGHT else KEYPAD_CENTER_SIZE_DEFAULT_FOUR
                "dpadPad" -> DPAD_PAD_CENTER_SIZE_DEFAULT
                else -> DPAD_PAD_CENTER_SIZE_DEFAULT
            }
        }

        val KEYPAD_DEFAULT_TEXTS: List<String> =
            listOf("上", "下", "左", "右", "中", "左上", "右上", "左下", "右下")
        val KEYPAD_DEFAULT_BITS: List<List<Int>> = List(KEYPAD_EIGHT_WAY_COUNT) { emptyList() }
        val KEYPAD_DEFAULT_COMBO_TIMINGS: List<ComboTiming> =
            List(KEYPAD_EIGHT_WAY_COUNT) { ComboTiming.SIMULTANEOUS }
        val KEYPAD_DEFAULT_COMBO_INTERVALS: List<Int> =
            List(KEYPAD_EIGHT_WAY_COUNT) { ComboTiming.DEFAULT_INTERVAL_MS }

        fun isKeypad(id: String): Boolean =
            id.substringBefore("_") == KEYPAD_BASE_ID

        /** 按区域索引顺序返回所有可绑定方向（不含中心）。8 方向模式才包含斜向。 */
        fun keypadDirectionIndices(eightWay: Boolean): List<Int> =
            if (eightWay) listOf(0, 1, 2, 3, 5, 6, 7, 8) else listOf(0, 1, 2, 3)

        fun keypadTextsOf(p: ButtonPosition): List<String> {
            val t = p.keypadTexts ?: return KEYPAD_DEFAULT_TEXTS
            if (t.size < KEYPAD_COUNT) return KEYPAD_DEFAULT_TEXTS
            if (t.size >= KEYPAD_EIGHT_WAY_COUNT) return t
            // 旧版只有 5 项：补齐斜向默认文本，保证 8 方向模式可安全索引
            return t + KEYPAD_DEFAULT_TEXTS.subList(t.size, KEYPAD_EIGHT_WAY_COUNT)
        }

        fun keypadBitsOf(p: ButtonPosition): List<List<Int>> {
            val b = p.keypadBits ?: return KEYPAD_DEFAULT_BITS
            if (b.size < KEYPAD_COUNT) return KEYPAD_DEFAULT_BITS
            if (b.size >= KEYPAD_EIGHT_WAY_COUNT) return b
            return b + KEYPAD_DEFAULT_BITS.subList(b.size, KEYPAD_EIGHT_WAY_COUNT)
        }

        fun keypadComboTimingsOf(p: ButtonPosition): List<ComboTiming> {
            val t = p.keypadComboTimings ?: return KEYPAD_DEFAULT_COMBO_TIMINGS
            if (t.size < KEYPAD_COUNT) return KEYPAD_DEFAULT_COMBO_TIMINGS
            if (t.size >= KEYPAD_EIGHT_WAY_COUNT) return t
            return t + KEYPAD_DEFAULT_COMBO_TIMINGS.subList(t.size, KEYPAD_EIGHT_WAY_COUNT)
        }

        fun keypadComboIntervalsOf(p: ButtonPosition): List<Int> {
            val v = p.keypadComboIntervals ?: return KEYPAD_DEFAULT_COMBO_INTERVALS
            if (v.size < KEYPAD_COUNT) return KEYPAD_DEFAULT_COMBO_INTERVALS
            if (v.size >= KEYPAD_EIGHT_WAY_COUNT) return v
            return v + KEYPAD_DEFAULT_COMBO_INTERVALS.subList(v.size, KEYPAD_EIGHT_WAY_COUNT)
        }

        fun keypadComboTimingOf(p: ButtonPosition, region: Int): ComboTiming =
            keypadComboTimingsOf(p).getOrElse(region) { ComboTiming.SIMULTANEOUS }

        fun keypadComboIntervalOf(p: ButtonPosition, region: Int): Int =
            keypadComboIntervalsOf(p).getOrElse(region) { ComboTiming.DEFAULT_INTERVAL_MS }
    }

}
