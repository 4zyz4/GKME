package com.zyz4.gkme.controlled

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import com.zyz4.gkme.haptic.He2Converter
import com.zyz4.gkme.haptic.He2Json
import com.zyz4.gkme.haptic.HeDocument
import com.zyz4.gkme.haptic.PwleEncoder
import com.zyz4.gkme.haptic.RichTapRawCodec
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 高清震动（RichTap 动态效果）驱动实现。
 *
 * 普通 Kotlin 类（非 Binder/Shizuku 用户服务）：由 [HapticInjector] 在 app 进程内直接实例化，
 * 以**前台 app 的 uid** 反射调用框架隐藏类（manifest 声明 `richtap-api` 共享库后可用），
 * 从而不被系统“后台震动”策略丢弃。
 * 按优先级选 backend（对齐 RichTap ASDK `RichTapUtils.init`，并新增 Android 16 PWLE）：
 * - **type 2 `RichTapPerformer`**：`richtap.os.PhonyVibrationEffect` / `android.os.RichTapVibrationEffect`
 *   的 `createPatternHeWithParam(int[])`，支持实时调参（`createHapticParameter`）。
 * - **type 1 `TencentPerformer`**：`android.os.DynamicEffect` + `android.os.HapticPlayer`。
 * - **type 4 `PWLE`**：Android 16+ 原生 `VibrationEffect.WaveformEnvelopeBuilder`
 *   （Piece-Wise Linear Envelope）。不依赖 RichTap / 厂商 hidden API，只要设备
 *   `Vibrator.areEnvelopeEffectsSupported()` 为 true 即可用；HE 由 [PwleEncoder] 转成
 *   `(amplitude, frequencyHz, duration)` 控制点。
 * - 三者都不可用时 `available=false`，由调用方回退普通 `Vibrator`。
 *
 * type 2 的 raw int[] 编码见 [RichTapRawCodec]（逆向自 SDK `base.b.a`）。
 */
// RichTap HD 震动只能通过厂商隐藏 API 反射调用，这是本类的核心实现方式。
@SuppressLint("PrivateApi")
class RemoteHapticService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) {

    companion object {
        private const val TAG = "GKME_RemoteHaptic"
        private const val DEFAULT_PACKAGE = "com.android.shell"

        const val PLAYER_NONE = 0
        const val PLAYER_TENCENT = 1
        const val PLAYER_RICHTAP = 2

        /** Android 16+ 原生 PWLE 包络震动（不依赖 RichTap hidden API）。 */
        const val PLAYER_PWLE = 4

        /** type 2 实时调参（createHapticParameter）的发送者组 id。 */
        private const val SENDER_GID = 1

        // createHapticParameter 头部标签（SDK `d.a` 常量）：256=发送者块，513=强度，514=频率。
        private const val PARAM_TAG_SENDER = 256
        private const val PARAM_TAG_INTENSITY = 513
        private const val PARAM_TAG_FREQUENCY = 514
    }

    private val lock = Any()

    @Volatile
    var available = false
        private set

    @Volatile
    var playerType = PLAYER_NONE
        private set

    @Volatile
    var version: String = ""
        private set

    @Volatile
    private var packageName: String = DEFAULT_PACKAGE

    /**
     * HE2.0 的 `Index` 左右交换。部分机型把左右马达索引接反，SDK 提供
     * `swapVibrationIndex(boolean)`；默认 false，保持厂商语义。
     */
    @Volatile
    var swapVibrationIndex: Boolean = false

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

