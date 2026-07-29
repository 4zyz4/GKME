package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class TouchpadPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var fillColor: Int = 0xFF121212.toInt()
    var fillBitmap: Bitmap? = null
    var outlineColor: Int = 0xFF666666.toInt()
    var outlineWidth: Int = 1

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun loadBitmap(path: String?): Bitmap? {
        if (path == null) return null
        return try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 16f

        if (fillBitmap != null) {
            ShapeImageUtil.applyCenterCrop(fillPaint, fillBitmap!!, w - pad * 2, h - pad * 2)
        } else {
            fillPaint.shader = null
            fillPaint.color = fillColor
        }
        fillPaint.style = Paint.Style.FILL

        val rect = RectF(pad, pad, w - pad, h - pad)
        canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
        fillPaint.shader = null

        if (outlineWidth > 0) {
            outlinePaint.color = outlineColor
            outlinePaint.strokeWidth = outlineWidth.toFloat()
            val hs = outlineWidth / 2f
            canvas.drawRoundRect(RectF(pad + hs, pad + hs, w - pad - hs, h - pad - hs), 10f, 10f, outlinePaint)
        }
    }
}
