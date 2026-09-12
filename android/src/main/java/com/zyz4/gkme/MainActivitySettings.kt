package com.zyz4.gkme

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
import android.view.ViewTreeObserver
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zyz4.gkme.model.AudioOutput
import com.zyz4.gkme.model.AudioDevice
import com.zyz4.gkme.model.AudioDeviceType
import com.zyz4.gkme.model.ConnectionMode
import com.zyz4.gkme.model.DisplayMode
import com.zyz4.gkme.model.GyroOrientation
import com.zyz4.gkme.model.GyroSource
import com.zyz4.gkme.model.GyroSourceType
import com.zyz4.gkme.model.HapticEffect
import com.zyz4.gkme.model.LayoutPreset
import com.zyz4.gkme.model.TargetPlatform
import com.zyz4.gkme.model.VibrationDevice
import com.zyz4.gkme.model.VibrationDeviceType
import com.zyz4.gkme.model.VibrationType
import com.zyz4.gkme.service.ConnectionPhase
import com.zyz4.gkme.view.WrapContentGridView
import com.zyz4.gkme.view.PresetPreviewView
import kotlin.time.Duration.Companion.milliseconds

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
    a.findViewById<View>(R.id.gamepadPanel).visibility = View.INVISIBLE
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
    if (a.currentSettingsCategory == 2) {
        val gl = a.gamepadLayout
        val vto = gl.viewTreeObserver
        if (vto.isAlive) {
            vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    gl.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (gl.width > 0 && gl.height > 0) a.renderAppearancePreview()
                }
            })
        }
    }
}

