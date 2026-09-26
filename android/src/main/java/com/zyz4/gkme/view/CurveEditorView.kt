package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x444445; strokeWidth = 1f }
    private val diagonalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x555556; strokeWidth = 2f; style = Paint.Style.STROKE }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; strokeWidth = 3f; style = Paint.Style.STROKE }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x100; style = Paint.Style.FILL }
    private val selectedPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0xff0100; style = Paint.Style.FILL }
    private val pointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x666667; textSize = 0f; textAlign = Paint.Align.CENTER }

    private val pointRadius = 20f
    private val pointHitRadius = 35f
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
        val left = curveAreaLeft
        val top = curveAreaTop
        val size = curveAreaSize
        val step = size / 5f

        for (i in 0..5) {
            canvas.drawLine(left + i * step, top, left + i * step, top + size, gridPaint)
            canvas.drawLine(left, top + i * step, left + size, top + i * step, gridPaint)
        }
        canvas.drawLine(left, top + size, left + size, top, diagonalPaint)

        drawCurve(canvas, left, top, size)

        for (i in points.indices) {
            val p = points[i]
            val px = left + p.first * size
            val py = top + (1f - p.second) * size
            canvas.drawCircle(px, py, pointRadius, if (i == selectedIndex) selectedPointPaint else pointPaint)
            canvas.drawCircle(px, py, pointRadius, pointStrokePaint)
        }

        val labelY = top + size + labelPaint.textSize * 1.8f
        canvas.drawText("手指距离 ->", left + size / 2f, labelY, labelPaint)
        canvas.save()
        canvas.rotate(-90f, left - labelPaint.textSize * 1.2f, top + size / 2f)
        canvas.drawText("输出距离 ->", left - labelPaint.textSize * 1.2f, top + size / 2f, labelPaint)
        canvas.restore()
    }

    private fun drawCurve(canvas: Canvas, left: Float, top: Float, size: Float) {
        if (points.isEmpty()) {
            val path = Path()
            path.moveTo(left, top + size)
            path.lineTo(left + size, top)
            canvas.drawPath(path, curvePaint)
            return
        }

        val flat = ArrayList<Float>(points.size * 2)
        for (p in points) {
            flat.add(p.first)
            flat.add(p.second)
        }

        val path = Path()
        path.moveTo(left, top + size)
        for (j in 1..40) {
            val x = j.toFloat() / 40
            val y = com.zyz4.gkme.input.SensitivityCurve.evaluate(flat, x)
            path.lineTo(left + x * size, top + (1f - y) * size)
        }
        canvas.drawPath(path, curvePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val canvasX = (event.x - curveAreaLeft) / curveAreaSize
        val canvasY = 1f - (event.y - curveAreaTop) / curveAreaSize

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = findHitPoint(event, canvasX, canvasY)
                if (idx >= 0) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    dragIndex = idx
                    selectedIndex = idx
                    invalidate()
                    return true
                }
                if (canvasX >= 0f && canvasX <= 1f && canvasY >= 0f && canvasY <= 1f) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    val clampedX = canvasX.coerceIn(0f, 1f)
                    val clampedY = canvasY.coerceIn(0f, 1f)
                    points.add(Pair(clampedX, clampedY))
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
                    parent.requestDisallowInterceptTouchEvent(true)
                    val clampedX = canvasX.coerceIn(0f, 1f)
                    val clampedY = canvasY.coerceIn(0f, 1f)
                    points[dragIndex] = Pair(clampedX, clampedY)
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

    private fun findHitPoint(event: MotionEvent, canvasX: Float, canvasY: Float): Int {
        for (i in points.indices) {
            val p = points[i]
            val px = event.x - (curveAreaLeft + p.first * curveAreaSize)
            val py = event.y - (curveAreaTop + (1f - p.second) * curveAreaSize)
            if (px * px + py * py < pointHitRadius * pointHitRadius) return i
        }
        return -1
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
        for (p in points) { flat.add(p.first); flat.add(p.second) }
        onPointsChanged?.invoke(flat)
    }

    fun setFromFlatList(list: List<Float>?) {
        points.clear()
        if (list != null) {
            for (i in 0 until list.size step 2) {
                if (i + 1 < list.size) {
                    val ax = list[i]
                    val ay = list[i + 1]
                    if (ax >= 0f && ax <= 1f && ay >= 0f && ay <= 1f) {
                        points.add(Pair(ax, ay))
                    }
                }
            }
        }
        selectedIndex = -1
        invalidate()
    }

    fun hasSelection(): Boolean = selectedIndex >= 0 && selectedIndex < points.size
}