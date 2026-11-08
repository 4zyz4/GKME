package com.zyz4.gkme.haptic

import com.zyz4.gkme.haptic.RichTapRawCodec.CurvePoint
import com.zyz4.gkme.haptic.RichTapRawCodec.EventParams
import com.zyz4.gkme.haptic.RichTapRawCodec.HeEvent
import com.zyz4.gkme.haptic.RichTapRawCodec.HePattern
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** HE 文档元数据。`version` 1 = HE1.0，2 = HE2.0。 */
data class HeMetadata(
    val version: Int,
    val created: String? = null,
    val description: String? = null,
)

/**
 * 统一的 HE 文档模型。
 *
 * HE1.0 用单个 pattern 表示；HE2.0 用多个 pattern（每段带 `AbsoluteTime`）表示。
 * 事件模型复用 [RichTapRawCodec.HeEvent]，因此可以直接喂给 raw 编码器。
 */
data class HeDocument(
    val metadata: HeMetadata,
    val patterns: List<HePattern>,
) {
    val version: Int get() = metadata.version
    val isHe20: Boolean get() = version >= He2Json.VERSION_2 || patterns.size > 1

    fun events(): List<HeEvent> = patterns.flatMap { it.events }
}

/**
 * HE1.0 / HE2.0 JSON 解析与序列化。
 *
 * 解析遵循 RichTap ASDK 2.2.0 的字段语义：
 * - HE1.0：顶层 `Pattern`；
 * - HE2.0：顶层 `PatternList[].Pattern`，每段带 `AbsoluteTime`；
 * - `Index` 只在 HE2.0 中存在，HE1.0 默认 0；
 * - HE2.0 的 `transient` 事件若省略 `Duration`，SDK 补 48ms；
 * - 曲线强度 0..1，频率偏移为整数（SDK `getInt` 会截断小数，这里四舍五入以保留更多信息）。
 */
object He2Json {

    const val VERSION_1 = 1
    const val VERSION_2 = 2

    const val MAX_POINTS = 16
    const val MAX_EVENTS = 16
    const val MAX_PATTERNS = 4
    const val TRANSIENT_DURATION_MS = 48

    // ── 解析 ──────────────────────────────────────────────────────────────

    fun parse(json: String): HeDocument? = try {
        val root = JSONObject(json)
        val metaObj = root.optJSONObject("Metadata")
        val version = metaObj?.optInt("Version", 0) ?: 0
        val created = metaObj?.optString("Created")?.takeIf { it.isNotEmpty() }
        val description = metaObj?.optString("Description")?.takeIf { it.isNotEmpty() }
        val patternList = root.optJSONArray("PatternList")
        val patternArray = root.optJSONArray("Pattern")

        when {
            patternList != null -> {
                val patterns = ArrayList<HePattern>()
                for (i in 0 until patternList.length()) {
                    val p = patternList.optJSONObject(i) ?: continue
                    val abs = p.optInt("AbsoluteTime", 0).coerceAtLeast(0)
                    val arr = p.optJSONArray("Pattern") ?: continue
                    patterns.add(HePattern(abs, parseEvents(arr, VERSION_2)))
                }
                if (patterns.isEmpty()) null
                else HeDocument(HeMetadata(if (version >= VERSION_2) version else VERSION_2, created, description), patterns)
            }

            patternArray != null -> {
                val events = parseEvents(patternArray, VERSION_1)
                if (events.isEmpty()) null
                else HeDocument(
                    HeMetadata(if (version > 0) version else VERSION_1, created, description),
                    listOf(HePattern(0, events)),
                )
            }

            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun parseEvents(arr: JSONArray, version: Int): List<HeEvent> {
        val out = ArrayList<HeEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val ev = item.optJSONObject("Event") ?: continue
            val type = ev.optString("Type", "continuous")
            val relative = ev.optInt("RelativeTime", 0).coerceAtLeast(0)
            val index = ev.optInt("Index", 0).coerceAtLeast(0)
            val paramsObj = ev.optJSONObject("Parameters")
            val frequency = paramsObj?.optInt("Frequency", -1) ?: -1
            val intensity = paramsObj?.optInt("Intensity", 100) ?: 100

            val duration = if (type == "transient") {
                ev.optInt("Duration", if (version >= VERSION_2) TRANSIENT_DURATION_MS else 0)
            } else {
                ev.optInt("Duration", 0)
            }
            val curve = if (type == "continuous") parseCurve(paramsObj?.optJSONArray("Curve")) else emptyList()

            out.add(
                HeEvent(
                    type = if (type == "transient") "transient" else "continuous",
                    relativeTimeMs = relative,
                    durationMs = duration.coerceAtLeast(0),
                    vibrationId = index,
                    params = EventParams(
                        intensity = intensity.coerceIn(0, 100),
                        frequency = frequency,
                        curve = curve,
                    ),
                )
            )
        }
        return out
    }

    private fun parseCurve(arr: JSONArray?): List<CurvePoint> {
        if (arr == null) return emptyList()
        val out = ArrayList<CurvePoint>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            out.add(
                CurvePoint(
                    timeMs = p.optInt("Time", 0).coerceAtLeast(0),
                    scale = p.optDouble("Intensity", 0.0).coerceIn(0.0, 1.0),
                    freqOffset = p.optDouble("Frequency", 0.0).roundToInt(),
                )
            )
        }
        return out
    }

