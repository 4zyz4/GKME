package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.TextView
import com.zyz4.gamepademu.R
import com.zyz4.gamepademu.model.ButtonPosition

class FloatingEditorPanel(context: Context) : FrameLayout(context) {

    interface EditorListener {
        fun onSave()
        fun onDiscard()
        fun onAddButton()
        fun onDeleteButton(buttonId: String)
        fun onButtonUpdated(buttonId: String, updated: ButtonPosition)
    }

    var editorListener: EditorListener? = null

    private val BUTTON_IDS = setOf(
        "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
        "btnY", "btnA", "btnX", "btnB",
        "btnLT", "btnLB", "btnRT", "btnRB",
        "btnSelect", "btnHome", "btnMenu",
    )

    private var currentButton: ButtonPosition? = null
    private var panelX = 0f
    private var panelY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false

    private lateinit var paramsContainer: LinearLayout
    private var contentW = 0
    private var panelW = 0

    private fun isButton(id: String) = id.substringBefore("_") in BUTTON_IDS

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
            "touchpad" -> "触摸板"
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

        setPadding(0, 0, 0, 0)
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
        lp.height = (context.resources.displayMetrics.heightPixels * 0.8f).toInt()
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
            orientation = LinearLayout.VERTICAL
            setOnTouchListener { _, event -> handleDrag(event); true }
        }

        val gripBar = buildGripBar(density)
        header.addView(gripBar)

        val btnRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(contentW, ViewGroup.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.HORIZONTAL
            setPadding((8f * density).toInt(), 0, (8f * density).toInt(), (10f * density).toInt())
        }

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
        val btnSpacing = (4f * density).toInt()
        btnRow.addView(btnSave, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = btnSpacing })
        btnRow.addView(btnDiscard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = btnSpacing })
        btnRow.addView(btnAdd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(btnRow)
        root.addView(header)

        val scroll = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(contentW, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }
        paramsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8f * density).toInt(), (12f * density).toInt(), (8f * density).toInt(), (12f * density).toInt())
        }
        scroll.addView(paramsContainer)
        root.addView(scroll)
        addView(root)
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

    fun clearParameters() {
        paramsContainer.removeAllViews()
    }

    fun showParameters(buttonId: String, button: ButtonPosition) {
        currentButton = button
        val density = context.resources.displayMetrics.density

        paramsContainer.removeAllViews()

        val tvId = TextView(context).apply {
            text = getChineseName(buttonId)
            setTextColor(-0x1)
            textSize = 16f
        }
        paramsContainer.addView(tvId, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8f * density).toInt() })

        if (button.lockAspect) {
            addSeekbar("大小", button.width, 1, 40) { value ->
                currentButton = currentButton?.copy(width = value, height = value)
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
        } else {
            val isSwapped = button.rotation == 90 || button.rotation == 270
            addSeekbar("宽度", if (isSwapped) button.height else button.width, 1, 40) { value ->
                if (isSwapped) {
                    currentButton = currentButton?.copy(height = value)
                } else {
                    currentButton = currentButton?.copy(width = value)
                }
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
            addSeekbar("高度", if (isSwapped) button.width else button.height, 1, 40) { value ->
                if (isSwapped) {
                    currentButton = currentButton?.copy(width = value)
                } else {
                    currentButton = currentButton?.copy(height = value)
                }
                currentButton?.let { editorListener?.onButtonUpdated(buttonId, it) }
            }
        }

        addRotationButtons(buttonId, density)
        if (isButton(buttonId)) {
            val cb = CheckBox(context).apply {
                text = "滑动触发"
                setTextColor(-0x444445)
                textSize = 14f
                isChecked = button.swipeTrigger
                setOnCheckedChangeListener { _, isChecked ->
                    currentButton?.let { editorListener?.onButtonUpdated(buttonId, it.copy(swipeTrigger = isChecked)) }
                }
            }
            paramsContainer.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8f * density).toInt() })
        }

        val btnDelete = Button(context).apply {
            text = "删除"
            setTextColor(-0x1)
            textSize = 14f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { editorListener?.onDeleteButton(buttonId) }
        }
        paramsContainer.addView(btnDelete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16f * density).toInt() })
    }

    private fun addRotationButtons(buttonId: String, density: Float) {
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
        paramsContainer.addView(row)
    }

    private fun addSeekbar(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
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
        paramsContainer.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4f * density).toInt() }
        }
        row2.addView(seek)
        paramsContainer.addView(row2)
    }
}
