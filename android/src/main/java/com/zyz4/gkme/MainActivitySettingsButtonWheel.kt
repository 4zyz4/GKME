package com.zyz4.gkme

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.zyz4.gkme.model.AppSettings
import com.zyz4.gkme.model.ConnectionMode
import com.zyz4.gkme.model.ControlType
import com.zyz4.gkme.model.SettingsButtonAction
import com.zyz4.gkme.model.SettingsButtonDirection
import com.zyz4.gkme.service.ConnectionPhase
import com.zyz4.gkme.view.SettingsButtonWheelView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** 轮盘入场/退场动画时长，与设置面板等浮层的开关动画保持一致。 */
private const val WHEEL_OPEN_DURATION_MS = 200L
private const val WHEEL_CLOSE_DURATION_MS = 150L

/** 轮盘收起/展开时的缩放：入场从放大状态收缩到原尺寸，退场缩小到隐藏尺寸。 */
private const val WHEEL_OPEN_SCALE = 1.12f
private const val WHEEL_HIDDEN_SCALE = 0.72f

/**
 * 给布局里的设置按钮挂上轮盘手势：
 * - 按下时在屏幕中心显示轮盘；
 * - 手指相对按钮的位移决定高亮方向，松手执行对应操作；
 * - 位移未超过阈值（原地松手）时执行「打开设置」。
 *
 * 悬浮模式下 [FloatingModeController] 会用自己的触摸监听覆盖这里，因此悬浮模式不启用轮盘。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun MainActivity.attachSettingsButtonWheel(view: View) {
    val a = this
    var downX = 0f
    var downY = 0f
    var wheelActive = false
    var wheelShown = false
    var selected: SettingsButtonDirection? = null
    // 阈值同时考虑系统 touch slop 和固定 18dp，避免轻点被误判成滑动。
    val threshold = maxOf(
        ViewConfiguration.get(a).scaledTouchSlop * 2f,
        18f * a.resources.displayMetrics.density,
    )

    view.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val settings = a.viewModel.settings.value
                // 未配置任何操作时保持原来的普通点击行为。
                if (!hasSettingsButtonWheelActions(settings) || a.isScreenOff || a.inSettings) {
                    wheelActive = false
                    wheelShown = false
                    v.isPressed = true
                    false
                } else {
                    downX = e.x
                    downY = e.y
                    selected = null
                    wheelActive = true
                    wheelShown = false
                    v.isPressed = true
                    // 轮盘在手指移出按钮区域后才显示，普通点击不会闪出轮盘。
                    true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!wheelActive) return@setOnTouchListener false
                val dx = e.x - downX
                val dy = e.y - downY
                if (!wheelShown && isOutsideButton(v, e.x, e.y)) {
                    val settings = a.viewModel.settings.value
                    a.showSettingsButtonWheel(settings.settingsButtonActions, settings.settingsButtonFourWay)
                    wheelShown = true
                }
                if (wheelShown) {
                    a.settingsButtonWheel?.setTouchOffset(dx, dy)
                    val direction = resolveSettingsButtonDirection(
                        dx = dx,
                        dy = dy,
                        fourWay = a.viewModel.settings.value.settingsButtonFourWay,
                        threshold = threshold,
                    )
                    if (direction != selected) {
                        selected = direction
                        a.settingsButtonWheel?.selectedDirection = direction
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                if (!wheelActive) return@setOnTouchListener false
                wheelActive = false
                if (!wheelShown) {
                    // 手指没有移出设置按钮区域：按普通点击处理。
                    selected = null
                    v.performClick()
                    return@setOnTouchListener true
                }
                wheelShown = false
                a.hideSettingsButtonWheel()
                // 中心（未选择方向）与未配置操作的方向都表示取消，不执行任何操作。
                val action = selected
                    ?.let { a.viewModel.settings.value.settingsButtonActions[it] }
                    ?: SettingsButtonAction.NONE
                selected = null
                // 切换布局会重建按钮 View，延后一帧再执行，避免在触摸分发过程中移除自己。
                if (action != SettingsButtonAction.NONE) v.post { a.executeSettingsButtonAction(action) }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (!wheelActive) return@setOnTouchListener false
                wheelActive = false
                if (wheelShown) a.hideSettingsButtonWheel()
                wheelShown = false
                selected = null
                true
            }

            else -> wheelActive
        }
    }
}

private fun isOutsideButton(view: View, x: Float, y: Float): Boolean =
    x < 0f || y < 0f || x >= view.width || y >= view.height

private fun hasSettingsButtonWheelActions(settings: AppSettings): Boolean =
    settings.settingsButtonActions.any { (direction, action) ->
        action != SettingsButtonAction.NONE &&
            (!settings.settingsButtonFourWay || !direction.diagonal)
    }

private fun resolveSettingsButtonDirection(
    dx: Float,
    dy: Float,
    fourWay: Boolean,
    threshold: Float,
): SettingsButtonDirection? {
    if (hypot(dx.toDouble(), dy.toDouble()) < threshold) return null
    // 屏幕坐标 y 向下，取反后 0° = 向右、90° = 向上。
    var angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
    if (angle < 0f) angle += 360f
    return SettingsButtonDirection.entries
        .filter { !fourWay || !it.diagonal }
        .minByOrNull { angleDistance(it.angleDeg, angle) }
}

private fun angleDistance(a: Float, b: Float): Float {
    val diff = abs(a - b) % 360f
    return if (diff > 180f) 360f - diff else diff
}

internal fun MainActivity.showSettingsButtonWheel(
    actions: Map<SettingsButtonDirection, SettingsButtonAction>,
    fourWay: Boolean,
) {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    val wheel = settingsButtonWheel ?: SettingsButtonWheelView(this).also {
        it.visibility = View.GONE
        root.addView(
            it,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        settingsButtonWheel = it
    }
    wheel.bind(actions, fourWay)
    // 关闭动画可能还在进行：先取消（避免它的结束回调把刚显示的轮盘藏起来），再从放大状态播放入场动画。
    wheel.animate().cancel()
    wheel.alpha = 0f
    wheel.scaleX = WHEEL_OPEN_SCALE
    wheel.scaleY = WHEEL_OPEN_SCALE
    wheel.visibility = View.VISIBLE
    wheel.bringToFront()
    // 缩小到原尺寸 + 淡入。
    wheel.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(WHEEL_OPEN_DURATION_MS)
        .setInterpolator(easeOutQuint())
        .start()
}

internal fun MainActivity.hideSettingsButtonWheel() {
    val wheel = settingsButtonWheel ?: return
    wheel.animate().cancel()
    if (wheel.visibility != View.VISIBLE) return
    // 缩小 + 淡出，结束后再真正隐藏。
    wheel.animate()
        .alpha(0f)
        .scaleX(WHEEL_HIDDEN_SCALE)
        .scaleY(WHEEL_HIDDEN_SCALE)
        .setDuration(WHEEL_CLOSE_DURATION_MS)
        .setInterpolator(easeOutQuint())
        .withEndAction { wheel.visibility = View.GONE }
        .start()
}

internal fun MainActivity.executeSettingsButtonAction(action: SettingsButtonAction) {
    when (action) {
        SettingsButtonAction.NONE -> Unit
        SettingsButtonAction.OPEN_SETTINGS -> showSettings()
        SettingsButtonAction.PREVIOUS_LAYOUT -> switchLayoutBy(-1)
        SettingsButtonAction.NEXT_LAYOUT -> switchLayoutBy(1)
        SettingsButtonAction.TOGGLE_SERVICE -> toggleService()
        SettingsButtonAction.EDIT_LAYOUT -> enterLayoutEditMode()
    }
}

/** 按布局预设列表的顺序循环切换，首尾相接。 */
internal fun MainActivity.switchLayoutBy(delta: Int) {
    val infos = viewModel.presetInfos.value
    if (infos.size < 2) return
    val current = viewModel.settings.value.currentPresetName
    val currentIndex = infos.indexOfFirst { it.name == current }.takeIf { it >= 0 } ?: 0
    val nextIndex = ((currentIndex + delta) % infos.size + infos.size) % infos.size
    loadPresetByName(infos[nextIndex].name)
}

internal fun MainActivity.enterLayoutEditMode() {
    if (gamepadLayout.isEditModeActive()) return
    viewModel.updateEditMode(true)
    hideSettings()
    applyPreset(viewModel.currentPreset.value)
    gamepadLayout.enterEditMode()
}

/** 与设置页「连接」里的启动/停止按钮一致：服务未运行则启动，运行中则停止。 */
internal fun MainActivity.toggleService() {
    val st = viewModel.connectionState.value
    if (st.phase != ConnectionPhase.IDLE) {
        val wasLocal = viewModel.settings.value.connectionMode == ConnectionMode.LOCAL
        viewModel.stopServer()
        if (wasLocal) exitFloatingMode()
        return
    }
    val s = viewModel.settings.value
    when {
        s.connectionMode == ConnectionMode.LOCAL -> startLocalMode()
        s.connectionMode == ConnectionMode.WIFI && s.controlType == ControlType.CONTROLLED -> launchControlledMode()
        s.connectionMode == ConnectionMode.BLUETOOTH -> startBluetoothModeWithPermission()
        s.connectionMode == ConnectionMode.USB -> checkUsbAdbAndStart()
        else -> viewModel.startServer()
    }
}
