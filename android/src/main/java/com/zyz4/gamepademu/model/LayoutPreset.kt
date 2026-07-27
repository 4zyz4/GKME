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
        private val JOYSTICK_IDS = setOf("leftJoystick", "rightJoystick")

        fun fromJson(json: String): LayoutPreset {
            val type = object : TypeToken<LayoutPreset>() {}.type
            val preset = gson.fromJson<LayoutPreset>(json, type)

            val hasDoubleClickField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.any { it.asJsonObject.has("doubleClickEnable") }
            } catch (_: Exception) {
                false
            }

            val hasFollowFingerField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.any { it.asJsonObject.has("followFinger") }
            } catch (_: Exception) {
                false
            }

            val hasHalfTriggerField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.any { it.asJsonObject.has("leftHalfTrigger") }
            } catch (_: Exception) {
                false
            }

            val hasCurveField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.any { it.asJsonObject.has("sensitivityCurve") }
            } catch (_: Exception) {
                false
            }

            val hasDeadZoneField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.any { it.asJsonObject.has("deadZone") }
            } catch (_: Exception) {
                false
            }

            return preset.copy(
                buttons = (preset.buttons ?: emptyList()).map { b ->
                    var sanitized = b.sanitize()
                    val baseId = b.id.substringBefore("_")
                    if (!hasDoubleClickField) {
                        sanitized = sanitized.copy(doubleClickEnable = baseId in DOUBLE_CLICK_IDS)
                    }
                    if (!hasFollowFingerField) {
                        sanitized = sanitized.copy(followFinger = false)
                    }
                    if (!hasHalfTriggerField && baseId in JOYSTICK_IDS) {
                        sanitized = sanitized.copy(leftHalfTrigger = false, rightHalfTrigger = false)
                    }
                    if (!hasCurveField && baseId in JOYSTICK_IDS) {
                        sanitized = sanitized.copy(sensitivityCurve = null)
                    }
                    if (!hasDeadZoneField && baseId in JOYSTICK_IDS) {
                        sanitized = sanitized.copy(deadZone = 0)
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
                m["followFinger"] = b.followFinger
            }
            if (baseId in JOYSTICK_IDS) {
                m["leftHalfTrigger"] = b.leftHalfTrigger
                m["rightHalfTrigger"] = b.rightHalfTrigger
                m["deadZone"] = b.deadZone
                if (b.sensitivityCurve != null && b.sensitivityCurve!!.isNotEmpty()) {
                    m["sensitivityCurve"] = b.sensitivityCurve
                }
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
