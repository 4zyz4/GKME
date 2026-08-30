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
    ): DispatchOutput {
        val action = event.actionMasked
        val currentSelected = _lastSelectedButtonId

        return when (action) {
            MotionEvent.ACTION_DOWN -> handleDown(
                event, raw, buttons, buttonBounds, allChildren,
                cellW, cellH, density, dispatcher, findChildAt,
            )
            MotionEvent.ACTION_MOVE -> handleMove(event, raw, buttons, buttonBounds, allChildren, cellW, cellH, density, dispatcher)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleUp(
                event, raw, buttons, buttonBounds, allChildren,
                cellW, cellH, density, dispatcher, currentSelected,
            )
            else -> DispatchOutput.empty()
        }
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
    ): DispatchOutput {
        val result = dispatcher.dispatchEdit(
            raw, buttons, buttonBounds, allChildren,
            _editState, cellW, cellH, density, _lastSelectedButtonId,
        )
        _editState = result.newState
        isAdjustingFollowArea = _editState.isAdjustingFollowArea
        adjustingFollowAreaId = _editState.adjustingFollowAreaId

        // Wire editState back to local fields
        syncFromEditState()

        var selectDragging: String? = null
        var selectResizing: String? = null

        if (_editState.childDragStart != null) {
            draggingChild = findChildAt(event.x, event.y)
            if (draggingChild != null && draggingChild?.tag != null) {
                selectDragging = draggingChild?.tag as? String
            } else if (draggingChild != null) {
                // Try to get button id from the child
                try {
                    selectDragging = draggingChild?.context?.resources?.getResourceEntryName(draggingChild!!.id)
                } catch (_: Exception) {
                    // no-op
                }
            }
        }
        if (_editState.childResizeStart != null) {
            resizingChild = findChildAt(event.x, event.y)
            if (resizingChild != null) {
                selectResizing = _editState.childResizeStart?.buttonId
            }
        }

        if (_editState.followAreaDragStart != null || _editState.followAreaResizeStart != null) {
            draggingFollowArea = _editState.followAreaDragStart != null
            resizingFollowArea = _editState.followAreaResizeStart != null
        }

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
    ): DispatchOutput {
        val result = dispatcher.dispatchEdit(
            raw, buttons, buttonBounds, allChildren,
            _editState, cellW, cellH, density, _lastSelectedButtonId,
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
    ): DispatchOutput {
        val result = dispatcher.dispatchEdit(
            raw, buttons, buttonBounds, allChildren,
            _editState, cellW, cellH, density, _lastSelectedButtonId,
        )
        _editState = result.newState

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
            syncIsAdjustingFollowArea = false,
            syncAdjustingFollowAreaId = null,
            syncDraggingChild = null,
            syncResizingChild = null,
            syncDraggingFollowArea = false,
            syncResizingFollowArea = false,
            selectedButton = selectDragging,
            selectDraggingChild = selectDragging,
            selectResizingChild = selectResizing,
        )
    }

    private fun syncFromEditState() {
        isAdjustingFollowArea = _editState.isAdjustingFollowArea
        adjustingFollowAreaId = _editState.adjustingFollowAreaId
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