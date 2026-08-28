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
import com.zyz4.gamepademu.view.CustomKeypadView

private fun lastIndexOfUnderscore(s: String): Int = s.lastIndexOf('_')

// ── Floating Editor ──────────────────────────────────────

// Built lazily via `floatingEditor by lazy` so the ~1100-line panel is not
// constructed during startup.
internal fun MainActivity.createFloatingEditor(): FloatingEditorPanel {
    val a = this
    return FloatingEditorPanel(a).apply {
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
                val current = a.gamepadLayout.currentButtons.find { it.id == buttonId }
                val merged = current?.let { cur ->
                    updated.copy(
                        x = cur.x,
                        y = cur.y,
                        followAreaX = cur.followAreaX,
                        followAreaY = cur.followAreaY,
                        followAreaW = cur.followAreaW,
                        followAreaH = cur.followAreaH,
                    )
                } ?: updated
                a.gamepadLayout.updateButtonPosition(buttonId, merged)
                val wasLinearTrigger = current?.linearTriggerEnabled == true
                val isLinearTrigger = merged.linearTriggerEnabled && setOf("btnLT", "btnRT").contains(merged.id.substringBefore("_"))
                if (wasLinearTrigger != isLinearTrigger) {
                    a.recreateViewForButton(buttonId, merged)
                } else {
                    a.updateButtonLabels(a.viewModel.settings.value.displayMode)
                }
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

                override fun onGyroModeChanged(mode: com.zyz4.gamepademu.model.GyroMode) {
                    a.viewModel.updateGyroMode(mode)
                }

override fun onGyroModeSensitivityChanged(value: Int) {
                        a.viewModel.updateGyroModeSensitivity(value)
                    }

                override fun onGyroActivateModeChanged(mode: com.zyz4.gamepademu.model.GyroActivateMode) {
                        a.viewModel.updateGyroActivateMode(mode)
                    }

            override fun onEnterGlobalGyroSettings() {
                a.gamepadLayout.deselectButton()
                a.gamepadLayout.blockSelectionForGlobalSettings = true
            }

            override fun onExitGlobalGyroSettings() {
                a.gamepadLayout.blockSelectionForGlobalSettings = false
            }
        }
    }.also { panel ->
        (a.findViewById<View>(android.R.id.content) as ViewGroup).addView(
            panel,
            FrameLayout.LayoutParams(
                (a.resources.displayMetrics.widthPixels * 0.4f).toInt(),
                (a.resources.displayMetrics.heightPixels * 0.8f).toInt()
            )
        )
    }
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
        "btnMouseLMB" -> "LMB"
        "btnMouseRMB" -> "RMB"
        "btnMouseMMB" -> "MMB"
        "btnCustomCircle", "btnCustomRect" -> "自定义"
        else -> null
    }
}

