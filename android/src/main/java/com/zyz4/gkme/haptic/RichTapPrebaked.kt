package com.zyz4.gkme.haptic

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * RichTap 预置效果（PrebakedEffect）的 HE 1.0 数据。
 *
 * 数据取自 RichTap ASDK 2.2.0 的 `com.apprichtap.haptic.base.c`（SDK 的 PrebakedEffect
 * 就是这些内嵌 HE），并已离线转换为设备端可解析的 HE 1.0：顶层 `PatternList`→`Pattern`、
 * 曲线裁成 4 点、`Frequency=-1`→56、丢弃 `Index`。
 *
 * ID 范围与 SDK 的 `PrebakedEffectId` 一致：[PREBAKED_ID_MIN]..[PREBAKED_ID_MAX]。
 */
object RichTapPrebaked {

    private const val TAG = "GKME_Prebaked"
    private const val ASSET = "richtap_prebaked.json"

    const val PREBAKED_ID_MIN = 10001
    const val PREBAKED_ID_MAX = 10050

    @Volatile
    private var patterns: Map<Int, String> = emptyMap()

    @Volatile
    var loaded: Boolean = false
        private set

    /** 读取内嵌的 50 段预置 HE；由 [com.zyz4.gkme.controlled.HapticInjector.init] 调用。 */
    fun load(context: Context) {
        if (loaded) return
        try {
            val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val map = HashMap<Int, String>(root.length())
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toIntOrNull() ?: continue
                map[id] = root.getJSONObject(key).toString()
            }
            patterns = map
            loaded = true
        } catch (t: Throwable) {
            Log.w(TAG, "加载预置 HE 失败", t)
        }
    }

    /** 取指定预置效果的 HE 1.0 JSON；未加载或 ID 无效时返回 null。 */
    fun he(prebakedId: Int): String? = patterns[prebakedId]
}
