package com.zyz4.gkme

import com.zyz4.gkme.R
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
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
 * 两个悬浮窗：
 * - 全屏窗口（[overlayRoot]）：承载 [GamepadLayout]，隐藏态也保持挂载但不可触摸、无子视图
 *   （即完全透明）。它同时是「屏幕方向探针」——窗口 frame 由 WMS 按当前旋转实时计算，
 *   旋转后会重新布局并回调，是悬浮态下唯一可信的屏幕尺寸来源。
 * - 小窗口（[toggleButton]）：隐藏态的显示/隐藏按钮，可拖动。
 *
 * 竖屏时把 [GamepadLayout] 旋转 90°，让手柄的长边贴着屏幕长边。
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

    // Full-screen overlay window: hosts the gamepad when shown, acts as an orientation
    // probe while hidden.
    private var overlayRoot: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    /** 最近一次由 WMS 量到的窗口尺寸（当前旋转下的真实屏幕尺寸）。 */
    private var overlaySize: ScreenGeometry? = null

    private var observerJob: Job? = null

    private var savedGamepadBackground: android.graphics.drawable.Drawable? = null
    private var gamepadBackgroundSaved = false

    // ── Lifecycle ──────────────────────────────────────────

    fun enter() {
        if (isActive) return
        isActive = true
        isShown = false
        // 初始位置先用系统给的屏幕信息估一次；探针窗口 layout 完成后会按真实尺寸校正。
        placeToggleFromSettingsOrFallback(screenGeometry())
        createOverlayWindow()
        createToggleWindow()
        startObservers()
    }

    fun exit() {
        if (!isActive) return
        if (isShown) {
            hideControls(recreateToggle = false)
        }
        removeToggleWindow()
        removeOverlayWindow()
        restoreGamepadBackground()
        restoreSettingsButton()
        stopObservers()
        toggleButton = null
        toggleParams = null
        isShown = false
        isActive = false
    }

    // ── Show / hide the full controls overlay ──────────────

    private fun showControls() {
        if (!isActive || isShown) return
        if (!ensureOverlayWindow()) return
        val root = overlayRoot ?: return
        isShown = true
        removeToggleWindow()

        val gl = activity.gamepadLayout
        gl.cancelAllTouches()
        (gl.parent as? ViewGroup)?.removeView(gl)

        // 用窗口的真实尺寸排版；窗口还没量出尺寸时先用系统信息估一次，
        // 随后的 onOverlaySized 会按真实尺寸校正。
        val screen = overlaySize ?: screenGeometry()
        root.addView(gl, FrameLayout.LayoutParams(screen.localWidth, screen.localHeight))
        applyControlsTransform(gl, screen)
        gl.alpha = floatingOpacity()
        // The app background (color or image) must not cover the underlying app.
        if (!gamepadBackgroundSaved) {
            savedGamepadBackground = gl.background
            gamepadBackgroundSaved = true
        }
        gl.background = null

        // 显示态需要接收触摸；隐藏态是不可触摸的透明探针。
        overlayParams?.let { params ->
            params.flags = overlayFlags()
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        overrideSettingsButtonForFloating()
        root.post {
            if (isShown) {
                activity.gamepadLayout.background = null
                activity.gamepadLayout.alpha = floatingOpacity()
                overrideSettingsButtonForFloating()
                // 首帧之后按真实窗口尺寸再对齐一次（预估尺寸可能来自过期的 DisplayInfo）。
                onOverlaySized(root.width, root.height)
            }
        }
    }

    private fun hideControls(recreateToggle: Boolean = true) {
        if (!isShown) return
        val gl = activity.gamepadLayout
        gl.cancelAllTouches()
        gl.alpha = 1f
        (gl.parent as? ViewGroup)?.removeView(gl)
        isShown = false

        // 隐藏态保留全屏窗口：移空子视图后完全透明，并关掉触摸，只作为屏幕方向探针。
        val root = overlayRoot
        overlayParams?.let { params ->
            params.flags = overlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        }

        attachGamepadToActivity()
        restoreGamepadBackground()
        restoreSettingsButton()

        if (!recreateToggle) return
        // 用刚量到的窗口尺寸定位 toggle，比可能过期的 Display 信息可靠。
        placeToggleFromSettingsOrFallback(knownScreen())
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

    private fun applyControlsTransform(gl: View, screen: ScreenGeometry) {
        if (screen.portrait) {
            gl.rotation = 90f
            gl.translationX = (screen.width - screen.height) / 2f
            gl.translationY = (screen.height - screen.width) / 2f
        } else {
            gl.rotation = 0f
            gl.translationX = 0f
            gl.translationY = 0f
        }
    }

    // ── Full-screen overlay window (controls + orientation probe) ──

    /**
     * 确保全屏悬浮窗可用。窗口被系统移除（例如悬浮窗权限被收回）时会重建。
     */
    private fun ensureOverlayWindow(): Boolean {
        val existing = overlayRoot
        if (existing != null) {
            if (existing.isAttachedToWindow) return true
            removeOverlayWindow()
        }
        return createOverlayWindow()
    }

    /**
     * 创建全屏悬浮窗。宽高用 `MATCH_PARENT` 交给 WMS 定量，避免把（可能过期的）屏幕尺寸
     * 写死；`fitInsetsTypes=0` 让它铺满整屏（含状态栏/导航栏），坐标系与屏幕一致。
     */
    private fun createOverlayWindow(): Boolean {
        if (overlayRoot != null) return true
        val root = FrameLayout(activity)
        root.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (v === overlayRoot) onOverlaySized(r - l, b - t)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // 隐藏态先按不可触摸创建，showControls() 时再把触摸打开。
            overlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 不避让任何系统栏：否则 MATCH_PARENT 只会拿到「应用区」（例如竖屏下
                // 状态栏 + 导航栏占掉的 194px），悬浮层坐标系就和屏幕对不上。
                fitInsetsTypes = 0
            }
        }
        return try {
            windowManager.addView(root, params)
            overlayRoot = root
            overlayParams = params
            true
        } catch (t: Throwable) {
            Log.w(TAG, "failed to add floating overlay window", t)
            false
        }
    }

    private fun removeOverlayWindow() {
        overlayRoot?.let { runCatching { windowManager.removeView(it) } }
        overlayRoot = null
        overlayParams = null
        overlaySize = null
    }

    /**
     * 窗口尺寸变化（含屏幕旋转）。这是悬浮态下唯一可信的屏幕尺寸来源：
     * 应用退到后台后 `Display#getRealSize` / `DisplayListener` / `WindowMetrics` 都可能
     * 长期停在进入后台前的值（MIUI 会延迟给不可见进程下发显示信息）。
     */
    private fun onOverlaySized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val screen = ScreenGeometry(width, height)
        if (screen.sameSizeAs(overlaySize)) return
        overlaySize = screen
        if (isShown) {
            applyControlsGeometry(screen)
        } else {
            // 隐藏态把「小眼睛」重新贴到设置按钮的位置，避免旋转后跑到屏幕外。
            repositionToggleFromSettings(screen)
        }
    }

    private fun applyControlsGeometry(screen: ScreenGeometry) {
        val gl = activity.gamepadLayout
        if (gl.parent !== overlayRoot) return
        (gl.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != screen.localWidth || lp.height != screen.localHeight) {
                lp.width = screen.localWidth
                lp.height = screen.localHeight
                gl.layoutParams = lp
            }
        }
        applyControlsTransform(gl, screen)
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
            contentDescription = activity.getString(R.string.i18n_show_hide_floating_button)
            isClickable = true
        }
        // Match the settings button's background / border configuration.
        com.zyz4.gkme.view.AppearanceApplier.applyButtonAppearance(
            button,
            activity.viewModel.settings.value,
        )
        // The hidden-state show button inherits the same floating opacity as the controls.
        button.alpha = floatingOpacity()
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
                        val screen = knownScreen()
                        val maxX = maxOf(0, screen.width - toggleSize)
                        val maxY = maxOf(0, screen.height - toggleSize)
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

    /** 按屏幕尺寸把 toggle 的尺寸/位置重置为设置按钮所在的格子（拿不到时回退到默认位置）。 */
    private fun placeToggleFromSettingsOrFallback(screen: ScreenGeometry) {
        val rect = settingsButtonScreenRect(screen)
        if (rect != null && rect.width() > 0 && rect.height() > 0) {
            toggleSize = maxOf(rect.width(), rect.height())
            toggleWinX = rect.left
            toggleWinY = rect.top
        } else {
            toggleSize = preferredToggleSizePx(screen)
            val (dx, dy) = defaultTogglePosition(toggleSize, screen)
            toggleWinX = dx
            toggleWinY = dy
        }
    }

    private fun clampTogglePosition() {
        val params = toggleParams ?: return
        val screen = knownScreen()
        val maxX = maxOf(0, screen.width - toggleSize)
        val maxY = maxOf(0, screen.height - toggleSize)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
        toggleWinX = params.x
        toggleWinY = params.y
        toggleButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /** Reads the settings-button grid position and moves the (hidden) toggle window there. */
    private fun repositionToggleFromSettings(screen: ScreenGeometry = knownScreen()) {
        val rect = settingsButtonScreenRect(screen) ?: run {
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
        val screen = knownScreen()
        val cell = screen.localWidth.toFloat() / activity.gamepadLayout.getGridCols()
        if (cell <= 0f) return
        val lx: Int
        val ly: Int
        if (screen.portrait) {
            lx = params.y
            ly = screen.width - params.x
        } else {
            lx = params.x
            ly = params.y
        }
        val maxCol = (activity.gamepadLayout.getGridCols() - pos.width).coerceAtLeast(0)
        val maxRow = ((screen.localHeight / cell).toInt() - pos.height).coerceAtLeast(0)
        val gx = (lx / cell).roundToInt().coerceIn(0, maxCol)
        // In portrait `ly` is the rotated rect's screen-left edge, i.e. the local *bottom*;
        // subtract the button height to get the top row back (otherwise the button lands a
        // whole button-width to the left on the next show).
        val gy = ((ly / cell) - pos.height).roundToInt().coerceIn(0, maxRow)
        activity.gamepadLayout.setButtonPositionQuiet(pos.id, pos.copy(x = gx, y = gy))
    }

    // ── Settings button (toggle inside the layout) ─────────

    private fun settingsButtonChild(): View? {
        val gl = activity.gamepadLayout
        // Iterate top-down so this matches bringSettingsToFront(): the frontmost settings
        // child is the one actually drawn (and hit-tested) on screen.
        for (i in gl.childCount - 1 downTo 0) {
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
            activity.attachSettingsButtonWheel(child)
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
     *  given screen. Works whether or not the controls window is shown. */
    private fun settingsButtonScreenRect(screen: ScreenGeometry): android.graphics.Rect? {
        val pos = activity.gamepadLayout.currentButtons
            .find { it.id == GamepadLayout.SETTINGS_BUTTON_ID } ?: return null
        val cell = screen.localWidth.toFloat() / activity.gamepadLayout.getGridCols()
        if (cell <= 0f) return null
        // Mirror GamepadLayout's child layout math (truncation, width relative to left) so the
        // toggle window lands exactly on the settings button instead of a rounded-off spot.
        val l = (pos.x * cell).toInt()
        val t = (pos.y * cell).toInt()
        val r = l + (pos.width * cell).toInt()
        val b = t + (pos.height * cell).toInt()
        return if (screen.portrait) {
            android.graphics.Rect(screen.width - b, l, screen.width - t, r)
        } else {
            android.graphics.Rect(l, t, r, b)
        }
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
                    val opacity = settings.floatingOpacity.coerceIn(0, 100) / 100f
                    if (isShown) activity.gamepadLayout.alpha = opacity
                    toggleButton?.alpha = opacity
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

    /** 当前屏幕尺寸：优先用探针窗口量到的真实值，退回系统信息（后台时可能已过期）。 */
    private fun knownScreen(): ScreenGeometry = overlaySize ?: screenGeometry()

    /**
     * 系统视角的屏幕尺寸快照（宽高已按旋转方向换算）。
     *
     * 只用于「探针窗口还没量出尺寸」的初始估算：应用退到后台/悬浮后 [Display.getRealSize]
     * 和 [WindowManager.getCurrentWindowMetrics] 都可能长期返回进入后台前的旧尺寸。
     *
     * 方向一律由尺寸本身推导（`portrait = height > width`），不混用 `getRotation()`：
     * 旋转瞬间两者可能不一致，混用会让「屏幕已横屏、悬浮层却按竖屏排版」。
     * 悬浮层的逻辑坐标系始终是横屏形状（长边 = [ScreenGeometry.localWidth]），
     * 竖屏时再把整层旋转 90°。
     */
    private fun screenGeometry(): ScreenGeometry {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return ScreenGeometry(bounds.width(), bounds.height())
                }
            }
        }
        val point = Point()
        @Suppress("DEPRECATION")
        currentDisplay().getRealSize(point)
        return ScreenGeometry(point.x, point.y)
    }

    private fun floatingOpacity(): Float =
        activity.viewModel.settings.value.floatingOpacity.coerceIn(0, 100) / 100f

    private fun preferredToggleSizePx(screen: ScreenGeometry): Int {
        val pos = activity.gamepadLayout.currentButtons
            .find { it.id == GamepadLayout.SETTINGS_BUTTON_ID }
        val longSide = screen.localWidth
        val size = if (pos != null) {
            (maxOf(pos.width, pos.height).toFloat() / activity.gamepadLayout.getGridCols() * longSide).toInt()
        } else {
            0
        }
        return if (size > 0) size else (48f * density).toInt()
    }

    private fun defaultTogglePosition(size: Int, screen: ScreenGeometry): Pair<Int, Int> {
        val margin = (16f * density).toInt()
        return (screen.width - size - margin) to (screen.height / 2 - size / 2)
    }

    private class ScreenGeometry(val width: Int, val height: Int) {

        val portrait: Boolean get() = height > width

        /** 悬浮层逻辑坐标系的宽度（横屏形状，长边）。 */
        val localWidth: Int get() = maxOf(width, height)

        /** 悬浮层逻辑坐标系的高度（横屏形状，短边）。 */
        val localHeight: Int get() = minOf(width, height)

        fun sameSizeAs(other: ScreenGeometry?): Boolean =
            other != null && other.width == width && other.height == height
    }

    private companion object {
        const val TAG = "GkmeFloating"
    }
}
