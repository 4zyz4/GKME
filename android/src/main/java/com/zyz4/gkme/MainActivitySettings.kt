package com.zyz4.gkme

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
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
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.zyz4.gkme.haptic.RichTapFrequency
import kotlin.math.roundToInt
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
import com.zyz4.gkme.model.AudioDevice
import com.zyz4.gkme.model.AudioDeviceType
import com.zyz4.gkme.model.AdaptiveTriggerDevice
import com.zyz4.gkme.model.AdaptiveTriggerTargetType
import com.zyz4.gkme.model.ConnectionMode
import com.zyz4.gkme.model.ControlType
import com.zyz4.gkme.model.VirtualGamepadType
import com.zyz4.gkme.controlled.ControlledActivity
import com.zyz4.gkme.controlled.GamepadInjector
import com.zyz4.gkme.controlled.HapticInjector
import com.zyz4.gkme.controlled.ShizukuServiceBinding
import com.zyz4.gkme.haptic.PhoneHdHaptics
import com.zyz4.gkme.model.DisplayMode
import com.zyz4.gkme.model.GyroOrientation
import com.zyz4.gkme.model.GyroSource
import com.zyz4.gkme.model.GyroSourceType
import com.zyz4.gkme.model.gameVibrationDeviceFor
import com.zyz4.gkme.model.voiceCoilDeviceFor
import com.zyz4.gkme.model.adaptiveTriggerDeviceFor
import com.zyz4.gkme.model.gyroSourceFor
import com.zyz4.gkme.model.gyroControllerIndexFor
import com.zyz4.gkme.model.HapticEffect
import com.zyz4.gkme.model.LayoutPreset
import com.zyz4.gkme.model.TargetPlatform
import com.zyz4.gkme.model.VibrationDevice
import com.zyz4.gkme.model.VibrationDeviceType
import com.zyz4.gkme.model.VibrationType
import com.zyz4.gkme.input.ControllerInfo
import com.zyz4.gkme.input.SdlAudio
import com.zyz4.gkme.service.ConnectionPhase
import com.zyz4.gkme.view.CircularRevealLayout
import com.zyz4.gkme.view.GamepadLayout
import com.zyz4.gkme.view.WrapContentGridView
import com.zyz4.gkme.view.PresetPreviewView
import com.zyz4.gkme.view.SidebarItemDrawable
import kotlin.time.Duration.Companion.milliseconds

// ── Settings ─────────────────────────────────────────────

private const val SETTINGS_OPEN_DURATION = 300L
private const val SETTINGS_CLOSE_DURATION = 300L
internal const val CATEGORY_SWITCH_DURATION = 150L
internal const val PAGE_SWITCH_OFFSET_DP = 24f

private val SETTINGS_PAGES = listOf(
    R.id.pageConnection, R.id.pagePresets, R.id.pageAppearance, R.id.pagePhysicalController,
    R.id.pageVibration, R.id.pageGyro, R.id.pageAudio, R.id.pageMisc, R.id.pageAbout
)

private val SETTINGS_CATEGORY_BUTTONS = listOf(
    R.id.btnCategoryConnection, R.id.btnCategoryPresets, R.id.btnCategoryAppearance,
    R.id.btnCategoryPhysicalController, R.id.btnCategoryVibration, R.id.btnCategoryGyro,
    R.id.btnCategoryAudio, R.id.btnCategoryMisc, R.id.btnCategoryAbout
)

internal fun easeOutQuint(): Interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

internal fun MainActivity.settingsButtonView(): View? {
    val a = this
    for (i in 0 until a.gamepadLayout.childCount) {
        val child = a.gamepadLayout.getChildAt(i)
        if (child.tag == GamepadLayout.SETTINGS_BUTTON_ID) return child
    }
    return null
}

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
    if (a.isScreenOff) return
    if (a.gamepadLayout.isEditModeActive()) return
    a.ensureSettingsInflated()
    a.inSettings = true
    val gamepad = a.findViewById<View>(R.id.gamepadPanel)
    val panel = a.findViewById<View>(R.id.settingsPanel)
    a.settingsRevealAnimator?.cancel()
    gamepad.visibility = View.VISIBLE
    panel.animate().cancel()
    panel.translationX = 0f
    panel.alpha = 1f
    panel.visibility = View.VISIBLE
    a.selectSettingsCategory(0, animate = false)
    a.syncSettingsUI()
    a.animateSettingsOpen(panel, gamepad)
}

internal fun MainActivity.hideSettings() {
    val a = this
    if (!a.inSettings) return
    a.inSettings = false
    a.vibrationPollingJob?.cancel()
    a.stopShizukuPolling()
    val gamepad = a.findViewById<View>(R.id.gamepadPanel)
    val panel = a.findViewById<View>(R.id.settingsPanel)
    a.settingsRevealAnimator?.end()
    a.settingsRevealAnimator = null
    gamepad.visibility = View.VISIBLE
    panel.animate().cancel()
    panel.translationX = 0f
    panel.alpha = 1f
    panel.visibility = View.VISIBLE
    a.animateSettingsClose(panel) {
        panel.visibility = View.GONE
    }
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

/** Center of the reveal, in the panel's coordinate space, and the radius needed to cover it. */
private fun MainActivity.settingsRevealGeometry(panel: View): Triple<Float, Float, Float> {
    val panelLoc = IntArray(2)
    panel.getLocationInWindow(panelLoc)
    var cx = panel.width / 2f
    var cy = panel.height / 2f
    val button = settingsButtonView()
    if (button != null && button.width > 0 && button.height > 0) {
        val btnLoc = IntArray(2)
        button.getLocationInWindow(btnLoc)
        cx = btnLoc[0] - panelLoc[0] + button.width / 2f
        cy = btnLoc[1] - panelLoc[1] + button.height / 2f
    }
    val maxRadius = kotlin.math.hypot(
        maxOf(cx, panel.width - cx),
        maxOf(cy, panel.height - cy)
    )
    return Triple(cx, cy, maxRadius)
}

/** Expands the settings panel from the settings button as a circular reveal. */
internal fun MainActivity.animateSettingsOpen(panel: View, gamepad: View) {
    val a = this
    val global = panel.viewTreeObserver
    if (!global.isAlive) {
        gamepad.visibility = View.INVISIBLE
        return
    }
    global.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (global.isAlive) global.removeOnPreDrawListener(this)
            if (!a.inSettings || panel.width == 0 || panel.height == 0) {
                gamepad.visibility = View.INVISIBLE
                return true
            }
            val (cx, cy, maxRadius) = a.settingsRevealGeometry(panel)
            val reveal = ViewAnimationUtils.createCircularReveal(
                panel, cx.toInt(), cy.toInt(), 0f, maxRadius
            )
            reveal.duration = SETTINGS_OPEN_DURATION
            reveal.interpolator = easeOutQuint()
            reveal.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (a.settingsRevealAnimator === animation) a.settingsRevealAnimator = null
                    if (a.inSettings) gamepad.visibility = View.INVISIBLE
                }
            })
            a.settingsRevealAnimator = reveal
            reveal.start()
            return true
        }
    })
}

