package com.zyz4.gamepademu

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
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
import com.zyz4.gamepademu.model.ControllerMode
import com.zyz4.gamepademu.service.ConnectionPhase
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.LayoutPreset
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.service.BluetoothTransportType
import com.zyz4.gamepademu.GamepadViewModel.PresetInfo
import com.zyz4.gamepademu.view.GamepadLayout
import com.zyz4.gamepademu.view.JoystickView
import com.zyz4.gamepademu.view.PresetPreviewView
import com.zyz4.gamepademu.view.FloatingEditorPanel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: GamepadViewModel by viewModels()
    private lateinit var gamepadLayout: GamepadLayout
    private lateinit var floatingEditor: FloatingEditorPanel
    private val controlViews = mutableMapOf<String, View>()
    private val touchpadLabels = mutableListOf<TextView>()
    private var discoverableRequested = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableEdgeToEdge()
        gamepadLayout = findViewById(R.id.gamepadLayout)
        setupFloatingEditor()
        setupGamepadLayoutListener()
        createAllControls()
        setupSettings()
        observeState()
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
                }

                override fun onDiscard() {
                    if (!gamepadLayout.hasUnsavedChanges()) {
                        gamepadLayout.exitEditMode()
                        viewModel.updateEditMode(false)
                        return
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("放弃修改")
                        .setMessage("确定放弃当前布局修改？")
                        .setPositiveButton("放弃") { _, _ ->
                            gamepadLayout.discardToSnapshot()
                            gamepadLayout.exitEditMode()
                            viewModel.updateEditMode(false)
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
        CtrlEntry("btnSelect", "选择", R.drawable.btn_select_xbox, bit = GamepadState.SELECT, w = 9, h = 9),
        CtrlEntry("btnHome", "主页", R.drawable.ic_home, useImageButton = true, bit = GamepadState.HOME, w = 9, h = 9),
        CtrlEntry("btnMenu", "菜单", R.drawable.btn_menu_xbox, bit = GamepadState.START, w = 9, h = 9),
    )

    private fun showAddButtonDialog() {
        val density = resources.displayMetrics.density
        val cols = 4
        val cellW = (200f * density).toInt()
        val iconSize = (40f * density).toInt()

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
                val iv = ImageView(this).apply {
                    setImageResource(entry.icon)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                }
                val tv = TextView(this).apply {
                    text = entry.name
                    setTextColor(-0xcccccd)
                    textSize = 10f
                    gravity = android.view.Gravity.CENTER
                }
                wrapper.addView(iv)
                wrapper.addView(tv, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4f * density).toInt() })
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
            else -> Button(this).apply {
                this.id = View.generateViewId(); tag = id
                setTextColor(-0x333334); textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(entry.bgRes)
                gravity = android.view.Gravity.CENTER
            }
        }
        gamepadLayout.addView(view, 0)

        if (!entry.isTouchpad) {
            setupTouchHandler(view, entry.bit, entry.isDpad, entry.isTrigger, entry.isJoystick)
        }

        val pos = ButtonPosition(
            id = id, x = 50, y = 20,
            width = entry.w, height = entry.h,
            lockAspect = entry.lockAspect,
        )
        gamepadLayout.addButtonPosition(pos)
        gamepadLayout.setSelectedButton(id)
        updateButtonLabels(viewModel.settings.value.displayMode)
    }

    // ── Gamepad Layout Listener ────────────────────────────────

    private fun setupGamepadLayoutListener() {
        gamepadLayout.listener = object : GamepadLayout.GamepadLayoutListener {
            override fun onButtonSelected(buttonId: String?) {
                viewModel.setSelectedButtonId(buttonId)
                if (buttonId != null) {
                    val pos = gamepadLayout.currentButtons.find { it.id == buttonId }
                    if (pos != null) {
                        floatingEditor.showParameters(buttonId, pos)
                    }
                }
            }

            override fun onEditModeChanged(isEditMode: Boolean) {
                floatingEditor.visibility = if (isEditMode) View.VISIBLE else View.GONE
                findViewById<ImageButton>(R.id.btnSettings).visibility =
                    if (isEditMode) View.GONE else View.VISIBLE
            }
        }
    }

    private fun hapticClick(v: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun hapticTick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ── Gamepad ──────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createAllControls() {
        viewModel.onHapticFeedbackPress = {
            if (viewModel.settings.value.vibrationEnabled) hapticClick(gamepadLayout)
        }
        viewModel.onHapticFeedbackRelease = {
            if (viewModel.settings.value.vibrationEnabled) hapticTick(gamepadLayout)
        }

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
                    }
                    tp.addView(label)
                    touchpadLabels.add(label)
                    setupTouchpadView(tp)
                    tp
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
                    else -> false
                }
            }
            isTrigger -> {
                val analogFn: (Int) -> Unit = if (bit == GamepadState.LT) viewModel::onLeftTrigger else viewModel::onRightTrigger
                view.setOnTouchListener { v, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); viewModel.onButtonDown(bit); analogFn(255); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; viewModel.onButtonUp(bit); analogFn(0); true }
                        else -> false
                    }
                }
            }
            !isJoystick -> view.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.performClick(); if (bit != 0) viewModel.onButtonDown(bit); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; if (bit != 0) viewModel.onButtonUp(bit); true }
                    else -> false
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchpadView(tp: FrameLayout) {
        val handler = Handler(Looper.getMainLooper())
        var firstTapTime = 0L; var firstTapX = 0f; var firstTapY = 0f
        var isDoubleClick = false
        val doubleTapTimeout = Runnable { firstTapTime = 0 }

        tp.setOnTouchListener { v, event ->
            val s = viewModel.settings.value
            val ds4 = s.controllerMode == ControllerMode.DS4 && s.connectionMode == ConnectionMode.WIFI
            val w = v.measuredWidth.coerceAtLeast(1)
            val h = v.measuredHeight.coerceAtLeast(1)
            val sx = (event.x / w * 1919).toInt().coerceIn(0, 1919)
            val sy = (event.y / h * 942).toInt().coerceIn(0, 942)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performClick()
                    val now = System.currentTimeMillis()
                    if (now - firstTapTime < 300 && firstTapTime > 0) {
                        handler.removeCallbacks(doubleTapTimeout)
                        v.isPressed = true; isDoubleClick = true; firstTapTime = 0
                        viewModel.onButtonDown(GamepadState.TOUCHPAD_CLICK)
                    } else {
                        firstTapTime = now; firstTapX = event.x; firstTapY = event.y; isDoubleClick = false
                        handler.postDelayed(doubleTapTimeout, 300)
                    }
                    if (ds4) viewModel.onTouchpad(sx, sy, true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (firstTapTime != 0L) {
                        val dx = event.x - firstTapX; val dy = event.y - firstTapY
                        if (dx * dx + dy * dy > 30f * 30f) { firstTapTime = 0; handler.removeCallbacks(doubleTapTimeout) }
                    }
                    if (ds4) viewModel.onTouchpad(sx, sy, true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDoubleClick) { v.isPressed = false; viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK) }
                    if (ds4) viewModel.onTouchpad(sx, sy, false)
                    isDoubleClick = false; true
                }
                else -> false
            }
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
        val pages = listOf(R.id.pageConnection, R.id.pagePresets, R.id.pageVibration, R.id.pageAbout)
        val buttons = listOf(
            R.id.btnCategoryConnection, R.id.btnCategoryPresets, R.id.btnCategoryVibration, R.id.btnCategoryAbout
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
        findViewById<Button>(R.id.btnCategoryPresets).setOnClickListener { selectSettingsCategory(1) }
        findViewById<Button>(R.id.btnCategoryVibration).setOnClickListener { selectSettingsCategory(2) }
        findViewById<Button>(R.id.btnCategoryAbout).setOnClickListener { selectSettingsCategory(3) }

        // ── Presets page ──
        findViewById<Switch>(R.id.switchEditMode).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.updateEditMode(true)
                hideSettings()
                gamepadLayout.loadPreset(viewModel.currentPreset.value)
                gamepadLayout.enterEditMode()
            }
        }

        val gridView = findViewById<GridView>(R.id.gridPresets)
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
            showRenameDialog(name)
        }

        findViewById<Button>(R.id.btnPresetDelete).setOnClickListener {
            val infos = viewModel.presetInfos.value
            val current = viewModel.settings.value.currentPresetName
            val idx = infos.indexOfFirst { it.name == current }
            val selected = if (idx >= 0) infos[idx].name else infos.firstOrNull()?.name ?: return@setOnClickListener
            if (selected == "Default") { showToast("不能删除默认预设"); return@setOnClickListener }
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

        // ── About page ──
        setupAboutPage()

        // ── Connection page ──
        setupConnectionPage()
        setupUnpairButton()
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

    @SuppressLint("SetTextI18n")
    private fun setupAboutPage() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.tvAppName).text = "Gamepad Emu"
        findViewById<TextView>(R.id.tvAppVersion).text = "版本 ${packageInfo.versionName}"
        findViewById<TextView>(R.id.tvAppDescription).text = "作者：4zyz4\n\n" +
                "开源地址：https://github.com/4zyz4/gamepad-emu-android\n" +
                "https://github.com/4zyz4/gamepad-emu-windows\n\n" +
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

    private fun loadPresetByName(name: String) {
        if (!viewModel.loadPreset(name)) return
        val preset = viewModel.currentPreset.value
        gamepadLayout.loadPreset(preset)
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
                    val defaultJson = com.zyz4.gamepademu.data.LayoutRepository.DEFAULT_JSON
                    val preset = LayoutPreset.fromJson(defaultJson)
                    viewModel.savePreset(name, preset)
                    gamepadLayout.loadPreset(preset)
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
                        gamepadLayout.loadPreset(preset)
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
        val gridView = findViewById<GridView>(R.id.gridPresets)
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
            val baseId = (child.tag as? String)?.substringBefore("_") ?: continue

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
        updateSettingsVisibility(s.connectionMode)

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
                        val ds4 = s.controllerMode == ControllerMode.DS4 && s.connectionMode == ConnectionMode.WIFI
                        controlViews["touchpad"]?.visibility = if (ds4) View.VISIBLE else View.GONE
                        updatePairedDeviceVisibility(viewModel.pairedDeviceName.value)
                    }
                }
                launch {
                    viewModel.currentPreset.collect { preset ->
                        if (!gamepadLayout.isEditModeActive()) {
                            gamepadLayout.loadPreset(preset)
                        }
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
            }
        }
    }
}
