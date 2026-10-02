package com.zyz4.gkme.haptic

/**
 * RichTap 引擎 raw `int[]` 编码器（type 2「RichTapPerformer」用）。
 *
 * 逆向自 RichTap ASDK 2.2.0 `com.apprichtap.haptic.base.b.a(String, version, core, minor, pid, sid, swap)`。
 * SDK 的 type 2 路径把 HE 解析成 raw int[]，再交给框架隐藏类
 * `android.os.RichTapVibrationEffect` / `richtap.os.PhonyVibrationEffect` 的
 * `createPatternHeWithParam(int[])` 下发。本对象复刻其 **HE1.0 与 HE2.0** 两条编码分支。
 *
 * 与 [RichTapHe]（只产 HE1.0 JSON、给 type 1 的 `DynamicEffect`）不同，这里是编码成引擎 raw 数组。
 *
 * 纯 JVM，可单测。
 */
object RichTapRawCodec {

    /** 连续事件标签（SDK 常量 w）。 */
    const val TAG_CONTINUOUS = 4096

    /** 瞬态事件标签（SDK 常量 x）。 */
    const val TAG_TRANSIENT = 4097

    const val TYPE_CONTINUOUS = "continuous"
    const val TYPE_TRANSIENT = "transient"

    private const val DEFAULT_FREQUENCY = 56
    private const val MAX_EVENTS = 16
    private const val MAX_POINTS = 16
    private const val POINT_SCALE = 100.0

    /** 曲线控制点：时间 ms、相对强度 0-1、HE 频率偏移。 */
    data class CurvePoint(val timeMs: Int, val scale: Double, val freqOffset: Int)

    /** 一个事件的参数：基础强度 0-100、基础频率 0-100（-1 表示缺省）。 */
    data class EventParams(val intensity: Int, val frequency: Int, val curve: List<CurvePoint>)

    /** HE 事件。 */
    data class HeEvent(
        val type: String,
        val relativeTimeMs: Int,
        val durationMs: Int,
        val vibrationId: Int,
        val params: EventParams,
    )

    /** HE2.0 的一个 pattern（绝对时间 + 事件列表）。 */
    data class HePattern(val absoluteTimeMs: Int, val events: List<HeEvent>)

    /**
     * 编码为 raw int[]。
     *
     * @param events          HE1.0 事件（GKME 产出的就是 HE1.0）。
     * @param heVersion       1 = HE1.0；2 = HE2.0。SDK 依 core 版本选择：core ≤ 23 → 1，否则 2。
     * @param coreMajorRichTap RichTap core 主版本（checkIfRichTapSupport 解出）。
     * @param minorRichTap    RichTap core 次版本。
     * @param pid             发送者进程 id（仅 HE2.0 头部使用）。
     * @param sid             发送者 id（仅 HE2.0 头部使用）。
     * @param swapVibrationIndex 是否交换左右马达索引。
     * @param patterns        显式 HE2.0 pattern 列表；为 null 时把 [events] 包成单个 pattern。
     * @return raw int[]；无法编码时返回 null。
     */
    fun encode(
        events: List<HeEvent>,
        heVersion: Int,
        coreMajorRichTap: Int,
        minorRichTap: Int,
        pid: Int,
        sid: Int,
        swapVibrationIndex: Boolean,
        patterns: List<HePattern>? = null,
    ): IntArray? {
        val out = ArrayList<Int>(64)
        try {
            if (heVersion == 1) {
                encodeHe10(out, events, coreMajorRichTap)
            } else {
                // SDK：HE2.0 要求 core > 24
                if (coreMajorRichTap < 24) return null
                encodeHe20(out, patterns ?: listOf(HePattern(0, events)), coreMajorRichTap, minorRichTap, pid, sid, swapVibrationIndex)
            }
        } catch (_: Throwable) {
            return null
        }
        if (out.isEmpty()) return null
        return IntArray(out.size) { out[it] }
    }

