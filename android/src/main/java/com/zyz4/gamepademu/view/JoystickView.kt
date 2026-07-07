package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var label: String = ""
    var axisRotation: Int = 0
    var onStickMoved: ((sx: Short, sy: Short) -> Unit)? = null
    var onStickClickDown: (() -> Unit)? = null
    var onStickClickUp: (() -> Unit)? = null
    var onStickReleased: (() -> Unit)? = null
    var doubleClickEnable: Boolean = true

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0xdddddd
        style = Paint.Style.FILL
    }
    private val basePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x555556
        style = Paint.Style.FILL
    }
    private val baseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0xaaaaab
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0xaaaaab
        style = Paint.Style.FILL
    }
    private val knobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x888889
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x555556
        textAlign = Paint.Align.CENTER
        textSize = 0f
    }

    private var isTouching = false
    private var isClicking = false
    private var firstTapTime = 0L
    private var firstTapX = 0f
    private var firstTapY = 0f
    private var isDoubleClick = false
    private val handler = Handler(Looper.getMainLooper())
    private val doubleTapTimeout = Runnable { firstTapTime = 0 }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = minOf(w, h) / 2f
        knobRadius = baseRadius * 0.32f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.rotate(axisRotation.toFloat(), centerX, centerY)
        canvas.drawCircle(centerX, centerY, baseRadius, if (isClicking) basePressedPaint else basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, baseStrokePaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobStrokePaint)
        if (label.isNotEmpty()) {
            if (labelPaint.textSize == 0f)
                labelPaint.textSize = knobRadius * 1.1f
            val textY = knobY - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(label, knobX, textY, labelPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (doubleClickEnable) {
                    val now = System.currentTimeMillis()
                    if (now - firstTapTime < 300 && firstTapTime > 0) {
                        handler.removeCallbacks(doubleTapTimeout)
                        isClicking = true
                        isDoubleClick = true
                        firstTapTime = 0
                        invalidate()
                        onStickClickDown?.invoke()
                    } else {
                        firstTapTime = now
                        firstTapX = event.x
                        firstTapY = event.y
                        isDoubleClick = false
                        handler.postDelayed(doubleTapTimeout, 300)
                    }
                }
                isTouching = true
                moveKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouching) {
                    moveKnob(event.x, event.y)
                }
                if (firstTapTime != 0L) {
                    val dx = event.x - firstTapX
                    val dy = event.y - firstTapY
                    if (sqrt(dx * dx + dy * dy) > 30f) {
                        firstTapTime = 0
                        handler.removeCallbacks(doubleTapTimeout)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                isClicking = false
                knobX = centerX
                knobY = centerY
                invalidate()
                if (isDoubleClick) {
                    onStickClickUp?.invoke()
                }
                onStickReleased?.invoke()
                onStickMoved?.invoke(0, 0)
                isDoubleClick = false
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveKnob(tx: Float, ty: Float) {
        val dx = tx - centerX
        val dy = ty - centerY
        val maxD = baseRadius - knobRadius
        val dist = sqrt(dx * dx + dy * dy)

        // Transform to canvas (rotated) space for visual knob position
        val r = axisRotation * Math.PI / 180.0
        val cosR = Math.cos(-r).toFloat()
        val sinR = Math.sin(-r).toFloat()
        val cdx = dx * cosR - dy * sinR
        val cdy = dx * sinR + dy * cosR

        val clampedDist = if (dist > maxD) maxD else dist
        val scale = if (dist > 0f) clampedDist / dist else 0f
        knobX = centerX + cdx * scale
        knobY = centerY + cdy * scale
        invalidate()

        var sx = if (maxD > 0f) ((dx * scale / maxD * 32767).toInt()).toShort() else 0
        var sy = if (maxD > 0f) ((dy * scale / maxD * 32767).toInt()).toShort() else 0

        when (axisRotation % 360) {
            90 -> { val tmp = sx; sx = sy; sy = (-tmp).toShort() }
            180 -> { sx = (-sx).toShort(); sy = (-sy).toShort() }
            270 -> { val tmp = sx; sx = (-sy).toShort(); sy = tmp }
        }
        onStickMoved?.invoke(sx, sy)
    }
}
