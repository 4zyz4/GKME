package com.zyz4.gkme.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap

private val gson = Gson()

/**
 * Hard-coded type info that mirrors MainActivityControls.allControls.
 * lockAspect and isKeyboard are derived from these sets, never saved.
 */
private val LOCK_ASPECT_FALSE_IDS = setOf(
    "btnLB", "btnRB", "btnLT", "btnRT",
    "touchpad", "mousepad", "btnCustomRect",
    "btnMouseLMB", "btnMouseRMB", "btnMouseMMB",
)

private val IS_KEYBOARD_IDS = setOf(
    "kbLCtrl", "kbLShift", "kbLAlt", "kbLWin",
    "kbRCtrl", "kbRShift", "kbRAlt", "kbRGui",
    "kbQ", "kbW", "kbE", "kbR", "kbT", "kbY", "kbU", "kbI", "kbO", "kbP",
    "kbA", "kbS", "kbD", "kbF", "kbG", "kbH", "kbJ", "kbK", "kbL",
    "kbZ", "kbX", "kbC", "kbV", "kbB", "kbN", "kbM",
    "kb1", "kb2", "kb3", "kb4", "kb5", "kb6", "kb7", "kb8", "kb9", "kb0",
    "kbSpace", "kbEnter", "kbBackspace", "kbTab", "kbCaps",
    "kbEsc", "kbDelete", "kbMenu",
    "kbMinus", "kbEqual", "kbLBracket", "kbRBracket", "kbBackslash",
    "kbSemicolon", "kbApostrophe", "kbComma", "kbDot", "kbSlash", "kbGrave",
    "kbF1", "kbF2", "kbF3", "kbF4", "kbF5", "kbF6", "kbF7", "kbF8", "kbF9",
    "kbF10", "kbF11", "kbF12",
    "kbArrowUp", "kbArrowDown", "kbArrowLeft", "kbArrowRight",
)

data class LayoutPreset(
    val version: Int = 1,
    val buttons: List<ButtonPosition> = emptyList(),
    val gyroOrientation: GyroOrientation? = null,
    val gyroActivateMode: GyroActivateMode? = null,
    val gyroMode: GyroMode? = null,
    val gyroModeSensitivity: Int? = null,
) {
    companion object {
        private val gsonInstance = Gson()
        private val DOUBLE_CLICK_IDS = setOf("leftJoystick", "rightJoystick", "touchpad")
        private val JOYSTICK_IDS = setOf("leftJoystick", "rightJoystick")
        private val AREA_IDS = setOf("leftJoystick", "rightJoystick", "touchpad", "dpadPad", "customKeypad")
        private val KEYPAD_IDS = setOf("customKeypad")
        private val MOUSEPAD_IDS = setOf("mousepad")

        private fun isLockAspectFalse(baseId: String): Boolean = baseId in LOCK_ASPECT_FALSE_IDS
        private fun isKeyboard(baseId: String): Boolean = baseId in IS_KEYBOARD_IDS

        fun fromJson(json: String): LayoutPreset {
            val root = gsonInstance.fromJson(json, JsonObject::class.java)
            val rawArray = root.getAsJsonArray("buttons")
            val buttons = mutableListOf<com.zyz4.gkme.model.ButtonPosition>()
            if (rawArray != null) {
                val size = java.lang.Integer.valueOf(rawArray.size())
                for (i in 0 until size) {
                    val btnObj = rawArray[i].asJsonObject
                    // Remove lockAspect and isKeyboard so they are not deserialized;
                    // they will be re-constructed from baseId when accessed.
                    btnObj.remove("lockAspect")
                    btnObj.remove("isKeyboard")
                    if (!btnObj.has("overlapTrigger")) btnObj.addProperty("overlapTrigger", true)
                    if (!btnObj.has("followAreaOverlapTrigger")) btnObj.addProperty("followAreaOverlapTrigger", false)
                    if (!btnObj.has("linearTriggerEnabled")) btnObj.addProperty("linearTriggerEnabled", false)
                    if (!btnObj.has("slideDirection")) btnObj.addProperty("slideDirection", "DOWN")
                    if (!btnObj.has("travelDistance")) btnObj.addProperty("travelDistance", 10)
                    if (!btnObj.has("doubleClickEnable")) btnObj.addProperty("doubleClickEnable", true)
                    if (!btnObj.has("isCustom")) btnObj.addProperty("isCustom", false)
                    if (!btnObj.has("followAreaEnabled")) btnObj.addProperty("followAreaEnabled", false)
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

                    val bp = gsonInstance.fromJson(btnObj, com.zyz4.gkme.model.ButtonPosition::class.java)
                    val baseId = bp.id.substringBefore("_")
                    // Override lockAspect and isKeyboard from hard-coded tables
                    val lockAspect = if (isKeyboard(baseId)) false else !isLockAspectFalse(baseId)
                    buttons.add(bp.copy(
                        lockAspect = lockAspect,
                        isKeyboard = isKeyboard(baseId),
                    ))
                }
            }
            val gyro = root.get("gyroOrientation")?.asString?.let { GyroOrientation.valueOf(it) }
            val gyroActivateMode = root.get("gyroActivateMode")?.asString?.let { GyroActivateMode.valueOf(it) }
            val gyroMode = root.get("gyroMode")?.asString?.let { GyroMode.valueOf(it) }
            val gyroModeSens = root.get("gyroModeSensitivity")?.asInt
            return LayoutPreset(
                version = root.get("version")?.asInt ?: 1,
                buttons = buttons,
                gyroOrientation = gyro,
                gyroActivateMode = gyroActivateMode,
                gyroMode = gyroMode,
                gyroModeSensitivity = gyroModeSens,
            )
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
                if (b.mouseAcceleration != null && b.mouseAcceleration.isNotEmpty()) m["mouseAcceleration"] = b.mouseAcceleration
                else if (b.mouseAcceleration != null && b.mouseAcceleration.isEmpty()) m.remove("mouseAcceleration")
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
        gyroActivateMode?.let { obj["gyroActivateMode"] = it.name }
        gyroMode?.let { obj["gyroMode"] = it.name }
        gyroModeSensitivity?.let { obj["gyroModeSensitivity"] = it }
        return gson.toJson(obj)
    }

}