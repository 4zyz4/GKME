package com.zyz4.gkme.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.Button
import android.view.MotionEvent
import com.zyz4.gkme.model.SlideDirection
import kotlin.math.abs
import kotlin.math.min

/**
 * Linear trigger view — a rounded-rectangle button that slides along one axis.
 * Looks identical to other buttons (same background drawable, same text rendering).
 */
class LinearTriggerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : Button(context, attrs, defStyleAttr) {

    init {
        gravity = android.view.Gravity.CENTER
    }

    var slideDirection: SlideDirection = SlideDirection.DOWN
        set(value) {
            field = value
            setTranslationX(0f)
            setTranslationY(0f)
        }
    var travelDistance: Int = 10
        set(value) {
            field = value
            invalidate()
        }
    var idleOpacity: Int = 100
        set(value) {
            field = value
        }
    var activeOpacity: Int = 100
        set(value) {
            field = value
        }

    /** 预测：用滑杆位移速度外推下一帧行程，加快模拟扳机响应。 */
    var prediction: Boolean = false

    var onValueChange: ((value: Int) -> Unit)? = null
    var onTriggerBottomVibrate: () -> Unit = {}
    var onButtonDown: (() -> Unit)? = null
    var onButtonUp: (() -> Unit)? = null

    private var travelPx = 0f

    private var isDragging = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var currentValue = 0
    private var wasAtMax = false
    // ── 自动保持：松手后锁定当前行程，下一次拖动从锁定位置继续 ──
    private var latched = false
    private var baseOffset = 0f
    // ── 预测：history + EMA 速度（与摇杆/鼠标板共用同一套估计）──
    private val velocity = EmaVelocityTracker(TAG)

