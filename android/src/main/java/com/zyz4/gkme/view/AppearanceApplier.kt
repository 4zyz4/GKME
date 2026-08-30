package com.zyz4.gkme.view

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
import com.zyz4.gkme.R
import com.zyz4.gkme.applyContentSizeCap
import com.zyz4.gkme.model.AppSettings
import com.zyz4.gkme.model.DisplayMode
import com.zyz4.gkme.model.FillType
import kotlin.math.sqrt

object AppearanceApplier {

    // Buttons whose foreground icon is resolved from the display mode via getIconDrawable.
    // select/menu use a neutral circle background and scale their icon as content; btnTouchpad
    // scales its (rectangular) grid icon as content too.
    private val iconButtonIds = setOf("btnSelect", "btnMenu", "btnTouchpad")

    // Image buttons that draw their icon as content and scale it with the adaptive setting.
    private val adaptiveImageButtonIds = setOf(
        "btnHome", "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight", "btnMic",
    )

    // Buttons whose foreground icon is content and fills the button with the adaptive setting:
    // PS-mode ABXY, XBOX/SWITCH select & menu, the touchpad grid, and the integrated LS/RS
    // triangle+letter.
    private val adaptiveForegroundButtonIds = setOf(
        "btnA", "btnB", "btnX", "btnY",
        "btnSelect", "btnMenu", "btnTouchpad", "btnLS", "btnRS",
    )

    // PS-mode ABXY: their foreground icons are set directly by updateButtonLabels (unlike
    // select/menu which are resolved from the mode here), so those are reused across passes.
    private val psIconButtonIds = setOf("btnA", "btnB", "btnX", "btnY")

    // Buttons that draw their content (text / foreground icon / image) inside the button and
    // always keep a min(w,h) x 10% padding.
    private val adaptiveContentButtonIds = setOf(
        "btnA", "btnB", "btnX", "btnY",
        "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
        "btnHome", "btnMic", "btnSelect", "btnMenu", "btnTouchpad", "btnLS", "btnRS",
    )

    // Last auto-size cap applied per TextView (px), so we don't reconfigure on every pass.
    private val appliedTextCap = HashMap<View, Int>()

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
        layout.setDpadPadTriggerAreaAppearance(settings.dpadPadTriggerOutlineColor, settings.dpadPadTriggerOutlineWidth)
        layout.setLinearTriggerBoxAppearance(settings.linearTriggerBoxOutlineColor, settings.linearTriggerBoxOutlineWidth)

        val buttonMap = layout.currentButtons.associateBy { it.id }.toMap()

        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            val tag = child.tag as? String ?: continue
            val baseId = tag.substringBefore("_")