    // ── 序列化 ────────────────────────────────────────────────────────────

    fun write(document: HeDocument): String =
        if (document.isHe20) writeHe20(document) else writeHe10(document)

    /** 写成设备可解析的 HE1.0；HE2.0 会先做 best-effort 转换。 */
    fun writeHe10(document: HeDocument): String {
        val normalized = He2Converter.toHe10BestEffort(document) ?: document
        val events = normalized.patterns.firstOrNull()?.events.orEmpty()
        val root = JSONObject()
        root.put(
            "Metadata",
            JSONObject().apply {
                put("Created", normalized.metadata.created ?: "gkme")
                put("Description", normalized.metadata.description ?: "hd")
                put("Version", VERSION_1)
            }
        )
        val pattern = JSONArray()
        for (e in events.take(MAX_EVENTS)) {
            pattern.put(JSONObject().put("Event", writeEvent(e, VERSION_1)))
        }
        root.put("Pattern", pattern)
        return root.toString()
    }

    /** 写成原生 HE2.0（保留多 pattern、Index、最多 16 点曲线）。 */
    fun writeHe20(document: HeDocument): String {
        val normalized = He2Converter.normalizeForHe20(document) ?: document
        val root = JSONObject()
        root.put(
            "Metadata",
            JSONObject().apply {
                put("Created", normalized.metadata.created ?: "gkme")
                put("Description", normalized.metadata.description ?: "hd")
                put("Version", VERSION_2)
            }
        )
        val list = JSONArray()
        for (p in normalized.patterns) {
            val pattern = JSONArray()
            for (e in p.events.take(MAX_EVENTS)) {
                pattern.put(JSONObject().put("Event", writeEvent(e, VERSION_2)))
            }
            list.put(
                JSONObject().apply {
                    put("AbsoluteTime", p.absoluteTimeMs.coerceAtLeast(0))
                    put("Pattern", pattern)
                }
            )
        }
        root.put("PatternList", list)
        return root.toString()
    }

    private fun writeEvent(e: HeEvent, version: Int): JSONObject {
        val ev = JSONObject()
        val type = if (e.type == "transient") "transient" else "continuous"
        ev.put("Type", type)
        if (version >= VERSION_2) ev.put("Index", e.vibrationId)
        ev.put("RelativeTime", e.relativeTimeMs.coerceAtLeast(0))
        // SDK 的 HE 序列化只给 continuous 写 Duration；transient 的 48ms 是解析期默认值。
        if (type == "continuous") {
            ev.put("Duration", e.durationMs.coerceIn(1, 5000))
        }

        val params = JSONObject()
        params.put("Frequency", e.params.frequency)
        params.put("Intensity", e.params.intensity.coerceIn(0, 100))
        if (type == "continuous") {
            val curve = JSONArray()
            for (c in e.params.curve) {
                curve.put(
                    JSONObject().apply {
                        put("Time", c.timeMs)
                        put("Intensity", c.scale.coerceIn(0.0, 1.0))
                        put("Frequency", c.freqOffset)
                    }
                )
            }
            params.put("Curve", curve)
        }
        ev.put("Parameters", params)
        return ev
    }
}

/**
 * HE2.0 ↔ HE1.0 转换与归一化。
 *
 * - [toHe10Sdk]：逐行复刻 SDK `base.b.a(String, true)`（裁剪到 4 点、重叠裁剪、只保留
 *   `PatternList[0]`）。用于对照/回归测试。
 * - [toHe10BestEffort]：type 1 设备的 best-effort 转换。HE2.0 会拍平所有 pattern、
 *   按 SDK 规则裁剪重叠，并补齐 4 点曲线；HE1.0 输入保持原有事件（仅做安全归一化），
 *   以免改变预置效果等已验证的 HE1.0 行为。
 * - [normalizeForHe20]：type 2 raw 下发前的归一化（transient 补 48ms、曲线 4–16 点、
 *   事件/pattern 上限等）。
 */
