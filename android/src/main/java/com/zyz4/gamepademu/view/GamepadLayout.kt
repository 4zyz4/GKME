package com.zyz4.gamepademu.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.GyroOrientation
import com.zyz4.gamepademu.model.LayoutPreset
import com.zyz4.gamepademu.view.JoystickView
import com.zyz4.gamepademu.view.CustomKeypadView
import com.zyz4.gamepademu.view.inputdispatcher.GamepadInputDispatcher
import com.zyz4.gamepademu.view.inputdispatcher.LayoutEngine
import com.zyz4.gamepademu.view.inputdispatcher.EditModeState
import com.zyz4.gamepademu.view.inputdispatcher.OldSlotState
import com.zyz4.gamepademu.view.inputdispatcher.SlotMatcher
import com.zyz4.gamepademu.view.inputdispatcher.EditCommand
import com.zyz4.gamepademu.view.inputdispatcher.toRawEvent
import com.zyz4.gamepademu.view.inputdispatcher.InteractionResult
import com.zyz4.gamepademu.view.inputdispatcher.TouchpadClickDetector
import com.zyz4.gamepademu.view.inputdispatcher.MouseInputDispatcher
import com.zyz4.gamepademu.view.inputdispatcher.isTouchpadId

class GamepadLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val dispatcher = GamepadInputDispatcher(
        layoutEngine = LayoutEngine,
        hitResolver = com.zyz4.gamepademu.view.inputdispatcher.HitResolver,
        followAreaStrategy = com.zyz4.gamepademu.view.inputdispatcher.FollowAreaStrategy,
        swipeTriggerStrategy = com.zyz4.gamepademu.view.inputdispatcher.SwipeTriggerStrategy,
        slotMatcher = com.zyz4.gamepademu.view.inputdispatcher.SlotMatcher,
        clickDetector = com.zyz4.gamepademu.view.inputdispatcher.TouchpadClickDetector,
    )

    private val gamepadRenderer = GamepadRenderer(
        gridCols = GRID_COLS,
        handleSizeDp = HANDLE_SIZE_DP.toFloat(),
        gridBaseAlpha = GRID_BASE_ALPHA,
        markerDistDp = 4f,
        density = density,
    )

    private val gamepadEditGesture = GamepadEditGesture()

    private val gamepadFollowAreaTrigger = GamepadFollowAreaTrigger(
        dispatchToChild = { child, event, action, pointerIdx -> dispatchToChild(child, event, action, pointerIdx) },
        setForceFollowFinger = { child, enabled ->
            when (child) {
                is JoystickView -> child.forceFollowFinger = enabled
                is DpadPadView -> child.forceFollowFinger = enabled
                is CustomKeypadView -> child.forceFollowFinger = enabled
            }
        },
        setTouchTargets = { pid, targets -> touchSession.touchTargets[pid] = targets.toMutableList() },
    )

    private val gamepadLayoutApplier = GamepadLayoutApplier()

    private var _editState = EditModeState()
    private var _slotState = OldSlotState()
    private var _dispatcherLastButtonState = 0
    private var _swipeActive = emptySet<String>()

    init {
        setWillNotDraw(false)
        setClipChildren(false)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        bringSettingsToFront()
    }

    companion object {
        const val GRID_COLS = 120
        const val SETTINGS_BUTTON_ID = "btnSettings"
        private const val HANDLE_SIZE_DP = 8f
        private const val HANDLE_HIT_DP = 16f
        private const val GRID_BASE_ALPHA = 170
        private val JOYSTICK_IDS = setOf("leftJoystick", "rightJoystick")
    }

    // Grid fade animation
    private var gridAlpha = 0f
    private var gridAnimator: ValueAnimator? = null

    // Transparency preview
    private var previewTransparency = false
    private var previewIdleTransparency = true
    private var previewButtonId: String? = null


    private var cellW = 0f
    private var cellH = 0f
    private var appearanceSettings: AppSettings? = null

    var currentButtons: List<ButtonPosition> = emptyList()
        private set
    var currentGyroOrientation: GyroOrientation? = null
        private set
    private var isEditMode = false
    var selectedButtonId: String? = null
        private set
    private var editSnapshot: LayoutPreset? = null
    var hasChanges = false
        private set

    var isAdjustingFollowArea = false
        private set
    var adjustingFollowAreaId: String? = null

    private var draggingChild: View? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var resizingChild: View? = null
    private var resizeStartW = 0
    private var resizeStartH = 0
    private var resizeStartGridX = 0
    private var resizeStartGridY = 0

    // Follow area drag/resize state
    private var draggingFollowArea = false
    private var resizingFollowArea = false
    private var followAreaStartX = 0
    private var followAreaStartY = 0
    private var followAreaStartW = 0
    private var followAreaStartH = 0
    private var followAreaDragStartX = 0
    private var followAreaDragStartY = 0

    var listener: GamepadLayoutListener? = null

    private fun animateGridTo(targetAlpha: Float) {
        gridAnimator?.cancel()
        if (gridAlpha == targetAlpha) return
        gridAnimator = ValueAnimator.ofFloat(gridAlpha, targetAlpha).apply {
            duration = 200L
            addUpdateListener { anim ->
                gridAlpha = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setTransparencyPreview(buttonId: String, isIdle: Boolean, previewing: Boolean) {
        previewTransparency = previewing
        previewIdleTransparency = isIdle
        previewButtonId = if (previewing) buttonId else null
        requestLayout()
    }

    /** Swipe-trigger state: tracks which buttons are currently pressed (buttonId -> child View) */
    private val activeSwipeButtons = HashMap<String, View>()

    /** Set of button IDs that have swipeTrigger enabled */
    private var swipeTriggerIds: Set<String> = emptySet()

    // MARK: - Dispatcher wiring

    fun setTouchpadCaptureMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (enabled == hasPointerCapture()) return
        if (enabled) {
            if (!isFocused) requestFocus()
            requestPointerCapture()
        } else {
            releasePointerCapture()
        }
    }

    var oldButtonState = 0

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return super.onCapturedPointerEvent(event)

        // ── Mouse mode via pointer capture ──
        // NOTE: Mousepad now uses touch events via setupMousepadView() instead of
        // pointer capture.  This pointer capture path is used for touchpad input
        // (physical controller touchpad emulation) only.
        //
        // We keep hasMousepad check here so that external code can still check
        // the property, but mouse events no longer flow through this method.
        if (hasMousepad) {
            // Mousepad does not use pointer capture anymore — route captured
            // events through MouseInputDispatcher for legacy support.
            val result = MouseInputDispatcher.dispatch(
                actionMasked = event.actionMasked,
                pointerCount = event.pointerCount,
                event = event,
                prevState = mouseModeState,
                doubleTapDrag = false,
                keepLeftDown = true,
                sensitivity = 1.0f,
            )
            mouseModeState = result.newState
            onCapturedMouseReport?.invoke(
                result.dx, result.dy, result.wheelV, result.wheelH,
                result.buttonDown, result.buttonUp,
                1.0f, event
            )
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                _touchpadClick = false
            }
            return true
        }

        val newButtonState = event.buttonState
        val clickResult = TouchpadClickDetector.detect(oldButtonState, newButtonState)

        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            _touchpadClick = false
            slot0Active = false
            slot0X = 0f
            slot0Y = 0f
            slot1Active = false
            slot1X = 0f
            slot1Y = 0f
        } else {
            _slotState = OldSlotState(slot0Active = slot0Active, slot0X = slot0X, slot0Y = slot0Y,
                    slot1Active = slot1Active, slot1X = slot1X, slot1Y = slot1Y)

            val raw = event.toRawEvent()
            val result = dispatcher.dispatchInteraction(
                raw, currentButtons, emptyMap(),
                emptySet(), _slotState, oldButtonState,
                cellW, cellH, false,
            )

            _slotState = result.newSlotState
            oldButtonState = newButtonState

            slot0Active = result.newSlotState.slot0Active
            slot0X = result.newSlotState.slot0X
            slot0Y = result.newSlotState.slot0Y
            slot1Active = result.newSlotState.slot1Active
            slot1X = result.newSlotState.slot1X
            slot1Y = result.newSlotState.slot1Y

            _touchpadClick = newButtonState and MotionEvent.BUTTON_PRIMARY != 0
        }

        listener?.onTouchpadEvent(
            if (slot0Active) slot0X else (if (slot1Active) slot1X else 0.5f),
            if (slot0Active) slot0Y else (if (slot1Active) slot1Y else 0.5f),
            listOf(floatArrayOf(0f, slot0X, slot0Y, if (slot0Active) 1f else 0f),
                   floatArrayOf(1f, slot1X, slot1Y, if (slot1Active) 1f else 0f)),
            slot0Active || slot1Active, _touchpadClick
        )
        return true
    }

    private var slot0Pid = -1
    private var slot1Pid = -1

    private fun pointerIdInSlot(pid: Int, slot: Int): Boolean =
        when (slot) {
            0 -> pid == slot0Pid
            1 -> pid == slot1Pid
            else -> false
        }

    private var slot0X = 0f
    private var slot0Y = 0f
    private var slot0Active = false
    private var slot1X = 0f
    private var slot1Y = 0f
    private var slot1Active = false

    // ── Mouse mode state ──
    private var mouseModeState = MouseInputDispatcher.State()
    private var _touchpadClick = false

    /** True when the current layout includes a mousepad control (id starts with "mousepad"). */
    val hasMousepad: Boolean
        get() = currentButtons.any { it.id.startsWith("mousepad") }
            || (0 until childCount).any { (getChildAt(it).tag as? String)?.startsWith("mousepad") == true }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode) {
            // In edit mode, bypass all children and route directly to onTouchEvent
            // so the parent can handle dragging/resizing, instead of children consuming them.
            return onTouchEvent(event)
        }
        if (!isEditMode) {
            if (swipeTriggerIds.isNotEmpty()) {
                return handleSwipeTriggerTouch(event)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerDown(event, 0)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pointerDown(event, event.actionIndex)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchSession.touchpadTarget != null) {
                        dispatchFilteredToTouchpad(touchSession.touchpadTarget!!, event)
                    }
                    if (touchSession.mousepadTarget != null) {
                        dispatchFilteredToTouchpad(touchSession.mousepadTarget!!, event, pointerIds = touchSession.mousepadPointerIds)
                    }
                    for ((pid, children) in touchSession.touchTargets.toMap()) {
                        val idx = event.findPointerIndex(pid)
                        if (idx >= 0) {
                            for (child in children) {
                                dispatchToChild(child, event, MotionEvent.ACTION_MOVE, idx)
                            }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (touchSession.touchpadTarget != null) {
                        val idx = event.actionIndex
                        val liftedPid = event.getPointerId(idx)
                        if (liftedPid in touchSession.touchpadPointerIds) {
                            dispatchFilteredToTouchpad(touchSession.touchpadTarget!!, event, listOf(idx))
                        }
                    }
                    if (touchSession.mousepadTarget != null) {
                        val idx = event.actionIndex
                        val liftedPid = event.getPointerId(idx)
                        if (liftedPid in touchSession.mousepadPointerIds) {
                            dispatchFilteredToTouchpad(touchSession.mousepadTarget!!, event, listOf(idx), touchSession.mousepadPointerIds)
                        }
                    }
                    pointerUp(event, event.actionIndex)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (touchSession.touchpadTarget != null) {
                        dispatchFilteredToTouchpad(touchSession.touchpadTarget!!, event)
                        touchSession.touchpadTarget = null
                    }
                    touchSession.touchpadPointerIds.clear()
                    if (touchSession.mousepadTarget != null) {
                        dispatchFilteredToTouchpad(touchSession.mousepadTarget!!, event, pointerIds = touchSession.mousepadPointerIds)
                        touchSession.mousepadTarget = null
                    }
                    touchSession.mousepadPointerIds.clear()
                    for ((_, children) in touchSession.touchTargets.toMap()) {
                        for (child in children) {
                            dispatchToChild(child, event, MotionEvent.ACTION_UP, 0)
                        }
                    }
                    resetForceFollowFinger()
                    touchSession.touchTargets.clear()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (touchSession.touchpadTarget != null) {
                        dispatchFilteredToTouchpad(touchSession.touchpadTarget!!, event)
                        touchSession.touchpadTarget = null
                    }
                    touchSession.touchpadPointerIds.clear()
                    if (touchSession.mousepadTarget != null) {
                        dispatchFilteredToTouchpad(touchSession.mousepadTarget!!, event, pointerIds = touchSession.mousepadPointerIds)
                        touchSession.mousepadTarget = null
                    }
                    touchSession.mousepadPointerIds.clear()
                    for ((_, children) in touchSession.touchTargets) {
                        for (child in children) {
                            val ev = MotionEvent.obtain(
                                event.downTime, event.eventTime,
                                MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                            )
                            child.dispatchTouchEvent(ev)
                            ev.recycle()
                        }
                    }
                    resetForceFollowFinger()
                    touchSession.touchTargets.clear()
                    return true
                }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    // ── Swipe-trigger touch handling ─────────────────────────
    //
    // Phase 3: Delegates swipe press/release logic to SwipeTriggerStrategy
    // via dispatcher.dispatchInteraction().
    //
    // Each swipe-triggered button independently checks ALL pointers.
    // If ANY pointer is within a button's bounds → pressed.
    // If NO pointer is within bounds → released.
    // This allows multiple swipe buttons to be pressed simultaneously.

    private fun getChildBounds(): Map<String, android.graphics.Rect> {
        val map = mutableMapOf<String, android.graphics.Rect>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.VISIBLE) {
                val id = getButtonId(c) ?: continue
                val pos = currentButtons.find { it.id == id }
                if (pos != null) {
                    val vb = visualBounds(pos)
                    val gx = vb[0] * cellW
                    val gy = vb[1] * cellH
                    val gw = vb[2] * cellW
                    val gh = vb[3] * cellH
                    map[id] = android.graphics.Rect(gx.toInt(), gy.toInt(), (gx + gw).toInt(), (gy + gh).toInt())
                } else {
                    map[id] = android.graphics.Rect(c.left, c.top, c.right, c.bottom)
                }
            }
        }
        return map
    }

    private fun runSwipeEvaluation(event: MotionEvent): InteractionResult {
        _dispatcherLastButtonState = event.buttonState
        val raw = event.toRawEvent()
        return dispatcher.dispatchInteraction(
            raw, currentButtons, getChildBounds(),
            activeSwipeButtons.keys.toSet(), _slotState, _dispatcherLastButtonState,
            cellW, cellH, true,
        )
    }

    private fun applySwipeResult(result: InteractionResult, event: MotionEvent, excludePid: Int? = null) {
        _swipeActive = result.newSwipeActive
        _slotState = result.newSlotState
        _dispatcherLastButtonState = result.newLastButtonState

        for (cid in result.releasedIds) {
            val child = activeSwipeButtons[cid] ?: continue
            dispatchToChild(child, event, MotionEvent.ACTION_UP, 0)
            activeSwipeButtons.remove(cid)
        }

        for (cid in result.pressedIds) {
            if (cid !in activeSwipeButtons) {
                val child = findViewById(cid) ?: continue
                var ptrIdx = 0
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid == excludePid) continue
                    val ex = event.getX(i)
                    val ey = event.getY(i)
                    if (child.left <= ex && ex <= child.right && child.top <= ey && ey <= child.bottom) {
                        ptrIdx = i
                        break
                    }
                }
                dispatchToChild(child, event, MotionEvent.ACTION_DOWN, ptrIdx)
                activeSwipeButtons[cid] = child
            }
        }
    }

    private fun handleSwipeTriggerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val pid = event.getPointerId(0)
                val allChildren = findAllChildrenAt(x, y)
                val children = filterOverlapChildren(allChildren)
                // Settings button is always the topmost control: a tap on it only opens settings.
                val settingsChild = children.firstOrNull { getButtonId(it) == SETTINGS_BUTTON_ID }
                if (settingsChild != null) {
                    touchSession.touchTargets[pid] = mutableListOf(settingsChild)
                    dispatchToChild(settingsChild, event, MotionEvent.ACTION_DOWN, 0)
                    return true
                }
                val nonJoystickChildren = children.filter { it !is JoystickView && it !is DpadPadView && it !is CustomKeypadView }
                if (nonJoystickChildren.isEmpty()) {
                    if (tryFollowAreaTrigger(x, y, pid, event, 0)) return true
                } else {
                    if (tryFollowAreaOverlapTrigger(x, y, pid, event, 0)) return true
                }
                val regularChildren = children.filter { getButtonId(it) !in swipeTriggerIds }
                if (regularChildren.isNotEmpty()) {
                    touchSession.touchTargets[pid] = regularChildren.toMutableList()
                    for (child in regularChildren) {
                        dispatchToChild(child, event, MotionEvent.ACTION_DOWN, 0)
                    }
                }
                val result = runSwipeEvaluation(event)
                applySwipeResult(result, event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                val allChildren = findAllChildrenAt(x, y)
                val children = filterOverlapChildren(allChildren)
                val settingsChild = children.firstOrNull { getButtonId(it) == SETTINGS_BUTTON_ID }
                if (settingsChild != null) {
                    touchSession.touchTargets[pid] = mutableListOf(settingsChild)
                    dispatchToChild(settingsChild, event, MotionEvent.ACTION_DOWN, idx)
                    return true
                }
                val nonJoystickChildren = children.filter { it !is JoystickView && it !is DpadPadView && it !is CustomKeypadView }
                if (nonJoystickChildren.isEmpty()) {
                    if (tryFollowAreaTrigger(x, y, pid, event, idx)) return true
                } else {
                    if (tryFollowAreaOverlapTrigger(x, y, pid, event, idx)) return true
                }
                val regularChildren = children.filter { getButtonId(it) !in swipeTriggerIds }
                if (regularChildren.isNotEmpty()) {
                    touchSession.touchTargets[pid] = regularChildren.toMutableList()
                    for (child in regularChildren) {
                        dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
                    }
                }
                val result = runSwipeEvaluation(event)
                applySwipeResult(result, event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for ((pid, children) in touchSession.touchTargets.toMap()) {
                    val idx = event.findPointerIndex(pid)
                    if (idx >= 0) {
                        for (child in children) {
                            dispatchToChild(child, event, MotionEvent.ACTION_MOVE, idx)
                        }
                    }
                }
                val result = runSwipeEvaluation(event)
                applySwipeResult(result, event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val children = touchSession.touchTargets.remove(pid)
                if (children != null) {
                    for (child in children) {
                        dispatchToChild(child, event, MotionEvent.ACTION_UP, idx)
                        when (child) {
                            is JoystickView -> child.forceFollowFinger = false
                            is DpadPadView -> child.forceFollowFinger = false
                            is CustomKeypadView -> child.forceFollowFinger = false
                        }
                    }
                }
                // Build a filtered RawTouchEvent without the lifted pointer
                val raw = event.toRawEvent()
                val filteredPointers = raw.pointers.filter { it.id != pid }
                val filteredRaw = raw.copy(pointers = filteredPointers)
                val result = dispatcher.dispatchInteraction(
                    filteredRaw, currentButtons, getChildBounds(),
                    activeSwipeButtons.keys.toSet(), _slotState, _dispatcherLastButtonState,
                    cellW, cellH, true,
                )
                applySwipeResult(result, event, excludePid = pid)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val terminalAction = if (event.actionMasked == MotionEvent.ACTION_UP) {
                    MotionEvent.ACTION_UP
                } else {
                    MotionEvent.ACTION_CANCEL
                }
                for ((_, children) in touchSession.touchTargets) {
                    for (child in children) {
                        val ev = MotionEvent.obtain(
                            event.downTime, event.eventTime,
                            terminalAction, 0f, 0f, 0
                        )
                        child.dispatchTouchEvent(ev)
                        ev.recycle()
                    }
                }
                touchSession.touchTargets.clear()
                resetForceFollowFinger()
                // Release all active swipe buttons
                for ((_, child) in activeSwipeButtons) {
                    val ev = MotionEvent.obtain(
                        event.downTime, event.eventTime,
                        MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                    )
                    child.dispatchTouchEvent(ev)
                    ev.recycle()
                }
                activeSwipeButtons.clear()
                return true
            }
        }
        return true
    }

    /** Find a child View by button id. */
    private fun findViewById(id: String): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (getButtonId(child) == id) return child
        }
        return null
    }

    private val touchSession = TouchSession()

    // ── Normal multi-touch dispatch (no swipe trigger) ───────

    private fun pointerDown(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val x = event.getX(idx)
        val y = event.getY(idx)
        val allChildren = findAllChildrenAt(x, y)
        val children = filterOverlapChildren(allChildren)
        val nonFollowAreaChildren = children.filter { it !is JoystickView && it !is DpadPadView && it !is CustomKeypadView }

        // Settings button is always the topmost control: a tap on it only opens settings.
        val settingsChild = children.firstOrNull { getButtonId(it) == SETTINGS_BUTTON_ID }
        if (settingsChild != null) {
            touchSession.touchTargets[pid] = mutableListOf(settingsChild)
            dispatchToChild(settingsChild, event, MotionEvent.ACTION_DOWN, idx)
            return
        }

        if (nonFollowAreaChildren.isEmpty()) {
            if (tryFollowAreaTrigger(x, y, pid, event, idx)) return
            if (children.isEmpty()) return
        } else {
            // Try follow-area trigger for controls that have it enabled, before falling through
            // to normal touch dispatch (which would make forceFollowFinger=false and miss the effect).
            if (tryFollowAreaTrigger(x, y, pid, event, idx)) return
            if (tryFollowAreaOverlapTrigger(x, y, pid, event, idx)) return
        }

        val tp = children.firstOrNull { getButtonId(it) == "touchpad" }
        if (tp != null) {
            touchSession.touchpadTarget = tp
            touchSession.touchpadPointerIds.add(pid)
            dispatchFilteredToTouchpad(tp, event)
            val otherChildren = children.filter { it != tp }
            if (otherChildren.isNotEmpty()) {
                touchSession.touchTargets[pid] = otherChildren.toMutableList()
                for (child in otherChildren) {
                    dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
                }
            }
            return
        }

        val mp = children.firstOrNull { getButtonId(it)?.startsWith("mousepad") == true }
        if (mp != null) {
            // Mousepad needs full multi-touch events so two-finger gestures
            // (scroll / right-click) work. Route it like the touchpad via the
            // pointer-preserving dispatcher instead of the single-pointer one.
            touchSession.mousepadTarget = mp
            touchSession.mousepadPointerIds.add(pid)
            dispatchFilteredToTouchpad(mp, event, pointerIds = touchSession.mousepadPointerIds)
            val otherChildren = children.filter { it != mp }
            if (otherChildren.isNotEmpty()) {
                touchSession.touchTargets[pid] = otherChildren.toMutableList()
                for (child in otherChildren) {
                    dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
                }
            }
            return
        }
        touchSession.touchTargets[pid] = children.toMutableList()
        for (child in children) {
            dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
        }
    }

    private fun pointerUp(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val children = touchSession.touchTargets.remove(pid)
        if (children != null) {
            for (child in children) {
                dispatchToChild(child, event, MotionEvent.ACTION_UP, idx)
                when (child) {
                    is JoystickView -> child.forceFollowFinger = false
                    is DpadPadView -> child.forceFollowFinger = false
                    is CustomKeypadView -> child.forceFollowFinger = false
                }
            }
        }
        touchSession.touchpadPointerIds.remove(pid)
        if (touchSession.touchpadPointerIds.isEmpty()) {
            touchSession.touchpadTarget = null
        }
        touchSession.mousepadPointerIds.remove(pid)
        if (touchSession.mousepadPointerIds.isEmpty()) {
            touchSession.mousepadTarget = null
        }
    }

    /** Deliver only touchpad-originated pointers to the touchpad child.
     *  When [indices] is given, only those pointer indices are included;
     *  otherwise all pointers whose ID is in [touchSession.touchpadPointerIds] are included. */
    private fun dispatchFilteredToTouchpad(
        child: View,
        event: MotionEvent,
        indices: List<Int>? = null,
        pointerIds: Set<Int> = touchSession.touchpadPointerIds,
    ) {
        GamepadTouchDispatchUtils.dispatchFilteredToTouchpad(child, event, pointerIds, indices)
    }

    private fun dispatchToChild(child: View, event: MotionEvent, action: Int, pointerIdx: Int) {
        GamepadTouchDispatchUtils.dispatchToChild(child, event, action, pointerIdx)
    }

    interface GamepadLayoutListener {
        fun onButtonSelected(buttonId: String?)
        fun onEditModeChanged(isEditMode: Boolean)
        fun onTouchpadEvent(
            x: Float, y: Float,
            touches: List<FloatArray>,
            touchpadTouch: Boolean, touchpadClick: Boolean
        )
    }

    /** Callback for mouse-mode: convert a MouseResult into a Bluetooth HID report. */
    var onCapturedMouseReport: ((
        dx: Short, dy: Short,
        wheelV: Short, wheelH: Short,
        buttonDown: Byte, buttonUp: Byte,
        sensitivity: Float, event: MotionEvent
    ) -> ByteArray)? = null

    var onCapturedTouchpadEvent: ((normalizedX: Float, normalizedY: Float,
        touches: List<FloatArray>, touchpadTouch: Boolean, touchpadClick: Boolean) -> Unit)? = null

    fun loadPreset(preset: LayoutPreset) {
        currentButtons = preset.buttons.map {
            if (it.id == "centerArea") it.copy(id = "touchpad", linearTriggerEnabled = false, slideDirection = com.zyz4.gamepademu.model.SlideDirection.DOWN) else it
        }.let { list ->
            if (list.none { it.id == SETTINGS_BUTTON_ID }) {
                list + ButtonPosition(
                    id = SETTINGS_BUTTON_ID,
                    x = (GRID_COLS - 6) / 2,
                    y = 0,
                    width = 6,
                    height = 6,
                    lockAspect = true,
                )
            } else {
                list
            }
        }.map {
            if (it.id == SETTINGS_BUTTON_ID) sanitizeSettingsButton(it) else it
        }.map { normalizeTouchpadArea(it) }
        currentGyroOrientation = preset.gyroOrientation
        hasChanges = false
        refreshSwipeTriggers()
        bringSettingsToFront()
        requestLayout()
    }

    /** Settings button: no rotation, swipe trigger always off, overlap trigger always on, fully visible on screen. */
    private fun sanitizeSettingsButton(pos: ButtonPosition): ButtonPosition {
        var p = pos.copy(rotation = 0, swipeTrigger = false, overlapTrigger = true, lockAspect = true,
            idleTransparency = 0, activeTransparency = 0,
            linearTriggerEnabled = false, slideDirection = com.zyz4.gamepademu.model.SlideDirection.DOWN)
        val maxCol = (GRID_COLS - p.width).coerceAtLeast(0)
        val maxRow = if (cellH > 0f) ((height / cellH).toInt() - p.height).coerceAtLeast(0) else Int.MAX_VALUE
        p = p.copy(
            x = p.x.coerceIn(0, maxCol),
            y = p.y.coerceIn(0, maxRow)
        )
        return p
    }

    private fun onScreenGridSize(pos: ButtonPosition): Pair<Int, Int> {
        return GamepadLayoutGeometry.onScreenGridSize(pos)
    }

    private fun touchpadContainedByArea(pos: ButtonPosition): Boolean {
        return GamepadLayoutGeometry.touchpadContainedByArea(pos)
    }

    private fun normalizeTouchpadArea(pos: ButtonPosition): ButtonPosition {
        return GamepadTouchpadUtils.normalizeTouchpadArea(pos)
    }

    private fun shrinkTouchpadToArea(pos: ButtonPosition): ButtonPosition {
        return GamepadTouchpadUtils.shrinkTouchpadToArea(pos)
    }

    /** Brings the settings button to the very top of the child stack so it is always the topmost control. */
    fun bringSettingsToFront() {
        for (i in childCount - 1 downTo 0) {
            if (getButtonId(getChildAt(i)) == SETTINGS_BUTTON_ID) {
                bringChildToFront(getChildAt(i))
                return
            }
        }
    }

    fun setFollowAreaAppearance(color: Int, strokeWidth: Int) {
        gamepadRenderer.setFollowAreaAppearance(color, strokeWidth.toFloat())
        invalidate()
    }

    fun setTouchpadAreaAppearance(color: Int, strokeWidth: Int) {
        gamepadRenderer.setTouchpadAreaAppearance(color, strokeWidth.toFloat())
        invalidate()
    }

    fun setDpadPadTriggerAreaAppearance(color: Int, strokeWidth: Int) {
        gamepadRenderer.setDpadPadTriggerAreaAppearance(color, strokeWidth.toFloat())
        invalidate()
    }

    fun applyAppearance(settings: AppSettings) {
        appearanceSettings = settings
        AppearanceApplier.applyToGamepadLayout(this, settings)
    }

    fun getPreset(): LayoutPreset {
        return LayoutPreset(version = 1, buttons = currentButtons.toList(), gyroOrientation = currentGyroOrientation)
    }

    fun enterEditMode() {
        gamepadEditGesture.reset()
        editSnapshot = getPreset()
        isEditMode = true
        hasChanges = false
        previewTransparency = false
        previewButtonId = null
        animateGridTo(0f)
        listener?.onEditModeChanged(true)
        invalidate()
        requestLayout()
    }

    fun exitEditMode() {
        gamepadEditGesture.reset()
        isEditMode = false
        selectedButtonId = null
        isAdjustingFollowArea = false
        adjustingFollowAreaId = null
        syncJoystickSelection()
        draggingChild = null
        resizingChild = null
        editSnapshot = null
        previewTransparency = false
        previewButtonId = null
        animateGridTo(0f)
        listener?.onEditModeChanged(false)
        invalidate()
        requestLayout()
    }

    fun enterFollowAreaAdjust(buttonId: String) {
        isAdjustingFollowArea = true
        adjustingFollowAreaId = buttonId
        _editState = _editState.copy(
            isAdjustingFollowArea = true,
            adjustingFollowAreaId = buttonId,
        )
        gamepadEditGesture.isAdjustingFollowArea = true
        gamepadEditGesture.adjustingFollowAreaId = buttonId
        setSelectedButton(buttonId)
        listener?.onButtonSelected(buttonId)
        invalidate()
    }

    fun exitFollowAreaAdjust() {
        isAdjustingFollowArea = false
        adjustingFollowAreaId = null
        _editState = _editState.copy(
            isAdjustingFollowArea = false,
            adjustingFollowAreaId = null,
            followAreaDragStart = null,
        )
        gamepadEditGesture.isAdjustingFollowArea = false
        gamepadEditGesture.adjustingFollowAreaId = null
        invalidate()
    }

    fun isEditModeActive(): Boolean = isEditMode

    fun getRotation(buttonId: String): Int {
        return currentButtons.find { it.id == buttonId }?.rotation ?: 0
    }

    fun getCellSize(): Float = cellW

    fun hasUnsavedChanges(): Boolean = hasChanges

    fun getEditSnapshot(): LayoutPreset? = editSnapshot

    fun discardToSnapshot() {
        editSnapshot?.let { loadPreset(it) }
        hasChanges = false
        requestLayout()
    }

    fun updateButtonPosition(id: String, updated: ButtonPosition) {
        val idx = currentButtons.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val newPos = if (id == SETTINGS_BUTTON_ID) sanitizeSettingsButton(updated) else normalizeTouchpadArea(updated)
            currentButtons = currentButtons.toMutableList().also {
                it[idx] = newPos
            }
            hasChanges = true
            refreshSwipeTriggers()
            // Reset edit gesture state to prevent stale drag state when position is updated externally
            gamepadEditGesture.reset()
            draggingChild = null
            resizingChild = null
            draggingFollowArea = false
            resizingFollowArea = false
            requestLayout()
            invalidate()
        }
    }

    fun addButtonPosition(pos: ButtonPosition) {
        val newPos = if (pos.id == SETTINGS_BUTTON_ID) sanitizeSettingsButton(pos) else pos
        currentButtons = currentButtons.toMutableList().also { it.add(newPos) }
        hasChanges = true
        refreshSwipeTriggers()
        bringSettingsToFront()
        requestLayout()
    }

    fun removeButtonPosition(id: String) {
        if (id == SETTINGS_BUTTON_ID) return
        currentButtons = currentButtons.toMutableList().also { it.removeAll { b -> b.id == id } }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (getButtonId(child) == id) {
                removeView(child)
                break
            }
        }
        if (selectedButtonId == id) {
            selectedButtonId = null
            syncJoystickSelection()
        }
        hasChanges = true
        refreshSwipeTriggers()
        requestLayout()
    }

    fun setSelectedButton(id: String?) {
        // Prevent deselection or switching controls during follow area adjustment
        if (isAdjustingFollowArea && adjustingFollowAreaId != null) {
            if (id != adjustingFollowAreaId) return
        }
        if (selectedButtonId != id) {
            selectedButtonId = id
            syncJoystickSelection()
            listener?.onButtonSelected(id)
            invalidate()
        }
    }

    private fun syncJoystickSelection() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is JoystickView) {
                val show = isEditMode && getButtonId(child) == selectedButtonId
                if (child.showDeadZoneIndicator != show) {
                    child.showDeadZoneIndicator = show
                    child.invalidate()
                }
            }
        }
    }

    /** Mark selection by tapping a child in edit mode */
    private fun selectChildAt(x: Float, y: Float) {
        val child = findChildAt(x, y)
        if (child != null) {
            val cid = getButtonId(child)
            if (cid != null) {
                setSelectedButton(cid)
            }
        } else {
            setSelectedButton(null)
        }
    }

    private fun refreshSwipeTriggers() {
        swipeTriggerIds = currentButtons.filter { it.swipeTrigger }.map { it.id }.toSet()
    }

