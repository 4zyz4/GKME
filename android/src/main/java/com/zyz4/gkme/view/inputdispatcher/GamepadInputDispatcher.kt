package com.zyz4.gkme.view.inputdispatcher

import com.zyz4.gkme.view.inputdispatcher.EditCommand
import com.zyz4.gkme.view.inputdispatcher.EditCommand.MoveButton
import com.zyz4.gkme.view.inputdispatcher.EditCommand.MoveFollowArea
import com.zyz4.gkme.view.inputdispatcher.EditCommand.ResizeButton
import com.zyz4.gkme.view.inputdispatcher.EditCommand.ResizeFollowArea
import com.zyz4.gkme.view.inputdispatcher.EditDispatchResult
import com.zyz4.gkme.view.inputdispatcher.ChildDragStart
import com.zyz4.gkme.view.inputdispatcher.ChildResizeStart
import com.zyz4.gkme.view.inputdispatcher.EditModeState
import com.zyz4.gkme.view.inputdispatcher.FollowAreaDragStart
import com.zyz4.gkme.view.inputdispatcher.FollowAreaResizeStart
import com.zyz4.gkme.view.inputdispatcher.FollowAreaResult
import com.zyz4.gkme.view.inputdispatcher.FollowAreaStrategy
import com.zyz4.gkme.view.inputdispatcher.HitResolver
import com.zyz4.gkme.view.inputdispatcher.InteractionResult
import com.zyz4.gkme.view.inputdispatcher.InputDispatcher
import com.zyz4.gkme.view.inputdispatcher.LayoutEngine
import com.zyz4.gkme.view.inputdispatcher.OldSlotState
import com.zyz4.gkme.view.inputdispatcher.PinchResizeStart
import com.zyz4.gkme.view.inputdispatcher.PinchResizeTarget
import com.zyz4.gkme.view.inputdispatcher.Pointer
import com.zyz4.gkme.view.inputdispatcher.RawTouchEvent
import com.zyz4.gkme.view.inputdispatcher.SlotMatcher.SlotResult
import com.zyz4.gkme.view.inputdispatcher.SwipeTriggerStrategy
import com.zyz4.gkme.view.inputdispatcher.TouchpadClickDetector
import kotlin.math.roundToInt

/**
 * The composition root of the input dispatcher.
 *
 * Composes all pure strategy objects and implements the two methods of
 * InputDispatcher:
 *   - dispatchInteraction() — run mode (button press, swipe, follow-area, touchpad)
 *   - dispatchEdit() — edit mode (drag, resize, follow-area-adjust)
 */
