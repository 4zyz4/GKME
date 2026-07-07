package com.zyz4.gamepademu.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap

data class LayoutPreset(
    val version: Int = 1,
    val buttons: List<ButtonPosition> = emptyList(),
    val gyroOrientation: GyroOrientation? = null,
) {
    companion object {
        private val gson = Gson()
        private val DOUBLE_CLICK_IDS = setOf("leftJoystick", "rightJoystick", "touchpad")

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
                        sanitized = sanitized.copy(doubleClickEnable = b.id.substringBefore("_") in DOUBLE_CLICK_IDS)
                    }
                    sanitized
                }
            )
        }

        fun toJson(preset: LayoutPreset): String = preset.toJson()
    }

    fun toJson(): String {
        val list: MutableList<Map<String, Any?>> = mutableListOf()
        for (b in buttons) {
            val baseId = b.id.substringBefore("_")
            val m = LinkedHashMap<String, Any?>()
            m["id"] = b.id
            m["x"] = b.x
            m["y"] = b.y
            m["width"] = b.width
            m["height"] = b.height
            m["visible"] = b.visible
            m["lockAspect"] = b.lockAspect
            m["swipeTrigger"] = b.swipeTrigger
            m["rotation"] = b.rotation
            m["isCustom"] = b.isCustom
            m["roundShape"] = b.roundShape
            if (b.isCustom) {
                m["customText"] = (b.customText ?: "自定义")
                m["customBits"] = (b.customBits ?: listOf<Int>())
            }
            if (baseId in DOUBLE_CLICK_IDS) {
                m["doubleClickEnable"] = b.doubleClickEnable
            }
            list.add(m)
        }
        val obj = LinkedHashMap<String, Any?>()
        obj["version"] = version
        obj["buttons"] = list
        gyroOrientation?.let { obj["gyroOrientation"] = it.name }
        return gson.toJson(obj)
    }
}
