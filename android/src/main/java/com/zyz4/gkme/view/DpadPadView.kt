package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.zyz4.gkme.R
import com.zyz4.gkme.model.FillType
import com.zyz4.gkme.model.GamepadState

/** Integrated D-pad control: a circle divided into 9 regions (5 squares + 4 corner segments).
 *  Touching the 8 surrounding regions reports D-pad values (diagonals report combined bits);
 *  sliding across regions switches the reported value and fires the "button press" haptic via
 *  the callback. The centre region does nothing.
 *
 *  Touch consumption mirrors the joystick: a pointer stays captured for the whole gesture even
 *  when it slides outside the circle, in which case the direction nearest to the finger is
 *  reported. */
class DpadPadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Called when the reported D-pad value changes. [released] holds the bits to unset and
     *  [pressed] the bits to set (GamepadState.DPAD_UP/DOWN/LEFT/RIGHT, combinable). */
    var onDpadChange: ((released: Int, pressed: Int) -> Unit)? = null

    /** Called when the touch lifts while a direction was active (a "key release" gesture). */
    var onLift: (() -> Unit)? = null

    var onGyroActivateDown: (() -> Unit)? = null
    var onGyroActivateUp: (() -> Unit)? = null

    // ── Appearance (per-control, from ButtonPosition) ──
    var appearanceFillType: FillType = FillType.SOLID_COLOR
    var appearanceColor: Int = 0xFF1A1A1A.toInt()
    var appearanceImagePath: String? = null
        set(value) {
            if (field != value) {
                field = value
                appearanceBitmap = value?.let { path ->
                    try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
                }
                invalidate()
            }
        }
    var appearanceBorderColor: Int = 0xFF666666.toInt()
    var appearanceBorderWidth: Float = 4f

    /** Adaptive arrow-size cap in px (from the global icon-size setting); null = sized relative to the region. */
    var arrowMaxSizePx: Float? = null

    var idleTransparency: Int = 0
    var activeTransparency: Int = 0

    private var appearanceBitmap: Bitmap? = null
    private var activeBits = 0
    private var isTouching = false

    /** When true, the effective center tracks the touch position (follow-area mode) */
    var forceFollowFinger: Boolean = false

    private var centerX = 0f
    private var centerY = 0f
    private var effectiveCenterX = 0f
    private var effectiveCenterY = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shapePath = Path()

    private var side = 0f
    private var originX = 0f
    private var originY = 0f
    private var third = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        side = minOf(w, h).toFloat()
        originX = (w - side) / 2f
        originY = (h - side) / 2f
        third = side / 3f
        centerX = originX + side / 2f
        centerY = originY + side / 2f
        effectiveCenterX = centerX
        effectiveCenterY = centerY
        rebuildShapePath()
    }

    private fun rebuildShapePath() {
        shapePath.reset()
        shapePath.addCircle(originX + side / 2f, originY + side / 2f, side / 2f, Path.Direction.CW)
    }

    private fun highlightColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (side <= 0f) return

        val dx = effectiveCenterX - centerX
        val dy = effectiveCenterY - centerY

        canvas.save()
        if (dx != 0f || dy != 0f) {
            canvas.translate(dx, dy)
        }

        val a = third

        // Circle fill
        if (appearanceFillType == FillType.IMAGE && appearanceBitmap != null) {
            ShapeImageUtil.applyCenterCrop(fillPaint, appearanceBitmap!!, side, side)
        } else {
            fillPaint.shader = null
            fillPaint.color = appearanceColor
        }
        canvas.drawPath(shapePath, fillPaint)
        fillPaint.shader = null

        // Highlight the currently triggered region. Every direction uses the matching grid
        // cell (clipped to the circle), so the highlight matches the UI shape exactly.
        if (activeBits != 0) {
            fillPaint.color = highlightColor(appearanceColor, 0.3f)
            canvas.save()
            canvas.clipPath(shapePath)
            canvas.drawPath(regionPath(activeBits), fillPaint)
            canvas.restore()
        }

        // Directional arrows (up / down / left / right)
        drawArrow(canvas, R.drawable.ic_arrow_up, a, 0f, a, a)
        drawArrow(canvas, R.drawable.ic_arrow_down, a, 2f * a, a, a)
        drawArrow(canvas, R.drawable.ic_arrow_left, 0f, a, a, a)
        drawArrow(canvas, R.drawable.ic_arrow_right, 2f * a, a, a, a)

        // Border + separators (same color & width)
        if (appearanceBorderWidth > 0f) {
            borderPaint.color = appearanceBorderColor
            borderPaint.strokeWidth = appearanceBorderWidth
            canvas.drawPath(shapePath, borderPaint)
            canvas.save()
            canvas.clipPath(shapePath)
            val hw = appearanceBorderWidth / 2f
            canvas.drawLine(originX + a, originY, originX + a, originY + side, borderPaint)
            canvas.drawLine(originX + 2f * a, originY, originX + 2f * a, originY + side, borderPaint)
            canvas.drawLine(originX, originY + a, originX + side, originY + a, borderPaint)
            canvas.drawLine(originX, originY + 2f * a, originX + side, originY + 2f * a, borderPaint)
            canvas.restore()
        }

        canvas.restore()
    }

    private fun drawArrow(canvas: Canvas, resId: Int, left: Float, top: Float, w: Float, h: Float) {
        val drawable = context.getDrawable(resId)?.mutate() ?: return
        val size = minOf(w * 0.55f, h * 0.55f, arrowMaxSizePx ?: Float.MAX_VALUE)
        if (size <= 0f) return
        val cx = originX + left + w / 2f
        val cy = originY + top + h / 2f
        drawable.bounds = Rect((cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
            (cx + size / 2f).toInt(), (cy + size / 2f).toInt())
        drawable.draw(canvas)
    }

    /** Path of the region (grid cell) corresponding to the given D-pad bit combination. */
    private fun regionPath(bits: Int): Path {
        val p = Path()
        val a = third
        val rect = RectF()
        when (bits) {
            GamepadState.DPAD_UP -> rect.set(originX + a, originY, originX + 2f * a, originY + a)
            GamepadState.DPAD_DOWN -> rect.set(originX + a, originY + 2f * a, originX + 2f * a, originY + 3f * a)
            GamepadState.DPAD_LEFT -> rect.set(originX, originY + a, originX + a, originY + 2f * a)
            GamepadState.DPAD_RIGHT -> rect.set(originX + 2f * a, originY + a, originX + 3f * a, originY + 2f * a)
            (GamepadState.DPAD_UP or GamepadState.DPAD_LEFT) ->
                rect.set(originX, originY, originX + a, originY + a)
            (GamepadState.DPAD_UP or GamepadState.DPAD_RIGHT) ->
                rect.set(originX + 2f * a, originY, originX + 3f * a, originY + a)
            (GamepadState.DPAD_DOWN or GamepadState.DPAD_LEFT) ->
                rect.set(originX, originY + 2f * a, originX + a, originY + 3f * a)
            (GamepadState.DPAD_DOWN or GamepadState.DPAD_RIGHT) ->
                rect.set(originX + 2f * a, originY + 2f * a, originX + 3f * a, originY + 3f * a)
            else -> p.reset()
        }
        if (bits != 0) {
            p.addRect(rect, Path.Direction.CW)
        }
        return p
    }

    /** D-pad bits for the region under (x, y) in view coordinates; 0 = centre. Points outside the
     *  circle map to whichever of the 8 surrounding direction cells is closest to the finger. */
    private fun regionAt(x: Float, y: Float): Int {
        val lx = x - originX
        val ly = y - originY
        val a = third
        val r = side / 2f
        val eOffX = effectiveCenterX - originX
        val eOffY = effectiveCenterY - originY
        val dx = lx - eOffX
        val dy = ly - eOffY
        if (dx * dx + dy * dy <= r * r && lx in 0f..side && ly in 0f..side) {
            val col = when { lx < eOffX - r + a -> 0; lx < eOffX - r + 2f * a -> 1; else -> 2 }
            val row = when { ly < eOffY - r + a -> 0; ly < eOffY - r + 2f * a -> 1; else -> 2 }
            return bitsAt(col, row)
        }
        val col = when { lx < eOffX - r + a -> 0; lx < eOffX - r + 2f * a -> 1; else -> 2 }
        val row = when { ly < eOffY - r + a -> 0; ly < eOffY - r + 2f * a -> 1; else -> 2 }
        return bitsAt(col, row)
    }

    private fun bitsAt(col: Int, row: Int): Int = when {
        row == 1 && col == 1 -> 0
        row == 0 && col == 0 -> GamepadState.DPAD_UP or GamepadState.DPAD_LEFT
        row == 0 && col == 1 -> GamepadState.DPAD_UP
        row == 0 && col == 2 -> GamepadState.DPAD_UP or GamepadState.DPAD_RIGHT
        row == 1 && col == 0 -> GamepadState.DPAD_LEFT
        row == 1 && col == 2 -> GamepadState.DPAD_RIGHT
        row == 2 && col == 0 -> GamepadState.DPAD_DOWN or GamepadState.DPAD_LEFT
        row == 2 && col == 1 -> GamepadState.DPAD_DOWN
        row == 2 && col == 2 -> GamepadState.DPAD_DOWN or GamepadState.DPAD_RIGHT
        else -> 0
    }

    private fun updateRegion(x: Float, y: Float) {
        val newBits = regionAt(x, y)
        if (newBits == activeBits) return
        val released = activeBits and newBits.inv()
        val pressed = newBits and activeBits.inv()
        activeBits = newBits
        if (released != 0 || pressed != 0) onDpadChange?.invoke(released, pressed)
        invalidate()
    }

    private fun releaseAll() {
        if (activeBits != 0) {
            onDpadChange?.invoke(activeBits, 0)
            activeBits = 0
            onLift?.invoke()
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                alpha = 1f - (activeTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
                if (forceFollowFinger) {
                    effectiveCenterX = event.x
                    effectiveCenterY = event.y
                }
                updateRegion(event.x, event.y)
                invalidate()
                performClick()
                onGyroActivateDown?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouching) updateRegion(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                alpha = 1f - (idleTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
                if (forceFollowFinger) {
                    effectiveCenterX = centerX
                    effectiveCenterY = centerY
                }
                releaseAll()
                performClick()
                onGyroActivateUp?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
