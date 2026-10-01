package com.zyz4.gkme.controlled

import android.content.Context
import android.util.Log
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
    private const val REQUEST_CODE = 0x5A18

    /**
     * 连续效果的单次时长。
     *
     * 实测本机 DynamicEffect 单次时长上限约 5000ms；另外厂商 HAL 的循环重播（loop=-1）
     * 在每圈衔接处有约 200ms 断点，而连续重投递新的 effect 可无缝衔接。因此这里用一段
     * 较短效果 + PhoneHdHaptics 定时重投递的方式实现持续震动。
     */
    private const val CONTINUOUS_DURATION_MS = 4_000

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

    private var binding: ShizukuServiceBinding? = null

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        RichTapPrebaked.load(context)
        val b = ShizukuServiceBinding(
            tag = TAG,
            processNameSuffix = "gkme_haptic",
            requestCode = REQUEST_CODE,
            serviceClass = RemoteHapticService::class.java,
            onConnected = { binder ->
                val svc = IHapticService.Stub.asInterface(binder)
                service = svc
                refreshCapabilities(svc)
            },
            onDisconnected = {
                service = null
                available = false
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
    }

    fun requestPermission() {
        binding?.requestPermission()
    }

    fun ensureBound() {
        binding?.ensureBound()
    }

    /** 是否可下发 HD 效果。 */
    fun isHapticReady(): Boolean = service != null && available

    // ── 效果所有权 ──
    // 手机马达 HD 有两个消费者：游戏 rumble（PhoneHdHaptics）与音圈 PCM（HdPcmStreamer）。
    // 它们共用同一个 HapticPlayer；若一方无条件 stop() 会误杀另一方刚投递的效果（表现为
    // “调用了震动但马达启动前被关闭”）。这里给当前效果打上 token，消费者只能停自己的效果。

    private val ownerLock = Any()

    @Volatile
    private var ownerToken: Any? = null

    /** 下发一段 HE 1.0 效果。 */
    fun startPattern(json: String, loop: Int, interval: Int, amplitude: Int, freq: Int, token: Any? = null): Boolean {
        val svc = service ?: return false
        if (!available) return false
        val ok = try {
            svc.startPattern(json, loop, interval, amplitude, freq)
        } catch (t: Throwable) {
            Log.w(TAG, "startPattern 失败", t)
            false
        }
        if (ok) synchronized(ownerLock) { ownerToken = token }
        return ok
    }

    /**
     * 下发一段**使用自身参数**的 HE 1.0 效果（多事件分块，每个事件自带 Frequency 与
     * 4 点 Curve）。用无参 `start()`，不传全局 amplitude/freq，避免覆盖事件参数。
     *
     * 兼容：若 Shizuku 仍复用旧版用户服务（没有 `startEffect` 事务），回退到
     * [startPattern]，至少能出声。
     */
    fun startEffect(json: String, token: Any? = null): Boolean {
        val svc = service ?: return false
        if (!available) return false
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
        if (started) synchronized(ownerLock) { ownerToken = token }
        return started
    }

    /** 播放持续震动（游戏 rumble / 自适应扳机 / 音圈）。[amplitude] 0-255，[frequency] 0-100。
     *  单次只投递一段效果，持续由 PhoneHdHaptics 定时重投递实现（见 continuous duration 注释）。 */
    fun startContinuous(amplitude: Int, frequency: Int, token: Any? = null): Boolean = startPattern(
        RichTapHe.continuous(frequency, CONTINUOUS_DURATION_MS),
        // 单次播放；不要用 loop=-1（HAL 循环衔接有 ~200ms 断点）。
        1, 0, amplitude.coerceIn(0, 255), frequency.coerceIn(0, 100), token,
    )

    /** 播放短促点击（按键反馈）。 */
    fun playClick(strength: Int, frequency: Int, token: Any? = null): Boolean = startPattern(
        RichTapHe.click(strength, frequency),
        1, 0, 255, frequency.coerceIn(0, 100), token,
    )

    /** 无条件停止当前效果（用户关闭 HD / 硬停止）。 */
    fun stop() {
        try {
            service?.stop()
        } catch (_: Throwable) {
        }
        synchronized(ownerLock) { ownerToken = null }
    }

    /**
     * 仅当当前效果属于 [token] 时停止；否则不动，避免误杀别的消费者正在播放的效果。
     * @return 是否实际停止。
     */
    fun stopOwnedBy(token: Any?): Boolean {
        if (token != null) {
            synchronized(ownerLock) { if (ownerToken !== token) return false }
        }
        stop()
        return true
    }

    fun statusText(): String {
        val b = binding ?: return "未初始化"
        return when {
            !b.binderAlive -> "Shizuku 未运行"
            !b.permissionGranted -> "Shizuku 未授权"
            service == null -> "正在启动用户服务…"
            !available -> "本机不支持 HD 震动"
            else -> if (version.isNotEmpty()) "HD 已就绪 ($version)" else "HD 已就绪"
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
        initialized = false
    }
}
