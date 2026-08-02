package com.zyz4.gamepademu.model

import com.google.gson.Gson
import com.google.gson.JsonObject
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
        private val AREA_IDS = setOf("leftJoystick", "rightJoystick", "touchpad")

        fun fromJson(json: String): LayoutPreset {
            val type = object : TypeToken<LayoutPreset>() {}.type
            val preset: LayoutPreset = gson.fromJson(json, type)
            val root = gson.fromJson(json, JsonObject::class.java)
            val buttonsArray = root.getAsJsonArray("buttons")
            val fixedButtons = preset.buttons.mapIndexed { index, b ->
                val btnObj = buttonsArray[index].asJsonObject
                var fixed = b
                if (!btnObj.has("overlapTrigger")) {
                    fixed = fixed.copy(overlapTrigger = true)
                }
                if (!btnObj.has("followAreaOverlapTrigger")) {
                    fixed = fixed.copy(followAreaOverlapTrigger = false)
                }
                fixed
            }
            return preset.copy(buttons = fixedButtons)
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
            m["idleTransparency"] = b.idleTransparency
            m["activeTransparency"] = b.activeTransparency
            m["followAreaTransparency"] = b.followAreaTransparency
            m["overlapTrigger"] = b.overlapTrigger
            m["followAreaOverlapTrigger"] = b.followAreaOverlapTrigger
            if (baseId in DOUBLE_CLICK_IDS) {
                m["doubleClickEnable"] = b.doubleClickEnable
            }
            if (baseId in JOYSTICK_IDS) {
                m["deadZone"] = b.deadZone
                if (b.sensitivityCurve != null && b.sensitivityCurve!!.isNotEmpty()) {
                    m["sensitivityCurve"] = b.sensitivityCurve
                }
            }
            if (baseId in AREA_IDS) {
                m["followAreaEnabled"] = b.followAreaEnabled
                if (b.followAreaEnabled) {
                    m["followAreaX"] = b.followAreaX
                    m["followAreaY"] = b.followAreaY
                    m["followAreaW"] = b.followAreaW
                    m["followAreaH"] = b.followAreaH
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
