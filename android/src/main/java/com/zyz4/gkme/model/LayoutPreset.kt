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
    val gyroDeadZone: Int? = null,
    val gyroReverseDeadZone: Int? = null,
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

        /**
         * 将旧版透明度字段 (0=不透明, 255=全透明) 迁移为不透明度字段 (100=不透明, 0=全透明)。
         * 若已存在新字段则以新字段为准，并移除旧字段，保证保存后自动替换。
         */
        private fun migrateLegacyOpacity(obj: JsonObject, legacyKey: String, newKey: String) {
            val legacy = obj.get(legacyKey) ?: return
            if (!obj.has(newKey)) {
                val opacity = (100 - legacy.asInt.coerceIn(0, 255) * 100 / 255).coerceIn(0, 100)
                obj.addProperty(newKey, opacity)
            }
            obj.remove(legacyKey)
        }

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
                    migrateLegacyOpacity(btnObj, "idleTransparency", "idleOpacity")
                    migrateLegacyOpacity(btnObj, "activeTransparency", "activeOpacity")
                    migrateLegacyOpacity(btnObj, "followAreaTransparency", "followAreaOpacity")
                    if (!btnObj.has("idleOpacity")) btnObj.addProperty("idleOpacity", 100)
                    if (!btnObj.has("activeOpacity")) btnObj.addProperty("activeOpacity", 100)
                    if (!btnObj.has("followAreaOpacity")) btnObj.addProperty("followAreaOpacity", 100)
                    if (!btnObj.has("followAreaX")) btnObj.addProperty("followAreaX", 0)
                    if (!btnObj.has("followAreaY")) btnObj.addProperty("followAreaY", 0)
                    if (!btnObj.has("followAreaW")) btnObj.addProperty("followAreaW", 0)
                    if (!btnObj.has("followAreaH")) btnObj.addProperty("followAreaH", 0)
                    if (!btnObj.has("deadZone")) btnObj.addProperty("deadZone", 0)
                    if (!btnObj.has("reverseDeadZone")) btnObj.addProperty("reverseDeadZone", 0)
                    if (!btnObj.has("mouseSensitivity")) btnObj.addProperty("mouseSensitivity", 1.0)
                    if (!btnObj.has("mouseMoveSlop")) btnObj.addProperty("mouseMoveSlop", 4)
                    if (!btnObj.has("scrollSensitivity")) btnObj.addProperty("scrollSensitivity", 0.1)
                    if (!btnObj.has("invertScrollV")) btnObj.addProperty("invertScrollV", false)
                    if (!btnObj.has("invertScrollH")) btnObj.addProperty("invertScrollH", false)
                    if (!btnObj.has("singleTapAction")) btnObj.addProperty("singleTapAction", MouseGestureAction.LEFT_CLICK.name)
                    if (!btnObj.has("twoFingerTapAction")) btnObj.addProperty("twoFingerTapAction", MouseGestureAction.RIGHT_CLICK.name)
                    if (!btnObj.has("threeFingerTapAction")) btnObj.addProperty("threeFingerTapAction", MouseGestureAction.MIDDLE_CLICK.name)
                    if (!btnObj.has("doubleTapDragAction")) btnObj.addProperty("doubleTapDragAction", MouseGestureAction.LEFT_DRAG.name)
                    if (!btnObj.has("oneFingerSwipeAction")) btnObj.addProperty("oneFingerSwipeAction", MouseGestureAction.MOVE_CURSOR.name)
                    if (!btnObj.has("twoFingerSwipeAction")) btnObj.addProperty("twoFingerSwipeAction", MouseGestureAction.SCROLL.name)
                    if (!btnObj.has("threeFingerSwipeAction")) btnObj.addProperty("threeFingerSwipeAction", MouseGestureAction.SCROLL.name)
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
            val gyroDeadZone = root.get("gyroDeadZone")?.asInt
            val gyroReverseDeadZone = root.get("gyroReverseDeadZone")?.asInt
            return LayoutPreset(
                version = root.get("version")?.asInt ?: 1,
                buttons = buttons,
                gyroOrientation = gyro,
                gyroActivateMode = gyroActivateMode,
                gyroMode = gyroMode,
                gyroModeSensitivity = gyroModeSens,
                gyroDeadZone = gyroDeadZone,
                gyroReverseDeadZone = gyroReverseDeadZone,
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
            if (b.idleOpacity != 100) m["idleOpacity"] = b.idleOpacity
            if (b.activeOpacity != 100) m["activeOpacity"] = b.activeOpacity
            if (b.followAreaOpacity != 100) m["followAreaOpacity"] = b.followAreaOpacity
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
                if (b.mouseMoveSlop != 4) m["mouseMoveSlop"] = b.mouseMoveSlop
                if (b.invertScrollV) m["invertScrollV"] = b.invertScrollV
                if (b.invertScrollH) m["invertScrollH"] = b.invertScrollH
                if (!b.doubleClickEnable) m["doubleClickEnable"] = b.doubleClickEnable
                if (b.singleTapAction != MouseGestureAction.LEFT_CLICK) m["singleTapAction"] = b.singleTapAction.name
                if (b.twoFingerTapAction != MouseGestureAction.RIGHT_CLICK) m["twoFingerTapAction"] = b.twoFingerTapAction.name
                if (b.threeFingerTapAction != MouseGestureAction.MIDDLE_CLICK) m["threeFingerTapAction"] = b.threeFingerTapAction.name
                if (b.doubleTapDragAction != MouseGestureAction.LEFT_DRAG) m["doubleTapDragAction"] = b.doubleTapDragAction.name
                if (b.oneFingerSwipeAction != MouseGestureAction.MOVE_CURSOR) m["oneFingerSwipeAction"] = b.oneFingerSwipeAction.name
                if (b.twoFingerSwipeAction != MouseGestureAction.SCROLL) m["twoFingerSwipeAction"] = b.twoFingerSwipeAction.name
                if (b.threeFingerSwipeAction != MouseGestureAction.SCROLL) m["threeFingerSwipeAction"] = b.threeFingerSwipeAction.name
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
        gyroDeadZone?.let { obj["gyroDeadZone"] = it }
        gyroReverseDeadZone?.let { obj["gyroReverseDeadZone"] = it }
        return gson.toJson(obj)
    }

}