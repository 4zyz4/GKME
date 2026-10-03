package com.zyz4.gkme.controlled

import android.content.Context
import android.os.Process
import android.util.Log

/**
 * 运行在 Shizuku UserService 进程中的保活服务。
 *
 * 该进程以 shell(uid 2000)/root 身份运行，且被 [ShizukuServiceBinding] 以 **daemon** 模式
 * 绑定，因此即使 App 进程被国产 ROM 杀死，本进程仍会存活。基于这一点做两层保活：
 *
 * 1. **系统级加固**：把目标包名加入 Doze（电池优化）白名单、待机桶置为 `active`，并放开
 *    `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND` / `WAKE_LOCK` 等 appops，降低被 AOSP
 *    省电机制限制网络与唤醒的概率。
 * 2. **复活看门狗**：后台线程周期性 `pidof` 检查 App 主进程；一旦发现被 ROM 杀掉，就用
 *    `am start-foreground-service` 把被控端前台服务重新拉起，实现 ROM 无关的“杀不死”。
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

        /** 被控端前台服务的类名后缀；复活时据此拼出 ComponentName。 */
        private const val HOST_SERVICE_SUFFIX = ".controlled.ControlledHostService"

        /** 看门狗检查间隔。 */
        private const val CHECK_INTERVAL_MS = 5_000L

        /** 连续复活失败时的最大退避间隔。 */
        private const val MAX_CHECK_INTERVAL_MS = 60_000L

        /** 两次实际拉起 App 之间的最小间隔，避免刚被杀就高频重启。 */
        private const val RESTART_COOLDOWN_MS = 8_000L
    }

    private val watchLock = Any()

    @Volatile
    private var watching = false

    @Volatile
    private var watchPackage: String? = null

    /** 每次启动/停止看门狗自增；旧线程发现代数变化会自行退出，避免重复守护。 */
    @Volatile
    private var watchGeneration = 0

    private var watchThread: Thread? = null

    override fun enable(packageName: String): Int {
        if (!isValidPackage(packageName)) {
            Log.w(TAG, "enable 包名非法: $packageName")
            return RESULT_BAD_ARGUMENT
        }
        // Doze 白名单即「电池优化 > 未优化应用」列表，添加后同时豁免 Doze 与电池优化。
        val whitelist = runShell("cmd deviceidle whitelist +$packageName")
        // 待机桶 active 在 Android 9+ 才有；低版本命令不存在，仅记为失败而不影响整体。
        runShell("am set-standby-bucket $packageName active")
        // 放开后台运行 / 唤醒锁等 appops，部分 AOSP 省电限制会据此判定是否限制。
        runShell("cmd appops set $packageName RUN_IN_BACKGROUND allow")
        runShell("cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow")
        runShell("cmd appops set $packageName WAKE_LOCK allow")
        runShell("cmd appops set $packageName START_FOREGROUND allow")
        startWatchdog(packageName)
        Log.i(TAG, "enable pkg=$packageName whitelist=$whitelist")
        return if (whitelist == RESULT_OK) RESULT_OK else RESULT_FAILED
    }

    override fun disable(packageName: String): Int {
        if (!isValidPackage(packageName)) {
            Log.w(TAG, "disable 包名非法: $packageName")
            return RESULT_BAD_ARGUMENT
        }
        stopWatchdog()
        val whitelist = runShell("cmd deviceidle whitelist -$packageName")
        Log.i(TAG, "disable pkg=$packageName whitelist=$whitelist")
        return if (whitelist == RESULT_OK) RESULT_OK else RESULT_FAILED
    }

    override fun exitService() {
        stopWatchdog()
        // Shizuku 约定：用户服务进程不会被自动杀死，需要自行退出。
        Process.killProcess(Process.myPid())
    }

    // ── 复活看门狗 ────────────────────────────────────────────

    private fun startWatchdog(packageName: String) {
        synchronized(watchLock) {
            watchPackage = packageName
            if (watching && watchThread?.isAlive == true) return
            watching = true
            watchGeneration++
            val generation = watchGeneration
            val thread = Thread({ watchdogLoop(generation) }, "gkme-keepalive-watch")
            thread.isDaemon = true
            watchThread = thread
            thread.start()
        }
    }

    private fun stopWatchdog() {
        synchronized(watchLock) {
            watching = false
            watchPackage = null
            watchGeneration++
            watchThread?.interrupt()
            watchThread = null
        }
    }

    /**
     * 循环检查 App 主进程是否存活；被杀则尝试拉起被控端前台服务。连续失败时指数退避，
     * 成功后立即复位检查频率。
     */
    private fun watchdogLoop(generation: Int) {
        var failures = 0
        var lastRestartAt = 0L
        while (watching && generation == watchGeneration) {
            val pkg = watchPackage
            if (pkg != null) {
                if (isProcessAlive(pkg)) {
                    failures = 0
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastRestartAt >= RESTART_COOLDOWN_MS) {
                        lastRestartAt = now
                        if (restartHost(pkg)) {
                            failures = 0
                            Log.i(TAG, "已复活 $pkg")
                        } else {
                            failures = (failures + 1).coerceAtMost(6)
                            Log.w(TAG, "复活 $pkg 失败 (第 $failures 次)")
                        }
                    }
                }
            }
            val interval = (CHECK_INTERVAL_MS shl failures).coerceAtMost(MAX_CHECK_INTERVAL_MS)
            try {
                Thread.sleep(interval)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** 通过 `pidof`（回退 `pgrep`）判断 App 主进程是否存活。 */
    private fun isProcessAlive(packageName: String): Boolean {
        val (pidCode, pidOut) = runShellOutput("pidof $packageName")
        if (pidCode == 0 && pidOut.trim().isNotEmpty()) return true
        // 部分 ROM 的 toybox 未提供 pidof：用 pgrep 精确匹配主进程名回退。
        val (pgrepCode, pgrepOut) = runShellOutput("pgrep -f \"^$packageName$\"")
        return pgrepCode == 0 && pgrepOut.trim().isNotEmpty()
    }

    /**
     * 把被控端前台服务重新拉起。优先 `start-foreground-service`（对齐 App 侧的
     * `startForegroundService`）；失败则退回 `startservice`，其 `onStartCommand` 内部
     * 同样会调用 `startForeground`。
     */
    private fun restartHost(packageName: String): Boolean {
        val component = "$packageName/$packageName$HOST_SERVICE_SUFFIX"
        val (fgCode, _) = runShellOutput("/system/bin/am start-foreground-service -n $component")
        if (fgCode == 0) return true
        val (svcCode, _) = runShellOutput("/system/bin/am startservice -n $component")
        return svcCode == 0
    }

    // ── shell 执行 ────────────────────────────────────────────

    private fun isValidPackage(packageName: String): Boolean =
        PACKAGE_REGEX.matches(packageName)

    /** 在 shell 中执行一条命令并返回退出码；调用线程为其唯一使用者（看门狗线程 / Binder 线程）。 */
    private fun runShell(script: String): Int = runShellOutput(script).first

    /** 执行 shell 命令并同时取回退出码与合并后的输出。 */
    private fun runShellOutput(script: String): Pair<Int, String> = try {
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = SHELL_PATH }
            .start()
        // 先排空输出，避免命令输出填满管道导致 waitFor 卡死。
        val output = process.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        process.waitFor() to output
    } catch (t: Throwable) {
        Log.w(TAG, "命令执行失败: $script", t)
        RESULT_FAILED to ""
    }
}