/** Erases the settings panel outward from the settings button as a growing circular hole. */
internal fun MainActivity.animateSettingsClose(panel: View, onEnd: () -> Unit) {
    val a = this
    if (panel !is CircularRevealLayout || !panel.isAttachedToWindow ||
        panel.width == 0 || panel.height == 0
    ) {
        if (panel is CircularRevealLayout) panel.clearHole()
        onEnd()
        return
    }
    val (cx, cy, maxRadius) = a.settingsRevealGeometry(panel)
    val animator = ValueAnimator.ofFloat(0f, maxRadius).apply {
        addUpdateListener { anim -> panel.setHole(cx, cy, anim.animatedValue as Float) }
    }
    animator.duration = SETTINGS_CLOSE_DURATION
    animator.interpolator = easeOutQuint()
    animator.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            if (a.settingsRevealAnimator === animation) a.settingsRevealAnimator = null
            panel.clearHole()
            onEnd()
        }
    })
    a.settingsRevealAnimator = animator
    animator.start()
}

internal fun MainActivity.selectSettingsCategory(index: Int, animate: Boolean = true) {
    val a = this
    a.audioPollingJob?.cancel()
    a.audioPollingJob = null
    val previous = a.currentSettingsCategory
    a.currentSettingsCategory = index
    val pages = SETTINGS_PAGES.map { a.findViewById<View>(it) }
    val buttons = SETTINGS_CATEGORY_BUTTONS.map { a.findViewById<Button>(it) }
    val newPage = pages[index]
    if (animate && previous != index) {
        a.animatePageSwitch(pages, pages.getOrNull(previous), newPage)
    } else {
        pages.forEach { p ->
            p.animate().cancel()
            p.translationY = 0f
            p.alpha = 1f
            p.visibility = if (p === newPage) View.VISIBLE else View.GONE
        }
    }
    buttons.forEachIndexed { i, btn ->
        btn.isSelected = i == index
        a.animateSidebarItem(btn, i == index, animate && previous != index)
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
                delay(500.milliseconds)
                if (a.settingsInflated) a.updateHdVibrationUI()
            }
        }
    }
    if (index == 6) {
        // Re-read the SDL sound device list so hotplugged outputs show up.
        a.syncVoiceCoilUI()
        a.syncControllerAudioUI()
        a.audioPollingJob = a.lifecycleScope.launch {
            while (true) {
                delay(50.milliseconds)
                a.refreshAudioVCIndicators()
            }
        }
    }
}

/** Slides the old page up while fading it out, and the new page up while fading it in. */
private fun MainActivity.animatePageSwitch(pages: List<View>, oldPage: View?, newPage: View) {
    val a = this
    val offset = PAGE_SWITCH_OFFSET_DP * a.resources.displayMetrics.density
    pages.forEach { it.animate().cancel() }
    pages.forEach { p ->
        if (p !== oldPage && p !== newPage) {
            p.visibility = View.GONE
            p.translationY = 0f
            p.alpha = 1f
        }
    }
    if (oldPage != null && oldPage !== newPage && oldPage.visibility == View.VISIBLE) {
        oldPage.animate()
            .translationY(-offset)
            .alpha(0f)
            .setDuration(CATEGORY_SWITCH_DURATION)
            .setInterpolator(easeOutQuint())
            .withEndAction {
                oldPage.visibility = View.GONE
                oldPage.translationY = 0f
                oldPage.alpha = 1f
            }
            .start()
    }
    newPage.visibility = View.VISIBLE
    newPage.translationY = offset
    newPage.alpha = 0f
    newPage.animate()
        .translationY(0f)
        .alpha(1f)
        .setDuration(CATEGORY_SWITCH_DURATION)
        .setInterpolator(easeOutQuint())
        .start()
}

