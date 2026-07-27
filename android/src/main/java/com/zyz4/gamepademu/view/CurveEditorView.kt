package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class CurveEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var points: MutableList<Pair<Float, Float>> = mutableListOf()
    var onPointsChanged: ((List<Float>) -> Unit)? = null

    private var selectedIndex = -1
    private var dragIndex = -1

    private val paddingLeft = 0f
    private val paddingRight = 0f
    private val paddingTop = 0f
    private val paddingBottom = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x444445
        strokeWidth = 1f
    }
    private val diagonalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x555556
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x100
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x100
        style = Paint.Style.FILL
    }
    private val selectedPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0xff0100
        style = Paint.Style.FILL
    }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x1
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x666667
        textSize = 0f
        textAlign = Paint.Align.CENTER
    }

    private val pointRadius = 20f
    private val hitRadius = 40f
    private var curveAreaSize = 0f
    private var curveAreaLeft = 0f
    private var curveAreaTop = 0f
    private var curveLen = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        curveLen = minOf(w, h).toFloat()
        curveAreaSize = curveLen
        curveAreaLeft = (w - curveLen) / 2f
        curveAreaTop = (h - curveLen) / 2f
        labelPaint.textSize = curveLen * 0.04f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = curveAreaLeft
        val top = curveAreaTop
        val size = curveAreaSize
        val step = size / 5f

        for (i in 0..5) {
            val x = left + i * step
            val y = top + i * step
            canvas.drawLine(x, top, x, top + size, gridPaint)
            canvas.drawLine(left, y, left + size, y, gridPaint)
        }

        canvas.drawLine(left, top + size, left + size, top, diagonalPaint)

        if (points.isNotEmpty()) {
            val path = Path()
            val sorted = points.sortedBy { it.first }
            val segments = 40
            path.moveTo(left, top + size)
            for (j in 1..segments) {
                val t = j.toFloat() / segments
                val y = catmullRomEvaluate(t, sorted)
                val px = left + t * size
                val py = top + (1f - y) * size
                path.lineTo(px, py)
            }
            canvas.drawPath(path, curvePaint)
        }

        for (i in points.indices) {
            val p = points[i]
            val px = left + p.first * size
            val py = top + (1f - p.second) * size
            val paint = if (i == selectedIndex) selectedPointPaint else pointPaint
            canvas.drawCircle(px, py, pointRadius, paint)
            canvas.drawCircle(px, py, pointRadius, pointStrokePaint)
        }

        val labelY = top + size + labelPaint.textSize * 1.8f
        canvas.drawText("手指距离 →", left + size / 2f, labelY, labelPaint)
        canvas.save()
        canvas.rotate(-90f, left - labelPaint.textSize * 1.2f, top + size / 2f)
        canvas.drawText("输出距离 →", left - labelPaint.textSize * 1.2f, top + size / 2f, labelPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val left = curveAreaLeft
        val top = curveAreaTop
        val size = curveAreaSize

        val tx = (event.x - left) / size
        val ty = 1f - (event.y - top) / size

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragIndex = -1
                selectedIndex = -1
                for (i in points.indices) {
                    val p = points[i]
                    val dx = (event.x - (left + p.first * size))
                    val dy = (event.y - (top + (1f - p.second) * size))
                    if (dx * dx + dy * dy < hitRadius * hitRadius) {
                        parent.requestDisallowInterceptTouchEvent(true)
                        dragIndex = i
                        selectedIndex = i
                        invalidate()
                        return true
                    }
                }
                if (tx >= 0f && tx <= 1f && ty >= 0f && ty <= 1f) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    val newX = tx.coerceIn(0.01f, 0.99f)
                    val newY = ty.coerceIn(0.01f, 0.99f)
                    points.add(Pair(newX, newY))
                    selectedIndex = points.size - 1
                    dragIndex = selectedIndex
                    notifyChanged()
                    invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex >= 0 && dragIndex < points.size) {
                    val newX = tx.coerceIn(0.01f, 0.99f)
                    val newY = ty.coerceIn(0.01f, 0.99f)
                    points[dragIndex] = Pair(newX, newY)
                    notifyChanged()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun deleteSelected() {
        if (selectedIndex >= 0 && selectedIndex < points.size) {
            points.removeAt(selectedIndex)
            selectedIndex = -1
            notifyChanged()
            invalidate()
        }
    }

    private fun notifyChanged() {
        val flat = mutableListOf<Float>()
        for (p in points) {
            flat.add(p.first)
            flat.add(p.second)
        }
        onPointsChanged?.invoke(flat)
    }

    fun setFromFlatList(list: List<Float>?) {
        points.clear()
        if (list != null) {
            for (i in 0 until list.size step 2) {
                if (i + 1 < list.size) {
                    points.add(Pair(list[i], list[i + 1]))
                }
            }
        }
        selectedIndex = -1
        invalidate()
    }

    fun hasSelection(): Boolean = selectedIndex >= 0 && selectedIndex < points.size

    private fun catmullRomEvaluate(t: Float, sorted: List<Pair<Float, Float>>): Float {
        val n = sorted.size
        if (n == 0) return t
        if (t <= sorted[0].first) {
            return if (sorted[0].first > 0f) sorted[0].second * t / sorted[0].first else sorted[0].second
        }
        if (t >= sorted[n - 1].first) {
            return if (sorted[n - 1].first < 1f) sorted[n - 1].second + (1f - sorted[n - 1].second) * (t - sorted[n - 1].first) / (1f - sorted[n - 1].first) else sorted[n - 1].second
        }
        for (i in 0 until n - 1) {
            if (t >= sorted[i].first && t < sorted[i + 1].first) {
                val localT = (t - sorted[i].first) / (sorted[i + 1].first - sorted[i].first)
                val pm1 = if (i > 0) sorted[i - 1] else Pair(-(sorted[i + 1].first - sorted[i].first), -(sorted[i + 1].second - sorted[i].second))
                val p2 = if (i < n - 2) sorted[i + 2] else Pair(sorted[i + 1].first + (sorted[i + 1].first - sorted[i].first), sorted[i + 1].second + (sorted[i + 1].second - sorted[i].second))
                return catmullRom(pm1.second, sorted[i].second, sorted[i + 1].second, p2.second, localT)
            }
        }
        return t
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }
}
