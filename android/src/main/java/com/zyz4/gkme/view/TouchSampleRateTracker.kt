package com.zyz4.gkme.view

import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 触控采样率识别器：从相邻触摸样本的时间间隔估计屏幕触控采样率。
 *
 * 屏幕摇杆（触摸板模式）与「快速响应模式」的摇杆/线性扳机共用同一套估计与日志逻辑：
 * 采集频率与预测外推时长都随屏幕触控采样率自适应，不再使用固定 16ms。
 */
class TouchSampleRateTracker(private val tag: String) {

    /** 估计的触控采样间隔（ms）；0 表示尚未识别。 */
    var intervalMs: Float = 0f
        private set

    private var loggedRateHz = 0
    private var lastLogTimeMs = 0L

    fun reset() {
        intervalMs = 0f
        loggedRateHz = 0
        lastLogTimeMs = 0L
    }

    /**
     * 用相邻样本的间隔（ms）更新估计，并在识别到的采样率变化时输出日志。
     * 调用方需自行跳过首个样本（DOWN→首次 MOVE 的间隔不代表触控采样间隔）。
     */
    fun update(dtMs: Float) {
        val clamped = dtMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        intervalMs = if (intervalMs <= 0f) clamped
                     else intervalMs * (1f - SMOOTHING) + clamped * SMOOTHING
        logIfChanged()
    }

    /** 尚未识别时返回 [fallbackMs]，否则返回识别到的采样间隔。 */
    fun intervalOr(fallbackMs: Float): Float = if (intervalMs > 0f) intervalMs else fallbackMs

    private fun logIfChanged() {
        if (intervalMs <= 0f) return
        val rateHz = (1000f / intervalMs).roundToInt()
        val now = System.currentTimeMillis()
        if (rateHz == loggedRateHz) return
        if (now - lastLogTimeMs < LOG_THROTTLE_MS) return
        loggedRateHz = rateHz
        lastLogTimeMs = now
        Log.d(tag, "识别到触控采样率 ≈ ${rateHz}Hz（采样间隔 ${String.format(Locale.US, "%.2f", intervalMs)}ms）")
    }

    companion object {
        // 估计间隔的夹取范围（ms）与 EMA 平滑系数。
        private const val MIN_INTERVAL_MS = 2f
        private const val MAX_INTERVAL_MS = 50f
        private const val SMOOTHING = 0.25f
        // 日志时间节流，避免估计值收敛期间刷屏。
        private const val LOG_THROTTLE_MS = 1000L
    }
}
