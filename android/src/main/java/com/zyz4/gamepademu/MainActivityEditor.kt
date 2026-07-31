package com.zyz4.gamepademu

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.view.FloatingEditorPanel
import com.zyz4.gamepademu.view.GamepadLayout
import com.zyz4.gamepademu.view.JoystickView
import com.zyz4.gamepademu.view.RotatableButton

// ── Floating Editor ──────────────────────────────────────

internal fun MainActivity.setupFloatingEditor() {
    val a = this
    a.floatingEditor = FloatingEditorPanel(a).apply {
        visibility = View.GONE
        editorListener = object : FloatingEditorPanel.EditorListener {
            override fun onSave() {
                a.viewModel.saveCurrentPreset(a.gamepadLayout.getPreset())
                a.gamepadLayout.exitEditMode()
                a.viewModel.updateEditMode(false)
                a.applyPreset(a.viewModel.currentPreset.value)
            }

            override fun onDiscard() {
                if (!a.gamepadLayout.hasUnsavedChanges()) {
                    a.gamepadLayout.exitEditMode()
                    a.viewModel.updateEditMode(false)
                    a.applyPreset(a.viewModel.currentPreset.value)
                    return
                }
                CustomDialog.showConfirm(a, "放弃修改", "确定放弃当前布局修改？",
                    positiveText = "放弃", onPositive = {
                        a.gamepadLayout.discardToSnapshot()
                        a.gamepadLayout.exitEditMode()
                        a.viewModel.updateEditMode(false)
                        a.applyPreset(a.viewModel.currentPreset.value)
                    })
            }

            override fun onAddButton() {
                a.showAddButtonDialog()
            }

            override fun onDeleteButton(buttonId: String) {
                CustomDialog.showConfirm(a, "删除控件", "确定删除该控件？",
                    positiveText = "删除", onPositive = {
                        a.gamepadLayout.removeButtonPosition(buttonId)
                        a.floatingEditor.clearParameters()
                    })
            }

            override fun onButtonUpdated(buttonId: String, updated: ButtonPosition) {
                a.gamepadLayout.updateButtonPosition(buttonId, updated)
                a.updateButtonLabels(a.viewModel.settings.value.displayMode)
            }

            override fun onPickOutputValues(buttonId: String, currentBits: List<Int>, onResult: (List<Int>) -> Unit) {
                a.showOutputValuePicker(currentBits, onResult)
            }
            override fun onGyroOrientationChanged(orientation: com.zyz4.gamepademu.model.GyroOrientation?) {
                val updated = a.gamepadLayout.getPreset().copy(gyroOrientation = orientation)
                a.gamepadLayout.loadPreset(updated)
                a.viewModel.updatePresetButtons(updated)
                a.viewModel.currentPresetGyroOrientation = orientation
                a.floatingEditor.presetGyroOrientation = orientation
            }

            override fun onEnterFollowAreaAdjust(buttonId: String) {
                a.floatingEditor.isAdjustingFollowArea = true
                a.gamepadLayout.enterFollowAreaAdjust(buttonId)
            }

            override fun onExitFollowAreaAdjust() {
                a.floatingEditor.isAdjustingFollowArea = false
                a.gamepadLayout.exitFollowAreaAdjust()
                // Re-show the joystick's parameters
                val pos = a.gamepadLayout.currentButtons.find { it.id == a.gamepadLayout.selectedButtonId }
                if (pos != null) {
                    a.floatingEditor.showParameters(a.gamepadLayout.selectedButtonId!!, pos)
                }
            }

            override fun onTransparencyPreviewStart(buttonId: String, isIdle: Boolean) {
                a.gamepadLayout.setTransparencyPreview(buttonId, isIdle, true)
            }

            override fun onTransparencyPreviewEnd(buttonId: String) {
                a.gamepadLayout.setTransparencyPreview(buttonId, true, false)
            }
        }
    }
    (a.findViewById<View>(android.R.id.content) as ViewGroup).addView(
        a.floatingEditor,
        FrameLayout.LayoutParams(
            (a.resources.displayMetrics.widthPixels * 0.4f).toInt(),
            (a.resources.displayMetrics.heightPixels * 0.8f).toInt()
        )
    )
}

