package com.zyz4.gkme.input

import com.zyz4.gkme.model.MouseGestureAction
import com.zyz4.gkme.model.TouchPoint
import kotlin.math.abs

/**
 * 实体手柄触摸板的纯手势识别器（最多两指）。
 *
 * 物理后端每帧给出一个稳定快照（`touches` 最多两个 slot），而不是 Android
 * `MotionEvent` 的 DOWN/MOVE/UP 序列。这里按快照之间的触点变化识别：
 *
 * - 单指滑动 / 双指滑动（位移超过 [MOVE_SLOP_UNITS] 才从点击转为滑动）
 * - 单指轻点 / 双指轻点（未移动且短于 [TAP_TIMEOUT_MS]）
 * - 双击后按住拖动（第二次按下即按住拖拽键，直到抬起）
 *
 * 触摸板按压已经是普通实体按键，因此这里不处理 `TOUCHPAD_CLICK`。
 *
 * 坐标系：`TouchPoint` 为后端统一后的 0..1919 / 0..942，坐标单位直接当作
 * 光标像素；`SCROLL_UNITS_PER_TOUCHPAD_UNIT` 把位移换算成滚轮单位。
 *
 * 该类不依赖 Android，方便 JVM 单元测试。
 */
class PhysicalTouchpadGestureRecognizer {

    /** 识别配置；由 [com.zyz4.gkme.model.PhysicalTouchpadSettings] 换算而来。 */
    data class Config(
        val mouseSensitivity: Float = 1f,
        val scrollSensitivity: Float = 1f,
        val invertScrollV: Boolean = false,
        val invertScrollH: Boolean = false,
        val singleTapAction: MouseGestureAction = MouseGestureAction.LEFT_CLICK,
        val twoFingerTapAction: MouseGestureAction = MouseGestureAction.RIGHT_CLICK,
        val doubleTapDragAction: MouseGestureAction = MouseGestureAction.LEFT_DRAG,
        val oneFingerSwipeAction: MouseGestureAction = MouseGestureAction.MOVE_CURSOR,
        val twoFingerSwipeAction: MouseGestureAction = MouseGestureAction.SCROLL,
    )

    /** 一次 [update] 产生的效果，按发生顺序排列。 */
    sealed interface Event {
        /** 光标位移（px）。 */
        data class Move(val dx: Int, val dy: Int) : Event

        /** 滚轮位移（滚轮单位）；正值向下 / 向右。 */
        data class Scroll(val vertical: Int, val horizontal: Int) : Event

        /** 一次轻点，View 层负责按下后延迟释放。 */
        data class Tap(val action: MouseGestureAction) : Event

        /** 开始按住某个鼠标键拖拽。 */
        data class DragStart(val action: MouseGestureAction) : Event

        /** 结束拖拽并释放按键。 */
        data class DragEnd(val action: MouseGestureAction) : Event
    }

    private var lastPoints: Map<Int, Pair<Int, Int>> = emptyMap()
    private var gestureDownTimeMs = 0L
    private var gestureTravel = 0f
    private var gestureMoved = false
    private var gestureMaxPointers = 0
    private var activeDrag = MouseGestureAction.NONE
    private var doubleTapArmed = false
    private var lastTapUpMs = -1L

    // 不足 1 px / 1 滚轮单位的余量，避免低速滑动被截断成 0。
    private var cursorAccumX = 0f
    private var cursorAccumY = 0f
    private var wheelAccumV = 0f
    private var wheelAccumH = 0f