            when {
                child is JoystickView -> applyToJoystick(child, settings)
                child is CustomKeypadView -> applyToKeypad(child, settings)
                child is DpadPadView -> applyToDpadPad(child, settings)
                child is com.zyz4.gkme.view.LinearTriggerView -> {
                    val pos = buttonMap[tag] ?: continue
                    val density = child.resources.displayMetrics.density
                    child.updateFromButton(pos)
                    applyToButtonWithColor(child, settings, false, density)
                }
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
        val maxPx = contentCapPx(view, settings)

        // Foreground content icons (PS ABXY, XBOX/SWITCH select & menu, LS/RS triangle+letter).
        // PS ABXY icons are set by updateButtonLabels, so reuse whatever is already on the view
        // (unwrap a previous cap so we never stack wrappers). select/menu are resolved from the
        // current mode via getIconDrawable instead, so switching to PS clears their foreground.
        val foregroundIcon = when {
            baseId == "btnLS" || baseId == "btnRS" ->
                letterIconDrawable(view, if (baseId == "btnLS") "L" else "R", maxPx?.toFloat())
            baseId in psIconButtonIds && settings.displayMode == DisplayMode.PLAYSTATION &&
                view !is ImageButton && view.foreground != null ->
                (view.foreground as? CappedContentDrawable)?.inner ?: view.foreground
            view !is ImageButton && baseId in iconButtonIds -> getIconDrawable(view, settings)
            else -> null
        }
        if (foregroundIcon != null) {
            view.foregroundGravity = if (baseId in adaptiveForegroundButtonIds) Gravity.FILL else Gravity.CENTER
            // Foreground FILL draws over the whole view (padding ignored), so inset the content
            // by the button's own 10% padding to keep it consistent with text / image content.
            val insetProvider: (() -> Float)? =
                if (baseId in adaptiveForegroundButtonIds) ({ view.paddingLeft.toFloat() }) else null
            view.foreground = when {
                baseId == "btnLS" || baseId == "btnRS" -> foregroundIcon
                // Keep the icon's intrinsic aspect (e.g. the rectangular touchpad-grid icon) so a
                // non-square icon is never stretched into a square.
                maxPx == null && insetProvider == null -> foregroundIcon
                else -> CappedContentDrawable(
                    foregroundIcon,
                    maxPx?.toFloat() ?: Float.MAX_VALUE,
                    fitAspect = true,
                    insetProvider = insetProvider,
                )
            }
        } else if (baseId in iconButtonIds || baseId == "btnLS" || baseId == "btnRS") {
            view.foreground = null
        }

        // ── Icon / text max size ──
        // Image buttons (home, dpad, mic): ImageView draws the image via intrinsic bounds + a fit
        // matrix, so a wrapper that computes from its bounds can never bind the cap (the bounds
        // are the intrinsic 24dp size, not the button). Draw the icon as the view foreground
        // instead, which receives the real view bounds, and let the wrapper cap at draw time.
        // "Unlimited" uses CappedContentDrawable with Float.MAX_VALUE so the icon fills the
        // padded button (same visual behavior as the capped case, just without a max size).
        if (view is ImageButton && baseId in adaptiveImageButtonIds) {
            val raw = (view.foreground as? CappedContentDrawable)?.inner
                ?: (view.drawable as? CappedContentDrawable)?.inner
                ?: view.drawable
            if (raw != null) {
                view.setImageDrawable(null)
                view.foregroundGravity = Gravity.FILL
                // Use Float.MAX_VALUE for "unlimited" so the icon fills the padded button area
                // (same visual behavior as the capped case, just without a hard max).
                val effectiveMax = maxPx?.toFloat() ?: Float.MAX_VALUE
                view.foreground = CappedContentDrawable(
                    raw, effectiveMax, fitAspect = true,
                    insetProvider = { view.paddingLeft.toFloat() },
                )
            }
        }

        // Text keeps auto-fit; the cap is the max size in px (null = unlimited: auto-fit already
        // stays within the button, so a huge cap never binds). Skipped until the button is
        // measured (GamepadLayout.onLayout applies the cap from the real size); a 0-size button
        // would yield a degenerate auto-size config.
        if (view is Button && !view.text.isNullOrEmpty() && view.width > 0 && view.height > 0) {
            applyContentTextCap(view, maxPx ?: UNLIMITED_TEXT_CAP_PX)
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
        joy.labelMaxSizePx = contentCapPx(joy, settings)?.toFloat()
        joy.invalidate()
    }

    private fun applyToDpadPad(pad: DpadPadView, settings: AppSettings) {
        pad.appearanceFillType = settings.dpadPadFillType
        pad.appearanceImagePath = settings.dpadPadImagePath
        pad.appearanceColor = settings.dpadPadColor
        pad.appearanceBorderColor = settings.dpadPadOutlineColor
        pad.appearanceBorderWidth = settings.dpadPadOutlineWidth.toFloat()
        pad.arrowMaxSizePx = contentCapPx(pad, settings)?.toFloat()
        pad.invalidate()
    }

    private fun applyToKeypad(pad: CustomKeypadView, settings: AppSettings) {
        pad.padFillType = settings.dpadPadFillType
        pad.padImagePath = settings.dpadPadImagePath
        pad.padColor = settings.dpadPadColor
        pad.padBorderColor = settings.dpadPadOutlineColor
        pad.padBorderWidth = settings.dpadPadOutlineWidth.toFloat()
        pad.invalidate()
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

    internal fun shapeImageDrawable(bmp: Bitmap, isCircle: Boolean, cornerRadius: Float,
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

    // Cap for the auto-fit text when the max size is "unlimited": so large that auto-fit
    // always stays within the button.
    const val UNLIMITED_TEXT_CAP_PX = 8192

    // Max content size in px from the sp value in settings.iconMaxSize (0..99); null = unlimited.
    // A "sp" value is scaled by the display's scaled density, matching the old textSize=20f unit.
    fun contentCapPx(view: View, settings: AppSettings?): Int? {
        val sp = settings?.iconMaxSize ?: return null
        if (sp >= 100) return null
        val scaled = view.resources.displayMetrics.scaledDensity
        return (sp.coerceIn(0, 99) * scaled).toInt().coerceAtLeast(1)
    }

    // Re-cap auto-fit text. Callable from GamepadLayout.onLayout so the cap tracks the
    // measured button size (the initial appearance pass may run before layout).
    fun applyContentTextCap(view: Button, capPx: Int) {
        if (appliedTextCap[view] != capPx) {
            view.applyContentSizeCap(capPx)
            appliedTextCap[view] = capPx
        }
    }

    /** Integrated LS/RS content: the triangle icon on top with the L/R letter below it, drawn
     *  as a single unit so they scale together. maxSizePx caps the whole unit (null = unlimited). */
    private fun letterIconDrawable(view: View, letter: String, maxSizePx: Float?): Drawable {
        val resId = if (letter == "L") R.drawable.ic_ls else R.drawable.ic_rs
        val triangle = view.context.getDrawable(resId)?.mutate()
        return LetterIconDrawable(letter, triangle, maxSizePx)
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
            "btnHome", "btnSelect", "btnMenu", "btnLS", "btnRS", "btnTouchpad", "btnMic",
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
    private val maxSizePx: Float?,
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
        val max = maxSizePx ?: Float.MAX_VALUE

        // Triangle at the top, the letter below as the main label. Both grow with the max size
        // and fill the button when there is no cap (instead of staying at a small fixed fraction).
        icon?.let { ic ->
            val size = minOf(w * 0.5f, h * 0.24f, max)
            val left = bounds.exactCenterX() - size / 2f
            val top = bounds.top + h * 0.05f
            ic.bounds = Rect(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            ic.draw(canvas)
        }

        letterPaint.textSize = minOf(w * 0.55f, h * 0.45f, max)
        // Center the glyph's bounding box on the button's middle (the baseline alone isn't the
        // visual center), so the letter scales around its center and never looks bottom-anchored.
        val baseline = bounds.exactCenterY() - (letterPaint.ascent() + letterPaint.descent()) / 2f
        canvas.drawText(letter, bounds.exactCenterX(), baseline, letterPaint)
    }

    override fun setAlpha(alpha: Int) { letterPaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { letterPaint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// Draws an inner drawable centered within its bounds, scaled to at most maxSizePx (and never
// larger than the padded bounds). With fitAspect the inner keeps its intrinsic aspect (as the
// image pipeline would); otherwise it fills a square (FILL behavior). insetProvider supplies
// the button's content padding (read at draw time) so the content keeps the same 10% margin
// that text / image content has, even though the foreground ignores view padding.
private const val SQRT_2 = 1.4142135623730951f

private class CappedContentDrawable(
    val inner: Drawable,
    private val maxSizePx: Float,
    private val fitAspect: Boolean,
    private val insetProvider: (() -> Float)? = null,
) : Drawable() {
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return
        val inset = insetProvider?.invoke() ?: 0f
        val paddedW = (w - inset * 2f).coerceAtLeast(1f)
        val paddedH = (h - inset * 2f).coerceAtLeast(1f)
        val maxDim = minOf(paddedW, paddedH, maxSizePx.coerceAtLeast(1f))

        val rect = if (fitAspect) {
            val iw = inner.intrinsicWidth.coerceAtLeast(1).toFloat()
            val ih = inner.intrinsicHeight.coerceAtLeast(1).toFloat()
            // maxDim is the side of a square cap. Scale non-square icons so their diagonal
            // matches that square cap's diagonal (same visual size as square icons), still
            // bounded by the padded button so "unlimited" fills like FIT_CENTER.
            val targetScale = maxDim * SQRT_2 / sqrt(iw * iw + ih * ih)
            val scale = minOf(targetScale, paddedW / iw, paddedH / ih)
            val dw = iw * scale
            val dh = ih * scale
            Rect(
                (bounds.exactCenterX() - dw / 2f).toInt(),
                (bounds.exactCenterY() - dh / 2f).toInt(),
                (bounds.exactCenterX() + dw / 2f).toInt(),
                (bounds.exactCenterY() + dh / 2f).toInt(),
            )
        } else {
            Rect(
                (bounds.exactCenterX() - maxDim / 2f).toInt(),
                (bounds.exactCenterY() - maxDim / 2f).toInt(),
                (bounds.exactCenterX() + maxDim / 2f).toInt(),
                (bounds.exactCenterY() + maxDim / 2f).toInt(),
            )
        }
        inner.bounds = rect
        inner.draw(canvas)
    }

    override fun getIntrinsicWidth(): Int = inner.intrinsicWidth
    override fun getIntrinsicHeight(): Int = inner.intrinsicHeight
    override fun setAlpha(alpha: Int) { inner.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { inner.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = inner.opacity
}
