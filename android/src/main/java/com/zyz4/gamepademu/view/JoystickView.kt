package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
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
        set(value) {
            field = value
            invalidate()
        }
    var onStickMoved: ((sx: Short, sy: Short) -> Unit)? = null
    var onStickClickDown: (() -> Unit)? = null
    var onStickClickUp: (() -> Unit)? = null
    var onStickReleased: (() -> Unit)? = null
    var doubleClickEnable: Boolean = true
    var forceFollowFinger: Boolean = false
    var idleTransparency: Int = 0
    var activeTransparency: Int = 0
    var sensitivityCurve: List<Float>? = null
    var deadZone: Int = 0
    var showDeadZoneIndicator: Boolean = false
    var isSelectedInEditor: Boolean = false
    // Max label size in px (from the adaptive icon-size setting); null = sized relative to the cap.
    var labelMaxSizePx: Float? = null

    private var centerX = 0f
    private var centerY = 0f
    private var effectiveCenterX = 0f
    private var effectiveCenterY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    // Appearance properties
    var appearanceBaseColor: Int = -0xdddddd
    var appearanceBaseBitmap: Bitmap? = null
    var appearanceBaseOutlineColor: Int = -0xaaaaab
    var appearanceBaseOutlineWidth: Float = 2f
    var appearanceCapColor: Int = -0xaaaaab
    var appearanceCapBitmap: Bitmap? = null
    var appearanceCapOutlineColor: Int = -0x888889
    var appearanceCapOutlineWidth: Float = 1.5f
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val baseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val deadZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x330000ff
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        effectiveCenterX = centerX
        effectiveCenterY = centerY
        baseRadius = minOf(w, h) / 2f
        knobRadius = baseRadius * 0.32f
        knobX = effectiveCenterX
        knobY = effectiveCenterY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.rotate(axisRotation.toFloat(), effectiveCenterX, effectiveCenterY)

        val active = isClicking

        // Base fill
        if (appearanceBaseBitmap != null) {
            ShapeImageUtil.applyCenterCrop(basePaint, appearanceBaseBitmap!!, baseRadius * 2, baseRadius * 2)
        } else {
            basePaint.shader = null
            basePaint.color = if (active) highlightColor(appearanceBaseColor, 0.3f) else appearanceBaseColor
        }
        canvas.drawCircle(effectiveCenterX, effectiveCenterY, baseRadius, basePaint)
        basePaint.shader = null

        // Base outline
        if (appearanceBaseOutlineWidth > 0f) {
            baseStrokePaint.color = if (active) highlightColor(appearanceBaseOutlineColor, 0.3f) else appearanceBaseOutlineColor
            baseStrokePaint.strokeWidth = appearanceBaseOutlineWidth
            canvas.drawCircle(effectiveCenterX, effectiveCenterY, baseRadius - appearanceBaseOutlineWidth / 2f, baseStrokePaint)
        }

        // Cap fill
        if (appearanceCapBitmap != null) {
            ShapeImageUtil.applyCenterCrop(knobPaint, appearanceCapBitmap!!, knobRadius * 2, knobRadius * 2)
        } else {
            knobPaint.shader = null
            knobPaint.color = if (active) highlightColor(appearanceCapColor, 0.3f) else appearanceCapColor
        }
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        knobPaint.shader = null

        // Cap outline
        if (appearanceCapOutlineWidth > 0f) {
            knobStrokePaint.color = if (active) highlightColor(appearanceCapOutlineColor, 0.3f) else appearanceCapOutlineColor
            knobStrokePaint.strokeWidth = appearanceCapOutlineWidth
            canvas.drawCircle(knobX, knobY, knobRadius - appearanceCapOutlineWidth / 2f, knobStrokePaint)
        }

        if (deadZone > 0 && showDeadZoneIndicator && isSelectedInEditor) {
            val dzRadius = baseRadius * (deadZone / 100f)
            canvas.drawCircle(effectiveCenterX, effectiveCenterY, dzRadius, deadZonePaint)
        }
        if (label.isNotEmpty()) {
            // Follow the adaptive icon-size cap (labelMaxSizePx); otherwise keep the natural
            // size relative to the cap (capped by the knob itself).
            val natural = knobRadius * 1.1f
            labelPaint.textSize = labelMaxSizePx?.let { minOf(natural, it) } ?: natural
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
                alpha = 1f - (activeTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
                if (forceFollowFinger) {
                    effectiveCenterX = event.x
                    effectiveCenterY = event.y
                }
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
                alpha = 1f - (idleTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
                if (forceFollowFinger) {
                    effectiveCenterX = centerX
                    effectiveCenterY = centerY
                }
                knobX = effectiveCenterX
                knobY = effectiveCenterY
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
        val dx = tx - effectiveCenterX
        val dy = ty - effectiveCenterY
        val maxD = baseRadius - knobRadius
        val dist = sqrt(dx * dx + dy * dy)

        val r = axisRotation * Math.PI / 180.0
        val cosR = Math.cos(-r).toFloat()
        val sinR = Math.sin(-r).toFloat()
        val cdx = dx * cosR - dy * sinR
        val cdy = dx * sinR + dy * cosR

        val clampedDist = if (dist > maxD) maxD else dist
        val normalized = if (maxD > 0f) clampedDist / maxD else 0f
        val dz = (deadZone / 100f).coerceIn(0f, 0.99f)
        val afterDeadZone = if (normalized <= dz) 0f else (normalized - dz) / (1f - dz)
        val afterCurve = evaluateCurve(afterDeadZone)
        val finalDist = afterCurve * maxD

        val scale = if (dist > 0f) finalDist / dist else 0f
        knobX = effectiveCenterX + cdx * scale
        knobY = effectiveCenterY + cdy * scale
        invalidate()

        val dirScale = if (dist > 0f) afterCurve / dist else 0f
        var sx = if (maxD > 0f) ((dx * dirScale * 32767).toInt()).toShort() else 0
        var sy = if (maxD > 0f) ((dy * dirScale * 32767).toInt()).toShort() else 0

        when (axisRotation % 360) {
            90 -> { val tmp = sx; sx = sy; sy = (-tmp).toShort() }
            180 -> { sx = (-sx).toShort(); sy = (-sy).toShort() }
            270 -> { val tmp = sx; sx = (-sy).toShort(); sy = tmp }
        }
        onStickMoved?.invoke(sx, sy)
    }

    private fun evaluateCurve(t: Float): Float {
        if (sensitivityCurve == null || sensitivityCurve!!.size < 2) return t
        val pts = mutableListOf<Pair<Float, Float>>()
        for (i in sensitivityCurve!!.indices step 2) {
            if (i + 1 < sensitivityCurve!!.size) {
                pts.add(Pair(sensitivityCurve!![i], sensitivityCurve!![i + 1]))
            }
        }
        if (pts.isEmpty()) return t
        val sorted = pts.sortedBy { it.first }

        val inVal = t.coerceIn(0f, 1f)
        if (inVal <= sorted.first().first) {
            if (sorted.first().first > 0f) return sorted.first().second * inVal / sorted.first().first
            return sorted.first().second
        }
        if (inVal >= sorted.last().first) {
            if (sorted.last().first < 1f) return sorted.last().second + (1f - sorted.last().second) * (inVal - sorted.last().first) / (1f - sorted.last().first)
            return sorted.last().second
        }

        for (i in 0 until sorted.size - 1) {
            val p0 = sorted[i]
            val p1 = sorted[i + 1]
            if (inVal >= p0.first && inVal < p1.first) {
                val localT = (inVal - p0.first) / (p1.first - p0.first)
                val pm1 = if (i > 0) sorted[i - 1] else Pair(-(p1.first - p0.first), -(p1.second - p0.second))
                val p2 = if (i < sorted.size - 2) sorted[i + 2] else Pair(p1.first + (p1.first - p0.first), p1.second + (p1.second - p0.second))
                return catmullRom(pm1.second, p0.second, p1.second, p2.second, localT)
            }
        }
        return inVal
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun highlightColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