internal fun MainActivity.getPreviewIcon(entry: CtrlEntry, mode: DisplayMode): Int {
    val text = getPreviewText(entry, mode)
    if (text != null) {
        return when (entry.baseId) {
            "btnLB", "btnRB", "btnLT", "btnRT" -> R.drawable.button_rounded_rect
            "btnCustomRect", "btnMouseLMB", "btnMouseRMB", "btnMouseMMB" -> R.drawable.button_rounded_rect
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
        "customKeypad" -> R.drawable.ic_custom_keypad
        else -> entry.icon
    }
}

internal fun MainActivity.showAddButtonDialog() {
    val a = this
    val density = a.resources.displayMetrics.density
    val cols = 6
    val iconSize = (48f * density).toInt()
    val mode = a.viewModel.settings.value.displayMode
    val cellW = (400f * density).toInt()

// Categorize controls
    val gamepadControls = allControls.filter {
        val base = it.baseId
        !base.startsWith("btnMouse") && base != "mousepad" && base != "btnCustomCircle" && base != "btnCustomRect" && base != "customKeypad" && !it.isKeyboard
    }
    val mouseControls = allControls.filter { it.baseId.startsWith("btnMouse") || it.baseId == "mousepad" }
    val customControls = allControls.filter { it.baseId in listOf("btnCustomCircle", "btnCustomRect", "customKeypad") }
    val keyboardControls = allControls.filter { it.isKeyboard }

    fun createPreviewView(entry: CtrlEntry): View {
        val wrapper = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { a.addDialog?.dismiss(); a.addControl(entry) }
            isClickable = true
            isFocusable = true
            setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
        }
        val text = a.getPreviewText(entry, mode)
        val labelText = when (entry.baseId) {
            "btnMouseLMB" -> "鼠标左键"
            "btnMouseRMB" -> "鼠标右键"
            "btnMouseMMB" -> "鼠标中键"
            else -> entry.name
        }
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
        } else if (entry.isKeypad) {
            val iv = ImageView(a).apply {
                setImageResource(a.getPreviewIcon(entry, mode))
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            wrapper.addView(iv)
        } else if (entry.isMousepad) {
            val iv = ImageView(a).apply {
                setImageResource(a.getPreviewIcon(entry, mode))
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            wrapper.addView(iv)
        } else if (entry.isKeyboard) {
            val btn = Button(a).apply {
                this.text = entry.name
                setTextColor(-0x333334)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.button_circle)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                isClickable = false
            }
            wrapper.addView(btn)
        } else {
            val iv = ImageView(a).apply {
                setImageResource(a.getPreviewIcon(entry, mode))
                if (!entry.isDpadPad) setBackgroundResource(R.drawable.button_circle)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            wrapper.addView(iv)
        }
        val label = TextView(a)
        label.text = labelText
        label.setTextColor(-0x333334)
        label.textSize = 10f
        label.gravity = Gravity.CENTER
        wrapper.addView(label)
        return wrapper
    }

    fun buildGrid(controls: List<CtrlEntry>): View {
        val grid = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt())
        }
        controls.chunked(cols).forEach { rowItems ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowItems.forEach { entry ->
                row.addView(createPreviewView(entry), LinearLayout.LayoutParams(cellW / cols, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (rowItems.indexOf(entry) < rowItems.lastIndex) rightMargin = (6f * density).toInt()
                })
            }
            grid.addView(row)
        }
        return grid
    }

    // Tab layout
    val content = NestedScrollView(a)
    val contentLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12f * density).toInt(), 0, (12f * density).toInt(), (12f * density).toInt())
    }

    val tabRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gravity = Gravity.CENTER
    }

    var selectedIndex = 0
    val tabLabels = arrayOf("手柄", "鼠标", "键盘", "自定义")
    val tabViews = arrayOfNulls<TextView>(4)
    val tabDatas = arrayOf(gamepadControls, mouseControls, keyboardControls, customControls)

    // Page container - defined before updateTabs so the lambda can reference it
    val pageContainer = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
    }

    // Pre-build pages first so pageViews is available in updateTabs lambda
    val pageViews = arrayOfNulls<View>(4)
    for (i in tabDatas.indices) {
        pageViews[i] = buildGrid(tabDatas[i])
    }
    pageViews[0]?.let { pageContainer.addView(it) }

    fun updateTabs() {
        tabRow.removeAllViews()
        for (i in tabLabels.indices) {
            val tab = TextView(a).apply {
                text = tabLabels[i]
                setTextColor(if (i == selectedIndex) -0x1 else -0x666667)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (i == selectedIndex) R.drawable.bg_chip_selected else R.drawable.bg_chip)
                setPadding((16f * density).toInt(), (6f * density).toInt(), (16f * density).toInt(), (6f * density).toInt())
                setOnClickListener {
                    if (it != this) return@setOnClickListener
                    selectedIndex = i
                    pageContainer.removeAllViews()
                    pageViews[i]?.let { pageContainer.addView(it) }
                    // Re-render tabs to update selected style
                    updateTabs()
                }
            }
            tabViews[i] = tab
            tabRow.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < tabLabels.lastIndex) rightMargin = (8f * density).toInt()
            })
        }
    }

    updateTabs()
    contentLayout.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (8f * density).toInt()
        bottomMargin = (8f * density).toInt()
    })

    contentLayout.addView(pageContainer)
    content.addView(contentLayout)

    val dialogW = minOf((a.resources.displayMetrics.widthPixels * 0.85f).toInt(), (700f * density).toInt())
    a.addDialog = CustomDialog.showCustomView(a, "添加控件", content, dialogW, negativeText = "取消", scrollable = true)
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
            val clickBit = if (isLeft) GamepadState.L3 else GamepadState.R3
            label = if (isLeft) "L" else "R"
            onStickClickDown = { a.viewModel.onButtonDown(clickBit); a.viewModel.triggerHapticPress() }
            onStickClickUp = { a.viewModel.onButtonUp(clickBit); a.viewModel.triggerHapticRelease() }
            onStickMoved = { sx, sy -> if (isLeft) a.viewModel.onLeftStick(sx, sy) else a.viewModel.onRightStick(sx, sy) }
            doubleClickEnable = true
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
        entry.isMousepad -> {
            val mp = FrameLayout(a).apply {
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
            mp.addView(label)
            label.text = a.viewModel.connectionState.value.statusText
            a.mousepadLabels.add(label)
            a.setupMousepadView(mp)
            mp
        }
        entry.isDpadPad -> a.createDpadPadView(id)
        entry.isKeypad -> a.createCustomKeypadView(id)
        entry.isKeyboard -> {
            val btn = if (!entry.lockAspect) RotatableButton(a) else Button(a)
            btn.apply {
                this.id = View.generateViewId(); tag = id
                text = entry.name
                setAllCaps(false)
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.button_rounded_rect)
                gravity = Gravity.CENTER
                enableAutoFitButtonText(20f)
            }
            btn
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
                enableAutoFitButtonText(20f)
            }
            btn
        }
        entry.baseId.startsWith("btnMouse") -> Button(a).apply {
            this.id = View.generateViewId(); tag = id
            text = entry.name
            setAllCaps(false)
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.button_rounded_rect)
            gravity = Gravity.CENTER
            enableAutoFitButtonText(20f)
        }
        !entry.lockAspect -> RotatableButton(a).apply {
            this.id = View.generateViewId(); tag = id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
            enableAutoFitButtonText(20f)
        }
        else -> Button(a).apply {
            this.id = View.generateViewId(); tag = id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
            enableAutoFitButtonText(20f)
        }
    }

    // Add both the view and position together using post to ensure they are processed
    // in the same layout pass, avoiding a layout request where only the view exists.
    val mouseTexts = mapOf(
        "btnMouseLMB" to "LMB",
        "btnMouseRMB" to "RMB",
        "btnMouseMMB" to "MMB",
    )
    val pos = if (entry.isKeypad) {
        ButtonPosition(
            id = id, x = 50, y = 20,
            width = entry.w, height = entry.h,
            lockAspect = entry.lockAspect,
            isCustom = entry.isCustom,
            isKeypad = entry.isKeypad,
            keypadTexts = ButtonPosition.KEYPAD_DEFAULT_TEXTS,
            keypadBits = ButtonPosition.KEYPAD_DEFAULT_BITS,
            keypadCenterDoubleClick = true,
            roundShape = false,
        )
    } else if (entry.isKeyboard) {
        val existingKeyboardPositions = a.gamepadLayout.currentButtons.filter { it.isKeyboard }
        var newX = 50
        var newY = 20
        for (kp in existingKeyboardPositions) {
            if (kp.x + kp.width > newX && kp.x < newX + entry.w && kp.y + kp.height > newY && kp.y < newY + entry.h) {
                newX += entry.w + 1
                if (newX + entry.w > 120) {
                    newX = 50
                    newY += entry.h + 1
                }
            }
        }
        ButtonPosition(
            id = id, x = newX, y = newY.coerceIn(0, 120 - entry.h),
            width = entry.w, height = entry.h,
            lockAspect = entry.lockAspect,
            isCustom = entry.isCustom,
            isKeyboard = entry.isKeyboard,
            customText = "键盘",
            customBits = emptyList(),
            roundShape = false,
        )
    } else {
        val existingSameBase = a.gamepadLayout.currentButtons.filter { it.id.substringBefore("_") == entry.baseId }
        var newX = 50
        var newY = 20
        for (bp in existingSameBase) {
            if (bp.x + bp.width > newX && bp.x < newX + entry.w && bp.y + bp.height > newY && bp.y < newY + entry.h) {
                newX += entry.w + 1
                if (newX + entry.w > 120) {
                    newX = 50
                    newY += entry.h + 1
                }
            }
        }
        ButtonPosition(
            id = id, x = newX, y = newY.coerceIn(0, 120 - entry.h),
            width = entry.w, height = entry.h,
            lockAspect = entry.lockAspect,
            isCustom = entry.isCustom,
            customText = mouseTexts[entry.baseId] ?: "自定义",
            customBits = if (entry.isCustom) emptyList() else listOfNotNull(a.getBitForEntry(entry)),
            roundShape = entry.baseId == "btnCustomCircle",
        )
    }

    a.gamepadLayout.post {
        a.gamepadLayout.addView(view)
        a.gamepadLayout.addButtonPosition(pos)

        if (!entry.isTouchpad && !entry.isMousepad && !entry.isDpadPad && !entry.isKeyboard) {
            if (entry.isCustom) {
                a.setupCustomTouchHandler(view)
            } else if (!entry.isKeypad) {
                a.setupTouchHandler(view, entry.bit, entry.isDpad, entry.isTrigger, entry.isJoystick)
            }
        } else if (entry.isKeyboard) {
            a.setupKeyboardTouchHandler(view, entry.keyboardKeyCode, pos)
        }

        a.gamepadLayout.post {
            a.gamepadLayout.setSelectedButton(id)
            a.updateButtonLabels(a.viewModel.settings.value.displayMode)
        }
    }
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
            enableAutoFitButtonText(20f)
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
            enableAutoFitButtonText(20f)
        }
    }
    a.gamepadLayout.addView(view)
    a.setupCustomTouchHandler(view)
    return view
}