object He2Converter {

    private const val MAX_EVENTS = He2Json.MAX_EVENTS
    private const val MAX_POINTS = He2Json.MAX_POINTS
    private const val DEFAULT_FREQUENCY = 56

    // ── 曲线 ──────────────────────────────────────────────────────────────

    /**
     * SDK `trim16pTo4p`：>4 点时保留首尾，中间按“两半平均”压成 2 点；≤4 点原样返回。
     * 注意 SDK **不会补齐** 1–3 点曲线。
     */
    fun trimCurveTo4p(curve: List<CurvePoint>): List<CurvePoint> {
        if (curve.size <= 4) return curve
        val n = curve.size
        val n2 = n - 2
        val n3 = n2 / 2
        if (n3 <= 0) return curve

        var t1 = 0
        var s1 = 0.0
        var f1 = 0
        for (i in 1..n3) {
            t1 += curve[i].timeMs
            s1 += curve[i].scale
            f1 += curve[i].freqOffset
        }
        val p1 = CurvePoint(t1 / n3, s1 / n3, f1 / n3)

        val count2 = n2 - n3
        var t2 = 0
        var s2 = 0.0
        var f2 = 0
        for (i in n3 + 1..n2) {
            t2 += curve[i].timeMs
            s2 += curve[i].scale
            f2 += curve[i].freqOffset
        }
        val p2 = CurvePoint(t2 / count2, s2 / count2, f2 / count2)

        return listOf(curve.first(), p1, p2, curve.last())
    }

    /** 把 0–3 点曲线补到 4 点；空曲线按“恒 1.0”处理，避免整条效果无声。 */
    fun padCurveTo4p(curve: List<CurvePoint>, durationMs: Int): List<CurvePoint> {
        val dur = durationMs.coerceAtLeast(1)
        if (curve.size >= 4) return curve
        if (curve.isEmpty()) {
            return listOf(
                CurvePoint(0, 1.0, 0),
                CurvePoint(dur / 3, 1.0, 0),
                CurvePoint(2 * dur / 3, 1.0, 0),
                CurvePoint(dur, 1.0, 0),
            )
        }
        if (curve.size == 1) {
            val p = curve.first()
            return listOf(
                p.copy(timeMs = 0),
                p.copy(timeMs = dur / 3),
                p.copy(timeMs = 2 * dur / 3),
                p.copy(timeMs = dur),
            )
        }
        val out = curve.toMutableList()
        val last = curve.last()
        while (out.size < 4) {
            out.add(last.copy(timeMs = dur))
        }
        var prev = -1
        return out.map { p ->
            val t = p.timeMs.coerceIn(0, dur).coerceAtLeast(prev)
            prev = t
            p.copy(timeMs = t, scale = p.scale.coerceIn(0.0, 1.0))
        }
    }

    /** 线性重采样到 [count] 点，保留首尾。 */
    fun resampleCurve(curve: List<CurvePoint>, count: Int): List<CurvePoint> {
        require(count >= 2)
        if (curve.isEmpty()) return padCurveTo4p(emptyList(), 1).let { resampleCurve(it, count) }
        if (curve.size == count) return curve
        val startT = curve.first().timeMs
        val endT = curve.last().timeMs.coerceAtLeast(startT + 1)
        val out = ArrayList<CurvePoint>(count)
        for (i in 0 until count) {
            val t = startT + ((endT - startT).toLong() * i / (count - 1)).toInt()
            out.add(interpolate(curve, t))
        }
        out[0] = curve.first().copy(timeMs = startT)
        out[count - 1] = curve.last().copy(timeMs = endT)
        return out
    }

    private fun interpolate(curve: List<CurvePoint>, t: Int): CurvePoint {
        if (t <= curve.first().timeMs) return curve.first()
        if (t >= curve.last().timeMs) return curve.last()
        for (i in 0 until curve.size - 1) {
            val a = curve[i]
            val b = curve[i + 1]
            if (t in a.timeMs..b.timeMs) {
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val f = (t - a.timeMs).toDouble() / span
                return CurvePoint(
                    timeMs = t,
                    scale = a.scale + (b.scale - a.scale) * f,
                    freqOffset = (a.freqOffset + (b.freqOffset - a.freqOffset) * f).roundToInt(),
                )
            }
        }
        return curve.last()
    }

