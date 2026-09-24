package com.zyz4.gkme.view

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.MouseGestureAction
import com.zyz4.gkme.model.GyroOrientation
import com.zyz4.gkme.model.GyroBaseDirection
import com.zyz4.gkme.model.GyroCoordinateSystem
import com.zyz4.gkme.model.GyroMode
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
        fun onGyroOrientationChanged(orientation: GyroOrientation?)
        fun onEnterFollowAreaAdjust(buttonId: String)
        fun onExitFollowAreaAdjust()
        fun onOpacityPreviewStart(buttonId: String, isIdle: Boolean)
        fun onOpacityPreviewEnd(buttonId: String)
        fun onGyroBaseDirectionChanged(direction: com.zyz4.gkme.model.GyroBaseDirection)
        fun onGyroCoordinateSystemChanged(coordinateSystem: com.zyz4.gkme.model.GyroCoordinateSystem)
        fun onGyroModeChanged(mode: com.zyz4.gkme.model.GyroMode)
        fun onGyroModeSensitivityChanged(value: Int)
        fun onGyroDeadZoneChanged(value: Int)
        fun onGyroReverseDeadZoneChanged(value: Int)
        fun onGyroActivateModeChanged(mode: com.zyz4.gkme.model.GyroActivateMode)
        fun onEnterGlobalGyroSettings()
        fun onExitGlobalGyroSettings()
    }

    var editorListener: EditorListener? = null

    /** Invoked when the panel is collapsed/expanded via the header toggle button. */
    var onToggleCollapsed: ((collapsed: Boolean) -> Unit)? = null

    private var collapsed = false
    private var expandedHeight = 0
    private var headerView: View? = null
    private var scrollView: View? = null
    private var toggleBtn: ImageButton? = null

    var presetGyroOrientation: GyroOrientation? = null
        set(value) {
            field = value
            if (!showingGlobalSettings) {
                gyroSpinner?.setSelection((value?.ordinal?.plus(1)) ?: 0)
            }
        }

    var presetGyroBaseDirection: com.zyz4.gkme.model.GyroBaseDirection = com.zyz4.gkme.model.GyroBaseDirection.VERTICAL
        set(value) {
            field = value
            if (!showingGlobalSettings) {
                val idx = baseDirectionValues.indexOf(value)
                if (idx >= 0) gyroBaseDirectionSpinner?.setSelection(idx)
            }
        }

    var presetGyroCoordinateSystem: com.zyz4.gkme.model.GyroCoordinateSystem = com.zyz4.gkme.model.GyroCoordinateSystem.YAW_ROLL
        set(value) {
            field = value
            if (!showingGlobalSettings) {
                val idx = coordinateSystemValues.indexOf(value)
                if (idx >= 0) gyroCoordinateSystemSpinner?.setSelection(idx)
            }
        }

    var presetGyroMode: com.zyz4.gkme.model.GyroMode = com.zyz4.gkme.model.GyroMode.HANDHELD
        set(value) {
            field = value
            if (!showingGlobalSettings) {
                val idx = mappingModeValues.indexOf(value)
                if (idx >= 0) gyroModeSpinner?.setSelection(idx)
            }
        }

    var presetGyroModeSensitivity: Int = 20
        set(value) {
            field = value
        }

    var presetGyroDeadZone: Int = 0
        set(value) {
            field = value
        }

    var presetGyroReverseDeadZone: Int = 0
        set(value) {
            field = value
        }

    var presetGyroActivateMode: com.zyz4.gkme.model.GyroActivateMode = com.zyz4.gkme.model.GyroActivateMode.ALWAYS
        set(value) {
            field = value
            gyroActivateSpinnerRef?.setSelection(value.ordinal)
        }

    private var gyroSpinner: Spinner? = null
    private var gyroBaseDirectionSpinner: Spinner? = null
    private var gyroBaseDirectionLabel: TextView? = null
    private var gyroCoordinateSystemSpinner: Spinner? = null
    private var gyroModeSpinner: Spinner? = null
    private var gyroActivateSpinnerRef: Spinner? = null
    private var globalSettingsContainer: LinearLayout? = null
    private var gyroAdvancedContainer: LinearLayout? = null
    var showingGlobalSettings = false

    var currentSettings: com.zyz4.gkme.model.AppSettings? = null

    fun restoreFromSettings(settings: com.zyz4.gkme.model.AppSettings) {
        currentSettings = settings
        presetGyroOrientation = settings.gyroOrientation
        presetGyroBaseDirection = settings.gyroBaseDirection
        presetGyroCoordinateSystem = settings.gyroCoordinateSystem
        presetGyroMode = settings.gyroMode
        presetGyroModeSensitivity = settings.gyroModeSensitivity
        presetGyroDeadZone = settings.gyroDeadZone
        presetGyroReverseDeadZone = settings.gyroReverseDeadZone
        presetGyroActivateMode = settings.gyroActivateMode
    }

    private val baseDirectionValues = listOf(
        com.zyz4.gkme.model.GyroBaseDirection.VERTICAL,
        com.zyz4.gkme.model.GyroBaseDirection.HORIZONTAL,
    )

    private val coordinateSystemValues = listOf(
        com.zyz4.gkme.model.GyroCoordinateSystem.YAW,
        com.zyz4.gkme.model.GyroCoordinateSystem.ROLL,
        com.zyz4.gkme.model.GyroCoordinateSystem.YAW_ROLL,
        com.zyz4.gkme.model.GyroCoordinateSystem.WORLD,
    )

    private val mappingModeValues = listOf(
        com.zyz4.gkme.model.GyroMode.HANDHELD,
        com.zyz4.gkme.model.GyroMode.MOUSE,
        com.zyz4.gkme.model.GyroMode.LEFT_STICK,
        com.zyz4.gkme.model.GyroMode.RIGHT_STICK,
        com.zyz4.gkme.model.GyroMode.ACCELEROMETER_LEFT_STICK,
        com.zyz4.gkme.model.GyroMode.ACCELEROMETER_RIGHT_STICK,
    )

    private val BUTTON_IDS = setOf(
        "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
        "btnY", "btnA", "btnX", "btnB",
        "btnLT", "btnLB", "btnRT", "btnRB",
        "btnSelect", "btnHome", "btnMenu",
        "btnTouchpad", "btnLS", "btnRS", "btnMic",
        "btnMouseLMB", "btnMouseRMB", "btnMouseMMB",
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
                adjustingReturnButtonText = getChineseName(currentButton?.id ?: "") + " 调节"
                btnGlobalSettings?.text = "返回"
            } else {
                adjustingReturnButtonText = null
                btnGlobalSettings?.text = if (showingGlobalSettings) "返回" else "陀螺仪设置"
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

    private fun updateActionButtonsVisibility() {
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

    private fun getChineseName(buttonId: String): String {
        val base = buttonId.substringBefore("_")
        return when (base) {
            "btnDpadUp" -> "上方向"
            "btnDpadDown" -> "下方向"
            "btnDpadLeft" -> "左方向"
            "btnDpadRight" -> "右方向"
            "btnY" -> "手柄：Y"
            "btnA" -> "手柄：A"
            "btnX" -> "手柄：X"
            "btnB" -> "手柄：B"
            "btnLT" -> "手柄：左扳机"
            "btnLB" -> "手柄：左肩键"
            "btnRT" -> "手柄：右扳机"
            "btnRB" -> "手柄：右肩键"
            "leftJoystick" -> "手柄：左摇杆"
            "rightJoystick" -> "手柄：右摇杆"
            "btnSelect" -> "手柄：选择"
            "btnHome" -> "手柄：主页"
            "btnMenu" -> "手柄：菜单"
            "btnTouchpad" -> "触摸板按下"
            "btnLS" -> "手柄：左摇杆按下"
            "btnRS" -> "手柄：右摇杆按下"
            "touchpad" -> "触摸板（手柄）"
            "mousepad" -> "触摸板（鼠标）"
            "dpadPad" -> "一体十字键"
            "customKeypad" -> "自定义按键盘"
            "btnCustomCircle" -> "自定义(圆)"
            "btnCustomRect" -> "自定义(方)"
            "btnMic" -> "麦克风静音"
            "btnMouseLMB" -> "鼠标：左键"
            "btnMouseRMB" -> "鼠标：右键"
            "btnMouseMMB" -> "鼠标：中键"
            "btnSettings" -> "设置按钮"
            "btn" -> "按钮"
            "joystick" -> "摇杆"
            "kbArrowUp" -> "键盘：↑"
            "kbArrowDown" -> "键盘：↓"
            "kbArrowLeft" -> "键盘：←"
            "kbArrowRight" -> "键盘：→"
            "kbLCtrl" -> "键盘：LCtrl"
            "kbLShift" -> "键盘：LShift"
            "kbLAlt" -> "键盘：LAlt"
            "kbLWin" -> "键盘：Win"
            "kbRCtrl" -> "键盘：RCtrl"
            "kbRShift" -> "键盘：RShift"
            "kbRAlt" -> "键盘：AltGr"
            "kbRGui" -> "键盘：RWin"
            "kbQ" -> "键盘：Q"
            "kbW" -> "键盘：W"
            "kbE" -> "键盘：E"
            "kbR" -> "键盘：R"
            "kbT" -> "键盘：T"
            "kbY" -> "键盘：Y"
            "kbU" -> "键盘：U"
            "kbI" -> "键盘：I"
            "kbO" -> "键盘：O"
            "kbP" -> "键盘：P"
            "kbA" -> "键盘：A"
            "kbS" -> "键盘：S"
            "kbD" -> "键盘：D"
            "kbF" -> "键盘：F"
            "kbG" -> "键盘：G"
            "kbH" -> "键盘：H"
            "kbJ" -> "键盘：J"
            "kbK" -> "键盘：K"
            "kbL" -> "键盘：L"
            "kbZ" -> "键盘：Z"
            "kbX" -> "键盘：X"
            "kbC" -> "键盘：C"
            "kbV" -> "键盘：V"
            "kbB" -> "键盘：B"
            "kbN" -> "键盘：N"
            "kbM" -> "键盘：M"
            "kb1" -> "键盘：1"
            "kb2" -> "键盘：2"
            "kb3" -> "键盘：3"
            "kb4" -> "键盘：4"
            "kb5" -> "键盘：5"
            "kb6" -> "键盘：6"
            "kb7" -> "键盘：7"
            "kb8" -> "键盘：8"
            "kb9" -> "键盘：9"
            "kb0" -> "键盘：0"
            "kbSpace" -> "键盘：Space"
            "kbEnter" -> "键盘：Enter"
            "kbBackspace" -> "键盘：Bksp"
            "kbTab" -> "键盘：Tab"
            "kbCaps" -> "键盘：Caps"
            "kbEsc" -> "键盘：Esc"
            "kbDelete" -> "键盘：Del"
            "kbMenu" -> "键盘：Menu"
            "kbMinus" -> "键盘：-"
            "kbEqual" -> "键盘：="
            "kbLBracket" -> "键盘：["
            "kbRBracket" -> "键盘：]"
            "kbBackslash" -> "键盘：\\"
            "kbSemicolon" -> "键盘：;"
            "kbApostrophe" -> "键盘：'"
            "kbComma" -> "键盘：,"
            "kbDot" -> "键盘：."
            "kbSlash" -> "键盘：/"
            "kbGrave" -> "键盘：`"
            "kbF1" -> "键盘：F1"
            "kbF2" -> "键盘：F2"
            "kbF3" -> "键盘：F3"
            "kbF4" -> "键盘：F4"
            "kbF5" -> "键盘：F5"
            "kbF6" -> "键盘：F6"
            "kbF7" -> "键盘：F7"
            "kbF8" -> "键盘：F8"
            "kbF9" -> "键盘：F9"
            "kbF10" -> "键盘：F10"
            "kbF11" -> "键盘：F11"
            "kbF12" -> "键盘：F12"
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
            contentDescription = "收起/展开面板"
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
        val btnSave = Button(context).apply {
            text = "保存"
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { editorListener?.onSave() }
        }
        val btnDiscard = Button(context).apply {
            text = "放弃"
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { editorListener?.onDiscard() }
        }
        val btnAdd = Button(context).apply {
            text = "+ 添加"
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { editorListener?.onAddButton() }
        }
        this.btnSave = btnSave
        this.btnDiscard = btnDiscard
        this.btnAdd = btnAdd
        btnGlobalSettings = Button(context).apply {
            text = "陀螺仪设置"
            setTextColor(-0x1)
            textSize = 13f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                if (isAdjustingFollowArea) {
                    // Return from follow area adjust
                    editorListener?.onExitFollowAreaAdjust()
                } else {
                    showingGlobalSettings = !showingGlobalSettings
                    if (showingGlobalSettings) {
                        text = "返回"
                        setTextColor(-0x1)
                        currentButton = null
                        editorListener?.onEnterGlobalGyroSettings()
                        updateActionButtonsVisibility()
                        buildGlobalSettingsPanel(density)
                    } else {
                        text = "陀螺仪设置"
                        clearGlobalSettingsPanel()
                        editorListener?.onExitGlobalGyroSettings()
                        updateActionButtonsVisibility()
                    }
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

    private fun buildGyroSelector(density: Float, container: LinearLayout) {
        val tv = TextView(context).apply {
            text = "体感握持方向"
            setTextColor(-0x1)
            textSize = 14f
        }
        container.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6f * density).toInt() })

        val items = listOf("不指定", "横屏", "竖屏", "倒置竖屏")
        val currentOrientation = presetGyroOrientation
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection((currentOrientation?.ordinal?.plus(1)) ?: 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val orientation = when (pos) {
                        1 -> GyroOrientation.LANDSCAPE
                        2 -> GyroOrientation.PORTRAIT
                        3 -> GyroOrientation.PORTRAIT_INVERTED
                        else -> null
                    }
                    editorListener?.onGyroOrientationChanged(orientation)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        gyroSpinner = spinner
        container.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })
    }

    private fun buildGlobalSettingsPanel(density: Float) {
        buttonParamsInner.removeAllViews()
        gyroAdvancedContainer?.removeAllViews()
        globalSettingsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8f * density).toInt(), (8f * density).toInt(), (8f * density).toInt(), (8f * density).toInt())
        }
        val container = globalSettingsContainer!!

        // ── Gyro orientation (top) ──
        buildGyroSelector(density, container)

        // Separator
        val sep = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1f * density).toInt()).apply {
                topMargin = (4f * density).toInt()
                bottomMargin = (8f * density).toInt()
            }
            background = GradientDrawable().apply { setColor(-0x444445) }
        }
        container.addView(sep)

        // ── Gyro activation mode ──
        val tvGyroActivate = TextView(context).apply {
            text = "陀螺仪激活方式"
            setTextColor(-0x1)
            textSize = 15f
            setPadding(0, (8f * density).toInt(), 0, 0)
        }
        container.addView(tvGyroActivate)

        val gyroActivateItems = listOf("始终开启", "按下特定按钮开启")
        val gyroActivateSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, gyroActivateItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(presetGyroActivateMode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val mode = when (pos) {
                        1 -> com.zyz4.gkme.model.GyroActivateMode.BUTTON
                        else -> com.zyz4.gkme.model.GyroActivateMode.ALWAYS
                    }
                    presetGyroActivateMode = mode
                    editorListener?.onGyroActivateModeChanged(mode)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        gyroActivateSpinnerRef = gyroActivateSpinner
        container.addView(gyroActivateSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt(); bottomMargin = (12f * density).toInt() })

        // ── Gyro mapping mode ──
        val tvGyroMode = TextView(context).apply {
            text = "陀螺仪行为"
            setTextColor(-0x1)
            textSize = 15f
            setPadding(0, (8f * density).toInt(), 0, 0)
        }
        container.addView(tvGyroMode)

        val gyroModeItems = listOf("手柄陀螺仪", "陀螺仪转鼠标", "陀螺仪转左摇杆", "陀螺仪转右摇杆", "加速度计转左摇杆", "加速度计转右摇杆")
        val modeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, gyroModeItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(mappingModeValues.indexOf(presetGyroMode).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val mode = mappingModeValues.getOrNull(pos) ?: GyroMode.HANDHELD
                    presetGyroMode = mode
                    editorListener?.onGyroModeChanged(mode)
                    updateGyroModeVisibility()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        gyroModeSpinner = modeSpinner
        container.addView(modeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt(); bottomMargin = (12f * density).toInt() })

        // ── Advanced settings: coordinate system, sensitivity, dead zones ──
        // Only shown when the gyro mode is not HANDHELD (手柄陀螺仪).
        gyroAdvancedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val advanced = gyroAdvancedContainer!!

        // ── Gyro base direction ──
        val tvGyroBaseDirection = TextView(context).apply {
            text = "基准方向"
            setTextColor(-0x1)
            textSize = 15f
            setPadding(0, (8f * density).toInt(), 0, 0)
        }

        val baseDirectionItems = listOf("竖放", "平放")
        val baseDirectionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, baseDirectionItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(baseDirectionValues.indexOf(presetGyroBaseDirection).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val direction = baseDirectionValues.getOrNull(pos) ?: GyroBaseDirection.VERTICAL
                    presetGyroBaseDirection = direction
                    editorListener?.onGyroBaseDirectionChanged(direction)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        gyroBaseDirectionSpinner = baseDirectionSpinner
        gyroBaseDirectionLabel = tvGyroBaseDirection
        advanced.addView(tvGyroBaseDirection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt(); bottomMargin = (2f * density).toInt() })
        advanced.addView(baseDirectionSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt(); bottomMargin = (12f * density).toInt() })

        // ── Gyro coordinate system ──
        val tvGyroCoordinateSystem = TextView(context).apply {
            text = "坐标系"
            setTextColor(-0x1)
            textSize = 15f
            setPadding(0, (8f * density).toInt(), 0, 0)
        }
        advanced.addView(tvGyroCoordinateSystem)

        val gyroCoordinateSystemItems = listOf("偏航", "滚转", "偏航+滚转", "世界空间")
        val coordinateSystemSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, gyroCoordinateSystemItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(coordinateSystemValues.indexOf(presetGyroCoordinateSystem).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val coordinateSystem = coordinateSystemValues.getOrNull(pos) ?: GyroCoordinateSystem.YAW_ROLL
                    presetGyroCoordinateSystem = coordinateSystem
                    editorListener?.onGyroCoordinateSystemChanged(coordinateSystem)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        gyroCoordinateSystemSpinner = coordinateSystemSpinner
        advanced.addView(coordinateSystemSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4f * density).toInt(); bottomMargin = (12f * density).toInt() })

        // ── Gyro sensitivity (only for mouse/stick mapping) ──
        addSeekbar(advanced, "灵敏度", presetGyroModeSensitivity, 1, 100, onChange = {
            presetGyroModeSensitivity = it
            editorListener?.onGyroModeSensitivityChanged(it)
        })

        // ── Gyro dead zone ──
        addSeekbar(advanced, "陀螺仪死区(%)", presetGyroDeadZone, 0, 100, onChange = {
            presetGyroDeadZone = it
            editorListener?.onGyroDeadZoneChanged(it)
        })

        // ── Gyro reverse dead zone ──
        addSeekbar(advanced, "陀螺仪反死区(%)", presetGyroReverseDeadZone, 0, 100, onChange = {
            presetGyroReverseDeadZone = it
            editorListener?.onGyroReverseDeadZoneChanged(it)
        })

        container.addView(advanced)

        updateGyroModeVisibility()

        buttonParamsInner.addView(container)
    }

    private fun clearGlobalSettingsPanel() {
        globalSettingsContainer?.removeAllViews()
        globalSettingsContainer = null
        gyroAdvancedContainer = null
    }

    private fun updateGyroModeVisibility() {
        val needAdvanced = presetGyroMode != com.zyz4.gkme.model.GyroMode.HANDHELD
        val vis = if (needAdvanced) View.VISIBLE else View.GONE
        gyroAdvancedContainer?.visibility = vis

        // Coordinate system only visible for gyro mouse/stick modes (not accelerometer)
        val needCoordSystem = presetGyroMode == com.zyz4.gkme.model.GyroMode.MOUSE ||
                presetGyroMode == com.zyz4.gkme.model.GyroMode.LEFT_STICK ||
                presetGyroMode == com.zyz4.gkme.model.GyroMode.RIGHT_STICK
        gyroCoordinateSystemSpinner?.visibility = if (needCoordSystem) View.VISIBLE else View.GONE

        // Base direction only visible for accelerometer stick modes
        val needBaseDirection = presetGyroMode == com.zyz4.gkme.model.GyroMode.ACCELEROMETER_LEFT_STICK ||
                presetGyroMode == com.zyz4.gkme.model.GyroMode.ACCELEROMETER_RIGHT_STICK
        gyroBaseDirectionSpinner?.visibility = if (needBaseDirection) View.VISIBLE else View.GONE
        gyroBaseDirectionLabel?.visibility = if (needBaseDirection) View.VISIBLE else View.GONE
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
    fun showAnimated() {
        hidingPanel = false
        animate().cancel()
        alpha = 0f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .setDuration(PANEL_FADE_IN_MS)
            .setInterpolator(easeOutQuint())
            .start()
    }

    /** Fades the whole panel out, then hides it. Used when exiting layout edit mode. */
    fun hideAnimated() {
        hidingPanel = true
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(PANEL_FADE_OUT_MS)
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
            text = getChineseName(buttonId)
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
            addSeekbar(buttonParamsInner, "区域宽度", button.followAreaW, 1, maxAw, onChange = { value ->
                var updated = currentButton?.copy(followAreaW = value) ?: return@addSeekbar
                if (touchpadAdjust) updated = shrinkTouchpadToArea(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
            addSeekbar(buttonParamsInner, "区域高度", button.followAreaH, 1, maxAh, onChange = { value ->
                var updated = currentButton?.copy(followAreaH = value) ?: return@addSeekbar
                if (touchpadAdjust) updated = shrinkTouchpadToArea(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
            addSeekbar(buttonParamsInner, "矩形区域不透明度(%)", button.followAreaOpacity.coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    currentButton = currentButton?.copy(followAreaOpacity = value.coerceIn(0, 100))
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onOpacityPreviewStart(buttonId, true) },
                onStopTracking = { editorListener?.onOpacityPreviewEnd(buttonId) }
            )
            if (!touchpadAdjust) {
                val cbFollowOverlap = CheckBox(context).apply {
                    text = "触发矩形区域重叠触发"
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
            addSeekbar(buttonParamsInner, "大小", button.width, 1, 40, onChange = { value ->
                currentButton = currentButton?.copy(width = value, height = value)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
        } else {
            val isSwapped = button.rotation == 90 || button.rotation == 270
            // When the extended range is enabled, growing the touchpad grows the rectangle.
            val areaEnabled = button.followAreaEnabled && isTouchpadId(buttonId)
            val sizeMax = maxOf(40, GamepadLayout.GRID_COLS)
            addSeekbar(buttonParamsInner, "宽度", if (isSwapped) button.height else button.width, 1, sizeMax, onChange = { value ->
                var updated = currentButton?.let { if (isSwapped) it.copy(height = value) else it.copy(width = value) } ?: return@addSeekbar
                if (areaEnabled) updated = growAreaToContain(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
            addSeekbar(buttonParamsInner, "高度", if (isSwapped) button.width else button.height, 1, sizeMax, onChange = { value ->
                var updated = currentButton?.let { if (isSwapped) it.copy(width = value) else it.copy(height = value) } ?: return@addSeekbar
                if (areaEnabled) updated = growAreaToContain(updated)
                currentButton = updated
                editorListener?.onButtonUpdated(buttonId, updated)
            })
        }

        addRotationButtons(buttonParamsInner, buttonId, density, isSettingsButton(buttonId))

        // ── Opacity (hidden for settings button) ──
        if (!isSettingsButton(buttonId)) {
            addSeekbar(buttonParamsInner, "空闲时不透明度(%)", button.idleOpacity.coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    currentButton = currentButton?.copy(idleOpacity = value.coerceIn(0, 100))
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onOpacityPreviewStart(buttonId, true) },
                onStopTracking = { editorListener?.onOpacityPreviewEnd(buttonId) }
            )
            addSeekbar(buttonParamsInner, "操作时不透明度(%)", button.activeOpacity.coerceIn(0, 100), 0, 100,
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
                text = "重叠区域触发"
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
                text = "用于激活陀螺仪"
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
                text = "自动保持"
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
                text = "双击按下"
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
                text = "扩展触摸范围"
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
                val btnEnterAdjust = Button(context).apply {
                    text = "进入调节"
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
                buttonParamsInner, "单击", MouseGestureAction.TAP_ACTIONS, button.singleTapAction,
            ) { action ->
                currentButton = currentButton?.copy(singleTapAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, "双指点击", MouseGestureAction.TAP_ACTIONS, button.twoFingerTapAction,
            ) { action ->
                currentButton = currentButton?.copy(twoFingerTapAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, "三指点击", MouseGestureAction.TAP_ACTIONS, button.threeFingerTapAction,
            ) { action ->
                currentButton = currentButton?.copy(threeFingerTapAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, "双击后滑动", MouseGestureAction.SWIPE_ACTIONS, button.doubleTapDragAction,
            ) { action ->
                currentButton = currentButton?.copy(doubleTapDragAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, "单指滑动", MouseGestureAction.SWIPE_ACTIONS, button.oneFingerSwipeAction,
            ) { action ->
                currentButton = currentButton?.copy(oneFingerSwipeAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, "双指滑动", MouseGestureAction.SWIPE_ACTIONS, button.twoFingerSwipeAction,
            ) { action ->
                currentButton = currentButton?.copy(twoFingerSwipeAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addMouseActionSpinner(
                buttonParamsInner, "三指滑动", MouseGestureAction.SWIPE_ACTIONS, button.threeFingerSwipeAction,
            ) { action ->
                currentButton = currentButton?.copy(threeFingerSwipeAction = action)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbarFloat(buttonParamsInner, "鼠标灵敏度", button.mouseSensitivity, 0.1f, 3f, 0.05f) { v ->
                currentButton = currentButton?.copy(mouseSensitivity = v)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbar(buttonParamsInner, "滑动判定距离", button.mouseMoveSlop, 0, 20, onChange = { v ->
                currentButton = currentButton?.copy(mouseMoveSlop = v)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
            addSeekbarFloat(buttonParamsInner, "滚动灵敏度", button.scrollSensitivity, 0.01f, 1f, 0.01f) { v ->
                currentButton = currentButton?.copy(scrollSensitivity = v)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbarFloat(buttonParamsInner, "指针加速度", button.mouseAcceleration?.get(1) ?: 0f, 0f, 0.5f, 0.01f) { v ->
                currentButton = currentButton?.copy(mouseAcceleration = listOf(1f, v))
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            val cbInvert = CheckBox(context).apply {
                text = "反转纵向滚动"
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.invertScrollV
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(invertScrollV = isChecked)) }
                }
            }
            buttonParamsInner.addView(cbInvert, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
            val cbInvertH = CheckBox(context).apply {
                text = "反转横向滚动"
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
                text = "矩形区域内跟随"
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
                val btnEnterAdjust = Button(context).apply {
                    text = "进入调节"
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

            val curveH = (200f * density).toInt()

            var resolvedDeadZone = button.deadZone
            var resolvedReverseDeadZone = button.reverseDeadZone

            val btnDeadZoneRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val btnDzSeekbar = createSimpleSeekbar("死区(%)", button.deadZone, 0, 100, { value ->
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
            val btnRdzSeekbar = createSimpleSeekbar("反死区(%)", button.reverseDeadZone, 0, 100, { value ->
                currentButton = currentButton?.copy(reverseDeadZone = value)
                resolvedReverseDeadZone = value
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            })
            btnRdzRow.addView(btnRdzSeekbar)
            buttonParamsInner.addView(btnRdzRow)

            val tvCurve = TextView(context).apply {
                text = "灵敏度曲线"
                setTextColor(-0x444445)
                textSize = 13f
            }
            buttonParamsInner.addView(tvCurve, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10f * density).toInt(); bottomMargin = (4f * density).toInt() })

            val curveView = CurveEditorView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, curveH)
                setFromFlatList(button.sensitivityCurve)
                onPointsChanged = { newList ->
                    currentButton?.let {
                        editorListener?.onButtonUpdated(buttonId, it.copy(sensitivityCurve = newList))
                    }
                }
            }
            buttonParamsInner.addView(curveView)

            val curveBtnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val btnDeletePoint = Button(context).apply {
                text = "删除选中点"
                setTextColor(-0x1)
                textSize = 12f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener { curveView.deleteSelected() }
            }
            val btnResetCurve = Button(context).apply {
                text = "重置为直线"
                setTextColor(-0x1)
                textSize = 12f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener {
                    currentButton = currentButton?.copy(sensitivityCurve = null)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    curveView.setFromFlatList(null)
                }
            }
            curveBtnRow.addView(btnDeletePoint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (4f * density).toInt() })
            curveBtnRow.addView(btnResetCurve, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttonParamsInner.addView(curveBtnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })
        }
        if (buttonId == "dpadPad") {
            // ── Rectangular area follow ──
            val cbFollowArea = CheckBox(context).apply {
                text = "矩形区域内跟随"
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
                val btnEnterAdjust = Button(context).apply {
                    text = "进入调节"
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
                text = "矩形区域内跟随"
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
                val btnEnterAdjust = Button(context).apply {
                    text = "进入调节"
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
                text = "滑动触发"
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
                    text = "模拟线性扳机"
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
                    text = "滑动方向"
                    setTextColor(-0x444445)
                    textSize = 13f
                }
                linearContainer.addView(tvDirection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

                val directionItems = listOf("向下", "向上", "向左", "向右")
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
                    text = "扳机行程"
                    setTextColor(-0x444445)
                    textSize = 13f
                }
                linearContainer.addView(tvTravel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val travelSeekbar = createSimpleSeekbarLinear("行程", button.travelDistance, 1, 40, { value ->
                    currentButton = currentButton?.copy(travelDistance = value)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                })
                linearContainer.addView(travelSeekbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

                // visibility controlled dynamically
                val updateLinearVisibility = { enabled: Boolean ->
                    val vis = if (enabled) View.VISIBLE else View.GONE
                    tvDirection.visibility = vis
                    directionSpinner.visibility = vis
                    tvTravel.visibility = vis
                    travelSeekbar.visibility = vis
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
                text = "显示文本"
                setTextColor(-0x444445)
                textSize = 13f
            }
            buttonParamsInner.addView(tvCustomLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10f * density).toInt() })

            val safeCustomText = button.customText ?: "自定义"
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
                        val newText = s.toString().ifEmpty { "自定义" }
                        currentButton = currentButton?.copy(customText = newText)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    }
                })
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newText = text.toString().ifEmpty { "自定义" }
                        currentButton = currentButton?.copy(customText = newText)
                        currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                    }
                }
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        val newText = text.toString().ifEmpty { "自定义" }
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
                text = "映射键值"
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
            val btnAddOutput = Button(context).apply {
                text = "添加"
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
            val btnClearOutput = Button(context).apply {
                text = "清空"
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
        }

        if (!isSettingsButton(buttonId)) {
            val btnDelete = Button(context).apply {
                text = "删除"
                setTextColor(-0x1)
                textSize = 14f
                setBackgroundResource(R.drawable.button_flat)
                setOnClickListener { editorListener?.onDeleteButton(buttonId) }
            }
            buttonParamsInner.addView(btnDelete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16f * density).toInt() })
        }
    }

    private fun buildKeypadParams(density: Float) {
        val id = currentButton?.id ?: return
        val cb = currentButton ?: return
        val texts = ButtonPosition.keypadTextsOf(cb)
        val bits = ButtonPosition.keypadBitsOf(cb)

        // ── 方向模式下拉框 ──
        val modeLabel = TextView(context).apply {
            text = "方向模式"
            setTextColor(-0x1)
            textSize = 14f
        }
        buttonParamsInner.addView(modeLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })

        val modeItems = listOf("4方向", "8方向")
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

        val dirs = mutableListOf("上方向" to 0, "下方向" to 1, "左方向" to 2, "右方向" to 3)
        if (cb.keypadEightWay) {
            dirs += listOf("左上方向" to 5, "右上方向" to 6, "左下方向" to 7, "右下方向" to 8)
        }
        // 双击中心固定放最后，保证其文本输入框与下方映射编辑器相邻。
        dirs += "双击中心" to 4

        for ((name, idx) in dirs) {
            buildKeypadRegionParam(density, name, idx, texts[idx], bits[idx])
        }

        // Center region output editor (center double-click is always enabled)
        buildKeypadBitsEditor(density, bits[4]) { newBits ->
            val cb2 = currentButton ?: return@buildKeypadBitsEditor
            val b = ButtonPosition.keypadBitsOf(cb2).toMutableList()
            b[4] = newBits
            currentButton = cb2.copy(keypadBits = b)
            currentButton?.let { editorListener?.onButtonUpdated(id, it) }
        }
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
                    val texts = ButtonPosition.keypadTextsOf(cb).toMutableList()
                    texts[regionFinal] = s.toString()
                    currentButton = cb.copy(keypadTexts = texts)
                    currentButton?.let { editorListener?.onButtonUpdated(id, it) }
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val texts = ButtonPosition.keypadTextsOf(cb).toMutableList()
                    texts[regionIdx] = text.toString()
                    currentButton = cb.copy(keypadTexts = texts)
                    currentButton?.let { editorListener?.onButtonUpdated(id, it) }
                }
            }
        }
        buttonParamsInner.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (2f * density).toInt() })

        if (regionIdx != 4) {
            buildKeypadBitsEditor(density, currentBits) { newBits ->
                val cb2 = currentButton ?: return@buildKeypadBitsEditor
                val b = ButtonPosition.keypadBitsOf(cb2).toMutableList()
                b[regionIdx] = newBits
                currentButton = cb2.copy(keypadBits = b)
                currentButton?.let { editorListener?.onButtonUpdated(id, it) }
            }
        }
    }

    private fun buildKeypadBitsEditor(density: Float, initialBits: List<Int>, onBitsChanged: (List<Int>) -> Unit) {
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
        val btnAdd = Button(context).apply {
            text = "添加"
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
        val btnClear = Button(context).apply {
            text = "清空"
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
            text = "逆时针"
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
            text = "顺时针"
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

        val items = actions.map { it.displayName }
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
