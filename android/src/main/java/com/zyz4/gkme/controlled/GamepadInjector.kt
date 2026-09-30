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
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.proto.GamepadInput
import rikka.shizuku.Shizuku

/**
 * App 侧对 Shizuku UserService 的封装：负责权限申请、绑定用户服务，并转发虚拟手柄状态。
 *
 * 只有在 Shizuku 运行且已授权、并且用户服务成功打开 /dev/uinput 后，[ensureReady] 才返回 true。
 */
object GamepadInjector {

    private const val TAG = "GKME_GamepadInjector"

    /** Shizuku 权限申请码。 */
    const val REQUEST_CODE = 0x5A17
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

    @Volatile
    var binderAlive: Boolean = false
        private set

    @Volatile
    var permissionGranted: Boolean = false
        private set

    @Volatile
    var service: IGamepadService? = null
        private set

    @Volatile
    private var created = false

    @Volatile
    private var lastError: String? = null

    private var appContext: Context? = null
    private var mainHandler: Handler? = null
    private var args: Shizuku.UserServiceArgs? = null

    @Volatile
    private var bound = false

    private var initialized = false

    @Volatile
    private var permissionRequestInFlight = false

    /** 虚拟键盘/鼠标的懒创建状态；仅在收到对应输入时创建。 */
    @Volatile
    private var keyboardCreated = false

    @Volatile
    private var keyboardFailed = false

    @Volatile
    private var mouseCreated = false

    @Volatile
    private var mouseFailed = false