    // ── type 4 资源（Android 16 PWLE，只缓存原始数值，避免 API 36 类型泄漏到低版本） ──
    private var pwleMinHz = Float.NaN
    private var pwleMaxHz = Float.NaN
    private var pwleResonantHz = Float.NaN
    private var pwleMinPointMs = 1L
    private var pwleMaxPointMs = 10_000L
    private var pwleMaxDurationMs = 60_000L
    private var pwleMaxPoints = 0

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
                if (!tryInitType1()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                        tryInitType4()
                    }
                }
            }
            Log.i(TAG, "init: playerType=$playerType available=$available version=$version pkg=$packageName")
        }
    }

    // ── 能力查询 ──

    /** 是否支持 type 2 实时调参（`createHapticParameter`）。 */
    fun supportsRealtimeAdjustment(): Boolean = playerType == PLAYER_RICHTAP && coreMajor >= 32

    /** 是否支持原生 HE2.0 raw（type 2 且 core ≥ 24）。 */
    fun supportsNativeHe20(): Boolean = playerType == PLAYER_RICHTAP && coreMajor >= 24

    /** 当前 backend 是否为 Android 16+ 原生 PWLE 包络震动（type 4）。 */
    fun supportsEnvelopeEffects(): Boolean = playerType == PLAYER_PWLE

    /** 当前 PWLE 设备约束；非 PWLE backend 返回 null。 */
    fun envelopeLimits(): PwleEncoder.Limits? {
        if (playerType != PLAYER_PWLE) return null
        return PwleEncoder.Limits(
            minFrequencyHz = pwleMinHz,
            maxFrequencyHz = pwleMaxHz,
            resonanceHz = pwleResonantHz,
            minPointDurationMs = pwleMinPointMs,
            maxPointDurationMs = pwleMaxPointMs,
            maxDurationMs = pwleMaxDurationMs,
            maxPoints = pwleMaxPoints,
        )
    }

    // ── 效果下发 ──

    fun startPattern(
        heJson: String,
        loop: Int,
        interval: Int,
        amplitude: Int,
        freq: Int,
    ): Boolean {
        if (!available) return false
        val document = He2Json.parse(heJson)
        return when (playerType) {
            PLAYER_RICHTAP -> {
                val doc = document ?: return false
                startRichtap(doc, loop, interval, amplitude, freq)
            }
            PLAYER_PWLE -> {
                val doc = document ?: return false
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
                startPwle(doc, loop, amplitude)
            }
            else -> startTencent(toHe10Json(heJson, document), loop, interval, amplitude, freq)
        }
    }

    fun startEffect(heJson: String): Boolean {
        if (!available) return false
        val document = He2Json.parse(heJson)
        return when (playerType) {
            PLAYER_RICHTAP -> {
                val doc = document ?: return false
                // 事件自带参数：无全局覆盖（对齐 SDK start() 的 1,0,gain,0）。
                startRichtap(doc, 1, 0, 255, 0)
            }
            PLAYER_PWLE -> {
                val doc = document ?: return false
                // 事件自带幅度/频率，全局 gain 取满。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
                startPwle(doc, 1, 255)
            }
            else -> startTencentEffect(toHe10Json(heJson, document))
        }
    }

    /**
     * type 1 只能吃 HE1.0：
     * - 输入本来就是 HE1.0：原样下发，避免改变已验证的预置效果/分块行为；
     * - 输入是 HE2.0：best-effort 转换后序列化为 HE1.0。
     */
    private fun toHe10Json(original: String, document: HeDocument?): String {
        if (document == null) return original
        if (document.version == He2Json.VERSION_1 && document.patterns.size == 1) return original
        val he10 = He2Converter.toHe10BestEffort(document) ?: return original
        return He2Json.writeHe10(he10)
    }

    fun updateParameter(intensity: Int, frequency: Int): Boolean {
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

    fun stop() {
        synchronized(lock) {
            when (playerType) {
                PLAYER_RICHTAP -> stopRichtapLocked()
                PLAYER_TENCENT -> stopTencentLocked()
                PLAYER_PWLE -> stopPwleLocked()
                else -> Unit
            }
        }
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

    private fun startRichtap(
        document: HeDocument,
        loop: Int,
        interval: Int,
        amplitude: Int,
        freq: Int,
    ): Boolean {
        val method = createPatternHeWithParam ?: return false
        val vib = vibrator ?: return false
        if (document.events().isEmpty()) {
            Log.w(TAG, "type 2 效果没有事件")
            return false
        }
        // SDK：core ≤ 23 走 HE1.0（有损转换），core ≥ 24 原生 HE2.0 raw。
        val heVersion = if (coreMajor <= 23) 1 else 2
        val raw = RichTapRawCodec.encode(
            document = document,
            coreMajorRichTap = coreMajor,
            minorRichTap = coreMinor,
            pid = pid,
            sid = nextSid(),
            swapVibrationIndex = swapVibrationIndex,
        ) ?: run {
            Log.w(TAG, "type 2 编码 HE 失败: version=${document.version} patterns=${document.patterns.size}")
            return false
        }
        synchronized(lock) {
            return try {
                // SDK `RichTapPerformer.start()` 恒传 freq=0；频率由事件 `Parameters.Frequency`
                // 决定。传非 0 的全局 freq 可能被厂商实现当成覆盖/叠加，导致频率翻倍或偏移。
                val effect = method.invoke(
                    null, raw, loop, interval, amplitude.coerceIn(0, 255), 0,
                ) as VibrationEffect
                vib.vibrate(effect)
                Log.i(
                    TAG,
                    "startRichtap heV=$heVersion raw=${raw.size} patterns=${document.patterns.size} " +
                        "loop=$loop interval=$interval amp=$amplitude requestedFreq=$freq",
                )
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

    // ── type 4：Android 16+ 原生 PWLE 包络震动 ──

    /**
     * 探测 Android 16+ 的 PWLE 能力：
     * - `Vibrator.areEnvelopeEffectsSupported()` 必须为 true；
     * - `Vibrator.getFrequencyProfile()` 必须给出有效且非 NaN 的频率上下限
     *   （PWLE 使用真实 Hz，频率越界会导致整条效果不播放）；
     * - `Vibrator.getEnvelopeEffectInfo()` 给出控制点数量/时长约束。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun tryInitType4(): Boolean {
        val vib = vibrator ?: return false
        return try {
            if (!vib.areEnvelopeEffectsSupported()) {
                Log.i(TAG, "PWLE: areEnvelopeEffectsSupported=false")
                return false
            }
            val profile = vib.getFrequencyProfile() ?: run {
                Log.i(TAG, "PWLE: getFrequencyProfile()=null")
                return false
            }
            val minHz = profile.minFrequencyHz
            val maxHz = profile.maxFrequencyHz
            if (!minHz.isFinite() || !maxHz.isFinite() || minHz <= 0f || maxHz <= minHz) {
                Log.i(TAG, "PWLE: 无有效频率范围 min=$minHz max=$maxHz")
                return false
            }
            val info = vib.getEnvelopeEffectInfo() ?: run {
                Log.i(TAG, "PWLE: getEnvelopeEffectInfo()=null")
                return false
            }
            pwleMinHz = minHz
            pwleMaxHz = maxHz
            pwleResonantHz = try {
                vib.resonantFrequency
            } catch (_: Throwable) {
                Float.NaN
            }
            pwleMinPointMs = info.minControlPointDurationMillis.coerceAtLeast(1L)
            pwleMaxPointMs = info.maxControlPointDurationMillis.coerceAtLeast(pwleMinPointMs)
            pwleMaxDurationMs = info.maxDurationMillis
            pwleMaxPoints = info.maxSize
            playerType = PLAYER_PWLE
            available = true
            version = "PWLE (Android ${Build.VERSION.RELEASE})"
            Log.i(
                TAG,
                "PWLE 就绪: freq=$minHz..$maxHz res=$pwleResonantHz " +
                    "point=$pwleMinPointMs..$pwleMaxPointMs maxDur=$pwleMaxDurationMs " +
                    "maxPoints=$pwleMaxPoints",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "初始化 PWLE 失败: ${t.message}")
            false
        }
    }

    /**
     * 用 `VibrationEffect.WaveformEnvelopeBuilder` 下发一条 PWLE 效果。
     *
     * @param gainAmplitude 全局幅度 0-255（对应 `startPattern` 的 amplitude）；事件自身曲线
     *                      已包含相对幅度，这里只做整体缩放。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startPwle(document: HeDocument, loop: Int, gainAmplitude: Int): Boolean {
        val points = PwleEncoder.encode(
            document = document,
            limits = envelopeLimits() ?: return false,
            gain = gainAmplitude.coerceIn(0, 255) / 255f,
        )
        if (points.isEmpty()) {
            Log.w(TAG, "startPwle 没有可用控制点: events=${document.events().size}")
            return false
        }
        return playPwle(points, loop)
    }

    /**
     * 直接播放一组 PWLE 控制点（PCM2PWLE 的输出，或 [PwleEncoder] 的转换结果）。
     *
     * 约定：第一个点 `durationMs == 0` 表示“初始状态”，只取它的频率作为
     * `setInitialFrequencyHz`，不交给 `addControlPoint`（Android 要求时长 > 0）。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun startEnvelope(points: List<PwleEncoder.ControlPoint>, loop: Int = 1): Boolean {
        if (playerType != PLAYER_PWLE) return false
        return playPwle(points, loop)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun playPwle(points: List<PwleEncoder.ControlPoint>, loop: Int): Boolean {
        val vib = vibrator ?: return false
        if (points.isEmpty()) return false
        val initialFrequency = points.first().frequencyHz
        val body = if (points.first().durationMs <= 0L) points.drop(1) else points
        if (body.isEmpty()) return false
        synchronized(lock) {
            return try {
                val builder = VibrationEffect.WaveformEnvelopeBuilder()
                    .setInitialFrequencyHz(initialFrequency)
                for (p in body) {
                    val duration = p.durationMs.coerceIn(pwleMinPointMs, pwleMaxPointMs)
                    builder.addControlPoint(
                        p.amplitude.coerceIn(0f, 1f),
                        p.frequencyHz,
                        duration,
                    )
                }
                val effect = builder.build()
                // PWLE 没有可靠的“无 stop 链接”，每次新效果直接接管；loop<0 用系统循环。
                val toPlay = if (loop < 0) VibrationEffect.createRepeatingEffect(effect) else effect
                vib.vibrate(toPlay)
                Log.d(
                    TAG,
                    "playPwle points=${body.size} dur=${body.sumOf { it.durationMs }}ms loop=$loop",
                )
                true
            } catch (t: Throwable) {
                Log.e(TAG, "playPwle 失败", t)
                false
            }
        }
    }

    private fun stopPwleLocked() {
        try {
            vibrator?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "stopPwle 失败: ${t.message}")
        }
    }

    // ── type 1：DynamicEffect + HapticPlayer ──

    private fun tryInitType1(): Boolean {
        return try {
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
                return false
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
            // app_process 下该字段为 null 需手动补齐；app 进程内它是 blocked 字段（拿不到），
            // 但厂商构造函数会自动填好，因此这里允许为 null。
            packageField = try {
                hp.getDeclaredField("mPackageName").apply { isAccessible = true }
            } catch (t: Throwable) {
                Log.i(TAG, "mPackageName 字段不可访问（app 进程内使用默认值）: ${t.message}")
                null
            }
            playerType = PLAYER_TENCENT
            available = true
            version = try {
                hp.getMethod("getVersion").invoke(null) as? String ?: ""
            } catch (_: Throwable) {
                ""
            }
            true
        } catch (t: Throwable) {
            available = false
            playerType = PLAYER_NONE
            Log.w(TAG, "隐藏 API 不可用: ${t.message}")
            false
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
                // app_process 下 mPackageName 为 null 需补齐；app 进程内它是 blocked 字段，
                // 拿不到时跳过（厂商构造函数会用当前包名填好）。
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
                //
                // **不要 stop 旧 player**：真机实测（加速度计，见 docs/richtap-hd-vibration.md §13）
                // 新 player 的 start() 会直接接管当前 track；而 stop() 是**全局**取消（停旧会连新
                // 一起停，`chain_single_stop` 实测停旧后整段静音）。因此流式分块改为「无 stop 链接」，
                // 只在整条流真正结束时由 [stop] 停一次。旧 player 对象随作用域被回收不影响播放
                //（与 `AccStream` 探针一致）。
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
