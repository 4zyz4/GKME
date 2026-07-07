package com.zyz4.gamepademu.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

data class LayoutPreset(
    val version: Int = 1,
    val buttons: List<ButtonPosition> = emptyList(),
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): LayoutPreset {
            val type = object : TypeToken<LayoutPreset>() {}.type
            val preset = gson.fromJson<LayoutPreset>(json, type)

            val hasDoubleClickField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.size() > 0 && arr[0].asJsonObject.has("doubleClickEnable")
            } catch (_: Exception) {
                false
            }

            return preset.copy(
                buttons = (preset.buttons ?: emptyList()).map { b ->
                    var sanitized = b.sanitize()
                    if (!hasDoubleClickField) {
                        sanitized = sanitized.copy(doubleClickEnable = true)
                    }
                    sanitized
                }
            )
        }

        fun toJson(preset: LayoutPreset): String = gson.toJson(preset)
    }

    fun toJson(): String = gson.toJson(this)
}
