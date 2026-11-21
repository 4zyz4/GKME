package com.zyz4.gkme.view

import android.graphics.Rect
import android.view.MotionEvent
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.view.inputdispatcher.EditCommand
import com.zyz4.gkme.view.inputdispatcher.EditDispatchResult

import com.zyz4.gkme.view.inputdispatcher.EditModeState
import com.zyz4.gkme.view.inputdispatcher.GamepadInputDispatcher
import com.zyz4.gkme.view.inputdispatcher.RawTouchEvent
import com.zyz4.gkme.view.inputdispatcher.SETTINGS_BUTTON_ID
import com.zyz4.gkme.view.inputdispatcher.isTouchpadId

/**
 * Deep module: edit-mode gesture handling.
 *
 * Orchestrates the interaction between [GamepadInputDispatcher.dispatchEdit()]
 * (which produces pure EditCommand data) and the GamepadLayout's UI state,
 * which owns the child View references and animation state.
 *
 * Interface: [dispatch()] returns a [DispatchOutput] describing what
 * UI-side effects the caller must apply.
 */
class GamepadEditGesture {

    /** The button currently being dragged (by finger, not by edit command). */
    var draggingChild: android.view.View? = null
        private set

    /** The button currently being resized (by handle). */
    var resizingChild: android.view.View? = null
        private set

    /** Follow-area drag/resize state. */
    var draggingFollowArea = false
        private set
    var resizingFollowArea = false
        private set

    /** 多选批量拖拽中（按下点属于多选集合且集合大小大于 1）。 */
    var isGroupDragging = false
        private set

    // ── 框选（marquee）状态 ─────────────────────────────────
    private var marqueeActive = false
    private var marqueeMoved = false
    private var marqueeStartX = 0f
    private var marqueeStartY = 0f
    private var marqueeCurrentX = 0f
    private var marqueeCurrentY = 0f
    private var marqueeRectInternal: android.graphics.RectF? = null
    private var marqueeSelectionInternal: Set<String> = emptySet()

    private var _editState = EditModeState()
    private var _lastSelectedButtonId: String? = null

    /** Whether follow-area adjustment is active. */
    var isAdjustingFollowArea = false
        set(value) {
            if (field != value) {
                _editState = _editState.copy(
                    isAdjustingFollowArea = value,
                    adjustingFollowAreaId = if (value) adjustingFollowAreaId else null,
                )
            }
            field = value
        }

    var adjustingFollowAreaId: String? = null
        set(value) {
            field = value
            if (value != null) {
                _editState = _editState.copy(
                    isAdjustingFollowArea = true,
                    adjustingFollowAreaId = value,
                )
            }
        }

    data class DispatchOutput(
        val commands: List<EditCommand>,
        val commandsNoMove: List<EditCommand>,
        val syncIsAdjustingFollowArea: Boolean,
        val syncAdjustingFollowAreaId: String?,
        val syncDraggingChild: android.view.View?,
        val syncResizingChild: android.view.View?,
        val syncDraggingFollowArea: Boolean,
        val syncResizingFollowArea: Boolean,
        val selectedButton: String?,
        val selectDraggingChild: String?,
        val selectResizingChild: String?,
        /** 框选进行中（本次事件开始/延续框选）。 */
        val marqueeActive: Boolean = false,
        /** 当前框选矩形（px），null 表示尚未越过起始阈值。 */
        val marqueeRect: android.graphics.RectF? = null,
        /** 框选过程中实时命中的控件集合。 */
        val marqueeSelection: Set<String> = emptySet(),
        /** 框选结束且选择结果需要提交。 */
        val marqueeSelectionChanged: Boolean = false,
        /** 本次事件结束了框选。 */
        val marqueeFinished: Boolean = false,
        /** 本次事件开始了多选批量拖拽。 */
        val groupDragging: Boolean = false,
    ) {
        companion object {
            fun empty(): DispatchOutput = DispatchOutput(
                commands = emptyList(),
                commandsNoMove = emptyList(),
                syncIsAdjustingFollowArea = false,
                syncAdjustingFollowAreaId = null,
                syncDraggingChild = null,
                syncResizingChild = null,
                syncDraggingFollowArea = false,
                syncResizingFollowArea = false,
                selectedButton = null,
                selectDraggingChild = null,
                selectResizingChild = null,
            )
        }
    }

