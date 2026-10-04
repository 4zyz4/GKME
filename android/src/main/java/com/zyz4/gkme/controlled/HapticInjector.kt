package com.zyz4.gkme.controlled

import android.content.Context
import android.util.Log
import com.zyz4.gkme.haptic.HapticArbiter
import com.zyz4.gkme.haptic.HapticSource
import com.zyz4.gkme.haptic.RichTapEngine
import com.zyz4.gkme.haptic.RichTapFrequency
import com.zyz4.gkme.haptic.RichTapHe
import com.zyz4.gkme.haptic.RichTapPrebaked

/**
 * App 侧对 HD 震动 Shizuku 用户服务（[RemoteHapticService]）的封装。
 *
 * 授权/绑定逻辑委托给独立的 [ShizukuServiceBinding]，本文件只关心能力查询与效果下发。
 * 只有 Shizuku 已运行、已授权、用户服务已绑定且本机支持 RichTap 隐藏 API 时，
 * [isHapticReady] 才为 true；否则调用方应回退到普通 Vibrator 通路。
 */
object HapticInjector {

    private const val TAG = "GKME_HapticInjector"

    /**
     * 连续效果的单次时长。
     *
     * 实测本机 DynamicEffect 单次时长上限约 5000ms；另外厂商 HAL 的循环重播（loop=-1）
     * 在每圈衔接处有约 200ms 断点，而连续重投递新的 effect 可无缝衔接。因此这里用一段
     * 较短效果 + PhoneHdHaptics 定时重投递的方式实现持续震动。
     */
    internal const val CONTINUOUS_DURATION_MS = 4_000

    @Volatile
    var service: IHapticService? = null
        private set

    /** 本机是否支持 RichTap 隐藏 API。 */
    @Volatile
    var available: Boolean = false
        private set

    /** RichTap core 版本号。 */
    @Volatile
    var version: String = ""
        private set

    /** 当前 backend 类型（对齐 SDK `PlayerType`）：0 无、1 TencentPerformer、2 RichTapPerformer。 */
    @Volatile
    var playerType: Int = 0
        private set

    /** 是否支持 type 2 的实时调参（`createHapticParameter`）。 */
    @Volatile
    var realtimeAdjust: Boolean = false
        private set

    private var binding: ShizukuServiceBinding? = null

    /** HD 是否在 app 进程内直接驱动（前台 uid），而非 Shizuku 用户服务。 */
    @Volatile
    var inProcess: Boolean = false
        private set

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        RichTapPrebaked.load(context)

        // 优先在 app 进程内直接驱动 RichTap：调用方是前台 app 的 uid，不会被系统的
        // "后台震动"策略（ignored_background）丢弃。需要 manifest 声明 richtap-api 共享库。
        val local = try {
            RemoteHapticService(context.applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "进程内 RichTap 初始化异常", t)
            null
        }
        if (local != null && local.isAvailable) {
            service = local
            refreshCapabilities(local)
            inProcess = true
            initialized = true
            Log.i(TAG, "使用 app 进程内 RichTap: playerType=$playerType version=$version")
            return
        }

