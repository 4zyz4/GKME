package com.zyz4.gkme.controlled

import android.content.Context
import android.util.Log
import com.zyz4.gkme.input.SdlNative
import com.zyz4.gkme.input.VirtualGamepad
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.proto.GamepadInput

/**
 * App 侧对 Shizuku UserService 的封装：负责把虚拟手柄状态转发到用户服务进程。
 *
 * 授权/绑定的通用逻辑在 [ShizukuServiceBinding] 中（单独文件）；本文件只保留手柄相关状态。
 * 只有在 Shizuku 运行且已授权、并且用户服务成功打开 /dev/uinput 后，[ensureReady] 才返回 true。
 */
object GamepadInjector {

    private const val TAG = "GKME_GamepadInjector"

    const val SHIZUKU_PACKAGE = ShizukuServiceBinding.SHIZUKU_PACKAGE
    const val SHIZUKU_DOWNLOAD_URL = ShizukuServiceBinding.SHIZUKU_DOWNLOAD_URL

    @Volatile
    var service: IGamepadService? = null
        private set

    @Volatile
    private var created = false

    @Volatile
    private var lastError: String? = null

    /** 当前虚拟手柄的配置，用于判断是否需要重建。 */
    @Volatile
    private var rumbleEnabled = true

    @Volatile
    private var mouseEnabled = true

    /** 当前虚拟手柄后端（0 = uinput，1 = uhid）与 uhid 身份（1 = DS4，2 = DualSense，3 = Switch Pro）。 */
    @Volatile
    private var backend = 0

    @Volatile
    private var profile = 1

    /** 虚拟键盘/鼠标的创建状态；虚拟手柄创建成功后立即创建。 */
    @Volatile
    private var keyboardCreated = false

    @Volatile
    private var keyboardFailed = false

    @Volatile
    private var mouseCreated = false

    @Volatile
    private var mouseFailed = false

    private var binding: ShizukuServiceBinding? = null

    /** 根据当前环境推断用户下一步需要执行的操作。 */
    enum class Action { DOWNLOAD, OPEN, REQUEST_PERMISSION, NONE }

    val binderAlive: Boolean get() = binding?.binderAlive == true
    val permissionGranted: Boolean get() = binding?.permissionGranted == true
    val permissionDenied: Boolean get() = binding?.permissionDenied == true

    /** 用户服务长时间无法连接（疑似 Shizuku 服务状态异常，需重启手机）。 */
    val bindFailed: Boolean get() = binding?.bindFailed == true

    @Synchronized
    fun init(context: Context) {
        if (binding != null) return
        val b = ShizukuServiceBinding(
            tag = TAG,
            processNameSuffix = "gkme_remote_input",
            serviceClass = RemoteGamepadService::class.java,
            onConnected = { binder ->
                service = IGamepadService.Stub.asInterface(binder)
                setCreated(false)
                lastError = null
            },
            onDisconnected = {
                service = null
                setCreated(false)
                resetKeyboardMouse()
            },
        )
        binding = b
        b.init(context)
    }

    fun requestPermission(force: Boolean = false) {
        binding?.requestPermission(force)
    }

    /**
     * 同步当前设置中的虚拟手柄类型（由 ConnectionManager 在设置变化时调用）。
     *
     * 若虚拟手柄当前已创建，会立即销毁并按新配置重建，保证切换设备类型后及时生效，
     * 无需等到下次 [ensureReady]。
     */
    @Synchronized
    fun configure(backendId: Int, profileId: Int) {
        if (backend == backendId && profile == profileId) return
        backend = backendId
        profile = profileId
        val svc = service
        if (!created || svc == null) return
        release()
        try {
            val r = svc.create(backend, profile, rumbleEnabled)
            if (r == 0) {
                setCreated(true)
                createKeyboardMouse(svc)
                lastError = null
            } else {
                lastError = "创建虚拟手柄失败 (code=$r)，可能需要以 root 启动 Shizuku"
            }
        } catch (t: Throwable) {
            setCreated(false)
            lastError = "创建虚拟手柄异常: ${t.message}"
        }
    }

    fun ensureBound() {
        binding?.ensureBound()
    }