internal fun MainActivity.getPreviewText(entry: CtrlEntry, mode: DisplayMode): String? {
    return when (entry.baseId) {
        "btnA" -> when (mode) { DisplayMode.XBOX -> "A"; DisplayMode.SWITCH -> "B"; else -> null }
        "btnB" -> when (mode) { DisplayMode.XBOX -> "B"; DisplayMode.SWITCH -> "A"; else -> null }
        "btnX" -> when (mode) { DisplayMode.XBOX -> "X"; DisplayMode.SWITCH -> "Y"; else -> null }
        "btnY" -> when (mode) { DisplayMode.XBOX -> "Y"; DisplayMode.SWITCH -> "X"; else -> null }
        "btnLB" -> when (mode) { DisplayMode.XBOX -> "LB"; DisplayMode.PLAYSTATION -> "L1"; DisplayMode.SWITCH -> "L" }
        "btnRB" -> when (mode) { DisplayMode.XBOX -> "RB"; DisplayMode.PLAYSTATION -> "R1"; DisplayMode.SWITCH -> "R" }
        "btnLT" -> when (mode) { DisplayMode.XBOX -> "LT"; DisplayMode.PLAYSTATION -> "L2"; DisplayMode.SWITCH -> "ZL" }
        "btnRT" -> when (mode) { DisplayMode.XBOX -> "RT"; DisplayMode.PLAYSTATION -> "R2"; DisplayMode.SWITCH -> "ZR" }
        "btnSelect" -> when (mode) { DisplayMode.PLAYSTATION -> "SHARE"; else -> null }
        "btnMenu" -> when (mode) { DisplayMode.PLAYSTATION -> "OPTION"; else -> null }
        "btnLS" -> "L"
        "btnRS" -> "R"
        "btnCustomCircle", "btnCustomRect" -> "自定义"
        else -> null
    }
}

internal fun MainActivity.getPreviewIcon(entry: CtrlEntry, mode: DisplayMode): Int {
    val text = getPreviewText(entry, mode)
    if (text != null) {
        return when (entry.baseId) {
            "btnLB", "btnRB", "btnLT", "btnRT" -> R.drawable.button_rounded_rect
            "btnCustomRect" -> R.drawable.button_rounded_rect
            else -> R.drawable.button_circle
        }
    }
    return when (entry.baseId) {
        "btnA" -> R.drawable.btn_ps_cross
        "btnB" -> R.drawable.btn_ps_circle
        "btnX" -> R.drawable.btn_ps_square
        "btnY" -> R.drawable.btn_ps_triangle
        "btnSelect" -> when (mode) { DisplayMode.XBOX -> R.drawable.btn_select_xbox; DisplayMode.SWITCH -> R.drawable.btn_select_switch; else -> R.drawable.button_circle }
        "btnMenu" -> when (mode) { DisplayMode.XBOX -> R.drawable.btn_menu_xbox; DisplayMode.SWITCH -> R.drawable.btn_menu_switch; else -> R.drawable.button_circle }
        "btnHome" -> when (mode) { DisplayMode.XBOX -> R.drawable.ic_home_xbox; DisplayMode.PLAYSTATION -> R.drawable.ic_home_playstation; else -> R.drawable.ic_home }
        "btnTouchpad" -> R.drawable.ic_touchpad_grid
        "btnLS" -> R.drawable.ic_ls
        "btnRS" -> R.drawable.ic_rs
        else -> entry.icon
    }
}

