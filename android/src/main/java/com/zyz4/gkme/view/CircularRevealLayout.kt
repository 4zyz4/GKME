package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.LinearLayout

class CircularRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val holePath = Path()
    private var holeX = 0f
    private var holeY = 0f
    private var holeRadius = 0f
    private var holeEnabled = false

    fun setHole(x: Float, y: Float, radius: Float) {
        holeX = x
        holeY = y
        holeRadius = radius
        holeEnabled = radius > 0f
        invalidate()
    }

    fun clearHole() {
        holeEnabled = false
        holeRadius = 0f
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (holeEnabled && holeRadius > 0f) {
            holePath.reset()
            holePath.addCircle(holeX, holeY, holeRadius, Path.Direction.CW)
            canvas.save()
            canvas.clipOutPath(holePath)
            super.draw(canvas)
            canvas.restore()
        } else {
            super.draw(canvas)
        }
    }
}
