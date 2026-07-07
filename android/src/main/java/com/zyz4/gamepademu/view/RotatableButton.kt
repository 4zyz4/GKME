package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.Button

class RotatableButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    var textRotation: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = textRotation % 360
        if (r == 0) {
            super.onDraw(canvas)
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        canvas.save()
        canvas.rotate(r.toFloat(), cx, cy)
        super.onDraw(canvas)
        canvas.restore()
    }
}
