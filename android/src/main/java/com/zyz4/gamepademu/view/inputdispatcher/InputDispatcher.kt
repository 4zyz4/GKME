package com.zyz4.gamepademu.view.inputdispatcher

import android.view.MotionEvent
import com.zyz4.gamepademu.model.ButtonPosition

// ==========================================================================
// InputDispatcher.kt — Type contracts (interface boundary)
//
// All types defined in this file form the interface contract between
// GamepadLayout and the inputdispatcher module. They contain no Android
// types except MotionEvent (used only by the adapter in ViewExtensions).
// ==========================================================================

/**
 * Raw touch event — the interface between Android's MotionEvent and the
 * pure strategy modules. Zero Android dependencies inside the strategy
 * layer.
 */
data class RawTouchEvent(
    val action: Int,         // MotionEvent.ACTION_* constant
    val actionIndex: Int,    // pointer index for POINTER_UP/DOWN
    val pointers: List<Pointer>,
    val buttonState: Int,    // raw button state from MotionEvent
)

data class Pointer(
    val id: Int,
    val x: Float,            // in GamepadLayout's coordinate space (0, 0) to (width, height)
    val y: Float,
)

/**
 * Hit test result — which button a point hits, filtered by overlapTrigger.
 */
data class HitResult(
    val buttonId: String,
    val childView: Any,      // View ref for caller dispatching (filled by caller)
)

/**
 * Outcome of the Interaction dispatch path (run mode).
 */
data class InteractionResult(
    val pressedIds: Set<String> = emptySet(),
    val releasedIds: Set<String> = emptySet(),
    val followAreaActions: List<FollowAreaAction> = emptyList(),
    val slotAssignment: SlotAssignment? = null,
    val clickResult: ClickResult? = null,
    val newSlotState: OldSlotState,
    val newLastButtonState: Int,
    val newSwipeActive: Set<String>,
    val hasAction: Boolean =
        pressedIds.isNotEmpty() ||
        releasedIds.isNotEmpty() ||
        followAreaActions.isNotEmpty() ||
        slotAssignment != null ||
        clickResult != null,
)

/**
 * Follow- area activation / deactivation action.
 */
data class FollowAreaAction(
    val buttonId: String,
    val type: Type,
) {
    enum class Type {
        ACTIVATE,
        DEACTIVATE,
    }
}

/**
 * Slot assignment for 2-finger touchpad.
 */
data class SlotAssignment(
    val slot0TouchId: Int,
    val slot0X: Float,       // normalised [0, 1]
    val slot0Y: Float,
    val slot1TouchId: Int,
    val slot1X: Float,
    val slot1Y: Float,
)

/**
 * Old slot persistent state — owned by caller, passed in each call.
 */
data class OldSlotState(
    val slot0Active: Boolean = false,
    val slot0X: Float = 0f,
    val slot0Y: Float = 0f,
    val slot1Active: Boolean = false,
    val slot1X: Float = 0f,
    val slot1Y: Float = 0f,
)

/**
 * Touch candidate for slot matching (raw event input).
 */
data class TouchCandidate(
    val touchId: Int,
    val x: Float,            // normalised [-0.5, 1.5] range matching dispatchTouchEvent coords
    val y: Float,
    val isReleased: Boolean = false,
)

/**
 * Click detection result from XOR of button state transitions.
 */
data class ClickResult(
    val primaryPressed: Boolean = false,
    val primaryReleased: Boolean = false,
    val secondaryPressed: Boolean = false,
    val secondaryReleased: Boolean = false,
) {
    val isClick: Boolean get() = primaryPressed || secondaryPressed
}

/**
 * Swipe result — which buttons transition to pressed / released.
 */
data class SwipeResult(
    val presses: Set<String> = emptySet(),
    val releases: Set<String> = emptySet(),
)

/**
 * Follow-area evaluation result.
 */
data class FollowAreaResult(
    val activatedButtonIds: Set<String> = emptySet(),
)

/**
 * Layout result — pure geometry, no Android types.
 */
data class LayoutResult(
    val bounds: Map<String, android.graphics.Rect>,            // left, top, right, bottom in px
    val visibleButtons: Set<String> = emptySet(),
    val goneButtons: Set<String> = emptySet(),
)

/**
 * Edit mode state — mutable state owned by the caller but managed
 * through the dispatcher's edit methods.
 */
// ── Edit mode state ───────────────────────────────────────

/**
 * Edit mode state — mutable state owned by the caller.
 */
data class EditModeState(
    val isAdjustingFollowArea: Boolean = false,
    val adjustingFollowAreaId: String? = null,
    val followAreaDragStart: FollowAreaDragStart? = null,
    val followAreaResizeStart: FollowAreaResizeStart? = null,
    val childDragStart: ChildDragStart? = null,
    val childResizeStart: ChildResizeStart? = null,
    val unselectedDragDownGrid: Pair<Int, Int>? = null,
)

data class FollowAreaDragStart(
    val buttonId: String,
    val startX: Int,
    val startY: Int,
    val dragStartGridX: Int,
    val dragStartGridY: Int,
)

data class FollowAreaResizeStart(
    val buttonId: String,
    val startW: Int,
    val startH: Int,
    val resizeStartGridX: Int,
    val resizeStartGridY: Int,
)

data class ChildDragStart(
    val buttonId: String,
    val offsetX: Float,
    val offsetY: Float,
)

data class ChildResizeStart(
    val buttonId: String,
    val startW: Int,
    val startH: Int,
    val resizeStartGridX: Int,
    val resizeStartGridY: Int,
)

/**
 * Edit dispatch result — commands produced by edit-mode interaction.
 */
data class EditDispatchResult(
    val commands: List<EditCommand> = emptyList(),
    val newState: EditModeState,
)

/**
 * Primary interface for the dispatcher — separates interaction (run
 * mode) from editing (edit mode). Each method accepts only pure data,
 * returns ID-only results. The caller (GamepadLayout) maps IDs to Views
 * and performs all side effects.
 */
interface InputDispatcher {

    /**
     * Run mode: button press, swipe, follow-area activation, 2-finger
     * touchpad slot matching.
     */
    fun dispatchInteraction(
        event: RawTouchEvent,
        buttons: List<ButtonPosition>,
        childBounds: Map<String, android.graphics.Rect>,
        prevSwipeActive: Set<String>,
        prevSlotState: OldSlotState,
        lastButtonState: Int,
        cellW: Float,
        cellH: Float,
        isSwipeMode: Boolean,
    ): InteractionResult

    /**
     * Edit mode: drag, resize, follow-area adjustment.
     */
    fun dispatchEdit(
        event: RawTouchEvent,
        buttons: List<ButtonPosition>,
        currentButtonBounds: Map<String, android.graphics.Rect>,
        children: List<android.view.View>,
        currentState: EditModeState,
        cellW: Float,
        cellH: Float,
        density: Float = 1f,
        selectedButtonId: String? = null,
    ): EditDispatchResult
}