package com.zyz4.gamepademu.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.Button
import com.zyz4.gamepademu.model.SlideDirection
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
    var idleTransparency: Int = 0
        set(value) {
            field = value
        }
    var activeTransparency: Int = 0
        set(value) {
            field = value
        }

    var onValueChange: ((value: Int) -> Unit)? = null
    var onTriggerBottomVibrate: () -> Unit = {}

    private var travelPx = 0f

    private var isDragging = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var currentValue = 0
    private var wasAtMax = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        travelPx = (min(w.toFloat(), h.toFloat()) * travelDistance / 40f).coerceAtLeast(1f)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        // Apply transparency for idle/active states
        val targetAlpha = if (isDragging) {
            (255 - activeTransparency).coerceIn(0, 255)
        } else {
            (255 - idleTransparency).coerceIn(0, 255)
        }
        val currentAlpha = alpha.toInt()
        if (currentAlpha != targetAlpha) {
            alpha = targetAlpha.toFloat()
        }

        super.onDraw(canvas)
    }

    private fun isGamepadEditMode(): Boolean {
        val p = parent
        if (p != null && p is android.view.ViewGroup) {
            var current: android.view.View? = this
            while (current != null) {
                val parent = current.parent
                if (parent is com.zyz4.gamepademu.view.GamepadLayout) {
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isGamepadEditMode()) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                initialTouchX = event.x
                initialTouchY = event.y
                setTranslationX(0f)
                setTranslationY(0f)
                currentValue = 1
                wasAtMax = false
                isPressed = true
                invalidate()
                onValueChange?.invoke(currentValue)
                onTriggerBottomVibrate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return super.onTouchEvent(event)
                val dx = event.x - initialTouchX
                val dy = event.y - initialTouchY

                when (slideDirection) {
                    SlideDirection.DOWN -> setTranslationY(dy.coerceIn(-travelPx, travelPx))
                    SlideDirection.UP -> setTranslationY((-dy).coerceIn(-travelPx, travelPx))
                    SlideDirection.LEFT -> setTranslationX((-dx).coerceIn(-travelPx, travelPx))
                    SlideDirection.RIGHT -> setTranslationX(dx.coerceIn(-travelPx, travelPx))
                }

                val absOffset = when (slideDirection) {
                    SlideDirection.DOWN, SlideDirection.UP -> abs(translationY)
                    SlideDirection.LEFT, SlideDirection.RIGHT -> abs(translationX)
                }
                val normalized = absOffset / travelPx
                currentValue = if (normalized < 0.004f) 0 else (normalized * 255).toInt().coerceIn(0, 255)

                invalidate()
                onValueChange?.invoke(currentValue)

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
                    isPressed = false
                    setTranslationX(0f)
                    setTranslationY(0f)
                    currentValue = 0
                    wasAtMax = false
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

    fun updateFromButton(button: com.zyz4.gamepademu.model.ButtonPosition) {
        text = button.customText ?: text
        slideDirection = button.slideDirection
        travelDistance = button.travelDistance
        idleTransparency = button.idleTransparency
        activeTransparency = button.activeTransparency
    }
}