@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.setupKeyboardTouchHandler(view: View, keyCode: Int, pos: com.zyz4.gamepademu.model.ButtonPosition) {
    val a = this
    val holdState = mutableMapOf<String, Boolean>()
    val swipeActive = mutableMapOf<String, Boolean>()

    fun getButtonBounds(): android.graphics.Rect? {
        return android.graphics.Rect(view.left, view.top, view.right, view.bottom)
    }

    fun doKeyDown() {
        a.viewModel.triggerHapticPress()
        a.viewModel.onKeyDown(keyCode)
    }
    fun doKeyUp() {
        a.viewModel.triggerHapticRelease()
        a.viewModel.onKeyUp(keyCode)
    }

    view.setOnTouchListener { v, event ->
        val masked = event.action and android.view.MotionEvent.ACTION_MASK
        when (masked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                if (pos.swipeTrigger) {
                    swipeActive[pos.id] = true
                    doKeyDown()
                } else if (pos.autoHold) {
                    val oldHeld = holdState[pos.id] == true
                    if (oldHeld) {
                        holdState[pos.id] = false
                        doKeyUp()
                    } else {
                        holdState[pos.id] = true
                        doKeyDown()
                    }
                } else {
                    doKeyDown()
                }
                true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (pos.swipeTrigger) {
                    val bounds = getButtonBounds()
                    if (bounds != null) {
                        val x = event.x
                        val y = event.y
                        val shouldPress = x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
                        val wasActive = swipeActive[pos.id] == true
                        if (shouldPress && !wasActive) {
                            swipeActive[pos.id] = true
                            doKeyDown()
                            v.isPressed = true
                        } else if (!shouldPress && wasActive) {
                            swipeActive[pos.id] = false
                            doKeyUp()
                            v.isPressed = false
                        }
                    }
                    true
                } else {
                    true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (pos.swipeTrigger) {
                    swipeActive.remove(pos.id)?.let { wasActive ->
                        if (wasActive) doKeyUp()
                    }
                } else if (pos.autoHold) {
                    if (holdState[pos.id] == true) {
                        v.isPressed = true
                    } else {
                        v.isPressed = false
                        holdState.remove(pos.id)
                    }
                } else {
                    doKeyUp()
                }
                true
            }
            else -> true
        }
    }
}

