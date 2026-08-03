package com.zyz4.gamepademu

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.HapticEffect
import com.zyz4.gamepademu.model.LayoutPreset
import com.zyz4.gamepademu.model.TargetPlatform
import com.zyz4.gamepademu.model.VibrationMotor
import com.zyz4.gamepademu.model.VibrationType
import com.zyz4.gamepademu.service.ConnectionPhase
import com.zyz4.gamepademu.GamepadViewModel.PresetInfo
import com.zyz4.gamepademu.view.WrapContentGridView
import com.zyz4.gamepademu.view.PresetPreviewView

// ── Settings ─────────────────────────────────────────────

/** Inflates the settings panel on first use and wires up its listeners. Safe to call
 *  repeatedly; the expensive inflation happens only once. */
internal fun MainActivity.ensureSettingsInflated() {
    val a = this
    if (a.settingsInflated) return
    (a.findViewById<ViewStub>(R.id.settingsStub))?.inflate()
    a.settingsInflated = true
    a.setupSettings()
}

internal fun MainActivity.showSettings() {
    val a = this
    if (a.gamepadLayout.isEditModeActive()) return
    a.ensureSettingsInflated()
    a.inSettings = true
    a.findViewById<View>(R.id.gamepadPanel).visibility = View.GONE
    a.findViewById<View>(R.id.settingsPanel).visibility = View.VISIBLE
    a.selectSettingsCategory(0)
    a.syncSettingsUI()
}

internal fun MainActivity.hideSettings() {
    val a = this
    a.inSettings = false
    a.vibrationPollingJob?.cancel()
    a.findViewById<View>(R.id.gamepadPanel).visibility = View.VISIBLE
    a.findViewById<View>(R.id.settingsPanel).visibility = View.GONE
}