class GamepadInputDispatcher(
    private val layoutEngine: LayoutEngine,
    private val hitResolver: HitResolver,
    private val followAreaStrategy: FollowAreaStrategy,
    private val swipeTriggerStrategy: SwipeTriggerStrategy,
    private val slotMatcher: SlotMatcher,
    private val clickDetector: TouchpadClickDetector,
) : InputDispatcher {

    /** 当前布局的内部网格列数/行数；由 GamepadLayout 在布局时更新。 */
    var gridCols: Int = LayoutEngine.GRID_COLS
    var gridRows: Int = LayoutEngine.GRID_COLS

    // ──────────────────────── Run mode ────────────────────────

    override fun dispatchInteraction(
        event: RawTouchEvent,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        childBounds: Map<String, android.graphics.Rect>,
        prevSwipeActive: Set<String>,
        prevSlotState: OldSlotState,
        lastButtonState: Int,
        cellW: Float,
        cellH: Float,
        isSwipeMode: Boolean,
    ): InteractionResult {

        val action = event.action
        val pointers = event.pointers

        // Terminal touchpad events (UP / CANCEL): clear slots and release all
        if (action == android.view.MotionEvent.ACTION_UP ||
            action == android.view.MotionEvent.ACTION_CANCEL) {
            return InteractionResult(
                pressedIds = emptySet(),
                releasedIds = prevSwipeActive,
                followAreaActions = emptyList(),
                slotAssignment = com.zyz4.gkme.view.inputdispatcher.SlotAssignment(
                    -1, 0f, 0f, -1, 0f, 0f),
                clickResult = null,
                newSlotState = OldSlotState(),
                newLastButtonState = event.buttonState,
                newSwipeActive = emptySet(),
            )
        }

        // Slot assignment
        var releasedCandidateIndex: Int? = null
        if (action == android.view.MotionEvent.ACTION_POINTER_UP) {
            releasedCandidateIndex = event.actionIndex
        }
        val candidates = slotMatcher.buildCandidates(pointers, releasedCandidateIndex)
        val slotResult: SlotResult = SlotMatcher.assign(prevSlotState, candidates, releasedCandidateIndex)

        // Click detection
        val clickResult = clickDetector.detect(lastButtonState, event.buttonState)

        // Swipe triggers or follow-area — mutually exclusive paths
        var swipeResult = SwipeResult()
        var followAreaActions = mutableListOf<com.zyz4.gkme.view.inputdispatcher.FollowAreaAction>()

        if (isSwipeMode) {
            swipeResult = swipeTriggerStrategy.evaluate(
                buttons = buttons,
                childBounds = childBounds,
                pointers = pointers,
                previouslyActive = prevSwipeActive,
            )
        } else {
            val faResult = followAreaStrategy.evaluate(
                buttons = buttons,
                cellW = cellW,
                cellH = cellH,
                pointers = pointers,
            )
            if (faResult.activatedButtonIds.isNotEmpty()) {
                followAreaActions = faResult.activatedButtonIds
                    .map { id -> com.zyz4.gkme.view.inputdispatcher.FollowAreaAction(id, com.zyz4.gkme.view.inputdispatcher.FollowAreaAction.Type.ACTIVATE) }
                    .toMutableList()
            }
        }

        val newSwipeActive = if (isSwipeMode) {
            prevSwipeActive + swipeResult.presses - swipeResult.releases
        } else {
            prevSwipeActive
        }

        return InteractionResult(
            pressedIds = swipeResult.presses,
            releasedIds = swipeResult.releases,
            followAreaActions = followAreaActions,
            slotAssignment = slotResult.slotAssignment,
            clickResult = clickResult,
            newSlotState = slotResult.newState,
            newLastButtonState = event.buttonState,
            newSwipeActive = newSwipeActive,
        )
    }

    // ──────────────────────── Edit mode ───────────────────────

override fun dispatchEdit(
        event: RawTouchEvent,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        currentButtonBounds: Map<String, android.graphics.Rect>,
        children: List<android.view.View>,
        currentState: EditModeState,
        cellW: Float,
        cellH: Float,
        density: Float,
        selectedButtonId: String?,
        selectedButtonIds: Set<String>,
    ): EditDispatchResult {

        val action = event.action
        val x = event.pointers.getOrElse(0) { Pointer(-1, 0f, 0f) }.x
        val y = event.pointers.getOrElse(0) { Pointer(-1, 0f, 0f) }.y

        return when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                handleDown(x, y, buttons, currentButtonBounds, children, currentState.copy(pinchResizeStart = null), cellW, cellH, density, selectedButtonId, selectedButtonIds)
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event.pointers, buttons, currentButtonBounds, currentState, selectedButtonIds)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                handleMove(x, y, event.pointers, buttons, currentButtonBounds, children, currentState, cellW, cellH, density, selectedButtonId)
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event.actionIndex, event.pointers, buttons, currentState)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                EditDispatchResult(
                    commands = emptyList(),
                    newState = currentState.copy(
                        followAreaDragStart = null,
                        followAreaResizeStart = null,
                        childDragStart = null,
                        childResizeStart = null,
                        unselectedDragDownGrid = null,
                        pinchResizeStart = null,
                    ),
                )
            }
            else -> EditDispatchResult(emptyList(), currentState)
        }
    }

    // ──────── Internal handlers ────────

    private fun handleDown(
        x: Float, y: Float,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        bounds: Map<String, android.graphics.Rect>,
        children: List<android.view.View>,
        state: EditModeState,
        cellW: Float,
        cellH: Float,
        densityPx: Float,
        selectedButtonId: String? = null,
        selectedButtonIds: Set<String> = emptySet(),
    ): EditDispatchResult {
        val commands = mutableListOf<EditCommand>()
        val handleHitPx = 16f * densityPx

        if (state.isAdjustingFollowArea && state.adjustingFollowAreaId != null) {
            val pos = buttons.find { it.id == state.adjustingFollowAreaId } ?: return EditDispatchResult(commands, state)

            if (isOnFollowAreaHandle(x, y, pos, cellW, handleHitPx)) {
                commands += ResizeFollowArea(state.adjustingFollowAreaId!!, pos.followAreaW, pos.followAreaH)
                return EditDispatchResult(commands, state.copy(
                    followAreaResizeStart = FollowAreaResizeStart(
                        state.adjustingFollowAreaId!!, pos.followAreaW, pos.followAreaH,
                        (x / cellW).toInt(), (y / cellH).toInt()),
                ))
            }
            if (isInFollowArea(x, y, pos, cellW)) {
                commands += MoveFollowArea(state.adjustingFollowAreaId!!, pos.followAreaX, pos.followAreaY)
                return EditDispatchResult(commands, state.copy(
                    followAreaDragStart = FollowAreaDragStart(
                        state.adjustingFollowAreaId!!, pos.followAreaX, pos.followAreaY,
                        (x / cellW).toInt(), (y / cellH).toInt()),
                ))
            }
            // When adjusting follow-area, ignore button hits outside the follow-area rect
            // to prevent button selection / drag from triggering
            return EditDispatchResult(commands, state)
        }

        for ((id, rect) in bounds) {
            val pos = buttons.find { it.id == id } ?: continue
            if (isOnVisualHandle(x, y, pos, rect, handleHitPx, cellW, cellH)) {
                commands += ResizeButton(id, pos.width, pos.height)
                val vb = visualBounds(pos, cellW, cellH)
                val localX = (x - vb[0]) / cellW
                val localY = (y - vb[1]) / cellH
                return EditDispatchResult(commands, state.copy(
                    childResizeStart = ChildResizeStart(id, pos.width, pos.height,
                        (x / cellW).toInt(), (y / cellH).toInt(),
                        localX, localY),
                ))
            }
        }

        // 1) 命中测试：优先保持多选组内的控件，其次顶层的控件。
        //    注意：按在空白区域时不再拖动“当前选中”的控件——空白按下用于框选。
        val allHitIds = hitResolver.resolve(x, y, buttons, bounds)

        val hitId: String? = allHitIds.firstOrNull { it in selectedButtonIds }
            ?: allHitIds.firstOrNull()
        
        if (hitId != null) {
            val rect = bounds[hitId]
            val pos = buttons.find { it.id == hitId }
            
            if (rect != null && pos != null) {
                // 多选拖拽：若按下的控件属于多选集合，整组一起移动。
                val groupIds = if (hitId in selectedButtonIds && selectedButtonIds.size > 1) {
                    selectedButtonIds.filter { id -> buttons.any { it.id == id && it.visible } }
                } else {
                    emptyList()
                }
                val groupStartGrid = if (groupIds.size > 1) {
                    groupIds.mapNotNull { id ->
                        buttons.find { it.id == id }?.let { id to (it.x to it.y) }
                    }.toMap()
                } else {
                    emptyMap()
                }

                commands += MoveButton(hitId, pos.x, pos.y)
                val isUnselectedDrag = groupStartGrid.isEmpty() && selectedButtonId != hitId
                return EditDispatchResult(commands, if (isUnselectedDrag) {
                    state.copy(
                        childDragStart = ChildDragStart(
                            hitId, x - rect.left.toFloat(), y - rect.top.toFloat(), groupStartGrid,
                        ),
                        unselectedDragDownGrid = Pair((x / cellW).toInt(), (y / cellH).toInt()),
                    )
                } else {
                    state.copy(
                        childDragStart = ChildDragStart(
                            hitId, x - rect.left.toFloat(), y - rect.top.toFloat(), groupStartGrid,
                        ),
                    )
                })
            } else if (hitId == selectedButtonId && pos != null) {
                // Selected button has no pixel bounds (possibly not yet laid out)
                // Create bounds from grid coordinates so drag can still work
                val gridLeft = pos.x * cellW
                val gridTop = pos.y * cellH
                val gridRight = (pos.x + pos.width) * cellW
                val gridBottom = (pos.y + pos.height) * cellH
                val fakeRect = android.graphics.Rect(gridLeft.toInt(), gridTop.toInt(), gridRight.toInt(), gridBottom.toInt())
                
                commands += MoveButton(hitId, pos.x, pos.y)
                // At this point hitId == selectedButtonId is always true (guaranteed by outer condition)
                // Selected button has no pixel bounds — use grid-based rect for drag offset
                return EditDispatchResult(commands, state.copy(
                    childDragStart = ChildDragStart(hitId, x - fakeRect.left.toFloat(), y - fakeRect.top.toFloat()),
                ))
            }
        }

        return EditDispatchResult(commands, state)
    }

    private fun handleMove(
        x: Float, y: Float,
        pointers: List<Pointer>,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        bounds: Map<String, android.graphics.Rect>,
        children: List<android.view.View>,
        state: EditModeState,
        cellW: Float,
        cellH: Float,
        densityPx: Float,
        selectedButtonId: String? = null,
    ): EditDispatchResult {
        val handleHitPx = 16f * densityPx
        val commands = mutableListOf<EditCommand>()

        // 双指缩放优先：矩形控件按 x/y 轴向指距独立缩放，其余控件等比缩放。
        state.pinchResizeStart?.let { start ->
            return handlePinchMove(pointers, buttons, state, start, cellW, cellH, densityPx)
        }

        if (state.followAreaResizeStart != null) {
            val fs = state.followAreaResizeStart
            val gx = (x / cellW).toInt().coerceAtLeast(0)
            val gy = (y / cellH).toInt().coerceAtLeast(0)
            var newW = (fs.startW + gx - fs.resizeStartGridX).coerceAtLeast(1)
            var newH = (fs.startH + gy - fs.resizeStartGridY).coerceAtLeast(1)
            val pos = buttons.find { it.id == fs.buttonId } ?: return EditDispatchResult(commands, state)
            if (isTouchpadId(pos.id) && pos.followAreaEnabled) {
                newW = newW.coerceAtMost(pos.followAreaW)
                newH = newH.coerceAtMost(pos.followAreaH)
            }
            commands += ResizeFollowArea(fs.buttonId, newW, newH)
            return EditDispatchResult(commands, state)
        }

        if (state.followAreaDragStart != null) {
            val ds = state.followAreaDragStart
            var newX = (ds.startX + (x / cellW).toInt() - ds.dragStartGridX)
            var newY = (ds.startY + (y / cellH).toInt() - ds.dragStartGridY)
            val pos = buttons.find { it.id == ds.buttonId } ?: return EditDispatchResult(commands, state)
            if (isTouchpadId(pos.id) && pos.followAreaEnabled) {
                newX = newX.coerceIn(0, (gridCols - pos.followAreaW).coerceAtLeast(0))
                newY = newY.coerceIn(0, if (cellW > 0f) (gridRows - pos.followAreaH).coerceAtLeast(0) else Int.MAX_VALUE)
            }
            commands += MoveFollowArea(ds.buttonId, newX, newY)
            return EditDispatchResult(commands, state)
        }

        if (state.childResizeStart != null) {
            val rs = state.childResizeStart
            val gx = (x / cellW).toInt().coerceAtLeast(0)
            val gy = (y / cellH).toInt().coerceAtLeast(0)
            val screenDx = (gx - rs.resizeStartGridX).toFloat()
            val screenDy = (gy - rs.resizeStartGridY).toFloat()
            val oldPos = buttons.find { it.id == rs.buttonId } ?: return EditDispatchResult(commands, state)

            // The visual top-left is always anchored at (pos.x, pos.y) and the handle sits at the
            // visual bottom-right. Track the finger in screen space, then map back to local
            // width/height (90/270 swap the on-screen width/height for non-aspect-locked buttons).
            val isSwapped = !oldPos.lockAspect && (oldPos.rotation % 360 in listOf(90, 270))
            val screenW = (rs.localStartX + screenDx).coerceAtLeast(1f)
            val screenH = (rs.localStartY + screenDy).coerceAtLeast(1f)
            var nw = if (isSwapped) screenH else screenW
            var nh = if (isSwapped) screenW else screenH
            if (oldPos.lockAspect) {
                val side = maxOf(nw, nh); nw = side; nh = side
            }
            if (rs.buttonId == SETTINGS_BUTTON_ID) {
                nw = nw.coerceAtMost((gridCols - oldPos.x).toFloat())
                nh = nh.coerceAtMost((gridRows - oldPos.y).toFloat())
                if (oldPos.lockAspect) { val s = minOf(nw, nh); nw = s; nh = s }
            }
            commands += ResizeButton(rs.buttonId, nw.toInt(), nh.toInt())
            return EditDispatchResult(commands, state)
        }

        if (state.childDragStart != null) {
            val ds = state.childDragStart
            var gridX = ((x - ds.offsetX) / cellW).toInt().coerceIn(0, gridCols - 1)
            var gridY = ((y - ds.offsetY) / cellH).toInt().coerceAtLeast(0)
            val pos = buttons.find { it.id == ds.buttonId } ?: return EditDispatchResult(commands, state)

            // 多选批量拖拽：以主控件位移为准，整组保持相对位置一起移动。
            val groupStart = ds.groupStartGrid
            if (groupStart.size > 1) {
                val primaryStart = groupStart[ds.buttonId]
                if (primaryStart != null) {
                    val deltaX = (gridX - primaryStart.first).toInt()
                    val deltaY = (gridY - primaryStart.second).toInt()
                    if (deltaX == 0 && deltaY == 0) {
                        return EditDispatchResult(commands, state)
                    }
                    for ((id, start) in groupStart) {
                        val target = buttons.find { it.id == id } ?: continue
                        val newX = (start.first + deltaX).coerceIn(0, (gridCols - 1).coerceAtLeast(0))
                        val newY = (start.second + deltaY).coerceAtLeast(0)
                        commands += MoveButton(id, newX, newY)
                        if (isTouchpadId(id) && target.followAreaEnabled) {
                            commands += MoveFollowArea(
                                id,
                                target.followAreaX + (newX - target.x),
                                target.followAreaY + (newY - target.y),
                            )
                        }
                    }
                    return EditDispatchResult(commands, state)
                }
            }

            if (state.unselectedDragDownGrid != null) {
                val (downGx, downGy) = state.unselectedDragDownGrid
                val dx = kotlin.math.abs(gridX - downGx)
                val dy = kotlin.math.abs(gridY - downGy)
                // 用约 8dp 的触摸容差换算成当前坐标单位。低密度网格（如 30 列）的
                // 单元格可达数十像素，固定 3 单位会造成过大的拖动死区，因此向上取整并保底 1 单位。
                val slopUnits = kotlin.math.ceil(8f * densityPx / cellW.coerceAtLeast(1f))
                    .toInt().coerceAtLeast(1)
                if (dx < slopUnits && dy < slopUnits) {
                    return EditDispatchResult(commands, state)
                }
            }
            if (isTouchpadId(ds.buttonId) && pos.followAreaEnabled) {
                commands += MoveButton(ds.buttonId, gridX, gridY)
                commands += MoveFollowArea(
                    ds.buttonId,
                    pos.followAreaX + (gridX - pos.x),
                    pos.followAreaY + (gridY - pos.y),
                )
            } else {
                commands += MoveButton(ds.buttonId, gridX, gridY)
            }
            return EditDispatchResult(commands, state)
        }

        return EditDispatchResult(commands, state)
    }

    // ──────── 双指缩放 ────────

    private fun handlePointerDown(
        pointers: List<Pointer>,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        bounds: Map<String, android.graphics.Rect>,
        state: EditModeState,
        selectedButtonIds: Set<String>,
    ): EditDispatchResult {
        // 调整扩展区域时双指缩放不参与，避免抢走跟随区手柄。
        if (state.isAdjustingFollowArea || state.pinchResizeStart != null) {
            return EditDispatchResult(emptyList(), state)
        }
        val distance = pinchDistance(pointers)
        if (distance < 1f) return EditDispatchResult(emptyList(), state)

        val targetIds = resolvePinchTargetIds(pointers, buttons, bounds, state, selectedButtonIds)
        if (targetIds.isEmpty()) return EditDispatchResult(emptyList(), state)

        val targets = targetIds.mapNotNull { id ->
            buttons.find { it.id == id }?.let {
                PinchResizeTarget(id, it.x, it.y, it.width, it.height)
            }
        }
        if (targets.isEmpty()) return EditDispatchResult(emptyList(), state)

        return EditDispatchResult(
            commands = emptyList(),
            newState = state.copy(
                pinchResizeStart = PinchResizeStart(
                    targets = targets,
                    startDistance = distance,
                    startSpanX = pinchSpanX(pointers),
                    startSpanY = pinchSpanY(pointers),
                    startFocusX = pinchFocusX(pointers),
                    startFocusY = pinchFocusY(pointers),
                ),
                childDragStart = null,
                childResizeStart = null,
                followAreaDragStart = null,
                followAreaResizeStart = null,
                unselectedDragDownGrid = null,
            ),
        )
    }

    /**
     * 第二根手指落下时选择缩放目标：
     * 1. 已有多选批量拖拽 → 整组；
     * 2. 正在拖拽/缩放的控件 → 该控件；
     * 3. 手指（或两指中点）命中的控件 → 该控件；若它属于多选则整组；
     * 4. 空白处 → 当前选中集合。
     */
    private fun resolvePinchTargetIds(
        pointers: List<Pointer>,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        bounds: Map<String, android.graphics.Rect>,
        state: EditModeState,
        selectedButtonIds: Set<String>,
    ): List<String> {
        val visibleIds = buttons.asSequence().filter { it.visible }.map { it.id }.toSet()
        val activeGroup = state.childDragStart?.groupStartGrid?.keys
            ?.filter { it in visibleIds }
            .orEmpty()
        if (activeGroup.size > 1) return activeGroup

        state.childDragStart?.buttonId?.takeIf { it in visibleIds }?.let { return listOf(it) }
        state.childResizeStart?.buttonId?.takeIf { it in visibleIds }?.let { return listOf(it) }

        val hitPoints = buildList {
            if (pointers.size >= 2) {
                add(pointers[0].x + (pointers[1].x - pointers[0].x) / 2f to
                    pointers[0].y + (pointers[1].y - pointers[0].y) / 2f)
            }
            pointers.forEach { add(it.x to it.y) }
        }
        val hitId = hitPoints.firstNotNullOfOrNull { (px, py) ->
            hitResolver.resolve(px, py, buttons, bounds)
                .firstOrNull { it in visibleIds }
        }
        val selected = selectedButtonIds.filter { it in visibleIds }
        if (hitId != null) {
            return if (hitId in selected && selected.size > 1) selected else listOf(hitId)
        }
        return selected
    }

    private fun handlePinchMove(
        pointers: List<Pointer>,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        state: EditModeState,
        start: PinchResizeStart,
        cellW: Float,
        cellH: Float,
        density: Float,
    ): EditDispatchResult {
        if (pointers.size < 2 || start.startDistance <= 0f) {
            return EditDispatchResult(emptyList(), state)
        }
        val currentDistance = pinchDistance(pointers)
        val uniformScale = (currentDistance / start.startDistance).coerceIn(0.05f, 20f)
        val currentSpanX = pinchSpanX(pointers)
        val currentSpanY = pinchSpanY(pointers)
        val currentFocusX = pinchFocusX(pointers)
        val currentFocusY = pinchFocusY(pointers)
        // 初始指距太小（两指几乎在同一条轴线上）时，用控件该方向尺寸做基准，
        // 让手指沿另一条轴线张开的位移也能改变对应尺寸，避免比例爆炸。
        val minSpanPx = 8f * density
        val commands = mutableListOf<EditCommand>()

        for (target in start.targets) {
            val pos = buttons.find { it.id == target.buttonId } ?: continue
            val swapped = !pos.lockAspect && (pos.rotation % 360 == 90 || pos.rotation % 360 == 270)
            val startVisualLeft = target.startX * cellW
            val startVisualTop = target.startY * cellH
            val startVisualW = (if (swapped) target.startHeight else target.startWidth) * cellW
            val startVisualH = (if (swapped) target.startWidth else target.startHeight) * cellH

            val scaleX = if (pos.lockAspect) {
                uniformScale
            } else {
                axisScale(start.startSpanX, currentSpanX, startVisualW, minSpanPx)
            }
            val scaleY = if (pos.lockAspect) {
                uniformScale
            } else {
                axisScale(start.startSpanY, currentSpanY, startVisualH, minSpanPx)
            }

            // 把控件放在“初始触摸帧 → 当前触摸帧”的缩放/平移变换里：
            // 手指最初按住的控件位置会随手指一起移动，缩放时始终跟手。
            val newVisualLeft = currentFocusX - (start.startFocusX - startVisualLeft) * scaleX
            val newVisualTop = currentFocusY - (start.startFocusY - startVisualTop) * scaleY
            val newVisualW = startVisualW * scaleX
            val newVisualH = startVisualH * scaleY

            var newX = (newVisualLeft / cellW).roundToInt()
                .coerceIn(0, (gridCols - 1).coerceAtLeast(0))
            var newY = (newVisualTop / cellH).roundToInt().coerceAtLeast(0)
            var newW = if (swapped) {
                (newVisualH / cellH).roundToInt()
            } else {
                (newVisualW / cellW).roundToInt()
            }.coerceAtLeast(1)
            var newH = if (swapped) {
                (newVisualW / cellW).roundToInt()
            } else {
                (newVisualH / cellH).roundToInt()
            }.coerceAtLeast(1)

            if (pos.id == SETTINGS_BUTTON_ID) {
                newX = newX.coerceIn(0, (gridCols - 1).coerceAtLeast(0))
                newY = newY.coerceAtLeast(0)
                newW = newW.coerceAtMost((gridCols - newX).coerceAtLeast(1))
                newH = newH.coerceAtMost((gridRows - newY).coerceAtLeast(1))
                if (pos.lockAspect) {
                    val side = minOf(newW, newH)
                    newW = side
                    newH = side
                }
            }

            if (newX != pos.x || newY != pos.y) {
                commands += MoveButton(target.buttonId, newX, newY)
                if (isTouchpadId(target.buttonId) && pos.followAreaEnabled) {
                    commands += MoveFollowArea(
                        target.buttonId,
                        pos.followAreaX + (newX - pos.x),
                        pos.followAreaY + (newY - pos.y),
                    )
                }
            }
            if (newW != pos.width || newH != pos.height) {
                commands += ResizeButton(target.buttonId, newW, newH)
            }
        }
        return EditDispatchResult(commands, state)
    }

    /**
     * 单轴缩放比例。初始指距足够大时按“当前间距 / 起始间距”计算；
     * 否则退化为“1 + 位移 / 控件该方向尺寸”，保证从 0 间距开始也能缩放。
     */
    private fun axisScale(
        startSpan: Float,
        currentSpan: Float,
        startSizePx: Float,
        minSpanPx: Float,
    ): Float {
        val scale = if (startSpan >= minSpanPx) {
            currentSpan / startSpan
        } else {
            val base = startSizePx.coerceAtLeast(minSpanPx)
            1f + (currentSpan - startSpan) / base
        }
        return scale.coerceIn(0.05f, 20f)
    }

    private fun handlePointerUp(
        actionIndex: Int,
        pointers: List<Pointer>,
        buttons: List<com.zyz4.gkme.model.ButtonPosition>,
        state: EditModeState,
    ): EditDispatchResult {
        val start = state.pinchResizeStart ?: return EditDispatchResult(emptyList(), state)
        // 三指及以上：抬起一根后按剩余两指重新锚定，缩放不中断。
        val remaining = pointers.filterIndexed { index, _ -> index != actionIndex }
        val distance = pinchDistance(remaining)
        if (remaining.size >= 2 && distance >= 1f) {
            val targets = start.targets.mapNotNull { target ->
                buttons.find { it.id == target.buttonId }?.let {
                    target.copy(
                        startX = it.x,
                        startY = it.y,
                        startWidth = it.width,
                        startHeight = it.height,
                    )
                }
            }
            if (targets.isNotEmpty()) {
                return EditDispatchResult(
                    emptyList(),
                    state.copy(
                        pinchResizeStart = PinchResizeStart(
                            targets = targets,
                            startDistance = distance,
                            startSpanX = pinchSpanX(remaining),
                            startSpanY = pinchSpanY(remaining),
                            startFocusX = pinchFocusX(remaining),
                            startFocusY = pinchFocusY(remaining),
                        ),
                    ),
                )
            }
        }
        return EditDispatchResult(emptyList(), state.copy(pinchResizeStart = null))
    }

    private fun pinchDistance(pointers: List<Pointer>): Float {
        if (pointers.size < 2) return 0f
        val dx = pointers[0].x - pointers[1].x
        val dy = pointers[0].y - pointers[1].y
        return kotlin.math.hypot(dx, dy)
    }

    private fun pinchSpanX(pointers: List<Pointer>): Float {
        if (pointers.size < 2) return 0f
        return kotlin.math.abs(pointers[0].x - pointers[1].x)
    }

    private fun pinchSpanY(pointers: List<Pointer>): Float {
        if (pointers.size < 2) return 0f
        return kotlin.math.abs(pointers[0].y - pointers[1].y)
    }

    private fun pinchFocusX(pointers: List<Pointer>): Float {
        if (pointers.size < 2) return pointers.firstOrNull()?.x ?: 0f
        return (pointers[0].x + pointers[1].x) / 2f
    }

    private fun pinchFocusY(pointers: List<Pointer>): Float {
        if (pointers.size < 2) return pointers.firstOrNull()?.y ?: 0f
        return (pointers[0].y + pointers[1].y) / 2f
    }

    // ──────── Geometry helpers ────────

    private fun visualBounds(pos: com.zyz4.gkme.model.ButtonPosition, cellW: Float, cellH: Float): FloatArray {
        val isSwapped = !pos.lockAspect && (pos.rotation % 360 in listOf(90, 270))
        val lw = if (isSwapped) pos.height else pos.width
        val lh = if (isSwapped) pos.width else pos.height
        return floatArrayOf(pos.x * cellW, pos.y * cellH, lw * cellW, lh * cellH)
    }

    private fun isOnVisualHandle(x: Float, y: Float, pos: com.zyz4.gkme.model.ButtonPosition,
                                  rect: android.graphics.Rect, handleHitPx: Float,
                                  cellW: Float, cellH: Float): Boolean {
        val vb = visualBounds(pos, cellW, cellH)
        val vx = vb[0]; val vy = vb[1]; val vw = vb[2]; val vh = vb[3]
        return x >= vx + vw - handleHitPx && x <= vx + vw && y >= vy + vh - handleHitPx && y <= vy + vh
    }

    private fun isOnFollowAreaHandle(x: Float, y: Float, pos: com.zyz4.gkme.model.ButtonPosition, cellW: Float, handleHitPx: Float): Boolean {
        val fl = pos.followAreaX * cellW
        val ft = pos.followAreaY * cellW
        val fr = (pos.followAreaX + pos.followAreaW) * cellW
        val fb = (pos.followAreaY + pos.followAreaH) * cellW
        return x >= fr - handleHitPx && x <= fr && y >= fb - handleHitPx && y <= fb
    }

    private fun isInFollowArea(x: Float, y: Float, pos: com.zyz4.gkme.model.ButtonPosition, cellW: Float): Boolean {
        val fl = pos.followAreaX * cellW
        val ft = pos.followAreaY * cellW
        val fr = (pos.followAreaX + pos.followAreaW) * cellW
        val fb = (pos.followAreaY + pos.followAreaH) * cellW
        return x >= fl && x <= fr && y >= ft && y <= fb
    }
}
