package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 0f
    private var sat = 0f
    private var value = 1f

    var color: Int
        get() = Color.HSVToColor(floatArrayOf(hue, sat, value))
        set(c) {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
            invalidate()
        }

    private val density = resources.displayMetrics.density

    // Layout constants (dp)
    private val padDp = 12f
    private val stripWDp = 20f
    private val thumbRDp = 7f
    private val selectorRDp = 8f

    // Computed layout
    private var squareL = 0f
    private var squareT = 0f
    private var squareS = 0f
    private var stripL = 0f
    private var stripW = 0f
    private var totalH = 0f

    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = -0x1
    }
    private val selectorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xCC000000.toInt()
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val satValShader = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hueShader = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = padDp * density
        squareL = pad
        squareT = pad
        val maxSquare = min(w, h) - pad * 2
        stripW = stripWDp * density
        val rightPad = stripW + pad * 2
        squareS = min(maxSquare, w - rightPad)
        stripL = squareL + squareS + pad
        totalH = squareS + pad * 2
    }

    override fun onDraw(canvas: Canvas) {
        val sL = squareL; val sT = squareT; val sS = squareS
        if (sS <= 0) return

        val stripR = stripL + stripW

        // ── saturation/value square ──
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val hGrad = LinearGradient(sL, 0f, sL + sS, 0f, -0x1, hueColor, Shader.TileMode.CLAMP)
        satValShader.shader = hGrad
        canvas.drawRect(sL, sT, sL + sS, sT + sS, satValShader)

        val vGrad = LinearGradient(0f, sT, 0f, sT + sS, 0, -0x1000000, Shader.TileMode.CLAMP)
        val vp = Paint().apply { shader = vGrad }
        canvas.drawRect(sL, sT, sL + sS, sT + sS, vp)

        // selector circle
        val sx = sL + sat * sS
        val sy = sT + (1f - value) * sS
        val sr = selectorRDp * density
        selectorFill.color = color
        canvas.drawCircle(sx, sy, sr, selectorFill)
        canvas.drawCircle(sx, sy, sr, selectorPaint)

        // ── hue strip ──
        val hueColors = intArrayOf(
            -0x10000, -0x100, -0xff0100, -0xff0001, -0xffff01,
            -0xff01, -0x10000
        )
        val huePos = floatArrayOf(0f, 0.17f, 0.33f, 0.5f, 0.67f, 0.83f, 1f)
        val hGrad2 = LinearGradient(0f, sT, 0f, sT + sS, hueColors, huePos, Shader.TileMode.CLAMP)
        hueShader.shader = hGrad2
        canvas.drawRect(stripL, sT, stripR, sT + sS, hueShader)

        // hue thumb
        val thumbY = sT + (hue / 360f) * sS
        val tr = thumbRDp * density
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawRoundRect(RectF(stripL - 1, thumbY - tr, stripR + 1, thumbY + tr), 3f, 3f, thumbFill)
        canvas.drawRoundRect(RectF(stripL - 1, thumbY - tr, stripR + 1, thumbY + tr), 3f, 3f, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false

        val x = event.x; val y = event.y
        val sL = squareL; val sT = squareT; val sS = squareS
        if (sS <= 0) return false

        // Check if in square area
        if (x >= sL - sS * 0.1f && x <= sL + sS * 1.1f && y >= sT - sS * 0.1f && y <= sT + sS * 1.1f) {
            sat = ((x - sL) / sS).coerceIn(0f, 1f)
            value = (1f - (y - sT) / sS).coerceIn(0f, 1f)
            invalidate()
            onColorChanged?.invoke(color)
            return true
        }

        // Check if in hue strip area
        val sLeft = this.stripL; val stripR = sLeft + stripW
        if (x >= sLeft - stripW && x <= stripR + stripW && y >= sT - sS * 0.1f && y <= sT + sS * 1.1f) {
            hue = ((y - sT) / sS).coerceIn(0f, 1f) * 360f
            invalidate()
            onColorChanged?.invoke(color)
            return true
        }

        return false
    }
}
