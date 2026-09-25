package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.zyz4.gkme.R
import com.zyz4.gkme.easeOutQuint
import com.zyz4.gkme.model.GyroActivateMode
import com.zyz4.gkme.model.GyroBaseDirection
import com.zyz4.gkme.model.GyroCoordinateSystem
import com.zyz4.gkme.model.GyroMode
import com.zyz4.gkme.model.GyroOrientation
import com.zyz4.gkme.model.LayoutPreset

/**
 * Full-screen, per-layout "全局设置" page opened from the layout editor.
 *
 * Mirrors the structure of the app-level settings panel (left category sidebar +
 * right content) but only acts on the layout preset currently being edited. The
 * whole page is rendered at 80% opacity so the gamepad layout stays faintly
 * visible behind it.
 */
class LayoutGlobalSettingsPanel(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onClose()
        fun onGyroOrientationChanged(orientation: GyroOrientation?)
        fun onGyroBaseDirectionChanged(direction: GyroBaseDirection)
        fun onGyroCoordinateSystemChanged(coordinateSystem: GyroCoordinateSystem)
        fun onGyroModeChanged(mode: GyroMode)
        fun onGyroModeSensitivityChanged(value: Int)
        fun onGyroDeadZoneChanged(value: Int)
        fun onGyroReverseDeadZoneChanged(value: Int)
        fun onGyroActivateModeChanged(mode: GyroActivateMode)
    }

    var listener: Listener? = null

    /** Page opacity: 80% opaque so the layout behind remains faintly visible. */
    private val pageAlpha = 0.8f

    private var gyroOrientation: GyroOrientation? = null
    private var gyroBaseDirection = GyroBaseDirection.VERTICAL
    private var gyroCoordinateSystem = GyroCoordinateSystem.YAW_ROLL
    private var gyroMode = GyroMode.HANDHELD
    private var gyroModeSensitivity = 20
    private var gyroDeadZone = 0
    private var gyroReverseDeadZone = 0
    private var gyroActivateMode = GyroActivateMode.ALWAYS

    private var contentContainer: LinearLayout? = null
    private var gyroAdvancedContainer: LinearLayout? = null
    private var gyroCoordinateSystemSpinner: Spinner? = null
    private var gyroBaseDirectionSpinner: Spinner? = null
    private var gyroBaseDirectionLabel: TextView? = null

    private val baseDirectionValues = listOf(
        GyroBaseDirection.VERTICAL,
        GyroBaseDirection.HORIZONTAL,
    )

    private val coordinateSystemValues = listOf(
        GyroCoordinateSystem.YAW,
        GyroCoordinateSystem.ROLL,
        GyroCoordinateSystem.YAW_ROLL,
        GyroCoordinateSystem.WORLD,
    )

    private val mappingModeValues = listOf(
        GyroMode.HANDHELD,
        GyroMode.MOUSE,
        GyroMode.LEFT_STICK,
        GyroMode.RIGHT_STICK,
        GyroMode.ACCELEROMETER_LEFT_STICK,
        GyroMode.ACCELEROMETER_RIGHT_STICK,
    )

    init {
        visibility = View.GONE
        alpha = 0f
        isClickable = true
        isFocusable = true
        background = ColorDrawable(ColorUtils.setAlphaComponent(Color.BLACK, (pageAlpha * 255).toInt()))
        buildShell()
    }

    // ── Show / hide ─────────────────────────────────────────

    fun showForPreset(
        preset: LayoutPreset,
        baseDirection: GyroBaseDirection,
        coordinateSystem: GyroCoordinateSystem,
    ) {
        bindFromPreset(preset, baseDirection, coordinateSystem)
        animate().cancel()
        alpha = 0f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(easeOutQuint())
            .start()
    }

    fun hide(onEnd: (() -> Unit)? = null) {
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(easeOutQuint())
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                onEnd?.invoke()
            }
            .start()
    }

    private fun bindFromPreset(
        preset: LayoutPreset,
        baseDirection: GyroBaseDirection,
        coordinateSystem: GyroCoordinateSystem,
    ) {
        gyroOrientation = preset.gyroOrientation
        gyroBaseDirection = baseDirection
        gyroCoordinateSystem = coordinateSystem
        gyroMode = preset.gyroMode ?: GyroMode.HANDHELD
        gyroModeSensitivity = preset.gyroModeSensitivity ?: 20
        gyroDeadZone = preset.gyroDeadZone ?: 0
        gyroReverseDeadZone = preset.gyroReverseDeadZone ?: 0
        gyroActivateMode = preset.gyroActivateMode ?: GyroActivateMode.ALWAYS
        rebuildContent()
    }

    // ── Shell (sidebar + content) ───────────────────────────

    private fun buildShell() {
        val density = resources.displayMetrics.density
        val sidebarWidth = (120f * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ── Left sidebar ──
        val sidebar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(
                ColorUtils.setAlphaComponent(
                    androidx.core.content.ContextCompat.getColor(context, R.color.bg_surface),
                    (pageAlpha * 255).toInt()
                )
            )
            setPadding(0, (16f * density).toInt(), 0, 0)
        }
        root.addView(sidebar, LinearLayout.LayoutParams(sidebarWidth, ViewGroup.LayoutParams.MATCH_PARENT))

        val btnBack = Button(context).apply {
            text = "返回"
            gravity = Gravity.CENTER
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_back, 0, 0, 0)
            compoundDrawablePadding = (4f * density).toInt()
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_sidebar_item)
            stateListAnimator = null
            isSelected = false
            setOnClickListener { listener?.onClose() }
        }
        sidebar.addView(btnBack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44f * density).toInt()))

        sidebar.addView(View(context).apply {
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.bg_surface_light))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1f * density).toInt()).apply {
            topMargin = (8f * density).toInt()
            bottomMargin = (8f * density).toInt()
        })

        val btnGyro = Button(context).apply {
            text = "陀螺仪"
            gravity = Gravity.CENTER
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_sidebar_item)
            stateListAnimator = null
            isSelected = true
        }
        sidebar.addView(btnGyro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48f * density).toInt()))

        // ── Right content ──
        val contentHost = FrameLayout(context).apply {
            setPadding((16f * density).toInt(), (16f * density).toInt(), (16f * density).toInt(), (16f * density).toInt())
        }
        root.addView(contentHost, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val scroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(contentContainer)
        contentHost.addView(scroll)

        addView(root)
    }

    private fun rebuildContent() {
        val container = contentContainer ?: return
        container.removeAllViews()
        gyroAdvancedContainer = null
        gyroCoordinateSystemSpinner = null
        gyroBaseDirectionSpinner = null
        gyroBaseDirectionLabel = null

        val density = resources.displayMetrics.density

        // ── Gyro orientation ──
        buildGyroSelector(density, container)

        container.addView(View(context).apply {
            background = GradientDrawable().apply { setColor(-0x444445) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1f * density).toInt()).apply {
            topMargin = (4f * density).toInt()
            bottomMargin = (8f * density).toInt()
        })

        // ── Gyro activation mode ──
        container.addView(sectionLabel("陀螺仪激活方式", density))

        val gyroActivateItems = listOf("始终开启", "按下特定按钮开启")
        val gyroActivateSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, gyroActivateItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(gyroActivateMode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val mode = if (pos == 1) GyroActivateMode.BUTTON else GyroActivateMode.ALWAYS
                    if (mode != gyroActivateMode) {
                        gyroActivateMode = mode
                        listener?.onGyroActivateModeChanged(mode)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        container.addView(gyroActivateSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4f * density).toInt()
            bottomMargin = (12f * density).toInt()
        })

        // ── Gyro mapping mode ──
        container.addView(sectionLabel("陀螺仪行为", density))

        val modeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, gyroModeItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(mappingModeValues.indexOf(gyroMode).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val mode = mappingModeValues.getOrNull(pos) ?: GyroMode.HANDHELD
                    if (mode != gyroMode) {
                        gyroMode = mode
                        listener?.onGyroModeChanged(mode)
                    }
                    updateGyroModeVisibility()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        container.addView(modeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4f * density).toInt()
            bottomMargin = (12f * density).toInt()
        })

        // ── Advanced settings (hidden for 手柄陀螺仪) ──
        gyroAdvancedContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val advanced = gyroAdvancedContainer!!

        // Gyro base direction
        gyroBaseDirectionLabel = sectionLabel("基准方向", density)
        advanced.addView(gyroBaseDirectionLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4f * density).toInt()
            bottomMargin = (2f * density).toInt()
        })

        val baseDirectionItems = listOf("竖放", "平放")
        gyroBaseDirectionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, baseDirectionItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(baseDirectionValues.indexOf(gyroBaseDirection).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val direction = baseDirectionValues.getOrNull(pos) ?: GyroBaseDirection.VERTICAL
                    if (direction != gyroBaseDirection) {
                        gyroBaseDirection = direction
                        listener?.onGyroBaseDirectionChanged(direction)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        advanced.addView(gyroBaseDirectionSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4f * density).toInt()
            bottomMargin = (12f * density).toInt()
        })

        // Gyro coordinate system
        advanced.addView(sectionLabel("坐标系", density))

        val gyroCoordinateSystemItems = listOf("偏航", "滚转", "偏航+滚转", "世界空间")
        gyroCoordinateSystemSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, gyroCoordinateSystemItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(coordinateSystemValues.indexOf(gyroCoordinateSystem).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val coordinateSystem = coordinateSystemValues.getOrNull(pos) ?: GyroCoordinateSystem.YAW_ROLL
                    if (coordinateSystem != gyroCoordinateSystem) {
                        gyroCoordinateSystem = coordinateSystem
                        listener?.onGyroCoordinateSystemChanged(coordinateSystem)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        advanced.addView(gyroCoordinateSystemSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4f * density).toInt()
            bottomMargin = (12f * density).toInt()
        })

        // Sensitivity / dead zones
        addSeekbar(advanced, "灵敏度", gyroModeSensitivity, 1, 100) {
            gyroModeSensitivity = it
            listener?.onGyroModeSensitivityChanged(it)
        }
        addSeekbar(advanced, "陀螺仪死区(%)", gyroDeadZone, 0, 100) {
            gyroDeadZone = it
            listener?.onGyroDeadZoneChanged(it)
        }
        addSeekbar(advanced, "陀螺仪反死区(%)", gyroReverseDeadZone, 0, 100) {
            gyroReverseDeadZone = it
            listener?.onGyroReverseDeadZoneChanged(it)
        }

        container.addView(advanced)
        updateGyroModeVisibility()
    }

    private val gyroModeItems = listOf(
        "手柄陀螺仪", "陀螺仪转鼠标", "陀螺仪转左摇杆", "陀螺仪转右摇杆",
        "加速度计转左摇杆", "加速度计转右摇杆",
    )

    private fun sectionLabel(text: String, density: Float): TextView = TextView(context).apply {
        this.text = text
        setTextColor(-0x1)
        textSize = 15f
        setPadding(0, (8f * density).toInt(), 0, 0)
    }

    private fun buildGyroSelector(density: Float, container: LinearLayout) {
        val tv = TextView(context).apply {
            text = "体感握持方向"
            setTextColor(-0x1)
            textSize = 14f
        }
        container.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (6f * density).toInt()
        })

        val items = listOf("不指定", "横屏", "竖屏", "倒置竖屏")
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection((gyroOrientation?.ordinal?.plus(1)) ?: 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val orientation = when (pos) {
                        1 -> GyroOrientation.LANDSCAPE
                        2 -> GyroOrientation.PORTRAIT
                        3 -> GyroOrientation.PORTRAIT_INVERTED
                        else -> null
                    }
                    if (orientation != gyroOrientation) {
                        gyroOrientation = orientation
                        listener?.onGyroOrientationChanged(orientation)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        container.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (8f * density).toInt()
        })
    }

    private fun updateGyroModeVisibility() {
        val vis = if (gyroMode != GyroMode.HANDHELD) View.VISIBLE else View.GONE
        gyroAdvancedContainer?.visibility = vis

        val needCoordSystem = gyroMode == GyroMode.MOUSE ||
            gyroMode == GyroMode.LEFT_STICK ||
            gyroMode == GyroMode.RIGHT_STICK
        gyroCoordinateSystemSpinner?.visibility = if (needCoordSystem) View.VISIBLE else View.GONE

        val needBaseDirection = gyroMode == GyroMode.ACCELEROMETER_LEFT_STICK ||
            gyroMode == GyroMode.ACCELEROMETER_RIGHT_STICK
        gyroBaseDirectionSpinner?.visibility = if (needBaseDirection) View.VISIBLE else View.GONE
        gyroBaseDirectionLabel?.visibility = if (needBaseDirection) View.VISIBLE else View.GONE
    }

    private fun addSeekbar(container: LinearLayout, label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
        val density = resources.displayMetrics.density
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
        row1.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        row1.addView(et, LinearLayout.LayoutParams(0, btnSize, 1f).apply {
            leftMargin = (8f * density).toInt()
            rightMargin = (4f * density).toInt()
        })
        row1.addView(btnMinus, LinearLayout.LayoutParams(btnSize, btnSize).apply { rightMargin = (4f * density).toInt() })
        row1.addView(btnPlus, LinearLayout.LayoutParams(btnSize, btnSize))
        container.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (4f * density).toInt()
            }
        }
        row2.addView(seek)
        container.addView(row2)
    }

    private companion object {
        const val SHOW_DURATION_MS = 200L
        const val HIDE_DURATION_MS = 150L
    }
}