    /**
     * 确保虚拟手柄已就绪。若 Shizuku 未运行/未授权/未绑定，则返回 false 并触发相应流程，
     * 调用方可稍后重试。
     *
     * @param rumble 是否把虚拟手柄的震动数据转发回控制端/手机。FF 能力始终暴露（系统
     *   与游戏仍视其为带震动的设备）；本机模式传 false 以忽略震动数据，避免手机马达
     *   与虚拟手柄之间形成死循环。
     * @param mouse 是否创建虚拟鼠标。本机模式下必须为 false：虚拟鼠标会与屏幕触摸
     *   输入冲突。
     */
    @Synchronized
    fun ensureReady(
        rumble: Boolean = true,
        mouse: Boolean = true,
        backendId: Int = backend,
        profileId: Int = profile,
    ): Boolean {
        val b = binding
        if (b == null || !b.binderAlive) {
            lastError = "Shizuku 未运行"
            return false
        }
        if (!b.permissionGranted) {
            if (b.permissionDenied) {
                lastError = b.lastError ?: "Shizuku 权限被拒绝"
            }
            b.requestPermission()
            return false
        }
        val svc = service
        if (svc == null) {
            lastError = "正在启动 Shizuku 用户服务…"
            b.ensureBound()
            return false
        }
        if (created) {
            if (rumbleEnabled == rumble && mouseEnabled == mouse &&
                backend == backendId && profile == profileId
            ) {
                return true
            }
            // 配置发生变化（例如切换后端/身份、从被控端切到本机模式）：销毁后按新配置重建。
            release()
        }
        rumbleEnabled = rumble
        mouseEnabled = mouse
        backend = backendId
        profile = profileId
        return try {
            val r = svc.create(backendId, profileId, rumble)
            if (r == 0) {
                setCreated(true)
                createKeyboardMouse(svc)
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
        setCreated(false)
        resetKeyboardMouse()
    }

    /**
     * 更新虚拟手柄的创建状态，并同步“隐藏 GKME 自身虚拟手柄”的开关。
     *
     * SDL 会改写设备名，且 uhid 手柄复用真实手柄的 vendor/product，所以排除只能按
     * vendor/product，且仅在虚拟手柄确实运行时启用，避免隐藏同型号的真实手柄。
     */
    private fun setCreated(value: Boolean) {
        created = value
        try {
            VirtualGamepad.virtualGamepadActive = value
            SdlNative.nativeSetVirtualGamepadExclusion(value)
        } catch (_: Throwable) {
        }
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
                input.gyroX,
                input.gyroY,
                input.gyroZ,
                input.accelX,
                input.accelY,
                input.accelZ,
                buildTouches(input),
            )
        } catch (_: Throwable) {
        }
        updateKeyboard(svc, input)
        if (mouseEnabled) updateMouse(svc, input)
    }

    /**
     * 虚拟手柄创建成功后立即创建虚拟键盘与（可选）虚拟鼠标，避免首次输入到达时
     * 才创建造成的延迟。创建失败则本次会话不再重试（与 GKME-Windows 的
     * [MarkKeyboardMouseFailed] 行为一致）。
     */
    private fun createKeyboardMouse(svc: IGamepadService) {
        if (!keyboardCreated && !keyboardFailed) {
            val r = try {
                svc.createKeyboard()
            } catch (_: Throwable) {
                -1
            }
            if (r == 0) {
                keyboardCreated = true
            } else {
                keyboardFailed = true
            }
        }
        if (mouseEnabled && !mouseCreated && !mouseFailed) {
            val r = try {
                svc.createMouse()
            } catch (_: Throwable) {
                -1
            }
            if (r == 0) {
                mouseCreated = true
            } else {
                mouseFailed = true
            }
        }
    }

    /** 转发一帧键盘全量状态。 */
    private fun updateKeyboard(svc: IGamepadService, input: GamepadInput) {
        if (!keyboardCreated) return
        try {
            svc.updateKeyboard(input.keyboardModifiers, input.pressedScanCodesList.toIntArray())
        } catch (_: Throwable) {
        }
    }

    /** 转发一帧鼠标状态（相对位移 + 滚轮 + 按键）。 */
    private fun updateMouse(svc: IGamepadService, input: GamepadInput) {
        if (!mouseCreated) return
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
        val b = binding ?: return "Shizuku 未运行"
        return when {
            !b.binderAlive -> "Shizuku 未运行"
            !b.permissionGranted ->
                if (b.permissionDenied) (b.lastError ?: "Shizuku 权限被拒绝") else "Shizuku 未授权"
            service == null -> if (b.bindFailed) {
                ShizukuServiceBinding.BIND_FAILED_MESSAGE
            } else {
                "正在启动 Shizuku 用户服务…"
            }
            lastError != null -> lastError!!
            created -> "虚拟手柄已就绪"
            else -> "正在创建虚拟手柄…"
        }
    }

    fun lastErrorMessage(): String? = lastError

    /** 推断用户下一步操作：下载 / 打开 Shizuku / 申请授权 / 无需操作。 */
    fun requiredAction(context: Context): Action = when (binding?.requiredAction(context)) {
        ShizukuServiceBinding.Action.DOWNLOAD -> Action.DOWNLOAD
        ShizukuServiceBinding.Action.OPEN -> Action.OPEN
        ShizukuServiceBinding.Action.REQUEST_PERMISSION -> Action.REQUEST_PERMISSION
        else -> Action.NONE
    }

    fun isShizukuInstalled(context: Context): Boolean =
        ShizukuServiceBinding.isShizukuInstalled(context)

    /** 打开 Shizuku；未安装时回退到下载页。 */
    fun openShizuku(context: Context) = ShizukuServiceBinding.openShizuku(context)

    fun openDownloadPage(context: Context) = ShizukuServiceBinding.openDownloadPage(context)

    fun destroy() {
        binding?.detach()
        binding = null
        service = null
        setCreated(false)
        rumbleEnabled = true
        mouseEnabled = true
        resetKeyboardMouse()
    }

    /**
     * 把 [GamepadInput] 的触摸板数据编码为 native 需要的格式：最多 2 个触点，
     * 每点 4 个 int [id, x, y, active]（x/y 为 0..1919/0..942）。仅 DS4/DualSense
     * 使用该数据。
     */
    private fun buildTouches(input: GamepadInput): IntArray {
        val list = input.touchesList
        val arr = IntArray(8)
        for (i in 0 until minOf(2, list.size)) {
            val tp = list[i]
            arr[i * 4] = tp.id
            arr[i * 4 + 1] = tp.x
            arr[i * 4 + 2] = tp.y
            arr[i * 4 + 3] = if (tp.active) 1 else 0
        }
        return arr
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
        // 触摸板点击不属于 XInput，用保留位 bit17（0x20000）透传给 native 打包。
        if (b and GamepadState.TOUCHPAD_CLICK != 0) x = x or 0x20000
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