    /** HE2 raw 曲线归一化：4–16 点、时间单调、落在事件时长内。 */
    fun normalizeCurveForHe20(curve: List<CurvePoint>, durationMs: Int): List<CurvePoint> {
        val dur = durationMs.coerceAtLeast(1)
        val base = if (curve.isEmpty()) padCurveTo4p(emptyList(), dur) else curve
        val sized = when {
            base.size > MAX_POINTS -> resampleCurve(base, MAX_POINTS)
            base.size < 4 -> padCurveTo4p(base, dur)
            else -> base
        }
        var prev = -1
        return sized.map { p ->
            val t = p.timeMs.coerceIn(0, dur).coerceAtLeast(prev)
            prev = t
            p.copy(timeMs = t, scale = p.scale.coerceIn(0.0, 1.0))
        }
    }

    // ── 重叠裁剪（SDK trimOverlapEvent） ─────────────────────────────────

    /**
     * 复刻 SDK `trimOverlapEvent`：
     * 1. 删除 `Index == 2` 的事件；
     * 2. 按 `RelativeTime` 排序；
     * 3. 相邻事件重叠时：continuous 且 overhang > 48ms → 截断为 `cur.end .. next.end`
     *    并替换成 4 点 `0→1→1→0` 斜坡；否则删除后一个事件。
     */
    fun trimOverlaps(events: List<HeEvent>): List<HeEvent> {
        val out = events.filter { it.vibrationId != 2 }.sortedBy { it.relativeTimeMs }.toMutableList()
        if (out.size <= 1) return out

        val drop = BooleanArray(out.size)
        var i = 0
        var j = 1
        while (i <= out.size - 2 && j <= out.size - 1) {
            val cur = out[i]
            val nxt = out[j]
            val curDur = if (cur.type == "transient") 48 else cur.durationMs.coerceAtLeast(0)
            val nxtDur = if (nxt.type == "transient") 48 else nxt.durationMs.coerceAtLeast(0)
            val curEnd = cur.relativeTimeMs + curDur
            val nxtEnd = nxt.relativeTimeMs + nxtDur

            if (nxt.relativeTimeMs < curEnd) {
                if (nxt.type == "continuous") {
                    val overhang = nxtEnd - curEnd
                    if (nxtEnd > curEnd && overhang > 48) {
                        val d = overhang
                        val ramp = listOf(
                            CurvePoint(0, 0.0, 0),
                            CurvePoint(d / 3, 1.0, 0),
                            CurvePoint(2 * d / 3, 1.0, 0),
                            CurvePoint(d, 0.0, 0),
                        )
                        out[j] = nxt.copy(
                            relativeTimeMs = curEnd,
                            durationMs = d,
                            params = nxt.params.copy(curve = ramp),
                        )
                        i = j
                    } else {
                        drop[j] = true
                    }
                } else {
                    drop[j] = true
                }
            } else {
                i = j
            }
            j++
        }
        return out.filterIndexed { index, _ -> !drop[index] }
    }

    // ── HE1.0 转换 ────────────────────────────────────────────────────────

    /** SDK 逐行兼容转换：只保留 `PatternList[0]`。 */
    fun toHe10Sdk(document: HeDocument): HeDocument? {
        val patterns = document.patterns.map { p ->
            val events = p.events.map { e ->
                if (e.type == "continuous") {
                    e.copy(params = e.params.copy(curve = trimCurveTo4p(e.params.curve)))
                } else {
                    e.copy(params = e.params.copy(frequency = e.params.frequency.coerceIn(0, 100)))
                }
            }
            p.copy(events = trimOverlaps(events))
        }
        val first = patterns.firstOrNull() ?: return null
        val abs = first.absoluteTimeMs
        val events = first.events.map { e ->
            val rel = (e.relativeTimeMs + abs).coerceAtLeast(0)
            val params = if (e.type == "continuous" && e.params.frequency == -1) {
                e.params.copy(
                    frequency = DEFAULT_FREQUENCY,
                    curve = e.params.curve.map { it.copy(freqOffset = 0) },
                )
            } else {
                e.params
            }
            e.copy(relativeTimeMs = rel, params = params)
        }
        return HeDocument(
            HeMetadata(1, document.metadata.created, document.metadata.description),
            listOf(HePattern(0, events)),
        )
    }