/** Activate follow-area trigger for a joystick or dpadPad.
      *  Only fires if the finger is inside the follow-area rect AND not inside any overlapping
      *  control's visual bounds (unless that control has overlapTrigger=true).
      *  Returns true if a matching view was found and dispatched. */
    private fun tryFollowAreaTrigger(x: Float, y: Float, pid: Int, event: MotionEvent, idx: Int): Boolean {
        val children = (0 until childCount).asSequence().map { getChildAt(it) }.toList()
        return gamepadFollowAreaTrigger.tryFollowAreaTrigger(
            x, y, pid, event, idx,
            children,
            ::findAllChildrenAt,
            ::childToButtonPosition,
            cellW, cellH,
        )
    }

    /** Called when a touch point lands inside a non-joystick control's bounds AND inside a follow-area rect.
      *  Only fires if [followAreaOverlapTrigger] is true. This allows the follow-area control to activate
      *  even though another control visually covers the touch point. */
    private fun tryFollowAreaOverlapTrigger(x: Float, y: Float, pid: Int, event: MotionEvent, idx: Int): Boolean {
        val children = (0 until childCount).asSequence().map { getChildAt(it) }.toList()
        return gamepadFollowAreaTrigger.tryFollowAreaOverlapTrigger(
            x, y, pid, event, idx,
            children,
            ::findAllChildrenAt,
            ::childToButtonPosition,
            cellW, cellH,
        )
    }

    private fun childToButtonPosition(child: View): ButtonPosition? {
        val cid = getButtonId(child) ?: return null
        return currentButtons.find { it.id == cid }
    }

    private fun findChildAt(x: Float, y: Float): View? {
        return GamepadLayoutGeometry.findChildAt(x, y, (0 until childCount).asSequence().map { getChildAt(it) }.toList())
    }

    private fun resetForceFollowFinger() {
        GamepadTouchDispatchUtils.resetForceFollowFinger((0 until childCount).asSequence().map { getChildAt(it) }.toList())
    }

    private fun getButtonId(child: View): String? {
        val tag = child.tag as? String
        if (tag != null) return tag
        return try {
            context.resources.getResourceEntryName(child.id)
        } catch (e: Exception) {
            null
        }
    }

    // Adaptive fill: content (text / PS foreground icon / image) fills the button,
    // keeping a min(w,h) x 10% padding. Buttons whose icon IS their background
    // (XBOX/SWITCH select & menu, touchpad, LS/RS) are excluded.
    private fun isAdaptiveContentButton(id: String, child: View) =
        AppearanceApplier.isAdaptiveContentButton(id, child)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        cellW = w.toFloat() / GRID_COLS
        cellH = cellW

        val buttonMap = currentButtons.associateBy { it.id }.toMap()

        // Delegate child layout configuration to GamepadLayoutApplier
        gamepadLayoutApplier.applyLayout(
            childCount = childCount,
            getChildAt = { i -> getChildAt(i) },
            getButtonId = ::getButtonId,
            buttons = buttonMap,
            cellW = cellW,
            cellH = cellH,
            selectedButtonId = selectedButtonId,
            isEditMode = isEditMode,
            previewTransparency = previewTransparency,
            previewButtonId = previewButtonId,
            previewIdleTransparency = previewIdleTransparency,
            isAdaptiveContentButton = ::isAdaptiveContentButton,
            contentCapPx = { view, settings -> AppearanceApplier.contentCapPx(view, settings) },
            applyContentTextCap = { btn, capPx -> AppearanceApplier.applyContentTextCap(btn, capPx) },
            getRotation = ::getRotation,
        )

        // Apply per-child config that the applier doesn't cover: adaptive padding,
        // content text cap, joystick/dpadPad/keypad-specific properties, rotation.
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val id = getButtonId(child) ?: continue
            val pos = buttonMap[id] ?: continue

            // Adaptive content padding
            if (isAdaptiveContentButton(id, child)) {
                val pad = (minOf(child.width, child.height) * 0.1f).toInt()
                if (child.paddingLeft != pad || child.paddingTop != pad) {
                    child.setPadding(pad, pad, pad, pad)
                }
            }

            // Content text cap
            val capPx = AppearanceApplier.contentCapPx(child, appearanceSettings)
            if (child is Button && !child.text.isNullOrEmpty()) {
                AppearanceApplier.applyContentTextCap(
                    child, capPx ?: AppearanceApplier.UNLIMITED_TEXT_CAP_PX
                )
            }

            // JoystickView-specific properties
            if (child is JoystickView) {
                child.sensitivityCurve = pos.sensitivityCurve
                child.doubleClickEnable = pos.doubleClickEnable
            }

            // DpadPadView-specific properties
            if (child is DpadPadView) {
                child.arrowMaxSizePx = AppearanceApplier.contentCapPx(child, appearanceSettings)?.toFloat()
            }

            // CustomKeypadView-specific properties
            if (child is CustomKeypadView) {
                child.keypadTexts = ButtonPosition.keypadTextsOf(pos)
                child.keypadCenterDoubleClick = pos.keypadCenterDoubleClick
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        gamepadRenderer.gridAlpha = gridAlpha

        // Delegate rendering to GamepadRenderer
        gamepadRenderer.render(
            canvas = canvas,
            width = width,
            height = height,
            cellW = cellW,
            cellH = cellH,
            buttons = currentButtons,
            selectedButtonId = selectedButtonId,
            isEditMode = isEditMode,
            adjustingFollowAreaId = adjustingFollowAreaId,
            isAdjustingFollowArea = isAdjustingFollowArea,
        )
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return isEditMode
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) return false

        val raw = event.toRawEvent()
        val bounds = mutableMapOf<String, android.graphics.Rect>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val id = getButtonId(child)
            if (id != null) {
                bounds[id] = android.graphics.Rect(child.left, child.top, child.right, child.bottom)
            }
        }
        val allChildren = (0 until childCount).map { i -> getChildAt(i) }

        fun applyEditCommands(commands: List<EditCommand>) {
            if (commands.isEmpty()) return
            var updated = currentButtons
            for (cmd in commands) {
                updated = cmd.applyTo(updated)
            }
            if (updated !== currentButtons) {
                currentButtons = updated
                hasChanges = true
                requestLayout()
            }
        }

        fun applyEditCommandsNoMove(commands: List<EditCommand>) {
            if (commands.isEmpty()) return
            var updated = currentButtons
            for (cmd in commands) {
                if (cmd is EditCommand.MoveButton) continue
                updated = cmd.applyTo(updated)
            }
            if (updated !== currentButtons) {
                currentButtons = updated
                hasChanges = true
                requestLayout()
            }
        }

        // Delegate edit-mode gesture dispatch to GamepadEditGesture
        val output = gamepadEditGesture.dispatch(
            event = event,
            raw = raw,
            buttons = currentButtons,
            buttonBounds = bounds,
            allChildren = allChildren,
            cellW = cellW,
            cellH = cellH,
            density = density,
            dispatcher = dispatcher,
            findChildAt = ::findChildAt,
        )

        // Sync edit-mode state from gesture output
        isAdjustingFollowArea = output.syncIsAdjustingFollowArea
        adjustingFollowAreaId = output.syncAdjustingFollowAreaId
        val previousDragging = draggingChild
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            draggingChild = output.syncDraggingChild
            resizingChild = output.syncResizingChild
            draggingFollowArea = output.syncDraggingFollowArea
            resizingFollowArea = output.syncResizingFollowArea
        }

        // Apply state-change commands (no MoveButton) on down
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            applyEditCommandsNoMove(output.commandsNoMove)

            // Wire drag/resize start state to local fields
            if (output.selectDraggingChild != null) {
                draggingChild = findChildAt(event.x, event.y)
                if (draggingChild != null) {
                    dragOffsetX = event.x - draggingChild!!.left
                    dragOffsetY = event.y - draggingChild!!.top
                    animateGridTo(1f)
                }
            }
            if (output.selectResizingChild != null) {
                resizingChild = findChildAt(event.x, event.y)
                if (resizingChild != null) {
                    val pos = currentButtons.find { it.id == output.selectResizingChild }
                    if (pos != null) {
                        resizeStartW = pos.width
                        resizeStartH = pos.height
                        animateGridTo(1f)
                    }
                }
            }
        }

        // Apply all commands on move
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            applyEditCommands(output.commands)
            if (draggingChild != null) {
                val id = getButtonId(draggingChild!!)
                if (id != null) {
                    setSelectedButton(id)
                }
            }
        }

        // On up/cancel: finalize and select button
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (isAdjustingFollowArea || adjustingFollowAreaId != null) {
                val id = adjustingFollowAreaId
                if (id != null) {
                    setSelectedButton(id)
                }
            }
            if (resizingChild != null) {
                val id = getButtonId(resizingChild!!)
                if (id != null) {
                    // Force refresh floating editor by calling listener directly,
                    // since setSelectedButton won't trigger when selectedButtonId == id.
                    // This updates SeekBar values after drag-resize.
                    listener?.onButtonSelected(id)
                    setSelectedButton(id)
                }
                resizingChild = null
            }
            if (draggingChild != null) {
                val id = getButtonId(draggingChild!!)
                if (id != null) {
                    setSelectedButton(id)
                }
                draggingChild = null
            }
            draggingFollowArea = false
            resizingFollowArea = false
            animateGridTo(0f)
        }

        return true
    }

    private fun isOnFollowAreaHandle(x: Float, y: Float, pos: ButtonPosition): Boolean {
        return gamepadFollowAreaTrigger.isOnFollowAreaHandle(x, y, pos, cellW, cellH, HANDLE_HIT_DP * density)
    }

    private fun isInFollowArea(x: Float, y: Float, pos: ButtonPosition): Boolean {
        return gamepadFollowAreaTrigger.isInFollowArea(x, y, pos, cellW, cellH)
    }

    private fun isTouchpadId(id: String): Boolean = com.zyz4.gamepademu.view.inputdispatcher.isTouchpadId(id)

    /** Returns all visible children at (x,y), topmost first.
     *  Uses grid-coordinate based bounds from [currentButtons] instead of viewport pixel bounds
     *  ([left/right/top/bottom]) to avoid missing hits on some Android devices where view
     *  bounds may not be synchronised with the grid layout at dispatch time. */
    private fun findAllChildrenAt(x: Float, y: Float): List<View> {
        return GamepadLayoutGeometry.findAllChildrenAt(
            x, y, cellW, cellH,
            (0 until childCount).asSequence().map { getChildAt(it) }.toList(),
            currentButtons,
        )
    }

    private fun filterOverlapChildren(children: List<View>): List<View> {
        return GamepadLayoutGeometry.filterOverlapChildren(children, currentButtons)
    }

    /** Returns [left, top, width, height] in grid coordinates for the visual extent. */
    private fun visualBounds(pos: ButtonPosition): FloatArray {
        return GamepadLayoutGeometry.visualBounds(pos)
    }

    private val density: Float
        get() = context.resources.displayMetrics.density
}