internal fun MainActivity.applyPreset(preset: com.zyz4.gamepademu.model.LayoutPreset) {
    gamepadLayout.loadPreset(preset)
    ensureViewsForAllPresetButtons()
}

internal fun MainActivity.ensureViewsForAllPresetButtons() {
    val a = this
    val buttons = a.gamepadLayout.currentButtons
    val triggerBaseIds = setOf("btnLT", "btnRT")

    // Rebuild all views from scratch to ensure correct view types
    for (i in (a.gamepadLayout.childCount - 1) downTo 0) {
        val child = a.gamepadLayout.getChildAt(i)
        val tag = child.tag as? String ?: continue
        if (tag == GamepadLayout.SETTINGS_BUTTON_ID) continue
        a.gamepadLayout.removeViewAt(i)
    }

    for (pos in buttons) {
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
    val maxSuffix = a.gamepadLayout.currentButtons
        .filter { it.id.startsWith("customKeypad_") }
        .mapNotNull { pos -> parseKeypadSuffix(pos.id) }
        .maxOrNull()
    a.addCounter = maxOf(0, (maxSuffix ?: 0) + 1)
}

private fun parseKeypadSuffix(id: String): Int? = try {
    id.substring(id.lastIndexOf('_') + 1).toInt()
} catch (e: Exception) { null }

internal fun MainActivity.createStandardControlView(pos: ButtonPosition) {
    val a = this
    val baseId = pos.id.substringBefore("_")
    val entry = allControls.find { it.baseId == baseId } ?: return

    val triggerBaseIds = setOf("btnLT", "btnRT")
    val view: View = when {
        pos.linearTriggerEnabled && baseId in triggerBaseIds -> {
            com.zyz4.gamepademu.view.LinearTriggerView(a).apply {
                id = View.generateViewId(); tag = pos.id
                text = "LT"
                this.slideDirection = pos.slideDirection
                this.travelDistance = pos.travelDistance
                this.idleTransparency = pos.idleTransparency
                this.activeTransparency = pos.activeTransparency
                onValueChange = { value ->
                    if (baseId == "btnLT") {
                        viewModel.onLeftTrigger(value)
                    } else {
                        viewModel.onRightTrigger(value)
                    }
                }
                onButtonDown = {
                    if (baseId == "btnLT") {
                        viewModel.onButtonDown(GamepadState.LT)
                    } else {
                        viewModel.onButtonDown(GamepadState.RT)
                    }
                }
                onButtonUp = {
                    if (baseId == "btnLT") {
                        viewModel.onButtonUp(GamepadState.LT)
                    } else {
                        viewModel.onButtonUp(GamepadState.RT)
                    }
                }
                onTriggerBottomVibrate = {
                    a.performHaptic(isPress = true)
                }
            }
        }
        entry.useImageButton -> ImageButton(a).apply {
            id = View.generateViewId(); tag = pos.id
            setBackgroundResource(entry.bgRes)
            setImageResource(entry.icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        entry.isJoystick -> JoystickView(a).apply {
            id = View.generateViewId(); tag = pos.id
            val isLeft = baseId == "leftJoystick"
            val clickBit = if (isLeft) GamepadState.L3 else GamepadState.R3
            label = if (isLeft) "L" else "R"
            onStickClickDown = { a.viewModel.onButtonDown(clickBit); a.viewModel.triggerHapticPress() }
            onStickClickUp = { a.viewModel.onButtonUp(clickBit); a.viewModel.triggerHapticRelease() }
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
        entry.isMousepad -> {
            val mp = FrameLayout(a).apply {
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
            mp.addView(label)
            a.mousepadLabels.add(label)
            a.setupMousepadView(mp)
            mp
        }
        entry.isDpadPad -> a.createDpadPadView(pos.id)
        entry.isKeypad -> a.createCustomKeypadView(pos.id)
        entry.isKeyboard -> {
            val btn = if (!entry.lockAspect) RotatableButton(a) else Button(a)
            btn.apply {
                id = View.generateViewId(); tag = pos.id
                text = entry.name
                setAllCaps(false)
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.button_rounded_rect)
                gravity = Gravity.CENTER
                enableAutoFitButtonText(20f)
            }
            btn
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
                enableAutoFitButtonText(20f)
            }
        }
        baseId.startsWith("btnMouse") -> Button(a).apply {
            id = View.generateViewId(); tag = pos.id
            text = pos.customText ?: entry.name
            setAllCaps(false)
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.button_rounded_rect)
            gravity = Gravity.CENTER
            enableAutoFitButtonText(20f)
        }
        !entry.lockAspect -> RotatableButton(a).apply {
            id = View.generateViewId(); tag = pos.id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
            enableAutoFitButtonText(20f)
        }
        else -> Button(a).apply {
            id = View.generateViewId(); tag = pos.id
            setTextColor(-0x333334); textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry.bgRes)
            gravity = Gravity.CENTER
            enableAutoFitButtonText(20f)
        }
    }

    if (!entry.isTouchpad && !entry.isMousepad && !entry.isCustom && !entry.isDpadPad && !entry.isKeypad && !entry.isKeyboard) {
        val bit = a.getBitForEntry(entry) ?: 0
        val isTrigger = baseId in triggerBaseIds
        if (pos.linearTriggerEnabled && isTrigger) {
            // LinearTriggerView handles its own touch events
        } else {
            a.setupTouchHandler(view, bit, entry.isDpad, entry.isTrigger && !pos.linearTriggerEnabled, entry.isJoystick)
        }
    } else if (entry.isCustom) {
        a.setupCustomTouchHandler(view)
    } else if (entry.isKeyboard) {
        a.setupKeyboardTouchHandler(view, entry.keyboardKeyCode, pos)
    } else if (pos.linearTriggerEnabled && baseId in triggerBaseIds) {
        // LinearTriggerView handles its own touch events
    }
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
        "btnMouseLMB" -> GamepadState.MOUSE_LMB
        "btnMouseRMB" -> GamepadState.MOUSE_RMB
        "btnMouseMMB" -> GamepadState.MOUSE_MMB
        else -> if (entry.bit != 0) entry.bit else null
    }
}

