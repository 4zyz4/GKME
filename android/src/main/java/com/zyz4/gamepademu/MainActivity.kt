package com.zyz4.gamepademu

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import com.zyz4.gamepademu.view.WrapContentGridView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.HapticEffect
import com.zyz4.gamepademu.model.LayoutPreset
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.model.TouchPoint
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.VibrationMotor
import com.zyz4.gamepademu.model.VibrationType
import com.zyz4.gamepademu.service.ConnectionPhase
import com.zyz4.gamepademu.service.BluetoothTransportType
import com.zyz4.gamepademu.GamepadViewModel.PresetInfo
import com.zyz4.gamepademu.view.GamepadLayout
import com.zyz4.gamepademu.view.JoystickView
import com.zyz4.gamepademu.view.RotatableButton
import com.zyz4.gamepademu.view.PresetPreviewView
import com.zyz4.gamepademu.view.FloatingEditorPanel
import com.zyz4.gamepademu.input.PhysicalControllerHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: GamepadViewModel by viewModels()
    private lateinit var gamepadLayout: GamepadLayout
    private lateinit var floatingEditor: FloatingEditorPanel
    private lateinit var btnToggleEditor: ImageButton
    private var editorVisible = true
    private val controlViews = mutableMapOf<String, View>()
    private val touchpadLabels = mutableListOf<TextView>()
    private var discoverableRequested = false

    private var mediaSession: MediaSession? = null

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.all { it.value }) {
            viewModel.startServer()
        } else {
            showToast("需要蓝牙权限才能使用蓝牙模式")
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startServer()
        } else {
            showToast("需要开启蓝牙才能使用蓝牙模式")
        }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> }

    private val importPresetLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importPresetFromUri(it) }
    }

    private val exportPresetLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportPresetToUri(it) }
    }

    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    private val physicalControllerHandler by lazy { PhysicalControllerHandler(this) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            checkDeviceRotation()
        }
    }

    @Suppress("DEPRECATION")
    private fun checkDeviceRotation() {
        val inverted = windowManager.defaultDisplay.rotation == Surface.ROTATION_270
        viewModel.setDeviceInverted(inverted)
        if (inSettings) updateGyroLandscapeInvertedNote(inverted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableEdgeToEdge()
        gamepadLayout = findViewById(R.id.gamepadLayout)
        setupMediaSession()
        setupFloatingEditor()
        setupGamepadLayoutListener()
        createAllControls()
        setupSettings()
        observeState()
        autoStartService()
        displayManager.registerDisplayListener(displayListener, null)
        checkDeviceRotation()
        physicalControllerHandler.start()
    }

    override fun onDestroy() {
        physicalControllerHandler.stop()
        mediaSession?.release()
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    private var pointerCaptureNeeded = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pointerCaptureNeeded) {
            Handler(Looper.getMainLooper()).postDelayed({
                gamepadLayout.setTouchpadCaptureMode(true)
            }, 500)
        }
    }

    // ── MediaSession (intercept volume keys before system) ──

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "GamepadEmu").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 0f)
                .build()
            )
            val volumeProvider = object : VolumeProvider(
                VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50
            ) {
                override fun onAdjustVolume(direction: Int) {
                    // Suppress system volume adjustment
                }
            }
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }
    }

    // ── Floating Editor ──────────────────────────────────────

    private fun setupFloatingEditor() {
        floatingEditor = FloatingEditorPanel(this).apply {
            visibility = View.GONE
            editorListener = object : FloatingEditorPanel.EditorListener {
                override fun onSave() {
                    viewModel.saveCurrentPreset(gamepadLayout.getPreset())
                    gamepadLayout.exitEditMode()
                    viewModel.updateEditMode(false)
                    applyPreset(viewModel.currentPreset.value)
                }

                override fun onDiscard() {
                    if (!gamepadLayout.hasUnsavedChanges()) {
                        gamepadLayout.exitEditMode()
                        viewModel.updateEditMode(false)
                        applyPreset(viewModel.currentPreset.value)
                        return
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("放弃修改")
                        .setMessage("确定放弃当前布局修改？")
                        .setPositiveButton("放弃") { _, _ ->
                            gamepadLayout.discardToSnapshot()
                            gamepadLayout.exitEditMode()
                            viewModel.updateEditMode(false)
                            applyPreset(viewModel.currentPreset.value)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                override fun onAddButton() {
                    showAddButtonDialog()
                }

                override fun onDeleteButton(buttonId: String) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除控件")
                        .setMessage("确定删除该控件？")
                        .setPositiveButton("删除") { _, _ ->
                            gamepadLayout.removeButtonPosition(buttonId)
                            floatingEditor.clearParameters()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                override fun onButtonUpdated(buttonId: String, updated: ButtonPosition) {
                    gamepadLayout.updateButtonPosition(buttonId, updated)
                    updateButtonLabels(viewModel.settings.value.displayMode)
                }

                override fun onPickOutputValues(buttonId: String, currentBits: List<Int>, onResult: (List<Int>) -> Unit) {
                    showOutputValuePicker(currentBits, onResult)
                }
                override fun onGyroOrientationChanged(orientation: GyroOrientation?) {
                    val updated = gamepadLayout.getPreset().copy(gyroOrientation = orientation)
                    gamepadLayout.loadPreset(updated)
                    viewModel.updatePresetButtons(updated)
                    viewModel.currentPresetGyroOrientation = orientation
                    floatingEditor.presetGyroOrientation = orientation
                }
            }
        }
        (findViewById<View>(android.R.id.content) as ViewGroup).addView(
            floatingEditor,
            FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.4f).toInt(),
                (resources.displayMetrics.heightPixels * 0.8f).toInt()
            )
        )

        btnToggleEditor = ImageButton(this).apply {
            visibility = View.GONE
            setBackgroundResource(R.drawable.bg_small_btn)
            setImageResource(R.drawable.ic_arrow_up)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((8f * resources.displayMetrics.density).toInt(), (8f * resources.displayMetrics.density).toInt(),
                (8f * resources.displayMetrics.density).toInt(), (8f * resources.displayMetrics.density).toInt())
            setOnClickListener {
                editorVisible = !editorVisible
                floatingEditor.visibility = if (editorVisible) View.VISIBLE else View.GONE
                setImageResource(if (editorVisible) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)
            }
        }
        (findViewById<View>(android.R.id.content) as ViewGroup).addView(
            btnToggleEditor,
            FrameLayout.LayoutParams((36f * resources.displayMetrics.density).toInt(), (36f * resources.displayMetrics.density).toInt()).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                topMargin = (6f * resources.displayMetrics.density).toInt()
            }
        )
    }

    private var addDialog: AlertDialog? = null
    private var addCounter = 0

    @SuppressLint("ClickableViewAccessibility")
    private data class CtrlEntry(
        val baseId: String, val name: String, val icon: Int,
        val bgRes: Int = R.drawable.button_circle,
        val isJoystick: Boolean = false,
        val isTouchpad: Boolean = false,
        val isDpad: Boolean = false,
        val isTrigger: Boolean = false,
        val useImageButton: Boolean = false,
        val isCustom: Boolean = false,
        val bit: Int = 0,
        val w: Int = 10, val h: Int = 10,
        val lockAspect: Boolean = true,
    )

    private val allControls = listOf(
        CtrlEntry("btnDpadUp", "上方向", R.drawable.ic_arrow_up, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_UP),
        CtrlEntry("btnDpadDown", "下方向", R.drawable.ic_arrow_down, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_DOWN),
        CtrlEntry("btnDpadLeft", "左方向", R.drawable.ic_arrow_left, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_LEFT),
        CtrlEntry("btnDpadRight", "右方向", R.drawable.ic_arrow_right, isDpad = true, useImageButton = true, bit = GamepadState.DPAD_RIGHT),
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
        CtrlEntry("btnTouchpad", "触摸板按下", R.drawable.btn_touchpad, R.drawable.btn_touchpad, bit = GamepadState.TOUCHPAD_CLICK, w = 9, h = 9),
        CtrlEntry("btnLS", "左摇杆按下", R.drawable.btn_ls, R.drawable.btn_ls, bit = GamepadState.L3, w = 9, h = 9),
        CtrlEntry("btnRS", "右摇杆按下", R.drawable.btn_rs, R.drawable.btn_rs, bit = GamepadState.R3, w = 9, h = 9),
        CtrlEntry("btnSelect", "选择", R.drawable.btn_select_xbox, bit = GamepadState.SELECT, w = 9, h = 9),
        CtrlEntry("btnHome", "主页", R.drawable.ic_home, useImageButton = true, bit = GamepadState.HOME, w = 9, h = 9),
        CtrlEntry("btnMenu", "菜单", R.drawable.btn_menu_xbox, bit = GamepadState.START, w = 9, h = 9),
        CtrlEntry("btnCustomCircle", "自定义(圆)", R.drawable.button_circle, isCustom = true),
        CtrlEntry("btnCustomRect", "自定义(方)", R.drawable.button_rounded_rect, R.drawable.button_rounded_rect, w = 14, h = 8, lockAspect = false, isCustom = true),
    )

    private fun getPreviewText(entry: CtrlEntry, mode: DisplayMode): String? {
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

    private fun getPreviewIcon(entry: CtrlEntry, mode: DisplayMode): Int {
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

    private fun showAddButtonDialog() {
        val density = resources.displayMetrics.density
        val cols = 6
        val cellW = (400f * density).toInt()
        val iconSize = (48f * density).toInt()
        val mode = viewModel.settings.value.displayMode

        val content = NestedScrollView(this)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt())
        }

        allControls.chunked(cols).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            rowItems.forEach { entry ->
                val wrapper = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setOnClickListener { addDialog?.dismiss(); addControl(entry) }
                    isClickable = true
                    isFocusable = true
                    setPadding((6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt(), (6f * density).toInt())
                }
                val text = getPreviewText(entry, mode)
                if (text != null) {
                    if (entry.baseId in listOf("btnLS", "btnRS")) {
                        val fl = FrameLayout(this).apply {
                            setBackgroundResource(R.drawable.button_circle)
                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        }
                        val iconId = if (entry.baseId == "btnLS") R.drawable.ic_ls else R.drawable.ic_rs
                        ImageView(this).apply {
                            setImageResource(iconId)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                            )
                        }.also { fl.addView(it) }
                        TextView(this).apply {
                            this.text = text
                            setTextColor(-0x333334)
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            gravity = android.view.Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.CENTER
                            )
                        }.also { fl.addView(it) }
                        wrapper.addView(fl)
                    } else {
                        val btn = Button(this).apply {
                            this.text = text
                            setTextColor(-0x333334)
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            setBackgroundResource(getPreviewIcon(entry, mode))
                            gravity = android.view.Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                            isClickable = false
                        }
                        wrapper.addView(btn)
                    }
                } else if (entry.isJoystick) {
                    val jl = if (entry.baseId.startsWith("left")) "L" else "R"
                    val jv = object : View(this) {
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
                    val iv = ImageView(this).apply {
                        setImageResource(getPreviewIcon(entry, mode))
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    }
                    wrapper.addView(iv)
                } else {
                    val iv = ImageView(this).apply {
                        setImageResource(getPreviewIcon(entry, mode))
                        setBackgroundResource(R.drawable.button_circle)
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    }
                    wrapper.addView(iv)
                }
                val label = TextView(this)
                label.text = entry.name
                label.setTextColor(-0x333334)
                label.textSize = 10f
                label.gravity = android.view.Gravity.CENTER
                wrapper.addView(label)
                row.addView(wrapper, LinearLayout.LayoutParams(cellW / cols, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            grid.addView(row)
        }

        content.addView(grid)
        addDialog = AlertDialog.Builder(this)
            .setTitle("添加控件")
            .setView(content)
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addControl(entry: CtrlEntry) {
        val id = "${entry.baseId}_${addCounter++}"
        val view: View = when {
            entry.useImageButton -> ImageButton(this).apply {
                this.id = View.generateViewId(); tag = id
                setBackgroundResource(R.drawable.button_circle)
                setImageResource(entry.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            entry.isJoystick -> JoystickView(this).apply {
                this.id = View.generateViewId(); tag = id
                val isLeft = entry.baseId == "leftJoystick"
                label = if (isLeft) "L" else "R"
                onStickClickDown = { viewModel.onButtonDown(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                onStickClickUp = { viewModel.onButtonUp(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                onStickMoved = { sx, sy -> if (isLeft) viewModel.onLeftStick(sx, sy) else viewModel.onRightStick(sx, sy) }
            }
            entry.isTouchpad -> {
                val tp = FrameLayout(this).apply {
                    this.id = View.generateViewId(); tag = id
                    setBackgroundResource(R.drawable.center_rect)
                }
                val label = TextView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    setTextColor(-0x6699999a)
                    textSize = 11f
                }
                tp.addView(label)
                label.text = viewModel.connectionState.value.statusText
                touchpadLabels.add(label)
                setupTouchpadView(tp)
                tp
            }
            entry.isCustom -> {
                val btn = if (!entry.lockAspect) RotatableButton(this) else Button(this)
                btn.apply {
                    this.id = View.generateViewId(); tag = id
                    text = "自定义"
                    setAllCaps(false)
                    setTextColor(-0x333334); textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(entry.bgRes)
                    gravity = android.view.Gravity.CENTER
                }
                btn
            }
            !entry.lockAspect -> RotatableButton(this).apply {
                this.id = View.generateViewId(); tag = id
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(entry.bgRes)
                gravity = android.view.Gravity.CENTER
            }
            else -> Button(this).apply {
                this.id = View.generateViewId(); tag = id
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(entry.bgRes)
                gravity = android.view.Gravity.CENTER
            }
        }
        gamepadLayout.addView(view)

        if (!entry.isTouchpad) {
            if (entry.isCustom) {
                setupCustomTouchHandler(view)
            } else {
                setupTouchHandler(view, entry.bit, entry.isDpad, entry.isTrigger, entry.isJoystick)
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
        gamepadLayout.addButtonPosition(pos)
        gamepadLayout.setSelectedButton(id)
        updateButtonLabels(viewModel.settings.value.displayMode)
    }

    private fun createCustomButtonView(pos: ButtonPosition): View {
        val displayText = (pos.customText ?: "自定义").ifEmpty { "自定义" }
        val view: View = if (pos.roundShape || pos.lockAspect) {
            Button(this).apply {
                id = View.generateViewId(); tag = pos.id
                text = displayText
                setAllCaps(false)
                setTextColor(-0x333334); textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.button_circle)
                gravity = android.view.Gravity.CENTER
            }
        } else {
            RotatableButton(this).apply {
                id = View.generateViewId(); tag = pos.id
                text = displayText
                setAllCaps(false)
                setTextColor(-0x333334); textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.button_rounded_rect)
                gravity = android.view.Gravity.CENTER
            }
        }
        gamepadLayout.addView(view)
        setupCustomTouchHandler(view)
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCustomTouchHandler(view: View) {
        val id = view.tag as String
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; v.performClick()
                    val bits = gamepadLayout.currentButtons.find { it.id == id }?.customBits.orEmpty()
                    viewModel.onCustomButtonDown(bits)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    val bits = gamepadLayout.currentButtons.find { it.id == id }?.customBits.orEmpty()
                    viewModel.onCustomButtonUp(bits)
                    true
                }
                else -> true
            }
        }
    }

    private fun applyPreset(preset: LayoutPreset) {
        gamepadLayout.loadPreset(preset)
        ensureViewsForAllPresetButtons()
    }

    private fun ensureViewsForAllPresetButtons() {
        val buttons = gamepadLayout.currentButtons

        val existingIds = (0 until gamepadLayout.childCount).mapNotNull {
            gamepadLayout.getChildAt(it).tag as? String
        }.toSet()

        for (pos in buttons) {
            if (pos.id in existingIds) continue
            if (pos.isCustom) {
                createCustomButtonView(pos)
            } else {
                createStandardControlView(pos)
            }
        }
        updateButtonLabels(viewModel.settings.value.displayMode)
    }

    private fun createStandardControlView(pos: ButtonPosition) {
        val baseId = pos.id.substringBefore("_")
        val entry = allControls.find { it.baseId == baseId } ?: return

        val view: View = when {
            entry.useImageButton -> ImageButton(this).apply {
                id = View.generateViewId(); tag = pos.id
                setBackgroundResource(entry.bgRes)
                setImageResource(entry.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            entry.isJoystick -> JoystickView(this).apply {
                id = View.generateViewId(); tag = pos.id
                val isLeft = baseId == "leftJoystick"
                label = if (isLeft) "L" else "R"
                onStickClickDown = { viewModel.onButtonDown(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                onStickClickUp = { viewModel.onButtonUp(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                onStickMoved = { sx, sy -> if (isLeft) viewModel.onLeftStick(sx, sy) else viewModel.onRightStick(sx, sy) }
            }
            entry.isTouchpad -> {
                val tp = FrameLayout(this).apply {
                    id = View.generateViewId(); tag = pos.id
                    setBackgroundResource(R.drawable.center_rect)
                }
                val label = TextView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    setTextColor(-0x6699999a); textSize = 11f
                    text = viewModel.connectionState.value.statusText
                }
                tp.addView(label)
                touchpadLabels.add(label)
                setupTouchpadView(tp)
                tp
            }
            entry.isCustom -> {
                val btn = if (!entry.lockAspect) RotatableButton(this) else Button(this)
                btn.apply {
                    id = View.generateViewId(); tag = pos.id
                    text = "自定义"
                    setAllCaps(false)
                    setTextColor(-0x333334); textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(entry.bgRes)
                    gravity = android.view.Gravity.CENTER
                }
            }
            !entry.lockAspect -> RotatableButton(this).apply {
                id = View.generateViewId(); tag = pos.id
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(entry.bgRes)
                gravity = android.view.Gravity.CENTER
            }
            else -> Button(this).apply {
                id = View.generateViewId(); tag = pos.id
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(entry.bgRes)
                gravity = android.view.Gravity.CENTER
            }
        }

        if (!entry.isTouchpad && !entry.isCustom) {
            val bit = getBitForEntry(entry) ?: 0
            setupTouchHandler(view, bit, entry.isDpad, entry.isTrigger, entry.isJoystick)
        } else if (entry.isCustom) {
            setupCustomTouchHandler(view)
        }
        controlViews[baseId] = view
        gamepadLayout.addView(view)
    }

    private fun getBitForEntry(entry: CtrlEntry): Int? {
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

    private var outputPickerDialog: AlertDialog? = null

    private fun showOutputValuePicker(currentBits: List<Int>, onResult: (List<Int>) -> Unit) {
        outputPickerDialog?.dismiss()
        val density = resources.displayMetrics.density
        val cols = 6
        val cellW = (400f * density).toInt()
        val iconSize = (48f * density).toInt()
        val mode = viewModel.settings.value.displayMode

        val content = NestedScrollView(this)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt(), (12f * density).toInt())
        }

        allControls.filter { it.baseId != "btnCustomCircle" && it.baseId != "btnCustomRect" && !it.isJoystick && !it.isTouchpad }
            .chunked(cols).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            rowItems.forEach { entry ->
                val bit = getBitForEntry(entry) ?: return@forEach
                val alreadySelected = bit in currentBits

                val wrapper = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setOnClickListener {
                        outputPickerDialog?.dismiss()
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

                val text = getPreviewText(entry, mode)
                if (text != null) {
                    if (entry.baseId in listOf("btnLS", "btnRS")) {
                        val fl = FrameLayout(this).apply {
                            setBackgroundResource(R.drawable.button_circle)
                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        }
                        val iconId = if (entry.baseId == "btnLS") R.drawable.ic_ls else R.drawable.ic_rs
                        ImageView(this).apply {
                            setImageResource(iconId)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                            )
                        }.also { fl.addView(it) }
                        TextView(this).apply {
                            this.text = text
                            setTextColor(-0x333334)
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            gravity = android.view.Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.CENTER
                            )
                        }.also { fl.addView(it) }
                        wrapper.addView(fl)
                    } else {
                        val btn = Button(this).apply {
                            this.text = text
                            setTextColor(-0x333334)
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            setBackgroundResource(getPreviewIcon(entry, mode))
                            gravity = android.view.Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                            isClickable = false
                        }
                        wrapper.addView(btn)
                    }
                } else if (entry.isJoystick) {
                    val jl = if (entry.baseId.startsWith("left")) "L" else "R"
                    val jv = object : View(this) {
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
                    val iv = ImageView(this).apply {
                        setImageResource(getPreviewIcon(entry, mode))
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
        outputPickerDialog = AlertDialog.Builder(this)
            .setTitle("选择传出值")
            .setView(content)
            .setPositiveButton("取消", null)
            .show()
    }

    // ── Gamepad Layout Listener ────────────────────────────────

    private fun setupGamepadLayoutListener() {
        gamepadLayout.listener = object : GamepadLayout.GamepadLayoutListener {
                override fun onButtonSelected(buttonId: String?) {
                    viewModel.setSelectedButtonId(buttonId)
                    val preset = gamepadLayout.getPreset()
                    floatingEditor.presetGyroOrientation = preset.gyroOrientation
                    if (buttonId != null) {
                        val pos = gamepadLayout.currentButtons.find { it.id == buttonId }
                        if (pos != null) {
                            floatingEditor.showParameters(buttonId, pos)
                        }
                    } else {
                        floatingEditor.clearParameters()
                    }
                }

            override fun onEditModeChanged(isEditMode: Boolean) {
                floatingEditor.visibility = if (isEditMode && editorVisible) View.VISIBLE else View.GONE
                btnToggleEditor.visibility = if (isEditMode) View.VISIBLE else View.GONE
                findViewById<ImageButton>(R.id.btnSettings).visibility =
                    if (isEditMode) View.GONE else View.VISIBLE
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
                physicalControllerHandler.setCapturedTouchpadState(x, y, touchPoints, touchpadTouch, touchpadClick)
                syncPhysicalControllerState()
            }
        }

        physicalControllerHandler.onPointerCaptureNeeded = { enabled ->
            pointerCaptureNeeded = enabled
            physicalControllerHandler.isPointerCaptureActive = enabled
            gamepadLayout.setTouchpadCaptureMode(enabled)
        }
    }

    private fun performHaptic(isPress: Boolean) {
        val s = viewModel.settings.value
        if (!s.vibrationEnabled) return
        val type = if (isPress) s.vibrationPressType else s.vibrationReleaseType
        when (type) {
            VibrationType.NONE -> return
            VibrationType.VIEW -> {
                val effect = if (isPress) s.vibrationPressViewEffect else s.vibrationReleaseViewEffect
                val constantId = hapticEffectToConstant(effect)
                gamepadLayout.performHapticFeedback(constantId)
            }
            VibrationType.VIBRATION_EFFECT -> {
                val duration = (if (isPress) s.vibrationPressDuration else s.vibrationReleaseDuration).coerceAtLeast(1)
                val intensity = if (isPress) s.vibrationPressIntensity else s.vibrationReleaseIntensity
                val effect = VibrationEffect.createOneShot(duration.toLong(), intensity.coerceIn(0, 255))
                vibrator.cancel()
                vibrator.vibrate(effect)
            }
        }
    }

    private fun hapticEffectToConstant(effect: HapticEffect): Int {
        val fallback = HapticFeedbackConstants.KEYBOARD_TAP
        return when (effect) {
            HapticEffect.KEYBOARD_TAP -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticEffect.CONFIRM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else fallback
            HapticEffect.REJECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else fallback
            HapticEffect.CLOCK_TICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CLOCK_TICK else fallback
            HapticEffect.CONTEXT_CLICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONTEXT_CLICK else fallback
            HapticEffect.LONG_PRESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.LONG_PRESS else fallback
            HapticEffect.KEYBOARD_PRESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.KEYBOARD_PRESS else fallback
            HapticEffect.KEYBOARD_RELEASE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.KEYBOARD_RELEASE else fallback
            HapticEffect.GESTURE_START -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_START else fallback
            HapticEffect.GESTURE_END -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END else fallback
            HapticEffect.VIRTUAL_KEY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.VIRTUAL_KEY else fallback
            HapticEffect.VIRTUAL_KEY_RELEASE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.VIRTUAL_KEY_RELEASE else fallback
        }
    }

    // ── Gamepad ──────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createAllControls() {
        viewModel.onHapticFeedbackPress = { performHaptic(isPress = true) }
        viewModel.onHapticFeedbackRelease = { performHaptic(isPress = false) }

        data class Def(
            val baseId: String, val bit: Int = 0,
            val isDpad: Boolean = false, val isTrigger: Boolean = false,
            val isJoystick: Boolean = false, val isTouchpad: Boolean = false,
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
            Def("btnSelect", bit = GamepadState.SELECT),
            Def("btnHome", bit = GamepadState.HOME, useImageButton = true, icon = R.drawable.ic_home),
            Def("btnMenu", bit = GamepadState.START),
        )

        for (d in defaults) {
            val view = when {
                d.useImageButton -> ImageButton(this).apply {
                    this.id = View.generateViewId(); tag = d.baseId
                    setBackgroundResource(R.drawable.button_circle)
                    setImageResource(d.icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                d.isJoystick -> JoystickView(this).apply {
                    this.id = View.generateViewId(); tag = d.baseId
                    val isLeft = d.baseId == "leftJoystick"
                    label = if (isLeft) "L" else "R"
                    onStickClickDown = { viewModel.onButtonDown(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                    onStickClickUp = { viewModel.onButtonUp(if (isLeft) GamepadState.L3 else GamepadState.R3) }
                    onStickMoved = { sx, sy -> if (isLeft) viewModel.onLeftStick(sx, sy) else viewModel.onRightStick(sx, sy) }
                    doubleClickEnable = true
                }
                d.isTouchpad -> {
                    val tp = FrameLayout(this).apply {
                        this.id = View.generateViewId(); tag = d.baseId
                        setBackgroundResource(R.drawable.center_rect)
                    }
                val label = TextView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    setTextColor(-0x6699999a)
                    textSize = 11f
                    text = viewModel.connectionState.value.statusText
                }
                tp.addView(label)
                touchpadLabels.add(label)
                setupTouchpadView(tp)
                tp
            }
            d.baseId in listOf("btnLB", "btnRB", "btnLT", "btnRT") -> RotatableButton(this).apply {
                    this.id = View.generateViewId(); tag = d.baseId
                    setTextColor(-0x333334); textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(d.bgRes)
                    gravity = android.view.Gravity.CENTER
                }
                else -> Button(this).apply {
                    this.id = View.generateViewId(); tag = d.baseId
                    setTextColor(-0x333334); textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(d.bgRes)
                    gravity = android.view.Gravity.CENTER
                }
            }
            if (!d.isTouchpad) {
                setupTouchHandler(view, d.bit, d.isDpad, d.isTrigger, d.isJoystick)
            }
            gamepadLayout.addView(view)
            controlViews[d.baseId] = view
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettings() }
        updateButtonLabels(viewModel.settings.value.displayMode)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandler(view: View, bit: Int, isDpad: Boolean, isTrigger: Boolean, isJoystick: Boolean) {
        when {
            isDpad -> view.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); viewModel.onDpad(bit, true); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; viewModel.onDpad(bit, false); true }
                    else -> true
                }
            }
            isTrigger -> {
                val analogFn: (Int) -> Unit = if (bit == GamepadState.LT) viewModel::onLeftTrigger else viewModel::onRightTrigger
                view.setOnTouchListener { v, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); viewModel.onButtonDown(bit); analogFn(255); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; viewModel.onButtonUp(bit); analogFn(0); true }
                        else -> true
                    }
                }
            }
            !isJoystick -> view.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); if (bit != 0) viewModel.onButtonDown(bit); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; if (bit != 0) viewModel.onButtonUp(bit); true }
                    else -> true
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchpadView(tp: FrameLayout) {
        val handler = Handler(Looper.getMainLooper())
        var firstTapTime = 0L; var firstTapX = 0f; var firstTapY = 0f
        var isDoubleClick = false
        val density = resources.displayMetrics.density
        val doubleTapTimeout = Runnable { firstTapTime = 0 }
        // Two physical slots. The framework may deliver each finger as a separate
        // 1-pointer event stream (same downTime/pointerId), so we cannot key by event
        // fields; instead we map each contact to a slot by position.
        val slots = arrayOfNulls<TouchPoint>(10)

        fun mapPoint(px: Float, py: Float): Pair<Int, Int> {
            val w = tp.measuredWidth.coerceAtLeast(1)
            val h = tp.measuredHeight.coerceAtLeast(1)
            val rotation = (tp.tag as? String)?.let { gamepadLayout.getRotation(it) } ?: 0
            val nx = px / w
            val ny = py / h
            val (vx, vy) = when (rotation % 360) {
                90 -> Pair(ny, 1f - nx)
                180 -> Pair(1f - nx, 1f - ny)
                270 -> Pair(1f - ny, nx)
                else -> Pair(nx, ny)
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
                tp.isPressed = false; viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK)
                isDoubleClick = false
            }
            viewModel.onTouchpadTouches(
                slots.take(2).mapIndexed { i, tp -> tp ?: TouchPoint(id = i, active = false) }
            )
        }

        tp.setOnTouchListener { v, event ->
            val btnId = v.tag as? String
            val doubleClickEnable = btnId?.let { gamepadLayout.currentButtons.find { p -> p.id == it }?.doubleClickEnable } ?: true
            val masked = event.action and MotionEvent.ACTION_MASK

            if (masked == MotionEvent.ACTION_UP) {
                val (sx, sy) = mapPoint(event.getX(0), event.getY(0))
                val slot = nearestSlot(sx, sy)
                if (slot >= 0) slots[slot] = null
                if (slots.all { it == null }) {
                    if (isDoubleClick) { v.isPressed = false; viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK) }
                    isDoubleClick = false
                }
                send()
                return@setOnTouchListener true
            }

            if (masked == MotionEvent.ACTION_CANCEL) {
                slots.fill(null)
                if (isDoubleClick) { v.isPressed = false; viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK) }
                isDoubleClick = false
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
                if (masked == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    if (isDoubleClick) {
                        // skip: converted ACTION_DOWN (e.g. from handleSwipeTriggerTouch)
                    } else if (doubleClickEnable) {
                        val now = System.currentTimeMillis()
                        if (now - firstTapTime < 300 && firstTapTime > 0) {
                            val dx = (event.getX(0) - firstTapX) / density
                            val dy = (event.getY(0) - firstTapY) / density
                            val distDp = Math.sqrt((dx * dx + dy * dy).toDouble())
                            if (distDp < 32.0 && slots.count { it != null } < 2) {
                                handler.removeCallbacks(doubleTapTimeout)
                                v.isPressed = true; isDoubleClick = true; firstTapTime = 0
                                viewModel.onButtonDown(GamepadState.TOUCHPAD_CLICK)
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

    // ── Settings ─────────────────────────────────────────────

    private var inSettings = false

    private fun showSettings() {
        if (gamepadLayout.isEditModeActive()) return
        inSettings = true
        findViewById<View>(R.id.gamepadPanel).visibility = View.GONE
        findViewById<View>(R.id.settingsPanel).visibility = View.VISIBLE
        selectSettingsCategory(0)
        syncSettingsUI()
    }

    private fun hideSettings() {
        inSettings = false
        findViewById<View>(R.id.gamepadPanel).visibility = View.VISIBLE
        findViewById<View>(R.id.settingsPanel).visibility = View.GONE
    }

    private fun selectSettingsCategory(index: Int) {
        val pages = listOf(R.id.pageConnection, R.id.pagePhysicalController, R.id.pagePresets, R.id.pageVibration, R.id.pageGyro, R.id.pageMisc, R.id.pageAbout)
        val buttons = listOf(
            R.id.btnCategoryConnection, R.id.btnCategoryPhysicalController, R.id.btnCategoryPresets, R.id.btnCategoryVibration, R.id.btnCategoryGyro, R.id.btnCategoryMisc, R.id.btnCategoryAbout
        )
        pages.forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        buttons.forEachIndexed { i, id ->
            findViewById<Button>(id).setTextColor(
                if (i == index) -0x1 else -0x777778
            )
        }
    }

    private fun setupSettings() {
        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener { hideSettings() }

        // Category switching
        findViewById<Button>(R.id.btnCategoryConnection).setOnClickListener { selectSettingsCategory(0) }
        findViewById<Button>(R.id.btnCategoryPhysicalController).setOnClickListener { selectSettingsCategory(1) }
        findViewById<Button>(R.id.btnCategoryPresets).setOnClickListener { selectSettingsCategory(2) }
        findViewById<Button>(R.id.btnCategoryVibration).setOnClickListener { selectSettingsCategory(3) }
        findViewById<Button>(R.id.btnCategoryGyro).setOnClickListener { selectSettingsCategory(4) }
        findViewById<Button>(R.id.btnCategoryMisc).setOnClickListener { selectSettingsCategory(5) }
        findViewById<Button>(R.id.btnCategoryAbout).setOnClickListener { selectSettingsCategory(6) }

        // ── Presets page ──
        findViewById<Switch>(R.id.switchEditMode).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val currentName = viewModel.settings.value.currentPresetName
                if (viewModel.isBuiltInPreset(currentName)) {
                    showToast("内置布局禁止编辑")
                    findViewById<Switch>(R.id.switchEditMode).isChecked = false
                    return@setOnCheckedChangeListener
                }
                viewModel.updateEditMode(true)
                hideSettings()
                applyPreset(viewModel.currentPreset.value)
                gamepadLayout.enterEditMode()
                floatingEditor.presetGyroOrientation = gamepadLayout.currentGyroOrientation
            }
        }

        val gridView = findViewById<WrapContentGridView>(R.id.gridPresets)
        gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val infos = viewModel.presetInfos.value
            val info = infos.getOrNull(position) ?: return@OnItemClickListener
            loadPresetByName(info.name)
        }

        findViewById<Button>(R.id.btnPresetNew).setOnClickListener { showNewPresetDialog() }
        findViewById<Button>(R.id.btnPresetImport).setOnClickListener {
            importPresetLauncher.launch(arrayOf("application/json", "*/*"))
        }
        findViewById<Button>(R.id.btnPresetExport).setOnClickListener {
            val name = viewModel.settings.value.currentPresetName
            exportPresetLauncher.launch("$name.json")
        }
        findViewById<Button>(R.id.btnPresetRename).setOnClickListener {
            val infos = viewModel.presetInfos.value
            val current = viewModel.settings.value.currentPresetName
            val idx = infos.indexOfFirst { it.name == current }
            val name = if (idx >= 0) infos[idx].name else infos.firstOrNull()?.name ?: return@setOnClickListener
            if (viewModel.isBuiltInPreset(name)) { showToast("内置布局禁止重命名"); return@setOnClickListener }
            showRenameDialog(name)
        }

        findViewById<Button>(R.id.btnPresetDelete).setOnClickListener {
            val infos = viewModel.presetInfos.value
            val current = viewModel.settings.value.currentPresetName
            val idx = infos.indexOfFirst { it.name == current }
            val selected = if (idx >= 0) infos[idx].name else infos.firstOrNull()?.name ?: return@setOnClickListener
            if (viewModel.isBuiltInPreset(selected)) { showToast("内置布局禁止删除"); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setTitle("删除预设")
                .setMessage("确定删除「$selected」？")
                .setPositiveButton("删除") { _, _ -> viewModel.deletePreset(selected); refreshPresetList() }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── Controller page ──
        listOf(R.id.btnDisplayXbox to 0, R.id.btnDisplayPlaystation to 1, R.id.btnDisplaySwitch to 2)
            .forEach { (id, idx) ->
                findViewById<Button>(id).setOnClickListener {
                    selectChipGroup(listOf(R.id.btnDisplayXbox, R.id.btnDisplayPlaystation, R.id.btnDisplaySwitch), idx)
                    viewModel.updateDisplayMode(DisplayMode.entries[idx])
                }
            }

        listOf(R.id.btnConnWifi to 0, R.id.btnConnBluetooth to 1).forEach { (id, idx) ->
            findViewById<Button>(id).setOnClickListener {
                if (viewModel.connectionState.value.phase != ConnectionPhase.IDLE) {
                    showToast("请先停止服务")
                    return@setOnClickListener
                }
                selectChipGroup(listOf(R.id.btnConnWifi, R.id.btnConnBluetooth), idx)
                val mode = ConnectionMode.entries[idx]
                viewModel.updateConnectionMode(mode)
                updateSettingsVisibility(mode)
            }
        }

        listOf(R.id.btnTargetWindows to 0, R.id.btnTargetAndroid to 1)
            .forEach { (id, idx) ->
                findViewById<Button>(id).setOnClickListener {
                    val platform = TargetPlatform.entries[idx]
                    if (platform == viewModel.settings.value.targetPlatform) return@setOnClickListener
                    val needsRestart = viewModel.settings.value.connectionMode == ConnectionMode.BLUETOOTH
                        && viewModel.isBluetoothRunning
                    if (needsRestart) {
                        AlertDialog.Builder(this)
                            .setTitle("切换目标平台")
                            .setMessage("切换目标平台需要重启应用，是否继续？")
                            .setPositiveButton("确定") { _, _ ->
                                viewModel.updateTargetPlatform(platform)
                                finishAffinity()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        selectChipGroup(listOf(R.id.btnTargetWindows, R.id.btnTargetAndroid), idx)
                        viewModel.updateTargetPlatform(platform)
                    }
                }
            }

        // ── Vibration page ──
        findViewById<Switch>(R.id.switchBtnVibration).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateVibrationEnabled(isChecked)
        }
        findViewById<Switch>(R.id.switchGameVibration).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateGameVibrationEnabled(isChecked)
        }

        // Press type selection
        val pressTypeIds = listOf(
            R.id.btnVibPressTypeNone to VibrationType.NONE,
            R.id.btnVibPressTypeView to VibrationType.VIEW,
            R.id.btnVibPressTypeEffect to VibrationType.VIBRATION_EFFECT,
        )
        pressTypeIds.forEach { (id, type) ->
            findViewById<Button>(id).setOnClickListener {
                viewModel.updateVibrationPressType(type)
                updateVibrationUI()
            }
        }
        // Release type selection
        val releaseTypeIds = listOf(
            R.id.btnVibReleaseTypeNone to VibrationType.NONE,
            R.id.btnVibReleaseTypeView to VibrationType.VIEW,
            R.id.btnVibReleaseTypeEffect to VibrationType.VIBRATION_EFFECT,
        )
        releaseTypeIds.forEach { (id, type) ->
            findViewById<Button>(id).setOnClickListener {
                viewModel.updateVibrationReleaseType(type)
                updateVibrationUI()
            }
        }

        // Effect Spinners
        setupEffectSpinner(R.id.spinnerPressEffect, isPress = true)
        setupEffectSpinner(R.id.spinnerReleaseEffect, isPress = false)

        // Press SeekBars
        findViewById<SeekBar>(R.id.seekPressDuration).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (progress < 1) { sb.progress = 1; return }
                    viewModel.updateVibrationPressDuration(progress)
                    findViewById<TextView>(R.id.tvPressDuration).text = "时长: ${progress}ms"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )
        findViewById<SeekBar>(R.id.seekPressIntensity).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    viewModel.updateVibrationPressIntensity(progress)
                    findViewById<TextView>(R.id.tvPressIntensity).text = "强度: $progress"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        // Release SeekBars
        findViewById<SeekBar>(R.id.seekReleaseDuration).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (progress < 1) { sb.progress = 1; return }
                    viewModel.updateVibrationReleaseDuration(progress)
                    findViewById<TextView>(R.id.tvReleaseDuration).text = "时长: ${progress}ms"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )
        findViewById<SeekBar>(R.id.seekReleaseIntensity).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    viewModel.updateVibrationReleaseIntensity(progress)
                    findViewById<TextView>(R.id.tvReleaseIntensity).text = "强度: $progress"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        // Test button (circular) — press & release via touch
        findViewById<Button>(R.id.btnTestVibration).setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { v.performClick(); testHaptic(isPress = true); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { testHaptic(isPress = false); true }
                else -> false
            }
        }

        // ── Gyro page ──
        findViewById<Switch>(R.id.switchGyroEnabled).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateGyroEnabled(isChecked)
        }

        listOf(
            R.id.btnGyroOriLandscape to GyroOrientation.LANDSCAPE,
            R.id.btnGyroOriPortrait to GyroOrientation.PORTRAIT,
            R.id.btnGyroOriPortraitInverted to GyroOrientation.PORTRAIT_INVERTED,
        ).forEach { (id, orientation) ->
            findViewById<Button>(id).setOnClickListener {
                val locked = viewModel.currentPreset.value.gyroOrientation
                if (locked != null) {
                    val name = viewModel.settings.value.currentPresetName
                    showToast("体感握持方向被布局「$name」锁定")
                    return@setOnClickListener
                }
                selectChipGroup(
                    listOf(R.id.btnGyroOriLandscape, R.id.btnGyroOriPortrait, R.id.btnGyroOriPortraitInverted),
                    orientation.ordinal
                )
                viewModel.updateGyroOrientation(orientation)
            }
        }

        findViewById<SeekBar>(R.id.seekGyroSensitivityX).apply {
            min = -3000
            isEnabled = false
            setOnTouchListener { _, _ -> true }
        }
        findViewById<SeekBar>(R.id.seekGyroSensitivityY).apply {
            min = -3000
            isEnabled = false
            setOnTouchListener { _, _ -> true }
        }
        findViewById<SeekBar>(R.id.seekGyroSensitivityZ).apply {
            min = -3000
            isEnabled = false
            setOnTouchListener { _, _ -> true }
        }

        // ── Physical Controller page ──
        findViewById<Switch>(R.id.switchControllerGyro).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateControllerGyroEnabled(isChecked)
            physicalControllerHandler.onControllerGyroSettingChanged(isChecked)
            if (isChecked) {
                findViewById<TextView>(R.id.tvControllerGyroNote).visibility = View.VISIBLE
            } else {
                findViewById<TextView>(R.id.tvControllerGyroNote).visibility = View.GONE
            }
        }

        // Vibration Mapping spinners
        setupVibrationMappingSpinner(R.id.spinnerStrongVibration) { mapping ->
            viewModel.updateStrongVibrationMapping(mapping)
            physicalControllerHandler.strongVibrationMapping = mapping
        }
        setupVibrationMappingSpinner(R.id.spinnerWeakVibration) { mapping ->
            viewModel.updateWeakVibrationMapping(mapping)
            physicalControllerHandler.weakVibrationMapping = mapping
        }

        // ── Misc page ──
        setupMiscPage()

        // ── About page ──
        setupAboutPage()

        // ── Connection page ──
        setupConnectionPage()
        setupUnpairButton()

        viewModel.connectionManager.onRumbleRequest = { large, small ->
            physicalControllerHandler.rumble(large, small)
        }
    }

    private fun setupEffectSpinner(spinnerId: Int, isPress: Boolean) {
        val spinner = findViewById<Spinner>(spinnerId)
        val names = HapticEffect.entries.map { it.displayName }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val effect = HapticEffect.entries[pos]
                if (isPress) viewModel.updateVibrationPressViewEffect(effect)
                else viewModel.updateVibrationReleaseViewEffect(effect)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private var vibrationMappingEntries: List<VibrationMotor> = VibrationMotor.entries.toList()

    private fun setupVibrationMappingSpinner(spinnerId: Int, onChanged: (VibrationMotor) -> Unit) {
        val spinner = findViewById<Spinner>(spinnerId)
        vibrationMappingEntries = VibrationMotor.entries.toList()
        updateMappingAdapter(spinner)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos < vibrationMappingEntries.size) onChanged(vibrationMappingEntries[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateMappingAdapter(spinner: Spinner) {
        val names = vibrationMappingEntries.map { it.displayName }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun updateVibrationUI() {
        val s = viewModel.settings.value

        // Press column
        selectChipGroup(listOf(R.id.btnVibPressTypeNone, R.id.btnVibPressTypeView, R.id.btnVibPressTypeEffect),
            s.vibrationPressType.ordinal)
        findViewById<View>(R.id.layoutPressViewEffects).visibility =
            if (s.vibrationPressType == VibrationType.VIEW) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutPressVibEffect).visibility =
            if (s.vibrationPressType == VibrationType.VIBRATION_EFFECT) View.VISIBLE else View.GONE
        findViewById<Spinner>(R.id.spinnerPressEffect).setSelection(s.vibrationPressViewEffect.ordinal)
        findViewById<TextView>(R.id.tvPressDuration).text = "时长: ${s.vibrationPressDuration}ms"
        findViewById<TextView>(R.id.tvPressIntensity).text = "强度: ${s.vibrationPressIntensity}"
        findViewById<SeekBar>(R.id.seekPressDuration).progress = s.vibrationPressDuration
        findViewById<SeekBar>(R.id.seekPressIntensity).progress = s.vibrationPressIntensity

        // Release column
        selectChipGroup(listOf(R.id.btnVibReleaseTypeNone, R.id.btnVibReleaseTypeView, R.id.btnVibReleaseTypeEffect),
            s.vibrationReleaseType.ordinal)
        findViewById<View>(R.id.layoutReleaseViewEffects).visibility =
            if (s.vibrationReleaseType == VibrationType.VIEW) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutReleaseVibEffect).visibility =
            if (s.vibrationReleaseType == VibrationType.VIBRATION_EFFECT) View.VISIBLE else View.GONE
        findViewById<Spinner>(R.id.spinnerReleaseEffect).setSelection(s.vibrationReleaseViewEffect.ordinal)
        findViewById<TextView>(R.id.tvReleaseDuration).text = "时长: ${s.vibrationReleaseDuration}ms"
        findViewById<TextView>(R.id.tvReleaseIntensity).text = "强度: ${s.vibrationReleaseIntensity}"
        findViewById<SeekBar>(R.id.seekReleaseDuration).progress = s.vibrationReleaseDuration
        findViewById<SeekBar>(R.id.seekReleaseIntensity).progress = s.vibrationReleaseIntensity
    }

    private fun testHaptic(isPress: Boolean) {
        val s = viewModel.settings.value
        if (!s.vibrationEnabled) return
        val type = if (isPress) s.vibrationPressType else s.vibrationReleaseType
        when (type) {
            VibrationType.NONE -> return
            VibrationType.VIEW -> {
                val e = if (isPress) s.vibrationPressViewEffect else s.vibrationReleaseViewEffect
                gamepadLayout.performHapticFeedback(hapticEffectToConstant(e))
            }
            VibrationType.VIBRATION_EFFECT -> {
                val dur = (if (isPress) s.vibrationPressDuration else s.vibrationReleaseDuration).coerceAtLeast(1)
                val amp = if (isPress) s.vibrationPressIntensity else s.vibrationReleaseIntensity
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createOneShot(dur.toLong(), amp.coerceIn(0, 255)))
            }
        }
    }

    private fun setupUnpairButton() {
        val nameView = findViewById<TextView>(R.id.tvPairedDeviceName)
        findViewById<Button>(R.id.btnUnpairDevice).setOnClickListener {
            val deviceName = nameView.text.toString()
            AlertDialog.Builder(this)
                .setTitle("取消配对")
                .setMessage("确定取消与「$deviceName」的配对？下次连接需要重新配对。")
                .setPositiveButton("取消配对") { _, _ -> viewModel.unpairDevice() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun bitToName(bit: Int): String {
        return when (bit) {
            GamepadState.A -> "A"; GamepadState.B -> "B"; GamepadState.X -> "X"; GamepadState.Y -> "Y"
            GamepadState.LB -> "LB"; GamepadState.RB -> "RB"; GamepadState.LT -> "LT"; GamepadState.RT -> "RT"
            GamepadState.SELECT -> "SELECT"; GamepadState.START -> "START"
            GamepadState.L3 -> "L3"; GamepadState.R3 -> "R3"
            GamepadState.DPAD_BIT_UP -> "上方向"; GamepadState.DPAD_BIT_DOWN -> "下方向"
            GamepadState.DPAD_BIT_LEFT -> "左方向"; GamepadState.DPAD_BIT_RIGHT -> "右方向"
            GamepadState.HOME -> "HOME"; GamepadState.TOUCHPAD_CLICK -> "触摸板"
            else -> "位$bit"
        }
    }

    private fun rebuildVolumeChips(containerId: Int, bits: List<Int>, onRemove: (Int) -> Unit) {
        val container = findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        if (bits.isEmpty()) {
            val tv = TextView(this).apply {
                text = "未映射"
                setTextColor(-0x777778)
                textSize = 13f
                setPadding(0, (4f * density).toInt(), 0, (4f * density).toInt())
            }
            container.addView(tv)
            return
        }
        val chipsPerRow = 4
        bits.chunked(chipsPerRow).forEach { rowBits ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            rowBits.forEach { bit ->
                val chip = TextView(this).apply {
                    text = bitToName(bit)
                    setTextColor(-0x1)
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding((6f * density).toInt(), (2f * density).toInt(), (6f * density).toInt(), (2f * density).toInt())
                    setOnClickListener { onRemove(bit) }
                }
                row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = (4f * density).toInt() })
            }
            container.addView(row)
        }
    }

    private fun updateVolumeMappingLabels() {
        val s = viewModel.settings.value
        rebuildVolumeChips(R.id.layoutVolumeUpChips, s.volumeUpBits) { bit ->
            val newBits = s.volumeUpBits - bit
            viewModel.updateVolumeUpBits(newBits)
        }
        rebuildVolumeChips(R.id.layoutVolumeDownChips, s.volumeDownBits) { bit ->
            val newBits = s.volumeDownBits - bit
            viewModel.updateVolumeDownBits(newBits)
        }
    }

    private fun setupMiscPage() {
        findViewById<Switch>(R.id.switchKeepScreenOn).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateKeepScreenOn(isChecked)
            if (isChecked) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        findViewById<View>(R.id.btnAddVolumeUp).setOnClickListener {
            showOutputValuePicker(viewModel.settings.value.volumeUpBits) { newBits ->
                viewModel.updateVolumeUpBits(newBits)
                updateVolumeMappingLabels()
            }
        }
        findViewById<View>(R.id.btnClearVolumeUp).setOnClickListener {
            viewModel.updateVolumeUpBits(emptyList())
            updateVolumeMappingLabels()
        }
        findViewById<View>(R.id.btnAddVolumeDown).setOnClickListener {
            showOutputValuePicker(viewModel.settings.value.volumeDownBits) { newBits ->
                viewModel.updateVolumeDownBits(newBits)
                updateVolumeMappingLabels()
            }
        }
        findViewById<View>(R.id.btnClearVolumeDown).setOnClickListener {
            viewModel.updateVolumeDownBits(emptyList())
            updateVolumeMappingLabels()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1 &&
            physicalControllerHandler.handleKeyEvent(event)) {
            syncPhysicalControllerState()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && physicalControllerHandler.handleKeyEvent(event)) {
            syncPhysicalControllerState()
            return true
        }
        val s = viewModel.settings.value
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (s.volumeUpBits.isNotEmpty()) {
                    viewModel.onVolumeKeyDown(s.volumeUpBits)
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (s.volumeDownBits.isNotEmpty()) {
                    viewModel.onVolumeKeyDown(s.volumeDownBits)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && physicalControllerHandler.handleKeyEvent(event)) {
            syncPhysicalControllerState()
            return true
        }
        val s = viewModel.settings.value
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (s.volumeUpBits.isNotEmpty()) {
                    viewModel.onVolumeKeyUp(s.volumeUpBits)
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (s.volumeDownBits.isNotEmpty()) {
                    viewModel.onVolumeKeyUp(s.volumeDownBits)
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && physicalControllerHandler.handleMotionEvent(event)) {
            syncPhysicalControllerState()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && event.device?.vendorId == 0x054c &&
            physicalControllerHandler.handleMotionEvent(event)) {
            syncPhysicalControllerState()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun syncPhysicalControllerState() {
        val state = physicalControllerHandler.controllerState.value
        viewModel.onPhysicalControllerInput(
            state.buttons,
            state.leftStickX, state.leftStickY,
            state.rightStickX, state.rightStickY,
            state.leftTrigger, state.rightTrigger,
            state.dpad,
            state.touchpadX, state.touchpadY,
            state.touchpadTouch, state.touchpadClick,
            state.touches,
        )
    }

    @SuppressLint("SetTextI18n")
    private fun setupAboutPage() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.tvAppName).text = "Gamepad Emu"
        findViewById<TextView>(R.id.tvAppVersion).text = "版本 ${packageInfo.versionName}"
        findViewById<TextView>(R.id.tvAppDescription).text = "作者：4zyz4\n\n" +
                "开源地址：https://github.com/4zyz4/gamepad-emu-android\n" +
                "注意：本软件不是Emotion，请进入Q群1045923515以下载正版Emotion"

        findViewById<ImageView>(R.id.ivAppIcon).setImageResource(R.mipmap.icon)

        findViewById<Button>(R.id.btnSponsor).setOnClickListener {
            showSponsorDialog()
        }
    }

    private fun showSponsorDialog() {
        val imageView = ImageView(this).apply {
            setImageResource(R.mipmap.reward)
            setScaleType(ImageView.ScaleType.FIT_CENTER)
            setPadding(40, 0, 40, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("赞助")
            .setMessage("感谢您的支持！")
            .setView(imageView)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun setupConnectionPage() {
        findViewById<Button>(R.id.btnConnectAction).setOnClickListener {
            val st = viewModel.connectionState.value
            if (st.phase != ConnectionPhase.IDLE) {
                viewModel.stopServer()
            } else {
                val s = viewModel.settings.value
                if (s.connectionMode == ConnectionMode.BLUETOOTH
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    val connectGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                    val advertiseGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_ADVERTISE
                    ) == PackageManager.PERMISSION_GRANTED
                    if (connectGranted && advertiseGranted) {
                        checkBluetoothOnAndStart()
                    } else {
                        bluetoothPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                            )
                        )
                    }
                } else {
                    viewModel.startServer()
                }
            }
        }

        findViewById<Switch>(R.id.switchAutoStart).setOnCheckedChangeListener { _, isChecked ->
            viewModel.updateAutoStartEnabled(isChecked)
        }
    }

    @Suppress("DEPRECATION")
    private fun checkBluetoothOnAndStart() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && !adapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableIntent)
        } else {
            viewModel.startServer()
        }
    }

    private fun autoStartService() {
        val s = viewModel.settings.value
        if (!s.autoStartEnabled) return
        if (s.connectionMode == ConnectionMode.BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val connectGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                val advertiseGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_ADVERTISE
                ) == PackageManager.PERMISSION_GRANTED
                if (!connectGranted || !advertiseGranted) return
            }
            checkBluetoothOnAndStart()
        } else {
            viewModel.startServer()
        }
    }

    private fun loadPresetByName(name: String) {
        if (!viewModel.loadPreset(name)) return
        val preset = viewModel.currentPreset.value
        applyPreset(preset)
        showToast("已加载「$name」")
        refreshPresetList()
    }

    private fun showNewPresetDialog() {
        val input = EditText(this)
        input.setHint("输入新预设名称")
        AlertDialog.Builder(this)
            .setTitle("新建布局")
            .setMessage("创建新布局")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val preset = viewModel.createDefaultLayout()
                    viewModel.savePreset(name, preset)
                    applyPreset(preset)
                    refreshPresetList()
                    showToast("已创建「$name」")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importPresetFromUri(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            val preset = LayoutPreset.fromJson(json)
            val nameInput = EditText(this)
            nameInput.setHint("输入预设名称")
            AlertDialog.Builder(this)
                .setTitle("导入布局")
                .setView(nameInput)
                .setPositiveButton("保存") { _, _ ->
                    val name = nameInput.text.toString().trim()
                    if (name.isNotEmpty()) {
                        viewModel.savePreset(name, preset)
                        applyPreset(preset)
                        refreshPresetList()
                        showToast("已导入「$name」")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            showToast("导入失败: ${e.message}")
        }
    }

    private fun exportPresetToUri(uri: Uri) {
        try {
            val json = viewModel.currentPreset.value.toJson()
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
            showToast("导出成功")
        } catch (e: Exception) {
            showToast("导出失败: ${e.message}")
        }
    }

    private fun showRenameDialog(oldName: String) {
        val input = EditText(this)
        input.setText(oldName)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != oldName) {
                    viewModel.renamePreset(oldName, newName)
                    refreshPresetList()
                    showToast("已重命名为「$newName」")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshPresetList() {
        val gridView = findViewById<WrapContentGridView>(R.id.gridPresets)
        val infos = viewModel.presetInfos.value
        val current = viewModel.settings.value.currentPresetName
        gridView.adapter = PresetGridAdapter(infos, current)
        findViewById<TextView>(R.id.tvCurrentPreset).text = "当前预设: $current"
    }

    private inner class PresetGridAdapter(
        private val infos: List<PresetInfo>,
        private val currentPresetName: String
    ) : android.widget.BaseAdapter() {
        override fun getCount() = infos.size
        override fun getItem(position: Int) = infos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_preset_card, parent, false)
            val info = infos[position]
            view.findViewById<PresetPreviewView>(R.id.presetPreview).setButtons(info.buttons)
            view.findViewById<TextView>(R.id.presetName).text = info.name
            view.findViewById<View>(R.id.cardBackground).setBackgroundResource(
                if (info.name == currentPresetName) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            return view
        }
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetTextI18n")
    private fun updateButtonLabels(mode: DisplayMode) {
        val abxyList = listOf("btnA", "btnB", "btnX", "btnY")
        val bumperList = listOf("btnLB", "btnRB", "btnLT", "btnRT")

        for (i in 0 until gamepadLayout.childCount) {
            val child = gamepadLayout.getChildAt(i)
            val tag = child.tag as? String ?: continue
            val baseId = tag.substringBefore("_") ?: continue

            if (baseId.startsWith("btnCustom")) {
                val pos = gamepadLayout.currentButtons.find { it.id == tag }
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
                            }
                            DisplayMode.PLAYSTATION -> {
                                text = ""; textSize = 12f
                                setBackgroundResource(intArrayOf(
                                    R.drawable.btn_ps_cross, R.drawable.btn_ps_circle,
                                    R.drawable.btn_ps_square, R.drawable.btn_ps_triangle
                                )[idx])
                            }
                            DisplayMode.SWITCH -> {
                                text = listOf("B", "A", "Y", "X")[idx]; textSize = 20f
                                setBackgroundResource(R.drawable.button_circle)
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
                        when (mode) {
                            DisplayMode.XBOX -> { text = ""; setBackgroundResource(R.drawable.btn_select_xbox) }
                            DisplayMode.PLAYSTATION -> { text = "SHARE"; setBackgroundResource(R.drawable.button_circle) }
                            DisplayMode.SWITCH -> { text = ""; setBackgroundResource(R.drawable.btn_select_switch) }
                        }
                    }
                }
                baseId == "btnHome" -> {
                    (child as? ImageButton)?.apply {
                        setBackgroundResource(R.drawable.button_circle)
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
                            DisplayMode.XBOX -> { text = ""; setBackgroundResource(R.drawable.btn_menu_xbox) }
                            DisplayMode.PLAYSTATION -> { text = "OPTION"; setBackgroundResource(R.drawable.button_circle) }
                            DisplayMode.SWITCH -> { text = ""; setBackgroundResource(R.drawable.btn_menu_switch) }
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
                        text = "L"; textSize = 20f
                        setBackgroundResource(R.drawable.btn_ls)
                    }
                }
                baseId == "btnRS" -> {
                    (child as? Button)?.apply {
                        text = "R"; textSize = 20f
                        setBackgroundResource(R.drawable.btn_rs)
                    }
                }
            }
        }
    }

    private fun selectChipGroup(ids: List<Int>, selected: Int) {
        ids.forEachIndexed { i, id ->
            findViewById<Button>(id).setBackgroundResource(
                if (i == selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
        }
    }

    private fun updateSettingsVisibility(mode: ConnectionMode) {
        val isBt = mode == ConnectionMode.BLUETOOTH
        findViewById<View>(R.id.sectionTargetPlatform).visibility = if (isBt) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvServerIp).visibility = if (isBt) View.GONE else View.VISIBLE
        updatePairedDeviceVisibility(viewModel.pairedDeviceName.value)
    }

    private fun updatePairedDeviceVisibility(name: String?) {
        val section = findViewById<View>(R.id.sectionPairedDevice)
        val nameView = findViewById<TextView>(R.id.tvPairedDeviceName)
        val isBt = viewModel.settings.value.connectionMode == ConnectionMode.BLUETOOTH
        if (name != null && isBt) {
            section.visibility = View.VISIBLE
            @SuppressLint("SetTextI18n")
            nameView.text = "蓝牙已配对: $name"
        } else {
            section.visibility = View.GONE
        }
    }

    private fun updateGyroChipsLockState(presetGyroOrientation: GyroOrientation?) {
        val orientationChips = listOf(
            R.id.btnGyroOriLandscape, R.id.btnGyroOriPortrait, R.id.btnGyroOriPortraitInverted
        )
        if (presetGyroOrientation != null) {
            selectChipGroup(orientationChips, presetGyroOrientation.ordinal)
        }
    }

    private fun updateGyroLandscapeInvertedNote(inverted: Boolean) {
        findViewById<TextView>(R.id.tvGyroLandscapeInvertedNote).visibility =
            if (inverted) View.VISIBLE else View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun syncSettingsUI() {
        val s = viewModel.settings.value
        findViewById<Switch>(R.id.switchEditMode).isChecked = false

        selectChipGroup(listOf(R.id.btnDisplayXbox, R.id.btnDisplayPlaystation, R.id.btnDisplaySwitch),
            DisplayMode.entries.indexOf(s.displayMode).coerceAtLeast(0))
        selectChipGroup(listOf(R.id.btnConnWifi, R.id.btnConnBluetooth),
            ConnectionMode.entries.indexOf(s.connectionMode).coerceAtLeast(0))
        selectChipGroup(listOf(R.id.btnTargetWindows, R.id.btnTargetAndroid),
            TargetPlatform.entries.indexOf(s.targetPlatform).coerceAtLeast(0))
        findViewById<Switch>(R.id.switchBtnVibration).isChecked = s.vibrationEnabled
        findViewById<Switch>(R.id.switchGameVibration).isChecked = s.gameVibrationEnabled
        updateVibrationUI()
        updateSettingsVisibility(s.connectionMode)

        findViewById<Switch>(R.id.switchAutoStart).isChecked = s.autoStartEnabled
        findViewById<Switch>(R.id.switchGyroEnabled).isChecked = s.gyroEnabled
        val effectiveOrientation = viewModel.currentPreset.value.gyroOrientation ?: s.gyroOrientation
        selectChipGroup(listOf(R.id.btnGyroOriLandscape, R.id.btnGyroOriPortrait, R.id.btnGyroOriPortraitInverted),
            GyroOrientation.entries.indexOf(effectiveOrientation).coerceAtLeast(0))
        findViewById<TextView>(R.id.tvGyroSensitivityX).text = "X: 0.00"
        findViewById<TextView>(R.id.tvGyroSensitivityY).text = "Y: 0.00"
        findViewById<TextView>(R.id.tvGyroSensitivityZ).text = "Z: 0.00"

        updateGyroChipsLockState(viewModel.currentPreset.value.gyroOrientation)

        @Suppress("DEPRECATION")
        val inverted = windowManager.defaultDisplay.rotation == Surface.ROTATION_270
        updateGyroLandscapeInvertedNote(inverted)

        findViewById<Switch>(R.id.switchKeepScreenOn).isChecked = s.keepScreenOn
        updateVolumeMappingLabels()

        val physicalConnected = physicalControllerHandler.isConnected.value
        findViewById<Switch>(R.id.switchControllerGyro).isChecked =
            physicalConnected && s.controllerGyroEnabled
        findViewById<Switch>(R.id.switchControllerGyro).isEnabled = physicalConnected
        vibrationMappingEntries = if (physicalConnected) VibrationMotor.entries.toList()
            else listOf(VibrationMotor.PHONE_MOTOR)
        updateMappingAdapter(findViewById(R.id.spinnerStrongVibration))
        updateMappingAdapter(findViewById(R.id.spinnerWeakVibration))
        findViewById<Spinner>(R.id.spinnerStrongVibration).setSelection(
            vibrationMappingEntries.indexOf(s.strongVibrationMapping).coerceAtLeast(0))
        findViewById<Spinner>(R.id.spinnerWeakVibration).setSelection(
            vibrationMappingEntries.indexOf(s.weakVibrationMapping).coerceAtLeast(0))
        findViewById<TextView>(R.id.tvPhysicalControllerStatus).text =
            if (physicalConnected) "已连接: ${physicalControllerHandler.controllerName.value}"
            else "未连接手柄"
        findViewById<TextView>(R.id.tvControllerGyroNote).visibility =
            if (s.controllerGyroEnabled && physicalConnected) View.VISIBLE else View.GONE

        refreshPresetList()
    }

    // ── State Observation ────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { st ->
                        for (label in touchpadLabels) label.text = st.statusText
                        findViewById<TextView>(R.id.tvConnectionStatus).text = st.statusText
                        val btn = findViewById<Button>(R.id.btnConnectAction)
                        btn.text = if (st.phase != ConnectionPhase.IDLE) "停止服务" else "启动服务"
                        val ip = if (viewModel.settings.value.connectionMode == ConnectionMode.WIFI &&
                            st.statusText != "未启动"
                        ) {
                            "本机 IP: ${viewModel.getServerIp()}"
                        } else ""
                        findViewById<TextView>(R.id.tvServerIp).text = ip

                        val transportType = st.transportType
                        val isClassicBt = transportType == BluetoothTransportType.CLASSIC

                        if (st.phase == ConnectionPhase.DISCOVERABLE
                            && !discoverableRequested
                            && viewModel.settings.value.connectionMode == ConnectionMode.BLUETOOTH
                            && isClassicBt
                        ) {
                            discoverableRequested = true
                            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                            }
                            discoverableLauncher.launch(intent)
                        }
                        if (st.phase == ConnectionPhase.IDLE) {
                            discoverableRequested = false
                        }
                    }
                }
                launch {
                    viewModel.displayMode.collect { mode ->
                        updateButtonLabels(mode)
                    }
                }
                launch {
                    viewModel.settings.collect { s ->
                        controlViews["touchpad"]?.visibility = View.VISIBLE
                        updatePairedDeviceVisibility(viewModel.pairedDeviceName.value)
                        if (s.keepScreenOn) {
                            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        val gyroDisabled = s.controllerGyroEnabled
                        listOf(
                            R.id.btnGyroOriLandscape,
                            R.id.btnGyroOriPortrait,
                            R.id.btnGyroOriPortraitInverted,
                        ).forEach { id ->
                            findViewById<Button>(id).isEnabled = !gyroDisabled
                            findViewById<Button>(id).alpha = if (gyroDisabled) 0.4f else 1.0f
                        }
                    }
                }
                launch {
                    viewModel.currentPreset.collect { preset ->
                        viewModel.currentPresetGyroOrientation = preset.gyroOrientation
                        if (!gamepadLayout.isEditModeActive()) {
                            applyPreset(preset)
                        }
                        updateGyroChipsLockState(preset.gyroOrientation)
                    }
                }
                launch {
                    viewModel.presetInfos.collect { _ ->
                        if (inSettings) refreshPresetList()
                    }
                }
                launch {
                    viewModel.pairedDeviceName.collect { name ->
                        updatePairedDeviceVisibility(name)
                    }
                }
                launch {
                    viewModel.gyroDisplay.collect { (x, y, z) ->
                        findViewById<SeekBar>(R.id.seekGyroSensitivityX).progress = (x * 100).toInt().coerceIn(-3000, 3000)
                        findViewById<SeekBar>(R.id.seekGyroSensitivityY).progress = (y * 100).toInt().coerceIn(-3000, 3000)
                        findViewById<SeekBar>(R.id.seekGyroSensitivityZ).progress = (z * 100).toInt().coerceIn(-3000, 3000)
                        findViewById<TextView>(R.id.tvGyroSensitivityX).text = "X: %.2f".format(x)
                        findViewById<TextView>(R.id.tvGyroSensitivityY).text = "Y: %.2f".format(y)
                        findViewById<TextView>(R.id.tvGyroSensitivityZ).text = "Z: %.2f".format(z)
                    }
                }
                launch {
                    physicalControllerHandler.isConnected.collect { connected ->
                        val s = viewModel.settings.value
                        physicalControllerHandler.onControllerGyroSettingChanged(s.controllerGyroEnabled)
                        physicalControllerHandler.strongVibrationMapping = s.strongVibrationMapping
                        physicalControllerHandler.weakVibrationMapping = s.weakVibrationMapping
                        findViewById<Switch>(R.id.switchControllerGyro).isChecked =
                            connected && s.controllerGyroEnabled
                        findViewById<Switch>(R.id.switchControllerGyro).isEnabled = connected
                        findViewById<TextView>(R.id.tvPhysicalControllerStatus).text =
                            if (connected) "已连接: ${physicalControllerHandler.controllerName.value}"
                            else "未连接手柄"
                        findViewById<TextView>(R.id.tvControllerGyroNote).visibility =
                            if (s.controllerGyroEnabled && connected) View.VISIBLE else View.GONE

                        // Restrict vibration mapping options when disconnected
                        vibrationMappingEntries = if (connected) VibrationMotor.entries.toList()
                            else listOf(VibrationMotor.PHONE_MOTOR)
                        updateMappingAdapter(findViewById(R.id.spinnerStrongVibration))
                        updateMappingAdapter(findViewById(R.id.spinnerWeakVibration))
                        findViewById<Spinner>(R.id.spinnerStrongVibration).setSelection(
                            vibrationMappingEntries.indexOf(s.strongVibrationMapping).coerceAtLeast(0))
                        findViewById<Spinner>(R.id.spinnerWeakVibration).setSelection(
                            vibrationMappingEntries.indexOf(s.weakVibrationMapping).coerceAtLeast(0))
                    }
                }
                launch {
                    physicalControllerHandler.gyroData.collect { gyro ->
                        if (viewModel.settings.value.controllerGyroEnabled) {
                            val accel = physicalControllerHandler.accelData.value
                            viewModel.onPhysicalControllerGyro(
                                gyro[0], gyro[1], gyro[2],
                                accel[0], accel[1], accel[2],
                            )
                        }
                    }
                }
            }
        }
    }
}
