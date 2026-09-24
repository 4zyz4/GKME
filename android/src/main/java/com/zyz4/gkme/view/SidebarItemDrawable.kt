package com.zyz4.gkme.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class SidebarItemDrawable : Drawable() {

    private val basePaint = Paint().apply { color = Color.TRANSPARENT }
    private val selectedPaint = Paint().apply { color = 0xFF2A2A2A.toInt() }

    var fill: Float = 0f

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)
        if (fill > 0f) {
            canvas.save()
            canvas.clipRect(
                b.left.toFloat(),
                b.top.toFloat(),
                b.left + b.width() * fill.coerceIn(0f, 1f),
                b.bottom.toFloat()
            )
            canvas.drawRect(b, selectedPaint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        selectedPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        selectedPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
