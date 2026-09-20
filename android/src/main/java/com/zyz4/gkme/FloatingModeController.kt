package com.zyz4.gkme

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import com.zyz4.gkme.view.GamepadLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Drives "悬浮模式": the app minimizes and an overlay window shows the gamepad.
 *
 * In the hidden state a single small window containing the show/hide button is placed
 * on screen and can be dragged around. Tapping it shows the full-screen controls window
 * that hosts the real [GamepadLayout] (reparented out of the activity), rotated 90°
 * when the foreground app is portrait. Tapping the settings button inside it hides the
 * controls again.
 */
internal class FloatingModeController(private val activity: MainActivity) {

    private val appContext: Context = activity.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val displayManager: DisplayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val density: Float get() = activity.resources.displayMetrics.density

    var isActive: Boolean = false
        private set

    var isShown: Boolean = false
        private set

    // Hidden-state toggle window
    private var toggleButton: ImageButton? = null
    private var toggleParams: WindowManager.LayoutParams? = null
    private var toggleSize = 0
    private var toggleWinX = 0
    private var toggleWinY = 0

    // Shown-state controls window
    private var controlsRoot: FrameLayout? = null
    private var controlsParams: WindowManager.LayoutParams? = null

    private var observerJob: Job? = null

    private var savedGamepadBackground: android.graphics.drawable.Drawable? = null
    private var gamepadBackgroundSaved = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            onOrientationChanged()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────

    fun enter() {
        if (isActive) return
        isActive = true
        isShown = false
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val rect = settingsButtonScreenRect()
        if (rect != null && rect.width() > 0 && rect.height() > 0) {
            toggleSize = maxOf(rect.width(), rect.height())
            toggleWinX = rect.left
            toggleWinY = rect.top
        } else {
            toggleSize = preferredToggleSizePx()
            val (dx, dy) = defaultTogglePosition(toggleSize)
            toggleWinX = dx
            toggleWinY = dy
        }
        createToggleWindow()
        startObservers()
    }

    fun exit() {
        if (!isActive) return
        if (isShown) {
            hideControls(recreateToggle = false)
        }
        removeToggleWindow()
        restoreGamepadBackground()
        restoreSettingsButton()
        displayManager.unregisterDisplayListener(displayListener)
        stopObservers()
        toggleButton = null
        toggleParams = null
        controlsRoot = null
        controlsParams = null
        isShown = false
        isActive = false
    }

    // ── Show / hide the full controls overlay ──────────────

