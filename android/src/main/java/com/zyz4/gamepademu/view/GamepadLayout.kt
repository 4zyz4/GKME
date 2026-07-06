package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.model.LayoutPreset

class GamepadLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    init {
        setWillNotDraw(false)
    }

    companion object {
        const val GRID_COLS = 120
        private const val HANDLE_SIZE_DP = 8f
        private const val HANDLE_HIT_DP = 16f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x55000001
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

    private var cellW = 0f
    private var cellH = 0f

    var currentButtons: List<ButtonPosition> = emptyList()
        private set
    private var isEditMode = false
    var selectedButtonId: String? = null
        private set
    private var editSnapshot: LayoutPreset? = null
    var hasChanges = false
        private set

    private var draggingChild: View? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var resizingChild: View? = null
    private var resizeStartW = 0
    private var resizeStartH = 0
    private var resizeStartGridX = 0
    private var resizeStartGridY = 0

    var listener: GamepadLayoutListener? = null

    /** Swipe-trigger state: tracks which buttons are currently pressed (buttonId -> child View) */
    private val activeSwipeButtons = HashMap<String, View>()

    /** Set of button IDs that have swipeTrigger enabled */
    private var swipeTriggerIds: Set<String> = emptySet()

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
                    pointerUp(event, event.actionIndex)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    pointerUp(event, 0)
                    touchTargets.clear()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
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
                val children = findAllChildrenAt(x, y)
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
                val children = findAllChildrenAt(x, y)
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
                // Per-pointer presence check for non-swipe buttons
                for ((pid, children) in touchTargets.toMap()) {
                    val idx = event.findPointerIndex(pid)
                    if (idx < 0) continue
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    val stillInside = mutableListOf<View>()
                    val noLongerInside = mutableListOf<View>()
                    for (child in children) {
                        if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                            stillInside.add(child)
                        } else {
                            noLongerInside.add(child)
                        }
                    }
                    for (child in noLongerInside) {
                        dispatchToChild(child, event, MotionEvent.ACTION_UP, idx)
                    }
                    for (child in stillInside) {
                        dispatchToChild(child, event, MotionEvent.ACTION_MOVE, idx)
                    }
                    if (stillInside.isNotEmpty()) {
                        touchTargets[pid] = stillInside.toMutableList()
                    } else {
                        touchTargets.remove(pid)
                    }
                }
                // Re-evaluate all swipe-triggered buttons against all pointers
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
                    }
                }
                // Re-evaluate swipe buttons excluding the lifted pointer
                updateSwipeButtons(event, setOf(pid))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
                touchTargets.clear()
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
            for (pi in 0 until event.pointerCount) {
                val pid = event.getPointerId(pi)
                if (pid in excludePointerIds) continue
                val x = event.getX(pi)
                val y = event.getY(pi)
                if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
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

    private fun pointerDown(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val x = event.getX(idx)
        val y = event.getY(idx)
        val children = findAllChildrenAt(x, y)
        if (children.isNotEmpty()) {
            touchTargets[pid] = children.toMutableList()
            for (child in children) {
                dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
            }
        }
    }

    private fun pointerUp(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val children = touchTargets.remove(pid)
        if (children != null) {
            for (child in children) {
                dispatchToChild(child, event, MotionEvent.ACTION_UP, idx)
            }
        }
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
    }

    fun loadPreset(preset: LayoutPreset) {
        currentButtons = preset.buttons.map {
            if (it.id == "centerArea") it.copy(id = "touchpad") else it
        }.toList()
        hasChanges = false
        refreshSwipeTriggers()
        requestLayout()
    }

    fun getPreset(): LayoutPreset {
        return LayoutPreset(version = 1, buttons = currentButtons.toList())
    }

    fun enterEditMode() {
        editSnapshot = getPreset()
        isEditMode = true
        hasChanges = false
        listener?.onEditModeChanged(true)
        invalidate()
        requestLayout()
    }

    fun exitEditMode() {
        isEditMode = false
        selectedButtonId = null
        draggingChild = null
        resizingChild = null
        editSnapshot = null
        listener?.onEditModeChanged(false)
        invalidate()
        requestLayout()
    }

    fun isEditModeActive(): Boolean = isEditMode

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
            currentButtons = currentButtons.toMutableList().also {
                it[idx] = updated
            }
            hasChanges = true
            refreshSwipeTriggers()
            requestLayout()
        }
    }

    fun addButtonPosition(pos: ButtonPosition) {
        currentButtons = currentButtons.toMutableList().also { it.add(pos) }
        hasChanges = true
        refreshSwipeTriggers()
        requestLayout()
    }

    fun removeButtonPosition(id: String) {
        currentButtons = currentButtons.toMutableList().also { it.removeAll { b -> b.id == id } }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (getButtonId(child) == id) {
                removeView(child)
                break
            }
        }
        if (selectedButtonId == id) selectedButtonId = null
        hasChanges = true
        refreshSwipeTriggers()
        requestLayout()
    }

    fun setSelectedButton(id: String?) {
        if (selectedButtonId != id) {
            selectedButtonId = id
            listener?.onButtonSelected(id)
            invalidate()
        }
    }

    /** Mark selection by tapping a child in edit mode */
    private fun selectChildAt(x: Float, y: Float) {
        val child = findChildAt(x, y)
        if (child != null) {
            val cid = getButtonId(child)
            if (cid != null) {
                selectedButtonId = cid
                listener?.onButtonSelected(cid)
                invalidate()
            }
        } else {
            if (selectedButtonId != null) {
                selectedButtonId = null
                listener?.onButtonSelected(null)
                invalidate()
            }
        }
    }

    private fun refreshSwipeTriggers() {
        swipeTriggerIds = currentButtons.filter { it.swipeTrigger }.map { it.id }.toSet()
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        cellW = w.toFloat() / GRID_COLS
        cellH = cellW

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val id = getButtonId(child) ?: continue
            val pos = currentButtons.find { it.id == id } ?: continue

            if (!pos.visible) {
                if (child.visibility != View.GONE) child.visibility = View.GONE
                continue
            }
            if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE

            val childW = (pos.width * cellW).toInt()
            val childH = (pos.height * cellH).toInt()
            val left = (pos.x * cellW).toInt()
            val top = (pos.y * cellH).toInt()

            child.measure(
                MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY),
            )
            child.layout(left, top, left + childW, top + childH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEditMode) return

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
        val l = (pos.x * cellW).toInt().toFloat()
        val t = (pos.y * cellH).toInt().toFloat()
        val bw = (pos.width * cellW).toInt().toFloat()
        val bh = (pos.height * cellH).toInt().toFloat()

        selectionPaint.setStrokeWidth(3f * density)
        canvas.drawRect(l, t, l + bw, t + bh, selectionPaint)

        val handleDp = HANDLE_SIZE_DP * density
        val handleX = l + bw - handleDp
        val handleY = t + bh - handleDp
        canvas.drawRect(handleX, handleY, handleX + handleDp, handleY + handleDp, handlePaint)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return isEditMode
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = selectedButtonId
                if (id != null && isOnHandle(event.x, event.y, id)) {
                    val child = findChildAt(event.x, event.y) ?: return true
                    resizingChild = child
                    val pos = currentButtons.find { it.id == id }!!
                    resizeStartW = pos.width
                    resizeStartH = pos.height
                    resizeStartGridX = (event.x / cellW).toInt()
                    resizeStartGridY = (event.y / cellH).toInt()
                    return true
                }

                draggingChild = findChildAt(event.x, event.y)
                if (draggingChild != null) {
                    dragOffsetX = event.x - draggingChild!!.left
                    dragOffsetY = event.y - draggingChild!!.top
                    val cid = getButtonId(draggingChild!!)
                    if (cid != null) {
                        selectedButtonId = cid
                        listener?.onButtonSelected(cid)
                        invalidate()
                    }
                } else {
                    selectChildAt(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizingChild != null) {
                    val gridX = (event.x / cellW).toInt().coerceAtLeast(0)
                    val gridY = (event.y / cellH).toInt().coerceAtLeast(0)
                    val rid = getButtonId(resizingChild!!) ?: return true
                    val idx = currentButtons.indexOfFirst { it.id == rid }
                    if (idx >= 0) {
                        val old = currentButtons[idx]
                        var newW = (gridX - old.x + 1).coerceAtLeast(1)
                        var newH = (gridY - old.y + 1).coerceAtLeast(1)
                        if (old.lockAspect) {
                            val side = maxOf(newW, newH)
                            newW = side
                            newH = side
                        }
                        if (newW != old.width || newH != old.height) {
                            currentButtons = currentButtons.toMutableList().also {
                                it[idx] = old.copy(width = newW, height = newH)
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
                    val gridX = (newLeft / cellW).toInt().coerceIn(0, GRID_COLS - 1)
                    val gridY = (newTop / cellH).toInt().coerceAtLeast(0)

                    val id = getButtonId(draggingChild!!)
                    if (id != null) {
                        val idx = currentButtons.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val old = currentButtons[idx]
                            if (old.x != gridX || old.y != gridY) {
                                currentButtons = currentButtons.toMutableList().also {
                                    it[idx] = old.copy(x = gridX, y = gridY)
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
                return true
            }
        }
        return false
    }

    private fun isOnHandle(x: Float, y: Float, buttonId: String): Boolean {
        val pos = currentButtons.find { it.id == buttonId } ?: return false
        val l = (pos.x * cellW).toInt().toFloat()
        val t = (pos.y * cellH).toInt().toFloat()
        val bw = (pos.width * cellW).toInt().toFloat()
        val bh = (pos.height * cellH).toInt().toFloat()
        val handleHit = HANDLE_HIT_DP * density
        val hx = l + bw - handleHit
        val hy = t + bh - handleHit
        return x >= hx && x <= l + bw && y >= hy && y <= t + bh
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

    private val density: Float
        get() = context.resources.displayMetrics.density
}
