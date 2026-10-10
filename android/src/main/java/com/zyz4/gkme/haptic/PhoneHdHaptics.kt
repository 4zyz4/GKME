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
 * 因此这里不随每帧参数变化立即重启，而是节流到 [MIN_RESUBMIT_NS] 才应用一次新的
 * 振幅/频率；参数稳定时按 [REFRESH_NS] 重投递以延续效果（单次效果时长见 HapticInjector）。
 *
 * 低于马达下限的目标频率（`wantLowHz>0`）另走一路：按目标周期**逐周期单独投递一个脉冲**
 * （[RichTapLowFreq.pulse]），调度 tick 间隔也随之变为该周期，而不是把多个脉冲塞进一条效果。
 */
object PhoneHdHaptics {

    /** 由设置同步；仅在为 true 时才尝试 HD。 */
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var active: Boolean = false

    /** 当前驱动本对象的来源（游戏震动或自适应扳机）；用于按优先级让位与定向停止。 */
    @Volatile
    private var activeSource: HapticSource? = null

    /** 正在播放的（量化后）振幅/HE 频率。 */
    @Volatile
    private var appliedAmp: Int = -1

    @Volatile
    private var appliedFreq: Int = -1

    @Volatile
    private var appliedSource: HapticSource? = null

    /** 期望播放的（量化后）振幅/HE 频率；由 [playMotors] 更新，调度线程负责应用。 */
    @Volatile
    private var wantAmp: Int = 0

    @Volatile
    private var wantFreq: Int = RichTapFrequency.HE_AT_RESONANCE

    /**
     * 低于马达下限的目标频率（Hz）。>0 时按目标周期**逐周期单独投递一个脉冲效果**来模拟低频
     * （[RichTapLowFreq.pulse]），而不是把多个脉冲塞进一条效果；为 0 时走普通连续效果。
     */
    @Volatile
    private var wantLowHz: Double = 0.0

    @Volatile
    private var appliedLowHz: Double = 0.0

    /** 是否对当前连续效果做幅度-频率补偿；双马达启发式通路直接透传强度（false）。 */
    @Volatile
    private var wantCompensate: Boolean = true

    @Volatile
    private var appliedCompensate: Boolean = true

