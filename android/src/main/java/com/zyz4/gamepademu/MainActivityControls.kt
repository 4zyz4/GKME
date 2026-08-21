package com.zyz4.gamepademu

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GestureDetectorCompat
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.TouchPoint
import com.zyz4.gamepademu.view.CustomKeypadView
import com.zyz4.gamepademu.view.DpadPadView
import com.zyz4.gamepademu.view.GamepadLayout
import com.zyz4.gamepademu.view.JoystickView
import com.zyz4.gamepademu.view.RotatableButton

private val _mainHandler = Handler(Looper.getMainLooper())

internal data class CtrlEntry(
    val baseId: String, val name: String, val icon: Int,
    val bgRes: Int = R.drawable.button_circle,
    val isJoystick: Boolean = false,
    val isTouchpad: Boolean = false,
    val isMousepad: Boolean = false,
    val isDpad: Boolean = false,
    val isTrigger: Boolean = false,
    val isDpadPad: Boolean = false,
    val isKeypad: Boolean = false,
    val useImageButton: Boolean = false,
    val isCustom: Boolean = false,
    val bit: Int = 0,
    val w: Int = 10, val h: Int = 10,
    val lockAspect: Boolean = true,
)

internal val allControls = listOf(
    CtrlEntry("btnDpadUp", "上方向", R.drawable.ic_arrow_up, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_UP),
    CtrlEntry("btnDpadDown", "下方向", R.drawable.ic_arrow_down, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_DOWN),
    CtrlEntry("btnDpadLeft", "左方向", R.drawable.ic_arrow_left, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_LEFT),
    CtrlEntry("btnDpadRight", "右方向", R.drawable.ic_arrow_right, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_RIGHT),
    CtrlEntry("dpadPad", "一体十字键", R.drawable.ic_dpad_pad, isDpadPad = true, w = 17, h = 17),
    CtrlEntry("btnA", "A", R.drawable.btn_ps_cross, bit = GamepadState.A),
    CtrlEntry("btnB", "B", R.drawable.btn_ps_circle, bit = GamepadState.B),
    CtrlEntry("btnX", "X", R.drawable.btn_ps_square, bit = GamepadState.X),
    CtrlEntry("btnY", "Y", R.drawable.btn_ps_triangle, bit = GamepadState.Y),
    CtrlEntry("btnLB", "LB", R.drawable.button_rounded_rect, R.drawable.button_rounded_rect, bit = GamepadState.LB, w = 14, h = 8, lockAspect = false),
    CtrlEntry("btnRB", "RB", R.drawable.button_rounded_rect, R.drawable.button_rounded_rect, bit = GamepadState.RB, w = 14, h = 8, lockAspect = false),
    CtrlEntry("btnLT", "LT", R.drawable.button_rounded_rect, R.drawable.button_rounded_rect, isTrigger = true, bit = GamepadState.LT, w = 14, h = 8, lockAspect = false),
    CtrlEntry("btnRT", "RT", R.drawable.button_rounded_rect, R.drawable.button_rounded_rect, isTrigger = true, bit = GamepadState.RT, w = 14, h = 8, lockAspect = false),
    CtrlEntry("leftJoystick", "左摇杆", R.drawable.joystick_outer, isJoystick = true, w = 17, h = 17),
    CtrlEntry("rightJoystick", "右摇杆", R.drawable.joystick_outer, isJoystick = true, w = 17, h = 17),
    CtrlEntry("touchpad", "触摸板", R.drawable.center_rect, isTouchpad = true, w = 34, h = 22, lockAspect = false),
    CtrlEntry("mousepad", "鼠标", R.drawable.center_rect, isMousepad = true, w = 34, h = 22, lockAspect = false),
    CtrlEntry("btnTouchpad", "触摸板按下", R.drawable.btn_touchpad, R.drawable.btn_touchpad, bit = GamepadState.TOUCHPAD_CLICK, w = 9, h = 9),
    CtrlEntry("btnLS", "左摇杆按下", R.drawable.btn_ls, R.drawable.btn_ls, bit = GamepadState.L3, w = 9, h = 9),
    CtrlEntry("btnRS", "右摇杆按下", R.drawable.btn_rs, R.drawable.btn_rs, bit = GamepadState.R3, w = 9, h = 9),
    CtrlEntry("btnSelect", "选择", R.drawable.btn_select_xbox, bit = GamepadState.SELECT, w = 9, h = 9),
    CtrlEntry("btnHome", "主页", R.drawable.ic_home, useImageButton = true, bit = GamepadState.HOME, w = 9, h = 9),
    CtrlEntry("btnMenu", "菜单", R.drawable.btn_menu_xbox, bit = GamepadState.START, w = 9, h = 9),
    CtrlEntry("btnCustomCircle", "自定义(圆)", R.drawable.button_circle, isCustom = true),
    CtrlEntry("btnCustomRect", "自定义(方)", R.drawable.button_rounded_rect, R.drawable.button_rounded_rect, w = 14, h = 8, lockAspect = false, isCustom = true),
    CtrlEntry("customKeypad", "自定义按键盘", R.drawable.ic_custom_keypad, isKeypad = true, w = 17, h = 17),
    CtrlEntry("btnMic", "麦克风静音", R.drawable.ic_mic, useImageButton = true, bit = GamepadState.MIC_MUTE, w = 9, h = 9),
)