    fun getCellSize(): Float {
        var p = parent
        while (p != null) {
            if (p is com.zyz4.gkme.view.GamepadLayout) {
                return p.getCellSize()
            }
            p = if (p is android.view.View) p.parent else null
        }
        return 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cellSize = getCellSize()
        travelPx = if (cellSize > 0f) {
            (cellSize * travelDistance).coerceAtLeast(1f)
        } else {
            (min(w.toFloat(), h.toFloat()) * travelDistance / 40f).coerceAtLeast(1f)
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        // Apply opacity for idle/active states
        val targetAlpha = (if (isDragging || latched) activeOpacity else idleOpacity).coerceIn(0, 100) / 100f
        if (alpha != targetAlpha) {
            alpha = targetAlpha
        }

        super.onDraw(canvas)
    }

    private fun isGamepadEditMode(): Boolean {
        val p = parent
        if (p != null && p is android.view.ViewGroup) {
            var current: android.view.View? = this
            while (current != null) {
                val parent = current.parent
                if (parent is com.zyz4.gkme.view.GamepadLayout) {
                    return parent.isEditModeActive()
                }
                if (parent is android.view.View) {
                    current = parent
                } else {
                    break
                }
            }
        }
        return false
    }

    private fun isAutoHoldEnabled(): Boolean {
        val id = tag as? String ?: return false
        var p = parent
        while (p != null) {
            if (p is com.zyz4.gkme.view.GamepadLayout) {
                return p.currentButtons.find { it.id == id }?.autoHold == true
            }
            p = if (p is android.view.View) p.parent else null
        }
        return false
    }

    /** 手指坐标 → 夹取在行程内的滑杆偏移（负值表示向上/向左）。 */
    private fun offsetFor(x: Float, y: Float): Float {
        val dx = x - initialTouchX
        val dy = y - initialTouchY
        return when (slideDirection) {
            SlideDirection.DOWN -> (baseOffset + dy).coerceIn(0f, travelPx)
            SlideDirection.UP -> (baseOffset + dy).coerceIn(-travelPx, 0f)
            SlideDirection.LEFT -> (baseOffset + dx).coerceIn(-travelPx, 0f)
            SlideDirection.RIGHT -> (baseOffset + dx).coerceIn(0f, travelPx)
        }
    }

    /** 把事件中的历史样本与当前样本（按夹取后的行程）送入速度估计。 */
    private fun addPredictionSamples(event: MotionEvent) {
        for (h in 0 until event.historySize) {
            velocity.add(
                offsetFor(event.getHistoricalX(0, h), event.getHistoricalY(0, h)),
                0f,
                event.getHistoricalEventTime(h),
            )
        }
        velocity.add(offsetFor(event.x, event.y), 0f, event.eventTime)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isGamepadEditMode()) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val continuing = latched
                latched = false
                isDragging = true
                initialTouchX = event.x
                initialTouchY = event.y
                if (!continuing) {
                    setTranslationX(0f)
                    setTranslationY(0f)
                    currentValue = 0
                }
                baseOffset = when (slideDirection) {
                    SlideDirection.DOWN, SlideDirection.UP -> translationY
                    SlideDirection.LEFT, SlideDirection.RIGHT -> translationX
                }
                wasAtMax = currentValue >= 255
                velocity.start(baseOffset, 0f, event.eventTime)
                isPressed = true
                invalidate()
                onValueChange?.invoke(currentValue)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return super.onTouchEvent(event)
                val offset = offsetFor(event.x, event.y)

                when (slideDirection) {
                    SlideDirection.DOWN, SlideDirection.UP ->
                        // 向下：手指向下（dy>0）→ 按钮向下（translationY>0）；
                        // 向上方向相反，两侧都夹取在行程范围内。
                        setTranslationY(offset)
                    SlideDirection.LEFT, SlideDirection.RIGHT ->
                        setTranslationX(offset)
                }

                val effectiveOffset = if (prediction) {
                    // history + EMA 速度：遍历批处理历史样本估计速度，外推下一帧行程。
                    addPredictionSamples(event)
                    offset + velocity.vx * velocity.frameMs()
                } else {
                    offset
                }
                val normalized = (abs(effectiveOffset) / travelPx).coerceAtMost(1f)
                val newValue = if (normalized < 0.004f) 0 else (normalized * 255).toInt().coerceIn(0, 255)

                invalidate()
                if (newValue != currentValue) {
                    val prevValue = currentValue
                    currentValue = newValue
                    onValueChange?.invoke(currentValue)
                    if (prevValue == 0 && currentValue > 0) {
                        onButtonDown?.invoke()
                    } else if (prevValue > 0 && currentValue == 0) {
                        onButtonUp?.invoke()
                    }
                }

                if (currentValue >= 255 && !wasAtMax) {
                    wasAtMax = true
                    onTriggerBottomVibrate()
                } else if (currentValue < 255) {
                    wasAtMax = false
                }

                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    velocity.reset()
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        currentValue > 0 && isAutoHoldEnabled()
                    ) {
                        // 自动保持：保留当前行程与位移，下一次拖动从该位置继续调整。
                        latched = true
                        isPressed = true
                        invalidate()
                        return true
                    }
                    isPressed = false
                    setTranslationX(0f)
                    setTranslationY(0f)
                    wasAtMax = false
                    if (currentValue > 0) {
                        onButtonUp?.invoke()
                    }
                    currentValue = 0
                    invalidate()
                    onValueChange?.invoke(0)
                    onTriggerBottomVibrate()
                    return true
                }
            }
        }
        return false
    }

    fun applyAppearanceColor(density: Float, btnColor: Int, outlineColor: Int, outlineWidth: Int, isPressed: Boolean) {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.RECTANGLE
        shape.cornerRadius = 12f * density
        if (isPressed) {
            val r = (btnColor ushr 16) and 0xFF
            val g = (btnColor ushr 8) and 0xFF
            val b = btnColor and 0xFF
            val pr = (r + (255 - r) * 0.3f).toInt().coerceIn(0, 255)
            val pg = (g + (255 - g) * 0.3f).toInt().coerceIn(0, 255)
            val pb = (b + (255 - b) * 0.3f).toInt().coerceIn(0, 255)
            shape.setColor((255 shl 24) or (pr shl 16) or (pg shl 8) or pb)
        } else {
            shape.setColor(btnColor)
        }
        if (outlineWidth > 0) {
            shape.setStroke(outlineWidth, outlineColor)
        }
        background = shape
    }

    fun updateFromButton(button: com.zyz4.gkme.model.ButtonPosition) {
        text = button.customText ?: text
        slideDirection = button.slideDirection
        travelDistance = button.travelDistance
        idleOpacity = button.idleOpacity
        activeOpacity = button.activeOpacity
        prediction = button.prediction
    }

    private companion object {
        private const val TAG = "LinearTriggerView"
    }
}