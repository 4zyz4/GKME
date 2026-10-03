package com.zyz4.gkme.controlled

import android.content.Context
import android.os.Process
import android.util.Log

/**
 * 运行在 Shizuku UserService 进程中的保活服务。
 *
 * 该进程以 shell(uid 2000)/root 身份运行，因此可以执行 `cmd` / `am` 等系统命令。
 * 通过 [enable] 把目标包名加入 Doze（电池优化）白名单并把待机桶置为 `active`，让 WiFi
 * 被控端在熄屏/后台时不被系统限制网络与唤醒；[disable] 在退出被控端时撤销白名单。
 *
 * 与 [RemoteGamepadService] / [RemoteHapticService] 一样，Shizuku v13 会优先使用带
 * [Context] 参数的构造器。
 */
class RemoteKeepAliveService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) : IKeepAliveService.Stub() {

    companion object {
        private const val TAG = "GKME_RemoteKeepAlive"

        private const val RESULT_OK = 0
        private const val RESULT_FAILED = -1
        private const val RESULT_BAD_ARGUMENT = -2

        /** 合法包名只含字母、数字、下划线与点，避免 shell 注入。 */
        private val PACKAGE_REGEX = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

        /** shell 脚本的 PATH；UserService 进程环境可能不含 /system/bin，显式补齐。 */
        private const val SHELL_PATH = "/system/bin:/system/xbin:/sbin"
    }

    override fun enable(packageName: String): Int {
        if (!isValidPackage(packageName)) {
            Log.w(TAG, "enable 包名非法: $packageName")
            return RESULT_BAD_ARGUMENT
        }
        // Doze 白名单即「电池优化 > 未优化应用」列表，添加后同时豁免 Doze 与电池优化。
        val whitelist = runShell("cmd deviceidle whitelist +$packageName")
        // 待机桶 active 在 Android 9+ 才有；低版本命令不存在，仅记为失败而不影响整体。
        val bucket = runShell("am set-standby-bucket $packageName active")
        Log.i(TAG, "enable pkg=$packageName whitelist=$whitelist bucket=$bucket")
        return if (whitelist == RESULT_OK) RESULT_OK else RESULT_FAILED
    }

    override fun disable(packageName: String): Int {
        if (!isValidPackage(packageName)) {
            Log.w(TAG, "disable 包名非法: $packageName")
            return RESULT_BAD_ARGUMENT
        }
        val whitelist = runShell("cmd deviceidle whitelist -$packageName")
        Log.i(TAG, "disable pkg=$packageName whitelist=$whitelist")
        return if (whitelist == RESULT_OK) RESULT_OK else RESULT_FAILED
    }

    override fun exitService() {
        // Shizuku 约定：用户服务进程不会被自动杀死，需要自行退出。
        Process.killProcess(Process.myPid())
    }

    private fun isValidPackage(packageName: String): Boolean =
        PACKAGE_REGEX.matches(packageName)

    /** 在 shell 中执行一条命令并返回退出码；调用线程为其唯一使用者（Binder 工作线程）。 */
    private fun runShell(script: String): Int = try {
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = SHELL_PATH }
            .start()
        // 先排空输出，避免命令输出填满管道导致 waitFor 卡死。
        process.inputStream.use { it.readBytes() }
        process.waitFor()
    } catch (t: Throwable) {
        Log.w(TAG, "命令执行失败: $script", t)
        RESULT_FAILED
    }
}
