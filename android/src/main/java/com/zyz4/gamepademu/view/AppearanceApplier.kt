package com.zyz4.gamepademu.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.zyz4.gamepademu.R
import com.zyz4.gamepademu.enableAutoFitButtonText
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.DisplayMode
import com.zyz4.gamepademu.model.FillType

object AppearanceApplier {

    // Buttons whose foreground icon is resolved from the display mode via getIconDrawable.
    // btnTouchpad's icon IS its background and is never scaled; select/menu use a neutral
    // circle background and scale their icon as content.
    private val iconButtonIds = setOf("btnSelect", "btnMenu", "btnTouchpad")

    // Image buttons that draw their icon as content and scale it with the adaptive setting.
    private val adaptiveImageButtonIds = setOf(
        "btnHome", "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
    )

    // Buttons whose foreground icon is content and fills the button with the adaptive setting:
    // PS-mode ABXY, XBOX/SWITCH select & menu, and the integrated LS/RS triangle+letter.
    private val adaptiveForegroundButtonIds = setOf(
        "btnA", "btnB", "btnX", "btnY",
        "btnSelect", "btnMenu", "btnLS", "btnRS",
    )

    // Buttons that draw their content (text / foreground icon / image) inside the button and
    // always keep a min(w,h) x 10% padding.
    private val adaptiveContentButtonIds = setOf(
        "btnA", "btnB", "btnX", "btnY",
        "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
        "btnHome", "btnSelect", "btnMenu", "btnLS", "btnRS",
    )

    // When adaptive icon size is ON the text max is effectively unlimited (fills the button);
    // when OFF it is capped at the original 20f.
    private const val ADAPTIVE_TEXT_MAX_SP = 1024f
    private const val DEFAULT_TEXT_MAX_SP = 20f

    // Last auto-size max applied per TextView, so we don't reconfigure on every appearance change.
    private val appliedTextMax = HashMap<View, Float>()

    // Bitmap cache to avoid re-decoding files on every change
    private var cachedBitmapPath: String? = null
    private var cachedBitmap: Bitmap? = null

    private fun getBitmap(path: String?): Bitmap? {
        if (path == null) return null
        if (path == cachedBitmapPath && cachedBitmap != null) return cachedBitmap
        cachedBitmap = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
        cachedBitmapPath = if (cachedBitmap != null) path else null
        return cachedBitmap
    }

    private fun clearBitmapCache() {
        cachedBitmap = null
        cachedBitmapPath = null
    }

