package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.zyz4.gkme.model.LayoutPreset

/**
 * 网格密度可视化预览。
 *
 * 只绘制网格本身，不再叠加示例控件：密度越高网格越细。
 * 网格线以预览区中心为圆心向四周渐隐，使视觉焦点集中在中心，
 * 同时避免边缘密集的线条显得杂乱。
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
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        val area = RectF(pad, pad, width - pad, height - pad)
        if (area.width() <= 0f || area.height() <= 0f) return

        canvas.drawRoundRect(area, dp(6f), dp(6f), backgroundPaint)

        drawGrid(canvas, area)

        canvas.drawRoundRect(area, dp(6f), dp(6f), borderPaint)
    }

    /** 绘制中心到边缘渐隐的网格线；关闭网格时只保留空白预览区。 */
    private fun drawGrid(canvas: Canvas, area: RectF) {
        if (!useGrid) return
        val cell = area.width() / density
        if (cell <= 0f) return

        val cx = area.centerX()
        val cy = area.centerY()
        val radius = area.width() / 2f

        // 径向渐变：中心最亮，向外逐渐透明。通过局部矩阵把圆压成与预览区
        // 同比例的椭圆，使上下左右四条边都在同一距离处渐隐到透明。
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0xCCFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        if (area.height() > 0f) {
            shader.setLocalMatrix(Matrix().apply {
                setScale(1f, area.height() / area.width(), cx, cy)
            })
        }
        gridPaint.shader = shader

        // 密度越高线越细，避免高密度时线条糊成实心色块。
        gridPaint.strokeWidth = (cell * 0.25f).coerceIn(dp(0.25f), dp(1f))

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

        gridPaint.shader = null
    }
}