internal fun MainActivity.selectSettingsCategory(index: Int) {
    val a = this
    a.currentSettingsCategory = index
    val pages = listOf(R.id.pageConnection, R.id.pagePresets, R.id.pageAppearance, R.id.pagePhysicalController, R.id.pageVibration, R.id.pageGyro, R.id.pageMisc, R.id.pageAbout)
    val buttons = listOf(
        R.id.btnCategoryConnection, R.id.btnCategoryPresets, R.id.btnCategoryAppearance, R.id.btnCategoryPhysicalController, R.id.btnCategoryVibration, R.id.btnCategoryGyro, R.id.btnCategoryMisc, R.id.btnCategoryAbout
    )
    pages.forEachIndexed { i, id ->
        a.findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
    }
    buttons.forEachIndexed { i, id ->
        a.findViewById<Button>(id).isSelected = i == index
        a.findViewById<Button>(id).setTextColor(
            if (i == index) -0x1 else -0x777778
        )
    }
    a.vibrationPollingJob?.cancel()
    if (index == 2) {
        a.findViewById<View>(R.id.previewContainer).post {
            a.updateAppearancePreview()
        }
    }
    if (index == 4) {
        a.vibrationPollingJob = a.lifecycleScope.launch {
            while (true) {
                a.refreshVibrationRedirect()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}

internal fun MainActivity.setupSettings() {
    val a = this
    a.findViewById<Button>(R.id.btnSettingsBack).setOnClickListener { a.hideSettings() }

    // Category switching
    a.findViewById<Button>(R.id.btnCategoryConnection).setOnClickListener { a.selectSettingsCategory(0) }
    a.findViewById<Button>(R.id.btnCategoryPresets).setOnClickListener { a.selectSettingsCategory(1) }
    a.findViewById<Button>(R.id.btnCategoryAppearance).setOnClickListener { a.selectSettingsCategory(2) }
    a.findViewById<Button>(R.id.btnCategoryPhysicalController).setOnClickListener { a.selectSettingsCategory(3) }
    a.findViewById<Button>(R.id.btnCategoryVibration).setOnClickListener { a.selectSettingsCategory(4) }
    a.findViewById<Button>(R.id.btnCategoryGyro).setOnClickListener { a.selectSettingsCategory(5) }
    a.findViewById<Button>(R.id.btnCategoryMisc).setOnClickListener { a.selectSettingsCategory(6) }
    a.findViewById<Button>(R.id.btnCategoryAbout).setOnClickListener { a.selectSettingsCategory(7) }

    // Sidebar scrollbar
    a.findViewById<ScrollView>(R.id.scrollSidebar).apply {
        viewTreeObserver.addOnGlobalLayoutListener {
            val aboutBtn = a.findViewById<View>(R.id.btnCategoryAbout)
            val visibleTop = maxOf(0, aboutBtn.top - scrollY)
            val visibleBottom = minOf(height, aboutBtn.bottom - scrollY)
            val visibleRatio = maxOf(0, visibleBottom - visibleTop).toFloat() / aboutBtn.height
            isVerticalScrollBarEnabled = visibleRatio < 0.5f
        }
    }

    // ── Presets page ──
    a.findViewById<Switch>(R.id.switchEditMode).setOnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            val currentName = a.viewModel.settings.value.currentPresetName
            if (a.viewModel.isBuiltInPreset(currentName)) {
                a.showToast("内置布局禁止编辑")
                a.findViewById<Switch>(R.id.switchEditMode).isChecked = false
                return@setOnCheckedChangeListener
            }
            a.viewModel.updateEditMode(true)
            a.hideSettings()
            a.applyPreset(a.viewModel.currentPreset.value)
            a.gamepadLayout.enterEditMode()
            a.floatingEditor.presetGyroOrientation = a.gamepadLayout.currentGyroOrientation
        }
    }

    val gridView = a.findViewById<WrapContentGridView>(R.id.gridPresets)
    gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
        val infos = a.viewModel.presetInfos.value
        val info = infos.getOrNull(position) ?: return@OnItemClickListener
        a.loadPresetByName(info.name)
    }

    a.findViewById<Button>(R.id.btnPresetNew).setOnClickListener { a.showNewPresetDialog() }
    a.findViewById<Button>(R.id.btnPresetImport).setOnClickListener {
        a.importPresetLauncher.launch(arrayOf("application/json", "*/*"))
    }
    a.findViewById<Button>(R.id.btnPresetExport).setOnClickListener {
        val name = a.viewModel.settings.value.currentPresetName
        a.exportPresetLauncher.launch("$name.json")
    }
    a.findViewById<Button>(R.id.btnPresetRename).setOnClickListener {
        val infos = a.viewModel.presetInfos.value
        val current = a.viewModel.settings.value.currentPresetName
        val idx = infos.indexOfFirst { it.name == current }
        val name = if (idx >= 0) infos[idx].name else infos.firstOrNull()?.name ?: return@setOnClickListener
        if (a.viewModel.isBuiltInPreset(name)) { a.showToast("内置布局禁止重命名"); return@setOnClickListener }
        a.showRenameDialog(name)
    }

    a.findViewById<Button>(R.id.btnPresetDelete).setOnClickListener {
        val infos = a.viewModel.presetInfos.value
        val current = a.viewModel.settings.value.currentPresetName
        val idx = infos.indexOfFirst { it.name == current }
        val selected = if (idx >= 0) infos[idx].name else infos.firstOrNull()?.name ?: return@setOnClickListener
        if (a.viewModel.isBuiltInPreset(selected)) { a.showToast("内置布局禁止删除"); return@setOnClickListener }
        CustomDialog.showConfirm(a, "删除预设", "确定删除「$selected」？",
            positiveText = "删除", onPositive = { a.viewModel.deletePreset(selected); a.refreshPresetList() })
    }

    // ── Controller page ──
    listOf(R.id.btnDisplayXbox to 0, R.id.btnDisplayPlaystation to 1, R.id.btnDisplaySwitch to 2)
        .forEach { (id, idx) ->
            a.findViewById<Button>(id).setOnClickListener {
                a.selectChipGroup(listOf(R.id.btnDisplayXbox, R.id.btnDisplayPlaystation, R.id.btnDisplaySwitch), idx)
                a.viewModel.updateDisplayMode(DisplayMode.entries[idx])
            }
        }

    listOf(R.id.btnConnWifi to 0, R.id.btnConnBluetooth to 1).forEach { (id, idx) ->
        a.findViewById<Button>(id).setOnClickListener {
            if (a.viewModel.connectionState.value.phase != ConnectionPhase.IDLE) {
                a.showToast("请先停止服务")
                return@setOnClickListener
            }
            a.selectChipGroup(listOf(R.id.btnConnWifi, R.id.btnConnBluetooth), idx)
            val mode = ConnectionMode.entries[idx]
            a.viewModel.updateConnectionMode(mode)
            a.updateSettingsVisibility(mode)
        }
    }

    listOf(R.id.btnTargetWindows to 0, R.id.btnTargetAndroid to 1, R.id.btnTargetLinux to 2)
        .forEach { (id, idx) ->
            a.findViewById<Button>(id).setOnClickListener {
                val platform = TargetPlatform.entries[idx]
                if (platform == a.viewModel.settings.value.targetPlatform) return@setOnClickListener
                val btRunning = a.viewModel.settings.value.connectionMode == ConnectionMode.BLUETOOTH
                    && a.viewModel.isBluetoothRunning
                if (btRunning) {
                    CustomDialog.showConfirm(a, "切换目标平台",
                        "将删除已保存的配对设备，是否继续？",
                        positiveText = "确定", onPositive = {
                            a.selectChipGroup(listOf(R.id.btnTargetWindows, R.id.btnTargetAndroid, R.id.btnTargetLinux), idx)
                            a.viewModel.switchTargetPlatform(platform)
                        })
                } else {
                    a.selectChipGroup(listOf(R.id.btnTargetWindows, R.id.btnTargetAndroid, R.id.btnTargetLinux), idx)
                    a.viewModel.updateTargetPlatform(platform)
                }
            }
        }

    // ── Vibration page ──
    a.findViewById<Switch>(R.id.switchBtnVibration).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateVibrationEnabled(isChecked)
    }
    a.findViewById<Switch>(R.id.switchGameVibration).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateGameVibrationEnabled(isChecked)
    }

    val pressTypeIds = listOf(
        R.id.btnVibPressTypeNone to VibrationType.NONE,
        R.id.btnVibPressTypeView to VibrationType.VIEW,
        R.id.btnVibPressTypeEffect to VibrationType.VIBRATION_EFFECT,
    )
    pressTypeIds.forEach { (id, type) ->
        a.findViewById<Button>(id).setOnClickListener {
            a.viewModel.updateVibrationPressType(type)
            a.updateVibrationUI()
        }
    }
    val releaseTypeIds = listOf(
        R.id.btnVibReleaseTypeNone to VibrationType.NONE,
        R.id.btnVibReleaseTypeView to VibrationType.VIEW,
        R.id.btnVibReleaseTypeEffect to VibrationType.VIBRATION_EFFECT,
    )
    releaseTypeIds.forEach { (id, type) ->
        a.findViewById<Button>(id).setOnClickListener {
            a.viewModel.updateVibrationReleaseType(type)
            a.updateVibrationUI()
        }
    }

    a.setupEffectSpinner(R.id.spinnerPressEffect, isPress = true)
    a.setupEffectSpinner(R.id.spinnerReleaseEffect, isPress = false)

    a.findViewById<SeekBar>(R.id.seekPressDuration).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (progress < 1) { sb.progress = 1; return }
                a.viewModel.updateVibrationPressDuration(progress)
                a.findViewById<TextView>(R.id.tvPressDuration).text = "时长: ${progress}ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )
    a.findViewById<SeekBar>(R.id.seekPressIntensity).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.viewModel.updateVibrationPressIntensity(progress)
                a.findViewById<TextView>(R.id.tvPressIntensity).text = "强度: $progress"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    a.findViewById<SeekBar>(R.id.seekReleaseDuration).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (progress < 1) { sb.progress = 1; return }
                a.viewModel.updateVibrationReleaseDuration(progress)
                a.findViewById<TextView>(R.id.tvReleaseDuration).text = "时长: ${progress}ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )
    a.findViewById<SeekBar>(R.id.seekReleaseIntensity).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.viewModel.updateVibrationReleaseIntensity(progress)
                a.findViewById<TextView>(R.id.tvReleaseIntensity).text = "强度: $progress"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // Test button
    a.findViewById<Button>(R.id.btnTestVibration).setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { v.performClick(); a.testHaptic(isPress = true); true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { a.testHaptic(isPress = false); true }
            else -> false
        }
    }

    // Vibration Mapping spinners
    a.setupVibrationMappingSpinner(R.id.spinnerStrongVibration) { mapping ->
        if (a.physicalControllerHandler.isConnected.value) {
            a.viewModel.updateStrongVibrationMappingConnected(mapping)
        } else {
            a.viewModel.updateStrongVibrationMapping(mapping)
        }
        a.physicalControllerHandler.strongVibrationMapping = mapping
    }
    a.setupVibrationMappingSpinner(R.id.spinnerWeakVibration) { mapping ->
        if (a.physicalControllerHandler.isConnected.value) {
            a.viewModel.updateWeakVibrationMappingConnected(mapping)
        } else {
            a.viewModel.updateWeakVibrationMapping(mapping)
        }
        a.physicalControllerHandler.weakVibrationMapping = mapping
    }

    // ── Gyro page ──
    a.findViewById<Switch>(R.id.switchGyroEnabled).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateGyroEnabled(isChecked)
    }

    listOf(
        R.id.btnGyroOriLandscape to GyroOrientation.LANDSCAPE,
        R.id.btnGyroOriPortrait to GyroOrientation.PORTRAIT,
        R.id.btnGyroOriPortraitInverted to GyroOrientation.PORTRAIT_INVERTED,
    ).forEach { (id, orientation) ->
        a.findViewById<Button>(id).setOnClickListener {
            val locked = a.viewModel.currentPreset.value.gyroOrientation
            if (locked != null) {
                val name = a.viewModel.settings.value.currentPresetName
                a.showToast("体感握持方向被布局「$name」锁定")
                return@setOnClickListener
            }
            a.selectChipGroup(
                listOf(R.id.btnGyroOriLandscape, R.id.btnGyroOriPortrait, R.id.btnGyroOriPortraitInverted),
                orientation.ordinal
            )
            a.viewModel.updateGyroOrientation(orientation)
        }
    }

    a.findViewById<SeekBar>(R.id.seekGyroSensitivityX).apply {
        min = -3000
        isEnabled = false
        setOnTouchListener { _, _ -> true }
    }
    a.findViewById<SeekBar>(R.id.seekGyroSensitivityY).apply {
        min = -3000
        isEnabled = false
        setOnTouchListener { _, _ -> true }
    }
    a.findViewById<SeekBar>(R.id.seekGyroSensitivityZ).apply {
        min = -3000
        isEnabled = false
        setOnTouchListener { _, _ -> true }
    }

    // Controller gyro toggle
    a.findViewById<Switch>(R.id.switchControllerGyro).setOnCheckedChangeListener { _, isChecked ->
        if (a.physicalControllerHandler.isConnected.value) {
            a.viewModel.updateControllerGyroEnabledConnected(isChecked)
        } else {
            a.viewModel.updateControllerGyroEnabled(isChecked)
        }
        a.physicalControllerHandler.onControllerGyroSettingChanged(isChecked)
        a.findViewById<TextView>(R.id.tvControllerGyroNote).visibility =
            if (isChecked) View.VISIBLE else View.GONE
    }

    listOf(R.id.seekControllerGyroX, R.id.seekControllerGyroY, R.id.seekControllerGyroZ).forEach { id ->
        a.findViewById<SeekBar>(id).apply {
            min = -3000
            isEnabled = false
            setOnTouchListener { _, _ -> true }
        }
    }

    // ── Physical Controller page ──
    a.findViewById<Button>(R.id.btnGoVibration).setOnClickListener {
        a.selectSettingsCategory(3)
    }
    a.findViewById<Button>(R.id.btnGoGyro).setOnClickListener {
        a.selectSettingsCategory(4)
    }

    a.findViewById<Switch>(R.id.switchNonLinearTriggerAdaptation).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateNonLinearTriggerAdaptation(isChecked)
        a.physicalControllerHandler.nonLinearTriggerAdaptation = isChecked
    }

    // ── Misc page ──
    a.setupMiscPage()

    // ── Appearance page ──
    a.setupAppearancePage()

    // ── About page ──
    a.setupAboutPage()

    // ── Connection page ──
    a.setupConnectionPage()
    a.setupUnpairButton()

    a.viewModel.connectionManager.onRumbleRequest = { large, small ->
        a.physicalControllerHandler.rumble(large, small)
    }
}

