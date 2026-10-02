package com.zyz4.gkme.controlled

import android.content.Context
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.zyz4.gkme.haptic.HeJson
import com.zyz4.gkme.haptic.RichTapRawCodec
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 运行在 Shizuku UserService 进程中的高清震动（RichTap 动态效果）服务。
 *
 * 该进程以 shell(uid 2000)/root 身份运行，不受 hidden API 限制，因此可以反射调用
 * 框架隐藏类。按优先级选 backend（对齐 RichTap ASDK `RichTapUtils.init`）：
 * - **type 2 `RichTapPerformer`**：`richtap.os.PhonyVibrationEffect` / `android.os.RichTapVibrationEffect`
 *   的 `createPatternHeWithParam(int[])`，支持实时调参（`createHapticParameter`）。
 * - **type 1 `TencentPerformer`**：`android.os.DynamicEffect` + `android.os.HapticPlayer`。
 * - 两者都不可用时 `available=false`，由调用方回退普通 `Vibrator`。
 *
 * type 2 的 raw int[] 编码见 [RichTapRawCodec]（逆向自 SDK `base.b.a`）。
 * 与 [RemoteGamepadService] 一样，Shizuku v13 会优先使用带 [Context] 参数的构造器。
 */
class RemoteHapticService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) : IHapticService.Stub() {

    companion object {
        private const val TAG = "GKME_RemoteHaptic"
        private const val DEFAULT_PACKAGE = "com.android.shell"

        const val PLAYER_NONE = 0
        const val PLAYER_TENCENT = 1
        const val PLAYER_RICHTAP = 2

        /** type 2 实时调参（createHapticParameter）的发送者组 id。 */
        private const val SENDER_GID = 1

        // createHapticParameter 头部标签（SDK `d.a` 常量）：256=发送者块，513=强度，514=频率。
        private const val PARAM_TAG_SENDER = 256
        private const val PARAM_TAG_INTENSITY = 513
        private const val PARAM_TAG_FREQUENCY = 514
    }

    private val lock = Any()

    @Volatile
    private var available = false

    @Volatile
    private var playerType = PLAYER_NONE

    @Volatile
    private var version: String = ""

    @Volatile
    private var packageName: String = DEFAULT_PACKAGE

    // ── type 1 资源 ──
    @Volatile
    private var player: Any? = null
    private var dynamicEffectClass: Class<*>? = null
    private var startMethod: Method? = null
    private var startNoArgMethod: Method? = null
    private var stopMethod: Method? = null
    private var packageField: Field? = null

    // ── type 2 资源 ──
    private var createPatternHeWithParam: Method? = null
    private var createHapticParameter: Method? = null
    private var createPatternHeParameter: Method? = null
    private var vibrator: Vibrator? = null
    private var coreMajor = -1
    private var coreMinor = -1

    /** 开关：逆序交换左右马达（暂未接入设置，保留与 SDK 对齐）。 */
    @Volatile
    private var swapVibrationIndex = false

    private val pid = Process.myPid()
    private var senderSeq = 0

