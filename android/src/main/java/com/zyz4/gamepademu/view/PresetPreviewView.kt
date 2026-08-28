package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.zyz4.gamepademu.model.ButtonPosition

class PresetPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var buttons: List<ButtonPosition> = emptyList()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x555556
        style = Paint.Style.FILL
    }
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x444445
        style = Paint.Style.FILL
    }

    fun setButtons(buttonPositions: List<ButtonPosition>) {
        buttons = buttonPositions
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (buttons.isEmpty()) return

        val w = width.toFloat() - paddingLeft - paddingRight
        val h = height.toFloat() - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val gridW = 120f
        val gridH = 53f
        val scaleX = w / gridW
        val scaleY = h / gridH
        val scale = minOf(scaleX, scaleY)
        val offsetX = paddingLeft + (w - gridW * scale) / 2f
        val offsetY = paddingTop + (h - gridH * scale) / 2f

        for (btn in buttons) {
            if (!btn.visible) continue
            val bx = offsetX + btn.x * scale
            val by = offsetY + btn.y * scale
            val isSwapped = !btn.lockAspect && (btn.rotation == 90 || btn.rotation == 270)
            val bw = (if (isSwapped) btn.height else btn.width) * scale
            val bh = (if (isSwapped) btn.width else btn.height) * scale
            if (btn.id.substringBefore("_") == "dpadPad" || btn.id.substringBefore("_") == "customKeypad") {
                val s = minOf(bw, bh)
                val cx = bx + (bw - s) / 2f + s / 2f
                val cy = by + (bh - s) / 2f + s / 2f
                canvas.drawCircle(cx, cy, s / 2f, rectPaint)
            } else if (btn.lockAspect) {
                val cx = bx + bw / 2f
                val cy = by + bh / 2f
                val r = (minOf(btn.width, btn.height) * scale * 0.35f).coerceAtLeast(2f)
                canvas.drawCircle(cx, cy, r, circlePaint)
            } else {
                val r = 2f * density
                canvas.drawRoundRect(bx, by, bx + bw, by + bh, r, r, rectPaint)
            }
        }
    }

    private val density: Float
        get() = context.resources.displayMetrics.density
}