    /**
     * Dispatch an edit-mode MotionEvent.
     *
     * @param event raw MotionEvent in GamepadLayout coords
     * @param raw pre-converted RawTouchEvent
     * @param buttons current button positions
     * @param buttonBounds id → Rect (left,top,right,bottom in px)
     * @param allChildren all visible child Views
     * @param cellW cell width in px
     * @param cellH cell height in px
     * @param density display density
     * @param dispatcher the GamepadInputDispatcher instance
     * @param findChildAt callback to find a child View at pixel coords
     *
     * The dispatcher [GamepadInputDispatcher.dispatchEdit()] handles the core
     * gesture recognition. This module maps the result to UI-side effects.
     */
    fun dispatch(
        event: MotionEvent,
        raw: RawTouchEvent,
        buttons: List<ButtonPosition>,
        buttonBounds: Map<String, Rect>,
        allChildren: List<android.view.View>,
        cellW: Float,
        cellH: Float,
        density: Float,
        dispatcher: GamepadInputDispatcher,
        findChildAt: (x: Float, y: Float) -> android.view.View?,
        selectedButtonId: String? = null,
        selectedButtonIds: Set<String> = emptySet(),
    ): DispatchOutput {
        val action = event.actionMasked
        val currentSelected = selectedButtonId ?: _lastSelectedButtonId

        // 框选进行中：事件不再交给拖拽/缩放逻辑。
        if (marqueeActive && action != MotionEvent.ACTION_DOWN) {
            return handleMarquee(event, buttons, buttonBounds, density)
        }

        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                val output = handleDown(
                    event, raw, buttons, buttonBounds, allChildren,
                    cellW, cellH, density, dispatcher, findChildAt,
                    currentSelected, selectedButtonIds,
                )
                if (shouldStartMarquee(output)) {
                    startMarquee(event)
                    return output.copy(marqueeActive = true)
                }
                output
            }
            MotionEvent.ACTION_MOVE -> handleMove(
                event, raw, buttons, buttonBounds, allChildren,
                cellW, cellH, density, dispatcher, currentSelected, selectedButtonIds,
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleUp(
                event, raw, buttons, buttonBounds, allChildren,
                cellW, cellH, density, dispatcher, currentSelected, selectedButtonIds,
            )
            else -> DispatchOutput.empty()
        }
    }

    // ── 框选（marquee） ──────────────────────────────────────

    /** 按下点没有命中任何可交互目标时进入框选。 */
    private fun shouldStartMarquee(output: DispatchOutput): Boolean {
        if (isAdjustingFollowArea) return false
        return output.syncDraggingChild == null &&
            output.syncResizingChild == null &&
            !output.syncDraggingFollowArea &&
            !output.syncResizingFollowArea
    }

    private fun startMarquee(event: MotionEvent) {
        marqueeActive = true
        marqueeMoved = false
        marqueeStartX = event.x
        marqueeStartY = event.y
        marqueeCurrentX = event.x
        marqueeCurrentY = event.y
        marqueeRectInternal = null
        marqueeSelectionInternal = emptySet()
        draggingChild = null
        resizingChild = null
        isGroupDragging = false
    }

    private fun handleMarquee(
        event: MotionEvent,
        buttons: List<ButtonPosition>,
        buttonBounds: Map<String, Rect>,
        density: Float,
    ): DispatchOutput {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                marqueeCurrentX = event.x
                marqueeCurrentY = event.y
                if (!marqueeMoved) {
                    val dx = marqueeCurrentX - marqueeStartX
                    val dy = marqueeCurrentY - marqueeStartY
                    if (kotlin.math.hypot(dx, dy) < 8f * density) {
                        return DispatchOutput.empty().copy(marqueeActive = true)
                    }
                    marqueeMoved = true
                }

                val rect = normalizedMarqueeRect()
                marqueeRectInternal = rect
                marqueeSelectionInternal = hitIdsInRect(rect, buttons, buttonBounds)
                return DispatchOutput.empty().copy(
                    marqueeActive = true,
                    marqueeRect = rect,
                    marqueeSelection = marqueeSelectionInternal,
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val isUp = event.actionMasked == MotionEvent.ACTION_UP
                val selection = if (isUp && marqueeMoved) marqueeSelectionInternal else emptySet()
                marqueeActive = false
                marqueeMoved = false
                marqueeRectInternal = null
                marqueeSelectionInternal = emptySet()
                _editState = _editState.copy(
                    childDragStart = null,
                    childResizeStart = null,
                    followAreaDragStart = null,
                    followAreaResizeStart = null,
                    unselectedDragDownGrid = null,
                )
                return DispatchOutput.empty().copy(
                    marqueeSelection = selection,
                    marqueeSelectionChanged = isUp,
                    marqueeFinished = true,
                )
            }
            else -> Unit
        }
        return DispatchOutput.empty()
    }

    private fun normalizedMarqueeRect(): android.graphics.RectF {
        return android.graphics.RectF(
            minOf(marqueeStartX, marqueeCurrentX),
            minOf(marqueeStartY, marqueeCurrentY),
            maxOf(marqueeStartX, marqueeCurrentX),
            maxOf(marqueeStartY, marqueeCurrentY),
        )
    }

    private fun hitIdsInRect(
        rect: android.graphics.RectF,
        buttons: List<ButtonPosition>,
        buttonBounds: Map<String, Rect>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        for (pos in buttons) {
            if (!pos.visible) continue
            val r = buttonBounds[pos.id] ?: continue
            if (r.width() <= 0 || r.height() <= 0) continue
            if (r.left < rect.right && r.right > rect.left &&
                r.top < rect.bottom && r.bottom > rect.top
            ) {
                result.add(pos.id)
            }
        }
        return result
    }

    fun updateStateFromEditState(newState: EditModeState) {
        _editState = newState
        isAdjustingFollowArea = newState.isAdjustingFollowArea
        adjustingFollowAreaId = newState.adjustingFollowAreaId
    }

    fun reset() {
        draggingChild = null
        resizingChild = null
        draggingFollowArea = false
        resizingFollowArea = false
        _editState = EditModeState()
        _lastSelectedButtonId = null
        isAdjustingFollowArea = false
        adjustingFollowAreaId = null
        isGroupDragging = false
        marqueeActive = false
        marqueeMoved = false
        marqueeRectInternal = null
        marqueeSelectionInternal = emptySet()
    }

    // ── Internal handlers ──────────────────────────────────

    private fun handleDown(
        event: MotionEvent,
        raw: RawTouchEvent,
        buttons: List<ButtonPosition>,
        buttonBounds: Map<String, Rect>,
        allChildren: List<android.view.View>,
        cellW: Float,
        cellH: Float,
        density: Float,
        dispatcher: GamepadInputDispatcher,
        findChildAt: (x: Float, y: Float) -> android.view.View?,
        selectedButtonId: String? = null,
        selectedButtonIds: Set<String> = emptySet(),
    ): DispatchOutput {
        // 每个新手势开始前清理上一次的拖拽目标，避免空白按下时残留旧状态。
        draggingChild = null
        resizingChild = null
        draggingFollowArea = false
        resizingFollowArea = false
        isGroupDragging = false

        val result = dispatcher.dispatchEdit(
            raw, buttons, buttonBounds, allChildren,
            _editState, cellW, cellH, density, selectedButtonId, selectedButtonIds,
        )
        _editState = result.newState
        isAdjustingFollowArea = _editState.isAdjustingFollowArea
        adjustingFollowAreaId = _editState.adjustingFollowAreaId

        // Wire editState back to local fields
        syncFromEditState()

        var selectDragging: String? = null
        var selectResizing: String? = null

        if (_editState.childDragStart != null) {
            val dragButtonId = _editState.childDragStart?.buttonId
            // Use the button ID from dispatcher result instead of hit-testing at pointer coords,
            // because the dispatcher uses grid-coordinates which are more reliable than pixel bounds
            // at dispatch time on some Android devices.
            if (dragButtonId != null) {
                draggingChild = findChildViewById(allChildren, dragButtonId, event)
                if (draggingChild != null && draggingChild?.tag != null) {
                    selectDragging = draggingChild?.tag as? String
                } else if (draggingChild != null) {
                    try {
                        selectDragging = draggingChild?.context?.resources?.getResourceEntryName(draggingChild!!.id)
                    } catch (_: Exception) {
                        // no-op
                    }
                }
            }
        }
        if (_editState.childResizeStart != null) {
            val resizeButtonId = _editState.childResizeStart?.buttonId
            if (resizeButtonId != null) {
                resizingChild = findChildViewById(allChildren, resizeButtonId, event)
            }
            if (resizingChild != null) {
                selectResizing = _editState.childResizeStart?.buttonId
            }
        }

        if (_editState.followAreaDragStart != null || _editState.followAreaResizeStart != null) {
            draggingFollowArea = _editState.followAreaDragStart != null
            resizingFollowArea = _editState.followAreaResizeStart != null
        }

        isGroupDragging = (_editState.childDragStart?.groupStartGrid?.size ?: 0) > 1

        return DispatchOutput(
            commands = result.commands,
            commandsNoMove = result.commands.filter { cmd ->
                cmd !is com.zyz4.gkme.view.inputdispatcher.EditCommand.MoveButton
            },
            syncIsAdjustingFollowArea = isAdjustingFollowArea,
            syncAdjustingFollowAreaId = adjustingFollowAreaId,
            syncDraggingChild = draggingChild,
            syncResizingChild = resizingChild,
            syncDraggingFollowArea = draggingFollowArea,
            syncResizingFollowArea = resizingFollowArea,
            selectedButton = _lastSelectedButtonId,
            selectDraggingChild = selectDragging,
            selectResizingChild = selectResizing,
            groupDragging = isGroupDragging,
        )
    }

    private fun handleMove(
        event: MotionEvent,
        raw: RawTouchEvent,
        buttons: List<ButtonPosition>,
        buttonBounds: Map<String, Rect>,
        allChildren: List<android.view.View>,
        cellW: Float,
        cellH: Float,
        density: Float,
        dispatcher: GamepadInputDispatcher,
        selectedButtonId: String? = null,
        selectedButtonIds: Set<String> = emptySet(),
    ): DispatchOutput {
        val result = dispatcher.dispatchEdit(
            raw, buttons, buttonBounds, allChildren,
            _editState, cellW, cellH, density, selectedButtonId, selectedButtonIds,
        )
        _editState = result.newState
        isAdjustingFollowArea = _editState.isAdjustingFollowArea
        adjustingFollowAreaId = _editState.adjustingFollowAreaId

        return DispatchOutput(
            commands = result.commands,
            commandsNoMove = result.commands.filter { cmd ->
                cmd !is com.zyz4.gkme.view.inputdispatcher.EditCommand.MoveButton
            },
            syncIsAdjustingFollowArea = isAdjustingFollowArea,
            syncAdjustingFollowAreaId = adjustingFollowAreaId,
            syncDraggingChild = draggingChild,
            syncResizingChild = resizingChild,
            syncDraggingFollowArea = draggingFollowArea,
            syncResizingFollowArea = resizingFollowArea,
            selectedButton = null,
            selectDraggingChild = null,
            selectResizingChild = null,
        )
    }

    private fun handleUp(
        event: MotionEvent,
        raw: RawTouchEvent,
        buttons: List<ButtonPosition>,
        buttonBounds: Map<String, Rect>,
        allChildren: List<android.view.View>,
        cellW: Float,
        cellH: Float,
        density: Float,
        dispatcher: GamepadInputDispatcher,
        lastSelectedButton: String?,
        selectedButtonIds: Set<String> = emptySet(),
    ): DispatchOutput {
        val result = dispatcher.dispatchEdit(
            raw, buttons, buttonBounds, allChildren,
            _editState, cellW, cellH, density, lastSelectedButton, selectedButtonIds,
        )
        _editState = result.newState
        // Keep the follow-area adjust session alive across gestures: lifting the finger
        // must only end the current drag/resize, not exit adjust mode (that is done via
        // exitFollowAreaAdjust / the panel's return button).
        syncFromEditState()

        var selectDragging: String? = null
        var selectResizing: String? = null

        // On up/cancel, select the last interacted button
        if (isAdjustingFollowArea || adjustingFollowAreaId != null) {
            selectDragging = adjustingFollowAreaId
        }
        if (resizingChild != null) {
            selectResizing = _editState.childResizeStart?.buttonId
        }
        if (draggingChild != null) {
            selectDragging = findButtonId(draggingChild)
        }

        draggingFollowArea = false
        resizingFollowArea = false

        return DispatchOutput(
            commands = emptyList(),
            commandsNoMove = emptyList(),
            syncIsAdjustingFollowArea = isAdjustingFollowArea,
            syncAdjustingFollowAreaId = adjustingFollowAreaId,
            syncDraggingChild = null,
            syncResizingChild = null,
            syncDraggingFollowArea = false,
            syncResizingFollowArea = false,
            selectedButton = selectDragging,
            selectDraggingChild = selectDragging,
            selectResizingChild = selectResizing,
            groupDragging = isGroupDragging,
        )
    }

    private fun syncFromEditState() {
        isAdjustingFollowArea = _editState.isAdjustingFollowArea
        adjustingFollowAreaId = _editState.adjustingFollowAreaId
    }

    /** Find a child View by button ID using tag or resource name. */
    private fun findChildViewById(
        children: List<android.view.View>,
        buttonId: String,
        event: MotionEvent,
    ): android.view.View? {
        // First try: use tag (fastest and most reliable)
        for (child in children) {
            if (child.tag as? String == buttonId) {
                return child
            }
        }
        // Fallback: use findChildrenAt (hit test) — prefer the child at event coordinates
        // since it matches the z-order used by the dispatcher's findAllChildrenAt
        return findChildrenAt(event.x, event.y, children).firstOrNull { child ->
            val tag = child.tag as? String
            val name = try {
                child.context.resources.getResourceEntryName(child.id)
            } catch (_: Exception) {
                null
            }
            tag == buttonId || name == buttonId
        }
    }

    private fun findButtonId(child: android.view.View?): String? {
        if (child == null) return null
        val tag = child.tag as? String
        if (tag != null) return tag
        return try {
            child.context.resources.getResourceEntryName(child.id)
        } catch (_: Exception) {
            null
        }
    }

    /** Get children at a pixel position (topmost first). */
    fun findChildrenAt(x: Float, y: Float, children: List<android.view.View>): List<android.view.View> {
        val result = mutableListOf<android.view.View>()
        for (i in children.size - 1 downTo 0) {
            val child = children[i]
            if (child.visibility != android.view.View.VISIBLE) continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                result.add(child)
            }
        }
        return result
    }

    /** True when the touch point is inside the follow-area handle hit region. */
    fun isOnFollowAreaHandle(x: Float, y: Float, pos: ButtonPosition, cellW: Float, density: Float): Boolean {
        val fLeft = pos.followAreaX * cellW
        val fTop = pos.followAreaY * cellW
        val fRight = (pos.followAreaX + pos.followAreaW) * cellW
        val fBottom = (pos.followAreaY + pos.followAreaH) * cellW
        val handleHit = 16f * density
        val hx = fRight - handleHit
        val hy = fBottom - handleHit
        return x >= hx && x <= fRight && y >= hy && y <= fBottom
    }

    /** True when the touch point is inside the follow-area rectangle. */
    fun isInFollowArea(x: Float, y: Float, pos: ButtonPosition, cellW: Float): Boolean {
        val fLeft = pos.followAreaX * cellW
        val fTop = pos.followAreaY * cellW
        val fRight = (pos.followAreaX + pos.followAreaW) * cellW
        val fBottom = (pos.followAreaY + pos.followAreaH) * cellW
        return x >= fLeft && x <= fRight && y >= fTop && y <= fBottom
    }
}