internal fun MainActivity.setupEffectSpinner(spinnerId: Int, isPress: Boolean) {
    val a = this
    val spinner = a.findViewById<Spinner>(spinnerId)
    val names = HapticEffect.entries.map { it.displayName }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            val effect = HapticEffect.entries[pos]
            if (isPress) a.viewModel.updateVibrationPressViewEffect(effect)
            else a.viewModel.updateVibrationReleaseViewEffect(effect)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

internal fun MainActivity.setupVibrationMappingSpinner(spinnerId: Int, onChanged: (VibrationMotor) -> Unit) {
    val a = this
    val spinner = a.findViewById<Spinner>(spinnerId)
    a.vibrationMappingEntries = VibrationMotor.entries.toList()
    a.updateMappingAdapter(spinner)
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            if (pos < a.vibrationMappingEntries.size) onChanged(a.vibrationMappingEntries[pos])
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

internal fun MainActivity.updateMappingAdapter(spinner: Spinner) {
    val a = this
    val names = a.vibrationMappingEntries.map { it.displayName }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
}

internal fun MainActivity.updateVibrationUI() {
    val a = this
    val s = a.viewModel.settings.value

    a.selectChipGroup(listOf(R.id.btnVibPressTypeNone, R.id.btnVibPressTypeView, R.id.btnVibPressTypeEffect),
        s.vibrationPressType.ordinal)
    a.findViewById<View>(R.id.layoutPressViewEffects).visibility =
        if (s.vibrationPressType == VibrationType.VIEW) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.layoutPressVibEffect).visibility =
        if (s.vibrationPressType == VibrationType.VIBRATION_EFFECT) View.VISIBLE else View.GONE
    a.findViewById<Spinner>(R.id.spinnerPressEffect).setSelection(s.vibrationPressViewEffect.ordinal)
    a.findViewById<TextView>(R.id.tvPressDuration).text = "时长: ${s.vibrationPressDuration}ms"
    a.findViewById<TextView>(R.id.tvPressIntensity).text = "强度: ${s.vibrationPressIntensity}"
    a.findViewById<SeekBar>(R.id.seekPressDuration).progress = s.vibrationPressDuration
    a.findViewById<SeekBar>(R.id.seekPressIntensity).progress = s.vibrationPressIntensity

    a.selectChipGroup(listOf(R.id.btnVibReleaseTypeNone, R.id.btnVibReleaseTypeView, R.id.btnVibReleaseTypeEffect),
        s.vibrationReleaseType.ordinal)
    a.findViewById<View>(R.id.layoutReleaseViewEffects).visibility =
        if (s.vibrationReleaseType == VibrationType.VIEW) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.layoutReleaseVibEffect).visibility =
        if (s.vibrationReleaseType == VibrationType.VIBRATION_EFFECT) View.VISIBLE else View.GONE
    a.findViewById<Spinner>(R.id.spinnerReleaseEffect).setSelection(s.vibrationReleaseViewEffect.ordinal)
    a.findViewById<TextView>(R.id.tvReleaseDuration).text = "时长: ${s.vibrationReleaseDuration}ms"
    a.findViewById<TextView>(R.id.tvReleaseIntensity).text = "强度: ${s.vibrationReleaseIntensity}"
    a.findViewById<SeekBar>(R.id.seekReleaseDuration).progress = s.vibrationReleaseDuration
    a.findViewById<SeekBar>(R.id.seekReleaseIntensity).progress = s.vibrationReleaseIntensity
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.renderVibrationRedirect(value: String?) {
    val a = this
    val statusView = a.findViewById<TextView>(R.id.tvVibrationRedirectStatus) ?: return
    val noteView = a.findViewById<TextView>(R.id.tvVibrationRedirectNote) ?: return
    noteView.text = "开启震动重定向后，手机震动会自动变为手柄马达1震动。\n本应用的手柄震动不受此设置影响。\n如果要在本应用中使用手机震动，请在系统设置-更多设置-语言与输入法中关闭震动重定向。"
    noteView.visibility = View.VISIBLE
    when (value) {
        "0" -> {
            statusView.text = "震动重定向：已关闭"
            statusView.setTextColor(0xFF4CAF50.toInt())
        }
        "1" -> {
            statusView.text = "震动重定向：已开启"
            statusView.setTextColor(0xFFFF5252.toInt())
        }
        else -> {
            statusView.text = "震动重定向：没有这个设置项"
            statusView.setTextColor(0xFFBBBBBB.toInt())
        }
    }
}

/** Reads the vibration-redirect setting off the main thread and caches the result. */
internal suspend fun MainActivity.refreshVibrationRedirect() {
    val a = this
    val value = withContext(Dispatchers.IO) { a.readVibrationRedirectSettingBlocking() }
    a.vibrationRedirectStatus = value
    a.renderVibrationRedirect(value)
}

internal fun MainActivity.readVibrationRedirectSettingBlocking(): String? {
    val a = this
    val key = "vibrate_input_devices"
    try {
        Settings.System.getString(a.contentResolver, key)?.let { return it }
    } catch (_: SecurityException) {}
    try {
        Settings.Global.getString(a.contentResolver, key)?.let { return it }
    } catch (_: SecurityException) {}
    try {
        Settings.Secure.getString(a.contentResolver, key)?.let { return it }
    } catch (_: SecurityException) {}
    try {
        val process = ProcessBuilder("/system/bin/sh", "-c", "settings get system $key")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.waitFor()
        if (output.isNotEmpty() && output != "null") return output
    } catch (_: Exception) {}
    return null
}

internal fun MainActivity.testHaptic(isPress: Boolean) {
    val a = this
    val s = a.viewModel.settings.value
    if (!s.vibrationEnabled) return
    val type = if (isPress) s.vibrationPressType else s.vibrationReleaseType
    when (type) {
        VibrationType.NONE -> return
        VibrationType.VIEW -> {
            val e = if (isPress) s.vibrationPressViewEffect else s.vibrationReleaseViewEffect
            a.gamepadLayout.performHapticFeedback(a.hapticEffectToConstant(e))
        }
        VibrationType.VIBRATION_EFFECT -> {
            val dur = (if (isPress) s.vibrationPressDuration else s.vibrationReleaseDuration).coerceAtLeast(1)
            val amp = if (isPress) s.vibrationPressIntensity else s.vibrationReleaseIntensity
            a.vibrator.cancel()
            a.vibrator.vibrate(VibrationEffect.createOneShot(dur.toLong(), amp.coerceIn(0, 255)))
        }
    }
}

internal fun MainActivity.setupUnpairButton() {
    val a = this
    val nameView = a.findViewById<TextView>(R.id.tvPairedDeviceName)
    a.findViewById<Button>(R.id.btnUnpairDevice).setOnClickListener {
        val deviceName = nameView.text.toString()
        CustomDialog.showConfirm(a, "取消配对",
            "确定取消与「$deviceName」的配对？下次连接需要重新配对。",
            positiveText = "取消配对", onPositive = { a.viewModel.unpairDevice() })
    }
}

internal fun MainActivity.setupMiscPage() {
    val a = this
    a.findViewById<Switch>(R.id.switchKeepScreenOn).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateKeepScreenOn(isChecked)
        if (isChecked) {
            a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    a.findViewById<View>(R.id.btnAddVolumeUp).setOnClickListener {
        a.showOutputValuePicker(a.viewModel.settings.value.volumeUpBits) { newBits ->
            a.viewModel.updateVolumeUpBits(newBits)
            a.updateVolumeMappingLabels()
        }
    }
    a.findViewById<View>(R.id.btnClearVolumeUp).setOnClickListener {
        a.viewModel.updateVolumeUpBits(emptyList())
        a.updateVolumeMappingLabels()
    }
    a.findViewById<View>(R.id.btnAddVolumeDown).setOnClickListener {
        a.showOutputValuePicker(a.viewModel.settings.value.volumeDownBits) { newBits ->
            a.viewModel.updateVolumeDownBits(newBits)
            a.updateVolumeMappingLabels()
        }
    }
    a.findViewById<View>(R.id.btnClearVolumeDown).setOnClickListener {
        a.viewModel.updateVolumeDownBits(emptyList())
        a.updateVolumeMappingLabels()
    }
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.setupAboutPage() {
    val a = this
    val packageInfo = a.packageManager.getPackageInfo(a.packageName, 0)
    a.findViewById<TextView>(R.id.tvAppName).text = "Gamepad Emu"
    a.findViewById<TextView>(R.id.tvAppVersion).text = "版本 ${packageInfo.versionName}"
    a.findViewById<TextView>(R.id.tvAppDescription).text = "作者：4zyz4  软件Q群：639317971\n\n" +
            "开源地址：https://github.com/4zyz4/gamepad-emu-android\n" +
            "注意：本软件不是Emotion，请进入Q群1045923515以下载正版Emotion"

    a.findViewById<ImageView>(R.id.ivAppIcon).setImageResource(R.mipmap.icon)

    a.findViewById<Button>(R.id.btnSponsor).setOnClickListener {
        a.showSponsorDialog()
    }
}

internal fun MainActivity.showSponsorDialog() {
    val a = this
    val imageView = ImageView(a).apply {
        setImageResource(R.mipmap.reward)
        setScaleType(ImageView.ScaleType.FIT_CENTER)
        setPadding(40, 0, 40, 0)
    }
    val content = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(a).apply { text = "感谢您的支持！"; textSize = 14f; setTextColor(-0x777778); gravity = Gravity.CENTER })
        addView(imageView)
    }
    CustomDialog.showCustomView(a, "赞助", content, negativeText = "关闭")
}

internal fun MainActivity.setupConnectionPage() {
    val a = this
    a.findViewById<Button>(R.id.btnConnectAction).setOnClickListener {
        val st = a.viewModel.connectionState.value
        if (st.phase != ConnectionPhase.IDLE) {
            a.viewModel.stopServer()
        } else {
            val s = a.viewModel.settings.value
            if (s.connectionMode == ConnectionMode.BLUETOOTH
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                val connectGranted = ContextCompat.checkSelfPermission(
                    a, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                val advertiseGranted = ContextCompat.checkSelfPermission(
                    a, Manifest.permission.BLUETOOTH_ADVERTISE
                ) == PackageManager.PERMISSION_GRANTED
                if (connectGranted && advertiseGranted) {
                    a.checkBluetoothOnAndStart()
                } else {
                    a.bluetoothPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                        )
                    )
                }
            } else {
                a.viewModel.startServer()
            }
        }
    }

    a.findViewById<Switch>(R.id.switchAutoStart).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateAutoStartEnabled(isChecked)
    }
}

