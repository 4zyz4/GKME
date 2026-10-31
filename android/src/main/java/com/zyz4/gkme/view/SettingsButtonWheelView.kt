package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zyz4.gkme.R
import com.zyz4.gkme.model.SettingsButtonAction
import com.zyz4.gkme.model.SettingsButtonDirection
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 屏幕中心的设置按钮轮盘菜单。
 *
 * 只负责显示：触摸事件仍由设置按钮接收，本 View 不消费任何事件。`selectedDirection`
 * 为 null 表示手指仍停留在中心（松开即打开设置）。
 */
class SettingsButtonWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private var actions: Map<SettingsButtonDirection, SettingsButtonAction> = emptyMap()
    private var fourWay = true
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    var selectedDirection: SettingsButtonDirection? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val outerRect = RectF()
    private val innerRect = RectF()
    private val wedgePath = Path()

    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
    private val surfaceColor = ContextCompat.getColor(context, R.color.bg_surface_light)
    private val surfaceDimColor = ContextCompat.getColor(context, R.color.bg_surface)
    private val outlineColor = ContextCompat.getColor(context, R.color.joy_stroke)
    // 选中态：在深色底上混入少量强调色，保持深色风格又有明确的选中反馈。
    private val selectedFillColor = ColorUtils.blendARGB(surfaceColor, accentColor, 0.30f)
    private val selectedOutlineColor = ColorUtils.blendARGB(outlineColor, accentColor, 0.55f)

    init {
        isClickable = false
        isFocusable = false
    }

    fun bind(
        actions: Map<SettingsButtonDirection, SettingsButtonAction>,
        fourWay: Boolean,
    ) {
        this.actions = actions
        this.fourWay = fourWay
        selectedDirection = null
        touchOffsetX = 0f
        touchOffsetY = 0f
        invalidate()
    }

    /**
     * 手指相对设置按钮的位移（px）。绘制时会等比收缩到轮盘范围内，
     * 因此即使设置按钮离屏幕中心很远，触点也能正确映射到轮盘上。
     */
    fun setTouchOffset(dx: Float, dy: Float) {
        if (touchOffsetX == dx && touchOffsetY == dy) return
        touchOffsetX = dx
        touchOffsetY = dy
        invalidate()
    }

    private fun dp(value: Float): Float = value * density

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (actions.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f
        val minSide = min(width, height).toFloat()
        val radius = (minSide * 0.30f).coerceIn(dp(96f), dp(200f))
        val innerRadius = radius * 0.34f
        val centerRadius = radius * 0.30f

        outerRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        innerRect.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        val directions = SettingsButtonDirection.entries.filter { fourWay.not() || !it.diagonal }
        val sweep = 360f / directions.size

        directions.forEach { direction ->
            val action = actions[direction] ?: SettingsButtonAction.NONE
            val selected = direction == selectedDirection
            // 屏幕坐标 y 向下，故 Canvas 角度取反（顺时针为正）。
            val start = -direction.angleDeg - sweep / 2f

            wedgePath.reset()
            wedgePath.addArc(outerRect, start, sweep)
            wedgePath.arcTo(innerRect, start + sweep, -sweep, false)
            wedgePath.close()

            fillPaint.color = when {
                selected -> withAlpha(selectedFillColor, 0xF2)
                action == SettingsButtonAction.NONE -> withAlpha(surfaceDimColor, 0xCC)
                else -> withAlpha(surfaceColor, 0xE6)
            }
            canvas.drawPath(wedgePath, fillPaint)

            strokePaint.color = if (selected) {
                withAlpha(selectedOutlineColor, 0xFF)
            } else {
                withAlpha(outlineColor, 0xFF)
            }
            canvas.drawPath(wedgePath, strokePaint)

            if (action != SettingsButtonAction.NONE) {
                val angleRad = Math.toRadians(direction.angleDeg.toDouble())
                // 操作文本放在扇环内部的中点，并按扇区可用宽度自动缩小。
                val labelRadius = (innerRadius + radius) / 2f
                val labelX = cx + (labelRadius * cos(angleRad)).toFloat()
                val label = context.getString(action.wheelLabelRes)
                val arcWidth = (2.0 * Math.PI * labelRadius / directions.size).toFloat()
                val maxWidth = minOf(arcWidth * 0.82f, (radius - innerRadius) * 0.95f)
                val baseSize = sp(12f)
                textPaint.textSize = baseSize
                val measured = textPaint.measureText(label)
                if (measured > maxWidth) {
                    textPaint.textSize = (baseSize * maxWidth / measured).coerceAtLeast(sp(7f))
                }
                val metrics = textPaint.fontMetrics
                val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
                val labelY = cy - (labelRadius * sin(angleRad)).toFloat() + baselineOffset
                textPaint.color = if (selected) accentColor else textPrimary
                canvas.drawText(label, labelX, labelY, textPaint)
            }
        }

        // 中心：取消。移回中心死区松手不会执行任何操作。
        fillPaint.color = if (selectedDirection == null) {
            withAlpha(surfaceColor, 0xFA)
        } else {
            withAlpha(surfaceDimColor, 0xFA)
        }
        canvas.drawCircle(cx, cy, centerRadius, fillPaint)
        strokePaint.color = if (selectedDirection == null) {
            accentColor
        } else {
            withAlpha(outlineColor, 0xFF)
        }
        canvas.drawCircle(cx, cy, centerRadius, strokePaint)

        val centerColor = if (selectedDirection == null) accentColor else textSecondary
        cancelPaint.color = centerColor
        val iconCenterY = cy - dp(9f)
        val cross = dp(6f)
        canvas.drawLine(
            cx - cross, iconCenterY - cross,
            cx + cross, iconCenterY + cross,
            cancelPaint,
        )
        canvas.drawLine(
            cx + cross, iconCenterY - cross,
            cx - cross, iconCenterY + cross,
            cancelPaint,
        )
        centerTextPaint.color = centerColor
        canvas.drawText(
            context.getString(R.string.i18n_cancel),
            cx,
            cy + centerRadius * 0.58f,
            centerTextPaint,
        )

        // 手指相对设置按钮的位移，等比映射到轮盘内部并显示触点。
        val offsetLength = hypot(touchOffsetX, touchOffsetY)
        if (offsetLength > dp(2f)) {
            val maxDotRadius = radius - dp(6f)
            val scale = if (offsetLength > maxDotRadius) maxDotRadius / offsetLength else 1f
            val dotX = cx + touchOffsetX * scale
            val dotY = cy + touchOffsetY * scale
            linePaint.color = (accentColor and 0x00FFFFFF) or 0x99000000.toInt()
            canvas.drawLine(cx, cy, dotX, dotY, linePaint)
            fillPaint.color = accentColor
            canvas.drawCircle(dotX, dotY, dp(7f), fillPaint)
            strokePaint.color = 0xCC000000.toInt()
            canvas.drawCircle(dotX, dotY, dp(7f), strokePaint)
        }
    }
}