    /**
     * 处理一个触摸快照。
     *
     * @param touches 后端给出的触点（可以包含 `active=false` 的占位 slot）
     * @param nowMs 单调时钟（毫秒）；仅用于点击 / 双击时间判定
     */
    fun update(
        touches: List<TouchPoint>,
        nowMs: Long,
        config: Config = Config(),
    ): List<Event> {
        val events = mutableListOf<Event>()
        val points = touches.asSequence()
            .filter { it.active }
            .associate { it.id to (it.x to it.y) }

        if (points.isEmpty()) {
            if (lastPoints.isNotEmpty()) finishGesture(nowMs, config, events)
            return events
        }

        if (lastPoints.isEmpty()) {
            beginGesture(points, nowMs, config, events)
            return events
        }

        if (points.size > gestureMaxPointers) gestureMaxPointers = points.size
        if (points.keys.any { it !in lastPoints } && doubleTapArmed) {
            // 第二根手指加入时取消“双击后拖动”，避免拖拽键卡住。
            doubleTapArmed = false
            endDrag(events)
        }

        // 只对上一帧已经存在的触点计算位移；新按下的手指从下一帧才开始贡献，
        // 这样不会因为 slot 分配或新触点出现而突然跳一大段距离。
        var sumDx = 0f
        var sumDy = 0f
        var movedPointers = 0
        for ((id, point) in points) {
            val previous = lastPoints[id] ?: continue
            sumDx += point.first - previous.first
            sumDy += point.second - previous.second
            movedPointers++
        }
        val dx = if (movedPointers > 0) sumDx / movedPointers else 0f
        val dy = if (movedPointers > 0) sumDy / movedPointers else 0f
        lastPoints = points

        if (dx == 0f && dy == 0f) return events

        gestureTravel += abs(dx) + abs(dy)
        if (!gestureMoved && gestureTravel > MOVE_SLOP_UNITS) gestureMoved = true
        if (!gestureMoved) return events

        val action = if (doubleTapArmed && points.size <= 1) {
            config.doubleTapDragAction
        } else {
            swipeActionFor(points.size, config)
        }
        applySwipe(action, dx, dy, config, events)
        return events
    }

    /** 模式切换 / 手柄断开时调用：释放可能按住的拖拽键。 */
    fun reset(): List<Event> {
        val events = mutableListOf<Event>()
        endDrag(events)
        clearGestureState()
        lastTapUpMs = -1L
        return events
    }

    private fun beginGesture(
        points: Map<Int, Pair<Int, Int>>,
        nowMs: Long,
        config: Config,
        events: MutableList<Event>,
    ) {
        gestureDownTimeMs = nowMs
        gestureTravel = 0f
        gestureMoved = false
        gestureMaxPointers = points.size
        activeDrag = MouseGestureAction.NONE
        cursorAccumX = 0f
        cursorAccumY = 0f
        wheelAccumV = 0f
        wheelAccumH = 0f

        val eligibleDoubleTap = points.size == 1 &&
            config.doubleTapDragAction.isDrag &&
            lastTapUpMs >= 0 &&
            nowMs - lastTapUpMs in 0..DOUBLE_TAP_WINDOW_MS
        doubleTapArmed = eligibleDoubleTap
        lastTapUpMs = -1L
        if (eligibleDoubleTap) startDrag(config.doubleTapDragAction, events)
        lastPoints = points
    }

    private fun finishGesture(
        nowMs: Long,
        config: Config,
        events: MutableList<Event>,
    ) {
        endDrag(events)
        val duration = nowMs - gestureDownTimeMs
        val wasTap = !gestureMoved && duration in 0..TAP_TIMEOUT_MS
        if (wasTap) {
            if (gestureMaxPointers <= 1) {
                if (doubleTapArmed) {
                    // 双击的第二击没有滑动：松开拖拽键后再补一次单击。
                    emitTap(config.singleTapAction, events)
                    lastTapUpMs = -1L
                } else {
                    emitTap(config.singleTapAction, events)
                    lastTapUpMs = nowMs
                }
            } else if (gestureMaxPointers == 2) {
                emitTap(config.twoFingerTapAction, events)
                lastTapUpMs = -1L
            } else {
                lastTapUpMs = -1L
            }
        } else {
            lastTapUpMs = -1L
        }
        clearGestureState()
    }