@Suppress("DEPRECATION")
internal fun MainActivity.checkBluetoothOnAndStart() {
    val a = this
    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter != null && !adapter.isEnabled) {
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        a.bluetoothEnableLauncher.launch(enableIntent)
    } else {
        a.viewModel.startServer()
    }
}

internal fun MainActivity.autoStartService() {
    val a = this
    val s = a.viewModel.settings.value
    if (!s.autoStartEnabled) return
    if (s.connectionMode == ConnectionMode.BLUETOOTH) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(
                a, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val advertiseGranted = ContextCompat.checkSelfPermission(
                a, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            if (!connectGranted || !advertiseGranted) return
        }
        a.checkBluetoothOnAndStart()
    } else {
        a.viewModel.startServer()
    }
}

internal fun MainActivity.loadPresetByName(name: String) {
    val a = this
    if (!a.viewModel.loadPreset(name)) return
    val preset = a.viewModel.currentPreset.value
    a.applyPreset(preset)
    a.showToast("已加载「$name」")
    a.refreshPresetList()
}

internal fun MainActivity.showNewPresetDialog() {
    CustomDialog.showInput(this, "新建布局", hint = "输入新预设名称",
        positiveText = "创建", onPositive = { name ->
            if (name.isNotEmpty()) {
                val preset = viewModel.createDefaultLayout()
                viewModel.savePreset(name, preset)
                applyPreset(preset)
                refreshPresetList()
                showToast("已创建「$name」")
            }
        })
}

