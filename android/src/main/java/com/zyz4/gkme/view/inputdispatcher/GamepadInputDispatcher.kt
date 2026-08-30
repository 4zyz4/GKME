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
import com.zyz4.gkme.view.inputdispatcher.Pointer
import com.zyz4.gkme.view.inputdispatcher.RawTouchEvent
import com.zyz4.gkme.view.inputdispatcher.SlotMatcher.SlotResult
import com.zyz4.gkme.view.inputdispatcher.SwipeTriggerStrategy
import com.zyz4.gkme.view.inputdispatcher.TouchpadClickDetector

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
    ): EditDispatchResult {

        val action = event.action
        val x = event.pointers.getOrElse(0) { Pointer(-1, 0f, 0f) }.x
        val y = event.pointers.getOrElse(0) { Pointer(-1, 0f, 0f) }.y
        val handleHitPx = 16f * density

        return when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                handleDown(x, y, buttons, currentButtonBounds, children, currentState, cellW, cellH, density, selectedButtonId)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                handleMove(x, y, buttons, currentButtonBounds, children, currentState, cellW, cellH, density, selectedButtonId)
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

        // 1) Try to select the currently selected button (it may have been moved externally)
        val allHitIds = hitResolver.resolve(x, y, buttons, bounds)
        
        var hitId: String? = null
        
        if (selectedButtonId != null && selectedButtonId in allHitIds) {
            // Selected button is under finger — drag it
            hitId = selectedButtonId
        } else if (selectedButtonId != null && allHitIds.isEmpty()) {
            // Selected button is NOT under finger (e.g. moved externally, or user tapped empty space)
            // Still drag the selected button — find its pixel bounds for offset calculation
            val selPos = buttons.find { it.id == selectedButtonId }
            if (selPos != null) {
                hitId = selectedButtonId
            }
        } else if (hitId == null) {
            // No relevant selection — pick topmost hit button
            hitId = allHitIds.firstOrNull()
        }
        
        if (hitId != null) {
            val rect = bounds[hitId]
            val pos = buttons.find { it.id == hitId }
            
            if (rect != null && pos != null) {
                // Normal case: button has valid bounds
                commands += MoveButton(hitId, pos.x, pos.y)
                val isUnselectedDrag = selectedButtonId == null || selectedButtonId != hitId
                return EditDispatchResult(commands, if (isUnselectedDrag) {
                    state.copy(
                        childDragStart = ChildDragStart(hitId, x - rect.left.toFloat(), y - rect.top.toFloat()),
                        unselectedDragDownGrid = Pair((x / cellW).toInt(), (y / cellH).toInt()),
                    )
                } else {
                    state.copy(
                        childDragStart = ChildDragStart(hitId, x - rect.left.toFloat(), y - rect.top.toFloat()),
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
                newX = newX.coerceIn(0, (LayoutEngine.GRID_COLS - pos.followAreaW).coerceAtLeast(0))
                newY = newY.coerceIn(0, if (cellW > 0f) (120 - pos.followAreaH).coerceAtLeast(0) else Int.MAX_VALUE)
            }
            commands += MoveFollowArea(ds.buttonId, newX, newY)
            return EditDispatchResult(commands, state)
        }

        if (state.childResizeStart != null) {
            val rs = state.childResizeStart
            val gx = (x / cellW).toInt().coerceAtLeast(0)
            val gy = (y / cellH).toInt().coerceAtLeast(0)
            val dx = gx - rs.resizeStartGridX
            val dy = gy - rs.resizeStartGridY
            val oldPos = buttons.find { it.id == rs.buttonId } ?: return EditDispatchResult(commands, state)
            
            val rot = oldPos.rotation % 360
            val screenDx = dx.toFloat()
            val screenDy = dy.toFloat()
            val localDx = when (rot) {
                90  ->  screenDy
                180 -> -screenDx
                270 -> -screenDy
                else -> screenDx
            }
            val localDy = when (rot) {
                90  -> -screenDx
                180 -> -screenDy
                270 -> screenDx
                else -> screenDy
            }
            
            val localCurrentX = rs.localStartX + localDx
            val localCurrentY = rs.localStartY + localDy
            var nw = localCurrentX.coerceAtLeast(1f)
            var nh = localCurrentY.coerceAtLeast(1f)
            if (oldPos.lockAspect) {
                val side = maxOf(nw, nh); nw = side; nh = side
            }
            if (rs.buttonId == SETTINGS_BUTTON_ID) {
                nw = nw.coerceAtMost((LayoutEngine.GRID_COLS - oldPos.x).toFloat())
                nh = nh.coerceAtMost((120 - oldPos.y).toFloat())
                if (oldPos.lockAspect) { val s = minOf(nw, nh); nw = s; nh = s }
            }
            commands += ResizeButton(rs.buttonId, nw.toInt(), nh.toInt())
            return EditDispatchResult(commands, state)
        }

        if (state.childDragStart != null) {
            val ds = state.childDragStart
            var gridX = ((x - ds.offsetX) / cellW).toInt().coerceIn(0, LayoutEngine.GRID_COLS - 1)
            var gridY = ((y - ds.offsetY) / cellH).toInt().coerceAtLeast(0)
            val pos = buttons.find { it.id == ds.buttonId } ?: return EditDispatchResult(commands, state)
            if (state.unselectedDragDownGrid != null) {
                val (downGx, downGy) = state.unselectedDragDownGrid
                val dx = kotlin.math.abs(gridX - downGx)
                val dy = kotlin.math.abs(gridY - downGy)
                if (dx < 3 && dy < 3) {
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
