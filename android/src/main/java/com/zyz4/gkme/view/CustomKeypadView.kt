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

/** Custom keypad: a circle split into directional regions plus a centre. In 4-direction mode the
 *  regions are wedge-shaped (up / down / left / right) around a centre square; in 8-direction mode
 *  ([eightWay]) the circle uses the same 3×3 grid partition as the integrated D-pad
 *  (cardinal edge cells + diagonal corner cells, centre = middle cell).
 *  Every region behaves like an independent button: pressing a region fires its [onRegionPress],
 *  sliding out (or reaching the centre/invalid region) fires [onRegionRelease].
 *  There are no direction-combination semantics — the output is whatever each region is bound to.
 *
 *  Region index: 0=up, 1=down, 2=left, 3=right, 4=centre, 5=up-left, 6=up-right, 7=down-left,
 *  8=down-right. */
class CustomKeypadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusableInTouchMode = false
    }

    var onRegionPress: ((region: Int) -> Unit)? = null
    var onRegionRelease: ((region: Int) -> Unit)? = null
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
    var idleOpacity: Int = 100
    var activeOpacity: Int = 100

    var keypadTexts: List<String> = ButtonPosition.KEYPAD_DEFAULT_TEXTS
        set(value) {
            field = value
            invalidate()
        }

    /** true = 8 方向模式（上/下/左/右/左上/右上/左下/右下），false = 4 方向模式。 */
    var eightWay: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                activeDir = -1
                rebuildPaths()
                invalidate()
            }
        }

    /** 全局“最大图标大小”限制（px）；null = 不限制。字号不会超过该值。 */
    var textMaxSizePx: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

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
    // 8 方向模式：3×3 网格中每个格的路径，索引 = row*3 + col（与一体十字键的划分一致）。
    private val eightCellPaths = Array(9) { Path() }
    // (row, col) → 区域索引；-1 表示中心（不参与方向触发，走双击逻辑）。
    private val gridRegionTable = arrayOf(
        intArrayOf(5, 0, 6),
        intArrayOf(2, -1, 3),
        intArrayOf(7, 1, 8),
    )
    // 区域索引 → 网格格索引 (row*3+col)。
    private val regionToCellIndex = intArrayOf(1, 7, 3, 5, 4, 0, 2, 6, 8)
    private val ringPath = Path()       // circle minus center square (for fill, no gaps)
    private val ringRegionPath = Path() // same ring as union of 4 clipped wedges (for highlight)

    private var side = 0f
    private var originX = 0f
    private var originY = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var half = 0f
    private var third = 0f // 8 方向网格每格边长 (side / 3)
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
        third = side / 3f
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

        // 8 方向：按一体十字键的 3×3 网格划分，每个方向对应一个 1/3 格。
        val a = third
        for (row in 0..2) {
            for (col in 0..2) {
                val path = eightCellPaths[row * 3 + col]
                path.reset()
                path.addRect(
                    originX + col * a, originY + row * a,
                    originX + (col + 1) * a, originY + (row + 1) * a,
                    Path.Direction.CW,
                )
            }
        }
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

        if (eightWay) {
            // 一体十字键图案：整圆填充 + 3×3 网格区域高亮。
            fillPaint.shader = null
            fillPaint.color = padColor
            canvas.drawPath(circlePath, fillPaint)
            if (activeDir in 0..8 && activeDir != ButtonPosition.KEYPAD_CENTER_INDEX) {
                fillPaint.color = highlightColor(padColor, 0.3f)
                canvas.drawPath(eightCellPaths[regionToCellIndex[activeDir]], fillPaint)
            }
            fillPaint.color = if (centerPressed) {
                highlightColor(padColor, 0.45f)
            } else {
                padColor
            }
            canvas.drawPath(eightCellPaths[regionToCellIndex[ButtonPosition.KEYPAD_CENTER_INDEX]], fillPaint)
        } else {
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
        }
        canvas.restore()

        if (padBorderWidth > 0f) {
            sepPaint.color = padBorderColor
            sepPaint.strokeWidth = padBorderWidth
            sepPaint.style = Paint.Style.STROKE
            sepPaint.pathEffect = null
            sepPaint.shader = null
            canvas.drawPath(circlePath, sepPaint)
            if (eightWay) {
                // 3×3 网格分隔线，裁剪到圆内，形成一体十字键的十字图案。
                val a = third
                canvas.save()
                canvas.clipPath(circlePath)
                canvas.drawLine(originX + a, originY, originX + a, originY + side, sepPaint)
                canvas.drawLine(originX + 2f * a, originY, originX + 2f * a, originY + side, sepPaint)
                canvas.drawLine(originX, originY + a, originX + side, originY + a, sepPaint)
                canvas.drawLine(originX, originY + 2f * a, originX + side, originY + 2f * a, sepPaint)
                canvas.restore()
            } else {
                canvas.drawPath(separatorPath, sepPaint)
                canvas.drawRect(centerX - half, centerY - half, centerX + half, centerY + half, sepPaint)
            }
        }

        val textAt: (Int) -> String = { keypadTexts.getOrElse(it) { "" } }
        if (eightWay) {
            val a = third
            val x1 = centerX
            val y1 = centerY
            // 边缘/中心格：接近完整的 1/3 格。
            drawRegionText(canvas, textAt(0), x1, originY + a / 2f, a, a)
            drawRegionText(canvas, textAt(1), x1, originY + 2.5f * a, a, a)
            drawRegionText(canvas, textAt(2), originX + a / 2f, y1, a, a)
            drawRegionText(canvas, textAt(3), originX + 2.5f * a, y1, a, a)
            drawRegionText(canvas, textAt(ButtonPosition.KEYPAD_CENTER_INDEX), x1, y1, a, a)
            // 四角格被圆裁剪，可用框更小；文本中心取可见区域形心。
            val cornerBox = radius * 0.50f
            val cOff = radius * 0.57f
            drawRegionText(canvas, textAt(5), x1 - cOff, y1 - cOff, cornerBox, cornerBox)
            drawRegionText(canvas, textAt(6), x1 + cOff, y1 - cOff, cornerBox, cornerBox)
            drawRegionText(canvas, textAt(7), x1 - cOff, y1 + cOff, cornerBox, cornerBox)
            drawRegionText(canvas, textAt(8), x1 + cOff, y1 + cOff, cornerBox, cornerBox)
        } else {
            // 四方向为 90° 扇形，可用框取接近正方形的 2/3 半径；中心为内部方块。
            val box = radius * 0.667f
            drawRegionText(canvas, textAt(0), centerX, centerY - radius * 0.58f, box, box)
            drawRegionText(canvas, textAt(1), centerX, centerY + radius * 0.58f, box, box)
            drawRegionText(canvas, textAt(2), centerX - radius * 0.58f, centerY, box, box)
            drawRegionText(canvas, textAt(3), centerX + radius * 0.58f, centerY, box, box)
            val centerBox = half * 2f
            drawRegionText(canvas, textAt(ButtonPosition.KEYPAD_CENTER_INDEX), centerX, centerY, centerBox, centerBox)
        }

        canvas.restore()
    }

    private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, cx: Float, cy: Float) {
        if (text.isEmpty()) return
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, cx, baseline, paint)
    }

    /** 文本宽度不超过区域的 80%、高度不超过 90%，并应用全局“最大图标大小”上限。 */
    private fun fittedTextSize(text: String, boxW: Float, boxH: Float): Float {
        if (text.isEmpty()) return 0f
        val ref = 100f
        textPaint.textSize = ref
        val w = textPaint.measureText(text).coerceAtLeast(0.01f)
        val h = (textPaint.descent() - textPaint.ascent()).coerceAtLeast(0.01f)
        val byWidth = ref * (boxW * 0.80f) / w
        val byHeight = ref * (boxH * 0.90f) / h
        return capTextSize(minOf(byWidth, byHeight))
    }

    private fun drawRegionText(canvas: Canvas, text: String, cx: Float, cy: Float, boxW: Float, boxH: Float) {
        if (text.isEmpty()) return
        textPaint.textSize = fittedTextSize(text, boxW, boxH)
        drawCenteredText(canvas, textPaint, text, cx, cy)
    }

    /** 应用全局“最大图标大小”上限。 */
    private fun capTextSize(size: Float): Float =
        textMaxSizePx?.let { Math.min(size, it) } ?: size

    private fun directionAt(x: Float, y: Float): Int {
        if (eightWay) return regionAtEight(x, y)
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

    /** 8 方向：按一体十字键的 3×3 网格判定落点，返回区域索引；中心格返回 -1。 */
    private fun regionAtEight(x: Float, y: Float): Int {
        val a = third
        val r = radius
        val eOffX = effectiveCenterX - originX
        val eOffY = effectiveCenterY - originY
        val lx = x - originX
        val ly = y - originY
        val col = when {
            lx < eOffX - r + a -> 0
            lx < eOffX - r + 2f * a -> 1
            else -> 2
        }
        val row = when {
            ly < eOffY - r + a -> 0
            ly < eOffY - r + 2f * a -> 1
            else -> 2
        }
        return gridRegionTable[row][col]
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
        alpha = activeOpacity.coerceIn(0, 100) / 100f
        if (forceFollowFinger) {
            effectiveCenterX = x
            effectiveCenterY = y
        }
        val dir = directionAt(x, y)
        if (dir == -1) {
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
        alpha = idleOpacity.coerceIn(0, 100) / 100f
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