    private fun emitTap(action: MouseGestureAction, events: MutableList<Event>) {
        if (action != MouseGestureAction.NONE) events += Event.Tap(action)
    }

    private fun applySwipe(
        action: MouseGestureAction,
        dx: Float,
        dy: Float,
        config: Config,
        events: MutableList<Event>,
    ) {
        if (action.isDrag) {
            if (activeDrag != action) {
                endDrag(events)
                startDrag(action, events)
            }
            addCursor(dx, dy, config, events)
            return
        }

        if (activeDrag != MouseGestureAction.NONE) endDrag(events)
        when (action) {
            MouseGestureAction.MOVE_CURSOR -> addCursor(dx, dy, config, events)
            MouseGestureAction.SCROLL -> addScroll(dx, dy, config, events)
            else -> Unit
        }
    }

    private fun addCursor(
        dx: Float,
        dy: Float,
        config: Config,
        events: MutableList<Event>,
    ) {
        cursorAccumX += dx * config.mouseSensitivity
        cursorAccumY += dy * config.mouseSensitivity
        val ix = cursorAccumX.toInt()
        val iy = cursorAccumY.toInt()
        cursorAccumX -= ix
        cursorAccumY -= iy
        if (ix != 0 || iy != 0) events += Event.Move(ix, iy)
    }

    private fun addScroll(
        dx: Float,
        dy: Float,
        config: Config,
        events: MutableList<Event>,
    ) {
        var vertical = dy * config.scrollSensitivity * SCROLL_UNITS_PER_TOUCHPAD_UNIT
        // 与屏幕鼠标板一致：横向默认按“自然滚动”反向（手指右滑 → 内容向左），
        // 勾选反转横向滚动后恢复为同向。
        var horizontal = -dx * config.scrollSensitivity * SCROLL_UNITS_PER_TOUCHPAD_UNIT
        if (config.invertScrollV) vertical = -vertical
        if (config.invertScrollH) horizontal = -horizontal
        wheelAccumV += vertical
        wheelAccumH += horizontal
        val iv = wheelAccumV.toInt()
        val ih = wheelAccumH.toInt()
        wheelAccumV -= iv
        wheelAccumH -= ih
        if (iv != 0 || ih != 0) events += Event.Scroll(iv, ih)
    }

    private fun startDrag(action: MouseGestureAction, events: MutableList<Event>) {
        if (!action.isDrag) return
        activeDrag = action
        events += Event.DragStart(action)
    }

    private fun endDrag(events: MutableList<Event>) {
        if (activeDrag == MouseGestureAction.NONE) return
        events += Event.DragEnd(activeDrag)
        activeDrag = MouseGestureAction.NONE
    }

    private fun swipeActionFor(count: Int, config: Config): MouseGestureAction = when {
        count == 1 -> config.oneFingerSwipeAction
        count >= 2 -> config.twoFingerSwipeAction
        else -> MouseGestureAction.NONE
    }

    private fun clearGestureState() {
        lastPoints = emptyMap()
        gestureDownTimeMs = 0L
        gestureTravel = 0f
        gestureMoved = false
        gestureMaxPointers = 0
        activeDrag = MouseGestureAction.NONE
        doubleTapArmed = false
        cursorAccumX = 0f
        cursorAccumY = 0f
        wheelAccumV = 0f
        wheelAccumH = 0f
    }

    companion object {
        /** 位移累计超过该值后，手势从点击切换为滑动。 */
        const val MOVE_SLOP_UNITS = 8f

        /** 按住短于该值且未移动才算轻点。 */
        const val TAP_TIMEOUT_MS = 200L

        /** 两次单指轻点的间隔上限。 */
        const val DOUBLE_TAP_WINDOW_MS = 250L

        /** 每个触摸板坐标单位产生的滚轮单位（100% 灵敏度时）。 */
        const val SCROLL_UNITS_PER_TOUCHPAD_UNIT = 0.5f
    }
}
