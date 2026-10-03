package com.zyz4.gkme.controlled

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App 侧对保活 Shizuku 用户服务（[RemoteKeepAliveService]）的封装。
 *
 * 授权/绑定逻辑委托给独立的 [ShizukuServiceBinding]；本文件只维护「期望保活的应用包名」
 * 与当前是否已生效。调用方只需在进入被控端前台服务时 [activate]，退出时 [deactivate]，
 * 由 [statusText] 展示状态。
 *
 * 所有会触发 Binder 调用的启用动作都放在内部 IO scope 执行，避免阻塞主线程。
 */
object KeepAliveInjector {

    private const val TAG = "GKME_KeepAliveInjector"

    private const val RESULT_OK = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var binding: ShizukuServiceBinding? = null

    @Volatile
    private var service: IKeepAliveService? = null

    @Volatile
    private var initialized = false

    /** 期望被保活的包名；为空表示未启用（退出被控端后清空）。 */
    @Volatile
    private var desiredPackage: String? = null

    /** 最近一次成功启用的包名，用于 [deactivate] 撤销白名单。 */
    @Volatile
    private var activatedPackage: String? = null

    @Volatile
    private var active = false

    /** 正在执行 enable，防止并发重复调用。 */
    @Volatile
    private var applying = false

    @Volatile
    private var lastError: String? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val b = ShizukuServiceBinding(
            tag = TAG,
            processNameSuffix = "gkme_keepalive",
            serviceClass = RemoteKeepAliveService::class.java,
            onConnected = { binder ->
                service = IKeepAliveService.Stub.asInterface(binder)
                scope.launch { ensureActive() }
            },
            onDisconnected = {
                service = null
                active = false
                activatedPackage = null
            },
        )
        binding = b
        b.init(context)
    }

    /** 跟随被控端前台服务启用保活；Shizuku 未就绪时会在绑定/授权完成后自动补上。 */
    fun activate(packageName: String) {
        desiredPackage = packageName
        binding?.ensureBound()
        scope.launch { ensureActive() }
    }

    /** 退出被控端时撤销保活白名单。 */
    fun deactivate() {
        desiredPackage = null
        val pkg = activatedPackage
        activatedPackage = null
        val wasActive = active
        active = false
        if (wasActive && pkg != null) {
            scope.launch {
                try {
                    service?.disable(pkg)
                } catch (t: Throwable) {
                    Log.w(TAG, "撤销保活失败: ${t.message}")
                }
            }
        }
    }

    fun requestPermission(force: Boolean = false) {
        binding?.requestPermission(force)
    }

    fun ensureBound() {
        binding?.ensureBound()
    }

    fun isActive(): Boolean = active

    /**
     * 若期望启用但尚未生效，则尝试启用。可在 IO 线程重复调用以兜底重试；内部对并发做去重。
     */
    @Synchronized
    fun ensureActive() {
        val pkg = desiredPackage ?: return
        if (active || applying) return
        val svc = service
        if (svc == null) {
            binding?.ensureBound()
            return
        }
        applying = true
        val result = try {
            svc.enable(pkg)
        } catch (t: Throwable) {
            lastError = "启用保活异常: ${t.message}"
            -1
        } finally {
            applying = false
        }
        if (result == RESULT_OK) {
            active = true
            activatedPackage = pkg
            lastError = null
        } else {
            lastError = "启用保活失败 (code=$result)"
        }
    }

    fun statusText(): String {
        if (!initialized) return "保活：未初始化"
        val b = binding ?: return "保活：未初始化"
        return when {
            !b.binderAlive -> "保活：Shizuku 未运行"
            !b.permissionGranted -> "保活：Shizuku 未授权"
            service == null -> "保活：正在启动保活服务…"
            active -> "Shizuku 保活已生效"
            desiredPackage == null -> "保活：未启用"
            lastError != null -> "保活：$lastError"
            else -> "保活：正在启用…"
        }
    }

    fun requiredAction(context: Context): ShizukuServiceBinding.Action =
        binding?.requiredAction(context) ?: ShizukuServiceBinding.Action.NONE

    fun destroy() {
        binding?.detach()
        binding = null
        service = null
        initialized = false
        desiredPackage = null
        activatedPackage = null
        active = false
        applying = false
        lastError = null
    }
}
