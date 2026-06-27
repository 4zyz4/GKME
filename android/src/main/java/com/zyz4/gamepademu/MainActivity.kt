package com.zyz4.gamepademu

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.ControllerMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.LayoutPreset
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.service.BluetoothTransportType
import com.zyz4.gamepademu.GamepadViewModel.PresetInfo
import com.zyz4.gamepademu.view.GamepadLayout
import com.zyz4.gamepademu.view.JoystickView
import com.zyz4.gamepademu.view.PresetPreviewView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: GamepadViewModel by viewModels()
    private lateinit var gamepadLayout: GamepadLayout
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
        setupGamepadLayoutListener()
        setupEditToolbar()
        setupGamepad()
        setupSettings()
        observeState()
    }

    // ── Gamepad Layout Listener ────────────────────────────────

    private fun setupGamepadLayoutListener() {
        gamepadLayout.listener = object : GamepadLayout.GamepadLayoutListener {
            override fun onButtonSelected(buttonId: String?) {
                viewModel.setSelectedButtonId(buttonId)
            }

            override fun onButtonMoved(buttonId: String, x: Int, y: Int) {
                viewModel.updatePresetButtons(gamepadLayout.getPreset())
            }

            override fun onEditModeChanged(isEditMode: Boolean) {
                findViewById<View>(R.id.editToolbar).visibility =
                    if (isEditMode) View.VISIBLE else View.GONE
                findViewById<ImageButton>(R.id.btnSettings).visibility =
                    if (isEditMode) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupEditToolbar() {
        findViewById<Button>(R.id.btnEditSave).setOnClickListener {
            viewModel.saveCurrentPreset()
            gamepadLayout.exitEditMode()
            viewModel.updateEditMode(false)
        }
        findViewById<Button>(R.id.btnEditDiscard).setOnClickListener {
            if (!gamepadLayout.hasUnsavedChanges()) {
                gamepadLayout.exitEditMode()
                viewModel.updateEditMode(false)
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
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

    private fun setupGamepad() {
        viewModel.onHapticFeedbackPress = {
            if (viewModel.settings.value.vibrationEnabled) hapticClick(gamepadLayout)
        }
        viewModel.onHapticFeedbackRelease = {
            if (viewModel.settings.value.vibrationEnabled) hapticTick(gamepadLayout)
        }
        setupTrigger(R.id.btnLT, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setupBumper(R.id.btnLB, GamepadState.LB)
            setupBumper(R.id.btnRB, GamepadState.RB)
        }
        setupTrigger(R.id.btnRT, false)

        setupDpad(R.id.btnDpadUp, GamepadState.DPAD_UP)
        setupDpad(R.id.btnDpadDown, GamepadState.DPAD_DOWN)
        setupDpad(R.id.btnDpadLeft, GamepadState.DPAD_LEFT)
        setupDpad(R.id.btnDpadRight, GamepadState.DPAD_RIGHT)

        setupActionBtn(R.id.btnA, GamepadState.A)
        setupActionBtn(R.id.btnB, GamepadState.B)
        setupActionBtn(R.id.btnX, GamepadState.X)
        setupActionBtn(R.id.btnY, GamepadState.Y)

        setupJoystick(R.id.leftJoystick, true)
        setupJoystick(R.id.rightJoystick, false)

        setupCenterArea()
        setupActionBtn(R.id.btnSelect, GamepadState.SELECT)
        updateButtonLabels(viewModel.settings.value.displayMode)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettings() }
        setupLongPressButton(R.id.btnHome, GamepadState.HOME)
        setupLongPressButton(R.id.btnMenu, GamepadState.START)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBumper(id: Int, bit: Int) {
        findViewById<Button>(id).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performClick()
                    viewModel.onButtonDown(bit); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    viewModel.onButtonUp(bit); true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTrigger(id: Int, isLeft: Boolean) {
        val bit = if (isLeft) GamepadState.LT else GamepadState.RT
        val analogFn: (Int) -> Unit = if (isLeft) viewModel::onLeftTrigger else viewModel::onRightTrigger
        findViewById<Button>(id).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performClick()
                    viewModel.onButtonDown(bit)
                    analogFn(255); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    viewModel.onButtonUp(bit)
                    analogFn(0); true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDpad(id: Int, dir: Int) {
        findViewById<ImageButton>(id).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performClick()
                    viewModel.onDpad(dir, true); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    viewModel.onDpad(dir, false); true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupActionBtn(id: Int, bit: Int) {
        findViewById<Button>(id).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performClick()
                    viewModel.onButtonDown(bit); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    viewModel.onButtonUp(bit); true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLongPressButton(id: Int, bit: Int) {
        findViewById<View>(id).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performClick()
                    viewModel.onButtonDown(bit); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    viewModel.onButtonUp(bit); true
                }
                else -> false
            }
        }
    }

    private fun setupJoystick(id: Int, isLeft: Boolean) {
        val stick = findViewById<JoystickView>(id)
        stick.onStickClickDown = {
            val bit = if (isLeft) GamepadState.L3 else GamepadState.R3
            viewModel.onButtonDown(bit)
        }
        stick.onStickClickUp = {
            val bit = if (isLeft) GamepadState.L3 else GamepadState.R3
            viewModel.onButtonUp(bit)
        }
        stick.onStickMoved = { sx, sy ->
            if (isLeft) viewModel.onLeftStick(sx, sy) else viewModel.onRightStick(sx, sy)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCenterArea() {
        val area = findViewById<FrameLayout>(R.id.centerArea)
        val handler = Handler(Looper.getMainLooper())
        var firstTapTime = 0L
        var firstTapX = 0f
        var firstTapY = 0f
        var isDoubleClick = false

        val doubleTapTimeout = Runnable {
            firstTapTime = 0
        }

        area.setOnTouchListener { v, event ->
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
                        v.isPressed = true
                        isDoubleClick = true
                        firstTapTime = 0

                        viewModel.onButtonDown(GamepadState.TOUCHPAD_CLICK)
                    } else {
                        firstTapTime = now
                        firstTapX = event.x
                        firstTapY = event.y
                        isDoubleClick = false
                        handler.postDelayed(doubleTapTimeout, 300)
                    }
                    if (ds4) {
                        viewModel.onTouchpad(sx, sy, true)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (firstTapTime != 0L) {
                        val dx = event.x - firstTapX
                        val dy = event.y - firstTapY
                        if (dx * dx + dy * dy > 30f * 30f) {
                            firstTapTime = 0
                            handler.removeCallbacks(doubleTapTimeout)
                        }
                    }
                    if (ds4) {
                        viewModel.onTouchpad(sx, sy, true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDoubleClick) {
                        v.isPressed = false
                        viewModel.onButtonUp(GamepadState.TOUCHPAD_CLICK)
                    }
                    if (ds4) viewModel.onTouchpad(sx, sy, false)
                    isDoubleClick = false
                    true
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
        val port = findViewById<EditText>(R.id.etPort).text.toString().toIntOrNull() ?: 37284
        viewModel.updateWifiServer("", port)
        findViewById<View>(R.id.gamepadPanel).visibility = View.VISIBLE
        findViewById<View>(R.id.settingsPanel).visibility = View.GONE
    }

    private fun selectSettingsCategory(index: Int) {
        val pages = listOf(R.id.pageConnection, R.id.pagePresets, R.id.pageVibration)
        val buttons = listOf(
            R.id.btnCategoryConnection, R.id.btnCategoryPresets, R.id.btnCategoryVibration
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

    private fun setupConnectionPage() {
        findViewById<Button>(R.id.btnConnectAction).setOnClickListener {
            val st = viewModel.connectionState.value
            if (st.phase != com.zyz4.gamepademu.service.ConnectionPhase.IDLE) {
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
            .setMessage("基于 Default 模板创建新布局")
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
        val abxyText = when (mode) {
            DisplayMode.XBOX -> listOf("A", "B", "X", "Y")
            DisplayMode.PLAYSTATION -> listOf("", "", "", "")
            DisplayMode.SWITCH -> listOf("B", "A", "Y", "X")
        }
        val abxyDrawables = intArrayOf(
            R.drawable.btn_ps_cross, R.drawable.btn_ps_circle,
            R.drawable.btn_ps_square, R.drawable.btn_ps_triangle
        )
        val abxyIds = listOf(R.id.btnA, R.id.btnB, R.id.btnX, R.id.btnY)
        abxyIds.forEachIndexed { i, id ->
            findViewById<Button>(id).apply {
                text = abxyText[i]
                setBackgroundResource(
                    if (mode == DisplayMode.PLAYSTATION) abxyDrawables[i] else R.drawable.button_circle
                )
            }
        }

        val bumpers = when (mode) {
            DisplayMode.XBOX -> listOf("LB", "RB", "LT", "RT")
            DisplayMode.PLAYSTATION -> listOf("L1", "R1", "L2", "R2")
            DisplayMode.SWITCH -> listOf("L", "R", "ZL", "ZR")
        }
        findViewById<Button>(R.id.btnLB).text = bumpers[0]
        findViewById<Button>(R.id.btnRB).text = bumpers[1]
        findViewById<Button>(R.id.btnLT).text = bumpers[2]
        findViewById<Button>(R.id.btnRT).text = bumpers[3]

        val selBtn = findViewById<Button>(R.id.btnSelect)
        val homeBtn = findViewById<ImageButton>(R.id.btnHome)
        val menuBtn = findViewById<Button>(R.id.btnMenu)
        when (mode) {
            DisplayMode.XBOX -> {
                selBtn.text = ""
                selBtn.setBackgroundResource(R.drawable.btn_select_xbox)
                homeBtn.setBackgroundResource(R.drawable.button_circle)
                homeBtn.setImageResource(R.drawable.ic_home_xbox)
                menuBtn.text = ""
                menuBtn.setBackgroundResource(R.drawable.btn_menu_xbox)
            }
            DisplayMode.PLAYSTATION -> {
                selBtn.text = "SHARE"
                selBtn.setBackgroundResource(R.drawable.button_circle)
                homeBtn.setBackgroundResource(R.drawable.button_circle)
                homeBtn.setImageResource(R.drawable.ic_home_playstation)
                menuBtn.text = "OPTION"
                menuBtn.setBackgroundResource(R.drawable.button_circle)
            }
            DisplayMode.SWITCH -> {
                selBtn.text = "\uFF0D"
                selBtn.setBackgroundResource(R.drawable.button_circle)
                homeBtn.setBackgroundResource(R.drawable.button_circle)
                homeBtn.setImageResource(R.drawable.ic_home)
                menuBtn.text = ""
                menuBtn.setBackgroundResource(R.drawable.btn_menu_switch)
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
        findViewById<EditText>(R.id.etPort).visibility = if (isBt) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvServerIp).visibility = if (isBt) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvBroadcastStatus).visibility = if (isBt) View.GONE else View.VISIBLE
    }

    private fun syncSettingsUI() {
        val s = viewModel.settings.value
        findViewById<EditText>(R.id.etPort).setText(s.wifiServerPort.toString())
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
                        findViewById<TextView>(R.id.centerText).text = st.statusText
                        findViewById<TextView>(R.id.tvConnectionStatus).text = st.statusText
                        val btn = findViewById<Button>(R.id.btnConnectAction)
                        btn.text = if (st.phase != com.zyz4.gamepademu.service.ConnectionPhase.IDLE) "停止服务" else "启动服务"
                        val ip = if (viewModel.settings.value.connectionMode == ConnectionMode.WIFI &&
                            st.statusText != "未启动"
                        ) {
                            "本机 IP: ${viewModel.getServerIp()}"
                        } else ""
                        findViewById<TextView>(R.id.tvServerIp).text = ip
                        findViewById<TextView>(R.id.tvBroadcastStatus).text =
                            if (st.connected && viewModel.settings.value.connectionMode == ConnectionMode.WIFI) "UDP 广播中 (每 3 秒)"
                            else ""

                        val transportType = st.transportType
                        val isClassicBt = transportType == BluetoothTransportType.CLASSIC

                        if (st.phase == com.zyz4.gamepademu.service.ConnectionPhase.DISCOVERABLE
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
                        if (st.phase == com.zyz4.gamepademu.service.ConnectionPhase.IDLE) {
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
                        findViewById<View>(R.id.centerArea).visibility = if (ds4) View.VISIBLE else View.GONE
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
                        val section = findViewById<View>(R.id.sectionPairedDevice)
                        val nameView = findViewById<TextView>(R.id.tvPairedDeviceName)
                        if (name != null) {
                            section.visibility = View.VISIBLE
                            @SuppressLint("SetTextI18n")
                            nameView.text = "蓝牙已配对: $name"
                        } else {
                            section.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
}