        // 回退：Shizuku 用户服务（shell/root 身份）。
        val b = ShizukuServiceBinding(
            tag = TAG,
            processNameSuffix = "gkme_haptic",
            serviceClass = RemoteHapticService::class.java,
            onConnected = { binder ->
                val svc = IHapticService.Stub.asInterface(binder)
                service = svc
                refreshCapabilities(svc)
            },
            onDisconnected = {
                service = null
                available = false
                playerType = 0
                realtimeAdjust = false
            },
        )
        binding = b
        b.init(context)
        initialized = true
    }

    private fun refreshCapabilities(svc: IHapticService) {
        available = try {
            svc.isAvailable
        } catch (t: Throwable) {
            Log.w(TAG, "查询 isAvailable 失败", t)
            false
        }
        version = try {
            svc.version ?: ""
        } catch (_: Throwable) {
            ""
        }
        // 这些事务是后加的；旧版用户服务没有，会抛异常，逐个兜底。
        playerType = try {
            svc.playerType
        } catch (_: Throwable) {
            0
        }
        realtimeAdjust = try {
            svc.supportsRealtimeAdjustment()
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(force: Boolean = false) {
        binding?.requestPermission(force)
    }

    fun ensureBound() {
        binding?.ensureBound()
    }

    /** 是否可下发 HD 效果。 */
    fun isHapticReady(): Boolean = service != null && available

    // ── 效果所有权 / 优先级仲裁 ──
    // 四条通路（自适应扳机 > 音频到震动 > 游戏震动 > 按钮震动）共用同一个 HapticPlayer。
    // 由本对象集中仲裁：同一时刻只有一个来源驱动；高优先级可抢占低优先级，低优先级在更高
    // 来源播放期间不能 start（调用方据此静默而不回退系统震动）。这同时保证一方 stop() 不会
    // 误杀另一方正在播放的效果。

    private val arbiter = HapticArbiter()

    /** 当前是否允许 [source] 驱动 HD：空闲，或优先级不低于当前来源（同级可刷新）。 */
    fun canPlay(source: HapticSource): Boolean = arbiter.canPlay(source)

    /** 抢占当前来源：空闲或优先级不低于当前时成功，并成为当前来源。 */
    fun acquire(source: HapticSource): Boolean = arbiter.acquire(source)

    /** 当前来源；空闲为 null。 */
    fun owner(): HapticSource? = arbiter.current()

    /** 仅当 [source] 为当前来源时清除所有权（不停止正在播放的效果）。 */
    fun release(source: HapticSource) = arbiter.release(source)

    /** 下发一段 HE 1.0 效果。 */
    fun startPattern(json: String, loop: Int, interval: Int, amplitude: Int, freq: Int, source: HapticSource): Boolean {
        val svc = service ?: return false
        if (!available) return false
        if (!acquire(source)) return false
        val ok = try {
            svc.startPattern(json, loop, interval, amplitude, freq)
        } catch (t: Throwable) {
            Log.w(TAG, "startPattern 失败", t)
            false
        }
        if (!ok) release(source)
        return ok
    }

    /**
     * 下发一段**使用自身参数**的 HE 1.0 效果（多事件分块，每个事件自带 Frequency 与
     * 4 点 Curve）。用无参 `start()`，不传全局 amplitude/freq，避免覆盖事件参数。
     *
     * 兼容：若 Shizuku 仍复用旧版用户服务（没有 `startEffect` 事务），回退到
     * [startPattern]，至少能出声。
     */
    fun startEffect(json: String, source: HapticSource): Boolean {
        val svc = service ?: return false
        if (!available) return false
        if (!acquire(source)) return false
        val ok = try {
            svc.startEffect(json)
        } catch (t: Throwable) {
            Log.w(TAG, "startEffect 失败，回退 startPattern", t)
            false
        }
        val started = if (ok) {
            true
        } else {
            try {
                svc.startPattern(json, 1, 0, 255, RichTapFrequency.HE_AT_RESONANCE)
            } catch (t: Throwable) {
                Log.w(TAG, "startPattern 回退失败", t)
                false
            }
        }
        if (!started) release(source)
        return started
    }

    /**
     * type 2 专用：实时调整当前效果的全局强度/频率（`createHapticParameter`），无需 stop+start。
     * [amplitude] 0-255 会换算成引擎的 0-100 强度。仅当 [realtimeAdjust] 为 true 时有效。
     */
    fun updateParameter(amplitude: Int, frequency: Int): Boolean {
        val svc = service ?: return false
        if (!available || !realtimeAdjust) return false
        val intensity = (amplitude.coerceIn(0, 255) * 100 / 255)
        return try {
            svc.updateParameter(intensity, frequency.coerceIn(0, 100))
        } catch (t: Throwable) {
            Log.w(TAG, "updateParameter 失败", t)
            false
        }
    }

    /** 播放持续震动（游戏 rumble / 自适应扳机 / 音圈）。[amplitude] 0-255，[frequency] 0-100。
     *  单次只投递一段效果，持续由 PhoneHdHaptics 定时重投递实现（见 continuous duration 注释）。 */
    fun startContinuous(amplitude: Int, frequency: Int, source: HapticSource): Boolean = startPattern(
        RichTapHe.continuous(frequency, CONTINUOUS_DURATION_MS),
        // 单次播放；不要用 loop=-1（HAL 循环衔接有 ~200ms 断点）。
        1, 0, RichTapEngine.compensate255(amplitude, frequency), frequency.coerceIn(0, 100), source,
    )

    /** 播放短促点击（按键反馈）。[strength] 按频率做幅度-频率补偿后写入事件强度。 */
    fun playClick(strength: Int, frequency: Int, source: HapticSource): Boolean = startPattern(
        RichTapHe.click(RichTapEngine.compensate255(strength, frequency), frequency),
        1, 0, 255, frequency.coerceIn(0, 100), source,
    )

    /** 无条件停止当前效果（用户关闭 HD / 硬停止）。 */
    fun stop() {
        try {
            service?.stop()
        } catch (_: Throwable) {
        }
        arbiter.clear()
    }

    /**
     * 仅当当前来源为 [source] 时停止；否则不动，避免误杀别的来源正在播放的效果。
     * @return 是否实际停止。
     */
    fun stopOwnedBy(source: HapticSource): Boolean {
        if (arbiter.current() !== source) return false
        stop()
        return true
    }

    fun statusText(): String {
        if (inProcess) {
            return if (available) {
                val type = when (playerType) {
                    2 -> "type2 RichTap"
                    1 -> "type1 Tencent"
                    else -> "type?"
                }
                "HD 已就绪 (进程内 $type)"
            } else {
                "本机不支持 HD 震动"
            }
        }
        val b = binding ?: return "未初始化"
        return when {
            !b.binderAlive -> "Shizuku 未运行"
            !b.permissionGranted -> "Shizuku 未授权"
            service == null -> if (b.bindFailed) {
                ShizukuServiceBinding.BIND_FAILED_MESSAGE
            } else {
                "正在启动用户服务…"
            }
            !available -> "本机不支持 HD 震动"
            else -> {
                val type = when (playerType) {
                    2 -> "type2 RichTap"
                    1 -> "type1 Tencent"
                    else -> "type?"
                }
                val v = version.ifEmpty { type }
                "HD 已就绪 ($v)"
            }
        }
    }

    fun requiredAction(context: Context): ShizukuServiceBinding.Action =
        binding?.requiredAction(context) ?: ShizukuServiceBinding.Action.NONE

    fun lastErrorMessage(): String? = binding?.lastError

    fun destroy() {
        binding?.detach()
        binding = null
        service = null
        available = false
        playerType = 0
        realtimeAdjust = false
        inProcess = false
        initialized = false
    }
}