internal fun MainActivity.importPresetFromUri(uri: Uri) {
    try {
        val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        val preset = LayoutPreset.fromJson(json)
        CustomDialog.showInput(this, "导入布局", hint = "输入预设名称",
            positiveText = "保存", onPositive = { name ->
                if (name.isNotEmpty()) {
                    viewModel.savePreset(name, preset)
                    applyPreset(preset)
                    refreshPresetList()
                    showToast("已导入「$name」")
                }
            })
    } catch (e: Exception) {
        showToast("导入失败: ${e.message}")
    }
}

internal fun MainActivity.exportPresetToUri(uri: Uri) {
    val a = this
    try {
        val json = a.viewModel.currentPreset.value.toJson()
        a.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
        a.showToast("导出成功")
    } catch (e: Exception) {
        a.showToast("导出失败: ${e.message}")
    }
}

internal fun MainActivity.showRenameDialog(oldName: String) {
    CustomDialog.showInput(this, "重命名", prefill = oldName,
        positiveText = "确定", onPositive = { newName ->
            if (newName.isNotEmpty() && newName != oldName) {
                viewModel.renamePreset(oldName, newName)
                refreshPresetList()
                showToast("已重命名为「$newName」")
            }
        })
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.refreshPresetList() {
    val a = this
    val gridView = a.findViewById<WrapContentGridView>(R.id.gridPresets) ?: return
    val infos = a.viewModel.presetInfos.value
    val current = a.viewModel.settings.value.currentPresetName
    a.findViewById<TextView>(R.id.tvCurrentPreset).text = "当前预设: $current"
    // Skip rebuilding the grid (inflating cards) when nothing changed, so opening
    // settings repeatedly doesn't re-inflate all preset preview cards.
    if (a.lastPresetInfos == infos && a.lastPresetCurrentName == current &&
        gridView.adapter is PresetGridAdapter
    ) return
    a.lastPresetInfos = infos
    a.lastPresetCurrentName = current
    gridView.adapter = PresetGridAdapter(a, infos, current)
}

internal class PresetGridAdapter(
    private val activity: MainActivity,
    private val infos: List<GamepadViewModel.PresetInfo>,
    private val currentPresetName: String
) : android.widget.BaseAdapter() {
    override fun getCount() = infos.size
    override fun getItem(position: Int) = infos[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(activity)
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

internal fun MainActivity.updateSettingsVisibility(mode: ConnectionMode) {
    val a = this
    if (!a.settingsInflated) return
    val isBt = mode == ConnectionMode.BLUETOOTH
    val isWifi = mode == ConnectionMode.WIFI
    a.findViewById<View>(R.id.sectionTargetPlatform).visibility = if (isBt) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.tvServerIp).visibility = if (isWifi) View.VISIBLE else View.GONE
    a.updatePairedDeviceVisibility(a.viewModel.pairedDeviceName.value)
}

internal fun MainActivity.updatePairedDeviceVisibility(name: String?) {
    val a = this
    if (!a.settingsInflated) return
    val section = a.findViewById<View>(R.id.sectionPairedDevice)
    val nameView = a.findViewById<TextView>(R.id.tvPairedDeviceName)
    val isBt = a.viewModel.settings.value.connectionMode == ConnectionMode.BLUETOOTH
    if (name != null && isBt) {
        section.visibility = View.VISIBLE
        @SuppressLint("SetTextI18n")
        nameView.text = "蓝牙已配对: $name"
    } else {
        section.visibility = View.GONE
    }
}

internal fun MainActivity.updateGyroChipsLockState(presetGyroOrientation: GyroOrientation?) {
    val a = this
    if (!a.settingsInflated) return
    val orientationChips = listOf(
        R.id.btnGyroOriLandscape, R.id.btnGyroOriPortrait, R.id.btnGyroOriPortraitInverted
    )
    if (presetGyroOrientation != null) {
        a.selectChipGroup(orientationChips, presetGyroOrientation.ordinal)
    }
}

internal fun MainActivity.updateGyroLandscapeInvertedNote(inverted: Boolean) {
    if (!settingsInflated) return
    findViewById<TextView>(R.id.tvGyroLandscapeInvertedNote).visibility =
        if (inverted) View.VISIBLE else View.GONE
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.syncSettingsUI() {
    val a = this
    val s = a.viewModel.settings.value
    a.findViewById<Switch>(R.id.switchEditMode).isChecked = false

    // Re-sync connection status here too: observers may have dropped emissions
    // while the settings panel was not yet inflated.
    val st = a.viewModel.connectionState.value
    a.findViewById<TextView>(R.id.tvConnectionStatus).text = st.statusText
    a.findViewById<Button>(R.id.btnConnectAction).text =
        if (st.phase != ConnectionPhase.IDLE) "停止服务" else "启动服务"

    a.selectChipGroup(listOf(R.id.btnDisplayXbox, R.id.btnDisplayPlaystation, R.id.btnDisplaySwitch),
        DisplayMode.entries.indexOf(s.displayMode).coerceAtLeast(0))
    a.selectChipGroup(listOf(R.id.btnConnWifi, R.id.btnConnBluetooth),
        ConnectionMode.entries.indexOf(s.connectionMode).coerceAtLeast(0))
    a.selectChipGroup(listOf(R.id.btnTargetWindows, R.id.btnTargetAndroid, R.id.btnTargetLinux),
        TargetPlatform.entries.indexOf(s.targetPlatform).coerceAtLeast(0))
    a.findViewById<Switch>(R.id.switchBtnVibration).isChecked = s.vibrationEnabled
    a.findViewById<Switch>(R.id.switchGameVibration).isChecked = s.gameVibrationEnabled
    a.updateVibrationUI()
    a.vibrationRedirectStatus?.let { a.renderVibrationRedirect(it) }
        ?: a.lifecycleScope.launch { a.refreshVibrationRedirect() }
    a.updateSettingsVisibility(s.connectionMode)

    a.findViewById<Switch>(R.id.switchAutoStart).isChecked = s.autoStartEnabled
    a.findViewById<Switch>(R.id.switchGyroEnabled).isChecked = s.gyroEnabled
    val effectiveOrientation = a.viewModel.currentPreset.value.gyroOrientation ?: s.gyroOrientation
    a.selectChipGroup(listOf(R.id.btnGyroOriLandscape, R.id.btnGyroOriPortrait, R.id.btnGyroOriPortraitInverted),
        GyroOrientation.entries.indexOf(effectiveOrientation).coerceAtLeast(0))
    a.findViewById<TextView>(R.id.tvGyroSensitivityX).text = "X: 0.00"
    a.findViewById<TextView>(R.id.tvGyroSensitivityY).text = "Y: 0.00"
    a.findViewById<TextView>(R.id.tvGyroSensitivityZ).text = "Z: 0.00"

    a.updateGyroChipsLockState(a.viewModel.currentPreset.value.gyroOrientation)

    val inverted = a.windowManager.defaultDisplay.rotation == android.view.Surface.ROTATION_270
    a.updateGyroLandscapeInvertedNote(inverted)

    a.findViewById<Switch>(R.id.switchKeepScreenOn).isChecked = s.keepScreenOn
    a.findViewById<Switch>(R.id.switchNonLinearTriggerAdaptation).isChecked = s.nonLinearTriggerAdaptation
    a.physicalControllerHandler.nonLinearTriggerAdaptation = s.nonLinearTriggerAdaptation
    a.updateVolumeMappingLabels()

    a.syncAppearanceUI()
    a.applyAppearanceIfChanged(s)

    val physicalConnected = a.physicalControllerHandler.isConnected.value
    val strongMapping = if (physicalConnected) s.strongVibrationMappingConnected else s.strongVibrationMapping
    val weakMapping = if (physicalConnected) s.weakVibrationMappingConnected else s.weakVibrationMapping
    val gyroEnabled = if (physicalConnected) s.controllerGyroEnabledConnected else s.controllerGyroEnabled

    a.findViewById<Switch>(R.id.switchControllerGyro).isChecked =
        physicalConnected && gyroEnabled
    a.findViewById<Switch>(R.id.switchControllerGyro).isEnabled = physicalConnected
    a.findViewById<TextView>(R.id.tvControllerGyroNote).visibility =
        if (gyroEnabled && physicalConnected) View.VISIBLE else View.GONE
    a.findViewById<TextView>(R.id.tvControllerGyroX).text = "X: 0.00"
    a.findViewById<TextView>(R.id.tvControllerGyroY).text = "Y: 0.00"
    a.findViewById<TextView>(R.id.tvControllerGyroZ).text = "Z: 0.00"
    a.findViewById<SeekBar>(R.id.seekControllerGyroX).progress = 0
    a.findViewById<SeekBar>(R.id.seekControllerGyroY).progress = 0
    a.findViewById<SeekBar>(R.id.seekControllerGyroZ).progress = 0

    val motorCount = a.physicalControllerHandler.controllerMotorCount
    a.vibrationMappingEntries = if (physicalConnected) VibrationMotor.entries.take(motorCount) + VibrationMotor.PHONE_MOTOR + VibrationMotor.NONE
        else listOf(VibrationMotor.PHONE_MOTOR, VibrationMotor.NONE)
    fun sel(m: VibrationMotor) = a.vibrationMappingEntries.indexOf(m).let { if (it >= 0) it else a.vibrationMappingEntries.indexOf(VibrationMotor.PHONE_MOTOR) }
    a.updateMappingAdapter(a.findViewById(R.id.spinnerStrongVibration))
    a.findViewById<Spinner>(R.id.spinnerStrongVibration).setSelection(sel(strongMapping))
    a.updateMappingAdapter(a.findViewById(R.id.spinnerWeakVibration))
    a.findViewById<Spinner>(R.id.spinnerWeakVibration).setSelection(sel(weakMapping))

    a.findViewById<TextView>(R.id.tvPhysicalControllerStatus).text =
        if (physicalConnected) "已连接: ${a.physicalControllerHandler.controllerName.value}"
        else "未连接手柄"

    a.refreshPresetList()
}
