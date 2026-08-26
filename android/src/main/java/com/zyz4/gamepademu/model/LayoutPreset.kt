package com.zyz4.gamepademu.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap

private val gson = Gson()

data class LayoutPreset(
    val version: Int = 1,
    val buttons: List<ButtonPosition> = emptyList(),
    val gyroOrientation: GyroOrientation? = null,
) {
    companion object {
        private val gsonInstance = Gson()
        private val DOUBLE_CLICK_IDS = setOf("leftJoystick", "rightJoystick", "touchpad")
        private val JOYSTICK_IDS = setOf("leftJoystick", "rightJoystick")
        private val AREA_IDS = setOf("leftJoystick", "rightJoystick", "touchpad", "dpadPad", "customKeypad")
        private val KEYPAD_IDS = setOf("customKeypad")
        private val MOUSEPAD_IDS = setOf("mousepad")

        fun fromJson(json: String): LayoutPreset {
            val root = gsonInstance.fromJson(json, JsonObject::class.java)
            val rawArray = root.getAsJsonArray("buttons")
            if (rawArray != null) {
                val size = java.lang.Integer.valueOf(rawArray.size())
                for (i in 0 until size) {
                    val btnObj = rawArray[i].asJsonObject
                    if (!btnObj.has("overlapTrigger")) btnObj.addProperty("overlapTrigger", true)
                    if (!btnObj.has("followAreaOverlapTrigger")) btnObj.addProperty("followAreaOverlapTrigger", false)
                    if (!btnObj.has("linearTriggerEnabled")) btnObj.addProperty("linearTriggerEnabled", false)
                    if (!btnObj.has("slideDirection")) btnObj.addProperty("slideDirection", "DOWN")
                    if (!btnObj.has("travelDistance")) btnObj.addProperty("travelDistance", 10)
                    if (!btnObj.has("doubleClickEnable")) btnObj.addProperty("doubleClickEnable", true)
                    if (!btnObj.has("isCustom")) btnObj.addProperty("isCustom", false)
                    if (!btnObj.has("followAreaEnabled")) btnObj.addProperty("followAreaEnabled", false)
                    if (!btnObj.has("lockAspect")) btnObj.addProperty("lockAspect", false)
                    if (!btnObj.has("swipeTrigger")) btnObj.addProperty("swipeTrigger", false)
                    if (!btnObj.has("roundShape")) btnObj.addProperty("roundShape", true)
                    if (!btnObj.has("idleTransparency")) btnObj.addProperty("idleTransparency", 0)
                    if (!btnObj.has("activeTransparency")) btnObj.addProperty("activeTransparency", 0)
                    if (!btnObj.has("followAreaTransparency")) btnObj.addProperty("followAreaTransparency", 0)
                    if (!btnObj.has("followAreaX")) btnObj.addProperty("followAreaX", 0)
                    if (!btnObj.has("followAreaY")) btnObj.addProperty("followAreaY", 0)
                    if (!btnObj.has("followAreaW")) btnObj.addProperty("followAreaW", 0)
                    if (!btnObj.has("followAreaH")) btnObj.addProperty("followAreaH", 0)
                    if (!btnObj.has("deadZone")) btnObj.addProperty("deadZone", 0)
                    if (!btnObj.has("reverseDeadZone")) btnObj.addProperty("reverseDeadZone", 0)
                    if (!btnObj.has("mouseSensitivity")) btnObj.addProperty("mouseSensitivity", 1.0)
                    if (!btnObj.has("scrollSensitivity")) btnObj.addProperty("scrollSensitivity", 0.1)
                    if (!btnObj.has("invertScrollV")) btnObj.addProperty("invertScrollV", false)
                    if (!btnObj.has("invertScrollH")) btnObj.addProperty("invertScrollH", false)
                    if (!btnObj.has("keypadCenterDoubleClick")) btnObj.addProperty("keypadCenterDoubleClick", false)
                    if (!btnObj.has("keypadTexts")) {
                        val texts = com.google.gson.JsonArray()
                        listOf("上", "下", "左", "右", "中").forEach { texts.add(it) }
                        btnObj.add("keypadTexts", texts)
                    }
                    if (!btnObj.has("keypadBits")) {
                        val bitsArr = com.google.gson.JsonArray()
                        repeat(5) { bitsArr.add(com.google.gson.JsonArray()) }
                        btnObj.add("keypadBits", bitsArr)
                    }
                    if (!btnObj.has("customBits")) {
                        btnObj.add("customBits", com.google.gson.JsonArray())
                    }
                    if (!btnObj.has("gyroActivate")) btnObj.addProperty("gyroActivate", false)
                    if (!btnObj.has("autoHold")) btnObj.addProperty("autoHold", false)
                    // Fix slideDirection: Gson uses enum name, not jsonValue
                    val dirStr = btnObj.get("slideDirection")?.asString
                    if (dirStr != null) {
                        val normalized = when (dirStr.lowercase()) {
                            "down" -> "DOWN"
                            "up" -> "UP"
                            "left" -> "LEFT"
                            "right" -> "RIGHT"
                            else -> dirStr.uppercase()
                        }
                        btnObj.remove("slideDirection")
                        btnObj.addProperty("slideDirection", normalized)
                    }
                }
            }
            val fixedJson = root.toString()
            val type = object : TypeToken<LayoutPreset>() {}.type
            return gsonInstance.fromJson(fixedJson, type)
        }

        fun toJson(preset: LayoutPreset): String = preset.toJson()
    }

    fun toJson(): String {
        val list: MutableList<Map<String, Any?>> = mutableListOf()
        for (b in buttons) {
            val baseId = b.id.substringBefore("_")
            val m = LinkedHashMap<String, Any?>()
            // always write
            m["id"] = b.id
            m["x"] = b.x
            m["y"] = b.y
            m["width"] = b.width
            m["height"] = b.height
            // conditional write (only when not default)
            if (b.visible) m["visible"] = b.visible
            if (b.lockAspect) m["lockAspect"] = b.lockAspect
            if (b.swipeTrigger) m["swipeTrigger"] = b.swipeTrigger
            m["rotation"] = b.rotation
            if (b.isCustom) {
                m["isCustom"] = b.isCustom
                m["customText"] = (b.customText ?: "自定义")
                m["customBits"] = (b.customBits ?: listOf<Int>())
            }
            if (b.roundShape != true) m["roundShape"] = b.roundShape
            if (b.idleTransparency != 0) m["idleTransparency"] = b.idleTransparency
            if (b.activeTransparency != 0) m["activeTransparency"] = b.activeTransparency
            if (b.followAreaTransparency != 0) m["followAreaTransparency"] = b.followAreaTransparency
            if (b.overlapTrigger != true) m["overlapTrigger"] = b.overlapTrigger
            if (b.followAreaOverlapTrigger) m["followAreaOverlapTrigger"] = b.followAreaOverlapTrigger
            if (baseId in DOUBLE_CLICK_IDS) {
                if (!b.doubleClickEnable) m["doubleClickEnable"] = b.doubleClickEnable
            }
            if (baseId in JOYSTICK_IDS) {
                if (b.deadZone != 0) m["deadZone"] = b.deadZone
                if (b.reverseDeadZone != 0) m["reverseDeadZone"] = b.reverseDeadZone
                if (b.sensitivityCurve != null && b.sensitivityCurve!!.isNotEmpty()) {
                    m["sensitivityCurve"] = b.sensitivityCurve
                }
            }
            if (baseId in AREA_IDS) {
                if (b.followAreaEnabled) {
                    m["followAreaEnabled"] = b.followAreaEnabled
                    m["followAreaX"] = b.followAreaX
                    m["followAreaY"] = b.followAreaY
                    m["followAreaW"] = b.followAreaW
                    m["followAreaH"] = b.followAreaH
                }
            }
            if (baseId in MOUSEPAD_IDS) {
                if (b.mouseSensitivity != 1f) m["mouseSensitivity"] = b.mouseSensitivity
                if (b.scrollSensitivity != 0.1f) m["scrollSensitivity"] = b.scrollSensitivity
                if (b.invertScrollV) m["invertScrollV"] = b.invertScrollV
                if (b.invertScrollH) m["invertScrollH"] = b.invertScrollH
            }
            // linear trigger fields
            if (b.linearTriggerEnabled) {
                m["linearTriggerEnabled"] = b.linearTriggerEnabled
                if (b.slideDirection != SlideDirection.DOWN) m["slideDirection"] = b.slideDirection.jsonValue
                if (b.travelDistance != 10) m["travelDistance"] = b.travelDistance
            }
            if (ButtonPosition.isKeypad(b.id)) {
                m["keypadTexts"] = b.keypadTexts ?: ButtonPosition.KEYPAD_DEFAULT_TEXTS
                m["keypadBits"] = b.keypadBits ?: ButtonPosition.KEYPAD_DEFAULT_BITS
                if (!b.keypadCenterDoubleClick) m["keypadCenterDoubleClick"] = b.keypadCenterDoubleClick
            }
            if (b.gyroActivate) m["gyroActivate"] = b.gyroActivate
            if (b.autoHold) m["autoHold"] = b.autoHold
            list.add(m)
        }
        val obj = LinkedHashMap<String, Any?>()
        obj["version"] = version
        obj["buttons"] = list
        gyroOrientation?.let { obj["gyroOrientation"] = it.name }
        return gson.toJson(obj)
    }

}