// Keep custom button text vertically centered even when the button is shrunk:
// drop the default Button theme padding/font padding and let the text auto-shrink to fit.
internal fun TextView.enableAutoFitButtonText(maxSizeSp: Float, minSizeSp: Float = maxSizeSp * 0.25f) {
    includeFontPadding = false
    setPadding(0, 0, 0, 0)
    minWidth = 0
    minHeight = 0
    maxLines = 1
    val scaled = resources.displayMetrics.scaledDensity
    val maxPx = (maxSizeSp * scaled).toInt().coerceAtLeast(2)
    val minPx = (minSizeSp * scaled).toInt().coerceAtLeast(1)
    setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, TypedValue.COMPLEX_UNIT_PX)
}

// Re-cap the auto-fit text to at most maxSizePx (does not touch padding, which is
// managed separately so layout can re-apply it on size changes).
internal fun TextView.applyContentSizeCap(maxSizePx: Int, minSizeSp: Float = 4f) {
    includeFontPadding = false
    minWidth = 0
    minHeight = 0
    maxLines = 1
    val scaled = resources.displayMetrics.scaledDensity
    val maxPx = maxSizePx.coerceAtLeast(3)
    val minPx = (minSizeSp * scaled).toInt().coerceIn(1, maxPx - 1)
    setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, TypedValue.COMPLEX_UNIT_PX)
}

// ── Gamepad Layout Listener ────────────────────────────────

internal fun MainActivity.setupGamepadLayoutListener() {
    val a = this
    gamepadLayout.listener = object : GamepadLayout.GamepadLayoutListener {
        override fun onButtonSelected(buttonId: String?) {
            a.viewModel.setSelectedButtonId(buttonId)
            val preset = a.gamepadLayout.getPreset()
            a.floatingEditor.presetGyroOrientation = preset.gyroOrientation
            if (buttonId != null) {
                val pos = a.gamepadLayout.currentButtons.find { it.id == buttonId }
                if (pos != null) {
                    a.floatingEditor.showParameters(buttonId, pos)
                }
            } else {
                a.floatingEditor.clearParameters()
            }
        }

        override fun onEditModeChanged(isEditMode: Boolean) {
            a.floatingEditor.visibility = if (isEditMode) View.VISIBLE else View.GONE
        }

        override fun onTouchpadEvent(
            x: Float, y: Float,
            touches: List<FloatArray>,
            touchpadTouch: Boolean, touchpadClick: Boolean
        ) {
            val touchPoints = touches.map { arr ->
                com.zyz4.gamepademu.model.TouchPoint(id = arr[0].toInt(),
                    x = (arr[1] * 1919).toInt().coerceIn(0, 1919),
                    y = (arr[2] * 942).toInt().coerceIn(0, 942),
                    active = arr.size > 3 && arr[3] > 0.5f)
            }
            a.physicalControllerHandler.setCapturedTouchpadState(x, y, touchPoints, touchpadTouch, touchpadClick)
            a.syncPhysicalControllerState()
        }
    }

    a.physicalControllerHandler.onPointerCaptureNeeded = { enabled ->
        val needCapture = enabled
        a.pointerCaptureNeeded = needCapture
        a.physicalControllerHandler.isPointerCaptureActive = enabled
        a.gamepadLayout.setTouchpadCaptureMode(needCapture)
    }

    // Set up mouse mode: when mousepad control exists in the layout, route captured pointer events
    // through MouseInputDispatcher and send reports via Bluetooth HID Report ID 2.
    a.gamepadLayout.onCapturedMouseReport = { dx, dy, wheelV, wheelH, buttonDown, buttonUp, _, _ ->
        val s = a.viewModel.settings.value
        if (s.connectionMode != com.zyz4.gamepademu.model.ConnectionMode.BLUETOOTH) {
            ByteArray(0)
        } else {
            val btn = (buttonDown.toInt() and 0x03) or (buttonUp.toInt() and 0xFC)
            a.viewModel.connectionManager.sendMouseReport(
                button = btn.toByte(),
                dx = dx.toByte(),
                dy = dy.toByte(),
                wheel = wheelV.toByte(),
            )
            ByteArray(4)
        }
    }
}

