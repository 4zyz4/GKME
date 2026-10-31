package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.zyz4.gkme.model.LayoutPreset

/**
 * 网格密度可视化预览。
 *
 * 只绘制网格本身，不再叠加示例控件：密度越高网格越细。
 * 没有底板与边框，网格直接显示在面板的半透明背景上；
 * 网格线通过矩形蒙版向四条边渐隐，使视觉焦点集中在中心，
 * 同时避免边缘密集的线条显得杂乱。
 */
class GridDensityPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var useGrid = true
    private var density = LayoutPreset.DEFAULT_GRID_DENSITY

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

        val area = RectF(0f, 0f, width.toFloat(), height.toFloat())
        if (area.width() <= 0f || area.height() <= 0f) return

        drawGrid(canvas, area)
    }

    /** 绘制矩形蒙版渐隐的网格线；关闭网格时预览区完全透明。 */
    private fun drawGrid(canvas: Canvas, area: RectF) {
        if (!useGrid) return
        val cell = area.width() / density
        if (cell <= 0f) return

        gridPaint.shader = buildRectMask(area)

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

    /**
     * 构建矩形透明度蒙版：水平、垂直两条渐变相乘（DST_IN），
     * 使网格在内侧矩形区域完全不透明，并向四条边对称渐隐到透明。
     */
    private fun buildRectMask(area: RectF): Shader {
        val alphas = intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF)
        val stops = floatArrayOf(0f, FADE_FRACTION, 1f - FADE_FRACTION, 1f)
        val horizontal = LinearGradient(
            area.left, 0f, area.right, 0f,
            alphas, stops, Shader.TileMode.CLAMP,
        )
        val vertical = LinearGradient(
            0f, area.top, 0f, area.bottom,
            alphas, stops, Shader.TileMode.CLAMP,
        )
        return ComposeShader(horizontal, vertical, PorterDuff.Mode.DST_IN)
    }

    private companion object {
        /** 每条边向内渐隐的比例。 */
        const val FADE_FRACTION = 0.18f
    }
}