    init {
        synchronized(lock) {
            packageName = context?.packageName ?: DEFAULT_PACKAGE
            vibrator = try {
                context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (_: Throwable) {
                null
            }
            if (!tryInitType2()) {
                tryInitType1()
            }
            Log.i(TAG, "init: playerType=$playerType available=$available version=$version pkg=$packageName")
        }
    }

    // ── 能力查询 ──

    override fun isAvailable(): Boolean = available

    override fun getVersion(): String = version

    override fun getPlayerType(): Int = playerType

    override fun supportsRealtimeAdjustment(): Boolean = playerType == PLAYER_RICHTAP && coreMajor >= 32

    // ── 效果下发 ──

    override fun startPattern(
        heJson: String,
        loop: Int,
        interval: Int,
        amplitude: Int,
        freq: Int,
    ): Boolean {
        if (!available) return false
        return if (playerType == PLAYER_RICHTAP) {
            startRichtap(heJson, loop, interval, amplitude, freq)
        } else {
            startTencent(heJson, loop, interval, amplitude, freq)
        }
    }

    override fun startEffect(heJson: String): Boolean {
        if (!available) return false
        return if (playerType == PLAYER_RICHTAP) {
            // 事件自带参数：无全局覆盖（对齐 SDK start() 的 1,0,gain,0）。
            startRichtap(heJson, 1, 0, 255, 0)
        } else {
            startTencentEffect(heJson)
        }
    }

    override fun updateParameter(intensity: Int, frequency: Int): Boolean {
        if (!available || playerType != PLAYER_RICHTAP) return false
        if (coreMajor < 32) return false
        val method = createHapticParameter ?: return false
        val vib = vibrator ?: return false
        val i = intensity.coerceIn(0, 100)
        val f = frequency.coerceIn(0, 100)
        synchronized(lock) {
            return try {
                val params = intArrayOf(
                    PARAM_TAG_SENDER, pid, nextSid(),
                    PARAM_TAG_INTENSITY, i,
                    PARAM_TAG_FREQUENCY, f,
                )
                val effect = method.invoke(null, params, params.size) as VibrationEffect
                vib.vibrate(effect)
                Log.i(TAG, "updateParameter intensity=$i freq=$f")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "updateParameter 失败", t)
                false
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            when (playerType) {
                PLAYER_RICHTAP -> stopRichtapLocked()
                PLAYER_TENCENT -> stopTencentLocked()
                else -> Unit
            }
        }
    }

    override fun exitService() {
        stop()
        Process.killProcess(Process.myPid())
    }

    // ── type 2：RichTapVibrationEffect / PhonyVibrationEffect ──

    private fun tryInitType2(): Boolean {
        val cls = tryClass("richtap.os.PhonyVibrationEffect")
            ?: tryClass("android.os.RichTapVibrationEffect")
            ?: return false
        return try {
            val check = cls.getMethod("checkIfRichTapSupport").invoke(null) as? Int ?: return false
            // SDK 约定：返回 2 表示不支持；返回 1 表示框架接了 RichTap 但无版本号。
            if (check == 2) {
                Log.w(TAG, "RichTapVibrationEffect 存在但 checkIfRichTapSupport=2（不支持）")
                return false
            }
            if (check != 1) {
                coreMajor = (check shr 8) and 0xFF
                coreMinor = check and 0xFF
            }
            if (coreMajor <= 0) {
                Log.w(TAG, "RichTapVibrationEffect check=$check 无有效版本，回退 type 1")
                return false
            }
            val int = Int::class.javaPrimitiveType!!
            createPatternHeWithParam = cls.getMethod(
                "createPatternHeWithParam",
                IntArray::class.java, int, int, int, int,
            )
            createHapticParameter = cls.getMethod("createHapticParameter", IntArray::class.java, int)
            createPatternHeParameter = cls.getMethod("createPatternHeParameter", int, int, int)
            playerType = PLAYER_RICHTAP
            available = vibrator != null
            version = "RichTap core $coreMajor.$coreMinor"
            if (vibrator == null) Log.w(TAG, "type 2 可用但没有 Vibrator 实例")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "初始化 type 2 失败: ${t.message}")
            false
        }
    }

