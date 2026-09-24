package com.zyz4.gkme.view

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
            applyTextRotationFit()
            invalidate()
        }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyTextRotationFit()
    }

    private fun applyTextRotationFit() {
        val r = textRotation % 360
        if (r != 90 && r != 270) {
            if (paddingLeft != 0 || paddingTop != 0 || paddingRight != 0 || paddingBottom != 0) {
                setPadding(0, 0, 0, 0)
            }
            return
        }
        if (width <= 0 || height <= 0) return
        val padH = (width - height) / 2
        val padV = (height - width) / 2
        if (paddingLeft != padH || paddingTop != padV ||
            paddingRight != padH || paddingBottom != padV
        ) {
            setPadding(padH, padV, padH, padV)
        }
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
