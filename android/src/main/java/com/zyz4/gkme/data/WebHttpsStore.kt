package com.zyz4.gkme.data

import android.content.Context
import androidx.core.content.edit

/**
 * 网页手柄的 HTTPS 开关。
 *
 * 与 [LicenseAgreementStore] 一样使用同步 SharedPreferences：前台服务在启动
 * [com.zyz4.gkme.web.WebServer] 时需要立即知道当前传输方式，而证书生成又必须在
 * IO 线程完成，无法在首帧之前等待异步 DataStore。
 */
object WebHttpsStore {

    private const val PREFS_NAME = "web_https"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        // commit() 确保用户切换后即使进程立刻被回收，重启服务仍沿用同一模式。
        prefs(context).edit(commit = true) { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
