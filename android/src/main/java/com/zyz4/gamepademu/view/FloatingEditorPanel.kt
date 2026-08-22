package com.zyz4.gamepademu.view

import android.content.Context
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
import com.zyz4.gamepademu.R
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.SlideDirection

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
        fun onTransparencyPreviewStart(buttonId: String, isIdle: Boolean)
        fun onTransparencyPreviewEnd(buttonId: String)
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
            gyroSpinner?.setSelection((value?.ordinal?.plus(1)) ?: 0)
        }

    private var gyroSpinner: Spinner? = null

    private val BUTTON_IDS = setOf(
        "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
        "btnY", "btnA", "btnX", "btnB",
        "btnLT", "btnLB", "btnRT", "btnRB",
        "btnSelect", "btnHome", "btnMenu",
        "btnTouchpad", "btnLS", "btnRS", "btnMic",
    )

    private var currentButton: ButtonPosition? = null
    var isAdjustingFollowArea: Boolean = false
        set(value) {
            field = value
            val vis = if (value) View.GONE else View.VISIBLE
            actionBtnRow?.visibility = vis
            gyroSpinnerRow?.visibility = vis
            separatorLine?.visibility = vis
        }
    private var panelX = 0f
    private var panelY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false

    private lateinit var paramsContainer: LinearLayout
    private lateinit var buttonParamsInner: LinearLayout
    private var actionBtnRow: LinearLayout? = null
    private var gyroSpinnerRow: View? = null
    private var separatorLine: View? = null
    private var contentW = 0
    private var panelW = 0

    private fun isButton(id: String): Boolean {
        val base = id.substringBefore("_")
        return base in BUTTON_IDS || base.startsWith("btnCustom")
    }

    private fun isSettingsButton(id: String): Boolean = id == GamepadLayout.SETTINGS_BUTTON_ID

    private fun isTouchpadId(id: String): Boolean = id.substringBefore("_") == "touchpad"
    private fun isMousepadId(id: String): Boolean = id.substringBefore("_") == "mousepad"

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
            "btnY" -> "Y"
            "btnA" -> "A"
            "btnX" -> "X"
            "btnB" -> "B"
            "btnLT" -> "左扳机"
            "btnLB" -> "左肩键"
            "btnRT" -> "右扳机"
            "btnRB" -> "右肩键"
            "leftJoystick" -> "左摇杆"
            "rightJoystick" -> "右摇杆"
            "btnSelect" -> "选择"
            "btnHome" -> "主页"
            "btnMenu" -> "菜单"
            "btnTouchpad" -> "触摸板按下"
            "btnLS" -> "左摇杆按下"
            "btnRS" -> "右摇杆按下"
            "touchpad" -> "触摸板"
            "mousepad" -> "鼠标"
            "dpadPad" -> "一体十字键/自定义按键盘"
            "customKeypad" -> "自定义按键盘"
            "btnCustomCircle" -> "自定义(圆)"
            "btnCustomRect" -> "自定义(方)"
            "btnMic" -> "麦克风静音"
            "btnSettings" -> "设置按钮"
            "btn" -> "按钮"
            "joystick" -> "摇杆"
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
        btnRow.addView(btnSave, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = btnSpacing })
        btnRow.addView(btnDiscard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = btnSpacing })
        btnRow.addView(btnAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        paramsContainer.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

        // Gyro orientation selector
        val gyroContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        gyroSpinnerRow = gyroContainer
        buildGyroSelector(density, gyroContainer)
        paramsContainer.addView(gyroContainer)

        // Separator
        val sep = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1f * density).toInt()).apply {
                bottomMargin = (8f * density).toInt()
            }
            background = GradientDrawable().apply { setColor(-0x444445) }
        }
        separatorLine = sep
        paramsContainer.addView(sep)

        // Button-specific params
        buttonParamsInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        paramsContainer.addView(buttonParamsInner)

        scroll.addView(paramsContainer)
        root.addView(scroll)
        addView(root)
    }

    private fun buildGyroSelector(density: Float, container: LinearLayout) {
        val tv = TextView(context).apply {
            text = "体感握持方向"
            setTextColor(-0x1)
            textSize = 14f
        }
        container.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6f * density).toInt() })

        val items = listOf("不指定", "横屏", "竖屏", "倒置竖屏")
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
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

    /** Collapses the panel to show only the drag handle + toggle button. */
    fun setCollapsed(collapsed: Boolean) {
        if (this.collapsed == collapsed) return
        this.collapsed = collapsed
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        scrollView?.visibility = if (collapsed) View.GONE else View.VISIBLE
        toggleBtn?.setImageResource(if (collapsed) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up)
        lp.height = if (collapsed) {
            val headerH = headerView?.height ?: 0
            (if (headerH > 0) headerH else (36f * resources.displayMetrics.density).toInt()) +
                paddingTop + paddingBottom
        } else {
            expandedHeight
        }
        requestLayout()
        onToggleCollapsed?.invoke(collapsed)
    }

    fun clearParameters() {
        buttonParamsInner.removeAllViews()
    }

    fun showParameters(buttonId: String, button: ButtonPosition) {
        currentButton = button
        val density = context.resources.displayMetrics.density
        setCollapsed(false)

        buttonParamsInner.removeAllViews()

        val tvId = TextView(context).apply {
            text = getChineseName(buttonId)
            setTextColor(-0x1)
            textSize = 16f
        }
        buttonParamsInner.addView(tvId, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

        if (isAdjustingFollowArea) {
            // Only show follow area dimensions + follow area transparency + return button
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
            addSeekbar(buttonParamsInner, "矩形区域透明度(%)", (button.followAreaTransparency * 100 / 255).coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    val transVal = (value * 255 / 100).coerceIn(0, 255)
                    currentButton = currentButton?.copy(followAreaTransparency = transVal)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onTransparencyPreviewStart(buttonId, true) },
                onStopTracking = { editorListener?.onTransparencyPreviewEnd(buttonId) }
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

            val returnText = when {
                touchpadAdjust -> "返回触摸板调节"
                dpadPadAdjust -> "返回十字键调节"
                keypadAdjust -> "返回按键盘调节"
                else -> "返回摇杆调节"
            }
            val btnReturn = Button(context).apply {
                text = returnText
                setTextColor(-0x1)
                textSize = 13f
                setBackgroundResource(R.drawable.button_flat)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16f * density).toInt() }
                setOnClickListener { editorListener?.onExitFollowAreaAdjust() }
            }
            buttonParamsInner.addView(btnReturn)
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

        // ── Transparency (hidden for settings button) ──
        if (!isSettingsButton(buttonId)) {
            addSeekbar(buttonParamsInner, "空闲时透明度(%)", (button.idleTransparency * 100 / 255).coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    val transVal = (value * 255 / 100).coerceIn(0, 255)
                    currentButton = currentButton?.copy(idleTransparency = transVal)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onTransparencyPreviewStart(buttonId, true) },
                onStopTracking = { editorListener?.onTransparencyPreviewEnd(buttonId) }
            )
            addSeekbar(buttonParamsInner, "操作时透明度(%)", (button.activeTransparency * 100 / 255).coerceIn(0, 100), 0, 100,
                onChange = { value ->
                    val transVal = (value * 255 / 100).coerceIn(0, 255)
                    currentButton = currentButton?.copy(activeTransparency = transVal)
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
                },
                onStartTracking = { editorListener?.onTransparencyPreviewStart(buttonId, false) },
                onStopTracking = { editorListener?.onTransparencyPreviewEnd(buttonId) }
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
            addSeekbarFloat(buttonParamsInner, "鼠标灵敏度", button.mouseSensitivity, 0.1f, 3f, 0.05f) { v ->
                currentButton = currentButton?.copy(mouseSensitivity = v)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbarFloat(buttonParamsInner, "滚动灵敏度", button.scrollSensitivity, 0.01f, 1f, 0.01f) { v ->
                currentButton = currentButton?.copy(scrollSensitivity = v)
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
        if (isButton(buttonId)) {
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
                    val initialPos = when (defDir) {
                        SlideDirection.DOWN -> 0
                        SlideDirection.UP -> 1
                        SlideDirection.LEFT -> 2
                        SlideDirection.RIGHT -> 3
                        else -> 0
                    }
                    setSelection(initialPos)
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
                text = "传出值"
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
                            text = getBitName(bit)
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
        val dirs = listOf("上方向" to 0, "下方向" to 1, "左方向" to 2, "右方向" to 3, "中心" to 4)

        for ((name, idx) in dirs) {
            buildKeypadRegionParam(density, name, idx, texts[idx], bits[idx])
        }

        addKeypadCenterDoubleClickParams(density, cb)
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
                var b = cb2.keypadBits ?: ButtonPosition.KEYPAD_DEFAULT_BITS
                val updated = b.toMutableList()
                updated[regionIdx] = newBits
                currentButton = cb2.copy(keypadBits = updated)
                currentButton?.let { editorListener?.onButtonUpdated(id, it) }
            }
        }
    }

    private fun addKeypadCenterDoubleClickParams(density: Float, cb: ButtonPosition) {
        val id = cb.id
        // Center double-click toggle + its output value editor
        val cbDC = CheckBox(context).apply {
            text = "中心双击按下"
            setTextColor(-0x444445)
            textSize = 14f
            isChecked = cb.keypadCenterDoubleClick
            setOnCheckedChangeListener { _, isChecked ->
                currentButton = cb.copy(keypadCenterDoubleClick = isChecked)
                currentButton?.let { editorListener?.onButtonUpdated(id, it) }
                showParameters(id, currentButton!!)
            }
        }
        buttonParamsInner.addView(cbDC, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16f * density).toInt() })

        if (cb.keypadCenterDoubleClick) {
            val centerBits = ButtonPosition.keypadBitsOf(cb)[4]
            buildKeypadBitsEditor(density, centerBits) { newBits ->
                val cb2 = currentButton ?: return@buildKeypadBitsEditor
                var b = cb2.keypadBits ?: ButtonPosition.KEYPAD_DEFAULT_BITS
                val updated = b.toMutableList()
                updated[4] = newBits
                currentButton = cb2.copy(keypadBits = updated)
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
                            text = getBitName(bit)
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

    private fun getBitName(bit: Int): String {
        return when (bit) {
            GamepadState.A -> "A"
            GamepadState.B -> "B"
            GamepadState.X -> "X"
            GamepadState.Y -> "Y"
            GamepadState.LB -> "LB"
            GamepadState.RB -> "RB"
            GamepadState.LT -> "LT"
            GamepadState.RT -> "RT"
            GamepadState.SELECT -> "选择"
            GamepadState.START -> "菜单"
            GamepadState.L3 -> "左摇杆"
            GamepadState.R3 -> "右摇杆"
            GamepadState.HOME -> "主页"
            GamepadState.TOUCHPAD_CLICK -> "触摸板"
            GamepadState.DPAD_BIT_UP -> "上"
            GamepadState.DPAD_BIT_DOWN -> "下"
            GamepadState.DPAD_BIT_LEFT -> "左"
            GamepadState.DPAD_BIT_RIGHT -> "右"
            GamepadState.MIC_MUTE -> "麦克风静音"
            else -> "位$bit"
        }
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