    private fun startRichtap(heJson: String, loop: Int, interval: Int, amplitude: Int, freq: Int): Boolean {
        val method = createPatternHeWithParam ?: return false
        val vib = vibrator ?: return false
        val events = HeJson.parseHe10(heJson)
        if (events.isNullOrEmpty()) {
            Log.w(TAG, "type 2 解析 HE 失败")
            return false
        }
        // SDK：core ≤ 23 走 HE1.0，否则包成单个 pattern 走 HE2.0（带 senderId，便于实时调参）。
        val heVersion = if (coreMajor <= 23) 1 else 2
        val raw = RichTapRawCodec.encode(
            events = events,
            heVersion = heVersion,
            coreMajorRichTap = coreMajor,
            minorRichTap = coreMinor,
            pid = pid,
            sid = nextSid(),
            swapVibrationIndex = swapVibrationIndex,
        ) ?: return false
        synchronized(lock) {
            return try {
                val effect = method.invoke(
                    null, raw, loop, interval, amplitude.coerceIn(0, 255), freq.coerceIn(0, 100),
                ) as VibrationEffect
                vib.vibrate(effect)
                Log.i(TAG, "startRichtap heV=$heVersion raw=${raw.size} loop=$loop interval=$interval amp=$amplitude freq=$freq")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "startRichtap 失败", t)
                false
            }
        }
    }

    private fun stopRichtapLocked() {
        val method = createPatternHeParameter ?: return
        val vib = vibrator ?: return
        try {
            val effect = method.invoke(null, 0, 0, 0) as VibrationEffect
            vib.vibrate(effect)
        } catch (t: Throwable) {
            Log.w(TAG, "stopRichtap 失败: ${t.message}")
        }
    }

    // ── type 1：DynamicEffect + HapticPlayer ──

    private fun tryInitType1() {
        try {
            val hp = Class.forName("android.os.HapticPlayer")
            val hpAvailable = try {
                hp.getMethod("isAvailable").invoke(null) as? Boolean ?: false
            } catch (t: Throwable) {
                Log.w(TAG, "isAvailable 调用失败", t)
                false
            }
            if (!hpAvailable) {
                available = false
                playerType = PLAYER_NONE
                return
            }
            dynamicEffectClass = Class.forName("android.os.DynamicEffect")
            startMethod = hp.getMethod(
                "start",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            stopMethod = hp.getMethod("stop")
            startNoArgMethod = hp.getMethod("start")
            packageField = hp.getDeclaredField("mPackageName").apply { isAccessible = true }
            playerType = PLAYER_TENCENT
            available = true
            version = try {
                hp.getMethod("getVersion").invoke(null) as? String ?: ""
            } catch (_: Throwable) {
                ""
            }
        } catch (t: Throwable) {
            available = false
            playerType = PLAYER_NONE
            Log.w(TAG, "隐藏 API 不可用: ${t.message}")
        }
    }

    private fun startTencent(heJson: String, loop: Int, interval: Int, amplitude: Int, freq: Int): Boolean {
        val de = dynamicEffectClass ?: return false
        val start = startMethod ?: return false
        synchronized(lock) {
            return try {
                // 必须先 stop 旧 effect：实测“不 stop 直接 start”不会替换旧 track，
                // 幅度/频率更新会被忽略（震动卡在首帧）。stop+start 的重投递才是无缝的。
                stopTencentLocked()
                val effect = de.getMethod("create", String::class.java).invoke(null, heJson)
                val hp = Class.forName("android.os.HapticPlayer")
                val newPlayer = hp.getConstructor(de).newInstance(effect)
                // app_process 下 mPackageName 为 null，start() 会 NPE，必须补齐。
                packageField?.set(newPlayer, packageName)
                start.invoke(newPlayer, loop, interval, amplitude, freq)
                player = newPlayer
                Log.i(TAG, "startTencent loop=$loop interval=$interval amp=$amplitude freq=$freq")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "startTencent 失败", t)
                false
            }
        }
    }

    private fun startTencentEffect(heJson: String): Boolean {
        val de = dynamicEffectClass ?: return false
        val start = startNoArgMethod ?: return false
        synchronized(lock) {
            return try {
                // 多事件分块效果：事件自带 Parameters/Curve，用无参 start() 避免被全局
                // amplitude/freq 覆盖。
                stopTencentLocked()
                val effect = de.getMethod("create", String::class.java).invoke(null, heJson)
                val hp = Class.forName("android.os.HapticPlayer")
                val newPlayer = hp.getConstructor(de).newInstance(effect)
                packageField?.set(newPlayer, packageName)
                start.invoke(newPlayer)
                player = newPlayer
                Log.i(TAG, "startTencentEffect chars=${heJson.length}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "startTencentEffect 失败", t)
                false
            }
        }
    }

    private fun stopTencentLocked() {
        val p = player ?: return
        try {
            stopMethod?.invoke(p)
        } catch (t: Throwable) {
            // 厂商实现里 stop() 可能会打印一个 NPE 栈，但不影响停止。
            Log.w(TAG, "stopTencent 异常: ${t.message}")
        } finally {
            player = null
        }
    }

    private fun nextSid(): Int {
        senderSeq = (senderSeq + 1) and 0xFFFF
        return (SENDER_GID shl 16) or senderSeq
    }

    private fun tryClass(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (_: Throwable) {
        null
    }
}
