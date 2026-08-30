package com.zyz4.gkme

import android.annotation.SuppressLint
import android.app.Dialog
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.zyz4.gkme.model.AudioOutput
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.AppSettings
import com.zyz4.gkme.model.HapticEffect
import com.zyz4.gkme.model.VibrationMotor
import com.zyz4.gkme.model.VibrationType
import com.zyz4.gkme.view.FloatingEditorPanel
import com.zyz4.gkme.view.GamepadLayout
import com.zyz4.gkme.input.PhysicalControllerHandler
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    internal val viewModel: GkViewModel by viewModels()
    internal lateinit var gamepadLayout: GamepadLayout
    internal val floatingEditor: FloatingEditorPanel by lazy { createFloatingEditor() }
    internal val controlViews = mutableMapOf<String, View>()
    internal val touchpadLabels = mutableListOf<TextView>()
    internal val mousepadLabels = mutableListOf<TextView>()
    internal var discoverableRequested = false
    internal var vibrationPollingJob: kotlinx.coroutines.Job? = null
    internal var audioPollingJob: kotlinx.coroutines.Job? = null
    internal var vibrationRedirectStatus: String? = null
    internal var lastAppliedSettings: AppSettings? = null
    internal var lastPresetInfos: Any? = null
    internal var lastPresetCurrentName: String? = null

    internal var audioMappingEntries: List<AudioOutput> = emptyList()
    internal var audioOutputEntries: List<AudioOutput> = emptyList()
    internal var audioControllerOutputEntries: List<AudioOutput> = emptyList()

    private var mediaSession: MediaSession? = null

    internal val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    internal val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.all { it.value }) {
            viewModel.startServer()
        } else {
            showToast("需要蓝牙权限才能使用蓝牙模式")
        }
    }

    internal val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startServer()
        } else {
            showToast("需要开启蓝牙才能使用蓝牙模式")
        }
    }

    internal val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> }

    internal val importPresetLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importPresetFromUri(it) }
    }

    internal val exportPresetLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportPresetToUri(it) }
    }

    internal val exportAppearanceLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { exportAppearanceToUri(it) }
    }

    internal val importAppearanceLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importAppearanceFromUri(it) }
    }

    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    internal lateinit var physicalControllerHandler: PhysicalControllerHandler

    internal val audioPlaybackService: com.zyz4.gkme.service.AudioPlaybackService
        get() = viewModel.connectionManager.audioPlaybackService

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            checkDeviceRotation()
        }
    }

    @Suppress("DEPRECATION")
    internal fun checkDeviceRotation() {
        val inverted = windowManager.defaultDisplay.rotation == Surface.ROTATION_270
        viewModel.setDeviceInverted(inverted)
        if (inSettings) updateGyroLandscapeInvertedNote(inverted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableEdgeToEdge()
        hideSystemBars()
        gamepadLayout = findViewById(R.id.gamepadLayout)
        physicalControllerHandler = PhysicalControllerHandler(this)
        setupMediaSession()
        setupGamepadLayoutListener()
        viewModel.onHapticFeedbackPress = { performHaptic(isPress = true) }
        viewModel.onHapticFeedbackRelease = { performHaptic(isPress = false) }
        gamepadLayout.applyAppearance(viewModel.settings.value)
        // Register the appearance image-picker launchers now (registration must happen before
        // the activity is resumed). The settings panel itself is inflated lazily on the first
        // showSettings(), and the inflation is also pre-scheduled off the startup path so the
        // first frame stays fast.
        setupAppearanceImageLaunchers()
        Handler(Looper.getMainLooper()).postDelayed({
            ensureSettingsInflated()
        }, 500L)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    previewZoomVisible -> hidePreviewZoom()
                    gamepadLayout.isEditModeActive() -> {
                        CustomDialog.showConfirm(
                            context = this@MainActivity,
                            title = "退出编辑",
                            message = "是否放弃更改？",
                            positiveText = "放弃",
                            onPositive = {
                                gamepadLayout.discardToSnapshot()
                                gamepadLayout.exitEditMode()
                            }
                        )
                    }
                    inSettings -> hideSettings()
                }
            }
        })
        observeState()
        autoStartService()
        displayManager.registerDisplayListener(displayListener, null)
        checkDeviceRotation()
        physicalControllerHandler.start()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    override fun onDestroy() {
        physicalControllerHandler.stop()
        mediaSession?.release()
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    internal var pointerCaptureNeeded = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (pointerCaptureNeeded) {
                Handler(Looper.getMainLooper()).postDelayed({
                    gamepadLayout.setTouchpadCaptureMode(true)
                }, 500)
            }
        }
    }

    // ── MediaSession (intercept volume keys before system) ──

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "GKME").apply {
            isActive = true
        }
    }

    // ── Dialog fields ──────────────────────────────────────

    internal var addDialog: Dialog? = null
    internal var addCounter = 0
    internal var inSettings = false
    internal var currentSettingsCategory = 0
    internal var settingsInflated = false
    internal var outputPickerDialog: Dialog? = null

    internal var vibrationMappingEntries: List<VibrationMotor> = VibrationMotor.entries.toList()

    // Appearance image pickers
    internal var bgImagePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    internal var btnImagePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    internal var joyBaseImagePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    internal var joyCapImagePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    internal var tpImagePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    internal var padImagePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    // ── Input dispatch ─────────────────────────────────────

    private fun isPhysicalGamepadKey(code: Int): Boolean = code == KeyEvent.KEYCODE_BUTTON_1 ||
        code == KeyEvent.KEYCODE_DPAD_UP ||
        code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT ||
        code == KeyEvent.KEYCODE_DPAD_RIGHT ||
        code == KeyEvent.KEYCODE_BUTTON_A ||
        code == KeyEvent.KEYCODE_BUTTON_B ||
        code == KeyEvent.KEYCODE_BUTTON_X ||
        code == KeyEvent.KEYCODE_BUTTON_Y ||
        code == KeyEvent.KEYCODE_BUTTON_L1 ||
        code == KeyEvent.KEYCODE_BUTTON_R1 ||
        code == KeyEvent.KEYCODE_BUTTON_L2 ||
        code == KeyEvent.KEYCODE_BUTTON_R2 ||
        code == KeyEvent.KEYCODE_BUTTON_SELECT ||
        code == KeyEvent.KEYCODE_BUTTON_START ||
        code == KeyEvent.KEYCODE_BUTTON_THUMBL ||
        code == KeyEvent.KEYCODE_BUTTON_THUMBR ||
        code == KeyEvent.KEYCODE_BUTTON_MODE ||
        code == KeyEvent.KEYCODE_MEDIA_RECORD

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isPhysicalGamepadKey(event.keyCode) &&
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

    internal fun syncPhysicalControllerState() {
        val state = physicalControllerHandler.controllerState.value
        val connected = physicalControllerHandler.isConnected.value
        val stickX = if (connected) state.leftStickX else 0
        val stickY = if (connected) state.leftStickY else 0
        val rStickX = if (connected) state.rightStickX else 0
        val rStickY = if (connected) state.rightStickY else 0
        val lTrigger = if (connected) state.leftTrigger else 0
        val rTrigger = if (connected) state.rightTrigger else 0

        viewModel.onPhysicalControllerInput(
            state.buttons,
            stickX, stickY,
            rStickX, rStickY,
            lTrigger, rTrigger,
            state.dpad,
            state.touchpadX, state.touchpadY,
            state.touchpadTouch, state.touchpadClick,
            state.touches,
        )
    }

    // ── Toast ──────────────────────────────────────────────

    internal fun showToast(msg: String) {
        CustomDialog.showToast(this, msg)
    }

    // ── Haptic ─────────────────────────────────────────────

    internal fun performHaptic(isPress: Boolean) {        val s = viewModel.settings.value
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

    internal fun hapticEffectToConstant(effect: HapticEffect): Int {
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

    // ── Chip Group ─────────────────────────────────────────

    /** Applies appearance only when the settings actually changed, to skip redundant
     *  full-tree restyles when opening/closing settings or on duplicate emissions. */
    internal fun applyAppearanceIfChanged(settings: AppSettings) {
        if (lastAppliedSettings != settings) {
            gamepadLayout.applyAppearance(settings)
            lastAppliedSettings = settings
        }
    }

    internal fun selectChipGroup(ids: List<Int>, selected: Int) {
        ids.forEachIndexed { i, id ->
            findViewById<Button>(id).setBackgroundResource(
                if (i == selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
        }
    }

    // ── Volume mapping helpers ─────────────────────────────

    

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
                    text = BitNameMapper.getBitName(bit)
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

    internal fun updateVolumeMappingLabels() {
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
}