internal fun MainActivity.selectSettingsCategory(index: Int) {
    val a = this
    a.audioPollingJob?.cancel()
    a.audioPollingJob = null
    a.currentSettingsCategory = index
    val pages = listOf(R.id.pageConnection, R.id.pagePresets, R.id.pageAppearance, R.id.pagePhysicalController, R.id.pageVibration, R.id.pageGyro, R.id.pageAudio, R.id.pageMisc, R.id.pageAbout)
    val buttons = listOf(
        R.id.btnCategoryConnection, R.id.btnCategoryPresets, R.id.btnCategoryAppearance, R.id.btnCategoryPhysicalController, R.id.btnCategoryVibration, R.id.btnCategoryGyro, R.id.btnCategoryAudio, R.id.btnCategoryMisc, R.id.btnCategoryAbout
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
                delay(1000.milliseconds)
            }
        }
    }
    if (index == 6) {
        a.audioPollingJob = a.lifecycleScope.launch {
            while (true) {
                delay(50.milliseconds)
                a.refreshAudioVCIndicators()
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
    a.findViewById<Button>(R.id.btnCategoryAudio).setOnClickListener { a.selectSettingsCategory(6) }
    a.findViewById<Button>(R.id.btnCategoryMisc).setOnClickListener { a.selectSettingsCategory(7) }
    a.findViewById<Button>(R.id.btnCategoryAbout).setOnClickListener { a.selectSettingsCategory(8) }

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
    a.findViewById<Button>(R.id.switchEditMode).setOnClickListener {
        val currentName = a.viewModel.settings.value.currentPresetName
        if (a.viewModel.isBuiltInPreset(currentName)) {
            a.showToast("内置布局禁止编辑")
            return@setOnClickListener
        }
        a.viewModel.updateEditMode(true)
        a.hideSettings()
        a.applyPreset(a.viewModel.currentPreset.value)
        a.gamepadLayout.enterEditMode()
        a.floatingEditor.presetGyroOrientation = a.gamepadLayout.currentGyroOrientation
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
    a.findViewById<Button>(R.id.btnPresetCopy).setOnClickListener {
        val current = a.viewModel.settings.value.currentPresetName
        a.showCopyPresetDialog(current)
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

    // ── Audio page ──
    a.findViewById<Spinner>(R.id.spinnerVoiceCoil).onItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val device = a.voiceCoilDeviceEntries.getOrNull(pos) ?: return
                if (a.viewModel.settings.value.voiceCoilDevice != device) {
                    a.viewModel.updateVoiceCoilDevice(device)
                }
                a.updateVoiceCoilSwapUI(device)
                a.audioPlaybackService.resumeIfStopped()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    a.findViewById<Switch>(R.id.switchSwapVoiceCoilMotors).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateSwapVoiceCoilMotors(isChecked)
        a.audioPlaybackService.resumeIfStopped()
    }
    a.setupControllerAudioSpinner(controllerAudioEntries()) { output ->
        a.viewModel.updateControllerAudioOutput(output)
        a.audioPlaybackService.resumeIfStopped()
    }

    // Audio VC indicator polling will be started in selectSettingsCategory when index == 6

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

    val targetChipIds = listOf(
        R.id.btnTargetWindows, R.id.btnTargetAndroid, R.id.btnTargetLinux,
        R.id.btnTargetAndroidGamepad, R.id.btnTargetUniversalKm
    )
    targetChipIds.forEachIndexed { idx, id ->
        a.findViewById<Button>(id).setOnClickListener {
            val platform = TargetPlatform.entries[idx]
            if (platform == a.viewModel.settings.value.targetPlatform) return@setOnClickListener
            CustomDialog.showConfirm(a, "切换目标平台",
                "将删除已保存的配对设备，是否继续？",
                positiveText = "确定", onPositive = {
                    a.selectChipGroup(targetChipIds, idx)
                    a.viewModel.switchTargetPlatform(platform)
                })
        }
    }

    // ── Vibration page ──
    a.findViewById<Spinner>(R.id.spinnerGameVibrationDevice).onItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val device = a.gameVibrationDeviceEntries.getOrNull(pos) ?: return
                a.viewModel.updateGameVibrationDevice(device)
                a.updateSwapMotorsUI(device)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    a.findViewById<Switch>(R.id.switchSwapMotors).setOnCheckedChangeListener { _, isChecked ->
        when (a.viewModel.settings.value.gameVibrationDevice.type) {
            VibrationDeviceType.PHONE -> a.viewModel.updateSwapPhoneMotors(isChecked)
            VibrationDeviceType.CONTROLLER -> a.viewModel.updateSwapControllerMotors(isChecked)
            VibrationDeviceType.NONE -> {}
        }
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

    // ── Gyro page ──
    a.findViewById<Spinner>(R.id.spinnerGyroSource).onItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val source = a.gyroSourceEntries.getOrNull(pos) ?: return
                if (source == a.currentGyroSource()) return
                a.applyGyroSource(source)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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

    // Controller gyro real-time display (read-only)
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

    a.findViewById<Spinner>(R.id.spinnerPhysicalController).apply {
        setOnTouchListener { _, _ ->
            a.inputControllerUserSelecting = true
            false
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!a.inputControllerUserSelecting) return
                a.inputControllerUserSelecting = false
                val index = a.inputControllerIndices.getOrNull(pos) ?: return
                if (a.viewModel.settings.value.inputControllerIndex != index) {
                    a.viewModel.updateInputControllerIndex(index)
                }
                a.physicalControllerHandler.inputControllerIndex = index
                a.syncPhysicalControllerState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                a.inputControllerUserSelecting = false
            }
        }
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
    a.viewModel.connectionManager.onControllerVibrationRequest = { controllerIndex, leftAmp, rightAmp ->
        a.physicalControllerHandler.setControllerMotorsVibration(controllerIndex, leftAmp, rightAmp)
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

internal fun MainActivity.phoneMotorCount(): Int {
    val a = this
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            val vm = a.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.vibratorIds?.size ?: 0
        } catch (_: Exception) { 0 }
    } else { 0 }
}

internal fun MainActivity.buildGameVibrationDeviceEntries(): List<VibrationDevice> {
    val a = this
    val entries = mutableListOf<VibrationDevice>()
    entries.add(VibrationDevice.PHONE)
    a.physicalControllerHandler.connectedControllers.value.forEachIndexed { index, _ ->
        entries.add(VibrationDevice.controller(index))
    }
    entries.add(VibrationDevice.NONE)
    return entries
}

internal fun MainActivity.updateGameVibrationDeviceAdapter(spinner: Spinner, entries: List<VibrationDevice>) {
    val a = this
    val names = entries.map { device ->
        when (device.type) {
            VibrationDeviceType.PHONE -> "手机马达"
            VibrationDeviceType.NONE -> "无"
            VibrationDeviceType.CONTROLLER -> {
                val info = a.physicalControllerHandler.connectedControllers.value.getOrNull(device.controllerIndex)
                info?.name?.takeIf { it.isNotBlank() } ?: "手柄${device.controllerIndex + 1}"
            }
        }
    }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
}

internal fun MainActivity.selectedDeviceMotorCount(device: VibrationDevice): Int {
    val a = this
    return when (device.type) {
        VibrationDeviceType.PHONE -> a.phoneMotorCount()
        VibrationDeviceType.CONTROLLER ->
            a.physicalControllerHandler.connectedControllers.value.getOrNull(device.controllerIndex)?.motorCount ?: 0
        VibrationDeviceType.NONE -> 0
    }
}

internal fun MainActivity.updateSwapMotorsUI(device: VibrationDevice) {
    val a = this
    val s = a.viewModel.settings.value
    val showSwap = a.selectedDeviceMotorCount(device) >= 2
    a.findViewById<View>(R.id.layoutSwapMotors).visibility = if (showSwap) View.VISIBLE else View.GONE
    val sw = a.findViewById<Switch>(R.id.switchSwapMotors)
    sw.isChecked = when (device.type) {
        VibrationDeviceType.PHONE -> s.swapPhoneMotors
        VibrationDeviceType.CONTROLLER -> s.swapControllerMotors
        VibrationDeviceType.NONE -> false
    }
}

internal fun MainActivity.syncGameVibrationUI() {
    val a = this
    if (!a.settingsInflated) return
    val s = a.viewModel.settings.value
    val entries = a.buildGameVibrationDeviceEntries()
    a.gameVibrationDeviceEntries = entries
    val spinner = a.findViewById<Spinner>(R.id.spinnerGameVibrationDevice)
    a.updateGameVibrationDeviceAdapter(spinner, entries)

    val connectedCount = a.physicalControllerHandler.connectedControllers.value.size
    var selected = s.gameVibrationDevice
    if (selected.type == VibrationDeviceType.CONTROLLER && selected.controllerIndex >= connectedCount) {
        selected = VibrationDevice.PHONE
    }
    val pos = entries.indexOf(selected).let { if (it >= 0) it else 0 }
    spinner.setSelection(pos)
    a.updateSwapMotorsUI(entries.getOrElse(pos) { VibrationDevice.PHONE })
}

internal fun MainActivity.buildInputControllerIndices(): List<Int> {
    val a = this
    val indices = mutableListOf<Int>()
    a.physicalControllerHandler.connectedControllers.value.forEachIndexed { index, _ -> indices.add(index) }
    indices.add(-1)
    return indices
}

internal fun MainActivity.updateInputControllerAdapter(spinner: Spinner, indices: List<Int>) {
    val a = this
    val names = indices.map { index ->
        if (index < 0) {
            "不使用手柄"
        } else {
            a.physicalControllerHandler.connectedControllers.value.getOrNull(index)?.name
                ?.takeIf { it.isNotBlank() } ?: "手柄${index + 1}"
        }
    }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
}

internal fun MainActivity.syncPhysicalControllerUI() {
    val a = this
    if (!a.settingsInflated) return
    val indices = a.buildInputControllerIndices()
    a.inputControllerIndices = indices
    val controllerCount = indices.size - 1
    val setting = a.viewModel.settings.value.inputControllerIndex
    var effective = setting
    if (effective !in 0 until controllerCount) {
        effective = if (controllerCount > 0) 0 else -1
        if (controllerCount > 0 && setting != effective) {
            a.viewModel.updateInputControllerIndex(effective)
        }
    }
    val spinner = a.findViewById<Spinner>(R.id.spinnerPhysicalController)
    val pos = indices.indexOf(effective).let { if (it >= 0) it else indices.size - 1 }
    a.updateInputControllerAdapter(spinner, indices)
    spinner.setSelection(pos)
}

internal fun MainActivity.currentGyroSource(): GyroSource {
    val a = this
    val s = a.viewModel.settings.value
    val connected = a.physicalControllerHandler.isConnected.value
    val useController = if (connected) s.controllerGyroEnabledConnected else s.controllerGyroEnabled
    return when {
        useController -> GyroSource.controller(s.gyroControllerIndex)
        s.gyroEnabled -> GyroSource.PHONE
        else -> GyroSource.NONE
    }
}

internal fun MainActivity.applyGyroSource(source: GyroSource) {
    val a = this
    when (source.type) {
        GyroSourceType.CONTROLLER -> {
            a.physicalControllerHandler.gyroControllerIndex = source.controllerIndex
            a.viewModel.updateGyroEnabled(true)
            a.viewModel.updateControllerGyroEnabled(true)
            a.viewModel.updateControllerGyroEnabledConnected(true)
            a.viewModel.updateGyroControllerIndex(source.controllerIndex)
        }
        GyroSourceType.PHONE -> {
            a.viewModel.updateGyroEnabled(true)
            a.viewModel.updateControllerGyroEnabled(false)
            a.viewModel.updateControllerGyroEnabledConnected(false)
        }
        GyroSourceType.NONE -> {
            a.viewModel.updateGyroEnabled(false)
            a.viewModel.updateControllerGyroEnabled(false)
            a.viewModel.updateControllerGyroEnabledConnected(false)
        }
    }
    a.physicalControllerHandler.onControllerGyroSettingChanged(source.type == GyroSourceType.CONTROLLER)
    a.updateGyroSourceVisibility(source)
}

internal fun MainActivity.gyroSourceDisplayName(source: GyroSource): String {
    val controllers = physicalControllerHandler.connectedControllers.value
    return when (source.type) {
        GyroSourceType.CONTROLLER ->
            controllers.getOrNull(source.controllerIndex)?.name?.takeIf { it.isNotBlank() }
                ?: "手柄${source.controllerIndex + 1}"
        GyroSourceType.PHONE -> "手机陀螺仪"
        GyroSourceType.NONE -> "不使用体感"
    }
}

internal fun MainActivity.updateGyroSourceVisibility(source: GyroSource) {
    val a = this
    val isPhone = source.type == GyroSourceType.PHONE
    val isController = source.type == GyroSourceType.CONTROLLER
    a.findViewById<View>(R.id.layoutGyroOrientation).visibility =
        if (isPhone) View.VISIBLE else View.GONE
    a.findViewById<TextView>(R.id.tvControllerGyroNote).visibility =
        if (isController) View.VISIBLE else View.GONE

    // Column titles follow the actual selected source (controller name, not a fixed label).
    val ctrlSource = if (isController) source
        else GyroSource.controller(a.viewModel.settings.value.gyroControllerIndex)
    a.findViewById<TextView>(R.id.tvPhoneGyroTitle).text = a.gyroSourceDisplayName(GyroSource.PHONE)
    a.findViewById<TextView>(R.id.tvControllerGyroTitle).text = a.gyroSourceDisplayName(ctrlSource)

    val phoneCol = a.findViewById<View>(R.id.layoutPhoneGyroDisplay)
    val ctrlCol = a.findViewById<View>(R.id.layoutControllerGyroDisplay)
    phoneCol.visibility = if (isPhone) View.VISIBLE else View.GONE
    ctrlCol.visibility = if (isController) View.VISIBLE else View.GONE
    (phoneCol.layoutParams as? LinearLayout.LayoutParams)?.weight = if (isPhone && !isController) 2f else 1f
    (ctrlCol.layoutParams as? LinearLayout.LayoutParams)?.weight = if (isController && !isPhone) 2f else 1f
}

internal fun MainActivity.buildGyroSourceEntries(): List<GyroSource> {
    val a = this
    val entries = mutableListOf<GyroSource>()
    a.physicalControllerHandler.connectedControllers.value.forEachIndexed { index, _ ->
        entries.add(GyroSource.controller(index))
    }
    entries.add(GyroSource.PHONE)
    entries.add(GyroSource.NONE)
    return entries
}

internal fun MainActivity.updateGyroSourceAdapter(spinner: Spinner, entries: List<GyroSource>) {
    val a = this
    val names = entries.map { a.gyroSourceDisplayName(it) }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
}

internal fun MainActivity.syncGyroSourceUI() {
    val a = this
    if (!a.settingsInflated) return
    val entries = a.buildGyroSourceEntries()
    a.gyroSourceEntries = entries
    val spinner = a.findViewById<Spinner>(R.id.spinnerGyroSource)
    a.updateGyroSourceAdapter(spinner, entries)
    var source = a.currentGyroSource()
    if (source.type == GyroSourceType.CONTROLLER &&
        source.controllerIndex >= a.physicalControllerHandler.connectedControllers.value.size
    ) {
        source = GyroSource.PHONE
    }
    val pos = entries.indexOf(source).let { if (it >= 0) it else entries.size - 1 }
    spinner.setSelection(pos)
    a.updateGyroSourceVisibility(entries.getOrElse(pos) { GyroSource.PHONE })
}

internal fun MainActivity.controllerAudioEntries(): List<AudioOutput> =
    listOf(AudioOutput.ALL_SPEAKERS, AudioOutput.NONE)

internal fun MainActivity.buildVoiceCoilDeviceEntries(): List<AudioDevice> {
    val a = this
    val entries = mutableListOf<AudioDevice>()
    a.physicalControllerHandler.connectedControllers.value.forEachIndexed { index, _ ->
        entries.add(AudioDevice.controller(index))
    }
    entries.add(AudioDevice.PHONE_MOTOR)
    entries.add(AudioDevice.PHONE_SPEAKER)
    entries.add(AudioDevice.NONE)
    return entries
}

internal fun MainActivity.updateVoiceCoilDeviceAdapter(spinner: Spinner, entries: List<AudioDevice>) {
    val a = this
    val controllers = a.physicalControllerHandler.connectedControllers.value
    val names = entries.map { device ->
        when (device.type) {
            AudioDeviceType.CONTROLLER ->
                controllers.getOrNull(device.controllerIndex)?.name?.takeIf { it.isNotBlank() }
                    ?: "手柄${device.controllerIndex + 1}"
            AudioDeviceType.PHONE_MOTOR -> "手机马达"
            AudioDeviceType.PHONE_SPEAKER -> "手机扬声器"
            AudioDeviceType.NONE -> "无"
        }
    }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
}

internal fun MainActivity.selectedVoiceCoilMotorCount(device: AudioDevice): Int {
    val a = this
    return when (device.type) {
        AudioDeviceType.PHONE_MOTOR -> a.phoneMotorCount()
        AudioDeviceType.CONTROLLER ->
            a.physicalControllerHandler.connectedControllers.value.getOrNull(device.controllerIndex)?.motorCount ?: 0
        else -> 0
    }
}

internal fun MainActivity.updateVoiceCoilSwapUI(device: AudioDevice) {
    val a = this
    val showSwap = a.selectedVoiceCoilMotorCount(device) >= 2
    a.findViewById<View>(R.id.layoutSwapVoiceCoilMotors).visibility = if (showSwap) View.VISIBLE else View.GONE
    a.findViewById<Switch>(R.id.switchSwapVoiceCoilMotors).isChecked = a.viewModel.settings.value.swapVoiceCoilMotors
}

internal fun MainActivity.syncVoiceCoilUI() {
    val a = this
    if (!a.settingsInflated) return
    val entries = a.buildVoiceCoilDeviceEntries()
    a.voiceCoilDeviceEntries = entries
    val spinner = a.findViewById<Spinner>(R.id.spinnerVoiceCoil)
    a.updateVoiceCoilDeviceAdapter(spinner, entries)
    val connectedCount = a.physicalControllerHandler.connectedControllers.value.size
    var selected = a.viewModel.settings.value.voiceCoilDevice
    if (selected.type == AudioDeviceType.CONTROLLER && selected.controllerIndex >= connectedCount) {
        selected = AudioDevice.PHONE_SPEAKER
    }
    val pos = entries.indexOf(selected).let { if (it >= 0) it else entries.size - 1 }
    spinner.setSelection(pos)
    a.updateVoiceCoilSwapUI(entries.getOrElse(pos) { AudioDevice.PHONE_SPEAKER })
}

internal fun MainActivity.setupControllerAudioSpinner(entries: List<AudioOutput>, onChanged: (AudioOutput) -> Unit) {
    val a = this
    val spinner = a.findViewById<Spinner>(R.id.spinnerControllerAudio)
    a.audioControllerOutputEntries = entries
    a.updateControllerAudioAdapter(spinner)
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            if (pos < a.audioControllerOutputEntries.size) onChanged(a.audioControllerOutputEntries[pos])
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

internal fun MainActivity.updateControllerAudioAdapter(spinner: Spinner) {
    val a = this
    val names = a.audioControllerOutputEntries.map {
        if (it == AudioOutput.ALL_SPEAKERS) "手机扬声器" else it.displayName
    }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.refreshAudioVCIndicators() {
    val a = this
    val info = a.audioPlaybackService.trackInfo.value
    a.findViewById<ProgressBar>(R.id.progressLeftVC)?.progress = info.leftVoiceCoilAmplitude
    a.findViewById<ProgressBar>(R.id.progressRightVC)?.progress = info.rightVoiceCoilAmplitude
    a.findViewById<TextView>(R.id.tvLeftVCValue)?.text = info.leftVoiceCoilAmplitude.toString()
    a.findViewById<TextView>(R.id.tvRightVCValue)?.text = info.rightVoiceCoilAmplitude.toString()
    val controllerAmp = info.controllerAudioAmplitude
    a.findViewById<ProgressBar>(R.id.progressControllerAudio)?.progress = controllerAmp
    a.findViewById<TextView>(R.id.tvControllerAudioValue)?.text = controllerAmp.toString()
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

internal fun MainActivity.testHaptic(isPress: Boolean) {
    val a = this
    val s = a.viewModel.settings.value
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
    a.findViewById<TextView>(R.id.tvAppName).text = "GKME"
    a.findViewById<TextView>(R.id.tvAppVersion).text = "版本 ${packageInfo.versionName}"
    a.findViewById<TextView>(R.id.tvAppDescription).text = "作者：4zyz4  软件Q群：639317971\n\n" +
            "开源地址：https://github.com/4zyz4/gkme\n" +
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

    // Polling Rate Spinner
    val pollingRateOptions = listOf(30, 45, 60, 90, 100, 120, 200, 250, 300, 500, 750, 1000)
    val pollingRateNames = pollingRateOptions.map { "$it Hz" }.toTypedArray()
    val pollingRateAdapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, pollingRateNames)
    pollingRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    a.findViewById<Spinner>(R.id.spinnerPollingRate).adapter = pollingRateAdapter
    a.findViewById<Spinner>(R.id.spinnerPollingRate).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            a.viewModel.updatePollingRate(pollingRateOptions[position])
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
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

internal fun MainActivity.showCopyPresetDialog(sourceName: String) {
    val candidate = sourceName.replace(Regex("^复制_"), "")
    CustomDialog.showInput(this, "复制布局", hint = "输入新预设名称", prefill = "复制_$candidate",
        positiveText = "创建", onPositive = { name ->
            if (name.isNotEmpty()) {
                val preset = viewModel.currentPreset.value.copy()
                viewModel.savePreset(name, preset)
                applyPreset(preset)
                refreshPresetList()
                showToast("已复制为「$name」")
            }
        })
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.refreshPresetList() {
    val a = this
    val gridView = a.findViewById<WrapContentGridView>(R.id.gridPresets) ?: return
    val infos = a.viewModel.presetInfos.value
    val current = a.viewModel.settings.value.currentPresetName
    val isBuiltIn = a.viewModel.isBuiltInPreset(current)
    a.findViewById<TextView>(R.id.tvCurrentPreset).text = "当前预设: $current"
    a.findViewById<Button>(R.id.btnPresetCopy).visibility = View.VISIBLE
    a.findViewById<Button>(R.id.btnPresetRename).visibility = if (isBuiltIn) View.GONE else View.VISIBLE
    a.findViewById<Button>(R.id.btnPresetDelete).visibility = if (isBuiltIn) View.GONE else View.VISIBLE
    a.findViewById<Button>(R.id.switchEditMode).visibility = if (isBuiltIn) View.GONE else View.VISIBLE
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
    private val infos: List<GkViewModel.PresetInfo>,
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
    a.findViewById<Button>(R.id.switchEditMode).text = "编辑"

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
    a.selectChipGroup(listOf(
        R.id.btnTargetWindows, R.id.btnTargetAndroid, R.id.btnTargetLinux,
        R.id.btnTargetAndroidGamepad, R.id.btnTargetUniversalKm
    ), TargetPlatform.entries.indexOf(s.targetPlatform).coerceAtLeast(0))
    val pollingRateOptions = listOf(30, 45, 60, 90, 100, 120, 200, 250, 300, 500, 750, 1000)
    val pollingRateIndex = pollingRateOptions.indexOf(s.pollingRate)
    if (pollingRateIndex >= 0) {
        a.findViewById<Spinner>(R.id.spinnerPollingRate).setSelection(pollingRateIndex)
    }
    a.updateVibrationUI()
    a.syncGameVibrationUI()
    a.updateSettingsVisibility(s.connectionMode)

    a.findViewById<Switch>(R.id.switchAutoStart).isChecked = s.autoStartEnabled
    a.syncGyroSourceUI()
    val effectiveOrientation = a.viewModel.currentPreset.value.gyroOrientation ?: s.gyroOrientation
    a.selectChipGroup(listOf(R.id.btnGyroOriLandscape, R.id.btnGyroOriPortrait, R.id.btnGyroOriPortraitInverted),
        GyroOrientation.entries.indexOf(effectiveOrientation).coerceAtLeast(0))
    a.findViewById<TextView>(R.id.tvGyroSensitivityX).text = "X: 0.00"
    a.findViewById<TextView>(R.id.tvGyroSensitivityY).text = "Y: 0.00"
    a.findViewById<TextView>(R.id.tvGyroSensitivityZ).text = "Z: 0.00"

    a.updateGyroChipsLockState(a.viewModel.currentPreset.value.gyroOrientation)

    @Suppress("DEPRECATION")
    val inverted = a.windowManager.defaultDisplay.getRotation() == android.view.Surface.ROTATION_270
    a.updateGyroLandscapeInvertedNote(inverted)

    a.findViewById<Switch>(R.id.switchKeepScreenOn).isChecked = s.keepScreenOn
    a.findViewById<Switch>(R.id.switchNonLinearTriggerAdaptation).isChecked = s.nonLinearTriggerAdaptation
    a.physicalControllerHandler.nonLinearTriggerAdaptation = s.nonLinearTriggerAdaptation
    a.updateVolumeMappingLabels()

    a.syncAppearanceUI()
    a.applyAppearanceIfChanged(s)

    a.findViewById<TextView>(R.id.tvControllerGyroX).text = "X: 0.00"
    a.findViewById<TextView>(R.id.tvControllerGyroY).text = "Y: 0.00"
    a.findViewById<TextView>(R.id.tvControllerGyroZ).text = "Z: 0.00"
    a.findViewById<SeekBar>(R.id.seekControllerGyroX).progress = 0
    a.findViewById<SeekBar>(R.id.seekControllerGyroY).progress = 0
    a.findViewById<SeekBar>(R.id.seekControllerGyroZ).progress = 0

    a.syncPhysicalControllerUI()

    a.refreshPresetList()
    a.syncAudioUI()
}

@SuppressLint("SetTextI18n")
internal fun MainActivity.syncAudioUI() {
    val a = this
    val s = a.viewModel.settings.value

    a.syncVoiceCoilUI()
    a.audioControllerOutputEntries = a.controllerAudioEntries()
    a.updateControllerAudioAdapter(a.findViewById(R.id.spinnerControllerAudio))
    val ctrlPos = a.audioControllerOutputEntries.indexOf(s.controllerAudioOutput)
        .let { if (it >= 0) it else 0 }
    a.findViewById<Spinner>(R.id.spinnerControllerAudio).setSelection(ctrlPos)
}