internal fun MainActivity.showOutputValuePicker(currentBits: List<Int>, onResult: (List<Int>) -> Unit) {
    val a = this
    a.outputPickerDialog?.dismiss()
    val density = a.resources.displayMetrics.density
    val cols = 6
    val iconSize = (48f * density).toInt()
    val mode = a.viewModel.settings.value.displayMode
    val cellW = (400f * density).toInt()

    val gamepadBits = allControls.filter { !it.baseId.startsWith("btnMouse") && it.baseId != "mousepad" && it.baseId != "btnCustomCircle" && it.baseId != "btnCustomRect" && it.baseId != "customKeypad" && !it.isJoystick && !it.isTouchpad && !it.isMousepad }
    val mouseBits = listOf("btnMouseLMB", "btnMouseRMB", "btnMouseMMB")
    val customBitsControls = allControls.filter { it.baseId == "btnCustomCircle" || it.baseId == "btnCustomRect" }

    fun getBit(entry: CtrlEntry): Int? {
        return a.getBitForEntry(entry)
    }

    fun createBitView(entry: CtrlEntry, alreadySelected: Boolean): View {
        val bit = getBit(entry) ?: return View(a).apply { layoutParams = LinearLayout.LayoutParams(0, 0) }
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
        val labelText = when (entry.baseId) {
            "btnMouseLMB" -> "鼠标左键"
            "btnMouseRMB" -> "鼠标右键"
            "btnMouseMMB" -> "鼠标中键"
            else -> entry.name
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
        } else if (entry.baseId.startsWith("btnMouse")) {
val btn = Button(a).apply {
                    this.text = text
                    setTextColor(-0x333334)
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(if (entry.baseId.startsWith("btnMouse")) R.drawable.button_rounded_rect else a.getPreviewIcon(entry, mode))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    isClickable = false
                }
            wrapper.addView(btn)
        } else {
            val iv = ImageView(a).apply {
                setImageResource(a.getPreviewIcon(entry, mode))
                setBackgroundResource(R.drawable.button_circle)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            wrapper.addView(iv)
        }
        val label = TextView(a)
        label.text = labelText
        label.setTextColor(-0x333334)
        label.textSize = 10f
        label.gravity = Gravity.CENTER
        wrapper.addView(label)
        return wrapper
    }

    fun buildBitGrid(controls: List<CtrlEntry>, selectedBits: List<Int>): View {
        val grid = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt())
        }
        controls.chunked(cols).forEach { rowItems ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowItems.forEach { entry ->
                val bit = getBit(entry) ?: return@forEach
                val alreadySelected = bit in selectedBits
                row.addView(createBitView(entry, alreadySelected), LinearLayout.LayoutParams(cellW / cols, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (rowItems.indexOf(entry) < rowItems.lastIndex) rightMargin = (6f * density).toInt()
                })
            }
            grid.addView(row)
        }
        return grid
    }

    val content = NestedScrollView(a)
    val contentLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12f * density).toInt(), 0, (12f * density).toInt(), (12f * density).toInt())
    }

    val tabRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gravity = Gravity.CENTER
    }

    var selectedIndex = 0
    val tabLabels = arrayOf("手柄", "鼠标", "自定义")

    // Page container - defined before updateTabs so the lambda can reference it
    val pageContainer = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
    }

    // Pre-build pages first
    val mouseControls = listOfNotNull(
        allControls.find { it.baseId == "btnMouseLMB" },
        allControls.find { it.baseId == "btnMouseRMB" },
        allControls.find { it.baseId == "btnMouseMMB" },
        allControls.find { it.baseId == "mousepad" }
    )
    val actualDatas = arrayOf(gamepadBits, mouseControls, customBitsControls)
    val pageViews = arrayOfNulls<View>(3)
    for (i in actualDatas.indices) {
        pageViews[i] = buildBitGrid(actualDatas[i], currentBits)
    }
    pageViews[0]?.let { pageContainer.addView(it) }

    fun updateTabs() {
        tabRow.removeAllViews()
        for (i in tabLabels.indices) {
            val tab = TextView(a).apply {
                text = tabLabels[i]
                setTextColor(if (i == selectedIndex) -0x1 else -0x666667)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (i == selectedIndex) R.drawable.bg_chip_selected else R.drawable.bg_chip)
                setPadding((16f * density).toInt(), (6f * density).toInt(), (16f * density).toInt(), (6f * density).toInt())
                setOnClickListener {
                    if (it != this) return@setOnClickListener
                    selectedIndex = i
                    pageContainer.removeAllViews()
                    pageViews[i]?.let { pageContainer.addView(it) }
                    // Re-render tabs to update selected style
                    updateTabs()
                }
            }
            tabRow.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < tabLabels.lastIndex) rightMargin = (8f * density).toInt()
            })
        }
    }

    updateTabs()
    contentLayout.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (8f * density).toInt()
        bottomMargin = (8f * density).toInt()
    })

    contentLayout.addView(pageContainer)
    content.addView(contentLayout)

    val dialogW = minOf((a.resources.displayMetrics.widthPixels * 0.85f).toInt(), (700f * density).toInt())
    a.outputPickerDialog = CustomDialog.showCustomView(a, "选择映射键值", content, dialogW, negativeText = "取消", scrollable = true)
}

