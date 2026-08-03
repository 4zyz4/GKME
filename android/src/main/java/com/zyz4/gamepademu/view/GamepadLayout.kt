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

class GamepadLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

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

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(120, 120, 120)
        strokeWidth = 1f
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x3300ff00
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x1
        style = Paint.Style.FILL
    }

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x10000
        textAlign = Paint.Align.CENTER
    }

    private val followAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x666667  // gray
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val touchpadAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x666667  // same default as the joystick trigger area
        style = Paint.Style.STROKE
        strokeWidth = 3f
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return super.onCapturedPointerEvent(event)

        val action = event.actionMasked

        // Terminal actions: clear everything
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            slot0Active = false; slot0Pid = -1
            slot1Active = false; slot1Pid = -1
        } else {
            // Collect old slot positions for coordinate matching

            // Build candidates from current event (all pointers including released in POINTER_UP)
            val candidates = (0 until event.pointerCount).map { i ->
                floatArrayOf(
                    event.getPointerId(i).toFloat(),
                    (event.getX(i) / 1920f).coerceIn(0f, 1f),
                    (event.getY(i) / 942f).coerceIn(0f, 1f),
                )
            }

            // For POINTER_UP: remove released finger from candidates and clear its slot
            if (action == MotionEvent.ACTION_POINTER_UP) {
                val rIdx = event.actionIndex
                val rx = (event.getX(rIdx) / 1920f).coerceIn(0f, 1f)
                val ry = (event.getY(rIdx) / 942f).coerceIn(0f, 1f)
                if (slot0Active && slot1Active) {
                    // Two fingers: clear the slot nearest to the released position
                    val d0 = (slot0X - rx) * (slot0X - rx) + (slot0Y - ry) * (slot0Y - ry)
                    val d1 = (slot1X - rx) * (slot1X - rx) + (slot1Y - ry) * (slot1Y - ry)
                    if (d0 <= d1) { slot0Active = false; slot0Pid = -1 }
                    else { slot1Active = false; slot1Pid = -1 }
                } else if (slot0Active) {
                    slot0Active = false; slot0Pid = -1
                } else if (slot1Active) {
                    slot1Active = false; slot1Pid = -1
                }
            }

            // Rebuild remaining slots after potential clearing
            val remainingSlots = mutableListOf<Int>()
            if (slot0Active) remainingSlots.add(0)
            if (slot1Active) remainingSlots.add(1)

            // Match remaining candidates (after removing released finger) to remaining slots
            val remainingCandidates = if (action == MotionEvent.ACTION_POINTER_UP) {
                candidates.filterIndexed { i, _ -> i != event.actionIndex }
            } else {
                candidates
            }

            if (remainingCandidates.isNotEmpty()) {
                val usedCand = mutableSetOf<Int>()
                for (slotIdx in remainingSlots) {
                    val refX = if (slotIdx == 0) slot0X else slot1X
                    val refY = if (slotIdx == 0) slot0Y else slot1Y
                    var best = -1; var bestD = Float.MAX_VALUE
                    for (ci in remainingCandidates.indices) {
                        if (ci in usedCand) continue
                        val dx = refX - remainingCandidates[ci][1]
                        val dy = refY - remainingCandidates[ci][2]
                        val d = dx * dx + dy * dy
                        if (d < bestD) { bestD = d; best = ci }
                    }
                    if (best >= 0) {
                        val (pid, nx, ny) = remainingCandidates[best]
                        if (slotIdx == 0) { slot0X = nx; slot0Y = ny; slot0Pid = pid.toInt(); slot0Active = true }
                        else { slot1X = nx; slot1Y = ny; slot1Pid = pid.toInt(); slot1Active = true }
                        usedCand.add(best)
                    }
                }
                // Any unmatched candidates go to empty slots
                for (ci in remainingCandidates.indices) {
                    if (ci in usedCand) continue
                    val (pid, nx, ny) = remainingCandidates[ci]
                    if (!slot0Active) { slot0X = nx; slot0Y = ny; slot0Pid = pid.toInt(); slot0Active = true }
                    else if (!slot1Active) { slot1X = nx; slot1Y = ny; slot1Pid = pid.toInt(); slot1Active = true }
                }
            }
        }

        // Track button state changes (XOR approach from Moonlight Android)
        val curBS = event.buttonState
        val changedBS = curBS xor _lastButtonState
        if ((changedBS and MotionEvent.BUTTON_PRIMARY) != 0) {
            _touchpadClick = (curBS and MotionEvent.BUTTON_PRIMARY) != 0
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            _touchpadClick = false
        }
        _lastButtonState = curBS

        // Build slots list for proto (slot 0 first, slot 1 second)
        val allTouches = mutableListOf<FloatArray>()
        allTouches.add(floatArrayOf(0f, slot0X, slot0Y, if (slot0Active) 1f else 0f))
        allTouches.add(floatArrayOf(1f, slot1X, slot1Y, if (slot1Active) 1f else 0f))

        listener?.onTouchpadEvent(
            if (slot0Active) slot0X else (if (slot1Active) slot1X else 0.5f),
            if (slot0Active) slot0Y else (if (slot1Active) slot1Y else 0.5f),
            allTouches, slot0Active || slot1Active, _touchpadClick
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

    private var _lastButtonState = 0
    private var _touchpadClick = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
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
                    if (touchpadTarget != null) {
                        dispatchFilteredToTouchpad(touchpadTarget!!, event)
                    }
                    for ((pid, children) in touchTargets.toMap()) {
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
                    if (touchpadTarget != null) {
                        val idx = event.actionIndex
                        val liftedPid = event.getPointerId(idx)
                        if (liftedPid in touchpadPointerIds) {
                            dispatchFilteredToTouchpad(touchpadTarget!!, event, listOf(idx))
                        }
                    }
                    pointerUp(event, event.actionIndex)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (touchpadTarget != null) {
                        dispatchFilteredToTouchpad(touchpadTarget!!, event)
                        touchpadTarget = null
                    }
                    touchpadPointerIds.clear()
                    for ((_, children) in touchTargets.toMap()) {
                        for (child in children) {
                            dispatchToChild(child, event, MotionEvent.ACTION_UP, 0)
                        }
                    }
                    resetForceFollowFinger()
                    touchTargets.clear()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (touchpadTarget != null) {
                        dispatchFilteredToTouchpad(touchpadTarget!!, event)
                        touchpadTarget = null
                    }
                    touchpadPointerIds.clear()
                    for ((_, children) in touchTargets) {
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
                    touchTargets.clear()
                    return true
                }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    // ── Swipe-trigger touch handling ─────────────────────────
    //
    // Each swipe-triggered button independently checks ALL pointers.
    // If ANY pointer is within a button's bounds → pressed.
    // If NO pointer is within bounds → released.
    // This allows multiple swipe buttons to be pressed simultaneously.

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
                    touchTargets[pid] = mutableListOf(settingsChild)
                    dispatchToChild(settingsChild, event, MotionEvent.ACTION_DOWN, 0)
                    return true
                }
                val nonJoystickChildren = children.filter { it !is JoystickView }
                if (nonJoystickChildren.isEmpty()) {
                    if (tryFollowAreaTrigger(x, y, pid, event, 0)) return true
                } else {
                    if (tryFollowAreaOverlapTrigger(x, y, pid, event, 0)) return true
                }
                val regularChildren = children.filter { getButtonId(it) !in swipeTriggerIds }
                if (regularChildren.isNotEmpty()) {
                    touchTargets[pid] = regularChildren.toMutableList()
                    for (child in regularChildren) {
                        dispatchToChild(child, event, MotionEvent.ACTION_DOWN, 0)
                    }
                }
                updateSwipeButtons(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                val allChildren = findAllChildrenAt(x, y)
                val children = filterOverlapChildren(allChildren)
                // Settings button is always the topmost control: a tap on it only opens settings.
                val settingsChild = children.firstOrNull { getButtonId(it) == SETTINGS_BUTTON_ID }
                if (settingsChild != null) {
                    touchTargets[pid] = mutableListOf(settingsChild)
                    dispatchToChild(settingsChild, event, MotionEvent.ACTION_DOWN, idx)
                    return true
                }
                val nonJoystickChildren = children.filter { it !is JoystickView }
                if (nonJoystickChildren.isEmpty()) {
                    if (tryFollowAreaTrigger(x, y, pid, event, idx)) return true
                } else {
                    if (tryFollowAreaOverlapTrigger(x, y, pid, event, idx)) return true
                }
                val regularChildren = children.filter { getButtonId(it) !in swipeTriggerIds }
                if (regularChildren.isNotEmpty()) {
                    touchTargets[pid] = regularChildren.toMutableList()
                    for (child in regularChildren) {
                        dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
                    }
                }
                updateSwipeButtons(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Non-swipe buttons: once pressed, stay pressed until finger lifts
                for ((pid, children) in touchTargets.toMap()) {
                    val idx = event.findPointerIndex(pid)
                    if (idx >= 0) {
                        for (child in children) {
                            dispatchToChild(child, event, MotionEvent.ACTION_MOVE, idx)
                        }
                    }
                }
                // Swipe-triggered buttons: presence-based, re-evaluate against all pointers
                updateSwipeButtons(event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val children = touchTargets.remove(pid)
                if (children != null) {
                    for (child in children) {
                        dispatchToChild(child, event, MotionEvent.ACTION_UP, idx)
                        if (child is JoystickView) child.forceFollowFinger = false
                    }
                }
                // Re-evaluate swipe buttons excluding the lifted pointer
                updateSwipeButtons(event, setOf(pid))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val terminalAction = if (event.actionMasked == MotionEvent.ACTION_UP) {
                    MotionEvent.ACTION_UP
                } else {
                    MotionEvent.ACTION_CANCEL
                }
                for ((_, children) in touchTargets) {
                    for (child in children) {
                        val ev = MotionEvent.obtain(
                            event.downTime, event.eventTime,
                            terminalAction, 0f, 0f, 0
                        )
                        child.dispatchTouchEvent(ev)
                        ev.recycle()
                    }
                }
                touchTargets.clear()
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

    /** Re-evaluate all swipe-triggered buttons: if any active pointer is inside → press, otherwise → release */
    private fun updateSwipeButtons(event: MotionEvent, excludePointerIds: Set<Int> = emptySet()) {
        // Determine which buttons should be active based on all non-excluded pointers
        val newlyActive = HashMap<String, View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val cid = getButtonId(child) ?: continue
            if (cid !in swipeTriggerIds) continue
            val pos = currentButtons.find { it.id == cid } ?: continue
            for (pi in 0 until event.pointerCount) {
                val pid = event.getPointerId(pi)
                if (pid in excludePointerIds) continue
                val x = event.getX(pi)
                val y = event.getY(pi)
                if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                    if (!pos.overlapTrigger) {
                        val allAt = findAllChildrenAt(x, y)
                        if (allAt.any { getButtonId(it) != cid }) continue
                    }
                    newlyActive[cid] = child
                    break
                }
            }
        }

        val prevActive = activeSwipeButtons.keys.toSet()
        val currActive = newlyActive.keys

        // Release buttons no longer under any pointer
        for (cid in prevActive - currActive) {
            val child = activeSwipeButtons[cid] ?: continue
            dispatchToChild(child, event, MotionEvent.ACTION_UP, 0)
            activeSwipeButtons.remove(cid)
        }

        // Press newly covered buttons
        for (cid in currActive - prevActive) {
            val child = newlyActive[cid] ?: continue
            var pointerIdx = 0
            for (pi in 0 until event.pointerCount) {
                val pid = event.getPointerId(pi)
                if (pid in excludePointerIds) continue
                val x = event.getX(pi)
                val y = event.getY(pi)
                if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                    pointerIdx = pi
                    break
                }
            }
            dispatchToChild(child, event, MotionEvent.ACTION_DOWN, pointerIdx)
            activeSwipeButtons[cid] = child
        }
    }

    // ── Normal multi-touch dispatch (no swipe trigger) ───────

    private val touchTargets = HashMap<Int, MutableList<View>>()

    /** Set of pointer IDs whose initial touch-down was on the touchpad. */
    private val touchpadPointerIds = mutableSetOf<Int>()

    /** When set, the touchpad child is active and receiving touchpad-pointer events. */
    private var touchpadTarget: View? = null

    private fun pointerDown(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val x = event.getX(idx)
        val y = event.getY(idx)
        val allChildren = findAllChildrenAt(x, y)
        val children = filterOverlapChildren(allChildren)
        val nonJoystickChildren = children.filter { it !is JoystickView }

        // Settings button is always the topmost control: a tap on it only opens settings.
        val settingsChild = children.firstOrNull { getButtonId(it) == SETTINGS_BUTTON_ID }
        if (settingsChild != null) {
            touchTargets[pid] = mutableListOf(settingsChild)
            dispatchToChild(settingsChild, event, MotionEvent.ACTION_DOWN, idx)
            return
        }

        if (nonJoystickChildren.isEmpty()) {
            if (tryFollowAreaTrigger(x, y, pid, event, idx)) return
            if (children.isEmpty()) return
        } else {
            if (tryFollowAreaOverlapTrigger(x, y, pid, event, idx)) return
        }

        val tp = children.firstOrNull { getButtonId(it) == "touchpad" }
        if (tp != null) {
            touchpadTarget = tp
            touchpadPointerIds.add(pid)
            dispatchFilteredToTouchpad(tp, event)
            return
        }
        touchTargets[pid] = children.toMutableList()
        for (child in children) {
            dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
        }
    }

    private fun pointerUp(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val children = touchTargets.remove(pid)
        if (children != null) {
            for (child in children) {
                dispatchToChild(child, event, MotionEvent.ACTION_UP, idx)
                if (child is JoystickView) child.forceFollowFinger = false
            }
        }
        touchpadPointerIds.remove(pid)
        if (touchpadPointerIds.isEmpty()) {
            touchpadTarget = null
        }
    }

    /** Deliver only touchpad-originated pointers to the touchpad child.
     *  When [indices] is given, only those pointer indices are included;
     *  otherwise all pointers whose ID is in [touchpadPointerIds] are included. */
    private fun dispatchFilteredToTouchpad(child: View, event: MotionEvent, indices: List<Int>? = null) {
        val include = indices ?: (0 until event.pointerCount).filter { event.getPointerId(it) in touchpadPointerIds }
        if (include.isEmpty()) return

        if (include.size == event.pointerCount) {
            val ev = MotionEvent.obtain(event)
            ev.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
            child.dispatchTouchEvent(ev)
            ev.recycle()
            return
        }

        val props = Array(include.size) { i ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(include[i], it) }
        }
        val coords = Array(include.size) { i ->
            MotionEvent.PointerCoords().also { event.getPointerCoords(include[i], it) }
        }

        val rawAction = event.actionMasked
        val newAction = if (include.size == 1 && rawAction == MotionEvent.ACTION_POINTER_DOWN) {
            MotionEvent.ACTION_DOWN
        } else if (include.size == 1 && rawAction == MotionEvent.ACTION_POINTER_UP) {
            MotionEvent.ACTION_UP
        } else {
            rawAction
        }

        val ev = MotionEvent.obtain(
            event.downTime, event.eventTime,
            newAction, include.size,
            props, coords,
            event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags,
            event.source, event.flags
        )
        ev.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
        child.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun dispatchToChild(child: View, event: MotionEvent, action: Int, pointerIdx: Int) {
        val ev = MotionEvent.obtain(
            event.downTime, event.eventTime,
            action,
            event.getX(pointerIdx) - child.left,
            event.getY(pointerIdx) - child.top,
            event.metaState
        )
        child.dispatchTouchEvent(ev)
        ev.recycle()
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

    var onCapturedTouchpadEvent: ((normalizedX: Float, normalizedY: Float,
        touches: List<FloatArray>, touchpadTouch: Boolean, touchpadClick: Boolean) -> Unit)? = null

    fun loadPreset(preset: LayoutPreset) {
        currentButtons = preset.buttons.map {
            if (it.id == "centerArea") it.copy(id = "touchpad") else it
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
            idleTransparency = 0, activeTransparency = 0)
        val maxCol = (GRID_COLS - p.width).coerceAtLeast(0)
        val maxRow = if (cellH > 0f) ((height / cellH).toInt() - p.height).coerceAtLeast(0) else Int.MAX_VALUE
        p = p.copy(
            x = p.x.coerceIn(0, maxCol),
            y = p.y.coerceIn(0, maxRow)
        )
        return p
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
        followAreaPaint.color = color
        followAreaPaint.strokeWidth = strokeWidth.toFloat()
        invalidate()
    }

    fun setTouchpadAreaAppearance(color: Int, strokeWidth: Int) {
        touchpadAreaPaint.color = color
        touchpadAreaPaint.strokeWidth = strokeWidth.toFloat()
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
        setSelectedButton(buttonId)
        listener?.onButtonSelected(buttonId)
        invalidate()
    }

    fun exitFollowAreaAdjust() {
        isAdjustingFollowArea = false
        adjustingFollowAreaId = null
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
                val newVal = child.showDeadZoneIndicator && getButtonId(child) == selectedButtonId
                if (child.isSelectedInEditor != newVal) {
                    child.isSelectedInEditor = newVal
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

    /** Activate follow-area trigger for a joystick.
     *  Returns true if a matching joystick was found and dispatched. */
    private fun tryFollowAreaTrigger(x: Float, y: Float, pid: Int, event: MotionEvent, idx: Int): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val cid = getButtonId(child) ?: continue
            val pos = currentButtons.find { it.id == cid } ?: continue
            if (child is JoystickView && pos.followAreaEnabled) {
                val areaLeft = pos.followAreaX * cellW
                val areaTop = pos.followAreaY * cellH
                val areaRight = (pos.followAreaX + pos.followAreaW) * cellW
                val areaBottom = (pos.followAreaY + pos.followAreaH) * cellH
                if (x >= areaLeft && x <= areaRight && y >= areaTop && y <= areaBottom) {
                    child.forceFollowFinger = true
                    touchTargets[pid] = mutableListOf(child)
                    dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
                    return true
                }
            }
        }
        return false
    }

    /** Activate follow-area trigger even when overlapping non-joystick controls,
     *  only for joysticks with [followAreaOverlapTrigger] = true. */
    private fun tryFollowAreaOverlapTrigger(x: Float, y: Float, pid: Int, event: MotionEvent, idx: Int): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val cid = getButtonId(child) ?: continue
            val pos = currentButtons.find { it.id == cid } ?: continue
            if (child is JoystickView && pos.followAreaEnabled && pos.followAreaOverlapTrigger) {
                if (isInFollowArea(x, y, pos)) {
                    child.forceFollowFinger = true
                    touchTargets[pid] = mutableListOf(child)
                    dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
                    return true
                }
            }
        }
        return false
    }

    private fun resetForceFollowFinger() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is JoystickView) {
                child.forceFollowFinger = false
            }
        }
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

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val id = getButtonId(child) ?: continue
            val pos = currentButtons.find { it.id == id }
            if (pos == null) {
                child.visibility = View.GONE
                continue
            }

            if (!pos.visible) {
                if (child.visibility != View.GONE) child.visibility = View.GONE
                continue
            }
            if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE

            val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
            val childW = ((if (isSwapped) pos.height else pos.width) * cellW).toInt()
            val childH = ((if (isSwapped) pos.width else pos.height) * cellH).toInt()
            val left = (pos.x * cellW).toInt()
            val top = (pos.y * cellH).toInt()

            child.measure(
                MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY),
            )
            child.layout(left, top, left + childW, top + childH)
            // Content (text / foreground icon / image) always keeps a min(w,h) x 10% padding,
            // whether or not adaptive icon size is enabled. Buttons whose icon IS their background
            // (touchpad, LS/RS before separation) are excluded here.
            if (isAdaptiveContentButton(id, child)) {
                val pad = (minOf(childW, childH) * 0.1f).toInt()
                if (child.paddingLeft != pad || child.paddingTop != pad) {
                    child.setPadding(pad, pad, pad, pad)
                }
                // Auto-fit text cap tracks the measured size (the first appearance pass may
                // have run before layout). Null cap = unlimited.
                val capPx = AppearanceApplier.contentCapPx(child, appearanceSettings)
                if (child is Button && !child.text.isNullOrEmpty()) {
                    AppearanceApplier.applyContentTextCap(
                        child, capPx ?: AppearanceApplier.UNLIMITED_TEXT_CAP_PX
                    )
                }
            }
            if (isEditMode && previewTransparency && id == previewButtonId) {
                val transVal = if (previewIdleTransparency) pos.idleTransparency else pos.activeTransparency
                child.alpha = 1f - (transVal.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
            } else if (isEditMode) {
                child.alpha = 1f
            } else {
                child.alpha = 1f - (pos.idleTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
            }
            if (child is JoystickView) {
                child.axisRotation = pos.rotation
                child.doubleClickEnable = pos.doubleClickEnable
                child.sensitivityCurve = pos.sensitivityCurve
                child.deadZone = pos.deadZone
                child.forceFollowFinger = false
                child.showDeadZoneIndicator = isEditMode
                child.isSelectedInEditor = id == selectedButtonId
                child.idleTransparency = pos.idleTransparency.coerceIn(0, 255)
                child.activeTransparency = pos.activeTransparency.coerceIn(0, 255)
            } else if (child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    child.getChildAt(j).rotation = pos.rotation.toFloat()
                }
            } else if (child is RotatableButton) {
                child.textRotation = pos.rotation
            } else if (pos.lockAspect) {
                child.rotation = pos.rotation.toFloat()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw area rectangles for joysticks and touchpads (always visible when enabled, even outside edit mode)
        for (pos in currentButtons) {
            val isJoyArea = pos.id.substringBefore("_") in JOYSTICK_IDS
            val isTpArea = isTouchpadId(pos.id)
            if ((isJoyArea || isTpArea) && pos.followAreaEnabled) {
                val areaPaint = if (isTpArea) touchpadAreaPaint else followAreaPaint
                val fLeft = (pos.followAreaX * cellW).toInt().toFloat()
                val fTop = (pos.followAreaY * cellH).toInt().toFloat()
                val fRight = ((pos.followAreaX + pos.followAreaW) * cellW).toInt().toFloat()
                val fBottom = ((pos.followAreaY + pos.followAreaH) * cellH).toInt().toFloat()

                if (areaPaint.strokeWidth > 0f) {
                    areaPaint.alpha = (255 - pos.followAreaTransparency.coerceIn(0, 255)).coerceIn(0, 255)
                    canvas.drawRect(fLeft, fTop, fRight, fBottom, areaPaint)
                    areaPaint.alpha = 255
                }

                if (pos.id == adjustingFollowAreaId && isAdjustingFollowArea) {
                    val handleDp2 = HANDLE_SIZE_DP * density
                    canvas.drawRect(fRight - handleDp2, fBottom - handleDp2, fRight, fBottom, handlePaint)
                }
            }
        }

        if (!isEditMode) return

        gridPaint.alpha = (gridAlpha * 255).toInt().coerceIn(0, 255)
        val rows = (height / cellH).toInt() + 1
        for (col in 0..GRID_COLS) {
            val x = col * cellW
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
        }
        for (row in 0..rows) {
            val y = row * cellH
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        val selId = selectedButtonId ?: return
        val pos = currentButtons.find { it.id == selId } ?: return
        val vb = visualBounds(pos)
        val vl = (vb[0] * cellW).toInt().toFloat()
        val vt = (vb[1] * cellH).toInt().toFloat()
        val vbw = (vb[2] * cellW).toInt().toFloat()
        val vbh = (vb[3] * cellH).toInt().toFloat()

        if (!isAdjustingFollowArea) {
            selectionPaint.setStrokeWidth(3f * density)
            canvas.drawRect(vl, vt, vl + vbw, vt + vbh, selectionPaint)
        }

        val handleDp = HANDLE_SIZE_DP * density
        val handleX = vl + vbw - handleDp
        val handleY = vt + vbh - handleDp
        if (!isAdjustingFollowArea) {
            canvas.drawRect(handleX, handleY, handleX + handleDp, handleY + handleDp, handlePaint)
        }

        markPaint.textSize = 14f * density
        val markerDist = 4f * density
        val (markX, markY, markAngle) = when (pos.rotation % 360) {
            90 -> Triple(vl + vbw + markerDist, vt + vbh / 2, 90f)
            180 -> Triple(vl + vbw / 2, vt + vbh + markerDist, 180f)
            270 -> Triple(vl - markerDist, vt + vbh / 2, 270f)
            else -> Triple(vl + vbw / 2, vt - markerDist, 0f)
        }
        if (!isAdjustingFollowArea && pos.id != SETTINGS_BUTTON_ID) {
            canvas.save()
            canvas.rotate(markAngle, markX, markY)
            canvas.drawText("上", markX, markY, markPaint)
            canvas.restore()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return isEditMode
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isAdjustingFollowArea && adjustingFollowAreaId != null) {
                    val selPos = currentButtons.find { it.id == adjustingFollowAreaId } ?: return true
                    // Check if tapping on follow area resize handle
                    if (isOnFollowAreaHandle(event.x, event.y, selPos)) {
                        resizingFollowArea = true
                        followAreaStartW = selPos.followAreaW
                        followAreaStartH = selPos.followAreaH
                        resizeStartGridX = (event.x / cellW).toInt()
                        resizeStartGridY = (event.y / cellH).toInt()
                        animateGridTo(1f)
                        return true
                    }
                    // Check if tapping within follow area (drag)
                    if (isInFollowArea(event.x, event.y, selPos)) {
                        draggingFollowArea = true
                        followAreaStartX = selPos.followAreaX
                        followAreaStartY = selPos.followAreaY
                        followAreaDragStartX = (event.x / cellW).toInt()
                        followAreaDragStartY = (event.y / cellH).toInt()
                        animateGridTo(1f)
                        return true
                    }
                    // Tapping outside follow area does nothing during adjustment
                    return true
                }

                val id = selectedButtonId
                if (id != null && isOnHandle(event.x, event.y, id)) {
                    val child = findChildAt(event.x, event.y) ?: return true
                    resizingChild = child
                    val pos = currentButtons.find { it.id == id }!!
                    resizeStartW = pos.width
                    resizeStartH = pos.height
                    resizeStartGridX = (event.x / cellW).toInt()
                    resizeStartGridY = (event.y / cellH).toInt()
                    animateGridTo(1f)
                    return true
                }

                draggingChild = findChildAt(event.x, event.y)
                if (draggingChild != null) {
                    dragOffsetX = event.x - draggingChild!!.left
                    dragOffsetY = event.y - draggingChild!!.top
                    val cid = getButtonId(draggingChild!!)
                    if (cid != null) {
                        setSelectedButton(cid)
                    }
                    animateGridTo(1f)
                } else {
                    selectChildAt(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizingFollowArea && adjustingFollowAreaId != null) {
                    val gridX = (event.x / cellW).toInt().coerceAtLeast(0)
                    val gridY = (event.y / cellH).toInt().coerceAtLeast(0)
                    val idx = currentButtons.indexOfFirst { it.id == adjustingFollowAreaId }
                    if (idx >= 0) {
                        val old = currentButtons[idx]
                        val deltaX = gridX - resizeStartGridX
                        val deltaY = gridY - resizeStartGridY
                        var newW = (followAreaStartW + deltaX).coerceAtLeast(1)
                        var newH = (followAreaStartH + deltaY).coerceAtLeast(1)
                        var updated: ButtonPosition
                        if (isTouchpadId(old.id) && old.followAreaEnabled) {
                            // Shrinking the area shrinks the touchpad to keep containment.
                            updated = old.copy(followAreaW = newW, followAreaH = newH)
                            updated = shrinkTouchpadToArea(updated)
                        } else {
                            updated = old.copy(followAreaW = newW, followAreaH = newH)
                        }
                        if (updated != old) {
                            currentButtons = currentButtons.toMutableList().also {
                                it[idx] = updated
                            }
                            hasChanges = true
                            requestLayout()
                        }
                    }
                    return true
                }
                if (draggingFollowArea && adjustingFollowAreaId != null) {
                    val gridX = (event.x / cellW).toInt().coerceIn(0, GRID_COLS - 1)
                    val gridY = (event.y / cellH).toInt().coerceAtLeast(0)
                    val idx = currentButtons.indexOfFirst { it.id == adjustingFollowAreaId }
                    if (idx >= 0) {
                        val old = currentButtons[idx]
                        val deltaX = gridX - followAreaDragStartX
                        val deltaY = gridY - followAreaDragStartY
                        var newX = followAreaStartX + deltaX
                        var newY = followAreaStartY + deltaY
                        var updated: ButtonPosition
                        if (isTouchpadId(old.id) && old.followAreaEnabled) {
                            val rows = if (cellH > 0f) (height / cellH).toInt() else GRID_COLS
                            val maxAX = (GRID_COLS - old.followAreaW).coerceAtLeast(0)
                            val maxAY = (rows - old.followAreaH).coerceAtLeast(0)
                            newX = newX.coerceIn(0, maxAX)
                            newY = newY.coerceIn(0, maxAY)
                            // The area's top-left edge cannot pass the touchpad's top-left edge.
                            newX = minOf(newX, old.x)
                            newY = minOf(newY, old.y)
                            updated = old.copy(followAreaX = newX, followAreaY = newY)
                            updated = shrinkTouchpadToArea(updated)
                        } else {
                            newX = newX.coerceIn(0, GRID_COLS - 1)
                            newY = newY.coerceAtLeast(0)
                            updated = old.copy(followAreaX = newX, followAreaY = newY)
                        }
                        if (updated != old) {
                            currentButtons = currentButtons.toMutableList().also {
                                it[idx] = updated
                            }
                            hasChanges = true
                            requestLayout()
                        }
                    }
                    return true
                }

                if (resizingChild != null) {
                    val gridX = (event.x / cellW).toInt().coerceAtLeast(0)
                    val gridY = (event.y / cellH).toInt().coerceAtLeast(0)
                    val rid = getButtonId(resizingChild!!) ?: return true
                    val idx = currentButtons.indexOfFirst { it.id == rid }
                    if (idx >= 0) {
                        val old = currentButtons[idx]
                        val isSwapped = !old.lockAspect && (old.rotation == 90 || old.rotation == 270)
                        val deltaX = gridX - resizeStartGridX
                        val deltaY = gridY - resizeStartGridY
                        var newW: Int; var newH: Int
                        if (isSwapped) {
                            newW = (resizeStartW + deltaY).coerceAtLeast(1)
                            newH = (resizeStartH + deltaX).coerceAtLeast(1)
                        } else {
                            newW = (resizeStartW + deltaX).coerceAtLeast(1)
                            newH = (resizeStartH + deltaY).coerceAtLeast(1)
                        }
                        if (old.lockAspect) {
                            val side = maxOf(newW, newH)
                            newW = side
                            newH = side
                        }
                        if (rid == SETTINGS_BUTTON_ID) {
                            // Keep the settings button fully on screen while resizing:
                            // its size can never exceed the grid from its current anchor.
                            val rows = if (cellH > 0f) (height / cellH).toInt() else GRID_COLS
                            newW = newW.coerceIn(1, (GRID_COLS - old.x).coerceAtLeast(1))
                            newH = newH.coerceIn(1, (rows - old.y).coerceAtLeast(1))
                            if (old.lockAspect) {
                                val side = minOf(newW, newH)
                                newW = side
                                newH = side
                            }
                        }
                        if (newW != old.width || newH != old.height) {
                            var updated = old.copy(width = newW, height = newH)
                            if (rid == SETTINGS_BUTTON_ID) updated = sanitizeSettingsButton(updated)
                            if (isTouchpadId(rid) && old.followAreaEnabled) {
                                updated = normalizeTouchpadArea(updated)
                            }
                            currentButtons = currentButtons.toMutableList().also {
                                it[idx] = updated
                            }
                            hasChanges = true
                            requestLayout()
                        }
                    }
                    return true
                }
                if (draggingChild != null) {
                    val newLeft = (event.x - dragOffsetX).coerceAtLeast(0f)
                    val newTop = (event.y - dragOffsetY).coerceAtLeast(0f)

                    val id = getButtonId(draggingChild!!)
                    if (id != null) {
                        val idx = currentButtons.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val old = currentButtons[idx]
                            var gridX: Int
                            var gridY: Int
                            if (id == SETTINGS_BUTTON_ID) {
                                val rows = if (cellH > 0f) (height / cellH).toInt() else GRID_COLS
                                gridX = (newLeft / cellW).toInt().coerceIn(0, (GRID_COLS - old.width).coerceAtLeast(0))
                                gridY = (newTop / cellH).toInt().coerceIn(0, (rows - old.height).coerceAtLeast(0))
                            } else {
                                gridX = (newLeft / cellW).toInt().coerceIn(0, GRID_COLS - 1)
                                gridY = (newTop / cellH).toInt().coerceAtLeast(0)
                            }
                            var updated: ButtonPosition
                            if (isTouchpadId(id) && old.followAreaEnabled) {
                                // The extended range rectangle moves in sync with the touchpad.
                                val deltaX = gridX - old.x
                                val deltaY = gridY - old.y
                                updated = old.copy(
                                    x = gridX, y = gridY,
                                    followAreaX = old.followAreaX + deltaX,
                                    followAreaY = old.followAreaY + deltaY
                                )
                            } else {
                                updated = old.copy(x = gridX, y = gridY)
                            }
                            if (old.x != updated.x || old.y != updated.y ||
                                old.followAreaX != updated.followAreaX || old.followAreaY != updated.followAreaY) {
                                currentButtons = currentButtons.toMutableList().also {
                                    it[idx] = updated
                                }
                                hasChanges = true
                                listener?.onButtonSelected(id)
                                requestLayout()
                            }
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizingFollowArea || draggingFollowArea) {
                    val id = adjustingFollowAreaId
                    if (id != null) {
                        listener?.onButtonSelected(id)
                    }
                    resizingFollowArea = false
                    draggingFollowArea = false
                    animateGridTo(0f)
                    return true
                }
                if (resizingChild != null) {
                    val id = getButtonId(resizingChild!!)
                    if (id != null) {
                        listener?.onButtonSelected(id)
                    }
                    resizingChild = null
                }
                if (draggingChild != null) {
                    val id = getButtonId(draggingChild!!)
                    if (id != null) {
                        val pos = currentButtons.find { it.id == id }
                        if (pos != null) {
                            listener?.onButtonSelected(id)
                        }
                    }
                    draggingChild = null
                }
                animateGridTo(0f)
                return true
            }
        }
        return false
    }

    private fun isOnFollowAreaHandle(x: Float, y: Float, pos: ButtonPosition): Boolean {
        val fLeft = pos.followAreaX * cellW
        val fTop = pos.followAreaY * cellH
        val fRight = (pos.followAreaX + pos.followAreaW) * cellW
        val fBottom = (pos.followAreaY + pos.followAreaH) * cellH
        val handleHit = HANDLE_HIT_DP * density
        val hx = fRight - handleHit
        val hy = fBottom - handleHit
        return x >= hx && x <= fRight && y >= hy && y <= fBottom
    }

    private fun isInFollowArea(x: Float, y: Float, pos: ButtonPosition): Boolean {
        val fLeft = pos.followAreaX * cellW
        val fTop = pos.followAreaY * cellH
        val fRight = (pos.followAreaX + pos.followAreaW) * cellW
        val fBottom = (pos.followAreaY + pos.followAreaH) * cellH
        return x >= fLeft && x <= fRight && y >= fTop && y <= fBottom
    }

    private fun isTouchpadId(id: String): Boolean = id.substringBefore("_") == "touchpad"

    /** On-screen size of a control in grid units (accounts for 90/270 rotation swap). */
    private fun onScreenGridSize(pos: ButtonPosition): Pair<Int, Int> {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        return if (isSwapped) (pos.height to pos.width) else (pos.width to pos.height)
    }

    /** True when the touchpad control is fully inside the extended range rectangle. */
    private fun touchpadContainedByArea(pos: ButtonPosition): Boolean {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return true
        val (sw, sh) = onScreenGridSize(pos)
        return pos.x >= pos.followAreaX && pos.y >= pos.followAreaY &&
            pos.x + sw <= pos.followAreaX + pos.followAreaW &&
            pos.y + sh <= pos.followAreaY + pos.followAreaH
    }

    /** Expands the extended range rectangle if it no longer contains the touchpad. */
    private fun normalizeTouchpadArea(pos: ButtonPosition): ButtonPosition {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return pos
        if (touchpadContainedByArea(pos)) return pos
        val (sw, sh) = onScreenGridSize(pos)
        val ax = minOf(pos.followAreaX, pos.x)
        val ay = minOf(pos.followAreaY, pos.y)
        val aw = maxOf(pos.followAreaW, pos.x + sw - ax)
        val ah = maxOf(pos.followAreaH, pos.y + sh - ay)
        return pos.copy(followAreaX = ax, followAreaY = ay, followAreaW = aw, followAreaH = ah)
    }

    /** Shrinks the touchpad so it fits inside the extended range rectangle. */
    private fun shrinkTouchpadToArea(pos: ButtonPosition): ButtonPosition {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return pos
        val (sw, sh) = onScreenGridSize(pos)
        val nw = minOf(sw, (pos.followAreaX + pos.followAreaW - pos.x).coerceAtLeast(1))
        val nh = minOf(sh, (pos.followAreaY + pos.followAreaH - pos.y).coerceAtLeast(1))
        if (nw == sw && nh == sh) return pos
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val width = if (isSwapped) nh else nw
        val height = if (isSwapped) nw else nh
        return pos.copy(width = width, height = height)
    }

    private fun isOnHandle(x: Float, y: Float, buttonId: String): Boolean {
        val pos = currentButtons.find { it.id == buttonId } ?: return false
        val vb = visualBounds(pos)
        val vl = vb[0] * cellW
        val vt = vb[1] * cellH
        val vbw = vb[2] * cellW
        val vbh = vb[3] * cellH
        val handleHit = HANDLE_HIT_DP * density
        val hx = vl + vbw - handleHit
        val hy = vt + vbh - handleHit
        return x >= hx && x <= vl + vbw && y >= hy && y <= vt + vbh
    }

    private fun findChildAt(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                return child
            }
        }
        return null
    }

    /** Returns all visible children at (x,y), topmost first */
    private fun findAllChildrenAt(x: Float, y: Float): List<View> {
        val result = mutableListOf<View>()
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                result.add(child)
            }
        }
        return result
    }

    /** When multiple children overlap, exclude those with [overlapTrigger] = false. */
    private fun filterOverlapChildren(children: List<View>): List<View> {
        if (children.size <= 1) return children
        return children.filter { child ->
            val id = getButtonId(child)
            id == null || currentButtons.find { it.id == id }?.overlapTrigger != false
        }
    }

    /** Returns [left, top, width, height] in grid coordinates for the visual extent. */
    private fun visualBounds(pos: ButtonPosition): FloatArray {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val lw = if (isSwapped) pos.height else pos.width
        val lh = if (isSwapped) pos.width else pos.height
        return floatArrayOf(pos.x.toFloat(), pos.y.toFloat(), lw.toFloat(), lh.toFloat())
    }

    private val density: Float
        get() = context.resources.displayMetrics.density
}