    private fun showControls() {
        if (!isActive || isShown) return
        isShown = true
        removeToggleWindow()

        val gl = activity.gamepadLayout
        gl.cancelAllTouches()
        (gl.parent as? ViewGroup)?.removeView(gl)

        val root = FrameLayout(activity)
        controlsRoot = root

        val (sw, sh) = realScreenSize()
        val portrait = isPortrait()
        val lw = if (portrait) sh else sw
        val lh = if (portrait) sw else sh
        root.addView(gl, FrameLayout.LayoutParams(lw, lh))
        applyControlsTransform(gl, sw, sh, portrait)
        gl.alpha = floatingOpacity()
        // The app background (color or image) must not cover the underlying app.
        if (!gamepadBackgroundSaved) {
            savedGamepadBackground = gl.background
            gamepadBackgroundSaved = true
        }
        gl.background = null

        val params = WindowManager.LayoutParams(
            sw,
            sh,
            overlayType(),
            overlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        controlsParams = params
        val added = runCatching { windowManager.addView(root, params) }.isSuccess
        if (!added) {
            (gl.parent as? ViewGroup)?.removeView(gl)
            controlsRoot = null
            controlsParams = null
            isShown = false
            attachGamepadToActivity()
            restoreGamepadBackground()
            createToggleWindow()
            return
        }
        overrideSettingsButtonForFloating()
        root.post {
            if (isShown) {
                activity.gamepadLayout.background = null
                activity.gamepadLayout.alpha = floatingOpacity()
                overrideSettingsButtonForFloating()
            }
        }
    }

    private fun hideControls(recreateToggle: Boolean = true) {
        if (!isShown) return
        val gl = activity.gamepadLayout
        val rect = settingsButtonScreenRect()
        gl.cancelAllTouches()
        gl.alpha = 1f
        (gl.parent as? ViewGroup)?.removeView(gl)
        controlsRoot?.let { runCatching { windowManager.removeView(it) } }
        controlsRoot = null
        controlsParams = null
        isShown = false

        attachGamepadToActivity()
        restoreGamepadBackground()
        restoreSettingsButton()

        if (!recreateToggle) return
        toggleSize = if (rect != null) maxOf(rect.width(), rect.height()) else preferredToggleSizePx()
        if (rect != null) {
            toggleWinX = rect.left
            toggleWinY = rect.top
        } else {
            val (dx, dy) = defaultTogglePosition(toggleSize)
            toggleWinX = dx
            toggleWinY = dy
        }
        createToggleWindow()
    }

    private fun attachGamepadToActivity() {
        val gl = activity.gamepadLayout
        val panel = activity.findViewById<ViewGroup>(R.id.gamepadPanel) ?: return
        // Undo the floating-mode transform (90° rotation for portrait) so the layout
        // renders normally again in the landscape activity.
        gl.rotation = 0f
        gl.translationX = 0f
        gl.translationY = 0f
        gl.alpha = 1f
        if (gl.parent !== panel) {
            (gl.parent as? ViewGroup)?.removeView(gl)
            panel.addView(
                gl,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun applyControlsTransform(
        gl: View,
        sw: Int,
        sh: Int,
        portrait: Boolean,
    ) {
        if (portrait) {
            gl.rotation = 90f
            gl.translationX = (sw - sh) / 2f
            gl.translationY = (sh - sw) / 2f
        } else {
            gl.rotation = 0f
            gl.translationX = 0f
            gl.translationY = 0f
        }
    }

    // ── Hidden-state toggle window ─────────────────────────

    private fun createToggleWindow() {
        removeToggleWindow()
        val button = ImageButton(activity).apply {
            setBackgroundResource(R.drawable.bg_small_btn)
            setImageResource(R.drawable.ic_eye)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (8f * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = "显示/隐藏悬浮按钮"
            isClickable = true
        }
        // Match the settings button's background / border configuration.
        com.zyz4.gkme.view.AppearanceApplier.applyButtonAppearance(
            button,
            activity.viewModel.settings.value,
        )
        val params = WindowManager.LayoutParams(
            toggleSize,
            toggleSize,
            overlayType(),
            overlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = toggleWinX
            y = toggleWinY
        }

        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        button.setOnClickListener { showControls() }
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && hypot(dx.toDouble(), dy.toDouble()) > slop) moved = true
                    if (moved) {
                        val (sw, sh) = realScreenSize()
                        val maxX = maxOf(0, sw - toggleSize)
                        val maxY = maxOf(0, sh - toggleSize)
                        params.x = (startX + dx).toInt().coerceIn(0, maxX)
                        params.y = (startY + dy).toInt().coerceIn(0, maxY)
                        toggleWinX = params.x
                        toggleWinY = params.y
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!moved) { v.post { showControls() } } else { commitTogglePositionToSettingsButton() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }

        toggleButton = button
        toggleParams = params
        runCatching { windowManager.addView(button, params) }
    }

    private fun removeToggleWindow() {
        toggleButton?.let { runCatching { windowManager.removeView(it) } }
        toggleButton = null
        toggleParams = null
    }

    private fun clampTogglePosition() {
        val params = toggleParams ?: return
        val (sw, sh) = realScreenSize()
        val maxX = maxOf(0, sw - toggleSize)
        val maxY = maxOf(0, sh - toggleSize)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
        toggleWinX = params.x
        toggleWinY = params.y
        toggleButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /** Reads the settings-button grid position and moves the (hidden) toggle window there. */
    private fun repositionToggleFromSettings() {
        val rect = settingsButtonScreenRect() ?: run {
            clampTogglePosition()
            return
        }
        toggleSize = maxOf(rect.width(), rect.height())
        toggleWinX = rect.left
        toggleWinY = rect.top
        val params = toggleParams ?: return
        params.width = toggleSize
        params.height = toggleSize
        params.x = toggleWinX
        params.y = toggleWinY
        toggleButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /** After the user drags the toggle in hidden state, fold its screen position back into the
     *  settings button's grid position so the shown layout keeps the button where it was left. */
    private fun commitTogglePositionToSettingsButton() {
        val params = toggleParams ?: return
        val pos = activity.gamepadLayout.currentButtons
            .find { it.id == GamepadLayout.SETTINGS_BUTTON_ID } ?: return
        val (sw, sh) = realScreenSize()
        val portrait = isPortrait()
        val lw = if (portrait) sh else sw
        val lh = if (portrait) sw else sh
        val cell = lw.toFloat() / GamepadLayout.GRID_COLS
        if (cell <= 0f) return
        val lx: Int
        val ly: Int
        if (portrait) {
            lx = params.y
            ly = sw - params.x
        } else {
            lx = params.x
            ly = params.y
        }
        val maxCol = (GamepadLayout.GRID_COLS - pos.width).coerceAtLeast(0)
        val maxRow = ((lh / cell).toInt() - pos.height).coerceAtLeast(0)
        val gx = (lx / cell).roundToInt().coerceIn(0, maxCol)
        val gy = (ly / cell).roundToInt().coerceIn(0, maxRow)
        activity.gamepadLayout.setButtonPositionQuiet(pos.id, pos.copy(x = gx, y = gy))
    }

    // ── Settings button (toggle inside the layout) ─────────

    private fun settingsButtonChild(): View? {
        val gl = activity.gamepadLayout
        for (i in 0 until gl.childCount) {
            val child = gl.getChildAt(i)
            if (child.tag == GamepadLayout.SETTINGS_BUTTON_ID) return child
        }
        return null
    }

    private fun overrideSettingsButtonForFloating() {
        settingsButtonChild()?.let { child ->
            child.setOnClickListener(null)
            if (child is ImageButton) child.setImageResource(R.drawable.ic_eye_off)
            val slop = ViewConfiguration.get(activity).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var moved = false
            child.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        moved = false
                        v.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved && hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) {
                            moved = true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        if (!moved) v.post { hideControls() }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun restoreSettingsButton() {
        settingsButtonChild()?.let { child ->
            child.setOnTouchListener(null)
            child.setOnClickListener { activity.showSettings() }
            if (child is ImageButton) child.setImageResource(R.drawable.ic_settings)
        }
    }

    private fun restoreGamepadBackground() {
        if (!gamepadBackgroundSaved) return
        gamepadBackgroundSaved = false
        activity.gamepadLayout.background = savedGamepadBackground
        savedGamepadBackground = null
    }

    /** Screen-space rect of the settings button, computed from its grid position for the
     *  current display orientation. Works whether or not the controls window is shown. */
    private fun settingsButtonScreenRect(): android.graphics.Rect? {
        val pos = activity.gamepadLayout.currentButtons
            .find { it.id == GamepadLayout.SETTINGS_BUTTON_ID } ?: return null
        val (sw, sh) = realScreenSize()
        val portrait = isPortrait()
        val lw = if (portrait) sh else sw
        val cell = lw.toFloat() / GamepadLayout.GRID_COLS
        if (cell <= 0f) return null
        val l = (pos.x * cell).roundToInt()
        val t = (pos.y * cell).roundToInt()
        val r = ((pos.x + pos.width) * cell).roundToInt()
        val b = ((pos.y + pos.height) * cell).roundToInt()
        return if (portrait) {
            android.graphics.Rect(sw - b, l, sw - t, r)
        } else {
            android.graphics.Rect(l, t, r, b)
        }
    }

    // ── Orientation ────────────────────────────────────────

    private fun onOrientationChanged() {
        if (!isActive) return
        if (isShown) {
            rebuildControlsForOrientation()
        } else {
            repositionToggleFromSettings()
        }
    }

    private fun rebuildControlsForOrientation() {
        val root = controlsRoot ?: return
        val params = controlsParams ?: return
        val gl = activity.gamepadLayout
        val (sw, sh) = realScreenSize()
        val portrait = isPortrait()
        val lw = if (portrait) sh else sw
        val lh = if (portrait) sw else sh
        (gl.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = lw
            it.height = lh
            gl.layoutParams = it
        }
        applyControlsTransform(gl, sw, sh, portrait)
        params.width = sw
        params.height = sh
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    // ── Observers (run while the activity is stopped) ──────

    private fun startObservers() {
        observerJob = activity.lifecycleScope.launch {
            launch {
                activity.viewModel._gamepadState.collect { state ->
                    activity.gamepadLayout._gamepadButtons = state.buttons
                    activity.gamepadLayout.ctrlEntryBitMap = ctrlEntryBitMap
                }
            }
            launch {
                activity.viewModel.settings.collect { settings ->
                    if (isShown) activity.gamepadLayout.alpha = settings.floatingOpacity / 100f
                }
            }
            launch {
                activity.viewModel.displayMode.collect { activity.updateButtonLabels(it) }
            }
            launch {
                activity.viewModel.keyboardShiftActive.collect { activity.updateKeyboardLabels(it) }
            }
        }
    }

    private fun stopObservers() {
        observerJob?.cancel()
        observerJob = null
    }

    // ── Geometry helpers ───────────────────────────────────

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: activity.windowManager.defaultDisplay

    private fun realScreenSize(): Pair<Int, Int> {
        val point = Point()
        @Suppress("DEPRECATION")
        currentDisplay().getRealSize(point)
        return point.x to point.y
    }

    private fun isPortrait(): Boolean {
        val rotation = currentDisplay().rotation
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
    }

    private fun floatingOpacity(): Float =
        activity.viewModel.settings.value.floatingOpacity.coerceIn(0, 100) / 100f

    private fun preferredToggleSizePx(): Int {
        val pos = activity.gamepadLayout.currentButtons
            .find { it.id == GamepadLayout.SETTINGS_BUTTON_ID }
        val (sw, sh) = realScreenSize()
        val longSide = maxOf(sw, sh)
        val size = if (pos != null) {
            (maxOf(pos.width, pos.height).toFloat() / GamepadLayout.GRID_COLS * longSide).toInt()
        } else {
            0
        }
        return if (size > 0) size else (48f * density).toInt()
    }

    private fun defaultTogglePosition(size: Int): Pair<Int, Int> {
        val (sw, sh) = realScreenSize()
        val margin = (16f * density).toInt()
        return (sw - size - margin) to (sh / 2 - size / 2)
    }
}
