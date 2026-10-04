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
 * 因此这里把时间轴切成 [eventsPerChunk] 个 [eventMs] 长的事件，每个事件压成 4 个控制点。
 *
 * **控制点时间必须聚到事件两端**（`0, ε, eventMs-ε, eventMs`）而不是均匀分布
 * （`0, eventMs/3, 2eventMs/3, eventMs`）。真机加速度计实测：均匀分布时厂商引擎会给
 * 每个事件叠加一段**与其时长成比例的平滑起振/衰减**（约 25–30% 时长），于是每个事件边界
 * 都出现明显掉幅（200ms 事件约 45ms 下陷、500ms 约 140ms、4000ms 约 1.2s 缓慢升降）；
 * 而把控制点聚到两端后，事件内部保持平直、相邻事件无缝衔接（16×100ms 单条效果经加速度计
 * 测得包络 0.97–1.01，完全连续）。见 `docs/richtap-hd-vibration.md` §13。
 *
 * 每个事件用**两个电平**（事件起点沿用上一事件收尾电平、终点为本事件聚合电平）线性过渡，
 * 因此整条效果是一条连续的分段线性包络，事件间无跳变。纯 JVM 代码，便于单测。
 */
class PcmHeEncoder(
    private val eventsPerChunk: Int = 16,
    private val eventMs: Int = 200,
    /**
     * 音量突增强调：检测到**突然增大**时，在对应时刻额外插入一条 [accentMs] 时长的
     * **满强度** `continuous` 事件（引擎内部调度，与常规事件可重叠），让 LRA 强震一下。
     * 0 = 关闭。
     */
    private val accentMs: Int = 0,
    /**
     * 短促瞬态响应：PC 会下发时长极短（≤[BURST_MAX_MS] ms）、且总是**从静音突变而来**的音频。
     * 本项识别这种“静音 → 短促有声 → 静音”的瞬态，在 200ms 分块内对应时刻额外插入一条
     * [burstMs] 时长的**满强度**事件，把这类“咔哒/敲击”还原成单次短促强震，一次瞬态只出一条。
     * 0 = 关闭。
     *
     * 与 [accentMs] 的区别：[accentMs] 对任意突然增大都会触发（不关心后续是否持续）；
     * 本项只在**短促且以静音收尾**的瞬态上触发。
     */
    private val burstMs: Int = 0,
) {

    private companion object {
        const val POINTS = 4
        const val MAX_OFFSET = 60.0

        /**
         * 事件两端控制点的内缩量（ms）：4 个控制点的时间放在 `0, ε, eventMs-ε, eventMs`。
         * 真机实测 ε=1–2ms 时事件起振/衰减基本消失；取 1–4ms 内、且不超过事件时长的 1/4。
         */
        const val EDGE_POINT_MS = 2
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

        /** 视为静音的幅度上限（0..1）；低于它即认为音频回到静音。 */
        const val BURST_SILENCE_AMP = 0.04f

        /**
         * 触发短促瞬态所需的最小峰值幅度（0..1）。
         *
         * 真机录音实测：这类短音经语音线圈 RMS 归一化后大多只有 0.10–0.15（远低于早期
         * 0.15 的门限），因此门限取在 HdPcmStreamer 的静音门（≈0.016）之上、短音之下。
         */
        const val BURST_MIN_AMP = 0.06f

        /**
         * 短促瞬态的**有效时长**上限（ms）：从首次起振到最后一个有效峰，超过即判为持续音。
         * 目标音源 ≤50ms，这里留出帧量化余量（RMS 帧边界最多使测量值多出一帧），取 60ms。
         */
        const val BURST_MAX_MS = 60

        /**
         * 瞬态内部的静音容限（ms）：短音常包含“主峰 → 短暂凹陷 → 余响/回声”（真机录音约
         * 20–35ms 凹陷）。凹陷不超过此值仍视为同一次瞬态，避免一次短音被拆成多条脉冲。
         */
        const val BURST_GAP_MS = 40

        /** 两次瞬态之间的最小间隔（ms），避免一次事件被幅度抖动拆成多条。 */
        const val BURST_REFRACTORY_MS = 40

        /**
         * 桶内幅度的**峰值保持**混合系数（0..1）。
         *
         * 一个事件的 4 个控制点各覆盖 [eventMs]/3 的时间；若只取桶内均值，落在桶内的
         * 攻击/峰值会被邻近平稳样本拉低，包络发闷。这里取 `max(均值, 峰值×本系数)`，
         * 在保留峰值的同时避免单点噪声把整段抬高。1.0 = 纯峰值，0 = 纯均值。
         */
        const val PEAK_MIX = 0.85f
    }

    private val ampSum = Array(eventsPerChunk) { FloatArray(POINTS) }
    private val ampMax = Array(eventsPerChunk) { FloatArray(POINTS) }
    private val heSum = Array(eventsPerChunk) { DoubleArray(POINTS) }
    private val count = Array(eventsPerChunk) { IntArray(POINTS) }
    private var tMs = 0

    // 音量突增检测状态（跨分块保留 prevAmp，避免分块重置被误判为突增）。
    private var prevAmp = 0f
    private var clockMs = 0L
    private var lastAccentClock = -1_000_000L

    // 分块边界跨接状态：上一分块收尾的（幅度, HE）。跨 [reset] 保留，供下一分块的首事件
    // 从该电平淡入，消除 stop()+start() 边界的跳变。仅在整条流真正停止/复位时清除。
    private var seamAmp = 0.0
    private var seamHe = RichTapFrequency.HE_AT_RESONANCE.toDouble()

    // 短促瞬态检测状态（同样跨分块保留，避免分块边界把一次瞬态截断）。
    // [armed] 只在“观察到静音”后才为 true：一次瞬态被判定为持续音后会解除武装，
    // 直到再次静音才允许识别下一次瞬态，避免持续音被反复当作候选并在流结束时误报。
    private var inBurst = false
    private var armed = true
    private var burstStartClock = 0L
    private var burstStartInChunk = 0
    private var burstLastActiveClock = 0L
    private var burstPeak = 0f
    private var lastBurstClock = -1_000_000L

    /** 当前是否有正在累积的短促瞬态（供 [HdPcmStreamer] 决定静音时是否立即冲刷）。 */
    val isBurstActive: Boolean get() = inBurst

    // 额外插入的满强度短事件（音量突增强调 / 短促瞬态响应）。
    private val pulses = ArrayList<Pulse>()

    /** 一条额外插入的满强度短事件。[timeMs] 为分块内起点，[durationMs] 为时长。 */
    private class Pulse(val timeMs: Int, val durationMs: Int, val intensity: Double)

    /** 一个完整分块的时长（毫秒）。 */
    val chunkMs: Int get() = eventsPerChunk * eventMs

    /** 效果时长（含首尾），供调用方安排重投递节奏。 */
    val effectMs: Int get() = chunkMs

    fun reset() {
        for (e in 0 until eventsPerChunk) {
            java.util.Arrays.fill(ampSum[e], 0f)
            java.util.Arrays.fill(ampMax[e], 0f)
            java.util.Arrays.fill(heSum[e], 0.0)
            java.util.Arrays.fill(count[e], 0)
        }
        tMs = 0
        pulses.clear()
        inBurst = false
        burstPeak = 0f
        burstLastActiveClock = 0L
        // 注意：prevAmp / clockMs / seamAmp / seamHe 故意保留，避免分块切换被误判为
        // “音量突增”，并让跨接淡入在分块边界连续。
    }

    /**
     * 整条流复位：清空分块缓冲并**重置跨接状态**。[reset] 会保留上一分块的收尾电平供
     * 跨接使用，只有在流真正停止/重连（不再是连续播放）时才应调用本方法，避免下一段音频
     * 从陈旧电平淡入。
     */
    fun resetStream() {
        reset()
        seamAmp = 0.0
        seamHe = RichTapFrequency.HE_AT_RESONANCE.toDouble()
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
        if (e < eventsPerChunk) {
            if (accentMs > 0) detectAccent(amp, tMs)
            if (burstMs > 0) detectBurst(amp, tMs)
        }
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
        if (amp > ampMax[e][p]) ampMax[e][p] = amp
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
            pulses.add(Pulse(timeInChunkMs, accentMs, 1.0))
            lastAccentClock = clockMs
        }
    }

    /**
     * 短促瞬态检测：识别“静音 → 有声 → 静音”且总时长 ≤ [BURST_MAX_MS] 的音频段。
     * 触发时登记一条位于起点的满强度短事件（一次事件只出一条，受 [BURST_REFRACTORY_MS] 抑制）。
     */
    private fun detectBurst(amp: Float, timeInChunkMs: Int) {
        if (amp >= BURST_MIN_AMP) {
            if (!inBurst) {
                if (armed) {
                    inBurst = true
                    burstStartClock = clockMs
                    burstStartInChunk = timeInChunkMs
                    burstLastActiveClock = clockMs
                    burstPeak = amp
                }
            } else {
                if (amp > burstPeak) burstPeak = amp
                burstLastActiveClock = clockMs
                // 有效时长超限：判为持续音、解除武装并放弃（连续音也必须在此判定）。
                if (burstLastActiveClock - burstStartClock > BURST_MAX_MS) {
                    inBurst = false
                    armed = false
                }
            }
            return
        }
        if (inBurst) {
            if (burstLastActiveClock - burstStartClock > BURST_MAX_MS) {
                // 有效时长超限：判为持续音、解除武装并放弃。
                inBurst = false
                armed = false
            } else if (clockMs - burstLastActiveClock >= BURST_GAP_MS) {
                // 凹陷超过容限：一次瞬态结束，登记并重新武装。
                inBurst = false
                registerBurst()
                armed = true
            }
        } else if (amp < BURST_SILENCE_AMP) {
            armed = true
        }
    }

    /** 当前（或刚结束的）瞬态是否满足“短促且达到最小峰值”（以最后一个有效峰计有效时长）。 */
    private fun isShortBurst(): Boolean =
        burstPeak >= BURST_MIN_AMP &&
            (burstLastActiveClock - burstStartClock) <= BURST_MAX_MS

    /** 把当前瞬态登记为一条满强度短事件（不满足条件或处于抑制窗内则不登记）。 */
    private fun registerBurst() {
        if (!isShortBurst()) return
        if (clockMs - lastBurstClock < BURST_REFRACTORY_MS) return
        lastBurstClock = clockMs
        pulses.add(Pulse(burstStartInChunk.coerceAtLeast(0), burstMs, 1.0))
    }

    /** 分块收尾：处理仍处于“进行中”的瞬态（其后没有跟踪到静音帧，如流结束时）。 */
    private fun finalizeBurst() {
        if (!inBurst) return
        inBurst = false
        registerBurst()
    }

    /** 立即取出当前（可能不完整）的分块；尾部全空的事件会被裁掉，无任何采样返回 null 并复位。 */
    fun flush(): String? {
        // 冲刷时机通常就是静音（见 HdPcmStreamer）：若瞬态仍在进行中，说明短音已结束，
        // 收尾判定它是否属于“短促瞬态”；随后重新武装，等待下一次瞬态。
        finalizeBurst()
        armed = true
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
        // 首事件从上一分块的收尾电平起（[seamAmp]/[seamHe]，[reset] 会保留），跨界连续。
        var lastAmp = seamAmp
        var lastHe = seamHe
        for (e in 0 until eventCount) {
            // 事件级聚合：均值与峰值保持的较大者（[PEAK_MIX]）。
            var ampMeanSum = 0.0
            var ampPeak = 0.0
            var heAll = 0.0
            var cAll = 0
            for (p in 0 until POINTS) {
                val c = count[e][p]
                if (c <= 0) continue
                ampMeanSum += ampSum[e][p]
                ampPeak = maxOf(ampPeak, ampMax[e][p].toDouble() * PEAK_MIX)
                heAll += heSum[e][p]
                cAll += c
            }
            val ampE: Double
            val heE: Double
            if (cAll > 0) {
                ampE = maxOf(ampMeanSum / cAll, ampPeak)
                heE = heAll / cAll
            } else {
                // 空事件：沿用上一事件电平，保证曲线连续且始终 4 点。
                ampE = lastAmp
                heE = lastHe
            }
            val base = heE.roundToInt().coerceIn(BASE_MIN, BASE_MAX)
            val edge = (EDGE_POINT_MS).coerceIn(1, (eventMs / 4).coerceAtLeast(1))
            // 控制点聚到事件两端：0/ε 用上一事件收尾电平（起点连续），
            // `eventMs-ε`/`eventMs` 用本事件聚合电平；引擎在两点间线性过渡 → 事件内也平滑。
            val points = ArrayList<RichTapHe.CurvePoint>(POINTS)
            points.add(edgePoint(0, lastAmp, lastHe, base, eventMs))
            points.add(edgePoint(edge, lastAmp, lastHe, base, eventMs))
            points.add(edgePoint(eventMs - edge, ampE, heE, base, eventMs))
            points.add(edgePoint(eventMs, ampE, heE, base, eventMs))
            events.add(RichTapHe.Event(e * eventMs, eventMs, base, points))
            lastAmp = ampE
            lastHe = heE
        }
        // 保存本分块收尾电平，供下一分块跨接（[reset] 会保留）。
        seamAmp = lastAmp
        seamHe = lastHe
        appendPulses(events, eventCount)
        return RichTapHe.pattern(events)
    }

    /** 生成一个控制点：频率偏移相对事件基频 [base]，幅度-频率补偿后再走引擎的次方律逆变换。 */
    private fun edgePoint(
        time: Int,
        amp: Double,
        he: Double,
        base: Int,
        eventMs: Int,
    ): RichTapHe.CurvePoint {
        val offset = (he - base).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        // 幅度-频率补偿：实际驱动频率 = 事件基频 + 曲线偏移，偏离谐振（HE 56）时抬升驱动幅度。
        val effHe = (base + offset).roundToInt().coerceIn(0, 100)
        val target = RichTapEngine.compensateNormalized(amp, effHe)
        // 引擎把曲线强度按次方律 (a = c·1.14^(10(c-1))) 转成驱动幅度，这里做逆变换，
        // 使 LRA 的实际位移幅度线性跟随原 PCM 的包络。
        val intensity = RichTapEngine.amplitudeToCurve(target)
        return RichTapHe.CurvePoint(time.coerceIn(0, eventMs), intensity, offset)
    }

    /**
     * 把额外插入的满强度短事件（音量突增强调 / 短促瞬态响应）追加进效果（受真机事件数上限约束）。
     * 曲线 4 点全为 [Pulse.intensity]（1.0 经 `amplitudeToCurve` 逆变换后仍为 1.0），即“最强震动”。
     */
    private fun appendPulses(events: ArrayList<RichTapHe.Event>, eventCount: Int) {
        if (pulses.isEmpty()) return
        val maxTime = eventCount * eventMs
        for (pulse in pulses) {
            if (pulse.timeMs < 0 || pulse.timeMs >= maxTime) continue
            if (events.size >= MAX_EVENTS) break
            val dur = pulse.durationMs.coerceAtLeast(1)
            // 控制点同样聚到两端，避免短脉冲被引擎加上成比例的起振/衰减。
            val edge = EDGE_POINT_MS.coerceIn(1, (dur / 4).coerceAtLeast(1))
            val pts = listOf(
                RichTapHe.CurvePoint(0, pulse.intensity, 0.0),
                RichTapHe.CurvePoint(edge, pulse.intensity, 0.0),
                RichTapHe.CurvePoint(dur - edge, pulse.intensity, 0.0),
                RichTapHe.CurvePoint(dur, pulse.intensity, 0.0),
            )
            events.add(RichTapHe.Event(pulse.timeMs, dur, RichTapFrequency.HE_AT_RESONANCE, pts))
        }
        events.sortBy { it.relativeTimeMs }
    }
}
