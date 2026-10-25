package com.zyz4.gkme.view

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.core.widget.NestedScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.zyz4.gkme.R
import com.zyz4.gkme.easeOutQuint
import com.zyz4.gkme.input.SensitivityCurve
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.model.CenterShape
import com.zyz4.gkme.model.ComboTiming
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.MouseGestureAction
import com.zyz4.gkme.model.SlideDirection
import com.zyz4.gkme.Kb
import com.zyz4.gkme.BitNameMapper

class FloatingEditorPanel(context: Context) : FrameLayout(context) {

    interface EditorListener {
        fun onSave()
        fun onDiscard()
        fun onAddButton()
        fun onDeleteButton(buttonId: String)
        fun onButtonUpdated(buttonId: String, updated: ButtonPosition)
        fun onPickOutputValues(buttonId: String, currentBits: List<Int>, onResult: (List<Int>) -> Unit)
        fun onEnterFollowAreaAdjust(buttonId: String)
        fun onExitFollowAreaAdjust()
        fun onOpacityPreviewStart(buttonId: String, isIdle: Boolean)
        fun onOpacityPreviewEnd(buttonId: String)
        fun onEnterGlobalGyroSettings()
        fun onExitGlobalGyroSettings()
        fun onEnlargeCurve(curve: List<Float>?, onChanged: (List<Float>?) -> Unit)
    }

    var editorListener: EditorListener? = null

    /** Invoked when the panel is collapsed/expanded via the header toggle button. */
    var onToggleCollapsed: ((collapsed: Boolean) -> Unit)? = null

    private var collapsed = false
    private var expandedHeight = 0
    private var headerView: View? = null
    private var scrollView: View? = null
    private var toggleBtn: ImageButton? = null

    /** True while the full-screen layout global settings page is open. */
    var showingGlobalSettings = false

    var currentSettings: com.zyz4.gkme.model.AppSettings? = null

    fun restoreFromSettings(settings: com.zyz4.gkme.model.AppSettings) {
        currentSettings = settings
    }

    private val BUTTON_IDS = setOf(
        "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
        "btnY", "btnA", "btnX", "btnB",
        "btnLT", "btnLB", "btnRT", "btnRB",
        "btnSelect", "btnHome", "btnMenu",
        "btnShare", "btnLS", "btnRS", "btnMic",
        "btnMouseLMB", "btnMouseRMB", "btnMouseMMB", "btnMouseBack", "btnMouseForward",
    )

    private val KEYBOARD_IDS = setOf(
        "kbLCtrl", "kbLShift", "kbLAlt", "kbLWin",
        "kbRCtrl", "kbRShift", "kbRAlt", "kbRGui",
        "kbQ", "kbW", "kbE", "kbR", "kbT", "kbY", "kbU", "kbI", "kbO", "kbP",
        "kbA", "kbS", "kbD", "kbF", "kbG", "kbH", "kbJ", "kbK", "kbL",
        "kbZ", "kbX", "kbC", "kbV", "kbB", "kbN", "kbM",
        "kb1", "kb2", "kb3", "kb4", "kb5", "kb6", "kb7", "kb8", "kb9", "kb0",
        "kbSpace", "kbEnter", "kbBackspace", "kbTab", "kbCaps",
        "kbEsc", "kbDelete", "kbMenu",
        "kbMinus", "kbEqual", "kbLBracket", "kbRBracket", "kbBackslash",
        "kbSemicolon", "kbApostrophe", "kbComma", "kbDot", "kbSlash", "kbGrave",
        "kbF1", "kbF2", "kbF3", "kbF4", "kbF5", "kbF6", "kbF7", "kbF8", "kbF9",
        "kbF10", "kbF11", "kbF12",
        "kbArrowUp", "kbArrowDown", "kbArrowLeft", "kbArrowRight",
    )

    private var currentButton: ButtonPosition? = null
    var isAdjustingFollowArea: Boolean = false
        set(value) {
            field = value
            updateActionButtonsVisibility()
            val vis = if (value) View.GONE else View.VISIBLE
            separatorLine?.visibility = vis
            buttonParamsInner?.visibility = vis
            if (value) {
                // When entering follow area adjust, show return button
                adjustingReturnButtonText = getControlName(currentButton?.id ?: "") + context.getString(R.string.i18n_adjust)
                btnGlobalSettings?.text = context.getString(R.string.i18n_back)
            } else {
                adjustingReturnButtonText = null
                btnGlobalSettings?.text = context.getString(R.string.i18n_global_settings)
            }
        }
    private var panelX = 0f
    private var panelY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false

    private lateinit var paramsContainer: LinearLayout
    private lateinit var buttonParamsHost: FrameLayout
    private lateinit var buttonParamsInner: LinearLayout
    private var actionBtnRow: LinearLayout? = null
    var btnSave: Button? = null
    var btnDiscard: Button? = null
    var btnAdd: Button? = null
    private var separatorLine: View? = null
    private var btnGlobalSettings: Button? = null
    private var adjustingReturnButtonText: String? = null
    private var contentW = 0
    private var panelW = 0

    /** True while a hide animation is pending; guards the end-action against a quick re-show. */
    private var hidingPanel = false

    /** Runs the collapse/expand height animation; cancelled when toggled again. */
    private var collapseAnimator: ValueAnimator? = null

    private companion object {
        const val PANEL_FADE_IN_MS = 200L
        const val PANEL_FADE_OUT_MS = 160L
        const val CONTENT_OUT_MS = 120L
        const val CONTENT_FADE_MS = 150L
        const val CONTENT_SLIDE_DP = 16f
        const val COLLAPSE_DURATION_MS = 200L
    }

    private fun isButton(id: String): Boolean {
        val base = id.substringBefore("_")
        return base in BUTTON_IDS || base.startsWith("btnCustom")
    }

    private fun isSettingsButton(id: String): Boolean = id == GamepadLayout.SETTINGS_BUTTON_ID

    private fun isTouchpadId(id: String): Boolean = id.substringBefore("_") == "touchpad"
    private fun isMousepadId(id: String): Boolean = id.substringBefore("_") == "mousepad"
    private fun isKeyboardId(id: String): Boolean = id.substringBefore("_") in KEYBOARD_IDS

    fun updateActionButtonsVisibility() {
        val hideSaveDiscardAdd = showingGlobalSettings || isAdjustingFollowArea
        btnSave?.visibility = if (hideSaveDiscardAdd) View.GONE else View.VISIBLE
        btnDiscard?.visibility = if (hideSaveDiscardAdd) View.GONE else View.VISIBLE
        btnAdd?.visibility = if (hideSaveDiscardAdd) View.GONE else View.VISIBLE
        btnGlobalSettings?.visibility = View.VISIBLE
    }

    /** On-screen size of the touchpad in grid units (accounts for 90/270 rotation swap). */
    private fun touchpadScreenSize(pos: ButtonPosition): Pair<Int, Int> {
        val swapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        return if (swapped) pos.height to pos.width else pos.width to pos.height
    }

    /** True when the touchpad control is fully inside the extended range rectangle. */
    private fun touchpadContained(pos: ButtonPosition): Boolean {
        if (!pos.followAreaEnabled || pos.followAreaW <= 0 || pos.followAreaH <= 0) return false
        val (sw, sh) = touchpadScreenSize(pos)
        return pos.x >= pos.followAreaX && pos.y >= pos.followAreaY &&
            pos.x + sw <= pos.followAreaX + pos.followAreaW &&
            pos.y + sh <= pos.followAreaY + pos.followAreaH
    }

    /** Shrinks the touchpad so it fits inside the extended range rectangle. */
    private fun shrinkTouchpadToArea(pos: ButtonPosition): ButtonPosition {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return pos
        val (sw, sh) = touchpadScreenSize(pos)
        val nw = minOf(sw, (pos.followAreaX + pos.followAreaW - pos.x).coerceAtLeast(1))
        val nh = minOf(sh, (pos.followAreaY + pos.followAreaH - pos.y).coerceAtLeast(1))
        if (nw == sw && nh == sh) return pos
        val swapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val width = if (swapped) nh else nw
        val height = if (swapped) nw else nh
        return pos.copy(width = width, height = height)
    }

    /** Grows the extended range rectangle so it contains the touchpad. */
    private fun growAreaToContain(pos: ButtonPosition): ButtonPosition {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return pos
        val (sw, sh) = touchpadScreenSize(pos)
        val newW = maxOf(pos.followAreaW, pos.x + sw - pos.followAreaX)
        val newH = maxOf(pos.followAreaH, pos.y + sh - pos.followAreaY)
        if (newW == pos.followAreaW && newH == pos.followAreaH) return pos
        return pos.copy(followAreaW = newW, followAreaH = newH)
    }

