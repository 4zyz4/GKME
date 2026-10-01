package com.zyz4.gkme.controlled

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 用户服务的“授权 + 绑定”封装（与具体业务解耦，单独成文件）。
 *
 * 负责监听 binder 存活/断开、申请权限、绑定/解绑用户服务，并把连接事件回调给调用方。
 * 调用方只需在 [onConnected] 里把 binder 转成具体接口，在 [onDisconnected] 里清空引用。
 */
class ShizukuServiceBinding(
    private val tag: String,
    private val processNameSuffix: String,
    private val requestCode: Int,
    private val serviceClass: Class<*>,
    private val onConnected: (IBinder) -> Unit,
    private val onDisconnected: () -> Unit,
) {

    /** 用户下一步需要执行的操作。 */
    enum class Action { DOWNLOAD, OPEN, REQUEST_PERMISSION, NONE }

    @Volatile
    var binderAlive: Boolean = false
        private set

    @Volatile
    var permissionGranted: Boolean = false
        private set

    /** 授权申请已被用户拒绝，调用方据此判定“授权失败”。 */
    @Volatile
    var permissionDenied: Boolean = false
        private set

    @Volatile
    var bound: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var handler: Handler? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var initialized = false
    private var permissionRequestInFlight = false

    /** 是否已自动发起过一次授权申请；未通过前不再自动重复申请（避免反复弹窗）。 */
    private var autoPrompted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return
            lastError = null
            Log.i(tag, "Shizuku 用户服务已连接")
            onConnected(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            Log.w(tag, "Shizuku 用户服务已断开")
            onDisconnected()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        binderAlive = true
        permissionDenied = false
        autoPrompted = false
        refreshPermission()
        if (permissionGranted) ensureBound()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive = false
        permissionGranted = false
        permissionDenied = false
        autoPrompted = false
        permissionRequestInFlight = false
        bound = false
        lastError = "Shizuku 已停止"
        onDisconnected()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { code, grantResult ->
            if (code != requestCode) return@OnRequestPermissionResultListener
            permissionRequestInFlight = false
            permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            if (permissionGranted) {
                permissionDenied = false
                autoPrompted = false
                lastError = null
                ensureBound()
            } else {
                permissionDenied = true
                autoPrompted = true
                lastError = "Shizuku 权限被拒绝"
            }
        }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        handler = Handler(Looper.getMainLooper())
        try {
            args = Shizuku.UserServiceArgs(
                ComponentName(app.packageName, serviceClass.name)
            )
                .daemon(false)
                .processNameSuffix(processNameSuffix)
                .debuggable(false)
                .version(1)
                .tag(tag)

            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            lastError = "Shizuku 初始化失败: ${t.message}"
            Log.e(tag, "init 失败", t)
        }
    }

    private fun refreshPermission() {
        permissionGranted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (permissionGranted) {
            permissionDenied = false
            autoPrompted = false
        }
    }

    /**
     * 申请 Shizuku 权限。
     *
     * 自动流程（[ensureBound] 等由轮询反复调用）只会真正发起一次申请，被拒绝后不再重复弹窗；
     * 用户手动点击「申请授权」时传 [force] = true 可再次申请。
     */
    fun requestPermission(force: Boolean = false) {
        handler?.post {
            try {
                if (Shizuku.isPreV11()) {
                    lastError = "Shizuku 版本过低，请升级 Shizuku"
                    return@post
                }
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = true
                    permissionDenied = false
                    autoPrompted = false
                    lastError = null
                    ensureBound()
                    return@post
                }
                if (permissionRequestInFlight) return@post
                if (autoPrompted && !force) return@post
                if (force) permissionDenied = false
                autoPrompted = true
                permissionRequestInFlight = true
                Shizuku.requestPermission(requestCode)
            } catch (t: Throwable) {
                permissionRequestInFlight = false
                lastError = "申请 Shizuku 权限失败: ${t.message}"
            }
        }
    }

    fun ensureBound() {
        handler?.post {
            val a = args ?: return@post
            if (bound) return@post
            if (!binderAlive) return@post
            if (!permissionGranted) {
                refreshPermission()
                if (!permissionGranted) {
                    requestPermission()
                    return@post
                }
            }
            try {
                Shizuku.bindUserService(a, connection)
                bound = true
            } catch (t: Throwable) {
                lastError = "绑定 Shizuku 用户服务失败: ${t.message}"
            }
        }
    }

    /** 是否已运行、已授权且已绑定用户服务（不代表具体能力就绪）。 */
    fun isBound(): Boolean = binderAlive && permissionGranted && bound

    fun unbind(remove: Boolean = false) {
        val a = args
        if (a != null && bound) {
            try {
                Shizuku.unbindUserService(a, connection, remove)
            } catch (_: Throwable) {
            }
        }
        bound = false
    }

    fun detach() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
        }
        unbind()
        initialized = false
        permissionRequestInFlight = false
        permissionDenied = false
        autoPrompted = false
    }

    /** 推断用户下一步操作：下载 / 打开 Shizuku / 申请授权 / 无需操作。 */
    fun requiredAction(context: Context): Action = when {
        !isShizukuInstalled(context) -> Action.DOWNLOAD
        !binderAlive -> Action.OPEN
        !permissionGranted -> Action.REQUEST_PERMISSION
        else -> Action.NONE
    }

    fun statusText(): String = when {
        !binderAlive -> "Shizuku 未运行"
        !permissionGranted -> "Shizuku 未授权"
        !bound -> "正在启动 Shizuku 用户服务…"
        lastError != null -> lastError!!
        else -> "已连接"
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

        fun isShizukuInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }

        /** 打开 Shizuku；未安装时回退到下载页。 */
        fun openShizuku(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return
            }
            openDownloadPage(context)
        }

        fun openDownloadPage(context: Context) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }
}
