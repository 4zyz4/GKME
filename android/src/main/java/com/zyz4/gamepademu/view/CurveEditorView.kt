package com.zyz4.gamepademu.view

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

    private val curveTempPoints = mutableListOf<Pair<Float, Float>>()

    private fun drawCurve(canvas: Canvas, left: Float, top: Float, size: Float) {
        if (points.isEmpty()) {
            val path = Path()
            path.moveTo(left, top + size)
            path.lineTo(left + size, top)
            canvas.drawPath(path, curvePaint)
            return
        }

        val path = Path()
        // Prepend (0,0) and append (1,1) to user points for interpolation
        curveTempPoints.clear()
        curveTempPoints.add(Pair(0f, 0f))
        curveTempPoints.addAll(points.sortedBy { it.first })
        curveTempPoints.add(Pair(1f, 1f))

        path.moveTo(left, top + size)
        for (j in 1..40) {
            val x = j.toFloat() / 40
            val y = evalCRWithKnots(x, curveTempPoints)
            path.lineTo(left + x * size, top + (1f - y) * size)
        }
        canvas.drawPath(path, curvePaint)
    }

    private fun findSegmentIndex(sortedWithKnots: List<Pair<Float, Float>>, cx: Float): Int {
        for (i in 0 until sortedWithKnots.size - 1) {
            val p0 = sortedWithKnots[i]
            val p1 = sortedWithKnots[i + 1]
            if (cx >= p0.first - 1e-6f && cx <= p1.first + 1e-6f && p0.first < p1.first + 1e-6f) {
                return i
            }
        }
        return sortedWithKnots.size - 2
    }

    /**
     * Catmull-Rom with explicit knots including (0,0) at index=0 and (1,1) at last index.
     * The curve passes through every knot in the chain.
     */
    private fun evalCRWithKnots(x: Float, sortedWithKnots: List<Pair<Float, Float>>): Float {
        val cx = x.coerceIn(0f, 1f)

        // At exact endpoints, return exact values
        if (cx < 1e-6f) return 0f
        if (cx >= 1f - 1e-5f) return 1f

        val segIdx = findSegmentIndex(sortedWithKnots, cx)
        val p0 = sortedWithKnots[segIdx]       // before current segment
        val p1 = sortedWithKnots[segIdx + 1]   // current segment target
        val pLocalIdx = segIdx                  // index of p0 in the full list

        val pm1 = if (pLocalIdx > 0) sortedWithKnots[pLocalIdx - 1]
                  else Pair(2f * 0f - p0.first, 2f * 0f - p0.second)  // extrapolate past (0,0)
        val p2 = if (pLocalIdx < sortedWithKnots.size - 2) sortedWithKnots[pLocalIdx + 2]
                 else Pair(2f * p1.first, 2f * p1.second)  // extrapolate past (1,1)

        val lt = if ((p1.first - p0.first) > 1e-6f)
            (cx - p0.first) / (p1.first - p0.first) else 0f

        return cr(pm1.second, p0.second, p1.second, p2.second, lt.coerceIn(0f, 1f))
    }

    private fun absSorted(): List<Pair<Float, Float>> {
        return points.sortedBy { it.first }.toMutableList()
    }

    private fun evalCR(x: Float, sorted: List<Pair<Float, Float>>): Float {
        val cx = x.coerceIn(0f, 1f)

        if (sorted.isEmpty()) return cx

        if (cx <= sorted[0].first) {
            val fx = sorted[0].first.coerceAtLeast(0.001f)
            return sorted[0].second * cx / fx
        }

        for (i in 0 until sorted.size - 1) {
            val p0 = sorted[i]; val p1 = sorted[i + 1]
            if (cx >= p0.first && cx < p1.first && p0.first < p1.first) {
                val lt = (cx - p0.first) / (p1.first - p0.first)
                val pm1 = if (i > 0) sorted[i - 1] else Pair(2f * 0f - p0.first, 2f * 0f - p0.second)
                val p2 = if (i < sorted.size - 2) sorted[i + 2] else Pair(
                    2f * p1.first - p0.first, 2f * p1.second - p0.second
                )
                return cr(pm1.second, p0.second, p1.second, p2.second, lt)
            }
        }

        val last = sorted.last()
        if (cx >= last.first) {
            if (last.first < 1f - 0.001f) {
                val slope = (last.second - if (sorted.size > 1) sorted[sorted.size - 2].second else 0f) /
                            (last.first - if (sorted.size > 1) sorted[sorted.size - 2].first else 0f)
                return last.second + slope * (cx - last.first)
            }
            return last.second
        }

        return last.second
    }

    /**
     * Catmull-Rom evaluation with (0,0) and (1,1) as implicit knots.
     * sortedWithFinal must include (1,1) as the last element.
     * @deprecated Use evalCRWithKnots instead.
     */
    @Deprecated("Use evalCRWithKnots", ReplaceWith("evalCRWithKnots(x, sortedWithFinal)"))
    private fun evalCRWithFinal(x: Float, sortedWithFinal: List<Pair<Float, Float>>): Float {
        val cx = x.coerceIn(0f, 1f)

        // cx == 0: always return 0 (passes through origin)
        if (cx < 1e-6f) return 0f

        // cx == 1: must return 1 (passes through (1,1))
        if (cx >= 1f - 1e-5f) return 1f

        // Find the segment containing cx
        for (i in 0 until sortedWithFinal.size - 1) {
            val p0 = sortedWithFinal[i]
            val p1 = sortedWithFinal[i + 1]
            if (cx >= p0.first && cx < p1.first && p0.first < p1.first) {
                val lt = (cx - p0.first) / (p1.first - p0.first)
                val pm1 = if (i > 0) sortedWithFinal[i - 1] else Pair(2f * 0f - p0.first, 2f * 0f - p0.second)
                val p2 = if (i < sortedWithFinal.size - 2) sortedWithFinal[i + 2] else Pair(
                    2f * p1.first, 2f * p1.second - 1f // extrapolate past (1,1) so curve naturally reaches it
                )
                return cr(pm1.second, p0.second, p1.second, p2.second, lt)
            }
        }

        // Fallback: should not reach here but clamp to last known
        return sortedWithFinal.last().second
    }

    private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
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