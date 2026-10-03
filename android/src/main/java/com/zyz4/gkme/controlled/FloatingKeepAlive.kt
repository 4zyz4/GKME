package com.zyz4.gkme.controlled

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 悬浮窗保活：被控端运行时在屏幕上挂一个 1×1 像素、完全透明且不可触摸的悬浮窗。
 *
 * 只要本进程持有一个系统级悬浮窗，系统就会把它视为“有可见窗口”的进程，
 * 从而在后台/熄屏时更不容易被内存或省电策略回收（国产 ROM 尤为如此）。
 *
 * 该方法不强制，由设置里的「悬浮窗保活」开关控制；需要 [Settings.canDrawOverlays] 权限，
 * 未授权时 [start] 会直接跳过，由界面引导用户授权。
 */
object FloatingKeepAlive {

    private const val TAG = "GKME_FloatingKeepAlive"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var view: View? = null

    /** 是否已获得“显示在其他应用上层”权限。 */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)

    fun isActive(): Boolean = view != null

    /** 开启悬浮窗保活；需 [canDrawOverlays] 为 true，否则记录日志并放弃。 */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!canDrawOverlays(app)) {
            Log.w(TAG, "缺少悬浮窗权限，跳过悬浮窗保活")
            return
        }
        mainHandler.post {
            if (view != null) return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val v = View(app)
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            try {
                wm.addView(v, params)
                view = v
                Log.i(TAG, "悬浮窗保活已生效")
            } catch (t: Throwable) {
                Log.w(TAG, "添加保活悬浮窗失败: ${t.message}")
            }
        }
    }

    /** 关闭悬浮窗保活并移除窗口。 */
    fun stop() {
        mainHandler.post {
            val v = view ?: return@post
            view = null
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            try {
                wm?.removeView(v)
                Log.i(TAG, "悬浮窗保活已停止")
            } catch (t: Throwable) {
                Log.w(TAG, "移除保活悬浮窗失败: ${t.message}")
            }
        }
    }
}