internal fun MainActivity.recreateViewForButton(buttonId: String, pos: ButtonPosition) {
    val a = this
    var viewIndex = -1
    var existingView: View? = null
    for (i in 0 until a.gamepadLayout.childCount) {
        val child = a.gamepadLayout.getChildAt(i)
        if (child.tag as? String == buttonId) {
            viewIndex = i
            existingView = child
            break
        }
    }
    if (existingView == null) return
    a.gamepadLayout.removeViewAt(viewIndex)

    val entry = allControls.find { it.baseId == pos.id.substringBefore("_") }
    val isTriggerBase = setOf("btnLT", "btnRT").contains(pos.id.substringBefore("_"))
    val newView: View = if (pos.linearTriggerEnabled && isTriggerBase) {
        com.zyz4.gamepademu.view.LinearTriggerView(a).apply {
            id = View.generateViewId()
            tag = buttonId
            text = entry?.name ?: "LT"
            setTextColor(-0x333334)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            slideDirection = pos.slideDirection
            travelDistance = pos.travelDistance
            idleTransparency = pos.idleTransparency
            activeTransparency = pos.activeTransparency
            onValueChange = { value ->
                val baseId = pos.id.substringBefore("_")
                if (baseId == "btnLT") {
                    viewModel.onLeftTrigger(value)
                } else {
                    viewModel.onRightTrigger(value)
                }
            }
            onTriggerBottomVibrate = {
                a.performHaptic(isPress = true)
            }
        }
    } else {
        val lockAspect = entry?.lockAspect ?: true
        val customText = pos.customText
        val buttonView = if (!lockAspect) RotatableButton(a) else Button(a)
        buttonView.apply {
            id = View.generateViewId()
            tag = buttonId
            setTextColor(-0x333334)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(entry?.bgRes ?: R.drawable.button_circle)
            gravity = Gravity.CENTER
            enableAutoFitButtonText(20f)
            text = customText ?: entry?.name ?: "按钮"
        }
        val bit = entry?.bit ?: 0
        val isDpad = entry?.isDpad ?: false
        val isTrigger = entry?.isTrigger ?: false
        val isJoystick = entry?.isJoystick ?: false
        a.setupTouchHandler(buttonView, bit, isDpad, isTrigger, isJoystick)
        buttonView
    }

    a.gamepadLayout.addView(newView, viewIndex)
    a.gamepadLayout.applyAppearance(a.viewModel.settings.value)
}
