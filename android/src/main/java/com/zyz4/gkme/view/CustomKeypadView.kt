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
import com.zyz4.gkme.R
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.model.CenterShape
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

    /** 按键盘文本颜色。 */
    var appearanceTextColor: Int = 0xFFCCCCCC.toInt()
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

    /** 中心形状。CIRCLE 时中心为内切圆，周围方向区域改为径向扇环（绘制与判定一致）。 */
    var centerShape: CenterShape = CenterShape.SQUARE
        set(value) {
            if (field != value) {
                field = value
                activeDir = -1
                rebuildPaths()
                invalidate()
            }
        }

    /** 中心区域大小（%）：圆形中心半径 = 控件半径 × 百分比；方形中心边长 = 控件直径 × 百分比。 */
    var centerSizePercent: Int = ButtonPosition.KEYPAD_CENTER_SIZE_DEFAULT_EIGHT
        set(value) {
            val v = value.coerceIn(ButtonPosition.CENTER_SIZE_MIN, ButtonPosition.CENTER_SIZE_MAX)
            if (field != v) {
                field = v
                activeDir = -1
                updateCenterGeometry()
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
    // 圆形模式：中心圆、外围环、以及按区域索引存放的扇环路径（4 方向用索引 0..3，8 方向用 0..8）。
    private val centerCirclePath = Path()
    private val circleRingPath = Path()
    private val sectorPaths = Array(9) { Path() }
    private var centerRadius = 0f

    /** 圆形模式扇区序号（0=右，顺时针）→ 区域索引。 */
    private val circleSectorRegionEight = intArrayOf(3, 8, 1, 7, 2, 5, 0, 6)
    private val circleSectorRegionFour = intArrayOf(3, 1, 2, 0)
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
    /** 中心区域半边长（px）：方形中心边长的一半 / 圆形中心半径。 */
    private var centerHalf = 0f

    /** 圆形中心使用径向扇区；方形中心始终使用网格（中心为 0 时由用户自行选择布局）。 */
    private val useRadialRegions: Boolean
        get() = centerShape == CenterShape.CIRCLE
    private var d = 0f // r / √2, where diagonals meet the circle

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        side = minOf(w, h).toFloat()
        originX = (w - side) / 2f
        originY = (h - side) / 2f
        centerX = originX + side / 2f
        centerY = originY + side / 2f
        radius = side / 2f
        d = radius * 0.70710678f
        effectiveCenterX = centerX
        effectiveCenterY = centerY
        updateCenterGeometry()
    }

    /** 根据 [centerSizePercent] 重算中心区域尺寸与全部路径。 */
    private fun updateCenterGeometry() {
        centerHalf = radius * centerSizePercent / 100f
        centerRadius = centerHalf
        rebuildPaths()
    }

    /** 8 方向模式下某一列的左/右边界（网格坐标，相对 view）。 */
    private fun cellLeft(col: Int): Float = when (col) {
        0 -> originX
        1 -> centerX - centerHalf
        else -> centerX + centerHalf
    }

    private fun cellRight(col: Int): Float = when (col) {
        0 -> centerX - centerHalf
        1 -> centerX + centerHalf
        else -> originX + side
    }

    private fun cellTop(row: Int): Float = when (row) {
        0 -> originY
        1 -> centerY - centerHalf
        else -> centerY + centerHalf
    }

    private fun cellBottom(row: Int): Float = when (row) {
        0 -> centerY - centerHalf
        1 -> centerY + centerHalf
        else -> originY + side
    }

    /** Each wedge path:
     *  Square edge (along the square) → line out to circle point → circular arc → line back to square corner → close.
     *  arcTo with forceMoveTo=false draws the arc starting from the line-To endpoint (which is on the circle). */
    private fun rebuildPaths() {
        val cx = centerX
        val cy = centerY
        val c = centerHalf
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

        // 8 方向：按一体十字键的 3×3 网格划分，中心格大小由中心区域百分比决定。
        for (row in 0..2) {
            for (col in 0..2) {
                val path = eightCellPaths[row * 3 + col]
                path.reset()
                path.addRect(
                    cellLeft(col), cellTop(row),
                    cellRight(col), cellBottom(row),
                    Path.Direction.CW,
                )
            }
        }

        // ── 圆形模式：中心圆 + 径向扇环 ──
        // 中心圆半径直接取中心区域半边长（默认值与旧版方形中心内切圆一致）。
        val cr = centerHalf
        centerRadius = cr

        centerCirclePath.reset()
        centerCirclePath.addCircle(cx, cy, cr, Path.Direction.CW)

        val outerRect = RectF(cx - r, cy - r, cx + r, cy + r)
        val innerRect = RectF(cx - cr, cy - cr, cx + cr, cy + cr)

        val cwRingCircle = Path()
        cwRingCircle.addCircle(cx, cy, r, Path.Direction.CW)
        val circleHole = Path()
        circleHole.addCircle(cx, cy, cr, Path.Direction.CCW)
        cwRingCircle.addPath(circleHole)
        circleRingPath.reset()
        circleRingPath.addPath(cwRingCircle)

        for (path in sectorPaths) path.reset()
        if (eightWay) {
            // 区域索引 → 扇环：0=上(270°), 1=下(90°), 2=左(180°), 3=右(0°),
            // 5=左上(225°), 6=右上(315°), 7=左下(135°), 8=右下(45°)。每扇 45°。
            buildSector(sectorPaths[0], outerRect, innerRect, 247.5f, 45f)
            buildSector(sectorPaths[1], outerRect, innerRect, 67.5f, 45f)
            buildSector(sectorPaths[2], outerRect, innerRect, 157.5f, 45f)
            buildSector(sectorPaths[3], outerRect, innerRect, -22.5f, 45f)
            buildSector(sectorPaths[5], outerRect, innerRect, 202.5f, 45f)
            buildSector(sectorPaths[6], outerRect, innerRect, 292.5f, 45f)
            buildSector(sectorPaths[7], outerRect, innerRect, 112.5f, 45f)
            buildSector(sectorPaths[8], outerRect, innerRect, 22.5f, 45f)
        } else {
            // 4 方向：每扇 90°，分隔线在四个对角方向。
            buildSector(sectorPaths[0], outerRect, innerRect, 225f, 90f)
            buildSector(sectorPaths[1], outerRect, innerRect, 45f, 90f)
            buildSector(sectorPaths[2], outerRect, innerRect, 135f, 90f)
            buildSector(sectorPaths[3], outerRect, innerRect, -45f, 90f)
        }
    }

    /** 构造一个扇环路径（中心圆到外圆之间的一段环形区域）。角度使用 Android 画布约定：0° 在右，顺时针为正。 */
    private fun buildSector(path: Path, outer: RectF, inner: RectF, startDeg: Float, sweepDeg: Float) {
        path.reset()
        path.arcTo(outer, startDeg, sweepDeg, true)
        path.arcTo(inner, startDeg + sweepDeg, -sweepDeg, false)
        path.close()
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

        textPaint.color = appearanceTextColor

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

        if (useRadialRegions) {
            // 圆形中心（或中心为 0）：整圆填充 + 方向扇环高亮 + 中心圆高亮。
            fillPaint.shader = null
            fillPaint.color = padColor
            canvas.drawPath(circlePath, fillPaint)
            if (activeDir in sectorPaths.indices && activeDir != ButtonPosition.KEYPAD_CENTER_INDEX) {
                fillPaint.color = highlightColor(padColor, 0.3f)
                canvas.drawPath(sectorPaths[activeDir], fillPaint)
            }
            if (centerPressed && centerHalf > 0f) {
                fillPaint.color = highlightColor(padColor, 0.45f)
                canvas.drawPath(centerCirclePath, fillPaint)
            }
        } else if (eightWay) {
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
            if (useRadialRegions) {
                // 中心圆 + 扇区分隔线（中心圆边缘 → 外圆）。
                canvas.drawCircle(centerX, centerY, centerRadius, sepPaint)
                val step = if (eightWay) 45f else 90f
                var deg = if (eightWay) 22.5f else 45f
                while (deg < 360f) {
                    val rad = Math.toRadians(deg.toDouble())
                    val cos = Math.cos(rad).toFloat()
                    val sin = Math.sin(rad).toFloat()
                    canvas.drawLine(
                        centerX + centerRadius * cos, centerY + centerRadius * sin,
                        centerX + radius * cos, centerY + radius * sin,
                        sepPaint,
                    )
                    deg += step
                }
            } else if (eightWay) {
                // 3×3 网格分隔线，裁剪到圆内，形成一体十字键的十字图案。
                val left = centerX - centerHalf
                val right = centerX + centerHalf
                val top = centerY - centerHalf
                val bottom = centerY + centerHalf
                canvas.save()
                canvas.clipPath(circlePath)
                canvas.drawLine(left, originY, left, originY + side, sepPaint)
                canvas.drawLine(right, originY, right, originY + side, sepPaint)
                canvas.drawLine(originX, top, originX + side, top, sepPaint)
                canvas.drawLine(originX, bottom, originX + side, bottom, sepPaint)
                canvas.restore()
            } else {
                canvas.drawPath(separatorPath, sepPaint)
                canvas.drawRect(centerX - centerHalf, centerY - centerHalf, centerX + centerHalf, centerY + centerHalf, sepPaint)
            }
        }

        val textAt: (Int) -> String = { localizeKeypadText(keypadTexts.getOrElse(it) { "" }) }
        if (useRadialRegions) {
            // 径向模式：每个方向文本放在对应扇环的中间半径处。
            val midR = centerRadius + (radius - centerRadius) * 0.62f
            val box = (radius - centerRadius) * 0.9f
            fun place(region: Int, deg: Float) {
                val rad = Math.toRadians(deg.toDouble())
                drawRegionText(
                    canvas, textAt(region),
                    centerX + midR * Math.cos(rad).toFloat(),
                    centerY + midR * Math.sin(rad).toFloat(),
                    box, box,
                )
            }
            place(0, 270f); place(1, 90f); place(2, 180f); place(3, 0f)
            if (eightWay) {
                place(5, 225f); place(6, 315f); place(7, 135f); place(8, 45f)
            }
            if (centerHalf > 0f) {
                val centerBox = centerRadius * 2f
                drawRegionText(canvas, textAt(ButtonPosition.KEYPAD_CENTER_INDEX), centerX, centerY, centerBox, centerBox)
            }
        } else if (eightWay) {
            val x1 = centerX
            val y1 = centerY
            val edge = radius - centerHalf
            val edgeW = edge.coerceAtLeast(1f)
            val centerW = (centerHalf * 2f).coerceAtLeast(1f)
            // 边缘/中心格：按中心区域大小动态分配剩余空间。
            drawRegionText(canvas, textAt(0), x1, originY + edge / 2f, centerW, edgeW)
            drawRegionText(canvas, textAt(1), x1, originY + side - edge / 2f, centerW, edgeW)
            drawRegionText(canvas, textAt(2), originX + edge / 2f, y1, edgeW, centerW)
            drawRegionText(canvas, textAt(3), originX + side - edge / 2f, y1, edgeW, centerW)
            if (centerHalf > 0f) {
                drawRegionText(canvas, textAt(ButtonPosition.KEYPAD_CENTER_INDEX), x1, y1, centerW, centerW)
            }
            // 四角格被圆裁剪成不规则形状：用可见区域的形心定位，并按该点的内切圆缩放文本，
            // 避免中心区域变大/变小时文本跑出圆弧外。
            val corner = cornerRegionGeometry()
            val cornerC = corner[0]
            val cornerD = corner[2] * 0.95f
            drawRegionTextInCircle(canvas, textAt(5), x1 - cornerC, y1 - cornerC, cornerD)
            drawRegionTextInCircle(canvas, textAt(6), x1 + cornerC, y1 - cornerC, cornerD)
            drawRegionTextInCircle(canvas, textAt(7), x1 - cornerC, y1 + cornerC, cornerD)
            drawRegionTextInCircle(canvas, textAt(8), x1 + cornerC, y1 + cornerC, cornerD)
        } else {
            // 四方向为 90° 扇形，可用框取接近正方形的 2/3 半径；中心为内部方块。
            val box = radius * 0.667f
            val edge = radius - centerHalf
            val off = centerHalf + edge * 0.475f
            drawRegionText(canvas, textAt(0), centerX, centerY - off, box, box)
            drawRegionText(canvas, textAt(1), centerX, centerY + off, box, box)
            drawRegionText(canvas, textAt(2), centerX - off, centerY, box, box)
            drawRegionText(canvas, textAt(3), centerX + off, centerY, box, box)
            val centerBox = (centerHalf * 2f).coerceAtLeast(1f)
            if (centerHalf > 0f) {
                drawRegionText(canvas, textAt(ButtonPosition.KEYPAD_CENTER_INDEX), centerX, centerY, centerBox, centerBox)
            }
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

    /** 在半径为 [radius] 的圆内居中绘制文本，字号按文本外接框的对角线计算，保证不越界。 */
    private fun drawRegionTextInCircle(canvas: Canvas, text: String, cx: Float, cy: Float, radius: Float) {
        if (text.isEmpty() || radius <= 0f) return
        val ref = 100f
        textPaint.textSize = ref
        val w = textPaint.measureText(text).coerceAtLeast(0.01f)
        val h = (textPaint.descent() - textPaint.ascent()).coerceAtLeast(0.01f)
        val diagonal = Math.sqrt((w * w + h * h).toDouble()).toFloat().coerceAtLeast(0.01f)
        textPaint.textSize = capTextSize(ref * (radius * 2f) / diagonal)
        drawCenteredText(canvas, textPaint, text, cx, cy)
    }

    /**
     * 8 方向 + 方形中心时，四角格被外圆裁剪成不规则形状。
     * 返回可见区域的形心坐标与形心到边界的最小距离（内切圆半径），相对控件中心。
     *
     * 角格在第一象限表示为 {u >= a, v >= a, u² + v² <= r²}（a = 中心半边长），
     * 返回 [c, c, d]，其中 (c, c) 为形心，d 为内切圆半径。
     */
    private fun cornerRegionGeometry(): FloatArray {
        val r = radius
        val a = centerHalf.coerceIn(0f, r)
        if (r <= 0f) return floatArrayOf(0f, 0f, 0f)
        val s = Math.sqrt((r * r - a * a).coerceAtLeast(0f).toDouble()).toFloat()
        if (s <= a) return floatArrayOf(0f, 0f, 0f)
        val theta = Math.asin((a / r).coerceIn(-1f, 1f).toDouble())
        val area = r * r * 0.5f * (Math.PI / 2.0 - 2.0 * theta).toFloat() - a * (s - a)
        if (area <= 0.0001f) return floatArrayOf(0f, 0f, 0f)
        val moment = (s * s * s - a * a * a) / 3f - a * (s * s - a * a) / 2f
        val c = (moment / area).coerceIn(a, r * 0.70710678f)
        val d = minOf(c - a, r - c * 1.41421356f).coerceAtLeast(0f)
        return floatArrayOf(c, c, d)
    }

    /** 应用全局“最大图标大小”上限。 */
    private fun capTextSize(size: Float): Float =
        textMaxSizePx?.let { Math.min(size, it) } ?: size

    private fun directionAt(x: Float, y: Float): Int {
        if (useRadialRegions) return regionAtCircle(x, y)
        if (eightWay) return regionAtEight(x, y)
        val dx = x - effectiveCenterX
        val dy = y - effectiveCenterY
        if (Math.abs(dx) <= centerHalf && Math.abs(dy) <= centerHalf) return -1
        return classify(dx, dy)
    }

    /** 圆形模式：中心圆内返回 -1（中心），否则按极角落入方向扇环，返回区域索引。 */
    private fun regionAtCircle(x: Float, y: Float): Int {
        val dx = x - effectiveCenterX
        val dy = y - effectiveCenterY
        if (dx * dx + dy * dy <= centerRadius * centerRadius) return -1
        var deg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        if (eightWay) {
            // 8 扇区：0=右, 1=右下, 2=下, 3=左下, 4=左, 5=左上, 6=上, 7=右上。
            val k = (((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
            return circleSectorRegionEight[k]
        }
        // 4 扇区：0=右, 1=下, 2=左, 3=上。
        val k = (((deg + 45f) % 360f) / 90f).toInt().coerceIn(0, 3)
        return circleSectorRegionFour[k]
    }

    private fun classify(dx: Float, dy: Float): Int = when {
        Math.abs(dx) >= Math.abs(dy) -> if (dx > 0f) 3 else 2
        dy < 0f -> 0
        else -> 1
    }

    /** 8 方向：按一体十字键的 3×3 网格判定落点，返回区域索引；中心格返回 -1。 */
    private fun regionAtEight(x: Float, y: Float): Int {
        val dx = x - effectiveCenterX
        val dy = y - effectiveCenterY
        val col = when {
            dx < -centerHalf -> 0
            dx > centerHalf -> 2
            else -> 1
        }
        val row = when {
            dy < -centerHalf -> 0
            dy > centerHalf -> 2
            else -> 1
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
    /** Legacy presets store the built-in default labels in Chinese; localize them at draw time. */
    private fun localizeKeypadText(text: String): String = when (text) {
        "上" -> context.getString(R.string.i18n_up)
        "下" -> context.getString(R.string.i18n_down)
        "左" -> context.getString(R.string.i18n_left_d2aff1)
        "右" -> context.getString(R.string.i18n_right)
        "中" -> context.getString(R.string.i18n_center)
        "左上" -> context.getString(R.string.i18n_top_left)
        "右上" -> context.getString(R.string.i18n_top_right)
        "左下" -> context.getString(R.string.i18n_bottom_left)
        "右下" -> context.getString(R.string.i18n_bottom_right)
        else -> text
    }

}
