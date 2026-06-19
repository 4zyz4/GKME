package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onStickMoved: ((sx: Short, sy: Short) -> Unit)? = null
    var onStickClickDown: (() -> Unit)? = null
    var onStickClickUp: (() -> Unit)? = null
    var onStickReleased: (() -> Unit)? = null

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
        canvas.drawCircle(centerX, centerY, baseRadius, if (isClicking) basePressedPaint else basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, baseStrokePaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - firstTapTime < 300 && firstTapTime > 0) {
                    handler.removeCallbacks(doubleTapTimeout)
                    isClicking = true
                    isDoubleClick = true
                    firstTapTime = 0
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onStickClickDown?.invoke()
                } else {
                    firstTapTime = now
                    firstTapX = event.x
                    firstTapY = event.y
                    isDoubleClick = false
                    handler.postDelayed(doubleTapTimeout, 300)
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
        var dx = tx - centerX
        var dy = ty - centerY
        val maxD = baseRadius - knobRadius
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > maxD) {
            dx = dx / dist * maxD
            dy = dy / dist * maxD
        }
        knobX = centerX + dx
        knobY = centerY + dy
        invalidate()

        val sx = if (maxD > 0f) ((dx / maxD * 32767).toInt()).toShort() else 0
        val sy = if (maxD > 0f) ((dy / maxD * 32767).toInt()).toShort() else 0
        onStickMoved?.invoke(sx, sy)
    }
}