/** Wipes the sidebar item highlight from left to right while cross-fading its text color. */
private fun MainActivity.animateSidebarItem(button: Button, selected: Boolean, animate: Boolean) {
    val a = this
    val drawable = a.sidebarItemDrawables[button.id] ?: return
    val targetFill = if (selected) 1f else 0f
    val targetColor = if (selected) 0xFFFFFFFF.toInt() else 0xFF888888.toInt()
    a.sidebarAnimators.remove(button.id)?.cancel()
    if (!animate) {
        drawable.fill = targetFill
        drawable.invalidateSelf()
        button.setTextColor(targetColor)
        return
    }
    val fillAnimator = ValueAnimator.ofFloat(drawable.fill, targetFill).apply {
        addUpdateListener { anim ->
            drawable.fill = anim.animatedValue as Float
            drawable.invalidateSelf()
        }
    }
    val colorAnimator = ValueAnimator.ofArgb(button.currentTextColor, targetColor).apply {
        addUpdateListener { anim -> button.setTextColor(anim.animatedValue as Int) }
    }
    val set = AnimatorSet()
    set.playTogether(fillAnimator, colorAnimator)
    set.duration = CATEGORY_SWITCH_DURATION
    set.interpolator = easeOutQuint()
    set.start()
    a.sidebarAnimators[button.id] = set
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

    // Sliding sidebar highlight
    SETTINGS_CATEGORY_BUTTONS.forEach { id ->
        a.sidebarItemDrawables[id] = SidebarItemDrawable()
        a.findViewById<Button>(id).background = a.sidebarItemDrawables[id]
    }

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
    a.findViewById<Spinner>(R.id.spinnerVoiceCoil).apply {
        setOnTouchListener { _, _ ->
            a.voiceCoilUserSelecting = true
            false
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!a.voiceCoilUserSelecting) return
                a.voiceCoilUserSelecting = false
                val device = a.voiceCoilDeviceEntries.getOrNull(pos) ?: return
                if (a.effectiveVoiceCoilDevice() != device) {
                    a.viewModel.updateVoiceCoilDevice(device)
                }
                a.updateVoiceCoilSwapUI(device)
                a.audioPlaybackService.resumeIfStopped()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                a.voiceCoilUserSelecting = false
            }
        }
    }
    a.findViewById<Switch>(R.id.switchSwapVoiceCoilMotors).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateSwapVoiceCoilMotors(isChecked)
        a.audioPlaybackService.resumeIfStopped()
    }
    a.setupControllerAudioSpinner(buildControllerAudioDeviceEntries()) { device ->
        a.viewModel.updateControllerAudioDevice(device)
        a.audioPlaybackService.resumeIfStopped()
    }

    // Audio VC indicator polling will be started in selectSettingsCategory when index == 6

    val connectionChipIds = listOf(R.id.btnConnWifi, R.id.btnConnBluetooth, R.id.btnConnUsb, R.id.btnConnLocal)
    listOf(R.id.btnConnWifi to 0, R.id.btnConnBluetooth to 1, R.id.btnConnUsb to 2, R.id.btnConnLocal to 3).forEach { (id, idx) ->
        a.findViewById<Button>(id).setOnClickListener {
            if (a.viewModel.connectionState.value.phase != ConnectionPhase.IDLE) {
                a.showToast("请先停止服务")
                return@setOnClickListener
            }
            a.selectChipGroup(connectionChipIds, idx)
            val mode = ConnectionMode.entries[idx]
            a.viewModel.updateConnectionMode(mode)
            a.updateSettingsVisibility(mode)
        }
    }

    val controlTypeChipIds = listOf(R.id.btnControlTypeController, R.id.btnControlTypeControlled)
    listOf(R.id.btnControlTypeController to 0, R.id.btnControlTypeControlled to 1).forEach { (id, idx) ->
        a.findViewById<Button>(id).setOnClickListener {
            if (a.viewModel.connectionState.value.phase != ConnectionPhase.IDLE) {
                a.showToast("请先停止服务")
                return@setOnClickListener
            }
            a.selectChipGroup(controlTypeChipIds, idx)
            a.viewModel.updateControlType(ControlType.entries[idx])
        }
    }

    val targetChipIds = listOf(
        R.id.btnTargetWindows, R.id.btnTargetAndroid, R.id.btnTargetLinux,
        R.id.btnTargetAndroidGamepad, R.id.btnTargetUniversalKm, R.id.btnTargetWindowsGamepad
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

    val vgTypeChipIds = listOf(R.id.btnVgXbox, R.id.btnVgDs4, R.id.btnVgDualsense, R.id.btnVgSwitch)
    vgTypeChipIds.forEachIndexed { idx, id ->
        a.findViewById<Button>(id).setOnClickListener {
            a.selectChipGroup(vgTypeChipIds, idx)
            a.viewModel.updateVirtualGamepadType(VirtualGamepadType.entries[idx])
        }
    }

    // ── Vibration page ──
    a.findViewById<Spinner>(R.id.spinnerGameVibrationDevice).apply {
        setOnTouchListener { _, _ ->
            a.gameVibrationUserSelecting = true
            false
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!a.gameVibrationUserSelecting) return
                a.gameVibrationUserSelecting = false
                val device = a.gameVibrationDeviceEntries.getOrNull(pos) ?: return
                if (a.effectiveGameVibrationDevice() != device) {
                    a.viewModel.updateGameVibrationDevice(device)
                }
                a.updateSwapMotorsUI(device)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                a.gameVibrationUserSelecting = false
            }
        }
    }
    a.findViewById<Switch>(R.id.switchSwapMotors).setOnCheckedChangeListener { _, isChecked ->
        when (a.effectiveGameVibrationDevice().type) {
            VibrationDeviceType.PHONE -> a.viewModel.updateSwapPhoneMotors(isChecked)
            VibrationDeviceType.CONTROLLER -> a.viewModel.updateSwapControllerMotors(isChecked)
            VibrationDeviceType.NONE -> {}
        }
    }

    a.setupHdVibrationEntry()

    a.findViewById<Spinner>(R.id.spinnerAdaptiveTriggerDevice).apply {
        setOnTouchListener { _, _ ->
            a.adaptiveTriggerUserSelecting = true
            false
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!a.adaptiveTriggerUserSelecting) return
                a.adaptiveTriggerUserSelecting = false
                val device = a.adaptiveTriggerDeviceEntries.getOrNull(pos) ?: return
                if (a.effectiveAdaptiveTriggerDevice() != device) {
                    a.viewModel.updateAdaptiveTriggerDevice(device)
                }
                a.updateSwapAdaptiveTriggersUI(device)
                a.applyAdaptiveTriggerSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                a.adaptiveTriggerUserSelecting = false
            }
        }
    }
    a.findViewById<Switch>(R.id.switchSwapAdaptiveTriggers).setOnCheckedChangeListener { _, isChecked ->
        a.viewModel.updateSwapAdaptiveTriggers(isChecked)
        a.applyAdaptiveTriggerSettings()
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

    a.findViewById<SeekBar>(R.id.seekPressFrequency).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.viewModel.updateVibrationPressFrequency(progress)
                a.findViewById<TextView>(R.id.tvPressFrequency).text = frequencyLabel(progress)
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
    a.findViewById<SeekBar>(R.id.seekReleaseFrequency).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.viewModel.updateVibrationReleaseFrequency(progress)
                a.findViewById<TextView>(R.id.tvReleaseFrequency).text = frequencyLabel(progress)
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
    a.findViewById<Spinner>(R.id.spinnerGyroSource).apply {
        setOnTouchListener { _, _ ->
            a.gyroSourceUserSelecting = true
            false
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!a.gyroSourceUserSelecting) return
                a.gyroSourceUserSelecting = false
                val source = a.gyroSourceEntries.getOrNull(pos) ?: return
                if (source == a.currentGyroSource()) return
                a.applyGyroSource(source)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                a.gyroSourceUserSelecting = false
            }
        }
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
    a.findViewById<Spinner>(R.id.spinnerControllerDriver).apply {
        setOnTouchListener { _, _ ->
            a.controllerDriverUserSelecting = true
            false
        }
        val names = a.controllerDriverEntries.map { it.displayName }.toTypedArray()
        adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!a.controllerDriverUserSelecting) return
                a.controllerDriverUserSelecting = false
                val driver = a.controllerDriverEntries.getOrNull(pos) ?: return
                if (driver == a.viewModel.settings.value.controllerDriver) return
                a.viewModel.updateControllerDriver(driver)
                a.physicalControllerHandler.setDriver(driver)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                a.controllerDriverUserSelecting = false
            }
        }
    }

    a.findViewById<Button>(R.id.btnRedetectController).setOnClickListener {
        a.physicalControllerHandler.reconnect()
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
    a.viewModel.connectionManager.onVoiceCoilMotorOutputUpdate = { leftAmp, rightAmp ->
        a.physicalControllerHandler.setVoiceCoilMotorOutput(leftAmp, rightAmp)
    }
    a.viewModel.connectionManager.onTriggerEffectsRequest = { leftEffect: ByteArray?, rightEffect: ByteArray? ->
        a.adaptiveTriggerHandler.onEffects(leftEffect, rightEffect)
    }
    a.viewModel.connectionManager.onTriggerRumbleRequest = { left: Int, right: Int ->
        a.adaptiveTriggerHandler.onTriggerRumble(left, right)
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
    val entries = a.buildGameVibrationDeviceEntries()
    a.gameVibrationDeviceEntries = entries
    val spinner = a.findViewById<Spinner>(R.id.spinnerGameVibrationDevice)
    a.updateGameVibrationDeviceAdapter(spinner, entries)

    val connectedCount = a.physicalControllerHandler.connectedControllers.value.size
    var selected = a.effectiveGameVibrationDevice()
    if (selected.type == VibrationDeviceType.CONTROLLER && selected.controllerIndex >= connectedCount) {
        selected = VibrationDevice.PHONE
    }
    val pos = entries.indexOf(selected).let { if (it >= 0) it else 0 }
    spinner.setSelection(pos)
    a.updateSwapMotorsUI(entries.getOrElse(pos) { VibrationDevice.PHONE })
}

// ── Adaptive trigger ─────────────────────────────────────

internal fun MainActivity.buildAdaptiveTriggerDeviceEntries(): List<AdaptiveTriggerDevice> {
    val a = this
    val entries = mutableListOf<AdaptiveTriggerDevice>()
    entries.add(AdaptiveTriggerDevice.PHONE_MOTOR)
    a.physicalControllerHandler.connectedControllers.value.forEachIndexed { index, info ->
        if (info.motorCount > 0) entries.add(AdaptiveTriggerDevice.controllerMotor(index))
        if (info.hasTriggerRumble || info.hasAdaptiveTrigger) {
            entries.add(AdaptiveTriggerDevice.controllerTrigger(index))
        }
    }
    entries.add(AdaptiveTriggerDevice.NONE)
    return entries
}

internal fun MainActivity.adaptiveTriggerDeviceName(device: AdaptiveTriggerDevice): String {
    val a = this
    val info = a.physicalControllerHandler.connectedControllers.value.getOrNull(device.controllerIndex)
    val controllerName = info?.name?.takeIf { it.isNotBlank() } ?: "手柄${device.controllerIndex + 1}"
    return when (device.type) {
        AdaptiveTriggerTargetType.PHONE_MOTOR -> "手机马达"
        AdaptiveTriggerTargetType.CONTROLLER_MOTOR -> "$controllerName 马达"
        AdaptiveTriggerTargetType.CONTROLLER_TRIGGER -> "$controllerName 扳机"
        AdaptiveTriggerTargetType.NONE -> "无"
    }
}

internal fun MainActivity.updateAdaptiveTriggerDeviceAdapter(
    spinner: Spinner, entries: List<AdaptiveTriggerDevice>,
) {
    val a = this
    val names = entries.map { a.adaptiveTriggerDeviceName(it) }.toTypedArray()
    val adapter = ArrayAdapter(a, android.R.layout.simple_spinner_item, names)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
}

/** Left/right output channels available for the selected adaptive-trigger target. A controller
 *  trigger target always has two triggers; motor targets need two motors to swap. */
internal fun MainActivity.selectedAdaptiveTriggerMotorCount(device: AdaptiveTriggerDevice): Int {
    val a = this
    return when (device.type) {
        AdaptiveTriggerTargetType.PHONE_MOTOR -> a.phoneMotorCount()
        AdaptiveTriggerTargetType.CONTROLLER_MOTOR ->
            a.physicalControllerHandler.connectedControllers.value.getOrNull(device.controllerIndex)?.motorCount ?: 0
        AdaptiveTriggerTargetType.CONTROLLER_TRIGGER -> 2
        AdaptiveTriggerTargetType.NONE -> 0
    }
}

internal fun MainActivity.updateSwapAdaptiveTriggersUI(device: AdaptiveTriggerDevice) {
    val a = this
    val showSwap = a.selectedAdaptiveTriggerMotorCount(device) >= 2
    a.findViewById<View>(R.id.layoutSwapAdaptiveTriggers).visibility = if (showSwap) View.VISIBLE else View.GONE
    a.findViewById<Switch>(R.id.switchSwapAdaptiveTriggers).isChecked =
        a.viewModel.settings.value.swapAdaptiveTriggers
}

internal fun MainActivity.syncAdaptiveTriggerUI() {
    val a = this
    if (!a.settingsInflated) return
    val entries = a.buildAdaptiveTriggerDeviceEntries()
    a.adaptiveTriggerDeviceEntries = entries
    val spinner = a.findViewById<Spinner>(R.id.spinnerAdaptiveTriggerDevice)
    a.updateAdaptiveTriggerDeviceAdapter(spinner, entries)

    val connectedCount = a.physicalControllerHandler.connectedControllers.value.size
    var selected = a.effectiveAdaptiveTriggerDevice()
    val missingController = selected.type != AdaptiveTriggerTargetType.PHONE_MOTOR &&
        selected.type != AdaptiveTriggerTargetType.NONE &&
        (selected.controllerIndex >= connectedCount || selected !in entries)
    if (missingController) {
        selected = AdaptiveTriggerDevice.NONE
    }
    val pos = entries.indexOf(selected).let { if (it >= 0) it else entries.size - 1 }
    spinner.setSelection(pos)
    a.updateSwapAdaptiveTriggersUI(entries.getOrElse(pos) { AdaptiveTriggerDevice.NONE })
    a.applyAdaptiveTriggerSettings()
}

/** The adaptive-trigger target of the active set (connected vs disconnected). */
internal fun MainActivity.effectiveAdaptiveTriggerDevice(): AdaptiveTriggerDevice =
    viewModel.settings.value.adaptiveTriggerDeviceFor(physicalControllerHandler.isConnected.value)

/** Pushes the selected adaptive-trigger target into the adaptive-trigger handler. */
internal fun MainActivity.applyAdaptiveTriggerSettings() {
    val a = this
    val s = a.viewModel.settings.value
    a.adaptiveTriggerHandler.setTarget(
        s.adaptiveTriggerDeviceFor(a.physicalControllerHandler.isConnected.value),
        s.swapAdaptiveTriggers,
    )
}

/** The game-rumble target of the active set (connected vs disconnected). */
internal fun MainActivity.effectiveGameVibrationDevice(): VibrationDevice =
    viewModel.settings.value.gameVibrationDeviceFor(physicalControllerHandler.isConnected.value)

/** The voice-coil target of the active set (connected vs disconnected). */
internal fun MainActivity.effectiveVoiceCoilDevice(): AudioDevice =
    viewModel.settings.value.voiceCoilDeviceFor(physicalControllerHandler.isConnected.value)

/**
 * Pushes the settings of the active (connected vs disconnected) set into the physical
 * controller handler: game rumble target, gyro source index and controller-gyro flag.
 */
internal fun MainActivity.applyEffectivePhysicalControllerSettings() {
    val a = this
    val s = a.viewModel.settings.value
    val connected = a.physicalControllerHandler.isConnected.value
    a.physicalControllerHandler.gameVibrationDevice = s.gameVibrationDeviceFor(connected)
    a.physicalControllerHandler.gyroControllerIndex = s.gyroControllerIndexFor(connected)
    a.physicalControllerHandler.onControllerGyroSettingChanged(
        s.gyroSourceFor(connected).type == GyroSourceType.CONTROLLER
    )
    a.applyAdaptiveTriggerSettings()
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

internal fun MainActivity.syncControllerDriverUI() {
    val a = this
    if (!a.settingsInflated) return
    val spinner = a.findViewById<Spinner>(R.id.spinnerControllerDriver)
    val pos = a.controllerDriverEntries.indexOf(a.viewModel.settings.value.controllerDriver)
        .let { if (it >= 0) it else 0 }
    spinner.setSelection(pos)
}

internal fun MainActivity.syncPhysicalControllerUI() {
    val a = this
    if (!a.settingsInflated) return
    a.syncControllerDriverUI()
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
    a.syncControllerDetailUI()
}

/** The controller currently selected as the input source, or null when none is active. */
internal fun MainActivity.currentInputControllerInfo(): ControllerInfo? {
    val a = this
    val index = a.viewModel.settings.value.inputControllerIndex
    if (index < 0) return null
    return a.physicalControllerHandler.connectedControllers.value.getOrNull(index)
}

internal fun MainActivity.controllerDetailVibrationText(info: ControllerInfo?): String = when {
    info == null -> "未连接"
    info.motorCount <= 0 -> "不支持"
    else -> "${info.motorCount}个马达"
}

internal fun MainActivity.controllerDetailGyroText(info: ControllerInfo?): String =
    when {
        info == null -> "未连接"
        info.hasGyro -> "支持"
        else -> "不支持"
    }

internal fun MainActivity.controllerDetailTouchpadText(info: ControllerInfo?): String =
    when {
        info == null -> "未连接"
        info.hasTouchpad -> "支持"
        else -> "不支持"
    }

internal fun MainActivity.controllerDetailTriggerText(info: ControllerInfo?): String = when {
    info == null -> "未连接"
    info.hasAdaptiveTrigger -> "自适应扳机"
    info.hasTriggerRumble -> "带有震动的线性扳机"
    info.hasAnalogTrigger -> "线性扳机"
    else -> "非线性扳机"
}

/** Refreshes the read-only detail card for the controller selected as the input source. */
internal fun MainActivity.syncControllerDetailUI() {
    val a = this
    if (!a.settingsInflated) return
    val info = a.currentInputControllerInfo()
    a.findViewById<TextView>(R.id.tvControllerDetailDriver).text =
        if (info == null) "未连接" else a.physicalControllerHandler.activeDriver.value.displayName
    a.findViewById<TextView>(R.id.tvControllerDetailVibration).text =
        a.controllerDetailVibrationText(info)
    a.findViewById<TextView>(R.id.tvControllerDetailGyro).text =
        a.controllerDetailGyroText(info)
    a.findViewById<TextView>(R.id.tvControllerDetailTouchpad).text =
        a.controllerDetailTouchpadText(info)
    a.findViewById<TextView>(R.id.tvControllerDetailTrigger).text =
        a.controllerDetailTriggerText(info)
}

internal fun MainActivity.currentGyroSource(): GyroSource {
    val a = this
    val connected = a.physicalControllerHandler.isConnected.value
    return a.viewModel.settings.value.gyroSourceFor(connected)
}

internal fun MainActivity.applyGyroSource(source: GyroSource) {
    val a = this
    val connected = a.physicalControllerHandler.isConnected.value
    a.viewModel.updateGyroSource(source)
    a.physicalControllerHandler.gyroControllerIndex =
        a.viewModel.settings.value.gyroControllerIndexFor(connected)
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
        else GyroSource.controller(
            a.viewModel.settings.value.gyroControllerIndexFor(a.physicalControllerHandler.isConnected.value)
        )
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

internal fun MainActivity.buildControllerAudioDeviceEntries(): List<AudioDevice> {
    val a = this
    val entries = mutableListOf<AudioDevice>()
    // Sound devices come first so the "first enumerated device" default is index 0.
    SdlAudio.devices().forEach { device ->
        entries.add(AudioDevice.soundDevice(device.id, device.name))
    }
    a.physicalControllerHandler.connectedControllers.value.forEachIndexed { index, _ ->
        if (a.physicalControllerHandler.controllerSupportsAudio(index)) {
            entries.add(AudioDevice.controller(index))
        }
    }
    entries.add(AudioDevice.NONE)
    return entries
}

internal fun MainActivity.buildVoiceCoilDeviceEntries(): List<AudioDevice> {
    val a = this
    val entries = mutableListOf<AudioDevice>()
    a.physicalControllerHandler.connectedControllers.value.forEachIndexed { index, _ ->
        entries.add(AudioDevice.controller(index))
    }
    entries.add(AudioDevice.PHONE_MOTOR)
    // SDL enumerates the system's sound devices, including the phone speaker as a
    // regular device (built-in speaker, USB, Bluetooth...).
    SdlAudio.devices().forEach { device ->
        entries.add(AudioDevice.soundDevice(device.id, device.name))
    }
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
            AudioDeviceType.SOUND_DEVICE -> device.deviceName.ifBlank { "声音设备" }
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
    var selected = a.effectiveVoiceCoilDevice()
    if (selected.type == AudioDeviceType.CONTROLLER && selected.controllerIndex >= connectedCount) {
        selected = AudioDevice.PHONE_MOTOR
    }
    if (selected.type == AudioDeviceType.SOUND_DEVICE &&
        selected.deviceId != AudioDevice.AUTO_SOUND_DEVICE_ID &&
        entries.none { it.type == AudioDeviceType.SOUND_DEVICE && it.deviceId == selected.deviceId }
    ) {
        // The saved device is gone (unplugged / id changed): fall back to the default.
        selected = AudioDevice.PHONE_MOTOR
    }
    val autoDevice = selected.deviceId == AudioDevice.AUTO_SOUND_DEVICE_ID
    val pos = entries.indexOfFirst {
        when (selected.type) {
            AudioDeviceType.SOUND_DEVICE ->
                it.type == AudioDeviceType.SOUND_DEVICE &&
                    (autoDevice || it.deviceId == selected.deviceId)
            else -> it.type == selected.type && it.controllerIndex == selected.controllerIndex
        }
    }.let { index ->
        if (index >= 0) index
        else entries.indexOfFirst { it.type == AudioDeviceType.PHONE_MOTOR }.coerceAtLeast(0)
    }
    spinner.setSelection(pos)
    a.updateVoiceCoilSwapUI(entries.getOrElse(pos) { AudioDevice.PHONE_MOTOR })
}

internal fun MainActivity.setupControllerAudioSpinner(
    entries: List<AudioDevice>,
    onChanged: (AudioDevice) -> Unit,
) {
    val a = this
    val spinner = a.findViewById<Spinner>(R.id.spinnerControllerAudio)
    spinner.setOnTouchListener { _, _ ->
        a.controllerAudioUserSelecting = true
        false
    }
    a.audioControllerDeviceEntries = entries
    a.updateControllerAudioAdapter(spinner)
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            if (!a.controllerAudioUserSelecting) return
            a.controllerAudioUserSelecting = false
            if (pos < a.audioControllerDeviceEntries.size) onChanged(a.audioControllerDeviceEntries[pos])
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {
            a.controllerAudioUserSelecting = false
        }
    }
}

/** Rebuilds the controller-audio list (sound devices + controller USB speakers) and syncs it. */
internal fun MainActivity.syncControllerAudioUI() {
    val a = this
    if (!a.settingsInflated) return
    val entries = a.buildControllerAudioDeviceEntries()
    a.audioControllerDeviceEntries = entries
    val spinner = a.findViewById<Spinner>(R.id.spinnerControllerAudio)
    a.updateControllerAudioAdapter(spinner)
    val selected = a.viewModel.settings.value.controllerAudioDevice
    val autoDevice = selected.type == AudioDeviceType.SOUND_DEVICE &&
        selected.deviceId == AudioDevice.AUTO_SOUND_DEVICE_ID
    val pos = entries.indexOfFirst {
        when (selected.type) {
            AudioDeviceType.SOUND_DEVICE ->
                it.type == AudioDeviceType.SOUND_DEVICE &&
                    (autoDevice || it.deviceId == selected.deviceId)
            AudioDeviceType.CONTROLLER ->
                it.type == AudioDeviceType.CONTROLLER && it.controllerIndex == selected.controllerIndex
            else -> it.type == selected.type
        }
    }.let { if (it >= 0) it else 0 }
    spinner.setSelection(pos)
}

internal fun MainActivity.updateControllerAudioAdapter(spinner: Spinner) {
    val a = this
    val controllers = a.physicalControllerHandler.connectedControllers.value
    val names = a.audioControllerDeviceEntries.map { device ->
        when (device.type) {
            AudioDeviceType.CONTROLLER ->
                controllers.getOrNull(device.controllerIndex)?.name?.takeIf { it.isNotBlank() }
                    ?: "手柄${device.controllerIndex + 1}"
            AudioDeviceType.SOUND_DEVICE -> device.deviceName.ifBlank { "声音设备" }
            AudioDeviceType.PHONE_MOTOR,
            AudioDeviceType.PHONE_SPEAKER -> "手机马达"
            AudioDeviceType.NONE -> "无"
        }
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

private fun frequencyLabel(he: Int): String =
    "频率: $he (${RichTapFrequency.heToHz(he).roundToInt()}Hz)"

@SuppressLint("SetTextI18n")
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
    a.findViewById<TextView>(R.id.tvPressFrequency).text = frequencyLabel(s.vibrationPressFrequency)
    a.findViewById<SeekBar>(R.id.seekPressFrequency).progress = s.vibrationPressFrequency

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
    a.findViewById<TextView>(R.id.tvReleaseFrequency).text = frequencyLabel(s.vibrationReleaseFrequency)
    a.findViewById<SeekBar>(R.id.seekReleaseFrequency).progress = s.vibrationReleaseFrequency
}

internal fun MainActivity.testHaptic(isPress: Boolean) {
    val a = this
    val s = a.viewModel.settings.value
    val type = if (isPress) s.vibrationPressType else s.vibrationReleaseType
    when (type) {
        VibrationType.NONE -> return
        VibrationType.VIEW -> {
            val e = if (isPress) s.vibrationPressViewEffect else s.vibrationReleaseViewEffect
            // 同 performHaptic：高清震动下系统效果会被 RichTap 抢占，改用预置效果替换。
            if (PhoneHdHaptics.playPrebaked(e.prebakedId)) return
            a.gamepadLayout.performHapticFeedback(a.hapticEffectToConstant(e))
        }
        VibrationType.VIBRATION_EFFECT -> {
            val dur = (if (isPress) s.vibrationPressDuration else s.vibrationReleaseDuration).coerceAtLeast(1)
            val amp = if (isPress) s.vibrationPressIntensity else s.vibrationReleaseIntensity
            val freq = if (isPress) s.vibrationPressFrequency else s.vibrationReleaseFrequency
            if (PhoneHdHaptics.playEffect(amp, dur, freq)) return
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

    listOf(R.id.rowScreenOff, R.id.btnScreenOff).forEach { id ->
        a.findViewById<View>(id).setOnClickListener { a.enterScreenOffMode() }
    }

    a.findViewById<SeekBar>(R.id.seekFloatingOpacity).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                a.findViewById<TextView>(R.id.tvFloatingOpacity).text = "$progress%"
                if (fromUser) a.viewModel.updateFloatingOpacity(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    )

    listOf(R.id.rowFloatingMode, R.id.btnFloatingMode).forEach { id ->
        a.findViewById<View>(id).setOnClickListener { a.enterFloatingMode() }
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
            val wasLocal = a.viewModel.settings.value.connectionMode == ConnectionMode.LOCAL
            a.viewModel.stopServer()
            if (wasLocal) a.exitFloatingMode()
        } else {
            val s = a.viewModel.settings.value
            if (s.connectionMode == ConnectionMode.LOCAL) {
                a.startLocalMode()
                return@setOnClickListener
            }
            if (s.connectionMode == ConnectionMode.WIFI && s.controlType == ControlType.CONTROLLED) {
                a.launchControlledMode()
                return@setOnClickListener
            }
            if (s.connectionMode == ConnectionMode.BLUETOOTH) {
                // 蓝牙权限只在启动蓝牙模式时按需申请（应用启动时不申请）。
                a.startBluetoothModeWithPermission()
            } else if (s.connectionMode == ConnectionMode.USB) {
                a.checkUsbAdbAndStart()
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

    a.setupShizukuEntry()
}

/** 本机模式：在当前设备创建虚拟手柄，随后进入悬浮模式。 */
internal fun MainActivity.startLocalMode() {
    val a = this
    GamepadInjector.init(a)
    GamepadInjector.ensureBound()
    a.localStartPending = true
    a.updateShizukuEntry()
    if (a.tryStartLocalVirtualDevice()) return
    a.startShizukuPolling()
}

/**
 * 尝试创建虚拟设备并进入悬浮模式。Shizuku 尚未运行/未授权/用户服务未就绪时返回
 * false，由 [startShizukuPolling] 在就绪后自动继续。
 */
internal fun MainActivity.tryStartLocalVirtualDevice(): Boolean {
    val a = this
    if (!a.localStartPending) return false
    // 本机模式：仍暴露 FF（系统/游戏视其为带震动的设备），但忽略震动数据（避免手机
    // 震动 ↔ 虚拟手柄死循环），也不创建虚拟鼠标（避免与屏幕触摸冲突）。
    if (!GamepadInjector.ensureReady(rumble = false, mouse = false)) {
        a.updateShizukuEntry()
        if (GamepadInjector.permissionDenied) {
            // 授权只自动尝试一次；被拒绝后停止轮询并把失败显示在服务状态上，避免反复弹窗。
            a.localStartPending = false
            a.stopShizukuPolling()
            a.updateShizukuEntry()
            a.viewModel.setLocalModeStatus("Shizuku 授权失败，请在 Shizuku 中手动授权后重试")
        } else if (GamepadInjector.bindFailed) {
            // 已授权但用户服务始终连不上（疑似 Shizuku 存在重复实例），停止轮询并提示重启。
            a.localStartPending = false
            a.stopShizukuPolling()
            a.updateShizukuEntry()
            a.viewModel.setLocalModeStatus(ShizukuServiceBinding.BIND_FAILED_MESSAGE)
        }
        return false
    }
    a.localStartPending = false
    a.stopShizukuPolling()
    a.viewModel.startServer()
    a.enterFloatingMode()
    return true
}

/** 轮询 Shizuku 状态：刷新授权条目，并在用户已点击启动服务后自动完成启动。 */
internal fun MainActivity.startShizukuPolling() {
    val a = this
    if (a.shizukuPollingJob != null) return
    a.updateShizukuEntry()
    a.shizukuPollingJob = a.lifecycleScope.launch {
        while (true) {
            delay(500.milliseconds)
            if (!a.settingsInflated) continue
            a.updateShizukuEntry()
            if (a.tryStartLocalVirtualDevice()) break
        }
    }
}

internal fun MainActivity.stopShizukuPolling() {
    val a = this
    a.shizukuPollingJob?.cancel()
    a.shizukuPollingJob = null
}

internal fun MainActivity.setupShizukuEntry() {
    val a = this
    a.findViewById<Button>(R.id.btnShizukuEntry).setOnClickListener { a.onShizukuEntryAction() }
    a.updateShizukuEntry()
}

internal fun MainActivity.onShizukuEntryAction() {
    val a = this
    when (GamepadInjector.requiredAction(a)) {
        GamepadInjector.Action.DOWNLOAD -> GamepadInjector.openDownloadPage(a)
        GamepadInjector.Action.OPEN -> GamepadInjector.openShizuku(a)
        GamepadInjector.Action.REQUEST_PERMISSION -> {
            GamepadInjector.requestPermission(force = true)
        }
        GamepadInjector.Action.NONE -> Unit
    }
}

internal fun MainActivity.updateShizukuEntry() {
    val a = this
    if (!a.settingsInflated) return
    val btn = a.findViewById<Button>(R.id.btnShizukuEntry) ?: return
    btn.isEnabled = true
    when (GamepadInjector.requiredAction(a)) {
        GamepadInjector.Action.DOWNLOAD -> btn.text = "下载 Shizuku"
        GamepadInjector.Action.OPEN -> btn.text = "打开 Shizuku"
        GamepadInjector.Action.REQUEST_PERMISSION -> btn.text = "申请授权"
        GamepadInjector.Action.NONE -> {
            btn.text = "已授权"
            btn.isEnabled = false
        }
    }
}

// ── 高清震动（HD / RichTap）────────────────────────────────

internal fun MainActivity.setupHdVibrationEntry() {
    val a = this
    HapticInjector.init(a)
    a.findViewById<Switch>(R.id.switchHdVibration).setOnCheckedChangeListener { _, isChecked ->
        // updateHdVibrationUI 会按设置值程序化回写开关，也会触发本回调；只有真实用户
        // 操作（新值与当前设置不同）才需要联动保存。
        if (isChecked != a.viewModel.settings.value.hdVibrationEnabled) {
            a.viewModel.updateHdVibrationEnabled(isChecked)
            a.updateHdVibrationUI()
        }
    }
    a.updateHdVibrationUI()
}

internal fun MainActivity.updateHdVibrationUI() {
    val a = this
    if (!a.settingsInflated) return
    val s = a.viewModel.settings.value
    val sw = a.findViewById<Switch>(R.id.switchHdVibration)
    if (sw != null && sw.isChecked != s.hdVibrationEnabled) sw.isChecked = s.hdVibrationEnabled
    a.findViewById<TextView>(R.id.tvHdStatus).text =
        if (s.hdVibrationEnabled) HapticInjector.statusText() else "HD 未启用"
}

/** 进入“作为被控端”的连接页面（接收远端控制端输入并创建本地虚拟手柄）。 */
internal fun MainActivity.launchControlledMode() {
    val a = this
    a.hideSettings()
    a.startActivity(Intent(a, ControlledActivity::class.java))
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
    // 被控端需要用户显式进入连接页面（并完成 Shizuku 授权），不自动启动。
    if (s.connectionMode == ConnectionMode.WIFI && s.controlType == ControlType.CONTROLLED) return
    // 本机模式需要 Shizuku 授权并进入悬浮模式，由用户点击“启动服务”触发。
    if (s.connectionMode == ConnectionMode.LOCAL) return
    if (s.connectionMode == ConnectionMode.BLUETOOTH) {
        // 应用启动时不申请蓝牙权限：未授权则跳过自动启动，等用户点击“启动服务”时再按需申请。
        if (!a.hasBluetoothRuntimePermissions()) return
        a.checkBluetoothOnAndStart()
    } else if (s.connectionMode == ConnectionMode.USB) {
        a.checkUsbAdbAndStart()
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
    val isLocal = mode == ConnectionMode.LOCAL
    a.findViewById<View>(R.id.sectionTargetPlatform).visibility = if (isBt) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.sectionControlType).visibility = if (isWifi) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.tvServerIp).visibility = if (isWifi) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.sectionShizuku).visibility = if (isLocal) View.VISIBLE else View.GONE
    // 被控端的手柄类型改到被控 activity 内选择，主设置页仅在本地模式显示。
    a.findViewById<View>(R.id.sectionVirtualGamepad).visibility =
        if (isLocal) View.VISIBLE else View.GONE
    if (isLocal) {
        a.startShizukuPolling()
    } else {
        a.localStartPending = false
        a.stopShizukuPolling()
        a.viewModel.setLocalModeStatus("未启动")
    }
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
    a.selectChipGroup(listOf(R.id.btnConnWifi, R.id.btnConnBluetooth, R.id.btnConnUsb, R.id.btnConnLocal),
        ConnectionMode.entries.indexOf(s.connectionMode).coerceAtLeast(0))
    a.selectChipGroup(listOf(R.id.btnControlTypeController, R.id.btnControlTypeControlled),
        ControlType.entries.indexOf(s.controlType).coerceAtLeast(0))
    a.selectChipGroup(listOf(
        R.id.btnTargetWindows, R.id.btnTargetAndroid, R.id.btnTargetLinux,
        R.id.btnTargetAndroidGamepad, R.id.btnTargetUniversalKm, R.id.btnTargetWindowsGamepad
    ), TargetPlatform.entries.indexOf(s.targetPlatform).coerceAtLeast(0))
    a.selectChipGroup(
        listOf(R.id.btnVgXbox, R.id.btnVgDs4, R.id.btnVgDualsense, R.id.btnVgSwitch),
        VirtualGamepadType.entries.indexOf(s.virtualGamepadType).coerceAtLeast(0)
    )
    val pollingRateOptions = listOf(30, 45, 60, 90, 100, 120, 200, 250, 300, 500, 750, 1000)
    val pollingRateIndex = pollingRateOptions.indexOf(s.pollingRate)
    if (pollingRateIndex >= 0) {
        a.findViewById<Spinner>(R.id.spinnerPollingRate).setSelection(pollingRateIndex)
    }
    a.updateVibrationUI()
    a.syncGameVibrationUI()
    a.updateHdVibrationUI()
    a.syncAdaptiveTriggerUI()
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
    a.findViewById<SeekBar>(R.id.seekFloatingOpacity).progress = s.floatingOpacity
    a.findViewById<TextView>(R.id.tvFloatingOpacity).text = "${s.floatingOpacity}%"

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

    a.syncVoiceCoilUI()
    a.syncControllerAudioUI()
}