    /** 根据当前环境推断用户下一步需要执行的操作。 */
    enum class Action { DOWNLOAD, OPEN, REQUEST_PERMISSION, NONE }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IGamepadService.Stub.asInterface(binder)
            created = false
            lastError = null
            Log.i(TAG, "Shizuku 用户服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            created = false
            bound = false
            resetKeyboardMouse()
            Log.w(TAG, "Shizuku 用户服务已断开")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        binderAlive = true
        refreshPermission()
        if (permissionGranted) {
            ensureBound()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive = false
        permissionGranted = false
        service = null
        created = false
        bound = false
        resetKeyboardMouse()
        lastError = "Shizuku 已停止"
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        permissionRequestInFlight = false
        permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) {
            lastError = null
            ensureBound()
        } else {
            lastError = "Shizuku 权限被拒绝"
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        mainHandler = Handler(Looper.getMainLooper())
        try {
            args = Shizuku.UserServiceArgs(
                ComponentName(app.packageName, RemoteGamepadService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("gkme_remote_input")
                .debuggable(false)
                .version(1)
                .tag("GKME_RemoteGamepad")

            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            lastError = "Shizuku 初始化失败: ${t.message}"
            Log.e(TAG, "init 失败", t)
        }
    }

    private fun refreshPermission() {
        permissionGranted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        mainHandler?.post {
            try {
                if (Shizuku.isPreV11()) {
                    lastError = "Shizuku 版本过低，请升级 Shizuku"
                    return@post
                }
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = true
                    ensureBound()
                    return@post
                }
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    lastError = "Shizuku 权限已被拒绝，请在 Shizuku 中手动授权"
                    return@post
                }
                if (permissionRequestInFlight) return@post
                permissionRequestInFlight = true
                Shizuku.requestPermission(REQUEST_CODE)
            } catch (t: Throwable) {
                permissionRequestInFlight = false
                lastError = "申请 Shizuku 权限失败: ${t.message}"
            }
        }
    }

    fun ensureBound() {
        mainHandler?.post {
            val a = args ?: return@post
            if (service != null || bound) return@post
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

    /**
     * 确保虚拟手柄已就绪。若 Shizuku 未运行/未授权/未绑定，则返回 false 并触发相应流程，
     * 调用方可稍后重试。
     */
    fun ensureReady(): Boolean {
        if (!binderAlive) {
            lastError = "Shizuku 未运行"
            return false
        }
        refreshPermission()
        if (!permissionGranted) {
            lastError = "正在申请 Shizuku 权限…"
            requestPermission()
            return false
        }
        val svc = service
        if (svc == null) {
            lastError = "正在启动 Shizuku 用户服务…"
            ensureBound()
            return false
        }
        if (created) return true
        return try {
            val r = svc.create(true)
            if (r == 0) {
                created = true
                lastError = null
                true
            } else {
                lastError = "创建虚拟手柄失败 (code=$r)，可能需要以 root 启动 Shizuku"
                false
            }
        } catch (t: Throwable) {
            lastError = "创建虚拟手柄异常: ${t.message}"
            false
        }
    }

    fun isReady(): Boolean = binderAlive && permissionGranted && service != null && created

    fun release() {
        val svc = service
        if (svc != null) {
            if (keyboardCreated || mouseCreated) {
                try {
                    svc.releaseKeyboardMouse()
                } catch (_: Throwable) {
                }
            }
            if (created) {
                try {
                    svc.release()
                } catch (_: Throwable) {
                }
            }
        }
        created = false
        resetKeyboardMouse()
    }

    /** 重置虚拟键盘/鼠标的懒创建状态（服务断开或释放后调用）。 */
    private fun resetKeyboardMouse() {
        keyboardCreated = false
        keyboardFailed = false
        mouseCreated = false
        mouseFailed = false
    }

    fun update(input: GamepadInput) {
        val svc = service ?: return
        if (!created) return
        try {
            svc.update(
                toXInputButtons(input),
                input.leftTrigger,
                input.rightTrigger,
                input.leftStickX,
                input.leftStickY,
                input.rightStickX,
                input.rightStickY,
            )
        } catch (_: Throwable) {
        }
        updateKeyboard(svc, input)
        updateMouse(svc, input)
    }

    /**
     * 转发一帧键盘全量状态。首次出现按键/修饰键时懒创建虚拟键盘；创建失败则
     * 本次会话不再重试（与 GKME-Windows 的 [MarkKeyboardMouseFailed] 行为一致）。
     */
    private fun updateKeyboard(svc: IGamepadService, input: GamepadInput) {
        val hasKeys = input.pressedScanCodesCount > 0 || input.keyboardModifiers != 0
        if (!keyboardCreated) {
            if (!hasKeys || keyboardFailed) return
            val r = try {
                svc.createKeyboard()
            } catch (_: Throwable) {
                -1
            }
            if (r != 0) {
                keyboardFailed = true
                return
            }
            keyboardCreated = true
        }
        try {
            svc.updateKeyboard(input.keyboardModifiers, input.pressedScanCodesList.toIntArray())
        } catch (_: Throwable) {
        }
    }

    /**
     * 转发一帧鼠标状态（相对位移 + 滚轮 + 按键）。首次出现鼠标活动时懒创建
     * 虚拟鼠标；创建失败则本次会话不再重试。
     */
    private fun updateMouse(svc: IGamepadService, input: GamepadInput) {
        val active = input.mouseDx != 0 || input.mouseDy != 0 || input.mouseWheel != 0 ||
            input.mousePan != 0 || input.mouseButtons != 0
        if (!mouseCreated) {
            if (!active || mouseFailed) return
            val r = try {
                svc.createMouse()
            } catch (_: Throwable) {
                -1
            }
            if (r != 0) {
                mouseFailed = true
                return
            }
            mouseCreated = true
        }
        try {
            svc.updateMouse(
                input.mouseDx,
                input.mouseDy,
                input.mouseWheel,
                input.mousePan,
                input.mouseButtons,
            )
        } catch (_: Throwable) {
        }
    }

    /** 返回高 16 位左马达、低 16 位右马达（0..65535），无震动为 0。 */
    fun pumpRumble(): Long {
        val svc = service ?: return 0L
        if (!created) return 0L
        return try {
            svc.pumpRumble()
        } catch (_: Throwable) {
            0L
        }
    }

    fun statusText(): String {
        return when {
            !binderAlive -> "Shizuku 未运行"
            !permissionGranted -> "Shizuku 未授权"
            service == null -> "正在启动 Shizuku 用户服务…"
            lastError != null -> lastError!!
            created -> "虚拟手柄已就绪"
            else -> "正在创建虚拟手柄…"
        }
    }

    fun lastErrorMessage(): String? = lastError

    /** 推断用户下一步操作：下载 / 打开 Shizuku / 申请授权 / 无需操作。 */
    fun requiredAction(context: Context): Action = when {
        !isShizukuInstalled(context) -> Action.DOWNLOAD
        !binderAlive -> Action.OPEN
        !permissionGranted -> Action.REQUEST_PERMISSION
        else -> Action.NONE
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
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

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
        }
        release()
        val a = args
        if (a != null && bound) {
            try {
                Shizuku.unbindUserService(a, connection, true)
            } catch (_: Throwable) {
            }
        }
        bound = false
        service = null
        initialized = false
    }

    /** 把 GKME 的按键位布局翻译成 XInput wButtons 掩码（含十字键低 4 位）。 */
    private fun toXInputButtons(input: GamepadInput): Int {
        val b = input.buttons.toInt()
        var x = 0
        if (b and GamepadState.A != 0) x = x or 0x1000
        if (b and GamepadState.B != 0) x = x or 0x2000
        if (b and GamepadState.X != 0) x = x or 0x4000
        if (b and GamepadState.Y != 0) x = x or 0x8000
        if (b and GamepadState.LB != 0) x = x or 0x0100
        if (b and GamepadState.RB != 0) x = x or 0x0200
        if (b and GamepadState.SELECT != 0) x = x or 0x0020
        if (b and GamepadState.START != 0) x = x or 0x0010
        if (b and GamepadState.L3 != 0) x = x or 0x0040
        if (b and GamepadState.R3 != 0) x = x or 0x0080
        if (b and GamepadState.HOME != 0) x = x or 0x0400
        // 兼容把十字键编码进 buttons bit12..15 的输入源。
        if (b and GamepadState.DPAD_BIT_UP != 0) x = x or 0x1
        if (b and GamepadState.DPAD_BIT_DOWN != 0) x = x or 0x2
        if (b and GamepadState.DPAD_BIT_LEFT != 0) x = x or 0x4
        if (b and GamepadState.DPAD_BIT_RIGHT != 0) x = x or 0x8
        when (input.dpad) {
            GamepadState.DPAD_UP -> x = x or 0x1
            GamepadState.DPAD_DOWN -> x = x or 0x2
            GamepadState.DPAD_LEFT -> x = x or 0x4
            GamepadState.DPAD_RIGHT -> x = x or 0x8
            GamepadState.DPAD_UP_LEFT -> x = x or 0x5
            GamepadState.DPAD_UP_RIGHT -> x = x or 0x9
            GamepadState.DPAD_DOWN_LEFT -> x = x or 0x6
            GamepadState.DPAD_DOWN_RIGHT -> x = x or 0xA
        }
        return x
    }
}
