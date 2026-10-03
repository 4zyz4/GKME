package com.zyz4.gkme

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/**
 * 应用启动耗时统计。
 *
 * 起点取进程启动时刻（[Process.getStartUptimeMillis]），终点为主界面布局首次加载完成
 * 并完成首帧绘制（通过 [ViewTreeObserver.OnPreDrawListener] 捕获），两者同在
 * [SystemClock.uptimeMillis] 时钟下，差值即启动总耗时。
 *
 * 仅记录一次：之后的布局重建（切换预设等）不会再上报。
 */
object AppStartupTracker {

    private const val TAG = "GKME_Startup"

    /** 进程启动时刻，单位 ms（uptime 时钟）。 */
    private val processStartUptimeMs: Long = runCatching {
        Process.getStartUptimeMillis().takeIf { it > 0L }
    }.getOrNull() ?: SystemClock.uptimeMillis()

    private var applicationCreatedReported = false
    private var layoutLoadPending = false
    private var layoutLoadedReported = false

    /** 在 [GkmeApp.onCreate] 调用，输出 Application 创建耗时并提前加载本类。 */
    fun markApplicationCreated() {
        if (applicationCreatedReported) return
        applicationCreatedReported = true
        Log.i(TAG, "Application.onCreate 距进程启动 ${elapsed()} ms")
    }

    /**
     * 在布局首次构建完成后调用。挂一个一次性的 pre-draw 监听，等布局真正完成
     * 首帧绘制时再上报，确保测到的是“布局加载完成”而非仅视图对象创建完成。
     */
    fun reportLayoutLoadedOnce(view: View) {
        if (layoutLoadedReported || layoutLoadPending) return
        layoutLoadPending = true
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                }
                markLayoutLoaded()
                return true
            }
        })
    }

    private fun markLayoutLoaded() {
        if (layoutLoadedReported) return
        layoutLoadedReported = true
        layoutLoadPending = false
        Log.i(TAG, "布局加载完成，启动总耗时 ${elapsed()} ms（进程启动→首帧绘制）")
    }

    private fun elapsed(): Long = SystemClock.uptimeMillis() - processStartUptimeMs
}
