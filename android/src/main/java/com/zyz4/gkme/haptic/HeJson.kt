package com.zyz4.gkme.haptic

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 把 GKME 产出的 HE1.0 JSON（见 [RichTapHe]）解析成 [RichTapRawCodec] 的事件模型。
 *
 * 仅 Android 运行期使用（依赖 `org.json`），供 type 2 路径把 HE 编码成引擎 raw int[]。
 */
object HeJson {

    /** 解析 HE1.0 的顶层 `Pattern`；失败返回 null。 */
    fun parseHe10(json: String): List<RichTapRawCodec.HeEvent>? = try {
        val root = JSONObject(json)
        val arr = root.getJSONArray("Pattern")
        val out = ArrayList<RichTapRawCodec.HeEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val ev = arr.getJSONObject(i).getJSONObject("Event")
            val type = ev.getString("Type")
            val relativeTime = ev.optInt("RelativeTime", 0)
            val duration = ev.optInt("Duration", 0)
            val vibrationId = ev.optInt("Index", 0)
            val params = ev.getJSONObject("Parameters")
            val frequency = params.optInt("Frequency", -1)
            val intensity = params.optInt("Intensity", 100)
            val curve = ArrayList<RichTapRawCodec.CurvePoint>()
            if (type == RichTapRawCodec.TYPE_CONTINUOUS && params.has("Curve")) {
                val curveArr = params.getJSONArray("Curve")
                for (j in 0 until curveArr.length()) {
                    val pt = curveArr.getJSONObject(j)
                    curve.add(
                        RichTapRawCodec.CurvePoint(
                            timeMs = pt.optInt("Time", 0),
                            scale = pt.optDouble("Intensity", 0.0),
                            freqOffset = pt.optDouble("Frequency", 0.0).roundToInt(),
                        )
                    )
                }
            }
            out.add(
                RichTapRawCodec.HeEvent(
                    type = type,
                    relativeTimeMs = relativeTime,
                    durationMs = duration,
                    vibrationId = vibrationId,
                    params = RichTapRawCodec.EventParams(intensity, frequency, curve),
                )
            )
        }
        out
    } catch (_: Throwable) {
        null
    }
}