    fun applyToGamepadLayout(layout: GamepadLayout, settings: AppSettings) {
        if (settings.bgFillType == FillType.SOLID_COLOR) {
            layout.setBackgroundColor(settings.bgColor)
        } else if (settings.bgFillType == FillType.IMAGE && settings.bgImagePath != null) {
            val bmp = getBitmap(settings.bgImagePath)
            if (bmp != null) layout.background = fitCenterDrawable(bmp)
        }

        layout.setFollowAreaAppearance(settings.joyTriggerOutlineColor, settings.joyTriggerOutlineWidth)
        layout.setTouchpadAreaAppearance(settings.tpTriggerOutlineColor, settings.tpTriggerOutlineWidth)

        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            val tag = child.tag as? String ?: continue
            val baseId = tag.substringBefore("_")

            when {
                child is JoystickView -> applyToJoystick(child, settings)
                baseId == "touchpad" -> applyToTouchpad(child, settings)
                else -> applyToButton(child, settings)
            }
        }
    }

    private fun applyToButton(view: View, settings: AppSettings) {
        val tag = view.tag as? String ?: return
        val baseId = tag.substringBefore("_")
        val isCircle = isCircleButton(tag)
        val density = view.resources.displayMetrics.density
        val adaptive = settings.adaptiveIconSize

        val foregroundIcon = when {
            baseId == "btnLS" || baseId == "btnRS" ->
                letterIconDrawable(view, if (baseId == "btnLS") "L" else "R")
            view !is ImageButton && baseId in iconButtonIds -> getIconDrawable(view, settings)
            else -> null
        }
        if (foregroundIcon != null) {
            view.foreground = foregroundIcon
            view.foregroundGravity = getIconGravity(baseId)
        } else if (baseId in iconButtonIds || baseId == "btnLS" || baseId == "btnRS") {
            view.foreground = null
        }

        // ── Adaptive icon / text fill ──
        if (view is ImageButton && baseId in adaptiveImageButtonIds) {
            view.scaleType = if (adaptive) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_INSIDE
        } else if (view is Button && baseId in adaptiveForegroundButtonIds) {
            // Content icons (PS ABXY, XBOX/SWITCH select & menu, LS/RS triangle+letter) are plain
            // foreground drawables: FILL scales them to the button, CENTER keeps their intrinsic
            // size. Vector drawables scale to their bounds, so no extra wrapping is needed.
            view.foregroundGravity = if (adaptive) Gravity.FILL else Gravity.CENTER
        }

        // Text keeps auto-fit in both states; the toggle only changes the max size:
        // OFF caps at 20f (original), ON lets it grow to fill the button.
        if (view is Button && !view.text.isNullOrEmpty()) {
            val maxSp = if (adaptive) ADAPTIVE_TEXT_MAX_SP else DEFAULT_TEXT_MAX_SP
            if (appliedTextMax[view] != maxSp) {
                view.enableAutoFitButtonText(maxSp, minSizeSp = if (adaptive) 4f else maxSp * 0.25f)
                appliedTextMax[view] = maxSp
            }
        }

        // Adaptive padding: min(w,h) x 10% for view-drawn content. Applied in both adaptive
        // states (the toggle only controls whether content fills the button). Applied
        // synchronously so the appearance preview captures the correct state immediately
        // (onLayout re-applies it on size changes).
        if (isAdaptiveContentButton(tag, view)) {
            val pad = (minOf(view.width, view.height) * 0.1f).toInt()
            if (view.paddingLeft != pad || view.paddingTop != pad) {
                view.setPadding(pad, pad, pad, pad)
            }
        }

        if (settings.btnFillType == FillType.SOLID_COLOR) {
            applyToButtonWithColor(view, settings, isCircle, density)
        } else if (settings.btnFillType == FillType.IMAGE) {
            applyToButtonWithImage(view, settings, isCircle, density)
        }
    }

    private fun applyToButtonWithColor(view: View, settings: AppSettings, isCircle: Boolean, density: Float) {
        val pressedShape = GradientDrawable()
        pressedShape.shape = if (isCircle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!isCircle) pressedShape.cornerRadius = 12f * density
        pressedShape.setColor(highlightColor(settings.btnColor, 0.3f))
        if (settings.btnOutlineWidth > 0) {
            pressedShape.setStroke(settings.btnOutlineWidth, highlightColor(settings.btnOutlineColor, 0.3f))
        }

        val normalShape = GradientDrawable()
        normalShape.shape = if (isCircle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!isCircle) normalShape.cornerRadius = 12f * density
        normalShape.setColor(settings.btnColor)
        if (settings.btnOutlineWidth > 0) {
            normalShape.setStroke(settings.btnOutlineWidth, settings.btnOutlineColor)
        }

        val sld = StateListDrawable()
        sld.addState(intArrayOf(android.R.attr.state_pressed), pressedShape)
        sld.addState(intArrayOf(), normalShape)
        view.background = sld
    }

    private fun applyToButtonWithImage(view: View, settings: AppSettings, isCircle: Boolean, density: Float) {
        val bmp = getBitmap(settings.btnImagePath)
        if (bmp == null) {
            applyToButtonWithColor(view, settings, isCircle, density)
            return
        }
        val cornerRadius = 12f * density
        val outlineWidth = settings.btnOutlineWidth
        val outlineColor = settings.btnOutlineColor
        val hasOutline = outlineWidth > 0

        // Normal state: center-crop bitmap clipped to shape
        val normal = shapeImageDrawable(bmp, isCircle, cornerRadius, if (hasOutline) outlineWidth else 0, outlineColor)

        // Pressed state: same + semi-transparent white overlay
        val pressedBmp = getBitmap(settings.btnImagePath) ?: bmp
        val pressed = shapeImageDrawable(pressedBmp, isCircle, cornerRadius, if (hasOutline) outlineWidth else 0,
            if (hasOutline) highlightColor(outlineColor, 0.3f) else 0)
        val overlay = GradientDrawable()
        overlay.setColor(Color.argb(60, 255, 255, 255))
        overlay.shape = if (isCircle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!isCircle) overlay.cornerRadius = cornerRadius
        val pressedLayer = LayerDrawable(arrayOf(pressed, overlay))

        val sld = StateListDrawable()
        sld.addState(intArrayOf(android.R.attr.state_pressed), pressedLayer)
        sld.addState(intArrayOf(), normal)
        view.background = sld
    }

    private fun applyToJoystick(joy: JoystickView, settings: AppSettings) {
        joy.appearanceBaseColor = settings.joyBaseColor
        joy.appearanceBaseBitmap = if (settings.joyBaseFillType == FillType.IMAGE) getBitmap(settings.joyBaseImagePath) else null
        joy.appearanceBaseOutlineColor = settings.joyBaseOutlineColor
        joy.appearanceBaseOutlineWidth = settings.joyBaseOutlineWidth.toFloat()
        joy.appearanceCapColor = settings.joyCapColor
        joy.appearanceCapBitmap = if (settings.joyCapFillType == FillType.IMAGE) getBitmap(settings.joyCapImagePath) else null
        joy.appearanceCapOutlineColor = settings.joyCapOutlineColor
        joy.appearanceCapOutlineWidth = settings.joyCapOutlineWidth.toFloat()
        joy.invalidate()
    }

    private fun applyToTouchpad(view: View, settings: AppSettings) {
        val density = view.resources.displayMetrics.density

        if (settings.tpFillType == FillType.SOLID_COLOR) {
            val pressedShape = GradientDrawable()
            pressedShape.shape = GradientDrawable.RECTANGLE
            pressedShape.cornerRadius = 10f * density
            pressedShape.setColor(highlightColor(settings.tpColor, 0.3f))
            if (settings.tpOutlineWidth > 0) {
                pressedShape.setStroke(settings.tpOutlineWidth, highlightColor(settings.tpOutlineColor, 0.3f))
            }

            val normalShape = GradientDrawable()
            normalShape.shape = GradientDrawable.RECTANGLE
            normalShape.cornerRadius = 10f * density
            normalShape.setColor(settings.tpColor)
            if (settings.tpOutlineWidth > 0) {
                normalShape.setStroke(settings.tpOutlineWidth, settings.tpOutlineColor)
            }

            val sld = StateListDrawable()
            sld.addState(intArrayOf(android.R.attr.state_pressed), pressedShape)
            sld.addState(intArrayOf(), normalShape)
            view.background = sld
        } else if (settings.tpImagePath != null) {
            val bmp = getBitmap(settings.tpImagePath)
            if (bmp != null) {
                val cornerRadius = 10f * density
                val outlineWidth = settings.tpOutlineWidth
                val outlineColor = settings.tpOutlineColor
                val normal = shapeImageDrawable(bmp, false, cornerRadius, outlineWidth, outlineColor)
                val pressed = shapeImageDrawable(bmp, false, cornerRadius, outlineWidth,
                    if (outlineWidth > 0) highlightColor(outlineColor, 0.3f) else 0)
                val overlay = GradientDrawable()
                overlay.setColor(Color.argb(60, 255, 255, 255))
                overlay.shape = GradientDrawable.RECTANGLE
                overlay.cornerRadius = cornerRadius
                val pressedLayer = LayerDrawable(arrayOf(pressed, overlay))
                val sld = StateListDrawable()
                sld.addState(intArrayOf(android.R.attr.state_pressed), pressedLayer)
                sld.addState(intArrayOf(), normal)
                view.background = sld
                return
            }
            val fallbackShape = GradientDrawable()
            fallbackShape.shape = GradientDrawable.RECTANGLE
            fallbackShape.cornerRadius = 10f * density
            fallbackShape.setColor(settings.tpColor)
            if (settings.tpOutlineWidth > 0) {
                fallbackShape.setStroke(settings.tpOutlineWidth, settings.tpOutlineColor)
            }
            view.background = fallbackShape
        }
    }

    private fun shapeImageDrawable(bmp: Bitmap, isCircle: Boolean, cornerRadius: Float,
                                   outlineWidth: Int = 0, outlineColor: Int = 0): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0 || h <= 0) return

                ShapeImageUtil.applyCenterCrop(paint, bmp, w, h)
                paint.style = Paint.Style.FILL
                if (isCircle) {
                    val cx = w / 2f; val cy = h / 2f; val r = minOf(cx, cy)
                    canvas.drawCircle(cx, cy, r, paint)
                } else {
                    canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, paint)
                }
                paint.shader = null

                if (outlineWidth > 0) {
                    paint.color = outlineColor
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = outlineWidth.toFloat()
                    val hs = outlineWidth / 2f
                    if (isCircle) {
                        val cx = w / 2f; val cy = h / 2f; val r = minOf(cx, cy) - hs
                        canvas.drawCircle(cx, cy, r, paint)
                    } else {
                        canvas.drawRoundRect(hs, hs, w - hs, h - hs, cornerRadius, cornerRadius, paint)
                    }
                    paint.style = Paint.Style.FILL
                }
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun getIconDrawable(view: View, settings: AppSettings): Drawable? {
        val baseId = (view.tag as? String)?.substringBefore("_") ?: return null
        val mode = settings.displayMode
        val resId = when (baseId) {
            "btnSelect" -> when (mode) {
                DisplayMode.XBOX -> R.drawable.ic_view
                DisplayMode.SWITCH -> R.drawable.ic_minus
                else -> null
            }
            "btnMenu" -> when (mode) {
                DisplayMode.XBOX -> R.drawable.ic_menu
                DisplayMode.SWITCH -> R.drawable.ic_plus
                else -> null
            }
            "btnTouchpad" -> R.drawable.ic_touchpad_grid
            else -> null
        }
        return if (resId != null) view.context.getDrawable(resId)?.mutate() else null
    }

    private fun getIconGravity(baseId: String): Int = Gravity.CENTER

    /** Integrated LS/RS content: the triangle icon on top with the L/R letter below it, drawn
     *  as a single unit so they scale together. */
    private fun letterIconDrawable(view: View, letter: String): Drawable {
        val resId = if (letter == "L") R.drawable.ic_ls else R.drawable.ic_rs
        val triangle = view.context.getDrawable(resId)?.mutate()
        return LetterIconDrawable(letter, triangle)
    }

    private fun highlightColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun isCircleButton(tag: String): Boolean {
        val baseId = tag.substringBefore("_")
        return baseId in listOf(
            "btnA", "btnB", "btnX", "btnY",
            "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
            "btnHome", "btnSelect", "btnMenu", "btnLS", "btnRS", "btnTouchpad",
        ) || baseId.startsWith("btnCustomCircle")
    }

    private fun fitCenterDrawable(bmp: Bitmap): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0 || h <= 0) return
                ShapeImageUtil.applyFitCenter(paint, bmp, w, h)
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }
    fun isAdaptiveContentButton(id: String, child: View): Boolean {
        val base = id.substringBefore("_")
        val isAdaptiveImage = child is ImageButton && base in adaptiveImageButtonIds
        val isContentButton = child is Button &&
            (!child.text.isNullOrEmpty() || base in adaptiveForegroundButtonIds)
        if (!(isAdaptiveImage || isContentButton)) return false
        return base in adaptiveContentButtonIds || base.startsWith("btnCustomCircle")
    }
}

// Draws a triangle icon in the top band and the L/R letter centered in the button as one
// drawable. The letter is sized so its top edge stays below the triangle, so the two never
// overlap regardless of the filled/intrinsic size.
@Suppress("DEPRECATION")
private class LetterIconDrawable(
    private val letter: String,
    private val icon: Drawable?,
) : Drawable() {
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun getIntrinsicWidth(): Int = icon?.intrinsicWidth ?: 24
    override fun getIntrinsicHeight(): Int = icon?.intrinsicHeight ?: 24

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return

        // Triangle at the top, scaled to fill the top band.
        icon?.let { ic ->
            val size = minOf(h * 0.28f, w * 0.88f)
            val left = bounds.exactCenterX() - size / 2f
            val top = bounds.top + h * 0.03f
            ic.bounds = Rect(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            ic.draw(canvas)
        }

        // Letter centered in the button, sized to stay below the triangle.
        letterPaint.textSize = h * 0.28f
        val baseline = bounds.exactCenterY() - (letterPaint.ascent() + letterPaint.descent()) / 2f
        canvas.drawText(letter, bounds.exactCenterX(), baseline, letterPaint)
    }

    override fun setAlpha(alpha: Int) { letterPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { letterPaint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