internal fun MainActivity.showAddButtonDialog() {
    val a = this
    val density = a.resources.displayMetrics.density
    val cols = 6
    val cellW = (400f * density).toInt()
    val iconSize = (48f * density).toInt()
    val mode = a.viewModel.settings.value.displayMode

    val content = NestedScrollView(a)
    val grid = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt())
    }

    allControls.chunked(cols).forEach { rowItems ->
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        rowItems.forEach { entry ->
            val wrapper = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { a.addDialog?.dismiss(); a.addControl(entry) }
                isClickable = true
                isFocusable = true
                setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
            }
            val text = a.getPreviewText(entry, mode)
            if (text != null) {
                if (entry.baseId in listOf("btnLS", "btnRS")) {
                    val fl = FrameLayout(a).apply {
                        setBackgroundResource(R.drawable.button_circle)
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    }
                    val iconId = if (entry.baseId == "btnLS") R.drawable.ic_ls else R.drawable.ic_rs
                    ImageView(a).apply {
                        setImageResource(iconId)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        )
                    }.also { fl.addView(it) }
                    TextView(a).apply {
                        this.text = text
                        setTextColor(-0x333334)
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    }.also { fl.addView(it) }
                    wrapper.addView(fl)
                } else {
                    val btn = Button(a).apply {
                        this.text = text
                        setTextColor(-0x333334)
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setBackgroundResource(a.getPreviewIcon(entry, mode))
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        isClickable = false
                    }
                    wrapper.addView(btn)
                }
            } else if (entry.isJoystick) {
                val jl = if (entry.baseId.startsWith("left")) "L" else "R"
                val jv = object : View(a) {
                    private val outerP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0xdddddd; style = Paint.Style.FILL }
                    private val outerS = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0xaaaaab; style = Paint.Style.STROKE; strokeWidth = 2f }
                    private val innerP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0xaaaaab; style = Paint.Style.FILL }
                    private val innerS = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x888889; style = Paint.Style.STROKE; strokeWidth = 1.5f }
                    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x555556; textAlign = Paint.Align.CENTER }
                    override fun onDraw(canvas: Canvas) {
                        val cx = width / 2f; val cy = height / 2f
                        val r = minOf(cx, cy)
                        val kr = r * 0.32f
                        canvas.drawCircle(cx, cy, r, outerP)
                        canvas.drawCircle(cx, cy, r, outerS)
                        canvas.drawCircle(cx, cy, kr, innerP)
                        canvas.drawCircle(cx, cy, kr, innerS)
                        lp.textSize = kr * 1.1f
                        val textY = cy - (lp.ascent() + lp.descent()) / 2f
                        canvas.drawText(jl, cx, textY, lp)
                    }
                }
                jv.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                wrapper.addView(jv)
            } else if (entry.isTouchpad) {
                val iv = ImageView(a).apply {
                    setImageResource(a.getPreviewIcon(entry, mode))
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                }
                wrapper.addView(iv)
            } else {
                val iv = ImageView(a).apply {
                    setImageResource(a.getPreviewIcon(entry, mode))
                    setBackgroundResource(R.drawable.button_circle)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                }
                wrapper.addView(iv)
            }
            val label = TextView(a)
            label.text = entry.name
            label.setTextColor(-0x333334)
            label.textSize = 10f
            label.gravity = Gravity.CENTER
            wrapper.addView(label)
            row.addView(wrapper, LinearLayout.LayoutParams(cellW / cols, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        grid.addView(row)
    }

    content.addView(grid)
    a.addDialog = CustomDialog.showCustomView(a, "添加控件", content, negativeText = "取消", scrollable = true)
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.addControl(entry: CtrlEntry) {
    val a = this
    val id = "${entry.baseId}_${a.addCounter++}"
    val view: View = when {
        entry.useImageButton -> ImageButton(a).apply {
            this.id = View.generateViewId(); tag = id
            setBackgroundResource(R.drawable.button_circle)
            setImageResource(entry.icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        entry.isJoystick -> JoystickView(a).apply {
            this.id = View.generateViewId(); tag = id
            val isLeft = entry.baseId == "leftJoystick"
            label = if (isLeft) "L" else "R"
            onStickClickDown = { a.viewModel.onButtonDown(if (isLeft) GamepadState.L3 else GamepadState.R3) }
            onStickClickUp = { a.viewModel.onButtonUp(if (isLeft) GamepadState.L3 else GamepadState.R3) }
            onStickMoved = { sx, sy -> if (isLeft) a.viewModel.onLeftStick(sx, sy) else a.viewModel.onRightStick(sx, sy) }
        }
        entry.isTouchpad -> {
            val tp = FrameLayout(a).apply {
                this.id = View.generateViewId(); tag = id
                setBackgroundResource(R.drawable.center_rect)
            }
            val label = TextView(a).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setTextColor(-0x6699999a)
                textSize = 11f
            }
            tp.addView(label)
            label.text = a.viewModel.connectionState.value.statusText
            a.touchpadLabels.add(label)
            a.setupTouchpadView(tp)
            tp
        }
        entry.isCustom -> {
            val btn = if (!entry.lockAspect) RotatableButton(a) else Button(a)
            btn.apply {
                this.id = View.generateViewId(); tag = id
                text = "自定义"
                setAllCaps(false)
                setTextColor(-0x333334); textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(entry.bgRes)
                gravity = Gravity.CENTER
            }
            btn
        }
        !entry.lockAspect -> RotatableButton(a).apply {
            this.id = View.generateViewId(); tag = id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
        }
        else -> Button(a).apply {
            this.id = View.generateViewId(); tag = id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
        }
    }
    a.gamepadLayout.addView(view)

    if (!entry.isTouchpad) {
        if (entry.isCustom) {
            a.setupCustomTouchHandler(view)
        } else {
            a.setupTouchHandler(view, entry.bit, entry.isDpad, entry.isTrigger, entry.isJoystick)
        }
    }

    val pos = ButtonPosition(
        id = id, x = 50, y = 20,
        width = entry.w, height = entry.h,
        lockAspect = entry.lockAspect,
        isCustom = entry.isCustom,
        customText = "自定义",
        customBits = emptyList(),
        roundShape = entry.baseId == "btnCustomCircle",
    )
    a.gamepadLayout.addButtonPosition(pos)
    a.gamepadLayout.setSelectedButton(id)
    a.updateButtonLabels(a.viewModel.settings.value.displayMode)
}

internal fun MainActivity.createCustomButtonView(pos: ButtonPosition): View {
    val a = this
    val displayText = (pos.customText ?: "自定义").ifEmpty { "自定义" }
    val view: View = if (pos.roundShape || pos.lockAspect) {
        Button(a).apply {
            id = View.generateViewId(); tag = pos.id
            text = displayText
            setAllCaps(false)
            setTextColor(-0x333334); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.button_circle)
            gravity = Gravity.CENTER
        }
    } else {
        RotatableButton(a).apply {
            id = View.generateViewId(); tag = pos.id
            text = displayText
            setAllCaps(false)
            setTextColor(-0x333334); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.button_rounded_rect)
            gravity = Gravity.CENTER
        }
    }
    a.gamepadLayout.addView(view)
    a.setupCustomTouchHandler(view)
    return view
}