    private fun getControlName(buttonId: String): String {
        val base = buttonId.substringBefore("_")
        return when (base) {
            "btnDpadUp" -> context.getString(R.string.i18n_up_3f99c7)
            "btnDpadDown" -> context.getString(R.string.i18n_down_c761f5)
            "btnDpadLeft" -> context.getString(R.string.i18n_left_3e0a82)
            "btnDpadRight" -> context.getString(R.string.i18n_right_b45922)
            "btnY" -> context.getString(R.string.i18n_gamepad_y)
            "btnA" -> context.getString(R.string.i18n_gamepad_a)
            "btnX" -> context.getString(R.string.i18n_gamepad_x)
            "btnB" -> context.getString(R.string.i18n_gamepad_b)
            "btnLT" -> context.getString(R.string.i18n_gamepad_left_trigger)
            "btnLB" -> context.getString(R.string.i18n_gamepad_left_bumper)
            "btnRT" -> context.getString(R.string.i18n_gamepad_right_trigger)
            "btnRB" -> context.getString(R.string.i18n_gamepad_right_bumper)
            "leftJoystick" -> context.getString(R.string.i18n_gamepad_left_stick)
            "rightJoystick" -> context.getString(R.string.i18n_gamepad_right_stick)
            "btnSelect" -> context.getString(R.string.i18n_gamepad_select)
            "btnHome" -> context.getString(R.string.i18n_gamepad_home)
            "btnMenu" -> context.getString(R.string.i18n_gamepad_menu)
            "btnShare" -> context.getString(R.string.i18n_gamepad_share)
            "btnLS" -> context.getString(R.string.i18n_gamepad_left_stick_click)
            "btnRS" -> context.getString(R.string.i18n_gamepad_right_stick_click)
            "touchpad" -> context.getString(R.string.i18n_touchpad_gamepad)
            "mousepad" -> context.getString(R.string.i18n_touchpad_mouse)
            "dpadPad" -> context.getString(R.string.i18n_integrated_d_pad)
            "customKeypad" -> context.getString(R.string.i18n_custom_keypad)
            "btnCustomCircle" -> context.getString(R.string.i18n_custom_circle)
            "btnCustomRect" -> context.getString(R.string.i18n_custom_rectangle)
            "btnMic" -> context.getString(R.string.i18n_mic_mute)
            "btnMouseLMB" -> context.getString(R.string.i18n_mouse_left_button)
            "btnMouseRMB" -> context.getString(R.string.i18n_mouse_right_button)
            "btnMouseMMB" -> context.getString(R.string.i18n_mouse_middle_button)
            "btnMouseBack" -> context.getString(R.string.i18n_mouse_back)
            "btnMouseForward" -> context.getString(R.string.i18n_mouse_forward)
            "btnSettings" -> context.getString(R.string.i18n_settings_button)
            "btn" -> context.getString(R.string.i18n_button)
            "joystick" -> context.getString(R.string.i18n_stick)
            "kbArrowUp" -> context.getString(R.string.i18n_keyboard_07bb03)
            "kbArrowDown" -> context.getString(R.string.i18n_keyboard_7484ad)
            "kbArrowLeft" -> context.getString(R.string.i18n_keyboard_d4a2fd)
            "kbArrowRight" -> context.getString(R.string.i18n_keyboard_e013ce)
            "kbLCtrl" -> context.getString(R.string.i18n_keyboard_left_ctrl)
            "kbLShift" -> context.getString(R.string.i18n_keyboard_left_shift)
            "kbLAlt" -> context.getString(R.string.i18n_keyboard_left_alt)
            "kbLWin" -> context.getString(R.string.i18n_keyboard_win)
            "kbRCtrl" -> context.getString(R.string.i18n_keyboard_right_ctrl)
            "kbRShift" -> context.getString(R.string.i18n_keyboard_right_shift)
            "kbRAlt" -> context.getString(R.string.i18n_keyboard_altgr)
            "kbRGui" -> context.getString(R.string.i18n_keyboard_right_win)
            "kbQ" -> context.getString(R.string.i18n_keyboard_q)
            "kbW" -> context.getString(R.string.i18n_keyboard_w)
            "kbE" -> context.getString(R.string.i18n_keyboard_e)
            "kbR" -> context.getString(R.string.i18n_keyboard_r)
            "kbT" -> context.getString(R.string.i18n_keyboard_t)
            "kbY" -> context.getString(R.string.i18n_keyboard_y)
            "kbU" -> context.getString(R.string.i18n_keyboard_u)
            "kbI" -> context.getString(R.string.i18n_keyboard_i)
            "kbO" -> context.getString(R.string.i18n_keyboard_o)
            "kbP" -> context.getString(R.string.i18n_keyboard_p)
            "kbA" -> context.getString(R.string.i18n_keyboard_a)
            "kbS" -> context.getString(R.string.i18n_keyboard_s)
            "kbD" -> context.getString(R.string.i18n_keyboard_d)
            "kbF" -> context.getString(R.string.i18n_keyboard_f)
            "kbG" -> context.getString(R.string.i18n_keyboard_g)
            "kbH" -> context.getString(R.string.i18n_keyboard_h)
            "kbJ" -> context.getString(R.string.i18n_keyboard_j)
            "kbK" -> context.getString(R.string.i18n_keyboard_k)
            "kbL" -> context.getString(R.string.i18n_keyboard_l)
            "kbZ" -> context.getString(R.string.i18n_keyboard_z)
            "kbX" -> context.getString(R.string.i18n_keyboard_x)
            "kbC" -> context.getString(R.string.i18n_keyboard_c)
            "kbV" -> context.getString(R.string.i18n_keyboard_v)
            "kbB" -> context.getString(R.string.i18n_keyboard_b)
            "kbN" -> context.getString(R.string.i18n_keyboard_n)
            "kbM" -> context.getString(R.string.i18n_keyboard_m)
            "kb1" -> context.getString(R.string.i18n_keyboard_1)
            "kb2" -> context.getString(R.string.i18n_keyboard_2)
            "kb3" -> context.getString(R.string.i18n_keyboard_3)
            "kb4" -> context.getString(R.string.i18n_keyboard_4)
            "kb5" -> context.getString(R.string.i18n_keyboard_5)
            "kb6" -> context.getString(R.string.i18n_keyboard_6)
            "kb7" -> context.getString(R.string.i18n_keyboard_7)
            "kb8" -> context.getString(R.string.i18n_keyboard_8)
            "kb9" -> context.getString(R.string.i18n_keyboard_9)
            "kb0" -> context.getString(R.string.i18n_keyboard_0)
            "kbSpace" -> context.getString(R.string.i18n_keyboard_space)
            "kbEnter" -> context.getString(R.string.i18n_keyboard_enter)
            "kbBackspace" -> context.getString(R.string.i18n_keyboard_backspace)
            "kbTab" -> context.getString(R.string.i18n_keyboard_tab)
            "kbCaps" -> context.getString(R.string.i18n_keyboard_caps_lock)
            "kbEsc" -> context.getString(R.string.i18n_keyboard_esc)
            "kbDelete" -> context.getString(R.string.i18n_keyboard_delete)
            "kbMenu" -> context.getString(R.string.i18n_keyboard_menu)
            "kbMinus" -> context.getString(R.string.i18n_keyboard_743bab)
            "kbEqual" -> context.getString(R.string.i18n_keyboard_ea7f6d)
            "kbLBracket" -> context.getString(R.string.i18n_keyboard_47e933)
            "kbRBracket" -> context.getString(R.string.i18n_keyboard_75eb76)
            "kbBackslash" -> context.getString(R.string.i18n_keyboard_0046e9)
            "kbSemicolon" -> context.getString(R.string.i18n_keyboard_a77d53)
            "kbApostrophe" -> context.getString(R.string.i18n_keyboard_c15dcd)
            "kbComma" -> context.getString(R.string.i18n_keyboard_6adcde)
            "kbDot" -> context.getString(R.string.i18n_keyboard_41fe99)
            "kbSlash" -> context.getString(R.string.i18n_keyboard_072e9b)
            "kbGrave" -> context.getString(R.string.i18n_keyboard_06d5f0)
            "kbF1" -> context.getString(R.string.i18n_keyboard_f1)
            "kbF2" -> context.getString(R.string.i18n_keyboard_f2)
            "kbF3" -> context.getString(R.string.i18n_keyboard_f3)
            "kbF4" -> context.getString(R.string.i18n_keyboard_f4)
            "kbF5" -> context.getString(R.string.i18n_keyboard_f5)
            "kbF6" -> context.getString(R.string.i18n_keyboard_f6)
            "kbF7" -> context.getString(R.string.i18n_keyboard_f7)
            "kbF8" -> context.getString(R.string.i18n_keyboard_f8)
            "kbF9" -> context.getString(R.string.i18n_keyboard_f9)
            "kbF10" -> context.getString(R.string.i18n_keyboard_f10)
            "kbF11" -> context.getString(R.string.i18n_keyboard_f11)
            "kbF12" -> context.getString(R.string.i18n_keyboard_f12)
            else -> buttonId
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    init {
        val density = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        panelW = (screenW * 0.4f).toInt()
        val panelH = (screenH * 0.8f).toInt()
        panelX = screenW - panelW - (12f * density)
        panelY = ((screenH - panelH) / 2f).coerceAtLeast(12f * density)

        setPadding((4f * density).toInt(), (4f * density).toInt(), (4f * density).toInt(), (4f * density).toInt())
        background = GradientDrawable().apply {
            setColor(-0x33E5E5E6)
            setStroke(Math.round(1f * density), -0x666667)
        }

        buildContent()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val lp = layoutParams as FrameLayout.LayoutParams
        lp.width = panelW
        expandedHeight = (context.resources.displayMetrics.heightPixels * 0.8f).toInt()
        lp.height = expandedHeight
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        translationX = panelX
        translationY = panelY
    }

    private fun buildContent() {
        removeAllViews()
        val density = context.resources.displayMetrics.density
        contentW = panelW - paddingLeft - paddingRight

        val root = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(contentW, ViewGroup.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(contentW, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener { _, event -> handleDrag(event); true }
        }
        val gripBar = buildGripBar(density)
        header.addView(gripBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val btnToggle = ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_up)
            setBackgroundResource(R.drawable.bg_small_btn)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                (6f * density).toInt(), (6f * density).toInt(),
                (6f * density).toInt(), (6f * density).toInt()
            )
            contentDescription = context.getString(R.string.i18n_collapse_expand_panel)
            setOnClickListener { toggleCollapsed() }
        }
        toggleBtn = btnToggle
        header.addView(btnToggle, LinearLayout.LayoutParams((34f * density).toInt(), (34f * density).toInt()).apply {
            leftMargin = (4f * density).toInt()
        })
        headerView = header
        root.addView(header)

        val scroll = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(contentW, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }
        scrollView = scroll
        paramsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8f * density).toInt(), (12f * density).toInt(), (8f * density).toInt(), (12f * density).toInt())
        }

        // Persistent action buttons
        val btnRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
        }
        actionBtnRow = btnRow
        val btnSpacing = (4f * density).toInt()
        val btnSave = EditorButton(context).apply {
            text = context.getString(R.string.i18n_save)
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { editorListener?.onSave() }
        }
        val btnDiscard = EditorButton(context).apply {
            text = context.getString(R.string.i18n_discard)
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { editorListener?.onDiscard() }
        }
        val btnAdd = EditorButton(context).apply {
            text = context.getString(R.string.i18n_add)
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { editorListener?.onAddButton() }
        }
        this.btnSave = btnSave
        this.btnDiscard = btnDiscard
        this.btnAdd = btnAdd
        btnGlobalSettings = EditorButton(context).apply {
            text = context.getString(R.string.i18n_global_settings)
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                if (isAdjustingFollowArea) {
                    // Return from follow area adjust
                    editorListener?.onExitFollowAreaAdjust()
                } else {
                    currentButton = null
                    editorListener?.onEnterGlobalGyroSettings()
                    updateActionButtonsVisibility()
                }
            }
        }
        btnRow.addView(btnSave, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = btnSpacing })
        btnRow.addView(btnDiscard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = btnSpacing })
        btnRow.addView(btnAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = btnSpacing })
        btnRow.addView(btnGlobalSettings, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        paramsContainer.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

        // Gyro orientation selector (moved into global settings)
        // Separator
        val sep = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1f * density).toInt()).apply {
                bottomMargin = (8f * density).toInt()
            }
            background = GradientDrawable().apply { setColor(-0x444445) }
        }
        separatorLine = sep
        paramsContainer.addView(sep)

        // Button-specific params. The host stays put; each rebuild swaps in a fresh content
        // view so an outgoing view can be animated at the same time as the incoming one.
        buttonParamsHost = FrameLayout(context)
        buttonParamsInner = createParamsContent()
        buttonParamsHost.addView(buttonParamsInner)
        paramsContainer.addView(buttonParamsHost)

        scroll.addView(paramsContainer)
        root.addView(scroll)
        addView(root)
    }

    private fun createParamsContent(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun buildGripBar(density: Float): View {
        val bar = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(contentW, (34f * density).toInt())
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setOnTouchListener { _, event -> handleDrag(event); true }
        }
        val gap = (4f * density).toInt()
        for (i in 0 until 3) {
            val line = View(context).apply {
                layoutParams = LinearLayout.LayoutParams((20f * density).toInt(), (2f * density).toInt()).apply {
                    if (i < 2) bottomMargin = gap
                }
                background = GradientDrawable().apply {
                    setColor(-0x777778)
                    setShape(GradientDrawable.RECTANGLE)
                }
            }
            bar.addView(line)
        }
        return bar
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragStartX = event.rawX
                dragStartY = event.rawY
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    val pv = parent as View
                    panelX = (panelX + dx).coerceIn(0f, pv.width.toFloat() - width)
                    panelY = (panelY + dy).coerceIn(0f, pv.height.toFloat() - height)
                    translationX = panelX
                    translationY = panelY
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun toggleCollapsed() {
        setCollapsed(!collapsed)
    }

    /** Fades the whole panel in. Used when entering layout edit mode. */
    fun showAnimated(durationMs: Long = PANEL_FADE_IN_MS) {
        hidingPanel = false
        animate().cancel()
        alpha = 0f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .setDuration(durationMs)
            .setInterpolator(easeOutQuint())
            .start()
    }

    /** Fades the whole panel out, then hides it. Used when exiting layout edit mode. */
    fun hideAnimated(durationMs: Long = PANEL_FADE_OUT_MS) {
        hidingPanel = true
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(easeOutQuint())
            .withEndAction {
                if (hidingPanel) {
                    visibility = View.GONE
                    alpha = 1f
                }
            }
            .start()
    }

    /** Collapses the panel to show only the drag handle + toggle button. */
    fun setCollapsed(collapsed: Boolean) {
        if (this.collapsed == collapsed) return
        this.collapsed = collapsed
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        toggleBtn?.setImageResource(if (collapsed) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up)
        // Content itself does not animate; it is clipped by the shrinking/growing panel.
        // GONE is only applied after a collapse finishes so the measurement stays stable.
        if (!collapsed) scrollView?.visibility = View.VISIBLE
        collapseAnimator?.cancel()
        val animator = ValueAnimator.ofInt(lp.height, if (collapsed) collapsedHeight() else expandedHeight)
        animator.duration = COLLAPSE_DURATION_MS
        animator.interpolator = easeOutQuint()
        animator.addUpdateListener {
            lp.height = it.animatedValue as Int
            requestLayout()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (collapsed && collapseAnimator === animation) {
                    scrollView?.visibility = View.GONE
                }
            }
        })
        collapseAnimator = animator
        animator.start()
        onToggleCollapsed?.invoke(collapsed)
    }

    private fun collapsedHeight(): Int {
        val headerH = headerView?.height ?: 0
        return (if (headerH > 0) headerH else (36f * resources.displayMetrics.density).toInt()) +
            paddingTop + paddingBottom
    }

    fun clearParameters() {
        swapToEmptyParameters()
    }

    private fun swapToEmptyParameters() {
        val old = buttonParamsInner
        val fresh = createParamsContent()
        buttonParamsInner = fresh
        buttonParamsHost.addView(fresh)
        if (old !== fresh && old.parent === buttonParamsHost) buttonParamsHost.removeView(old)
    }

    /** Slides the parameters area up and fades it out. Used on deselect. */
    fun clearParametersAnimated() {
        val old = buttonParamsInner
        val fresh = createParamsContent()
        buttonParamsInner = fresh
        buttonParamsHost.addView(fresh)
        if (old.childCount == 0) {
            buttonParamsHost.removeView(old)
            return
        }
        val offset = CONTENT_SLIDE_DP * resources.displayMetrics.density
        old.animate()
            .translationY(-offset)
            .alpha(0f)
            .setDuration(CONTENT_OUT_MS)
            .setInterpolator(easeOutQuint())
            .withEndAction { if (old.parent === buttonParamsHost) buttonParamsHost.removeView(old) }
            .start()
    }

    /** Slides the old parameters up/out and the new ones up/in at the same time. Used when switching control. */
    fun showParametersAnimated(buttonId: String, button: ButtonPosition) {
        val old = buttonParamsInner
        val fresh = createParamsContent()
        buttonParamsInner = fresh
        buttonParamsHost.addView(fresh)
        populateParameterViews(buttonId, button)
        val offset = CONTENT_SLIDE_DP * resources.displayMetrics.density
        // Draw the outgoing content on top: for same-type controls the two layouts are nearly
        // identical, so otherwise the incoming view would cover the outgoing animation.
        if (old.childCount > 0) buttonParamsHost.bringChildToFront(old)
        fresh.translationY = offset
        fresh.alpha = 0f
        fresh.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(CONTENT_FADE_MS)
            .setInterpolator(easeOutQuint())
            .start()
        if (old.childCount == 0) {
            buttonParamsHost.removeView(old)
        } else {
            old.animate()
                .translationY(-offset)
                .alpha(0f)
                .setDuration(CONTENT_FADE_MS)
                .setInterpolator(easeOutQuint())
                .withEndAction { if (old.parent === buttonParamsHost) buttonParamsHost.removeView(old) }
                .start()
        }
    }

    fun showParameters(buttonId: String, button: ButtonPosition) {
        val old = buttonParamsInner
        val fresh = createParamsContent()
        buttonParamsInner = fresh
        buttonParamsHost.addView(fresh)
        populateParameterViews(buttonId, button)
        if (old !== fresh && old.parent === buttonParamsHost) buttonParamsHost.removeView(old)
    }

    private fun populateParameterViews(buttonId: String, button: ButtonPosition) {
        currentButton = button
        val density = context.resources.displayMetrics.density

        // Restore visibility when in follow area adjust mode
        if (isAdjustingFollowArea) {
            buttonParamsInner.visibility = View.VISIBLE
            separatorLine?.visibility = View.VISIBLE
        }

        val tvId = TextView(context).apply {
            text = getControlName(buttonId)
            setTextColor(-0x1)
            textSize = 16f
        }
        buttonParamsInner.addView(tvId, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

        if (isAdjustingFollowArea) {
            // Only show follow area dimensions + follow area opacity
            val touchpadAdjust = isTouchpadId(buttonId)
            val dpadPadAdjust = buttonId == "dpadPad"
            val keypadAdjust = ButtonPosition.isKeypad(buttonId)
            val maxAw = maxOf(40, button.followAreaW)
            val maxAh = maxOf(40, button.followAreaH)
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_area_width), button.followAreaW, 1, maxAw, onChange = { value ->
                var updated = currentButton?.copy(followAreaW = value) ?: return@addSeekbar
                if (touchpadAdjust) updated = shrinkTouchpadToArea(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_area_height), button.followAreaH, 1, maxAh, onChange = { value ->
                var updated = currentButton?.copy(followAreaH = value) ?: return@addSeekbar
                if (touchpadAdjust) updated = shrinkTouchpadToArea(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_follow_area_opacity), button.followAreaOpacity.coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    currentButton = currentButton?.copy(followAreaOpacity = value.coerceIn(0, 100))
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onOpacityPreviewStart(buttonId, true) },
                onStopTracking = { editorListener?.onOpacityPreviewEnd(buttonId) }
            )
            if (!touchpadAdjust) {
                val cbFollowOverlap = CheckBox(context).apply {
                    text = context.getString(R.string.i18n_trigger_when_rectangles_overlap)
                    setTextColor(-0x444445)
                    textSize = 14f
                    isChecked = button.followAreaOverlapTrigger
                    setOnCheckedChangeListener { _, isChecked ->
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(followAreaOverlapTrigger = isChecked)) }
                    }
                }
                buttonParamsInner.addView(cbFollowOverlap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
            }

            return
        }

        if (button.lockAspect) {
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_size), button.width, 1, 40, onChange = { value ->
                currentButton = currentButton?.copy(width = value, height = value)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
        } else {
            val isSwapped = button.rotation == 90 || button.rotation == 270
            // When the extended range is enabled, growing the touchpad grows the rectangle.
            val areaEnabled = button.followAreaEnabled && isTouchpadId(buttonId)
            val sizeMax = maxOf(40, GamepadLayout.GRID_COLS)
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_width), if (isSwapped) button.height else button.width, 1, sizeMax, onChange = { value ->
                var updated = currentButton?.let { if (isSwapped) it.copy(height = value) else it.copy(width = value) } ?: return@addSeekbar
                if (areaEnabled) updated = growAreaToContain(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_height), if (isSwapped) button.width else button.height, 1, sizeMax, onChange = { value ->
                var updated = currentButton?.let { if (isSwapped) it.copy(width = value) else it.copy(height = value) } ?: return@addSeekbar
                if (areaEnabled) updated = growAreaToContain(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
        }

        addRotationButtons(buttonParamsInner, buttonId, density, isSettingsButton(buttonId))

        // ── Opacity (hidden for settings button) ──
        if (!isSettingsButton(buttonId)) {
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_idle_opacity), button.idleOpacity.coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    currentButton = currentButton?.copy(idleOpacity = value.coerceIn(0, 100))
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onOpacityPreviewStart(buttonId, true) },
                onStopTracking = { editorListener?.onOpacityPreviewEnd(buttonId) }
            )
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_active_opacity), button.activeOpacity.coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    currentButton = currentButton?.copy(activeOpacity = value.coerceIn(0, 100))
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onOpacityPreviewStart(buttonId, false) },
                onStopTracking = { editorListener?.onOpacityPreviewEnd(buttonId) }
            )
        }

        // ── Overlap trigger for all controls ──
        if (!isSettingsButton(buttonId)) {
            val cbOverlap = CheckBox(context).apply {
                text = context.getString(R.string.i18n_overlap_trigger)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.overlapTrigger
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(overlapTrigger = isChecked)) }
                }
            }
            buttonParamsInner.addView(cbOverlap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
        }

        // ── Gyro activation for all controls ──
        if (!isSettingsButton(buttonId)) {
            val cbGyro = CheckBox(context).apply {
                text = context.getString(R.string.i18n_used_to_activate_the_gyro)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.gyroActivate
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(gyroActivate = isChecked)) }
                }
            }
            buttonParamsInner.addView(cbGyro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
        }

        // ── Auto hold for buttons ──
        if ((isButton(buttonId) || isKeyboardId(buttonId)) && !isSettingsButton(buttonId)) {
            val cbAutoHold = CheckBox(context).apply {
                text = context.getString(R.string.i18n_auto_hold)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.autoHold
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(autoHold = isChecked)) }
                }
            }
            buttonParamsInner.addView(cbAutoHold, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
        }

        val joystickOrTouchpadIds = setOf("leftJoystick", "rightJoystick", "touchpad")
        val joystickIds = setOf("leftJoystick", "rightJoystick")
        if (buttonId.substringBefore("_") in joystickOrTouchpadIds) {
            val cb = CheckBox(context).apply {
                text = context.getString(R.string.i18n_double_tap_press)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.doubleClickEnable
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(doubleClickEnable = isChecked)) }
                }
            }
            buttonParamsInner.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
        }
        if (isTouchpadId(buttonId)) {
            // ── Extended touch range ──
            val cbExtended = CheckBox(context).apply {
                text = context.getString(R.string.i18n_extended_touch_area_dac06c)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.followAreaEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    val current = currentButton ?: return@setOnCheckedChangeListener
                    val updated = if (isChecked) {
                        if (current.followAreaW > 0 && current.followAreaH > 0 && touchpadContained(current)) {
                            current.copy(followAreaEnabled = true)
                        } else {
                            // Initialize the rectangle to match the touchpad control
                            val (sw, sh) = touchpadScreenSize(current)
                            current.copy(
                                followAreaEnabled = true,
                                followAreaX = current.x,
                                followAreaY = current.y,
                                followAreaW = sw,
                                followAreaH = sh,
                            )
                        }
                    } else {
                        current.copy(followAreaEnabled = false)
                    }
                    currentButton = updated
                    editorListener?.onButtonUpdated(buttonId, updated)
                    showParameters(buttonId, updated)
                }
            }
            buttonParamsInner.addView(cbExtended, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

            if (button.followAreaEnabled) {
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt() }
                }
                val btnEnterAdjust = EditorButton(context).apply {
                    text = context.getString(R.string.i18n_adjust_d198bf)
                    setTextColor(-0x1)
                    textSize = 13f
                    setBackgroundResource(R.drawable.button_flat)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        editorListener?.onEnterFollowAreaAdjust(buttonId)
                    }
                }
                btnRow.addView(btnEnterAdjust)
                buttonParamsInner.addView(btnRow)
            }
        }
        if (isMousepadId(buttonId)) {
            // ── 手势动作配置（多个下拉选框）──
            addMouseActionSpinner(
                buttonParamsInner, context.getString(R.string.i18n_single_tap), MouseGestureAction.TAP_ACTIONS, button.singleTapAction,
            ) { action ->
                currentButton = currentButton?.copy(singleTapAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, context.getString(R.string.i18n_two_finger_tap), MouseGestureAction.TAP_ACTIONS, button.twoFingerTapAction,
            ) { action ->
                currentButton = currentButton?.copy(twoFingerTapAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, context.getString(R.string.i18n_three_finger_tap), MouseGestureAction.TAP_ACTIONS, button.threeFingerTapAction,
            ) { action ->
                currentButton = currentButton?.copy(threeFingerTapAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, context.getString(R.string.i18n_double_tap_then_drag), MouseGestureAction.SWIPE_ACTIONS, button.doubleTapDragAction,
            ) { action ->
                currentButton = currentButton?.copy(doubleTapDragAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, context.getString(R.string.i18n_one_finger_swipe), MouseGestureAction.SWIPE_ACTIONS, button.oneFingerSwipeAction,
            ) { action ->
                currentButton = currentButton?.copy(oneFingerSwipeAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, context.getString(R.string.i18n_two_finger_swipe), MouseGestureAction.SWIPE_ACTIONS, button.twoFingerSwipeAction,
            ) { action ->
                currentButton = currentButton?.copy(twoFingerSwipeAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, context.getString(R.string.i18n_three_finger_swipe), MouseGestureAction.SWIPE_ACTIONS, button.threeFingerSwipeAction,
            ) { action ->
                currentButton = currentButton?.copy(threeFingerSwipeAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbarFloat(buttonParamsInner, context.getString(R.string.i18n_mouse_sensitivity), button.mouseSensitivity, 0.1f, 3f, 0.05f) { v ->
                currentButton = currentButton?.copy(mouseSensitivity = v)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbar(buttonParamsInner, context.getString(R.string.i18n_swipe_threshold), button.mouseMoveSlop, 0, 20, onChange = { v ->
                currentButton = currentButton?.copy(mouseMoveSlop = v)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
            addSeekbarFloat(buttonParamsInner, context.getString(R.string.i18n_scroll_sensitivity), button.scrollSensitivity, 0.01f, 1f, 0.01f) { v ->
                currentButton = currentButton?.copy(scrollSensitivity = v)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbarFloat(buttonParamsInner, context.getString(R.string.i18n_pointer_acceleration), button.mouseAcceleration?.get(1) ?: 0f, 0f, 0.5f, 0.01f) { v ->
                currentButton = currentButton?.copy(mouseAcceleration = listOf(1f, v))
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            val cbInvert = CheckBox(context).apply {
                text = context.getString(R.string.i18n_invert_vertical_scroll)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.invertScrollV
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(invertScrollV = isChecked)) }
                }
            }
            buttonParamsInner.addView(cbInvert, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
            val cbInvertH = CheckBox(context).apply {
                text = context.getString(R.string.i18n_invert_horizontal_scroll)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.invertScrollH
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(invertScrollH = isChecked)) }
                }
            }
            buttonParamsInner.addView(cbInvertH, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
        }
        if (buttonId.substringBefore("_") in joystickIds) {
            // ── Rectangular area follow ──
            val cbFollowArea = CheckBox(context).apply {
                text = context.getString(R.string.i18n_follow_inside_rectangle)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.followAreaEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    val current = currentButton ?: return@setOnCheckedChangeListener
                    val updated = if (isChecked && current.followAreaW == 0) {
                        // Initialize follow area to match joystick position and size
                        current.copy(
                            followAreaEnabled = true,
                            followAreaX = current.x,
                            followAreaY = current.y,
                            followAreaW = current.width,
                            followAreaH = current.height
                        )
                    } else {
                        current.copy(followAreaEnabled = isChecked)
                    }
                    currentButton = updated
                    editorListener?.onButtonUpdated(buttonId, updated)
                    // Refresh UI to show/hide "进入调节" button immediately
                    showParameters(buttonId, updated)
                }
            }
            buttonParamsInner.addView(cbFollowArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

            if (button.followAreaEnabled) {
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt() }
                }
                val btnEnterAdjust = EditorButton(context).apply {
                    text = context.getString(R.string.i18n_adjust_d198bf)
                    setTextColor(-0x1)
                    textSize = 13f
                    setBackgroundResource(R.drawable.button_flat)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        editorListener?.onEnterFollowAreaAdjust(buttonId)
                    }
                }
                btnRow.addView(btnEnterAdjust)
                buttonParamsInner.addView(btnRow)
            }

            val cbTouchpadMode = CheckBox(context).apply {
                text = context.getString(R.string.i18n_touchpad_mode)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.touchpadMode
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton = currentButton?.copy(touchpadMode = isChecked)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                }
            }
            buttonParamsInner.addView(cbTouchpadMode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

            val cbPrediction = CheckBox(context).apply {
                text = context.getString(R.string.i18n_fast_response_mode)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.prediction
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton = currentButton?.copy(prediction = isChecked)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                }
            }
            buttonParamsInner.addView(cbPrediction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

            val curveH = (200f * density).toInt()

            val btnSensSeekbar = createSimpleSeekbar(context.getString(R.string.i18n_sensitivity_a072e4), button.joystickSensitivity, 10, 300, { value ->
                currentButton = currentButton?.copy(joystickSensitivity = value)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
            buttonParamsInner.addView(btnSensSeekbar)

            var resolvedDeadZone = button.deadZone
            var resolvedReverseDeadZone = button.reverseDeadZone

            val btnDeadZoneRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val btnDzSeekbar = createSimpleSeekbar(context.getString(R.string.i18n_dead_zone), button.deadZone, 0, 100, { value ->
                currentButton = currentButton?.copy(deadZone = value)
                resolvedDeadZone = value
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
            btnDeadZoneRow.addView(btnDzSeekbar)
            buttonParamsInner.addView(btnDeadZoneRow)

            val btnRdzRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val btnRdzSeekbar = createSimpleSeekbar(context.getString(R.string.i18n_anti_dead_zone), button.reverseDeadZone, 0, 100, { value ->
                currentButton = currentButton?.copy(reverseDeadZone = value)
                resolvedReverseDeadZone = value
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
            btnRdzRow.addView(btnRdzSeekbar)
            buttonParamsInner.addView(btnRdzRow)

            val tvCurve = TextView(context).apply {
                text = context.getString(R.string.i18n_sensitivity_curve)
                setTextColor(-0x444445)
                textSize = 13f
            }
            buttonParamsInner.addView(tvCurve, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10f * density).toInt(); bottomMargin = (4f * density).toInt() })

            val curvePresetLabels = listOf(context.getString(R.string.i18n_custom)) + SensitivityCurve.PRESETS.map { context.getString(it.labelRes) }
            val curvePresetSpinner = Spinner(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, curvePresetLabels).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(0)
            }
            buttonParamsInner.addView(curvePresetSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() })

            val curveView = CurveEditorView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, curveH)
                setFromFlatList(button.sensitivityCurve)
                onPointsChanged = { newList ->
                    currentButton?.let {
                        editorListener?.onButtonUpdated(buttonId, it.copy(sensitivityCurve = newList))
                    }
                    curvePresetSpinner.setSelection(0)
                }
            }
            buttonParamsInner.addView(curveView)

            curvePresetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position <= 0) return
                    val preset = SensitivityCurve.PRESETS[position - 1]
                    curveView.setFromFlatList(preset.points)
                    currentButton = currentButton?.copy(sensitivityCurve = preset.points)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            val curveBtnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val btnDeletePoint = EditorButton(context).apply {
                text = context.getString(R.string.i18n_delete_selected_point)
                setTextColor(-0x1)
                textSize = 12f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener { curveView.deleteSelected() }
            }
            val btnResetCurve = EditorButton(context).apply {
                text = context.getString(R.string.i18n_reset_to_straight_line)
                setTextColor(-0x1)
                textSize = 12f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener {
                    currentButton = currentButton?.copy(sensitivityCurve = null)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    curveView.setFromFlatList(null)
                }
            }
            val btnEnlargeCurve = EditorButton(context).apply {
                text = context.getString(R.string.i18n_zoom_in)
                setTextColor(-0x1)
                textSize = 12f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener {
                    editorListener?.onEnlargeCurve(curveView.flatList()) { newList ->
                        curveView.setFromFlatList(newList)
                        currentButton = currentButton?.copy(sensitivityCurve = newList)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    }
                }
            }
            curveBtnRow.addView(btnDeletePoint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (4f * density).toInt() })
            curveBtnRow.addView(btnResetCurve, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (4f * density).toInt() })
            curveBtnRow.addView(btnEnlargeCurve, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttonParamsInner.addView(curveBtnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })
        }
        if (buttonId == "dpadPad") {
            buildCenterShapeParam(density)
            // ── Rectangular area follow ──
            val cbFollowArea = CheckBox(context).apply {
                text = context.getString(R.string.i18n_follow_inside_rectangle)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.followAreaEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    val current = currentButton ?: return@setOnCheckedChangeListener
                    val updated = if (isChecked && current.followAreaW == 0) {
                        current.copy(
                            followAreaEnabled = true,
                            followAreaX = current.x,
                            followAreaY = current.y,
                            followAreaW = current.width,
                            followAreaH = current.height
                        )
                    } else {
                        current.copy(followAreaEnabled = isChecked)
                    }
                    currentButton = updated
                    editorListener?.onButtonUpdated(buttonId, updated)
                    showParameters(buttonId, updated)
                }
            }
            buttonParamsInner.addView(cbFollowArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

            if (button.followAreaEnabled) {
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt() }
                }
                val btnEnterAdjust = EditorButton(context).apply {
                    text = context.getString(R.string.i18n_adjust_d198bf)
                    setTextColor(-0x1)
                    textSize = 13f
                    setBackgroundResource(R.drawable.button_flat)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        editorListener?.onEnterFollowAreaAdjust(buttonId)
                    }
                }
                btnRow.addView(btnEnterAdjust)
                buttonParamsInner.addView(btnRow)
            }
        }

        if (buttonId.substringBefore("_") == "customKeypad") {
            // ── Rectangular area follow ──
            val cbFollowArea = CheckBox(context).apply {
                text = context.getString(R.string.i18n_follow_inside_rectangle)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.followAreaEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    val current = currentButton ?: return@setOnCheckedChangeListener
                    val updated = if (isChecked && current.followAreaW == 0) {
                        current.copy(
                            followAreaEnabled = true,
                            followAreaX = current.x,
                            followAreaY = current.y,
                            followAreaW = current.width,
                            followAreaH = current.height
                        )
                    } else {
                        current.copy(followAreaEnabled = isChecked)
                    }
                    currentButton = updated
                    editorListener?.onButtonUpdated(buttonId, updated)
                    showParameters(buttonId, updated)
                }
            }
            buttonParamsInner.addView(cbFollowArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

            if (button.followAreaEnabled) {
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt() }
                }
                val btnEnterAdjust = EditorButton(context).apply {
                    text = context.getString(R.string.i18n_adjust_d198bf)
                    setTextColor(-0x1)
                    textSize = 13f
                    setBackgroundResource(R.drawable.button_flat)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        editorListener?.onEnterFollowAreaAdjust(buttonId)
                    }
                }
                btnRow.addView(btnEnterAdjust)
                buttonParamsInner.addView(btnRow)
            }

            // ── Keypad parameter editing ─────────
            buildKeypadParams(density)
        }

        val triggerIds = setOf("btnLT", "btnRT")
        if (isButton(buttonId) || isKeyboardId(buttonId)) {
            val isTrigger = buttonId in triggerIds
            val cbSwipe = CheckBox(context).apply {
                text = context.getString(R.string.i18n_swipe_trigger)
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.swipeTrigger && !button.linearTriggerEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && isTrigger) {
                        currentButton = currentButton?.copy(
                            swipeTrigger = true,
                            linearTriggerEnabled = false,
                            slideDirection = button.slideDirection,
                            travelDistance = button.travelDistance
                        )
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                        showParameters(buttonId, currentButton!!)
                    } else if (isChecked && !isTrigger) {
                        currentButton = currentButton?.copy(
                            swipeTrigger = true
                        )
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                        showParameters(buttonId, currentButton!!)
                    } else {
                        currentButton = currentButton?.copy(
                            swipeTrigger = isChecked,
                            linearTriggerEnabled = false,
                            slideDirection = button.slideDirection,
                            travelDistance = button.travelDistance
                        )
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                        showParameters(buttonId, currentButton!!)
                    }
                }
            }
            buttonParamsInner.addView(cbSwipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

            if (isTrigger) {
                val cbLinear = CheckBox(context).apply {
                    text = context.getString(R.string.i18n_emulate_linear_trigger)
                    setTextColor(-0x444445)
                    textSize = 14f
                    isChecked = button.linearTriggerEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        currentButton = currentButton?.copy(
                            linearTriggerEnabled = isChecked,
                            swipeTrigger = if (isChecked) false else button.swipeTrigger,
                            slideDirection = button.slideDirection,
                            travelDistance = button.travelDistance
                        )
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                        showParameters(buttonId, currentButton!!)
                    }
                }
                buttonParamsInner.addView(cbLinear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

                val linearContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    id = View.generateViewId()
                }

                // slide direction spinner
                val tvDirection = TextView(context).apply {
                    text = context.getString(R.string.i18n_swipe_direction)
                    setTextColor(-0x444445)
                    textSize = 13f
                }
                linearContainer.addView(tvDirection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

                val directionItems = listOf(context.getString(R.string.i18n_down_c4557f), context.getString(R.string.i18n_up_00d5a2), context.getString(R.string.i18n_left), context.getString(R.string.i18n_right_6a21ce))
                val directionSpinner = Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, directionItems).also {
                        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    val defDir = currentButton?.slideDirection ?: SlideDirection.DOWN
                    setSelection(when (defDir) {
                        SlideDirection.DOWN -> 0
                        SlideDirection.UP -> 1
                        SlideDirection.LEFT -> 2
                        SlideDirection.RIGHT -> 3
                    })
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            val dir = when (pos) {
                                0 -> SlideDirection.DOWN
                                1 -> SlideDirection.UP
                                2 -> SlideDirection.LEFT
                                3 -> SlideDirection.RIGHT
                                else -> SlideDirection.DOWN
                            }
                            currentButton = currentButton?.copy(slideDirection = dir)
                            currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                linearContainer.addView(directionSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

                // travel distance seekbar
                val tvTravel = TextView(context).apply {
                    text = context.getString(R.string.i18n_trigger_travel)
                    setTextColor(-0x444445)
                    textSize = 13f
                }
                linearContainer.addView(tvTravel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val travelSeekbar = createSimpleSeekbarLinear(context.getString(R.string.i18n_travel), button.travelDistance, 1, 40, { value ->
                    currentButton = currentButton?.copy(travelDistance = value)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                })
                linearContainer.addView(travelSeekbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

                val cbLinearPrediction = CheckBox(context).apply {
                    text = context.getString(R.string.i18n_fast_response_mode)
                    setTextColor(-0x444445)
                    textSize = 14f
                    isChecked = button.prediction
                    setOnCheckedChangeListener { _, isChecked ->
                        currentButton = currentButton?.copy(prediction = isChecked)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    }
                }
                linearContainer.addView(cbLinearPrediction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

                // visibility controlled dynamically
                val updateLinearVisibility = { enabled: Boolean ->
                    val vis = if (enabled) View.VISIBLE else View.GONE
                    tvDirection.visibility = vis
                    directionSpinner.visibility = vis
                    tvTravel.visibility = vis
                    travelSeekbar.visibility = vis
                    cbLinearPrediction.visibility = vis
                }
                // set initial visibility
                if (button.linearTriggerEnabled) {
                    updateLinearVisibility(true)
                } else {
                    updateLinearVisibility(false)
                }

                buttonParamsInner.addView(linearContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt() })
            }
        }

        // ── Custom button settings ─────────────────────────
        if (button.isCustom) {
            val tvCustomLabel = TextView(context).apply {
                text = context.getString(R.string.i18n_display_text)
                setTextColor(-0x444445)
                textSize = 13f
            }
            buttonParamsInner.addView(tvCustomLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10f * density).toInt() })

            val safeCustomText = button.customText ?: context.getString(R.string.i18n_custom)
            val etCustomText = EditText(context).apply {
                setText(safeCustomText)
                setTextColor(-0x444445)
                textSize = 13f
                setBackgroundResource(R.drawable.bg_small_btn)
                setPadding((8f * density).toInt(), (4f * density).toInt(), (8f * density).toInt(), (4f * density).toInt())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val newText = s.toString().ifEmpty { context.getString(R.string.i18n_custom) }
                        currentButton = currentButton?.copy(customText = newText)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    }
                })
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newText = text.toString().ifEmpty { context.getString(R.string.i18n_custom) }
                        currentButton = currentButton?.copy(customText = newText)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    }
                }
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        val newText = text.toString().ifEmpty { context.getString(R.string.i18n_custom) }
                        currentButton = currentButton?.copy(customText = newText)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                        clearFocus()
                        true
                    } else false
                }
            }
            buttonParamsInner.addView(etCustomText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12f * density).toInt() })

            // ── Output values section ──
            val tvOutputLabel = TextView(context).apply {
                text = context.getString(R.string.i18n_mapped_keys)
                setTextColor(-0x1)
                textSize = 14f
            }
            buttonParamsInner.addView(tvOutputLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() })

            val bits = button.customBits.orEmpty()
            if (bits.isNotEmpty()) {
                val chipsContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val chipsPerRow = 3
                bits.chunked(chipsPerRow).forEach { rowBits ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    rowBits.forEach { bit ->
                        val chip = TextView(context).apply {
                            text = BitNameMapper.getBitName(bit)
                            setTextColor(-0x1)
                            textSize = 11f
                            gravity = Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_chip)
                            setPadding((6f * density).toInt(), (2f * density).toInt(), (6f * density).toInt(), (2f * density).toInt())
                            setOnClickListener {
                                val newBits = (currentButton?.customBits.orEmpty()) - bit
                                currentButton = currentButton?.copy(customBits = newBits)
                                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                                showParameters(buttonId, currentButton!!)
                            }
                        }
                        row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = (4f * density).toInt() })
                    }
                    chipsContainer.addView(row)
                }
                buttonParamsInner.addView(chipsContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })
            }

            val btnOutputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val btnAddOutput = EditorButton(context).apply {
                text = context.getString(R.string.i18n_add_b58c75)
                setTextColor(-0x1)
                textSize = 12f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener {
                    editorListener?.onPickOutputValues(buttonId, currentButton?.customBits.orEmpty()) { newBits ->
                        currentButton = currentButton?.copy(customBits = newBits)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                        showParameters(buttonId, currentButton!!)
                    }
                }
            }
            val btnClearOutput = EditorButton(context).apply {
                text = context.getString(R.string.i18n_clear)
                setTextColor(-0x1)
                textSize = 12f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener {
                    currentButton = currentButton?.copy(customBits = emptyList())
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    showParameters(buttonId, currentButton!!)
                }
            }
            btnOutputRow.addView(btnAddOutput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (4f * density).toInt() })
            btnOutputRow.addView(btnClearOutput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttonParamsInner.addView(btnOutputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12f * density).toInt() })

            buttonParamsInner.addComboTimingControls(button.comboTiming, button.comboIntervalMs) { newTiming, newInterval ->
                currentButton = currentButton?.copy(
                    comboTiming = newTiming,
                    comboIntervalMs = newInterval,
                )
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
        }

        if (!isSettingsButton(buttonId)) {
            val btnDelete = EditorButton(context).apply {
                text = context.getString(R.string.i18n_delete)
                setTextColor(-0x1)
                textSize = 14f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener { editorListener?.onDeleteButton(buttonId) }
            }
            buttonParamsInner.addView(btnDelete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16f * density).toInt() })
        }
    }

    /** 中心形状配置项：方形 / 圆形。选择圆形后方向区域变为径向扇环。 */
    private fun buildCenterShapeParam(density: Float) {
        val cb0 = currentButton ?: return
        val label = TextView(context).apply {
            text = context.getString(R.string.i18n_center_shape)
            setTextColor(-0x1)
            textSize = 14f
        }
        buttonParamsInner.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

        val items = listOf(context.getString(R.string.i18n_square), context.getString(R.string.i18n_circle))
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(if (cb0.centerShape == CenterShape.CIRCLE) 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id2: Long) {
                    val current = currentButton ?: return
                    val shape = if (pos == 1) CenterShape.CIRCLE else CenterShape.SQUARE
                    if (shape == current.centerShape) return
                    val updated = current.copy(centerShape = shape)
                    currentButton = updated
                    editorListener?.onButtonUpdated(current.id, updated)
                    showParameters(current.id, updated)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        buttonParamsInner.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })
    }

    private fun buildKeypadParams(density: Float) {
        val id = currentButton?.id ?: return
        val cb = currentButton ?: return
        val texts = ButtonPosition.keypadTextsOf(cb)
        val bits = ButtonPosition.keypadBitsOf(cb)

        // ── 方向模式下拉框 ──
        val modeLabel = TextView(context).apply {
            text = context.getString(R.string.i18n_direction_mode)
            setTextColor(-0x1)
            textSize = 14f
        }
        buttonParamsInner.addView(modeLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

        val modeItems = listOf(context.getString(R.string.i18n_4_way), context.getString(R.string.i18n_8_way))
        val modeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, modeItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(if (cb.keypadEightWay) 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id2: Long) {
                    val eight = pos == 1
                    val current = currentButton ?: return
                    if (eight == current.keypadEightWay) return
                    val updated = current.copy(keypadEightWay = eight)
                    currentButton = updated
                    editorListener?.onButtonUpdated(id, updated)
                    showParameters(id, updated)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        buttonParamsInner.addView(modeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

        buildCenterShapeParam(density)

        val dirs = mutableListOf(context.getString(R.string.i18n_up_3f99c7) to 0, context.getString(R.string.i18n_down_c761f5) to 1, context.getString(R.string.i18n_left_3e0a82) to 2, context.getString(R.string.i18n_right_b45922) to 3)
        if (cb.keypadEightWay) {
            dirs += listOf(context.getString(R.string.i18n_top_left_07092c) to 5, context.getString(R.string.i18n_top_right_a52f17) to 6, context.getString(R.string.i18n_bottom_left_a289e9) to 7, context.getString(R.string.i18n_bottom_right_553067) to 8)
        }
        // 双击中心固定放最后，保证其文本输入框与下方映射编辑器相邻。
        dirs += context.getString(R.string.i18n_double_tap_center) to 4

        for ((name, idx) in dirs) {
            buildKeypadRegionParam(density, name, idx, texts[idx], bits[idx])
        }

        // Center region output editor (center double-click is always enabled)
        buildKeypadBitsEditor(
            density,
            bits[4],
            ButtonPosition.keypadComboTimingOf(cb, 4),
            ButtonPosition.keypadComboIntervalOf(cb, 4),
            onBitsChanged = { newBits ->
                val cb2 = currentButton ?: return@buildKeypadBitsEditor
                val b = ButtonPosition.keypadBitsOf(cb2).toMutableList()
                b[4] = newBits
                currentButton = cb2.copy(keypadBits = b)
                currentButton?.let { editorListener?.onButtonUpdated(id, it) }
            },
            onTimingChanged = { newTiming, newInterval ->
                updateKeypadComboTiming(id, 4, newTiming, newInterval)
            },
        )
    }

    /** Stores the combo timing of one keypad region in the current button. */
    private fun updateKeypadComboTiming(
        buttonId: String,
        region: Int,
        timing: ComboTiming,
        intervalMs: Int,
    ) {
        val cb = currentButton ?: return
        val timings = ButtonPosition.keypadComboTimingsOf(cb).toMutableList()
        timings[region] = timing
        val intervals = ButtonPosition.keypadComboIntervalsOf(cb).toMutableList()
        intervals[region] = intervalMs
        currentButton = cb.copy(
            keypadComboTimings = timings,
            keypadComboIntervals = intervals,
        )
        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
    }

    @Suppress("SameParameterValue")
    private fun buildKeypadRegionParam(density: Float, label: String, regionIdx: Int, currentText: String, currentBits: List<Int>) {
        val cb = currentButton ?: return
        val id = cb.id

        // Label
        val tv = TextView(context).apply {
            text = label
            setTextColor(-0x1)
            textSize = 14f
        }
        buttonParamsInner.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

        // Display text
        val et = EditText(context).apply {
            setText(currentText)
            setTextColor(-0x444445)
            textSize = 13f
            setBackgroundResource(R.drawable.bg_small_btn)
            setPadding((8f * density).toInt(), (4f * density).toInt(), (8f * density).toInt(), (4f * density).toInt())
            val regionFinal = regionIdx
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val latest = currentButton ?: cb
                    val texts = ButtonPosition.keypadTextsOf(latest).toMutableList()
                    texts[regionFinal] = s.toString()
                    currentButton = latest.copy(keypadTexts = texts)
                    currentButton?.let { editorListener?.onButtonUpdated(id, it) }
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val latest = currentButton ?: cb
                    val texts = ButtonPosition.keypadTextsOf(latest).toMutableList()
                    texts[regionIdx] = text.toString()
                    currentButton = latest.copy(keypadTexts = texts)
                    currentButton?.let { editorListener?.onButtonUpdated(id, it) }
                }
            }
        }
        buttonParamsInner.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (2f * density).toInt() })

        if (regionIdx != 4) {
            buildKeypadBitsEditor(
                density,
                currentBits,
                ButtonPosition.keypadComboTimingOf(cb, regionIdx),
                ButtonPosition.keypadComboIntervalOf(cb, regionIdx),
                onBitsChanged = { newBits ->
                    val cb2 = currentButton ?: return@buildKeypadBitsEditor
                    val b = ButtonPosition.keypadBitsOf(cb2).toMutableList()
                    b[regionIdx] = newBits
                    currentButton = cb2.copy(keypadBits = b)
                    currentButton?.let { editorListener?.onButtonUpdated(id, it) }
                },
                onTimingChanged = { newTiming, newInterval ->
                    updateKeypadComboTiming(id, regionIdx, newTiming, newInterval)
                },
            )
        }
    }

    private fun buildKeypadBitsEditor(
        density: Float,
        initialBits: List<Int>,
        initialTiming: ComboTiming,
        initialIntervalMs: Int,
        onBitsChanged: (List<Int>) -> Unit,
        onTimingChanged: (ComboTiming, Int) -> Unit,
    ) {
        val bitsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val currentBitsRef = object { var bits: List<Int> = initialBits.toList() }

        fun renderChips() {
            bitsContainer.removeAllViews()
            if (currentBitsRef.bits.isNotEmpty()) {
                val chipsPerRow = 3
                currentBitsRef.bits.chunked(chipsPerRow).forEach { rowBits ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    rowBits.forEach { bit ->
                        val chip = TextView(context).apply {
                            text = BitNameMapper.getBitName(bit)
                            setTextColor(-0x1)
                            textSize = 11f
                            gravity = Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_chip)
                            setPadding((6f * density).toInt(), (2f * density).toInt(), (6f * density).toInt(), (2f * density).toInt())
                            setOnClickListener {
                                currentBitsRef.bits = currentBitsRef.bits - bit
                                renderChips()
                                onBitsChanged(currentBitsRef.bits)
                            }
                        }
                        row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = (4f * density).toInt() })
                    }
                    bitsContainer.addView(row)
                }
            }
        }

        renderChips()
        buttonParamsInner.addView(bitsContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() })

        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val btnAdd = EditorButton(context).apply {
            text = context.getString(R.string.i18n_add_b58c75)
            setTextColor(-0x1)
            textSize = 12f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                editorListener?.onPickOutputValues(currentButton?.id ?: "", currentBitsRef.bits) { newBits ->
                    currentBitsRef.bits = newBits
                    renderChips()
                    onBitsChanged(newBits)
                }
            }
        }
        val btnClear = EditorButton(context).apply {
            text = context.getString(R.string.i18n_clear)
            setTextColor(-0x1)
            textSize = 12f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                currentBitsRef.bits = emptyList()
                renderChips()
                onBitsChanged(emptyList())
            }
        }
        row2.addView(btnAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (4f * density).toInt() })
        row2.addView(btnClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonParamsInner.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

        buttonParamsInner.addComboTimingControls(initialTiming, initialIntervalMs, onTimingChanged)
    }

    private fun addRotationButtons(container: LinearLayout, buttonId: String, density: Float, hide: Boolean = false) {
        if (hide) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * density).toInt()
            }
        }
        val btnSize = (40f * density).toInt()
        val spacing = (8f * density).toInt()

        val btnCcw = TextView(context).apply {
            text = "↺"
            setTextColor(-0x1)
            textSize = 24f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                currentButton = currentButton?.let { cb ->
                    val newRot = (cb.rotation - 90 + 360) % 360
                    val updated = cb.copy(rotation = newRot)
                    editorListener?.onButtonUpdated(buttonId, updated)
                    updated
                }
            }
        }
        val btnCw = TextView(context).apply {
            text = "↻"
            setTextColor(-0x1)
            textSize = 24f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                currentButton = currentButton?.let { cb ->
                    val newRot = (cb.rotation + 90) % 360
                    val updated = cb.copy(rotation = newRot)
                    editorListener?.onButtonUpdated(buttonId, updated)
                    updated
                }
            }
        }

        val colCcw = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = spacing }
        }
        colCcw.addView(btnCcw, LinearLayout.LayoutParams(btnSize, btnSize))
        colCcw.addView(TextView(context).apply {
            text = context.getString(R.string.i18n_counterclockwise)
            setTextColor(-0x444445)
            textSize = 11f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (2f * density).toInt() })

        val colCw = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        colCw.addView(btnCw, LinearLayout.LayoutParams(btnSize, btnSize))
        colCw.addView(TextView(context).apply {
            text = context.getString(R.string.i18n_clockwise)
            setTextColor(-0x444445)
            textSize = 11f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (2f * density).toInt() })

        row.addView(colCcw)
        row.addView(colCw)
        container.addView(row)
    }


    /** 鼠标触控板手势动作下拉框：标签 + Spinner。 */
    private fun addMouseActionSpinner(
        container: LinearLayout,
        label: String,
        actions: List<MouseGestureAction>,
        current: MouseGestureAction,
        onChange: (MouseGestureAction) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val tv = TextView(context).apply {
            text = label
            setTextColor(-0x1)
            textSize = 14f
            setPadding(0, (8f * density).toInt(), 0, 0)
        }
        container.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val items = actions.map { context.getString(it.displayNameRes) }
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(actions.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val action = actions.getOrNull(pos) ?: return
                    onChange(action)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        container.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4f * density).toInt()
            bottomMargin = (4f * density).toInt()
        })
    }

    private fun addSeekbar(container: LinearLayout, label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit, onStartTracking: (() -> Unit)? = null, onStopTracking: (() -> Unit)? = null) {
        val density = context.resources.displayMetrics.density
        val btnSize = (32f * density).toInt()
        var currentValue = value.coerceIn(min, max)

        var et: EditText? = null
        var seek: SeekBar? = null

        val tv = TextView(context).apply {
            text = label
            setTextColor(-0x444445)
            textSize = 13f
        }

        et = EditText(context).apply {
            setText("$currentValue")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(-0x444445)
            setBackgroundResource(R.drawable.bg_small_btn)
            setPadding((4f * density).toInt(), 0, (4f * density).toInt(), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    val parsed = text.toString().toIntOrNull() ?: currentValue
                    val clamped = parsed.coerceIn(min, max)
                    if (clamped != currentValue) {
                        currentValue = clamped
                        seek?.progress = clamped - min
                        onChange(clamped)
                    }
                    clearFocus()
                }
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val parsed = text.toString().toIntOrNull() ?: currentValue
                    val clamped = parsed.coerceIn(min, max)
                    if (clamped != currentValue) {
                        currentValue = clamped
                        seek?.progress = clamped - min
                        onChange(clamped)
                    }
                }
            }
        }

        val btnMinus = TextView(context).apply {
            text = "-"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                val clamped = (currentValue - 1).coerceIn(min, max)
                if (clamped != currentValue) {
                    currentValue = clamped
                    et?.setText("$clamped")
                    seek?.progress = clamped - min
                    onChange(clamped)
                }
            }
        }

        val btnPlus = TextView(context).apply {
            text = "+"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                val clamped = (currentValue + 1).coerceIn(min, max)
                if (clamped != currentValue) {
                    currentValue = clamped
                    et?.setText("$clamped")
                    seek?.progress = clamped - min
                    onChange(clamped)
                }
            }
        }

        seek = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            this.max = max - min
            progress = currentValue - min
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        var p = v.parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(true)
                            p = p.parent
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        var p = v.parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(false)
                            p = p.parent
                        }
                    }
                }
                false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newVal = progress + min
                        currentValue = newVal
                        et?.setText("$newVal")
                        onChange(newVal)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {
                    onStartTracking?.invoke()
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    onStopTracking?.invoke()
                }
            })
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row1.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
        row1.addView(et, LinearLayout.LayoutParams(0, btnSize, 1f).apply { leftMargin = (8f * density).toInt(); rightMargin = (4f * density).toInt() })
        row1.addView(btnMinus, LinearLayout.LayoutParams(btnSize, btnSize).apply { rightMargin = (4f * density).toInt() })
        row1.addView(btnPlus, LinearLayout.LayoutParams(btnSize, btnSize))
        container.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() }
        }
        row2.addView(seek)
        container.addView(row2)
    }

    private fun addSeekbarFloat(
        container: LinearLayout,
        label: String,
        value: Float,
        min: Float,
        max: Float,
        step: Float = 0.05f,
        scale: Int = 100,
        onChange: (Float) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val btnSize = (32f * density).toInt()
        var currentValue = value.coerceIn(min, max)

        var et: EditText? = null
        var seek: SeekBar? = null

        val tv = TextView(context).apply {
            text = label
            setTextColor(-0x444445)
            textSize = 13f
        }

        fun fmt(v: Float) = String.format("%.2f", v)
        fun progressOf(v: Float) = ((v - min) * scale).toInt().coerceAtLeast(0)

        et = EditText(context).apply {
            setText(fmt(currentValue))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(-0x444445)
            setBackgroundResource(R.drawable.bg_small_btn)
            setPadding((4f * density).toInt(), 0, (4f * density).toInt(), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    val parsed = text.toString().toFloatOrNull() ?: currentValue
                    val clamped = parsed.coerceIn(min, max)
                    if (clamped != currentValue) {
                        currentValue = clamped
                        seek?.progress = progressOf(clamped)
                        onChange(clamped)
                    }
                    clearFocus()
                }
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val parsed = text.toString().toFloatOrNull() ?: currentValue
                    val clamped = parsed.coerceIn(min, max)
                    if (clamped != currentValue) {
                        currentValue = clamped
                        seek?.progress = progressOf(clamped)
                        onChange(clamped)
                    }
                }
            }
        }

        val btnMinus = TextView(context).apply {
            text = "-"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                val clamped = (currentValue - step).coerceIn(min, max)
                if (clamped != currentValue) {
                    currentValue = clamped
                    et?.setText(fmt(clamped))
                    seek?.progress = progressOf(clamped)
                    onChange(clamped)
                }
            }
        }

        val btnPlus = TextView(context).apply {
            text = "+"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                val clamped = (currentValue + step).coerceIn(min, max)
                if (clamped != currentValue) {
                    currentValue = clamped
                    et?.setText(fmt(clamped))
                    seek?.progress = progressOf(clamped)
                    onChange(clamped)
                }
            }
        }

        seek = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            this.max = ((max - min) * scale).toInt()
            progress = progressOf(currentValue)
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        var p = v.parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(true)
                            p = p.parent
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        var p = v.parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(false)
                            p = p.parent
                        }
                    }
                }
                false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newVal = (min + progress.toFloat() / scale).coerceIn(min, max)
                        currentValue = newVal
                        et?.setText(fmt(newVal))
                        onChange(newVal)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row1.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
        row1.addView(et, LinearLayout.LayoutParams(0, btnSize, 1f).apply { leftMargin = (8f * density).toInt(); rightMargin = (4f * density).toInt() })
        row1.addView(btnMinus, LinearLayout.LayoutParams(btnSize, btnSize).apply { rightMargin = (4f * density).toInt() })
        row1.addView(btnPlus, LinearLayout.LayoutParams(btnSize, btnSize))
        container.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() }
        }
        row2.addView(seek)
        container.addView(row2)
    }

    private fun createSimpleSeekbar(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val density = context.resources.displayMetrics.density
        var currentValue = value.coerceIn(min, max)
        var et: EditText? = null
        var seek: SeekBar? = null

        fun updateValue(newValue: Int) {
            currentValue = newValue.coerceIn(min, max)
            et?.setText("$currentValue")
            seek?.progress = currentValue - min
            onChange(currentValue)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        val tv = TextView(context).apply {
            text = label
            setTextColor(-0x444445)
            textSize = 13f
        }
        row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        et = EditText(context).apply {
            setText("$currentValue")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(-0x444445)
            setBackgroundResource(R.drawable.bg_small_btn)
            setPadding((4f * density).toInt(), 0, (4f * density).toInt(), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    updateValue(text.toString().toIntOrNull() ?: currentValue)
                    clearFocus()
                }
                false
            }
        }
        row.addView(et, LinearLayout.LayoutParams(0, (32f * density).toInt(), 1f).apply { leftMargin = (8f * density).toInt(); rightMargin = (4f * density).toInt() })

        val btnMinus = TextView(context).apply {
            text = "-"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { updateValue(currentValue - 1) }
        }
        row.addView(btnMinus, LinearLayout.LayoutParams((32f * density).toInt(), (32f * density).toInt()).apply { rightMargin = (4f * density).toInt() })

        val btnPlus = TextView(context).apply {
            text = "+"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { updateValue(currentValue + 1) }
        }
        row.addView(btnPlus, LinearLayout.LayoutParams((32f * density).toInt(), (32f * density).toInt()))

        seek = SeekBar(context).apply {
            this.max = max - min
            progress = currentValue - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateValue(progress + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        container.addView(row)
        container.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() })
        return container
    }

    private fun createSimpleSeekbarLinear(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val density = context.resources.displayMetrics.density
        var currentValue = value.coerceIn(min, max)
        var et: EditText? = null
        var seek: SeekBar? = null

        fun updateValue(newValue: Int) {
            currentValue = newValue.coerceIn(min, max)
            et?.setText("$currentValue")
            seek?.progress = currentValue - min
            onChange(currentValue)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
        }

        val tv = TextView(context).apply {
            text = label
            setTextColor(-0x444445)
            textSize = 13f
        }

        et = EditText(context).apply {
            setText("$currentValue")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(-0x444445)
            setBackgroundResource(R.drawable.bg_small_btn)
            setPadding((4f * density).toInt(), 0, (4f * density).toInt(), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    updateValue(text.toString().toIntOrNull() ?: currentValue)
                    clearFocus()
                }
                false
            }
        }
        row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(et, LinearLayout.LayoutParams(0, (32f * density).toInt(), 1f).apply { leftMargin = (8f * density).toInt(); rightMargin = (4f * density).toInt() })

        val btnMinus = TextView(context).apply {
            text = "-"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { updateValue(currentValue - 1) }
        }
        row.addView(btnMinus, LinearLayout.LayoutParams((32f * density).toInt(), (32f * density).toInt()).apply { rightMargin = (4f * density).toInt() })

        val btnPlus = TextView(context).apply {
            text = "+"
            setTextColor(-0x1)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { updateValue(currentValue + 1) }
        }
        row.addView(btnPlus, LinearLayout.LayoutParams((32f * density).toInt(), (32f * density).toInt()))

        seek = SeekBar(context).apply {
            this.max = max - min
            progress = currentValue - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateValue(progress + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        container.addView(row)
        container.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() })
        return container
    }
}

/** Single-line, auto-shrinking button used by the floating editor so long labels never wrap. */
private class EditorButton(context: Context) : Button(context) {
    init {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, TypedValue.COMPLEX_UNIT_SP)
    }
}
