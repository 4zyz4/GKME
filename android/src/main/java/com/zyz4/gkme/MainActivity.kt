package com.zyz4.gkme

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.InputDevice
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
import androidx.core.content.ContextCompat
import com.zyz4.gkme.model.AudioDevice
import com.zyz4.gkme.model.AdaptiveTriggerDevice
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.AppSettings
import com.zyz4.gkme.model.HapticEffect
import com.zyz4.gkme.model.VibrationType
import com.zyz4.gkme.service.FloatingOverlayService
import com.zyz4.gkme.view.FloatingEditorPanel
import com.zyz4.gkme.view.GamepadLayout
import com.zyz4.gkme.view.LayoutGlobalSettingsPanel
import com.zyz4.gkme.input.AdaptiveTriggerHandler
import com.zyz4.gkme.input.PhysicalControllerHandler
import com.zyz4.gkme.input.SdlAudio
import com.zyz4.gkme.input.SdlPlatform
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    internal val viewModel: GkViewModel by viewModels()
    internal lateinit var gamepadLayout: GamepadLayout
    internal lateinit var floatingController: FloatingModeController
    internal val floatingEditor: FloatingEditorPanel by lazy { createFloatingEditor() }
    internal var layoutGlobalSettingsPanel: LayoutGlobalSettingsPanel? = null
    internal val controlViews = mutableMapOf<String, View>()
    internal val touchpadLabels = mutableListOf<TextView>()
    internal val mousepadLabels = mutableListOf<TextView>()
    internal var discoverableRequested = false
    internal var vibrationPollingJob: kotlinx.coroutines.Job? = null
    internal var audioPollingJob: kotlinx.coroutines.Job? = null
    internal var lastAppliedSettings: AppSettings? = null
    internal var lastPresetInfos: Any? = null
    internal var lastPresetCurrentName: String? = null

    private var floatingStartedAt = 0L
    private var pendingFloatingStart = false
    private val FLOATING_RESUME_GUARD_MS = 2000L

    internal var audioControllerDeviceEntries: List<AudioDevice> = emptyList()
    internal var adaptiveTriggerDeviceEntries: List<AdaptiveTriggerDevice> = emptyList()
    internal var adaptiveTriggerUserSelecting = false

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

    internal val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            continueEnterFloating()
        } else {
            showToast("需要悬浮窗权限才能使用悬浮模式")
        }
    }

    internal val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingFloatingStart) {
            pendingFloatingStart = false
            startFloatingMode()
        }
    }

    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    internal lateinit var physicalControllerHandler: PhysicalControllerHandler

    internal val adaptiveTriggerHandler: AdaptiveTriggerHandler by lazy {
        AdaptiveTriggerHandler(physicalControllerHandler) { left, right ->
            vibratePhoneForAdaptive(left, right)
        }
    }

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
        floatingController = FloatingModeController(this)
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
                    isScreenOff -> exitScreenOffMode()
                    previewZoomVisible -> hidePreviewZoom()
                    isLayoutGlobalSettingsVisible() -> hideLayoutGlobalSettings()
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
        setupUsbAudioCallbacks()
        displayManager.registerDisplayListener(displayListener, null)
        checkDeviceRotation()
        // Bring up the SDL Java glue (without claiming USB HID) and the SDL audio
        // subsystem so the sound-device list is available even when the physical
        // controller is handled by the non-SDL driver. Failure is non-fatal: the
        // phone-speaker path then falls back to AudioTrack.
        runCatching { SdlPlatform.ensureCore(this) }
        SdlAudio.ensureInit()
        physicalControllerHandler.start()
    }

    private fun setupUsbAudioCallbacks() {
        val a = this
        a.audioPlaybackService.supportsVoiceCoilPcm = { index ->
            a.physicalControllerHandler.controllerSupportsVoiceCoilPcm(index)
        }
        a.audioPlaybackService.supportsControllerAudio = { index ->
            a.physicalControllerHandler.controllerSupportsAudio(index)
        }
        a.audioPlaybackService.onVoiceCoilPcm = { index, frame ->
            a.physicalControllerHandler.submitVoiceCoilFrame(index, frame)
        }
        a.audioPlaybackService.onControllerAudioPcm = { index, frame ->
            a.physicalControllerHandler.submitControllerAudioFrame(index, frame)
        }
        a.audioPlaybackService.supportsHdRumble = { index ->
            a.physicalControllerHandler.controllerSupportsHdRumble(index)
        }
        a.audioPlaybackService.onHdRumble = { index, bands ->
            a.physicalControllerHandler.setControllerHdRumble(
                index,
                bands.leftHighFreq, bands.leftHighAmp, bands.leftLowFreq, bands.leftLowAmp,
                bands.rightHighFreq, bands.rightHighAmp, bands.rightLowFreq, bands.rightLowAmp,
            )
        }
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
        if (::floatingController.isInitialized && floatingController.isActive) {
            runCatching { floatingController.exit() }
            stopService(Intent(this, FloatingOverlayService::class.java))
        }
        physicalControllerHandler.stop()
        mediaSession?.release()
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::floatingController.isInitialized && floatingController.isActive &&
            android.os.SystemClock.elapsedRealtime() - floatingStartedAt > FLOATING_RESUME_GUARD_MS
        ) {
            exitFloatingMode()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(FloatingOverlayService.EXTRA_EXIT_FLOATING, false)) {
            exitFloatingMode()
        }
    }

    // ── Floating mode ──────────────────────────────────────

    internal fun enterFloatingMode() {
        if (::floatingController.isInitialized && floatingController.isActive) return
        if (gamepadLayout.isEditModeActive()) {
            showToast("请先退出编辑模式")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        continueEnterFloating()
    }

    private fun continueEnterFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingFloatingStart = true
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startFloatingMode()
    }

    private fun startFloatingMode() {
        if (floatingController.isActive) return
        if (isScreenOff) exitScreenOffMode()
        if (inSettings) hideSettings()
        ContextCompat.startForegroundService(this, Intent(this, FloatingOverlayService::class.java))
        floatingController.enter()
        floatingStartedAt = android.os.SystemClock.elapsedRealtime()
        moveTaskToBack(true)
        showToast("悬浮模式已开启，点按悬浮按钮显示/隐藏")
    }

    internal fun exitFloatingMode() {
        if (!::floatingController.isInitialized || !floatingController.isActive) return
        runCatching { floatingController.exit() }
        stopService(Intent(this, FloatingOverlayService::class.java))
        showToast("已退出悬浮模式")
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
    internal var isScreenOff = false
    internal var currentSettingsCategory = 0
    internal var settingsInflated = false
    internal var outputPickerDialog: Dialog? = null
    internal val sidebarItemDrawables = mutableMapOf<Int, com.zyz4.gkme.view.SidebarItemDrawable>()
    internal val sidebarAnimators = mutableMapOf<Int, android.animation.AnimatorSet>()
    internal var settingsRevealAnimator: android.animation.Animator? = null

    internal var gameVibrationDeviceEntries: List<com.zyz4.gkme.model.VibrationDevice> =
        listOf(com.zyz4.gkme.model.VibrationDevice.PHONE, com.zyz4.gkme.model.VibrationDevice.NONE)

    /** Controller indices backing the physical-controller input spinner; -1 = 不使用手柄. */
    internal var inputControllerIndices: List<Int> = listOf(-1)

    /** True while the user is picking a physical-controller input from the spinner. */
    internal var inputControllerUserSelecting = false

    /** True while the user is picking the physical-controller driver from the spinner. */
    internal var controllerDriverUserSelecting = false

    /** Drivers backing the physical-controller driver spinner. */
    internal var controllerDriverEntries: List<com.zyz4.gkme.model.ControllerDriver> =
        com.zyz4.gkme.model.ControllerDriver.entries

    /** True while the user is picking a game-rumble device from the spinner. */
    internal var gameVibrationUserSelecting = false

    /** True while the user is picking a voice-coil device from the spinner. */
    internal var voiceCoilUserSelecting = false

    /** True while the user is picking a controller-audio device from the spinner. */
    internal var controllerAudioUserSelecting = false

    /** True while the user is picking a gyro source from the spinner. */
    internal var gyroSourceUserSelecting = false

    /** Devices backing the voice-coil spinner. */
    internal var voiceCoilDeviceEntries: List<com.zyz4.gkme.model.AudioDevice> =
        listOf(com.zyz4.gkme.model.AudioDevice.PHONE_MOTOR)

    /** Sources backing the gyro-source spinner. */
    internal var gyroSourceEntries: List<com.zyz4.gkme.model.GyroSource> =
        listOf(com.zyz4.gkme.model.GyroSource.PHONE, com.zyz4.gkme.model.GyroSource.NONE)

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

    /** True when [event] is a BACK key coming from a gamepad/joystick rather than the
     *  system back key or back gesture. Some controllers report their B button as
     *  KEYCODE_BACK, which would otherwise leave screen-off mode. */
    private fun isGamepadBackEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val source = event.source
        if (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) return true
        if (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) return true
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        return device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // In screen-off mode the gamepad is meant for gameplay: swallow controller
        // BACK events (e.g. the B button) so only the real back key/gesture exits.
        if (isScreenOff && isGamepadBackEvent(event)) return true
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

    // ── Screen off mode ────────────────────────────────────

    /** Enters screen-off mode: the screen is covered with pure black while the invisible
     *  touch gamepad keeps working. The settings button is disabled until the user leaves
     *  with the back key or back gesture. */
    internal fun enterScreenOffMode() {
        val a = this
        if (a.isScreenOff || a.gamepadLayout.isEditModeActive()) return
        if (a.inSettings) a.hideSettings()
        a.isScreenOff = true
        a.findViewById<View>(R.id.screenOffOverlay).visibility = View.VISIBLE
        a.setSettingsButtonEnabled(false)
        // Force the screen on while in screen-off mode, regardless of the user setting.
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        a.showToast("使用返回键或返回手势退出")
    }

    internal fun exitScreenOffMode() {
        val a = this
        if (!a.isScreenOff) return
        a.isScreenOff = false
        a.findViewById<View>(R.id.screenOffOverlay).visibility = View.GONE
        a.setSettingsButtonEnabled(true)
        // Restore the user's own keep-screen-on preference.
        if (a.viewModel.settings.value.keepScreenOn) {
            a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    internal fun setSettingsButtonEnabled(enabled: Boolean) {
        val a = this
        for (i in 0 until a.gamepadLayout.childCount) {
            val child = a.gamepadLayout.getChildAt(i)
            if (child.tag == GamepadLayout.SETTINGS_BUTTON_ID) {
                child.isEnabled = enabled
                child.isClickable = enabled
            }
        }
    }

    // ── Haptic ─────────────────────────────────────────────
internal fun performHaptic(isPress: Boolean) {
        val s = viewModel.settings.value
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

    /** Drives the phone motors for adaptive-trigger conversion. Left/right map to the two
     *  actuators when the device exposes multiple vibrators, otherwise the loudest side is used. */
    internal fun vibratePhoneForAdaptive(left: Int, right: Int) {
        val l = left.coerceIn(0, 255)
        val r = right.coerceIn(0, 255)
        if (l <= 0 && r <= 0) {
            try { vibrator.cancel() } catch (_: Exception) {}
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val ids = vm?.vibratorIds
            if (vm != null && ids != null && ids.size >= 2) {
                try {
                    vm.cancel()
                    val combo = android.os.CombinedVibration.startParallel()
                    if (l > 0) combo.addVibrator(ids[0], VibrationEffect.createOneShot(60000, l))
                    if (r > 0) combo.addVibrator(ids[1], VibrationEffect.createOneShot(60000, r))
                    vm.vibrate(combo.combine())
                } catch (_: Exception) {}
                return
            }
        }
        try {
            vibrator.cancel()
            vibrator.vibrate(VibrationEffect.createOneShot(60000, maxOf(l, r)))
        } catch (_: Exception) {}
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
        val container = findViewById<ViewGroup>(containerId)
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
        bits.forEach { bit ->
            val chip = TextView(this).apply {
                text = BitNameMapper.getBitName(bit)
                setTextColor(-0x1)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setPadding((6f * density).toInt(), (2f * density).toInt(), (6f * density).toInt(), (2f * density).toInt())
                setOnClickListener { onRemove(bit) }
            }
            container.addView(chip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    internal fun updateVolumeMappingLabels() {
        rebuildVolumeChips(R.id.layoutVolumeUpChips, viewModel.settings.value.volumeUpBits) { bit ->
            viewModel.updateVolumeUpBits(viewModel.settings.value.volumeUpBits - bit)
            updateVolumeMappingLabels()
        }
        rebuildVolumeChips(R.id.layoutVolumeDownChips, viewModel.settings.value.volumeDownBits) { bit ->
            viewModel.updateVolumeDownBits(viewModel.settings.value.volumeDownBits - bit)
            updateVolumeMappingLabels()
        }
    }
}