internal fun MainActivity.applyPreset(preset: com.zyz4.gamepademu.model.LayoutPreset) {
    gamepadLayout.loadPreset(preset)
    ensureViewsForAllPresetButtons()
}

internal fun MainActivity.ensureViewsForAllPresetButtons() {
    val a = this
    val buttons = a.gamepadLayout.currentButtons

    val existingIds = (0 until a.gamepadLayout.childCount).mapNotNull {
        a.gamepadLayout.getChildAt(it).tag as? String
    }.toSet()

    for (pos in buttons) {
        if (pos.id in existingIds) continue
        if (pos.id == GamepadLayout.SETTINGS_BUTTON_ID) {
            a.createSettingsButtonView()
            continue
        }
        if (pos.isCustom) {
            a.createCustomButtonView(pos)
        } else {
            a.createStandardControlView(pos)
        }
    }
    a.gamepadLayout.bringSettingsToFront()
    a.updateButtonLabels(a.viewModel.settings.value.displayMode)
}

internal fun MainActivity.createStandardControlView(pos: ButtonPosition) {
    val a = this
    val baseId = pos.id.substringBefore("_")
    val entry = allControls.find { it.baseId == baseId } ?: return

    val view: View = when {
        entry.useImageButton -> ImageButton(a).apply {
            id = View.generateViewId(); tag = pos.id
            setBackgroundResource(entry.bgRes)
            setImageResource(entry.icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        entry.isJoystick -> JoystickView(a).apply {
            id = View.generateViewId(); tag = pos.id
            val isLeft = baseId == "leftJoystick"
            label = if (isLeft) "L" else "R"
            onStickClickDown = { a.viewModel.onButtonDown(if (isLeft) GamepadState.L3 else GamepadState.R3) }
            onStickClickUp = { a.viewModel.onButtonUp(if (isLeft) GamepadState.L3 else GamepadState.R3) }
            onStickMoved = { sx, sy -> if (isLeft) a.viewModel.onLeftStick(sx, sy) else a.viewModel.onRightStick(sx, sy) }
        }
        entry.isTouchpad -> {
            val tp = FrameLayout(a).apply {
                id = View.generateViewId(); tag = pos.id
                setBackgroundResource(R.drawable.center_rect)
            }
            val label = TextView(a).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setTextColor(-0x6699999a); textSize = 11f
                text = a.viewModel.connectionState.value.statusText
            }
            tp.addView(label)
            a.touchpadLabels.add(label)
            a.setupTouchpadView(tp)
            tp
        }
        entry.isCustom -> {
            val btn = if (!entry.lockAspect) RotatableButton(a) else Button(a)
            btn.apply {
                id = View.generateViewId(); tag = pos.id
                text = "自定义"
                setAllCaps(false)
                setTextColor(-0x333334); textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(entry.bgRes)
                gravity = Gravity.CENTER
            }
        }
        !entry.lockAspect -> RotatableButton(a).apply {
            id = View.generateViewId(); tag = pos.id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
        }
        else -> Button(a).apply {
            id = View.generateViewId(); tag = pos.id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
        }
    }

    if (!entry.isTouchpad && !entry.isCustom) {
        val bit = a.getBitForEntry(entry) ?: 0
        a.setupTouchHandler(view, bit, entry.isDpad, entry.isTrigger, entry.isJoystick)
    } else if (entry.isCustom) {
        a.setupCustomTouchHandler(view)
    }
    a.controlViews[baseId] = view
    a.gamepadLayout.addView(view)
}

internal fun MainActivity.getBitForEntry(entry: CtrlEntry): Int? {
    return when (entry.baseId) {
        "leftJoystick" -> GamepadState.L3
        "rightJoystick" -> GamepadState.R3
        "touchpad" -> GamepadState.TOUCHPAD_CLICK
        "btnTouchpad" -> GamepadState.TOUCHPAD_CLICK
        "btnDpadUp" -> GamepadState.DPAD_BIT_UP
        "btnDpadDown" -> GamepadState.DPAD_BIT_DOWN
        "btnDpadLeft" -> GamepadState.DPAD_BIT_LEFT
        "btnDpadRight" -> GamepadState.DPAD_BIT_RIGHT
        else -> if (entry.bit != 0) entry.bit else null
    }
}

internal fun MainActivity.showOutputValuePicker(currentBits: List<Int>, onResult: (List<Int>) -> Unit) {
    val a = this
    a.outputPickerDialog?.dismiss()
    val density = a.resources.displayMetrics.density
    val cols = 6
    val cellW = (400f * density).toInt()
    val iconSize = (48f * density).toInt()
    val mode = a.viewModel.settings.value.displayMode

    val content = NestedScrollView(a)
    val grid = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt())
    }

    allControls.filter { it.baseId != "btnCustomCircle" && it.baseId != "btnCustomRect" && !it.isJoystick && !it.isTouchpad }
        .chunked(cols).forEach { rowItems ->
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        rowItems.forEach { entry ->
            val bit = a.getBitForEntry(entry) ?: return@forEach
            val alreadySelected = bit in currentBits

            val wrapper = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener {
                    a.outputPickerDialog?.dismiss()
                    val newBits = if (alreadySelected) currentBits else currentBits + bit
                    onResult(newBits)
                }
                isClickable = true
                isFocusable = true
                setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
            }

            if (alreadySelected) {
                wrapper.setBackgroundResource(R.drawable.bg_chip_selected)
            }

            val text = a.getPreviewText(entry, mode)
            if (text != null) {
                if (entry.baseId in listOf("btnLS", "btnRS")) {
                    val fl = FrameLayout(a).apply {
                        setBackgroundResource(R.drawable.button_circle)
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    }
                    val iconId = if (entry.baseId == "btnLS") R.drawable.ic_ls else R.drawable.ic_rs
                    ImageView(a).apply {
                        setImageResource(iconId)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        )
                    }.also { fl.addView(it) }
                    TextView(a).apply {
                        this.text = text
                        setTextColor(-0x333334)
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    }.also { fl.addView(it) }
                    wrapper.addView(fl)
                } else {
                    val btn = Button(a).apply {
                        this.text = text
                        setTextColor(-0x333334)
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setBackgroundResource(a.getPreviewIcon(entry, mode))
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        isClickable = false
                    }
                    wrapper.addView(btn)
                }
            } else if (entry.isJoystick) {
                val jl = if (entry.baseId.startsWith("left")) "L" else "R"
                val jv = object : View(a) {
                    private val outerP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0xdddddd; style = Paint.Style.FILL }
                    private val outerS = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0xaaaaab; style = Paint.Style.STROKE; strokeWidth = 2f }
                    private val innerP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0xaaaaab; style = Paint.Style.FILL }
                    private val innerS = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x888889; style = Paint.Style.STROKE; strokeWidth = 1.5f }
                    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x555556; textAlign = Paint.Align.CENTER }
                    override fun onDraw(canvas: Canvas) {
                        val cx = width / 2f; val cy = height / 2f
                        val r = minOf(cx, cy)
                        val kr = r * 0.32f
                        canvas.drawCircle(cx, cy, r, outerP)
                        canvas.drawCircle(cx, cy, r, outerS)
                        canvas.drawCircle(cx, cy, kr, innerP)
                        canvas.drawCircle(cx, cy, kr, innerS)
                        lp.textSize = kr * 1.1f
                        val textY = cy - (lp.ascent() + lp.descent()) / 2f
                        canvas.drawText(jl, cx, textY, lp)
                    }
                }
                jv.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                wrapper.addView(jv)
            } else {
                val iv = ImageView(a).apply {
                    setImageResource(a.getPreviewIcon(entry, mode))
                    setBackgroundResource(R.drawable.button_circle)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                }
                wrapper.addView(iv)
            }
            row.addView(wrapper, LinearLayout.LayoutParams(cellW / cols, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        grid.addView(row)
    }

    content.addView(grid)
    a.outputPickerDialog = CustomDialog.showCustomView(a, "选择传出值", content, negativeText = "取消", scrollable = true)
}
