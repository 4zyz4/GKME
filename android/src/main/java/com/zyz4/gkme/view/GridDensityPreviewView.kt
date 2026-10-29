package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.zyz4.gkme.R
import com.zyz4.gkme.model.LayoutPreset
import kotlin.math.roundToInt

/**
 * 网格密度可视化预览。
 *
 * - 按当前密度绘制网格线，密度越高网格越细；
 * - 用示例控件演示“吸附到最近网格”的效果，密度越高越接近理想位置；
 * - 关闭网格时不绘制网格线，示例控件保持自由摆放。
 */
class GridDensityPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var useGrid = true
    private var density = LayoutPreset.DEFAULT_GRID_DENSITY

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = dp(0.5f)
    }
    private val idealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CAF50.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt()
        textSize = dp(11f)
    }

    fun setState(useGrid: Boolean, density: Int) {
        this.useGrid = useGrid
        this.density = density.coerceIn(
            LayoutPreset.MIN_GRID_DENSITY,
            LayoutPreset.MAX_GRID_DENSITY,
        )
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = dp(8f)
        val area = RectF(pad, pad, width - pad, height - pad - dp(18f))
        if (area.width() <= 0f || area.height() <= 0f) return

        canvas.drawRoundRect(area, dp(6f), dp(6f), backgroundPaint)
        canvas.drawRoundRect(area, dp(6f), dp(6f), borderPaint)

        if (useGrid) {
            val cell = area.width() / density
            var x = area.left
            while (x <= area.right + 0.5f) {
                canvas.drawLine(x, area.top, x, area.bottom, gridPaint)
                x += cell
            }
            var y = area.top
            while (y <= area.bottom + 0.5f) {
                canvas.drawLine(area.left, y, area.right, y, gridPaint)
                y += cell
            }
        }

        // 理想位置固定为 30% / 35%，尺寸 22%；实际控件吸附到最近网格。
        val idealLeft = area.left + area.width() * 0.30f
        val idealTop = area.top + area.height() * 0.35f
        val idealSize = minOf(area.width(), area.height()) * 0.22f
        val ideal = RectF(idealLeft, idealTop, idealLeft + idealSize, idealTop + idealSize)

        val snapped = if (useGrid) {
            val cell = area.width() / density
            fun snap(value: Float, origin: Float): Float =
                origin + ((value - origin) / cell).roundToInt() * cell
            val left = snap(ideal.left, area.left)
            val top = snap(ideal.top, area.top)
            val right = snap(ideal.right, area.left)
            val bottom = snap(ideal.bottom, area.top)
            RectF(left, top, maxOf(right, left + cell), maxOf(bottom, top + cell))
        } else {
            RectF(ideal)
        }

        canvas.drawRoundRect(ideal, dp(4f), dp(4f), idealPaint)
        canvas.drawRoundRect(snapped, dp(4f), dp(4f), samplePaint)

        val label = if (useGrid) {
            context.getString(R.string.i18n_grid_preview_on, density)
        } else {
            context.getString(R.string.i18n_grid_preview_off)
        }
        canvas.drawText(label, area.left, height - dp(4f), textPaint)
    }
}
