package com.zyz4.gkme.view

import android.view.MotionEvent
import com.zyz4.gkme.model.ButtonPosition

/**
 * 手指速度估计：从触摸样本（含 [MotionEvent] 批处理中的历史样本）计算瞬时速度，
 * 再用 EMA（指数移动平均）平滑；位移按平滑后的速度积分。
 *
 * 屏幕摇杆的触摸板模式与「快速响应模式」预测、线性扳机预测、鼠标板光标位移
 * 共用同一套算法，保证同一手势在不同控件上的响应一致：
 * - 历史样本：一个事件里的所有原始样本都参与计算，不丢批处理内的位移；
 * - EMA 速度：抑制逐样本速度抖动，抬手/停下后不残留速度；
 * - 采样间隔：由同一条链路识别触控采样率，供预测外推时长与归零看门狗使用。
 */
class EmaVelocityTracker(private val tag: String) {

    /** 平滑后的速度（px/ms，方向与 MotionEvent 坐标一致：向右/向下为正）。 */
    var vx: Float = 0f
        private set
    var vy: Float = 0f
        private set

    /** 最近一次 [consumeDelta] 取出的位移（px，按平滑速度积分）。 */
    var deltaX: Float = 0f
        private set
    var deltaY: Float = 0f
        private set

    /** 触控采样率识别：与速度估计共用同一批样本间隔。 */
    val sampleRate = TouchSampleRateTracker(tag)

    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    // 是否已得到可计算间隔的样本（首个间隔只用于播种速度）。
    private var hasSample = false
    // 自上次 [consumeDelta] 起按平滑速度积分的位移。
    private var pendingDx = 0f
    private var pendingDy = 0f

    fun reset() {
        vx = 0f
        vy = 0f
        hasSample = false
        lastTime = 0L
        pendingDx = 0f
        pendingDy = 0f
        deltaX = 0f
        deltaY = 0f
        sampleRate.reset()
    }

    /**
     * 以按下点播种；不产生速度。`DOWN→首次移动` 的间隔只用于播种速度，
     * 仅在合理范围（不超过停顿阈值）内参与采样率识别。
     */
    fun start(x: Float, y: Float, time: Long) {
        reset()
        lastX = x
        lastY = y
        lastTime = time
    }

    /**
     * 只清零速度，保留采样率估计与上一次样本时间：手指短暂停住后再次移动时，
     * 下一个间隔会直接重新播种速度，不会从 0 缓慢爬升。
     */
    fun clearVelocity() {
        vx = 0f
        vy = 0f
        hasSample = false
    }

    /** 处理事件中 [pointerIndex] 手指的全部样本（历史样本 + 当前样本）。 */
    fun add(event: MotionEvent, pointerIndex: Int = 0) {
        for (h in 0 until event.historySize) {
            add(
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h),
                event.getHistoricalEventTime(h),
            )
        }
        add(event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
    }

    /** 追加一个样本（px、ms），更新 EMA 速度并累计积分位移。 */
    fun add(x: Float, y: Float, time: Long) {
        if (lastTime == 0L) {
            lastX = x
            lastY = y
            lastTime = time
            return
        }
        val dt = (time - lastTime).toFloat()
        if (dt <= 0f) {
            return
        }
        val dx = x - lastX
        val dy = y - lastY
        val instVx = dx / dt
        val instVy = dy / dt
        when {
            !hasSample -> {
                // 首个间隔（DOWN→首次移动）只用于播种：不做平滑（避免丢掉起始位移）。
                vx = instVx
                vy = instVy
                hasSample = true
                // 单样本事件的首个间隔也可能是真实采样间隔；仅在合理范围内参与识别，
                // 让归零看门狗有可用的估计值（过长的停顿间隔不计入）。
                if (dt <= RESEED_GAP_MS) {
                    sampleRate.update(dt)
                }
            }
            dt > RESEED_GAP_MS -> {
                // 停顿过久，平滑值已无参考意义：直接用当前间隔重新播种。
                vx = instVx
                vy = instVy
            }
            else -> {
                vx += (instVx - vx) * EMA_ALPHA
                vy += (instVy - vy) * EMA_ALPHA
                sampleRate.update(dt)
            }
        }
        pendingDx += vx * dt
        pendingDy += vy * dt
        lastX = x
        lastY = y
        lastTime = time
    }

    /** 取出并清空自上次调用以来累积的位移（按平滑速度积分）。 */
    fun consumeDelta() {
        deltaX = pendingDx
        deltaY = pendingDy
        pendingDx = 0f
        pendingDy = 0f
    }

    /** 外推时长（ms）：与识别到的触控采样间隔同步，未识别时退回固定帧长。 */
    fun frameMs(): Float = sampleRate.intervalOr(ButtonPosition.PREDICTION_FRAME_MS)

    companion object {
        // EMA 平滑系数：越大越跟手，越小越稳。120Hz 采样下时间常数约 12ms。
        private const val EMA_ALPHA = 0.4f
        // 相邻样本间隔超过该值（ms）视为停顿后重新开始，直接重新播种速度。
        private const val RESEED_GAP_MS = 50f
    }
}
