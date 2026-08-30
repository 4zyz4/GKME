package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class JoystickPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var baseColor: Int = -0xdddddd
    var baseBitmap: Bitmap? = null
    var baseOutlineColor: Int = -0xaaaaab
    var baseOutlineWidth: Int = 2
    var capColor: Int = -0xaaaaab
    var capBitmap: Bitmap? = null
    var capOutlineColor: Int = -0x888889
    var capOutlineWidth: Int = 2
    var triggerColor: Int = -0x666667
    var triggerWidth: Int = 3

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val triggerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun loadBitmap(path: String?): Bitmap? {
        if (path == null) return null
        return try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val baseR = minOf(w, h) / 2f * 0.75f
        val capR = baseR * 0.4f
        val triggerPad = 8f
        val triggerR = baseR + triggerPad

        triggerPaint.color = triggerColor
        triggerPaint.strokeWidth = triggerWidth.toFloat()
        val hs = triggerWidth / 2f
        canvas.drawRect(cx - triggerR + hs, cy - triggerR + hs, cx + triggerR - hs, cy + triggerR - hs, triggerPaint)

        if (baseBitmap != null) {
            ShapeImageUtil.applyCenterCrop(basePaint, baseBitmap!!, baseR * 2, baseR * 2)
        } else {
            basePaint.shader = null
            basePaint.color = baseColor
        }
        basePaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, baseR, basePaint)
        basePaint.shader = null

        if (baseOutlineWidth > 0) {
            baseOutlinePaint.color = baseOutlineColor
            baseOutlinePaint.strokeWidth = baseOutlineWidth.toFloat()
            canvas.drawCircle(cx, cy, baseR - baseOutlineWidth / 2f, baseOutlinePaint)
        }

        if (capBitmap != null) {
            ShapeImageUtil.applyCenterCrop(capPaint, capBitmap!!, capR * 2, capR * 2)
        } else {
            capPaint.shader = null
            capPaint.color = capColor
        }
        capPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, capR, capPaint)
        capPaint.shader = null

        if (capOutlineWidth > 0) {
            capOutlinePaint.color = capOutlineColor
            capOutlinePaint.strokeWidth = capOutlineWidth.toFloat()
            canvas.drawCircle(cx, cy, capR - capOutlineWidth / 2f, capOutlinePaint)
        }
    }
}