    /**
     * type 1 的 best-effort 转换：
     * - HE2.0：拍平所有 pattern、加 AbsoluteTime、按 SDK 规则裁剪重叠、补 4 点曲线、限制 16 事件；
     * - HE1.0：保持事件顺序/重叠关系，仅做曲线与频率的安全归一化（不改变已验证行为）。
     */
    fun toHe10BestEffort(document: HeDocument): HeDocument? {
        val fromHe20 = document.version >= He2Json.VERSION_2 || document.patterns.size > 1
        val all = ArrayList<HeEvent>()
        for (p in document.patterns) {
            for (e in p.events) {
                val abs = (e.relativeTimeMs + p.absoluteTimeMs).coerceAtLeast(0)
                all.add(e.copy(relativeTimeMs = abs))
            }
        }
        if (all.isEmpty()) return null

        val sorted = all.sortedBy { it.relativeTimeMs }
        // SDK 顺序：先 trimOverlapEvent（会删除 Index==2）再写 HE1；HE1.0 输入保持原样。
        val trimmed = if (fromHe20) trimOverlaps(sorted) else sorted
        val capped = trimmed.take(MAX_EVENTS)
        val events = capped.map { normalizeHe10Event(it) }
        return HeDocument(
            HeMetadata(1, document.metadata.created, document.metadata.description),
            listOf(HePattern(0, events)),
        )
    }

    private fun normalizeHe10Event(e: HeEvent): HeEvent {
        if (e.type == "transient") {
            return e.copy(
                relativeTimeMs = e.relativeTimeMs.coerceAtLeast(0),
                durationMs = 0,
                vibrationId = 0,
                params = e.params.copy(
                    frequency = e.params.frequency.coerceIn(0, 100),
                    intensity = e.params.intensity.coerceIn(0, 100),
                    curve = emptyList(),
                ),
            )
        }
        val rawFreq = e.params.frequency
        val freq = if (rawFreq == -1) DEFAULT_FREQUENCY else rawFreq.coerceIn(0, 100)
        val curve0 = if (rawFreq == -1) {
            e.params.curve.map { it.copy(freqOffset = 0) }
        } else {
            e.params.curve
        }
        val dur = e.durationMs.coerceIn(1, 5000)
        return e.copy(
            relativeTimeMs = e.relativeTimeMs.coerceAtLeast(0),
            durationMs = dur,
            vibrationId = 0,
            params = e.params.copy(
                frequency = freq,
                intensity = e.params.intensity.coerceIn(0, 100),
                curve = padCurveTo4p(trimCurveTo4p(curve0), dur),
            ),
        )
    }

    // ── HE2.0 raw 归一化 ─────────────────────────────────────────────────

    fun normalizeForHe20(document: HeDocument): HeDocument? {
        if (document.patterns.isEmpty()) return null
        val patterns = ArrayList<HePattern>(document.patterns.size)
        for (p in document.patterns) {
            val abs = p.absoluteTimeMs.coerceAtLeast(0)
            val events = ArrayList<HeEvent>(minOf(p.events.size, MAX_EVENTS))
            for (e in p.events.take(MAX_EVENTS)) {
                val rel = e.relativeTimeMs.coerceAtLeast(0)
                if (e.type == "transient") {
                    val freq = e.params.frequency.coerceIn(0, 100)
                    val dur = if (e.durationMs <= 0) He2Json.TRANSIENT_DURATION_MS else e.durationMs
                    events.add(
                        e.copy(
                            relativeTimeMs = rel,
                            durationMs = dur,
                            params = e.params.copy(
                                frequency = freq,
                                intensity = e.params.intensity.coerceIn(0, 100),
                                curve = emptyList(),
                            ),
                        )
                    )
                } else {
                    val dur = e.durationMs.coerceIn(1, 5000)
                    // HE2.0 raw 保留 `Frequency=-1` 哨兵：core > 32（或 minor > 16）时
                    // SDK 原样写 -1，让引擎使用默认频率；core 较旧时 raw 编码器再替换成 56。
                    val freq = e.params.frequency.coerceIn(-1, 100)
                    events.add(
                        e.copy(
                            relativeTimeMs = rel,
                            durationMs = dur,
                            params = e.params.copy(
                                frequency = freq,
                                intensity = e.params.intensity.coerceIn(0, 100),
                                curve = normalizeCurveForHe20(e.params.curve, dur),
                            ),
                        )
                    )
                }
            }
            patterns.add(HePattern(abs, events))
        }
        return HeDocument(
            HeMetadata(He2Json.VERSION_2, document.metadata.created, document.metadata.description),
            patterns,
        )
    }
}