    @Volatile
    private var lastSubmitNs: Long = 0L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GkmeHdUpdate").apply { isDaemon = true }
    }

    @Volatile
    private var task: ScheduledFuture<*>? = null

    private val DEFAULT_FREQ = RichTapFrequency.HE_AT_RESONANCE    // ≈170 Hz（谐振点）
    private val HIGH_FREQ = RichTapFrequency.hzToHe(210.0)         // ≈210 Hz
    private const val CLICK_STRENGTH_MAX = 255

    /** 两次重投递之间的最小间隔；小于此值的参数变化会合并。 */
    private const val MIN_RESUBMIT_NS = 500_000_000L

    /** 参数稳定时的重投递间隔，须小于单次效果时长（HapticInjector 里为 1000ms）。 */
    private const val REFRESH_NS = 700_000_000L

    /** 普通连续效果的调度线程检查周期。 */
    private const val CHECK_INTERVAL_MS = 100L

    /** 驱动左右马达（0-255）。命中 HD 返回 true，否则返回 false 由调用方回退。
     *  [frequencyHz] > 0 时用 PCM 估计的主导音高映射成 HE 频率，否则按左右力度启发式选择。
     *  [source] 为本次驱动来源（游戏震动或自适应扳机），用于优先级仲裁：被更高优先级占用时
     *  返回 true（静默、不回退），不打扰正在播放的高优先级效果。 */
    fun playMotors(
        left: Int,
        right: Int,
        frequencyHz: Double = 0.0,
        source: HapticSource = HapticSource.GAME_RUMBLE,
    ): Boolean {
        if (!enabled) return false
        val l = left.coerceIn(0, 255)
        val r = right.coerceIn(0, 255)
        val amp = maxOf(l, r)
        if (amp <= 0) return false
        if (!HapticInjector.isHapticReady()) return false

        val qAmp = quantizeAmp(amp)
        // 低于马达下限的目标频率改为脉冲串模拟（见 RichTapLowFreq）。
        // 频率来自真实音高（frequencyHz>0）时做幅度-频率补偿；否则走大小马达启发式，
        // **不补偿、直接透传强度**——真机谐振点与 169Hz 假设不符，补偿会反而抬高高频。
        val (lowHz, freq, compensate) = if (frequencyHz > 0.0) {
            if (RichTapLowFreq.supports(frequencyHz)) {
                Triple(frequencyHz, RichTapFrequency.HE_AT_RESONANCE, true)
            } else {
                Triple(0.0, quantizeHe(RichTapFrequency.hzToHe(frequencyHz)), true)
            }
        } else {
            val (lp, f) = frequencyForMotors(l, r)
            Triple(lp, f, false)
        }

        // 曾被更高优先级抢占（如自适应扳机）时不持有所有权；恢复后需要重投递。
        val wasOwner = HapticInjector.owner() === source
        // 被更高优先级来源占用：静默（返回 true 让调用方不要回退系统震动）。
        if (!HapticInjector.acquire(source)) return true

        val previousSource = activeSource
        wantAmp = qAmp
        wantFreq = freq
        wantLowHz = lowHz
        wantCompensate = compensate
        activeSource = source

        if (!active) {
            if (submit(source)) {
                active = true
                ensureScheduled()
                return true
            }
            activeSource = null
            HapticInjector.release(source)
            return false
        }
        // 来源切换或被抢占恢复后需立即重投递；同来源的参数变化交由调度线程按节流应用，
        // 避免高频重启把输出拖弱。
        if (previousSource != source || !wasOwner) submit(source)
        return true
    }

    /** 按键反馈用的短促点击。[strength] 0-255。被更高优先级来源占用时静默（返回 true）。 */
    fun playClick(strength: Int): Boolean {
        if (!enabled) return false
        if (!HapticInjector.isHapticReady()) return false
        if (!HapticInjector.canPlay(HapticSource.BUTTON)) return true
        val s = strength.coerceIn(0, CLICK_STRENGTH_MAX)
        return HapticInjector.playClick(s, DEFAULT_FREQ, HapticSource.BUTTON)
    }

    /** 按指定时长播放一次 HD 效果（按键按下/抬起等）。[strength] 0-255，[durationMs] 毫秒，
     *  [frequencyHe] 为 RichTap HE 频率 0-100，缺省用谐振点 [DEFAULT_FREQ]。
     *  被更高优先级来源占用时静默（返回 true）。 */
    fun playEffect(strength: Int, durationMs: Int, frequencyHe: Int = DEFAULT_FREQ): Boolean {
        if (!enabled) return false
        if (!HapticInjector.isHapticReady()) return false
        if (!HapticInjector.canPlay(HapticSource.BUTTON)) return true
        val s = strength.coerceIn(0, CLICK_STRENGTH_MAX)
        val dur = durationMs.coerceAtLeast(1)
        val freq = frequencyHe.coerceIn(0, 100)
        return HapticInjector.startPattern(
            RichTapHe.continuous(freq, dur), 1, 0,
            RichTapEngine.compensate255(s, freq), freq, HapticSource.BUTTON,
        )
    }

    /** 播放一段 RichTap 预置效果（PrebakedEffect，ID 10001-10050），用于在 HD 下替换
     *  系统 `performHapticFeedback`（后者会被 RichTap 抢占而失效）。命中 HD 返回 true。
     *  被更高优先级来源占用时静默（返回 true）。 */
    fun playPrebaked(prebakedId: Int): Boolean {
        if (!enabled) return false
        if (!HapticInjector.isHapticReady()) return false
        if (!HapticInjector.canPlay(HapticSource.BUTTON)) return true
        val he = RichTapPrebaked.he(prebakedId) ?: return false
        return HapticInjector.startEffect(he, HapticSource.BUTTON)
    }

    /** 播放一段低频脉冲串效果（模拟低于马达下限的低频）。[strength] 0-255，
     *  [frequencyHz] 目标低频（Hz），[durationMs] 时长。命中 HD 返回 true。 */
    fun playLowFrequency(
        strength: Int,
        frequencyHz: Double,
        durationMs: Int,
        source: HapticSource = HapticSource.GAME_RUMBLE,
    ): Boolean {
        if (!enabled) return false
        if (!HapticInjector.isHapticReady()) return false
        if (!RichTapLowFreq.supports(frequencyHz)) return false
        if (!HapticInjector.canPlay(source)) return true
        val s = strength.coerceIn(0, CLICK_STRENGTH_MAX)
        val dur = durationMs.coerceAtLeast(1)
        return HapticInjector.startEffect(RichTapLowFreq.pattern(frequencyHz, dur, s), source)
    }

    /** 停止 HD 持续震动。传入 [source] 时仅当当前来源匹配才停止，避免让位后误杀别的来源；
     *  不传 [source] 视为硬停止（用户关闭 HD），清除所有已投递的效果。 */
    fun stop(source: HapticSource? = null): Boolean {
        if (source != null && activeSource != source) return false
        val owned = activeSource
        active = false
        activeSource = null
        appliedAmp = -1
        appliedFreq = -1
        appliedLowHz = 0.0
        appliedCompensate = true
        appliedSource = null
        cancelTask()
        when {
            source == null -> HapticInjector.stop()
            owned != null -> HapticInjector.stopOwnedBy(owned)
        }
        return true
    }

    /** 断开/心跳丢失后复位内部缓存。 */
    fun reset() {
        active = false
        activeSource = null
        appliedAmp = -1
        appliedFreq = -1
        appliedLowHz = 0.0
        appliedCompensate = true
        appliedSource = null
        cancelTask()
    }

    /** 用当前期望值投递一次；成功返回 true。低频时只投递**单个**脉冲。 */
    private fun submit(source: HapticSource): Boolean {
        val ok: Boolean
        if (wantLowHz > 0.0) {
            ok = HapticInjector.startEffect(RichTapLowFreq.pulse(wantLowHz, wantAmp), source)
        } else {
            ok = HapticInjector.startContinuous(wantAmp, wantFreq, source, wantCompensate)
        }
        if (ok) {
            appliedAmp = wantAmp
            appliedFreq = wantFreq
            appliedLowHz = wantLowHz
            appliedCompensate = wantCompensate
            appliedSource = source
            lastSubmitNs = System.nanoTime()
        }
        return ok
    }

    private fun ensureScheduled() {
        synchronized(this) {
            val f = task
            if (f != null && !f.isCancelled) return
            task = scheduler.schedule(::onTick, nextTickDelayMs(), TimeUnit.MILLISECONDS)
        }
    }

    private fun onTick() {
        try {
            if (!active || !enabled) return
            if (!HapticInjector.isHapticReady()) return
            val source = activeSource ?: return
            // 低频：每个周期单独投递一个脉冲效果（不在一条效果里塞多个脉冲）。
            if (wantLowHz > 0.0) {
                submit(source)
                return
            }
            val now = System.nanoTime()
            // 来源切换（自适应扳机 ↔ 游戏震动）也需要重投递，刷新引擎侧的参数与所有权。
            val changed = appliedAmp != wantAmp || appliedFreq != wantFreq ||
                appliedLowHz != wantLowHz || appliedSource != source ||
                appliedCompensate != wantCompensate
            var submitted = false
            if (changed) {
                // type 2 且 core ≥ 32 时，参数变化走实时调参，避免 stop+start 拖弱输出。
                val realtime = HapticInjector.realtimeAdjust && appliedLowHz <= 0.0 &&
                    appliedSource == source && appliedAmp >= 0
                if (realtime) {
                    // updateParameter 直接吃 0-255 幅度，故补偿在调用前手动折算。
                    val realtimeAmp = if (wantCompensate) {
                        RichTapEngine.compensate255(wantAmp, wantFreq)
                    } else {
                        wantAmp
                    }
                    if (HapticInjector.updateParameter(realtimeAmp, wantFreq)) {
                        appliedAmp = wantAmp
                        appliedFreq = wantFreq
                        appliedLowHz = 0.0
                        appliedCompensate = wantCompensate
                        appliedSource = source
                        // 不刷新 lastSubmitNs：仍靠下方周期重投递延续效果时长。
                    } else if (now - lastSubmitNs >= MIN_RESUBMIT_NS) {
                        submitted = submit(source)
                    }
                } else if (appliedLowHz > 0.0 || now - lastSubmitNs >= MIN_RESUBMIT_NS) {
                    // appliedLowHz>0 表示刚由低频切回连续，立即重投递，避免 500ms 空档。
                    submitted = submit(source)
                }
            }
            // 延续效果：在单次效果结束前重投递；也在实时调参期间兜底刷新效果时长。
            if (!submitted && now - lastSubmitNs >= REFRESH_NS) {
                submit(source)
            }
        } finally {
            synchronized(this) {
                if (active && enabled) {
                    task = scheduler.schedule(::onTick, nextTickDelayMs(), TimeUnit.MILLISECONDS)
                } else {
                    task = null
                }
            }
        }
    }

    /**
     * 下一次 tick 的间隔：低频按目标周期逐脉冲投递（[RichTapLowFreq.periodMs]），
     * 普通连续效果用固定检查周期。
     */
    private fun nextTickDelayMs(): Long =
        if (wantLowHz > 0.0) RichTapLowFreq.periodMs(wantLowHz).toLong() else CHECK_INTERVAL_MS

    private fun cancelTask() {
        synchronized(this) {
            task?.cancel(false)
            task = null
        }
    }

    /** 游戏 rumble 的左右马达 → (低频模拟 Hz, HE 频率)。Hz>0 表示走 [RichTapLowFreq] 脉冲串。
     *  游戏震动**不做低频分段**（分段会让大小马达听感变成一顿一顿的脉冲），也**不做幅度-频率补偿**
     *  （本通路直接透传强度）。低频（强）马达用**谐振频率** [DEFAULT_FREQ] 取最大位移；较大值为
     *  “弱/高频”马达时切到 [HIGH_FREQ] 得到更高的音调。 */
    private fun frequencyForMotors(left: Int, right: Int): Pair<Double, Int> {
        val l = left.coerceIn(0, 255)
        val r = right.coerceIn(0, 255)
        return when {
            r > l -> 0.0 to HIGH_FREQ
            else -> 0.0 to DEFAULT_FREQ
        }
    }

    /** 把 0-255 量化到 16 级（最小 16），降低 HD 效果重启频率。 */
    private fun quantizeAmp(amp: Int): Int =
        (((amp + AMP_STEP / 2) / AMP_STEP) * AMP_STEP).coerceIn(AMP_STEP, 255)

    /** 把 HE 频率量化到 [HE_STEP] 的整数倍，降低因 PCM 抖动导致的重启。 */
    private fun quantizeHe(he: Int): Int =
        (((he + HE_STEP / 2) / HE_STEP) * HE_STEP).coerceIn(0, 100)

    private const val AMP_STEP = 16
    private const val HE_STEP = 4
}
