package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class AppearancePreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var bgColor: Int = 0xFF000000.toInt()
    var bgBitmap: Bitmap? = null
    var btnColor: Int = 0xFF1A1A1A.toInt()
    var btnBitmap: Bitmap? = null
    var btnOutlineColor: Int = 0xFF666666.toInt()
    var btnOutlineWidth: Int = 1

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun loadBitmap(path: String?): Bitmap? {
        if (path == null) return null
        return try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 16f

        if (bgBitmap != null) {
            ShapeImageUtil.applyCenterCrop(bgPaint, bgBitmap!!, w, h)
        } else {
            bgPaint.shader = null
            bgPaint.color = bgColor
        }
        bgPaint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null

        val usableW = w / 2f - pad * 1.5f
        val btnSize = minOf(usableW, h - pad * 2)
        val cy = h / 2f

        // Circle button (left)
        val cx = w / 4f
        val radius = btnSize / 2f
        if (btnBitmap != null) {
            ShapeImageUtil.applyCenterCrop(circlePaint, btnBitmap!!, btnSize, btnSize)
        } else {
            circlePaint.shader = null
            circlePaint.color = btnColor
        }
        circlePaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, circlePaint)
        circlePaint.shader = null
        if (btnOutlineWidth > 0) {
            circleOutlinePaint.color = btnOutlineColor
            circleOutlinePaint.strokeWidth = btnOutlineWidth.toFloat()
            canvas.drawCircle(cx, cy, radius - btnOutlineWidth / 2f, circleOutlinePaint)
        }

        // Square button (right)
        val sqLeft = w / 2f + pad / 2f
        val sqTop = cy - btnSize / 2f
        val sqRight = sqLeft + btnSize
        val sqBottom = sqTop + btnSize
        if (btnBitmap != null) {
            ShapeImageUtil.applyCenterCrop(rectPaint, btnBitmap!!, btnSize, btnSize)
        } else {
            rectPaint.shader = null
            rectPaint.color = btnColor
        }
        rectPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(sqLeft, sqTop, sqRight, sqBottom), 12f, 12f, rectPaint)
        rectPaint.shader = null
        if (btnOutlineWidth > 0) {
            rectOutlinePaint.color = btnOutlineColor
            rectOutlinePaint.strokeWidth = btnOutlineWidth.toFloat()
            val hs = btnOutlineWidth / 2f
            canvas.drawRoundRect(RectF(sqLeft + hs, sqTop + hs, sqRight - hs, sqBottom - hs), 12f, 12f, rectOutlinePaint)
        }
    }
}
