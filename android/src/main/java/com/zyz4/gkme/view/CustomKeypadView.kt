package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.model.FillType

/** Custom keypad: a circle split into 4 directional wedges (up / down / left / right) plus a
 *  centre square. Every region behaves like an independent button: pressing a region fires its
 *  [onRegionPress], sliding out (or reaching the centre/invalid region) fires [onRegionRelease].
 *  There are no direction-combination semantics — the output is whatever each region is bound to.
 *
 *  Region index: 0=up, 1=down, 2=left, 3=right, 4=centre. */
class CustomKeypadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusableInTouchMode = false
    }

    var onRegionPress: ((region: Int) -> Unit)? = null
    var onRegionRelease: ((region: Int) -> Unit)? = null
    var keypadCenterDoubleClick: Boolean = false
    var validDirs: Set<Int> = setOf(0, 1, 2, 3)

    /** When true, the effective center tracks the touch position (follow-area mode) */
    var forceFollowFinger: Boolean = false

    var padFillType: FillType = FillType.SOLID_COLOR
    var padColor: Int = 0xFF1A1A1A.toInt()
    var padImagePath: String? = null
        set(value) {
            if (field != value) {
                field = value
                padBitmap = value?.let { path ->
                    try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
                }
                invalidate()
            }
        }
    var padBorderColor: Int = 0xFF666666.toInt()
    var padBorderWidth: Float = 4f
    var idleTransparency: Int = 0
    var activeTransparency: Int = 0

    var keypadTexts: List<String> = ButtonPosition.KEYPAD_DEFAULT_TEXTS

    private var padBitmap: Bitmap? = null
    private var activeDir = -1
    private var centerPressed = false
    private var isTouching = false

    private var effectiveCenterX = 0f
    private var effectiveCenterY = 0f

    private var firstTapTime = 0L
    private var firstTapX = 0f
    private var firstTapY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val doubleTapTimeout = Runnable { firstTapTime = 0 }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val circlePath = Path()
    private val centerRect = Path()
    private val sepPaint = Paint(borderPaint).apply { style = Paint.Style.STROKE }
    private val wedgePaths = arrayOf(Path(), Path(), Path(), Path())
    private val ringPath = Path()       // circle minus center square (for fill, no gaps)
    private val ringRegionPath = Path() // same ring as union of 4 clipped wedges (for highlight)

    private var side = 0f
    private var originX = 0f
    private var originY = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var half = 0f
    private var d = 0f // r / √2, where diagonals meet the circle

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        side = minOf(w, h).toFloat()
        originX = (w - side) / 2f
        originY = (h - side) / 2f
        centerX = originX + side / 2f
        centerY = originY + side / 2f
        radius = side / 2f
        half = side * 0.20f
        d = radius * 0.70710678f
        effectiveCenterX = centerX
        effectiveCenterY = centerY
        rebuildPaths()
    }

    /** Each wedge path:
     *  Square edge (along the square) → line out to circle point → circular arc → line back to square corner → close.
     *  arcTo with forceMoveTo=false draws the arc starting from the line-To endpoint (which is on the circle). */
    private fun rebuildPaths() {
        val cx = centerX
        val cy = centerY
        val c = half
        val r = radius
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        circlePath.reset()
        circlePath.addCircle(cx, cy, r, Path.Direction.CW)

        centerRect.reset()
        centerRect.addRect(cx - c, cy - c, cx + c, cy + c, Path.Direction.CW)

        // Ring = circle (CW) minus square hole (CCW) = one unified path with no gap.
        // Used for the base fill so there are zero gaps between wedges.
        val cwRing = Path()
        cwRing.addCircle(cx, cy, r, Path.Direction.CW)
        val hole = Path()
        hole.addRect(cx - c, cy - c, cx + c, cy + c, Path.Direction.CCW)
        cwRing.addPath(hole)
        ringPath.reset()
        ringPath.addPath(cwRing)
        ringRegionPath.reset()
        ringRegionPath.addPath(cwRing)

        separatorPath.reset()
        separatorPath.moveTo(cx - c, cy - c); separatorPath.lineTo(cx - d, cy - d)
        separatorPath.moveTo(cx + c, cy - c); separatorPath.lineTo(cx + d, cy - d)
        separatorPath.moveTo(cx - c, cy + c); separatorPath.lineTo(cx - d, cy + d)
        separatorPath.moveTo(cx + c, cy + c); separatorPath.lineTo(cx + d, cy + d)

        // Each wedge: square edge → radial line to circle → arc along circle → close to square.
        // Diagonal circle points: NE(315°)=(+d,-d), SE(45°)=(+d,+d), NW(225°)=(-d,-d), SW(135°)=(-d,+d).
        // All arcs sweep +90° (CW). arcTo(forceMoveTo=false) starts the arc at the current position.
        // After lineTo(circlePt), current position IS on the circle, so arc starts without a bridging line.

        // UP(0):   edge NW→NE, arc NW(225°)→NE(315°) via top. Start NE square, go to NW square, out to NW circle, arc to NE circle, close.
        wedgePaths[0].reset()
        wedgePaths[0].moveTo(cx + c, cy - c)
        wedgePaths[0].lineTo(cx - c, cy - c)
        wedgePaths[0].lineTo(cx - d, cy - d)
        wedgePaths[0].arcTo(rect, 225f, 90f, false)
        wedgePaths[0].close()

        // DOWN(1): edge SW→SE, arc SE(45°)→SW(135°) via bottom. Start SW square, go to SE, out to SE circle, arc to SW circle, close.
        wedgePaths[1].reset()
        wedgePaths[1].moveTo(cx - c, cy + c)
        wedgePaths[1].lineTo(cx + c, cy + c)
        wedgePaths[1].lineTo(cx + d, cy + d)
        wedgePaths[1].arcTo(rect, 45f, 90f, false)
        wedgePaths[1].close()

        // LEFT(2): edge NW→SW, arc SW(135°)→NW(225°) via left. Start NW square, go to SW, out to SW circle, arc to NW circle, close.
        wedgePaths[2].reset()
        wedgePaths[2].moveTo(cx - c, cy - c)
        wedgePaths[2].lineTo(cx - c, cy + c)
        wedgePaths[2].lineTo(cx - d, cy + d)
        wedgePaths[2].arcTo(rect, 135f, 90f, false)
        wedgePaths[2].close()

        // RIGHT(3): edge NE→SE, arc NE(315°)→SE(45°) via right. Start NE square, out to NE circle, arc to SE circle, to SE square, close.
        wedgePaths[3].reset()
        wedgePaths[3].moveTo(cx + c, cy - c)
        wedgePaths[3].lineTo(cx + d, cy - d)
        wedgePaths[3].arcTo(rect, 315f, 90f, false)
        wedgePaths[3].lineTo(cx + c, cy + c)
        wedgePaths[3].close()
    }

    private val separatorPath = Path()

    private fun highlightColor(color: Int, factor: Float): Int {
        val r2 = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g2 = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b2 = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r2, g2, b2)
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

        if (padFillType == FillType.IMAGE && padBitmap != null) {
            ShapeImageUtil.applyCenterCrop(fillPaint, padBitmap!!, side, side)
        } else {
            fillPaint.shader = null
            fillPaint.color = padColor
        }

        canvas.save()
        canvas.clipPath(circlePath)

        // Base fill: single ring path ensures zero gaps between wedges.
        fillPaint.shader = null
        fillPaint.color = padColor
        canvas.drawPath(ringPath, fillPaint)
        fillPaint.shader = null

        if (activeDir in 0..3) {
            fillPaint.color = highlightColor(padColor, 0.3f)
            canvas.drawPath(wedgePaths[activeDir], fillPaint)
        }

        fillPaint.color = if (centerPressed) {
            highlightColor(padColor, 0.45f)
        } else {
            padColor
        }
        canvas.drawPath(centerRect, fillPaint)
        canvas.restore()

        if (padBorderWidth > 0f) {
            sepPaint.color = padBorderColor
            sepPaint.strokeWidth = padBorderWidth
            sepPaint.style = Paint.Style.STROKE
            sepPaint.pathEffect = null
            sepPaint.shader = null
            canvas.drawPath(circlePath, sepPaint)
            canvas.drawPath(separatorPath, sepPaint)
            canvas.drawRect(centerX - half, centerY - half, centerX + half, centerY + half, sepPaint)
        }

        val maxTextSize = Math.max(10f, radius * 0.30f)
        textPaint.textSize = Math.min(maxTextSize, radius * 0.32f)
        drawCenteredText(canvas, textPaint, keypadTexts[0], centerX, centerY - radius * 0.58f)
        drawCenteredText(canvas, textPaint, keypadTexts[1], centerX, centerY + radius * 0.58f)
        drawCenteredText(canvas, textPaint, keypadTexts[2], centerX - radius * 0.58f, centerY)
        drawCenteredText(canvas, textPaint, keypadTexts[3], centerX + radius * 0.58f, centerY)

        textPaint.textSize = Math.min(half * 1.1f, radius * 0.30f)
        drawCenteredText(canvas, textPaint, keypadTexts[4], centerX, centerY)

        canvas.restore()
    }

    private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, cx: Float, cy: Float) {
        if (text.isEmpty()) return
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun directionAt(x: Float, y: Float): Int {
        val dx = x - effectiveCenterX
        val dy = y - effectiveCenterY
        if (Math.abs(dx) <= half && Math.abs(dy) <= half) return -1
        return classify(dx, dy)
    }

    private fun classify(dx: Float, dy: Float): Int = when {
        Math.abs(dx) >= Math.abs(dy) -> if (dx > 0f) 3 else 2
        dy < 0f -> 0
        else -> 1
    }

    private fun updateRegion(x: Float, y: Float) {
        val newDir = directionAt(x, y)
        if (newDir == activeDir) return
        if (newDir in validDirs && newDir != activeDir) {
            // Mini-model: pressing a new valid wedge = releasing the previous one, then pushing the new one.
            releaseActiveDir()
            activeDir = newDir
            onRegionPress?.invoke(newDir)
            invalidate()
        } else if (newDir != activeDir) {
            // Moved out of a valid wedge (e.g. into the centre or an unbound wedge) = release.
            releaseActiveDir()
            invalidate()
        }
    }

    private fun releaseActiveDir() {
        if (activeDir != -1) {
            val old = activeDir
            activeDir = -1
            onRegionRelease?.invoke(old)
            invalidate()
        }
    }

    private fun handleDown(x: Float, y: Float) {
        isTouching = true
        alpha = 1f - (activeTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
        if (forceFollowFinger) {
            effectiveCenterX = x
            effectiveCenterY = y
        }
        val dir = directionAt(x, y)
        if (dir == -1 && keypadCenterDoubleClick) {
            val density = resources.displayMetrics.density
            val now = System.currentTimeMillis()
            val dx = (x - firstTapX) / density
            val dy = (y - firstTapY) / density
            val distDp = Math.sqrt((dx * dx + dy * dy).toDouble())
            if (now - firstTapTime < 300 && firstTapTime > 0 && distDp < 32.0) {
                handler.removeCallbacks(doubleTapTimeout)
                firstTapTime = 0
                centerPressed = true
                invalidate()
                onRegionPress?.invoke(4)
            } else {
                firstTapTime = now
                firstTapX = x
                firstTapY = y
                handler.postDelayed(doubleTapTimeout, 300)
            }
        }
        updateRegion(x, y)
        performClick()
        invalidate()
    }

    private fun handleUp() {
        isTouching = false
        alpha = 1f - (idleTransparency.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
        if (centerPressed) {
            centerPressed = false
            invalidate()
            onRegionRelease?.invoke(4)
        }
        releaseActiveDir()
        if (forceFollowFinger) {
            effectiveCenterX = centerX
            effectiveCenterY = centerY
        }
        performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handleDown(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouching) updateRegion(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleUp()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
