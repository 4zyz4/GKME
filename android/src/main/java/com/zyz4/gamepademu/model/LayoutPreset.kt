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

            val hasAlphaField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.any { it.asJsonObject.has("alpha") }
            } catch (_: Exception) {
                false
            }

            val hasFollowAreaField = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                arr != null && arr.any { it.asJsonObject.has("followAreaEnabled") }
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

            // Parse old field values from raw JSON for backward compat
            val rawButtons = try {
                val obj = JsonParser.parseString(json).asJsonObject
                val arr = obj.getAsJsonArray("buttons")
                if (arr != null) arr.map { it.asJsonObject } else emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            return preset.copy(
                buttons = (preset.buttons ?: emptyList()).mapIndexed { index, b ->
                    var sanitized = b.sanitize()
                    val baseId = b.id.substringBefore("_")
                    if (!hasDoubleClickField) {
                        sanitized = sanitized.copy(doubleClickEnable = baseId in DOUBLE_CLICK_IDS)
                    }
                    if (!hasAlphaField) {
                        sanitized = sanitized.copy(alpha = 255)
                    }
                    if (!hasFollowAreaField && baseId in JOYSTICK_IDS && index < rawButtons.size) {
                        val raw = rawButtons[index]
                        val oldFollowFinger = try { raw.get("followFinger").asBoolean } catch (_: Exception) { false }
                        val oldLeftHalf = try { raw.get("leftHalfTrigger").asBoolean } catch (_: Exception) { false }
                        val oldRightHalf = try { raw.get("rightHalfTrigger").asBoolean } catch (_: Exception) { false }
                        val areaW = b.width.coerceAtLeast(1)
                        val areaH = b.height.coerceAtLeast(1)
                        when {
                            oldLeftHalf -> sanitized = sanitized.copy(
                                followAreaEnabled = true,
                                followAreaX = (b.x - 60).coerceAtLeast(0),
                                followAreaY = b.y,
                                followAreaW = (120).coerceAtMost(60),
                                followAreaH = areaH
                            )
                            oldRightHalf -> sanitized = sanitized.copy(
                                followAreaEnabled = true,
                                followAreaX = 60,
                                followAreaY = b.y,
                                followAreaW = 60,
                                followAreaH = areaH
                            )
                            oldFollowFinger -> sanitized = sanitized.copy(
                                followAreaEnabled = true,
                                followAreaX = b.x,
                                followAreaY = b.y,
                                followAreaW = areaW,
                                followAreaH = areaH
                            )
                        }
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
            m["alpha"] = b.alpha
            if (baseId in DOUBLE_CLICK_IDS) {
                m["doubleClickEnable"] = b.doubleClickEnable
            }
            if (baseId in JOYSTICK_IDS) {
                m["deadZone"] = b.deadZone
                m["followAreaEnabled"] = b.followAreaEnabled
                if (b.followAreaEnabled) {
                    m["followAreaX"] = b.followAreaX
                    m["followAreaY"] = b.followAreaY
                    m["followAreaW"] = b.followAreaW
                    m["followAreaH"] = b.followAreaH
                }
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