    /** SDK HE1.0 分支（`i2 == 1`）。 */
    private fun encodeHe10(out: ArrayList<Int>, events: List<HeEvent>, coreMajor: Int) {
        out.add(if (coreMajor > 24) 3 else 1)
        val size = minOf(events.size, MAX_EVENTS)
        for (i in 0 until size) {
            val e = events[i]
            when (e.type) {
                TYPE_TRANSIENT -> {
                    out.add(TAG_TRANSIENT)
                    out.add(e.relativeTimeMs)
                    out.add(e.params.intensity)
                    out.add(e.params.frequency)
                    out.add(e.durationMs)
                    if (coreMajor < 24) {
                        repeat(12) { out.add(0) }
                    } else {
                        out.add(0)
                        out.add(0)
                        repeat(48) { out.add(0) }
                    }
                }

                TYPE_CONTINUOUS -> {
                    val n = e.params.curve.size
                    val condA = coreMajor >= 24 || n == 4
                    val condB = coreMajor < 24 || n in 4..MAX_POINTS
                    if (condA && condB) {
                        out.add(TAG_CONTINUOUS)
                        out.add(e.relativeTimeMs)
                        out.add(e.params.intensity)
                        out.add(if (e.params.frequency == -1) DEFAULT_FREQUENCY else e.params.frequency)
                        out.add(e.durationMs)
                        if (coreMajor < 24) {
                            for (pt in e.params.curve) {
                                out.add(pt.timeMs)
                                out.add((pt.scale * POINT_SCALE).toInt())
                                out.add(if (e.params.frequency == -1) 0 else pt.freqOffset)
                            }
                        } else {
                            out.add(0)
                            out.add(n)
                            for (pt in e.params.curve) {
                                out.add(pt.timeMs)
                                out.add((pt.scale * POINT_SCALE).toInt())
                                out.add(if (e.params.frequency == -1) 0 else pt.freqOffset)
                            }
                            repeat((MAX_POINTS - n) * 3) { out.add(0) }
                        }
                    }
                }

                else -> Unit // 未知类型跳过（同 SDK）
            }
        }
    }

    /** SDK HE2.0 分支（`i2 == 2`）。 */
    private fun encodeHe20(
        out: ArrayList<Int>,
        patterns: List<HePattern>,
        coreMajor: Int,
        minor: Int,
        pid: Int,
        sid: Int,
        swap: Boolean,
    ) {
        out.add(2)
        out.add(2)
        out.add(pid)
        out.add(sid)
        val pc = patterns.size
        out.add((pc and 0xFFFF) or ((pc shl 16) and -0x10000))
        for ((index, pattern) in patterns.withIndex()) {
            out.add(index)
            out.add(pattern.absoluteTimeMs)
            val events = pattern.events
            val size = minOf(events.size, MAX_EVENTS)
            out.add(size)
            for (i in 0 until size) {
                val e = events[i]
                when (e.type) {
                    TYPE_TRANSIENT -> {
                        out.add(TAG_TRANSIENT)
                        out.add(5)
                        out.add(swapId(e.vibrationId, swap))
                        out.add(e.relativeTimeMs)
                        out.add(e.params.intensity)
                        out.add(e.params.frequency)
                        out.add(e.durationMs)
                    }

                    TYPE_CONTINUOUS -> {
                        val n = e.params.curve.size
                        if (n in 4..MAX_POINTS) {
                            out.add(TAG_CONTINUOUS)
                            out.add(n * 3 + 6)
                            out.add(swapId(e.vibrationId, swap))
                            out.add(e.relativeTimeMs)
                            out.add(e.params.intensity)
                            val extended = coreMajor > 32 || minor > 16
                            if (extended) {
                                out.add(e.params.frequency)
                            } else {
                                out.add(if (e.params.frequency == -1) DEFAULT_FREQUENCY else e.params.frequency)
                            }
                            out.add(e.durationMs)
                            out.add(n)
                            for (pt in e.params.curve) {
                                out.add(pt.timeMs)
                                out.add((pt.scale * POINT_SCALE).toInt())
                                if (extended) {
                                    out.add(pt.freqOffset)
                                } else {
                                    out.add(if (e.params.frequency == -1) 0 else pt.freqOffset)
                                }
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun swapId(id: Int, swap: Boolean): Int =
        if (!swap) id else when (id) {
            0 -> 0
            1 -> 2
            else -> 1
        }
}
