package com.zyz4.gkme.haptic

import kotlin.math.roundToInt

/**
 * 把逐帧 PCM 的（归一化幅度, HE 频率）编码成 **HE 1.0 多事件效果**。
 *
 * 真机实测的硬约束（见 docs/richtap-hd-vibration.md）：
 * - 单条效果最多 [eventsPerChunk] 个事件（本机 16；超出会被 HAL 整条丢弃）；
 * - 每个事件的 `Curve` **必须恰好 4 个控制点**（非 4 点会让 HAL 报 `Invalid time param`
 *   并使整条效果无输出）；
 * - 单个事件时长 ≤ 5000ms。
 *
 * 因此这里把时间轴切成 [eventsPerChunk] 个 [eventMs] 长的事件，每个事件内压成 4 个控制点
 * （幅度取桶内均值、频率相对事件基频给偏移），段与段由引擎内部调度，没有 stop+start 凹陷。
 *
 * 纯 JVM 代码，便于单测。
 */
class PcmHeEncoder(
    private val eventsPerChunk: Int = 16,
    private val eventMs: Int = 200,
    /**
     * 分块边界「起振补偿」增益（≥1）。每次 `stop()+start()` 重启时 LRA 会有一小段掉幅；
     * 把**首事件**的领先控制点按此值抬升、随后衰减到 1，用更大驱动幅度加速机械恢复、填掉凹陷。
     * 1.0 = 关闭。与 [EVENTS_PER_CHUNK] 的取舍配套使用（块越短边界越频繁，越需要补偿）。
     */
    private val seamBoost: Double = 1.0,
    /**
     * 音量突增强调：检测到**突然增大**时，在对应时刻额外插入一条 [accentMs] 时长的
     * **满强度** `continuous` 事件（引擎内部调度，与常规事件可重叠），让 LRA 强震一下。
     * 0 = 关闭。
     */
    private val accentMs: Int = 0,
) {

    private companion object {
        const val POINTS = 4
        const val MAX_OFFSET = 60.0
        const val BASE_MIN = 20
        const val BASE_MAX = 80

        /** 真机单条效果的事件数上限。 */
        const val MAX_EVENTS = 16

        /** 触发强调需要的最小幅度（0..1）。 */
        const val ONSET_MIN = 0.25f

        /** 触发强调所需的“突然增大”幅度差（0..1）。 */
        const val ONSET_DELTA = 0.22f

        /** 两次强调之间的最小间隔（ms），避免连续触发。 */
        const val ACCENT_REFRACTORY_MS = 80
    }

    private val ampSum = Array(eventsPerChunk) { FloatArray(POINTS) }
    private val heSum = Array(eventsPerChunk) { DoubleArray(POINTS) }
    private val count = Array(eventsPerChunk) { IntArray(POINTS) }
    private var tMs = 0

    // 音量突增检测状态（跨分块保留 prevAmp，避免分块重置被误判为突增）。
    private var prevAmp = 0f
    private var clockMs = 0L
    private var lastAccentClock = -1_000_000L
    private val accents = ArrayList<Int>()

    /** 一个完整分块的时长（毫秒）。 */
    val chunkMs: Int get() = eventsPerChunk * eventMs

    /** 效果时长（含首尾），供调用方安排重投递节奏。 */
    val effectMs: Int get() = chunkMs

    fun reset() {
        for (e in 0 until eventsPerChunk) {
            java.util.Arrays.fill(ampSum[e], 0f)
            java.util.Arrays.fill(heSum[e], 0.0)
            java.util.Arrays.fill(count[e], 0)
        }
        tMs = 0
        accents.clear()
        // 注意：prevAmp / clockMs 故意保留，避免分块切换被误判为“音量突增”。
    }

    /**
     * 追加一帧。
     *
     * @param dtMs   距上一帧的毫秒数（≥1）。
     * @param amp01  归一化幅度 0..1。
     * @param he     HE 频率 0..100。
     * @return 当凑满一个完整分块时返回 HE JSON 并自动复位；否则返回 null。
     */
    fun addSample(dtMs: Int, amp01: Float, he: Int): String? {
        val amp = amp01.coerceIn(0f, 1f)
        val e = tMs / eventMs
        if (accentMs > 0 && e < eventsPerChunk) detectAccent(amp, tMs)
        prevAmp = amp
        clockMs += dtMs.coerceAtLeast(1)

        if (e >= eventsPerChunk) {
            val json = build()
            reset()
            return json
        }
        val local = tMs - e * eventMs
        val p = (local * POINTS / eventMs).coerceIn(0, POINTS - 1)
        ampSum[e][p] += amp
        heSum[e][p] += he.coerceIn(0, 100).toDouble()
        count[e][p]++
        tMs += dtMs.coerceAtLeast(1)
        return null
    }

    /** 音量突增检测：幅度较上一帧跳升超过 [ONSET_DELTA] 且过 [ACCENT_REFRACTORY_MS] 抑制窗。 */
    private fun detectAccent(amp: Float, timeInChunkMs: Int) {
        val rising = amp - prevAmp
        if (rising >= ONSET_DELTA && amp >= ONSET_MIN &&
            (clockMs - lastAccentClock) >= ACCENT_REFRACTORY_MS
        ) {
            accents.add(timeInChunkMs)
            lastAccentClock = clockMs
        }
    }

    /** 立即取出当前（可能不完整）的分块；尾部全空的事件会被裁掉，无任何采样返回 null 并复位。 */
    fun flush(): String? {
        var lastNonEmpty = -1
        for (e in 0 until eventsPerChunk) {
            for (p in 0 until POINTS) {
                if (count[e][p] > 0) { lastNonEmpty = e; break }
            }
        }
        val json = if (lastNonEmpty >= 0) build(lastNonEmpty + 1) else null
        reset()
        return json
    }

    private fun build(eventCount: Int = eventsPerChunk): String {
        val events = ArrayList<RichTapHe.Event>(eventCount)
        var lastAmp = 0.0
        var lastHe = RichTapFrequency.HE_AT_RESONANCE.toDouble()
        for (e in 0 until eventCount) {
            var heAll = 0.0
            var cAll = 0
            for (p in 0 until POINTS) { heAll += heSum[e][p]; cAll += count[e][p] }
            val base = if (cAll > 0) {
                (heAll / cAll).roundToInt().coerceIn(BASE_MIN, BASE_MAX)
            } else {
                lastHe.roundToInt().coerceIn(BASE_MIN, BASE_MAX)
            }
            val points = ArrayList<RichTapHe.CurvePoint>(POINTS)
            for (p in 0 until POINTS) {
                val time = p * eventMs / (POINTS - 1)
                val amp: Double
                val he: Double
                if (count[e][p] > 0) {
                    amp = (ampSum[e][p] / count[e][p]).toDouble()
                    he = heSum[e][p] / count[e][p]
                } else {
                    // 空桶沿用上一控制点，保证曲线连续且始终 4 点。
                    amp = lastAmp
                    he = lastHe
                }
                lastAmp = amp
                lastHe = he
                // 分块边界起振补偿：只抬升首事件、并在事件内线性衰减回 1。
                val target = if (e == 0) (amp * seamBoostAt(p)).coerceAtMost(1.0) else amp
                // 引擎把曲线强度按次方律 (a = c·1.14^(10(c-1))) 转成驱动幅度，这里做逆变换，
                // 使 LRA 的实际位移幅度线性跟随原 PCM 的包络。
                val intensity = RichTapEngine.amplitudeToCurve(target)
                points.add(RichTapHe.CurvePoint(time, intensity, (he - base).coerceIn(-MAX_OFFSET, MAX_OFFSET)))
            }
            events.add(RichTapHe.Event(e * eventMs, eventMs, base, points))
        }
        appendAccents(events, eventCount)
        return RichTapHe.pattern(events)
    }

    /**
     * 把检测到的音量突增点，作为**满强度** [accentMs] 短事件追加进效果（受真机事件数上限约束）。
     * 曲线 4 点全为 1.0（`amplitudeToCurve(1.0)=1.0`），即“最强震动”。
     */
    private fun appendAccents(events: ArrayList<RichTapHe.Event>, eventCount: Int) {
        if (accentMs <= 0 || accents.isEmpty()) return
        val maxTime = eventCount * eventMs
        for (a in accents) {
            if (a < 0 || a >= maxTime) continue
            if (events.size >= MAX_EVENTS) break
            val pts = ArrayList<RichTapHe.CurvePoint>(POINTS)
            for (p in 0 until POINTS) {
                pts.add(RichTapHe.CurvePoint(p * accentMs / (POINTS - 1), 1.0, 0.0))
            }
            events.add(RichTapHe.Event(a, accentMs, RichTapFrequency.HE_AT_RESONANCE, pts))
        }
        events.sortBy { it.relativeTimeMs }
    }

    /** 首事件第 [p] 个控制点的补偿系数：p=0 为 [seamBoost]，线性衰减到末点为 1。 */
    private fun seamBoostAt(p: Int): Double {
        if (seamBoost <= 1.0) return 1.0
        return 1.0 + (seamBoost - 1.0) * (1.0 - p.toDouble() / (POINTS - 1))
    }
}
