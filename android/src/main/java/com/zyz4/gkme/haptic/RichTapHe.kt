package com.zyz4.gkme.haptic

/**
 * 构造 RichTap HE 1.0 动态效果 JSON。
 *
 * 设备端 `android.os.DynamicEffect.create(String)` 只认 HE 1.0（顶层 `Pattern`），且每条
 * `Curve` 会被裁成 4 点，因此这里直接产出 4 点曲线，避免依赖 SDK 的 HE2.0→HE1.0 转换。
 *
 * 约定：
 * - `Parameters.Intensity` 为 0-100，`Curve[].Intensity` 为 0-1（相对强度包络）。
 * - `Parameters.Frequency` 为 0-100，`Curve[].Frequency` 为频率偏移。
 * - 具体的全局强度/频率由 `HapticPlayer.start(loop, interval, amplitude, freq)` 覆盖。
 */
object RichTapHe {

    /** 协议约定每条曲线最多保留 4 点。 */
    private const val POINT_COUNT = 4

    /**
     * 恒定（近似方波）连续效果：整段保持同一强度，适合游戏 rumble 这类持续震动。
     *
     * @param durationMs 单次播放时长，调用方可循环播放。
     */
    fun continuous(frequency: Int, durationMs: Int): String {
        val dur = durationMs.coerceAtLeast(20)
        val head = dur / 8
        val tail = (dur - dur / 8).coerceAtLeast(head)
        return build(
            type = "continuous",
            frequency = frequency,
            durationMs = dur,
            intensity = 100,
            curve = listOf(0 to 1.0, head to 1.0, tail to 1.0, dur to 1.0),
        )
    }

    /**
     * 短促的“点击”效果：快速起振后衰减，比普通 one-shot 更有质感。
     *
     * @param strength  相对强度 0-100。
     * @param frequency 频率 0-100。
     */
    fun click(strength: Int, frequency: Int): String {
        val dur = 40
        return build(
            // 设备端目前只稳定解析 continuous 事件（transient 会得到空效果），
            // 因此用一条短 continuous 曲线模拟点击。
            type = "continuous",
            frequency = frequency,
            durationMs = dur,
            intensity = strength.coerceIn(0, 100),
            curve = listOf(0 to 0.0, 4 to 1.0, 16 to 0.6, dur to 0.0),
        )
    }

    /**
     * 带时间曲线的连续效果：单次效果内体现音高/强度变化。
     *
     * @param durationMs 效果总时长。
     * @param baseFreq   基础 HE 频率（0-100）；曲线里的频率偏移相对它叠加。
     * @param points     控制点（时间 ms、相对强度 0-1、频率偏移），最多 4 点（本机只认 4 点）。
     */
    fun curve(durationMs: Int, baseFreq: Int, points: List<CurvePoint>): String {
        val dur = durationMs.coerceAtLeast(1)
        val base = baseFreq.coerceIn(0, 100)
        val pts = points.take(POINT_COUNT).joinToString(",") { p ->
            "{\"Frequency\":${fmt(p.freqOffset)},\"Intensity\":${fmt(p.intensity.coerceIn(0.0, 1.0))},\"Time\":${p.timeMs.coerceIn(0, dur)}}"
        }
        return "{" +
            "\"Metadata\":{\"Created\":\"gkme\",\"Description\":\"hd\",\"Version\":1}," +
            "\"Pattern\":[{" +
            "\"Event\":{" +
            "\"Type\":\"continuous\"," +
            "\"RelativeTime\":0," +
            "\"Duration\":$dur," +
            "\"Parameters\":{" +
            "\"Frequency\":$base," +
            "\"Intensity\":100," +
            "\"Curve\":[$pts]" +
            "}}}]}"
    }

    /** 曲线控制点：[timeMs] 时间、[intensity] 相对强度 0-1、[freqOffset] HE 频率偏移。 */
    data class CurvePoint(val timeMs: Int, val intensity: Double, val freqOffset: Double)

    /**
     * 多事件效果中的一个 `continuous` 事件。
     *
     * @param relativeTimeMs 相对效果起点的开始时间。
     * @param durationMs     事件时长（必须 ≤ 5000ms）。
     * @param baseFreq       基础 HE 频率 0-100；[points] 的频率为相对它的偏移。
     * @param points         控制点；**必须恰好 4 个**（真机实测：非 4 点会使 HAL 报
     *                       `Invalid time param` 并导致整条效果无输出）。
     */
    data class Event(
        val relativeTimeMs: Int,
        val durationMs: Int,
        val baseFreq: Int,
        val points: List<CurvePoint>,
    )

    /**
     * 构造一条包含多个 `continuous` 事件的 HE 1.0 效果（顶层 `Pattern` 数组）。
     *
     * 真机实测：单条效果**最多 16 个事件**，超出会被 HAL 整条丢弃；每个事件的 `Duration`
     * 必须 ≤ 5000ms。事件由引擎内部调度，因此相比反复 `stop()+start()` 分段投递，段与段
     * 之间没有切换凹陷。
     */
    fun pattern(events: List<Event>): String {
        val body = events.joinToString(",") { e ->
            val dur = e.durationMs.coerceIn(1, 5000)
            val pts = e.points.take(POINT_COUNT).joinToString(",") { p ->
                "{\"Frequency\":${fmt(p.freqOffset)}," +
                    "\"Intensity\":${fmt(p.intensity.coerceIn(0.0, 1.0))}," +
                    "\"Time\":${p.timeMs.coerceIn(0, dur)}}"
            }
            "{" +
                "\"Event\":{" +
                "\"Type\":\"continuous\"," +
                "\"RelativeTime\":${e.relativeTimeMs.coerceAtLeast(0)}," +
                "\"Duration\":$dur," +
                "\"Parameters\":{" +
                "\"Frequency\":${e.baseFreq.coerceIn(0, 100)}," +
                "\"Intensity\":100," +
                "\"Curve\":[$pts]" +
                "}}}"
        }
        return "{" +
            "\"Metadata\":{\"Created\":\"gkme\",\"Description\":\"hd\",\"Version\":1}," +
            "\"Pattern\":[$body]}"
    }

    private fun build(
        type: String,
        frequency: Int,
        durationMs: Int,
        intensity: Int,
        curve: List<Pair<Int, Double>>,
    ): String {
        val pts = curve.take(POINT_COUNT).joinToString(",") { (t, v) ->
            "{\"Frequency\":0.0,\"Intensity\":${fmt(v)},\"Time\":$t}"
        }
        return "{" +
            "\"Metadata\":{\"Created\":\"gkme\",\"Description\":\"hd\",\"Version\":1}," +
            "\"Pattern\":[{" +
            "\"Event\":{" +
            "\"Type\":\"$type\"," +
            "\"RelativeTime\":0," +
            "\"Duration\":$durationMs," +
            "\"Parameters\":{" +
            "\"Frequency\":${frequency.coerceIn(0, 100)}," +
            "\"Intensity\":${intensity.coerceIn(0, 100)}," +
            "\"Curve\":[$pts]" +
            "}}}]}"
    }

    private fun fmt(v: Double): String =
        if (v == v.toInt().toDouble()) "${v.toInt()}.0" else v.toString()
}
