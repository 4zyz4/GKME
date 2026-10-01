package com.zyz4.gkme.haptic

import com.zyz4.gkme.controlled.HapticInjector
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 手机马达的 HD 震动门面：集中处理“是否启用 HD / 是否就绪 / 回退普通震动”，
 * 并用**节流重投递**维持持续震动。
 *
 * 厂商 HAL 的限制（真机实测）：
 * - `loop=-1` 循环重播在每圈衔接处约有 200ms 断点；
 * - 每次 `stop()+start()` 后 LRA 需要重新爬升，重投递越频繁输出越弱
 *   （实测 amp200：单次≈129、200ms≈103、500ms≈162、1000ms≈236）。
 *
 * 因此这里不随每帧参数变化立即重启，而是节流到 [MIN_RESUBMIT_MS] 才应用一次新的
 * 振幅/频率；参数稳定时按 [REFRESH_MS] 重投递以延续效果（单次效果时长见 HapticInjector）。
 */
object PhoneHdHaptics {

    /** 由设置同步；仅在为 true 时才尝试 HD。 */
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var active: Boolean = false

    /** 正在播放的（量化后）振幅/HE 频率。 */
    @Volatile
    private var appliedAmp: Int = -1

    @Volatile
    private var appliedFreq: Int = -1

    /** 期望播放的（量化后）振幅/HE 频率；由 [playMotors] 更新，调度线程负责应用。 */
    @Volatile
    private var wantAmp: Int = 0

    @Volatile
    private var wantFreq: Int = RichTapFrequency.HE_AT_RESONANCE

    @Volatile
    private var lastSubmitNs: Long = 0L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GkmeHdUpdate").apply { isDaemon = true }
    }

    @Volatile
    private var task: ScheduledFuture<*>? = null

    private val DEFAULT_FREQ = RichTapFrequency.HE_AT_RESONANCE    // ≈170 Hz（谐振点）
    private val LOW_FREQ = RichTapFrequency.hzToHe(140.0)          // ≈140 Hz
    private val HIGH_FREQ = RichTapFrequency.hzToHe(210.0)         // ≈210 Hz
    private const val CLICK_STRENGTH_MAX = 255

    /** 两次重投递之间的最小间隔；小于此值的参数变化会合并。 */
    private const val MIN_RESUBMIT_NS = 500_000_000L

    /** 参数稳定时的重投递间隔，须小于单次效果时长（HapticInjector 里为 4000ms）。 */
    private const val REFRESH_NS = 3_000_000_000L

    /** 调度线程检查周期。 */
    private const val CHECK_INTERVAL_MS = 100L

    /** 驱动左右马达（0-255）。命中 HD 返回 true，否则返回 false 由调用方回退。
     *  [frequencyHz] > 0 时用 PCM 估计的主导音高映射成 HE 频率，否则按左右力度启发式选择。 */
    fun playMotors(left: Int, right: Int, frequencyHz: Double = 0.0): Boolean {
        if (!enabled) return false
        val l = left.coerceIn(0, 255)
        val r = right.coerceIn(0, 255)
        val amp = maxOf(l, r)
        if (amp <= 0) return false
        if (!HapticInjector.isHapticReady()) return false

        val qAmp = quantizeAmp(amp)
        val freq = if (frequencyHz > 0.0) {
            quantizeHe(RichTapFrequency.hzToHe(frequencyHz))
        } else {
            frequencyFor(l, r)
        }
        wantAmp = qAmp
        wantFreq = freq

        if (!active) {
            if (submit()) {
                active = true
                ensureScheduled()
                return true
            }
            return false
        }
        // 已在播放：参数变化交由调度线程按节流应用，避免高频重启把输出拖弱。
        return true
    }

    /** 按键反馈用的短促点击。[strength] 0-255。 */
    fun playClick(strength: Int): Boolean {
        if (!enabled) return false
        if (!HapticInjector.isHapticReady()) return false
        val s = strength.coerceIn(0, CLICK_STRENGTH_MAX)
        return HapticInjector.playClick(s, frequencyFor(s, s), TOKEN)
    }

    /** 按指定时长播放一次 HD 效果（按键按下/抬起等）。[strength] 0-255，[durationMs] 毫秒。 */
    fun playEffect(strength: Int, durationMs: Int): Boolean {
        if (!enabled) return false
        if (!HapticInjector.isHapticReady()) return false
        val s = strength.coerceIn(0, CLICK_STRENGTH_MAX)
        val dur = durationMs.coerceAtLeast(1)
        val freq = frequencyFor(s, s)
        return HapticInjector.startPattern(RichTapHe.continuous(freq, dur), 1, 0, s, freq, TOKEN)
    }

    /** 停止 HD 持续震动（若正在运行）。仅停止本消费者自己的效果。 */
    fun stop(): Boolean {
        active = false
        appliedAmp = -1
        appliedFreq = -1
        HapticInjector.stopOwnedBy(TOKEN)
        return true
    }

    /** 断开/心跳丢失后复位内部缓存。 */
    fun reset() {
        active = false
        appliedAmp = -1
        appliedFreq = -1
    }

    /** 用当前期望值投递一次；成功返回 true。 */
    private fun submit(): Boolean {
        val ok = HapticInjector.startContinuous(wantAmp, wantFreq, TOKEN)
        if (ok) {
            appliedAmp = wantAmp
            appliedFreq = wantFreq
            lastSubmitNs = System.nanoTime()
        }
        return ok
    }

    private fun ensureScheduled() {
        synchronized(this) {
            val f = task
            if (f != null && !f.isCancelled) return
            task = scheduler.scheduleWithFixedDelay(
                ::onTick,
                CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun onTick() {
        if (!active || !enabled) return
        if (!HapticInjector.isHapticReady()) return
        val now = System.nanoTime()
        val changed = appliedAmp != wantAmp || appliedFreq != wantFreq
        if (changed) {
            if (now - lastSubmitNs >= MIN_RESUBMIT_NS) submit()
        } else if (now - lastSubmitNs >= REFRESH_NS) {
            // 延续效果：在单次效果结束前重投递。
            submit()
        }
    }

    private fun frequencyFor(left: Int, right: Int): Int {
        val l = left.coerceIn(0, 255)
        val r = right.coerceIn(0, 255)
        if (l == r) return DEFAULT_FREQ
        // 约定：较大值为“弱/高频”马达 → 更高频；否则更低频。
        return if (r >= l) HIGH_FREQ else LOW_FREQ
    }

    /** 把 0-255 量化到 16 级（最小 16），降低 HD 效果重启频率。 */
    private fun quantizeAmp(amp: Int): Int =
        (((amp + AMP_STEP / 2) / AMP_STEP) * AMP_STEP).coerceIn(AMP_STEP, 255)

    /** 把 HE 频率量化到 [HE_STEP] 的整数倍，降低因 PCM 抖动导致的重启。 */
    private fun quantizeHe(he: Int): Int =
        (((he + HE_STEP / 2) / HE_STEP) * HE_STEP).coerceIn(0, 100)

    private const val AMP_STEP = 16
    private const val HE_STEP = 4

    /** 效果所有权 token：与 HdPcmStreamer（音圈 PCM）区分，避免互相误杀。 */
    private val TOKEN = Any()
}
