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
import kotlin.math.sqrt

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

    private var currentButtons: List<ButtonPosition> = emptyList()
    private var isEditMode = false
    var selectedButtonId: String? = null
        private set
    private var editSnapshot: LayoutPreset? = null
    private var hasChanges = false

    private var draggingChild: View? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var resizingChild: View? = null
    private var resizeStartW = 0
    private var resizeStartH = 0
    private var resizeStartGridX = 0
    private var resizeStartGridY = 0

    var listener: GamepadLayoutListener? = null

    // Per-pointer tracking for non-edit mode multi-touch dispatch
    private val touchTargets = HashMap<Int, View>()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) {
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
                    for ((pid, child) in touchTargets.toMap()) {
                        val idx = event.findPointerIndex(pid)
                        if (idx >= 0) {
                            dispatchToChild(child, event, MotionEvent.ACTION_MOVE, idx)
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
                    for ((_, child) in touchTargets) {
                        val ev = MotionEvent.obtain(
                            event.downTime, event.eventTime,
                            MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                        )
                        child.dispatchTouchEvent(ev)
                        ev.recycle()
                    }
                    touchTargets.clear()
                    return true
                }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun pointerDown(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val x = event.getX(idx)
        val y = event.getY(idx)
        val child = findChildAt(x, y)
        if (child != null) {
            touchTargets[pid] = child
            dispatchToChild(child, event, MotionEvent.ACTION_DOWN, idx)
        }
    }

    private fun pointerUp(event: MotionEvent, idx: Int) {
        val pid = event.getPointerId(idx)
        val child = touchTargets.remove(pid)
        if (child != null) {
            dispatchToChild(child, event, MotionEvent.ACTION_UP, idx)
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
        fun onButtonMoved(buttonId: String, x: Int, y: Int)
        fun onEditModeChanged(isEditMode: Boolean)
    }

    fun loadPreset(preset: LayoutPreset) {
        currentButtons = preset.buttons.toList()
        hasChanges = false
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

    private fun getButtonId(child: View): String? {
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
                    if (selectedButtonId != null) {
                        selectedButtonId = null
                        listener?.onButtonSelected(null)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizingChild != null) {
                    val gridX = (event.x / cellW).toInt().coerceAtLeast(0)
                    val gridY = (event.y / cellH).toInt().coerceAtLeast(0)
                    val id = getButtonId(resizingChild!!) ?: return true
                    val idx = currentButtons.indexOfFirst { it.id == id }
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
                        val pos = currentButtons.find { it.id == id }
                        if (pos != null) listener?.onButtonMoved(id, pos.x, pos.y)
                    }
                    resizingChild = null
                }
                if (draggingChild != null) {
                    val id = getButtonId(draggingChild!!)
                    if (id != null) {
                        val pos = currentButtons.find { it.id == id }
                        if (pos != null) listener?.onButtonMoved(id, pos.x, pos.y)
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

    private val density: Float
        get() = context.resources.displayMetrics.density
}
