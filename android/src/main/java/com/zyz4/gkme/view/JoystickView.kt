package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var label: String = ""
    var axisRotation: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var onStickMoved: ((sx: Short, sy: Short) -> Unit)? = null
    var onStickClickDown: (() -> Unit)? = null
    var onStickClickUp: (() -> Unit)? = null
    var onStickReleased: (() -> Unit)? = null
    var onGyroActivateDown: (() -> Unit)? = null
    var onGyroActivateUp: (() -> Unit)? = null
    var doubleClickEnable: Boolean = true
    var forceFollowFinger: Boolean = false
    var idleOpacity: Int = 100
    var activeOpacity: Int = 100
    var sensitivityCurve: List<Float>? = null
    var joystickSensitivity: Int = 100
    var touchpadMode: Boolean = false
    var prediction: Boolean = false
    var deadZone: Int = 0
    var reverseDeadZone: Int = 0
    var showDeadZoneIndicator: Boolean = false
    // Max label size in px (from the adaptive icon-size setting); null = sized relative to the cap.
    var labelMaxSizePx: Float? = null

    private var centerX = 0f
    private var centerY = 0f
    private var effectiveCenterX = 0f
    private var effectiveCenterY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    // Appearance properties
    var appearanceBaseColor: Int = -0xdddddd
    var appearanceBaseBitmap: Bitmap? = null
    var appearanceBaseOutlineColor: Int = -0xaaaaab
    var appearanceBaseOutlineWidth: Float = 2f
    var appearanceCapColor: Int = -0xaaaaab
    var appearanceCapBitmap: Bitmap? = null
    var appearanceCapOutlineColor: Int = -0x888889
    var appearanceCapOutlineWidth: Float = 1.5f
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val baseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x555556
        textAlign = Paint.Align.CENTER
        textSize = 0f
    }
    private val deadZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffee00.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val reverseDeadZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff0088ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var isTouching = false
    private var isClicking = false
    private var firstTapTime = 0L
    private var firstTapX = 0f
    private var firstTapY = 0f
    private var isDoubleClick = false
    private val handler = Handler(Looper.getMainLooper())
    private val doubleTapTimeout = Runnable { firstTapTime = 0 }

    // ── 触摸板（速度）模式状态 ──
    // 直接以触摸事件为时钟：每个触摸样本计算一次瞬时速度，获取频率天然等于屏幕触控采样率。
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastSampleX = 0f
    private var lastSampleY = 0f
    private var lastSampleTime = 0L
    // 触控采样率识别：触摸板模式与「快速响应模式」共用，估计值随屏幕触控采样率自适应。
    private val sampleRate = TouchSampleRateTracker(TAG)
    // 看门狗：手指停下（不再产生 MOVE）后，按估计的采样间隔把速度归零并回中。
    private val velocityResetRunnable = Runnable {
        if (!isTouching || !touchpadMode) return@Runnable
        velocityX = 0f
        velocityY = 0f
        applyVelocity(0f, 0f)
    }
    // ── 绝对模式预测：记录上一次触摸样本，用速度外推下一帧位置 ──
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchTime = 0L
    // 预测路径是否已获得有效移动样本（跳过 DOWN→首次 MOVE）。
    private var predictionSampled = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        effectiveCenterX = centerX
        effectiveCenterY = centerY
        baseRadius = minOf(w, h) / 2f
        knobRadius = baseRadius * 0.32f
        knobX = effectiveCenterX
        knobY = effectiveCenterY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.rotate(axisRotation.toFloat(), effectiveCenterX, effectiveCenterY)

        val active = isClicking

        // Base fill
        if (appearanceBaseBitmap != null) {
            ShapeImageUtil.applyCenterCrop(basePaint, appearanceBaseBitmap!!, baseRadius * 2, baseRadius * 2)
        } else {
            basePaint.shader = null
            basePaint.color = if (active) highlightColor(appearanceBaseColor, 0.3f) else appearanceBaseColor
        }
        canvas.drawCircle(effectiveCenterX, effectiveCenterY, baseRadius, basePaint)
        basePaint.shader = null

        // Base outline
        if (appearanceBaseOutlineWidth > 0f) {
            baseStrokePaint.color = if (active) highlightColor(appearanceBaseOutlineColor, 0.3f) else appearanceBaseOutlineColor
            baseStrokePaint.strokeWidth = appearanceBaseOutlineWidth
            canvas.drawCircle(effectiveCenterX, effectiveCenterY, baseRadius - appearanceBaseOutlineWidth / 2f, baseStrokePaint)
        }

        // Cap fill
        if (appearanceCapBitmap != null) {
            ShapeImageUtil.applyCenterCrop(knobPaint, appearanceCapBitmap!!, knobRadius * 2, knobRadius * 2)
        } else {
            knobPaint.shader = null
            knobPaint.color = if (active) highlightColor(appearanceCapColor, 0.3f) else appearanceCapColor
        }
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        knobPaint.shader = null

        // Cap outline
        if (appearanceCapOutlineWidth > 0f) {
            knobStrokePaint.color = if (active) highlightColor(appearanceCapOutlineColor, 0.3f) else appearanceCapOutlineColor
            knobStrokePaint.strokeWidth = appearanceCapOutlineWidth
            canvas.drawCircle(knobX, knobY, knobRadius - appearanceCapOutlineWidth / 2f, knobStrokePaint)
        }

        if (label.isNotEmpty()) {
            // Follow the adaptive icon-size cap (labelMaxSizePx); otherwise keep the natural
            // size relative to the cap (capped by the knob itself).
            val natural = knobRadius * 1.1f
            labelPaint.textSize = labelMaxSizePx?.let { minOf(natural, it) } ?: natural
            val textY = knobY - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(label, knobX, textY, labelPaint)
        }

        // Dead zone (yellow) and reverse dead zone (blue) circles
        // Drawn after cap so they are not covered by the cap.
        if (showDeadZoneIndicator) {
            if (deadZone > 0) {
                val dzRadius = baseRadius * (deadZone / 100f)
                canvas.drawCircle(effectiveCenterX, effectiveCenterY, dzRadius, deadZonePaint)
            }
            if (reverseDeadZone > 0) {
                val rdzRadius = baseRadius * (reverseDeadZone / 100f)
                canvas.drawCircle(effectiveCenterX, effectiveCenterY, rdzRadius, reverseDeadZonePaint)
            }
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (doubleClickEnable) {
                    val now = System.currentTimeMillis()
                    if (now - firstTapTime < 300 && firstTapTime > 0) {
                        handler.removeCallbacks(doubleTapTimeout)
                        isClicking = true
                        isDoubleClick = true
                        firstTapTime = 0
                        invalidate()
                        onStickClickDown?.invoke()
                    } else {
                        firstTapTime = now
                        firstTapX = event.x
                        firstTapY = event.y
                        isDoubleClick = false
                        handler.postDelayed(doubleTapTimeout, 300)
                    }
                }
                isTouching = true
                alpha = activeOpacity.coerceIn(0, 100) / 100f
                if (forceFollowFinger) {
                    effectiveCenterX = event.x
                    effectiveCenterY = event.y
                }
                if (touchpadMode) {
                    startVelocityTracking(event.x, event.y)
                } else {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    lastTouchTime = event.eventTime
                    predictionSampled = false
                    if (prediction) sampleRate.reset()
                    moveKnob(event.x, event.y)
                }
                onGyroActivateDown?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouching) {
                    if (touchpadMode) {
                        sampleTouchVelocity(event)
                    } else if (prediction) {
                        moveKnobPredicted(event)
                    } else {
                        moveKnob(event.x, event.y)
                    }
                }
                if (firstTapTime != 0L) {
                    val dx = event.x - firstTapX
                    val dy = event.y - firstTapY
                    if (sqrt(dx * dx + dy * dy) > 30f) {
                        firstTapTime = 0
                        handler.removeCallbacks(doubleTapTimeout)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                isClicking = false
                alpha = idleOpacity.coerceIn(0, 100) / 100f
                if (touchpadMode) {
                    stopVelocityTracking()
                }
                if (forceFollowFinger) {
                    effectiveCenterX = centerX
                    effectiveCenterY = centerY
                }
                knobX = effectiveCenterX
                knobY = effectiveCenterY
                invalidate()
                if (isDoubleClick) {
                    onStickClickUp?.invoke()
                }
                onStickReleased?.invoke()
                onStickMoved?.invoke(0, 0)
                isDoubleClick = false
                performClick()
                onGyroActivateUp?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveKnob(tx: Float, ty: Float) {
        val dx = tx - effectiveCenterX
        val dy = ty - effectiveCenterY
        val maxD = baseRadius - knobRadius
        val dist = sqrt(dx * dx + dy * dy)

        val r = axisRotation * Math.PI / 180.0
        val cosR = Math.cos(-r).toFloat()
        val sinR = Math.sin(-r).toFloat()
        val cdx = dx * cosR - dy * sinR
        val cdy = dx * sinR + dy * cosR

        // 灵敏度：手指位移归一化后乘以灵敏度，再在死区之前应用。
        // 小于 100% 时手指可超出 view 继续推动摇杆，直到达到满量程才到达边界。
        val sens = (joystickSensitivity / 100f).coerceIn(0.01f, 10f)
        val rawNorm = if (maxD > 0f) dist / maxD else 0f
        val normalized = (rawNorm * sens).coerceAtMost(1f)
        val afterCurve = shapeNormalized(normalized)
        val finalDist = afterCurve * maxD

        val scale = if (dist > 0f) finalDist / dist else 0f
        knobX = effectiveCenterX + cdx * scale
        knobY = effectiveCenterY + cdy * scale
        invalidate()

        if (maxD > 0f) emitStick(dx, dy, dist, afterCurve) else onStickMoved?.invoke(0, 0)
    }

    /** 绝对模式预测：用相邻样本的速度外推下一帧手指位置，再送入 [moveKnob]。 */
    private fun moveKnobPredicted(event: MotionEvent) {
        val tx = event.x
        val ty = event.y
        val dtMs = (event.eventTime - lastTouchTime).coerceAtLeast(1L)
        val vx = (tx - lastTouchX) / dtMs
        val vy = (ty - lastTouchY) / dtMs
        // 采样间隔优先取批处理中的历史样本；首个样本（DOWN→首次 MOVE）不可靠，跳过。
        val history = event.historySize
        if (history > 0) {
            sampleRate.update((event.eventTime - event.getHistoricalEventTime(history - 1)).coerceAtLeast(1L).toFloat())
        } else if (predictionSampled) {
            sampleRate.update(dtMs.toFloat())
        }
        predictionSampled = true
        lastTouchX = tx
        lastTouchY = ty
        lastTouchTime = event.eventTime
        // 外推时长与识别到的触控采样间隔同步；未识别时退回固定帧长。
        val frame = sampleRate.intervalOr(com.zyz4.gkme.model.ButtonPosition.PREDICTION_FRAME_MS)
        moveKnob(tx + vx * frame, ty + vy * frame)
    }

    /** 触摸板模式：把手指速度矢量映射为摇杆偏移；速度归零时摇杆回到中心。 */
    private fun applyVelocity(vx: Float, vy: Float) {
        val maxD = baseRadius - knobRadius
        if (maxD <= 0f) return
        val speed = sqrt(vx * vx + vy * vy)

        // 与绝对模式同一条管线：先按最大行程 + 时间常数归一化，再乘灵敏度，
        // 然后依次经过死区 / 反死区 / 曲线。
        val sens = (joystickSensitivity / 100f).coerceIn(0.01f, 10f)
        val rawNorm = speed * VELOCITY_TIME_CONSTANT / maxD * sens
        val normalized = rawNorm.coerceAtMost(1f)
        val afterCurve = shapeNormalized(normalized)
        val finalDist = afterCurve * maxD

        val r = axisRotation * Math.PI / 180.0
        val cosR = Math.cos(-r).toFloat()
        val sinR = Math.sin(-r).toFloat()
        val cvx = vx * cosR - vy * sinR
        val cvy = vx * sinR + vy * cosR

        val scale = if (speed > 0f) finalDist / speed else 0f
        knobX = effectiveCenterX + cvx * scale
        knobY = effectiveCenterY + cvy * scale
        invalidate()

        emitStick(vx, vy, speed, afterCurve)
    }

    /** 死区 → 反死区 → 灵敏度曲线，输入/输出均为归一化量 (0..1)。 */
    private fun shapeNormalized(normalized: Float): Float {
        val dz = (deadZone / 100f).coerceIn(0f, 0.99f)
        val afterDeadZone = if (normalized <= dz) 0f
                            else (normalized - dz) / (1f - dz)

        val rdz = (reverseDeadZone / 100f).coerceIn(0f, 0.99f)
        val afterReverseDeadZone = if (afterDeadZone == 0f) rdz
                                   else afterDeadZone * (1f - rdz) + rdz
        return evaluateCurve(afterReverseDeadZone)
    }

    /** 按方向矢量与曲线后幅度生成 16 位摇杆输出，并施加旋转映射。 */
    private fun emitStick(vx: Float, vy: Float, magnitude: Float, afterCurve: Float) {
        val dirScale = if (magnitude > 0f) afterCurve / magnitude else 0f
        var sx = (vx * dirScale * 32767).toInt().toShort()
        var sy = (vy * dirScale * 32767).toInt().toShort()

        when (axisRotation % 360) {
            90 -> { val tmp = sx; sx = sy; sy = (-tmp).toShort() }
            180 -> { sx = (-sx).toShort(); sy = (-sy).toShort() }
            270 -> { val tmp = sx; sx = (-sy).toShort(); sy = tmp }
        }
        onStickMoved?.invoke(sx, sy)
    }

    private fun startVelocityTracking(x: Float, y: Float) {
        lastSampleX = x
        lastSampleY = y
        // 0 表示尚无有效触摸样本：下一个 MOVE 只做种子，不据此计算速度/采样间隔，
        // 避免把 DOWN→首次 MOVE 的间隔误当成触控采样间隔。
        lastSampleTime = 0L
        sampleRate.reset()
        velocityX = 0f
        velocityY = 0f
        handler.removeCallbacks(velocityResetRunnable)
    }

    private fun stopVelocityTracking() {
        handler.removeCallbacks(velocityResetRunnable)
        velocityX = 0f
        velocityY = 0f
        sampleRate.reset()
    }

    /**
     * 按触摸事件计算瞬时速度。触摸事件（含批处理中的历史样本）即触控采样率的时钟，
     * 因此速度获取频率随屏幕触控采样率自适应，不再依赖固定定时器。
     */
    private fun sampleTouchVelocity(event: MotionEvent) {
        val history = event.historySize
        val prevX: Float
        val prevY: Float
        val prevTime: Long
        if (history > 0) {
            // 批处理事件内优先取最近两个原始样本，得到真实采样间隔下的速度。
            val idx = history - 1
            prevX = event.getHistoricalX(idx)
            prevY = event.getHistoricalY(idx)
            prevTime = event.getHistoricalEventTime(idx)
        } else if (lastSampleTime > 0L) {
            prevX = lastSampleX
            prevY = lastSampleY
            prevTime = lastSampleTime
        } else {
            lastSampleX = event.x
            lastSampleY = event.y
            lastSampleTime = event.eventTime
            return
        }

        val dtMs = (event.eventTime - prevTime).coerceAtLeast(1L)
        sampleRate.update(dtMs.toFloat())
        lastSampleX = event.x
        lastSampleY = event.y
        lastSampleTime = event.eventTime

        velocityX = (event.x - prevX) / dtMs * 1000f
        velocityY = (event.y - prevY) / dtMs * 1000f
        applyVelocity(velocityX, velocityY)
        scheduleVelocityReset()
    }

    /** 手指停下后不再有 MOVE，按估计的采样间隔触发一次归零，让摇杆回中。 */
    private fun scheduleVelocityReset() {
        handler.removeCallbacks(velocityResetRunnable)
        val interval = sampleRate.intervalOr(MIN_SAMPLE_INTERVAL_MS)
        val delay = (interval * VELOCITY_RESET_INTERVALS).toLong().coerceAtLeast(1L)
        handler.postDelayed(velocityResetRunnable, delay)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(velocityResetRunnable)
        handler.removeCallbacks(doubleTapTimeout)
    }

    private fun evaluateCurve(t: Float): Float =
        com.zyz4.gkme.input.SensitivityCurve.evaluate(sensitivityCurve, t)

    private fun highlightColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    companion object {
        // 触摸板模式增益：摇杆偏移 = 手指速度 × 该时间常数（再按最大行程归一化、乘灵敏度）。
        // 100% 灵敏度下，手指以当前速度再移动 0.1s 的距离恰好对应满量程。
        private const val VELOCITY_TIME_CONSTANT = 0.1f
        // 尚未识别到采样率时，归零看门狗使用的兜底采样间隔（ms）。
        private const val MIN_SAMPLE_INTERVAL_MS = 2f
        // 手指停下后等待多少个采样间隔归零回中（至少 1 个间隔）。
        private const val VELOCITY_RESET_INTERVALS = 2f
        private const val TAG = "JoystickView"
    }
}