// ── Gamepad ──────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.createAllControls() {
    val a = this
    a.viewModel.onHapticFeedbackPress = { a.performHaptic(isPress = true) }
    a.viewModel.onHapticFeedbackRelease = { a.performHaptic(isPress = false) }

    data class Def(
        val baseId: String, val bit: Int = 0,
        val isDpad: Boolean = false, val isTrigger: Boolean = false,
        val isJoystick: Boolean = false, val isTouchpad: Boolean = false, val isMousepad: Boolean = false,
        val useImageButton: Boolean = false, val icon: Int = 0,
        val bgRes: Int = R.drawable.button_circle,
    )

    val defaults = listOf(
        Def("btnDpadUp", bit = GamepadState.DPAD_UP, isDpad = true, useImageButton = true, icon = R.drawable.ic_arrow_up),
        Def("btnDpadDown", bit = GamepadState.DPAD_DOWN, isDpad = true, useImageButton = true, icon = R.drawable.ic_arrow_down),
        Def("btnDpadLeft", bit = GamepadState.DPAD_LEFT, isDpad = true, useImageButton = true, icon = R.drawable.ic_arrow_left),
        Def("btnDpadRight", bit = GamepadState.DPAD_RIGHT, isDpad = true, useImageButton = true, icon = R.drawable.ic_arrow_right),
        Def("btnA", bit = GamepadState.A),
        Def("btnB", bit = GamepadState.B),
        Def("btnX", bit = GamepadState.X),
        Def("btnY", bit = GamepadState.Y),
        Def("btnLB", bit = GamepadState.LB, bgRes = R.drawable.button_rounded_rect),
        Def("btnRB", bit = GamepadState.RB, bgRes = R.drawable.button_rounded_rect),
        Def("btnLT", isTrigger = true, bit = GamepadState.LT, bgRes = R.drawable.button_rounded_rect),
        Def("btnRT", isTrigger = true, bit = GamepadState.RT, bgRes = R.drawable.button_rounded_rect),
        Def("leftJoystick", isJoystick = true),
        Def("rightJoystick", isJoystick = true),
        Def("touchpad", isTouchpad = true),
        Def("mousepad", isMousepad = true),
        Def("btnSelect", bit = GamepadState.SELECT),
        Def("btnHome", bit = GamepadState.HOME, useImageButton = true, icon = R.drawable.ic_home),
        Def("btnMenu", bit = GamepadState.START),
    )

    for (d in defaults) {
        val view = when {
            d.useImageButton -> ImageButton(a).apply {
                this.id = View.generateViewId(); tag = d.baseId
                setBackgroundResource(R.drawable.button_circle)
                setImageResource(d.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            d.isJoystick -> JoystickView(a).apply {
                this.id = View.generateViewId(); tag = d.baseId
                val isLeft = d.baseId == "leftJoystick"
                label = if (isLeft) "L" else "R"
                onStickClickDown = { a.viewModel.onButtonDown(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                onStickClickUp = { a.viewModel.onButtonUp(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                onStickMoved = { sx, sy -> if (isLeft) a.viewModel.onLeftStick(sx, sy) else a.viewModel.onRightStick(sx, sy) }
                doubleClickEnable = true
            }
            d.isTouchpad -> {
                val tp = FrameLayout(a).apply {
                    this.id = View.generateViewId(); tag = d.baseId
                    setBackgroundResource(R.drawable.center_rect)
                }
                val label = TextView(a).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    setTextColor(-0x6699999a)
                    textSize = 11f
                    text = a.viewModel.connectionState.value.statusText
                }
                tp.addView(label)
                a.touchpadLabels.add(label)
                a.setupTouchpadView(tp)
                tp
            }
            d.isMousepad -> {
                val mp = FrameLayout(a).apply {
                    this.id = View.generateViewId(); tag = d.baseId
                    setBackgroundResource(R.drawable.center_rect)
                }
                val label = TextView(a).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    setTextColor(-0x6699999a)
                    textSize = 11f
                    text = a.viewModel.connectionState.value.statusText
                }
                mp.addView(label)
                a.mousepadLabels.add(label)
                a.setupMousepadView(mp)
                mp
            }
            d.baseId in listOf("btnLB", "btnRB", "btnLT", "btnRT") -> RotatableButton(a).apply {
                this.id = View.generateViewId(); tag = d.baseId
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(d.bgRes)
                gravity = android.view.Gravity.CENTER
                enableAutoFitButtonText(20f)
            }
            else -> Button(a).apply {
                this.id = View.generateViewId(); tag = d.baseId
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(d.bgRes)
                gravity = android.view.Gravity.CENTER
                enableAutoFitButtonText(20f)
            }
        }
        if (!d.isTouchpad && !d.isMousepad) {
            a.setupTouchHandler(view, d.bit, d.isDpad, d.isTrigger, d.isJoystick)
        }
        a.gamepadLayout.addView(view)
        a.controlViews[d.baseId] = view
    }

    a.createSettingsButtonView()
    a.gamepadLayout.bringSettingsToFront()
    a.updateButtonLabels(a.viewModel.settings.value.displayMode)
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.createSettingsButtonView(): View {
    val a = this
    val view = ImageButton(a).apply {
        id = View.generateViewId()
        tag = GamepadLayout.SETTINGS_BUTTON_ID
        setBackgroundResource(R.drawable.bg_small_btn)
        setImageResource(R.drawable.ic_settings)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(
            (8f * a.resources.displayMetrics.density).toInt(),
            (8f * a.resources.displayMetrics.density).toInt(),
            (8f * a.resources.displayMetrics.density).toInt(),
            (8f * a.resources.displayMetrics.density).toInt()
        )
        contentDescription = "Settings"
        translationZ = 1000f
        setOnClickListener { a.showSettings() }
    }
    a.gamepadLayout.addView(view)
    return view
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupTouchHandler(view: View, bit: Int, isDpad: Boolean, isTrigger: Boolean, isJoystick: Boolean) {
    val a = this
    fun applyActiveAlpha() {
        val id = view.tag as? String ?: return
        val pos = a.gamepadLayout.currentButtons.find { it.id == id } ?: return
        view.alpha = 1f - (pos.activeTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
    }
    fun applyIdleAlpha() {
        val id = view.tag as? String ?: return
        val pos = a.gamepadLayout.currentButtons.find { it.id == id } ?: return
        view.alpha = 1f - (pos.idleTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
    }
    when {
        isDpad -> view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); applyActiveAlpha(); a.viewModel.onDpad(bit, true); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; applyIdleAlpha(); a.viewModel.onDpad(bit, false); true }
                else -> true
            }
        }
        isTrigger -> {
            val analogFn: (Int) -> Unit = if (bit == GamepadState.LT) a.viewModel::onLeftTrigger else a.viewModel::onRightTrigger
            view.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); applyActiveAlpha(); a.viewModel.onButtonDown(bit); analogFn(255); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; applyIdleAlpha(); a.viewModel.onButtonUp(bit); analogFn(0); true }
                    else -> true
                }
            }
        }
        !isJoystick -> view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); applyActiveAlpha(); if (bit != 0) a.viewModel.onButtonDown(bit); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; applyIdleAlpha(); if (bit != 0) a.viewModel.onButtonUp(bit); true }
                else -> true
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.createDpadPadView(id: String): DpadPadView {
    val a = this
    return DpadPadView(a).apply {
        this.id = View.generateViewId(); tag = id
        a.setupDpadPadTouch(this)
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupDpadPadTouch(view: DpadPadView) {
    val a = this
    view.onDpadChange = { released, pressed ->
        a.viewModel.updateDpad(pressed, released)
    }
    view.onLift = { a.viewModel.updateDpadRelease() }
}
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.createCustomKeypadView(id: String): CustomKeypadView {
    val a = this
    val pos = a.gamepadLayout.currentButtons.find { it.id == id }
    return CustomKeypadView(a).apply {
        this.id = View.generateViewId(); tag = id
        a.setupCustomKeypadTouch(this, pos)
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupCustomKeypadTouch(view: CustomKeypadView, initialPos: ButtonPosition?) {
    val a = this
    fun findKeypadPos(): ButtonPosition? = a.gamepadLayout.currentButtons.find { it.id == view.tag }
    view.onRegionPress = { region ->
        val pos = findKeypadPos()
        if (pos != null) {
            val bits = ButtonPosition.keypadBitsOf(pos).getOrNull(region).orEmpty()
            a.viewModel.onCustomButtonDown(bits)
        }
    }
    view.onRegionRelease = { region ->
        val pos = findKeypadPos()
        if (pos != null) {
            val bits = ButtonPosition.keypadBitsOf(pos).getOrNull(region).orEmpty()
            a.viewModel.onCustomButtonUp(bits)
        }
    }
    view.validDirs = (0..3).filter { i ->
        val kpBits = findKeypadPos()?.let { ButtonPosition.keypadBitsOf(it) } ?: listOf()
        kpBits.getOrNull(i)?.isNotEmpty() == true
    }.toSet()
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupTouchpadView(tp: FrameLayout) {
    val a = this
    val handler = Handler(Looper.getMainLooper())
    var firstTapTime = 0L; var firstTapX = 0f; var firstTapY = 0f
    var isDoubleClick = false
    val density = resources.displayMetrics.density
    val doubleTapTimeout = Runnable { firstTapTime = 0 }
    val slots = arrayOfNulls<TouchPoint>(10)

    fun mapPoint(px: Float, py: Float): Pair<Int, Int> {
        val btnId = tp.tag as? String ?: return 0 to 0
        val pos = a.gamepadLayout.currentButtons.find { it.id == btnId } ?: return 0 to 0
        val w = tp.measuredWidth.coerceAtLeast(1)
        val h = tp.measuredHeight.coerceAtLeast(1)
        val rotation = pos.rotation
        val nx = px / w
        val ny = py / h
        val (vx, vy) = when (rotation % 360) {
            90 -> Pair(ny, 1f - nx)
            180 -> Pair(1f - nx, 1f - ny)
            270 -> Pair(1f - ny, nx)
            else -> Pair(nx, ny)
        }
        if (pos.followAreaEnabled && pos.followAreaW > 0 && pos.followAreaH > 0) {
            // Extended touch range: the sent coordinate is normalized over the
            // rectangle area instead of the touchpad control itself.
            val cell = a.gamepadLayout.getCellSize()
            if (cell > 0f) {
                val areaL = pos.followAreaX * cell
                val areaT = pos.followAreaY * cell
                val areaW = pos.followAreaW * cell
                val areaH = pos.followAreaH * cell
                val absX = tp.left + vx * w
                val absY = tp.top + vy * h
                val nax = ((absX - areaL) / areaW).coerceIn(0f, 1f)
                val nay = ((absY - areaT) / areaH).coerceIn(0f, 1f)
                return (nax * 1919).toInt().coerceIn(0, 1919) to (nay * 942).toInt().coerceIn(0, 942)
            }
        }
        return (vx * 1919).toInt().coerceIn(0, 1919) to (vy * 942).toInt().coerceIn(0, 942)
    }

    fun emptySlot(): Int {
        for (i in slots.indices) if (slots[i] == null) return i
        return -1
    }

    fun nearestSlot(x: Int, y: Int): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in slots.indices) {
            val s = slots[i] ?: continue
            val dx = s.x - x; val dy = s.y - y
            val d = (dx * dx + dy * dy).toFloat()
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun send() {
        if (isDoubleClick && slots.all { it == null }) {
            tp.isPressed = false; a.viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK)
            isDoubleClick = false
        }
        a.viewModel.onTouchpadTouches(
            slots.take(2).mapIndexed { i, tp -> tp ?: TouchPoint(id = i, active = false) }
        )
    }

    fun touchpadAlpha(active: Boolean) {
        val id = tp.tag as? String ?: return
        val pos = a.gamepadLayout.currentButtons.find { it.id == id } ?: return
        tp.alpha = 1f - ((if (active) pos.activeTransparency else pos.idleTransparency).coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
    }

    tp.setOnTouchListener { v, event ->
        val btnId = v.tag as? String
        val doubleClickEnable = btnId?.let { a.gamepadLayout.currentButtons.find { p -> p.id == it }?.doubleClickEnable } ?: true
        val masked = event.action and MotionEvent.ACTION_MASK

        if (masked == MotionEvent.ACTION_UP) {
            val (sx, sy) = mapPoint(event.getX(0), event.getY(0))
            val slot = nearestSlot(sx, sy)
            if (slot >= 0) slots[slot] = null
            if (slots.all { it == null }) {
                if (isDoubleClick) { v.isPressed = false; a.viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK) }
                isDoubleClick = false
                touchpadAlpha(false)
            }
            send()
            return@setOnTouchListener true
        }

        if (masked == MotionEvent.ACTION_CANCEL) {
            slots.fill(null)
            if (isDoubleClick) { v.isPressed = false; a.viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK) }
            isDoubleClick = false
            touchpadAlpha(false)
            send()
            return@setOnTouchListener true
        }

        if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN) {
            val idx = if (masked == MotionEvent.ACTION_DOWN) 0 else event.actionIndex
            val (sx, sy) = mapPoint(event.getX(idx), event.getY(idx))
            var slot = emptySlot()
            if (slot < 0) slot = nearestSlot(sx, sy)
            if (slot < 0) slot = if (masked == MotionEvent.ACTION_DOWN) 0 else 1
            slots[slot] = TouchPoint(id = slot, x = sx, y = sy, active = true)
            touchpadAlpha(true)
            if (masked == MotionEvent.ACTION_DOWN) {
                v.performClick()
                if (isDoubleClick) {
                } else if (doubleClickEnable) {
                    val now = System.currentTimeMillis()
                    if (now - firstTapTime < 300 && firstTapTime > 0) {
                        val dx = (event.getX(0) - firstTapX) / density
                        val dy = (event.getY(0) - firstTapY) / density
                        val distDp = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (distDp < 32.0 && slots.count { it != null } < 2) {
                            handler.removeCallbacks(doubleTapTimeout)
                            v.isPressed = true; isDoubleClick = true; firstTapTime = 0
                            a.viewModel.onButtonDown(GamepadState.TOUCHPAD_CLICK)
                        } else {
                            firstTapTime = now; firstTapX = event.getX(0); firstTapY = event.getY(0); isDoubleClick = false
                            handler.postDelayed(doubleTapTimeout, 300)
                        }
                    } else {
                        firstTapTime = now; firstTapX = event.getX(0); firstTapY = event.getY(0); isDoubleClick = false
                        handler.postDelayed(doubleTapTimeout, 300)
                    }
                }
            }
            send()
            return@setOnTouchListener true
        }

        if (masked == MotionEvent.ACTION_POINTER_UP) {
            val idx = event.actionIndex
            val (sx, sy) = mapPoint(event.getX(idx), event.getY(idx))
            val slot = nearestSlot(sx, sy)
            if (slot >= 0) slots[slot] = null
            if (slots.all { it == null }) touchpadAlpha(false)
            send()
            return@setOnTouchListener true
        }

        if (masked == MotionEvent.ACTION_MOVE) {
            for (i in 0 until event.pointerCount) {
                val (sx, sy) = mapPoint(event.getX(i), event.getY(i))
                val slot = nearestSlot(sx, sy)
                if (slot >= 0) slots[slot] = slots[slot]!!.copy(x = sx, y = sy)
                else { val e = emptySlot(); if (e >= 0) slots[e] = TouchPoint(id = e, x = sx, y = sy, active = true) }
            }
            send()
            return@setOnTouchListener true
        }

        true
    }
}

/**
 * Set up a mousepad control.
 *
 * Uses GestureDetectorCompat on the mousepad View itself
 * (exactly like Mousedroid's GestureHandler on the touchpadSensor View).
 * Works regardless of pointer capture / connection mode.
 *
 * Gesture map:
 *   Single-finger slide  → cursor movement (Bluetooth HID)
 *   Two-finger slide    → vertical/horizontal scroll
 *   Single-finger tap    → left click (no highlight, no vibration)
 *   Double-tap           → left button hold-down (highlight + vibration, release on finger up)
 *   Two-finger tap       → right click
 */
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupMousepadView(mp: FrameLayout) {
    val a = this
    var isDoubleTapPress = false     // 左键是否保持按下（双击按住）
    var lastTapDownTime = 0L        // 第一击按下时刻，用于 150ms 双击窗口
    var gestureMoved = false        // 本次单指手势是否明显移动
    var singleClickUp: Runnable? = null  // 第一击单击的延迟释放（可被第二击取消）
    var heldButtons = 0             // 当前按住的鼠标键位（绝对状态）
    var multiTouch = false          // 本次手势是否涉及多指（双指滚动/右键）
    var twoFingerMoved = false      // 双指手势是否产生明显位移（滚动而非轻触）
    var tracked = 0                 // 当前按在 mousepad 上的手指数（自有计数）
    val SCROLL_SENSITIVITY = 0.3f  // 滚动灵敏度默认值（像素 -> 滚轮单位，越小越慢）
    var wheelAccumX = 0f           // 双指滚动的水平/垂直累积量（传统滚动，降灵敏度用）
    var wheelAccumY = 0f
    var cursorAccumX = 0f          // 移动光标的累积量（鼠标灵敏度 <1 时保留小数位移）
    var cursorAccumY = 0f
    var mouseSens = 1f             // 鼠标灵敏度（来自布局配置）
    var scrollSens = SCROLL_SENSITIVITY // 滚动灵敏度（来自布局配置）
    var invertScroll = false       // 反转滚动方向（来自布局配置）

    // per-pointer down times for right-click detection
    val pointerDownTimes = mutableMapOf<Int, Long>()
    // per-pointer last-known position for delta calculation
    val prevX = mutableMapOf<Int, Float>()
    val prevY = mutableMapOf<Int, Float>()

    val DOUBLE_TAP_WINDOW = 200L   // 第一击按下后一定时间的再次轻点 -> 潜在双击/按住拖动
    val TAP_TIMEOUT = 200L         // 轻触时长上限 / 单击按住时长
    val MOVE_SLOP = 8f             // 区分点击与拖动的最小位移

    fun readConfig() {
        val id = mp.tag as? String ?: return
        val pos = a.gamepadLayout.currentButtons.find { it.id == id } ?: return
        mouseSens = pos.mouseSensitivity
        scrollSens = pos.scrollSensitivity
        invertScroll = pos.invertScroll
    }

    fun press(bit: Int) {
        heldButtons = heldButtons or bit
        a.sendMouseReportDirect(buttonDown = heldButtons, buttonUp = 0, dx = 0, dy = 0)
    }
    fun release(bit: Int) {
        heldButtons = heldButtons and bit.inv()
        a.sendMouseReportDirect(buttonDown = heldButtons, buttonUp = 0, dx = 0, dy = 0)
    }
    fun moveRaw(dx: Float, dy: Float) {
        cursorAccumX += dx * mouseSens
        cursorAccumY += dy * mouseSens
        val ix = cursorAccumX.toInt()
        val iy = cursorAccumY.toInt()
        if (ix != 0 || iy != 0) {
            cursorAccumX -= ix
            cursorAccumY -= iy
            a.sendMouseReportDirect(buttonDown = heldButtons, buttonUp = 0,
                dx = ix.toByte(), dy = iy.toByte())
        }
    }
    fun cancelSingleClick() {
        singleClickUp?.let { _mainHandler.removeCallbacks(it) }
        singleClickUp = null
    }

    mp.setOnTouchListener { _, event ->
        val masked = event.action and MotionEvent.ACTION_MASK
        val pointerCount = event.pointerCount

        when (masked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                pointerDownTimes[pid] = event.eventTime
                prevX[pid] = event.getX(idx)
                prevY[pid] = event.getY(idx)
                gestureMoved = false
                twoFingerMoved = false
                multiTouch = false
                tracked = 1
                cursorAccumX = 0f
                cursorAccumY = 0f
                readConfig()

                val isPotentialDouble = lastTapDownTime > 0 &&
                        (event.eventTime - lastTapDownTime) < DOUBLE_TAP_WINDOW
                if (isPotentialDouble) {
                    cancelSingleClick()
                    isDoubleTapPress = true
                    mp.isPressed = true
                    mousepadHighlight(mp, true, a)
                    a.performHaptic(isPress = true)
                    press(1)
                } else if (singleClickUp != null) {
                    cancelSingleClick()
                    release(1)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                pointerDownTimes[pid] = event.eventTime
                prevX[pid] = event.getX(idx)
                prevY[pid] = event.getY(idx)
                twoFingerMoved = false
                multiTouch = true
                gestureMoved = false
                tracked = 2
                wheelAccumX = 0f
                wheelAccumY = 0f
                if (singleClickUp != null) {
                    cancelSingleClick()
                    release(1)
                }
                if (isDoubleTapPress) {
                    release(1)
                    isDoubleTapPress = false
                    mp.isPressed = false
                    mousepadHighlight(mp, false, a)
                    a.performHaptic(isPress = false)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 经 touchpad 派发后，抬指通常以 ACTION_UP（单指）送达，
                // 这里仅做状态清理，右键判定放在 ACTION_UP 中按 tracked 处理。
                val idx = event.actionIndex
                val liftedPid = event.getPointerId(idx)
                pointerDownTimes.remove(liftedPid)
                prevX.remove(liftedPid)
                prevY.remove(liftedPid)
                if (tracked > 0) tracked -= 1
            }
            MotionEvent.ACTION_MOVE -> {
                var totalDx = 0f
                var totalDy = 0f
                val count = pointerCount
                for (i in 0 until count) {
                    val pid = event.getPointerId(i)
                    val ex = event.getX(i)
                    val ey = event.getY(i)
                    val prevPx = prevX[pid] ?: ex
                    val prevPy = prevY[pid] ?: ey
                    totalDx += (ex - prevPx)
                    totalDy += (ey - prevPy)
                    prevX[pid] = ex
                    prevY[pid] = ey
                }
                if (count > 0) {
                    totalDx /= count
                    totalDy /= count
                }

                if (!gestureMoved &&
                    (Math.abs(totalDx) + Math.abs(totalDy)) > MOVE_SLOP) {
                    gestureMoved = true
                }

                if (count == 2) {
                    multiTouch = true
                    tracked = 2
                    if ((Math.abs(totalDx) + Math.abs(totalDy)) > MOVE_SLOP) {
                        twoFingerMoved = true
                    }
                    val sDy = if (invertScroll) -totalDy else totalDy
                    val sDx = if (invertScroll) -totalDx else totalDx
                    wheelAccumY += sDy * scrollSens
                    wheelAccumX += sDx * scrollSens
                    val wY = wheelAccumY.toInt().coerceIn(-127, 127)
                    val wX = wheelAccumX.toInt().coerceIn(-127, 127)
                    wheelAccumY -= wY
                    wheelAccumX -= wX
                    if (wX != 0 || wY != 0) {
                        a.sendMouseReportDirect(
                            buttonDown = 0, buttonUp = 0, dx = 0, dy = 0,
                            wheel = wY.toByte(), hWheel = wX.toByte()
                        )
                    }
                } else if (isDoubleTapPress) {
                    moveRaw(totalDx, totalDy)
                } else if (count == 1) {
                    moveRaw(totalDx, totalDy)
                }
            }
            MotionEvent.ACTION_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                val downTime = pointerDownTimes[pid] ?: event.downTime
                val dur = event.eventTime - downTime
                pointerDownTimes.remove(pid)
                prevX.remove(pid)
                prevY.remove(pid)

                if (multiTouch) {
                    // 双指手势：第一指抬起且未滚动 -> 右键；第二指抬起仅清理
                    if (tracked >= 2 && !twoFingerMoved) {
                        a.sendMouseTap(button = 2)
                    }
                    if (heldButtons != 0) {
                        heldButtons = 0
                        a.sendMouseReportDirect(buttonDown = 0, buttonUp = 0, dx = 0, dy = 0)
                    }
                    tracked -= 1
                    if (tracked <= 0) {
                        multiTouch = false
                        twoFingerMoved = false
                        gestureMoved = false
                        tracked = 0
                        wheelAccumX = 0f
                        wheelAccumY = 0f
                        cursorAccumX = 0f
                        cursorAccumY = 0f
                    }
                } else if (isDoubleTapPress) {
                    release(1)
                    isDoubleTapPress = false
                    mp.isPressed = false
                    mousepadHighlight(mp, false, a)
                    a.performHaptic(isPress = false)
                    // 第二次轻点很快抬起且无大范围滑动 -> 追加一次点击，模拟双击
                    if (!gestureMoved && dur < TAP_TIMEOUT) {
                        press(1)
                        release(1)
                    }
                } else {
                    val isTap = !gestureMoved && dur < TAP_TIMEOUT
                    if (isTap) {
                        press(1)
                        lastTapDownTime = downTime
                        val r = object : Runnable {
                            override fun run() {
                                release(1)
                                singleClickUp = null
                            }
                        }
                        singleClickUp = r
                        _mainHandler.postDelayed(r, TAP_TIMEOUT)
                    } else if (heldButtons != 0) {
                        heldButtons = 0
                        a.sendMouseReportDirect(buttonDown = 0, buttonUp = 0, dx = 0, dy = 0)
                    }
                }
                if (!multiTouch) {
                    tracked = 0
                    twoFingerMoved = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDoubleTapPress) {
                    release(1)
                    isDoubleTapPress = false
                    mp.isPressed = false
                    mousepadHighlight(mp, false, a)
                    a.performHaptic(isPress = false)
                }
                cancelSingleClick()
                if (heldButtons != 0) {
                    heldButtons = 0
                    a.sendMouseReportDirect(buttonDown = 0, buttonUp = 0, dx = 0, dy = 0)
                }
                pointerDownTimes.clear()
                prevX.clear()
                prevY.clear()
                multiTouch = false
                twoFingerMoved = false
                tracked = 0
                wheelAccumX = 0f
                wheelAccumY = 0f
                cursorAccumX = 0f
                cursorAccumY = 0f
            }
        }
        true
    }
}

/** Mousepad visual highlight (alpha) matching touchpad behavior. */
private fun mousepadHighlight(mp: FrameLayout, active: Boolean, a: MainActivity) {
    val id = mp.tag as? String ?: return
    val pos = a.gamepadLayout.currentButtons.find { it.id == id } ?: return
    mp.alpha = 1f - ((if (active) pos.activeTransparency else pos.idleTransparency).coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
}

/** Send a mouse button tap (down then up) directly via Bluetooth HID. */
private fun MainActivity.sendMouseTap(button: Int) {
    val s = viewModel.settings.value
    if (s.connectionMode != com.zyz4.gamepademu.model.ConnectionMode.BLUETOOTH) return
    viewModel.connectionManager.sendMouseReport(
        button = button.toByte(), // button bitmask: bit0=left, bit1=right, bit2=middle
        dx = 0.toByte(), dy = 0.toByte(), wheel = 0.toByte(),
    )
    _mainHandler.postDelayed(Runnable {
        viewModel.connectionManager.sendMouseReport(
            button = 0.toByte(), // release all buttons
            dx = 0.toByte(), dy = 0.toByte(), wheel = 0.toByte(),
        )
    }, 150)
}

/** Send a mouse report with button state changes and delta movement (Bluetooth only). */
private fun MainActivity.sendMouseReportDirect(
    buttonDown: Int, buttonUp: Int,
    dx: Byte, dy: Byte, wheel: Byte = 0, hWheel: Byte = 0
) {
    val s = viewModel.settings.value
    if (s.connectionMode != com.zyz4.gamepademu.model.ConnectionMode.BLUETOOTH) return
    viewModel.connectionManager.sendMouseReport(
        button = ((buttonDown and 0x03) or (buttonUp and 0xFC)).toByte(),
        dx = dx, dy = dy, wheel = wheel,
    )
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupCustomTouchHandler(view: View) {
    val a = this
    val id = view.tag as String
    view.setOnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true; v.performClick()
                val pos = a.gamepadLayout.currentButtons.find { it.id == id }
                if (pos != null) view.alpha = 1f - (pos.activeTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
                val bits = pos?.customBits.orEmpty()
                a.viewModel.onCustomButtonDown(bits)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                val pos = a.gamepadLayout.currentButtons.find { it.id == id }
                if (pos != null) view.alpha = 1f - (pos.idleTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
                val bits = pos?.customBits.orEmpty()
                a.viewModel.onCustomButtonUp(bits)
                true
            }
            else -> true
        }
    }
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.updateButtonLabels(mode: DisplayMode) {
    val a = this
    val abxyList = listOf("btnA", "btnB", "btnX", "btnY")
    val bumperList = listOf("btnLB", "btnRB", "btnLT", "btnRT")

    for (i in 0 until a.gamepadLayout.childCount) {
        val child = a.gamepadLayout.getChildAt(i)
        val tag = child.tag as? String ?: continue
        val baseId = tag.substringBefore("_") ?: continue

        if (baseId.startsWith("btnCustom")) {
            val pos = a.gamepadLayout.currentButtons.find { it.id == tag }
            (child as? TextView)?.apply {
                text = (pos?.customText ?: "自定义").ifEmpty { "自定义" }
                setAllCaps(false)
                textSize = 20f
            }
            continue
        }

        when {
            baseId in abxyList -> {
                val idx = abxyList.indexOf(baseId)
                (child as? Button)?.apply {
                    when (mode) {
                        DisplayMode.XBOX -> {
                            text = listOf("A", "B", "X", "Y")[idx]; textSize = 20f
                            setBackgroundResource(R.drawable.button_circle)
                            foreground = null
                        }
                        DisplayMode.PLAYSTATION -> {
                            text = ""; textSize = 12f
                            setBackgroundResource(R.drawable.button_circle)
                            foreground = context.getDrawable(intArrayOf(
                                R.drawable.ic_ps_cross, R.drawable.ic_ps_circle,
                                R.drawable.ic_ps_square, R.drawable.ic_ps_triangle
                            )[idx])
                            foregroundGravity = android.view.Gravity.CENTER
                        }
                        DisplayMode.SWITCH -> {
                            text = listOf("B", "A", "Y", "X")[idx]; textSize = 20f
                            setBackgroundResource(R.drawable.button_circle)
                            foreground = null
                        }
                    }
                }
            }
            baseId in bumperList -> {
                val idx = bumperList.indexOf(baseId)
                (child as? Button)?.apply {
                    when (mode) {
                        DisplayMode.XBOX -> { text = listOf("LB", "RB", "LT", "RT")[idx]; textSize = 20f; setBackgroundResource(R.drawable.button_rounded_rect) }
                        DisplayMode.PLAYSTATION -> { text = listOf("L1", "R1", "L2", "R2")[idx]; textSize = 20f; setBackgroundResource(R.drawable.button_rounded_rect) }
                        DisplayMode.SWITCH -> { text = listOf("L", "R", "ZL", "ZR")[idx]; textSize = 20f; setBackgroundResource(R.drawable.button_rounded_rect) }
                    }
                }
            }
            baseId == "btnSelect" -> {
                (child as? Button)?.apply {
                    // XBOX/SWITCH: the icon is drawn as foreground content (adaptive), so the
                    // background is the neutral circle. PS: text.
                    when (mode) {
                        DisplayMode.XBOX -> { text = ""; setBackgroundResource(R.drawable.button_circle) }
                        DisplayMode.PLAYSTATION -> { text = "SHARE"; setBackgroundResource(R.drawable.button_circle) }
                        DisplayMode.SWITCH -> { text = ""; setBackgroundResource(R.drawable.button_circle) }
                    }
                }
            }
            baseId == "btnHome" -> {
                (child as? ImageButton)?.apply {
                    setBackgroundResource(R.drawable.button_circle)
                    // The adaptive cap is applied via the foreground (see AppearanceApplier), so
                    // clear it here: the freshly set image is then taken as the new icon.
                    foreground = null
                    when (mode) {
                        DisplayMode.XBOX -> setImageResource(R.drawable.ic_home_xbox)
                        DisplayMode.PLAYSTATION -> setImageResource(R.drawable.ic_home_playstation)
                        DisplayMode.SWITCH -> setImageResource(R.drawable.ic_home)
                    }
                }
            }
            baseId == "btnMenu" -> {
                (child as? Button)?.apply {
                    when (mode) {
                        DisplayMode.XBOX -> { text = ""; setBackgroundResource(R.drawable.button_circle) }
                        DisplayMode.PLAYSTATION -> { text = "OPTION"; setBackgroundResource(R.drawable.button_circle) }
                        DisplayMode.SWITCH -> { text = ""; setBackgroundResource(R.drawable.button_circle) }
                    }
                }
            }
            baseId == "btnTouchpad" -> {
                (child as? Button)?.apply {
                    text = ""; setBackgroundResource(R.drawable.btn_touchpad)
                }
            }
            baseId == "btnLS" -> {
                (child as? Button)?.apply {
                    // Triangle + letter are integrated into a single foreground drawable
                    // (see AppearanceApplier.letterIconDrawable), so no text is needed here.
                    text = ""
                    setBackgroundResource(R.drawable.button_circle)
                }
            }
            baseId == "btnRS" -> {
                (child as? Button)?.apply {
                    text = ""
                    setBackgroundResource(R.drawable.button_circle)
                }
            }
        }
    }

    // Re-apply custom appearance after label updates
    a.gamepadLayout.applyAppearance(a.viewModel.settings.value)
}
