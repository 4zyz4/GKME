package com.zyz4.gkme.haptic

/**
 * 兼容入口：把 HE1.0 JSON 解析成 [RichTapRawCodec] 的事件模型。
 *
 * 新代码请直接使用 [He2Json]（同时支持 HE1.0 / HE2.0、`PatternList`、16 点曲线和 `Index`）。
 */
object HeJson {

    /** 解析 HE1.0 的顶层 `Pattern`；HE2.0 请用 [He2Json.parse]。 */
    fun parseHe10(json: String): List<RichTapRawCodec.HeEvent>? =
        He2Json.parse(json)
            ?.takeIf { it.version == He2Json.VERSION_1 }
            ?.events()
}
