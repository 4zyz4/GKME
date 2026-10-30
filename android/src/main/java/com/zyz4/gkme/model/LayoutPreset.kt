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
    "btnMouseLMB", "btnMouseRMB", "btnMouseMMB", "btnMouseBack", "btnMouseForward",
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
    /** 是否使用网格吸附。关闭后控件按像素自由摆放（内部 1 单位 = 1 px），尺寸按屏幕长边百分比存储。 */
    val useGrid: Boolean = true,
    /** 网格密度（横向列数），默认 120，范围 30..240。 */
    val gridDensity: Int = 120,
    val gyroOrientation: GyroOrientation? = null,
    val gyroActivateMode: GyroActivateMode? = null,
    val gyroMode: GyroMode? = null,
    val gyroModeSensitivity: Int? = null,
    val gyroDeadZone: Int? = null,
    val gyroReverseDeadZone: Int? = null,
    /** Shared sensitivity curve (flat [x0,y0,x1,y1,...]) for the gyro/accel -> stick modes. */
    val gyroStickCurve: List<Float>? = null,
    /** Per-layout remapping of the physical controller; a missing key means "default". */
    val physicalInputMappings: Map<String, PhysicalInputMapping> = emptyMap(),
    /** Analog shaping (dead zone / anti dead zone / curve) for the left physical joystick. */
    val leftStickSettings: PhysicalStickSettings? = null,
    /** Analog shaping (dead zone / anti dead zone / curve) for the right physical joystick. */
    val rightStickSettings: PhysicalStickSettings? = null,
    /** Per-layout output bits emitted by the volume-up key. */
    val volumeUpBits: List<Int> = emptyList(),
    /** Per-layout output bits emitted by the volume-down key. */
    val volumeDownBits: List<Int> = emptyList(),
    /** Timing used when [volumeUpBits] / [volumeDownBits] contains a chord. */
    val volumeUpComboTiming: ComboTiming = ComboTiming.SIMULTANEOUS,
    val volumeUpComboIntervalMs: Int = ComboTiming.DEFAULT_INTERVAL_MS,
    val volumeDownComboTiming: ComboTiming = ComboTiming.SIMULTANEOUS,
    val volumeDownComboIntervalMs: Int = ComboTiming.DEFAULT_INTERVAL_MS,
) {
    companion object {
        /** 旧版固定网格列数，也是 `x/y/width/height` 的持久化坐标系。 */
        const val BASE_GRID_COLS = 120
        const val MIN_GRID_DENSITY = 30
        const val DEFAULT_GRID_DENSITY = 120
        const val MAX_GRID_DENSITY = 240

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
            // 网格开关与密度：旧版布局没有这两个字段，默认使用网格 120。
            val useGrid = root.get("useGrid")?.asBoolean ?: true
            val gridDensity = (root.get("gridDensity")?.asInt ?: DEFAULT_GRID_DENSITY)
                .coerceIn(MIN_GRID_DENSITY, MAX_GRID_DENSITY)
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
                    if (!btnObj.has("joystickSensitivity")) btnObj.addProperty("joystickSensitivity", 100)
                    if (!btnObj.has("touchpadMode")) btnObj.addProperty("touchpadMode", false)
                    if (!btnObj.has("prediction")) btnObj.addProperty("prediction", false)
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
                    if (!btnObj.has("keypadTexts")) {
                        val texts = com.google.gson.JsonArray()
                        com.zyz4.gkme.model.ButtonPosition.KEYPAD_DEFAULT_TEXTS.forEach { texts.add(it) }
                        btnObj.add("keypadTexts", texts)
                    }
                    if (!btnObj.has("keypadBits")) {
                        val bitsArr = com.google.gson.JsonArray()
                        repeat(com.zyz4.gkme.model.ButtonPosition.KEYPAD_EIGHT_WAY_COUNT) { bitsArr.add(com.google.gson.JsonArray()) }
                        btnObj.add("keypadBits", bitsArr)
                    }
                    if (!btnObj.has("keypadEightWay")) btnObj.addProperty("keypadEightWay", false)
                    if (!btnObj.has("centerShape")) btnObj.addProperty("centerShape", "SQUARE")
                    // 旧版布局没有 centerSize：补 -1（自动），与显式 0（关闭中心）区分。
                    if (!btnObj.has("centerSize")) btnObj.addProperty("centerSize", -1)
                    if (!btnObj.has("customBits")) {
                        btnObj.add("customBits", com.google.gson.JsonArray())
                    }
                    if (!btnObj.has("comboTiming")) {
                        btnObj.addProperty("comboTiming", ComboTiming.SIMULTANEOUS.name)
                    }
                    if (!btnObj.has("comboIntervalMs")) {
                        btnObj.addProperty("comboIntervalMs", ComboTiming.DEFAULT_INTERVAL_MS)
                    }
                    if (!btnObj.has("keypadComboTimings")) {
                        val timings = com.google.gson.JsonArray()
                        repeat(com.zyz4.gkme.model.ButtonPosition.KEYPAD_EIGHT_WAY_COUNT) {
                            timings.add(ComboTiming.SIMULTANEOUS.name)
                        }
                        btnObj.add("keypadComboTimings", timings)
                    }
                    if (!btnObj.has("keypadComboIntervals")) {
                        val intervals = com.google.gson.JsonArray()
                        repeat(com.zyz4.gkme.model.ButtonPosition.KEYPAD_EIGHT_WAY_COUNT) {
                            intervals.add(ComboTiming.DEFAULT_INTERVAL_MS)
                        }
                        btnObj.add("keypadComboIntervals", intervals)
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

                    // 旧版触摸板按下按钮 id "btnTouchpad" 迁移为 "btnShare"，保留多实例后缀。
                    btnObj.get("id")?.asString?.let { oldId ->
                        if (oldId == "btnTouchpad" || oldId.startsWith("btnTouchpad_")) {
                            btnObj.addProperty("id", "btnShare" + oldId.removePrefix("btnTouchpad"))
                        }
                    }

                    val bp = gsonInstance.fromJson(btnObj, com.zyz4.gkme.model.ButtonPosition::class.java)
                    val baseId = bp.id.substringBefore("_")
                    // Override lockAspect and isKeyboard from hard-coded tables
                    val lockAspect = if (isKeyboard(baseId)) false else !isLockAspectFalse(baseId)
                    buttons.add(bp.copy(
                        lockAspect = lockAspect,
                        isKeyboard = isKeyboard(baseId),
                        centerSize = if (bp.centerSize >= 0) {
                            bp.centerSize.coerceIn(
                                com.zyz4.gkme.model.ButtonPosition.CENTER_SIZE_MIN,
                                com.zyz4.gkme.model.ButtonPosition.CENTER_SIZE_MAX,
                            )
                        } else {
                            -1
                        },
                    ))
                }
            }
            val gyro = root.get("gyroOrientation")?.asString?.let { GyroOrientation.valueOf(it) }
            val gyroActivateMode = root.get("gyroActivateMode")?.asString?.let { GyroActivateMode.valueOf(it) }
            val gyroMode = root.get("gyroMode")?.asString?.let { GyroMode.valueOf(it) }
            val gyroModeSens = root.get("gyroModeSensitivity")?.asInt
            val gyroDeadZone = root.get("gyroDeadZone")?.asInt
            val gyroReverseDeadZone = root.get("gyroReverseDeadZone")?.asInt
            val gyroStickCurve = root.getAsJsonArray("gyroStickCurve")?.map { it.asFloat }
            val physicalInputMappings: Map<String, PhysicalInputMapping> =
                root.get("physicalInputMappings")?.let { element ->
                    val parsed: Map<String, PhysicalInputMapping> = gsonInstance.fromJson(
                        element,
                        object : TypeToken<Map<String, PhysicalInputMapping>>() {}.type,
                    )
                    parsed.mapKeys { (key, _) -> PhysicalInputs.normalizeKey(key) }
                } ?: emptyMap()
            val leftStickSettings = root.get("leftStickSettings")?.let {
                gsonInstance.fromJson(it, PhysicalStickSettings::class.java)
            }
            val rightStickSettings = root.get("rightStickSettings")?.let {
                gsonInstance.fromJson(it, PhysicalStickSettings::class.java)
            }
            val volumeUpBits = root.getAsJsonArray("volumeUpBits")?.map { it.asInt } ?: emptyList()
            val volumeDownBits = root.getAsJsonArray("volumeDownBits")?.map { it.asInt } ?: emptyList()
            val volumeUpComboTiming = ComboTiming.fromNameOrSimultaneous(
                root.get("volumeUpComboTiming")?.asString
            )
            val volumeDownComboTiming = ComboTiming.fromNameOrSimultaneous(
                root.get("volumeDownComboTiming")?.asString
            )
            val volumeUpComboIntervalMs = (root.get("volumeUpComboIntervalMs")?.asInt
                ?: ComboTiming.DEFAULT_INTERVAL_MS).coerceIn(
                ComboTiming.MIN_INTERVAL_MS, ComboTiming.MAX_INTERVAL_MS
            )
            val volumeDownComboIntervalMs = (root.get("volumeDownComboIntervalMs")?.asInt
                ?: ComboTiming.DEFAULT_INTERVAL_MS).coerceIn(
                ComboTiming.MIN_INTERVAL_MS, ComboTiming.MAX_INTERVAL_MS
            )
            return LayoutPreset(
                version = root.get("version")?.asInt ?: 1,
                buttons = buttons,
                useGrid = useGrid,
                gridDensity = gridDensity,
                gyroOrientation = gyro,
                gyroActivateMode = gyroActivateMode,
                gyroMode = gyroMode,
                gyroModeSensitivity = gyroModeSens,
                gyroDeadZone = gyroDeadZone,
                gyroReverseDeadZone = gyroReverseDeadZone,
                gyroStickCurve = gyroStickCurve,
                physicalInputMappings = physicalInputMappings,
                leftStickSettings = leftStickSettings,
                rightStickSettings = rightStickSettings,
                volumeUpBits = volumeUpBits,
                volumeDownBits = volumeDownBits,
                volumeUpComboTiming = volumeUpComboTiming,
                volumeUpComboIntervalMs = volumeUpComboIntervalMs,
                volumeDownComboTiming = volumeDownComboTiming,
                volumeDownComboIntervalMs = volumeDownComboIntervalMs,
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
            // 自由/高精度几何：x/y 为屏幕宽/高百分比，尺寸为长边百分比。
            // 网格模式下也写入，用于保留密度 > 120 时的坐标精度。
            b.freeX?.let { m["freeX"] = it }
            b.freeY?.let { m["freeY"] = it }
            b.freeWidth?.let { m["freeWidth"] = it }
            b.freeHeight?.let { m["freeHeight"] = it }
            b.freeFollowAreaX?.let { m["freeFollowAreaX"] = it }
            b.freeFollowAreaY?.let { m["freeFollowAreaY"] = it }
            b.freeFollowAreaW?.let { m["freeFollowAreaW"] = it }
            b.freeFollowAreaH?.let { m["freeFollowAreaH"] = it }
            b.freeTravelDistance?.let { m["freeTravelDistance"] = it }
            // conditional write (only when not default)
            if (b.visible) m["visible"] = b.visible
            if (b.swipeTrigger) m["swipeTrigger"] = b.swipeTrigger
            m["rotation"] = b.rotation
            if (b.isCustom) {
                m["isCustom"] = b.isCustom
                m["customText"] = (b.customText ?: "自定义")
                m["customBits"] = (b.customBits ?: listOf<Int>())
            }
            if (b.comboTiming != ComboTiming.SIMULTANEOUS) m["comboTiming"] = b.comboTiming.name
            if (b.comboIntervalMs != ComboTiming.DEFAULT_INTERVAL_MS) {
                m["comboIntervalMs"] = b.comboIntervalMs
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
                if (b.joystickSensitivity != 100) m["joystickSensitivity"] = b.joystickSensitivity
                if (b.touchpadMode) m["touchpadMode"] = b.touchpadMode
                if (b.deadZone != 0) m["deadZone"] = b.deadZone
                if (b.reverseDeadZone != 0) m["reverseDeadZone"] = b.reverseDeadZone
                if (b.sensitivityCurve != null && b.sensitivityCurve!!.isNotEmpty()) {
                    m["sensitivityCurve"] = b.sensitivityCurve
                }
            }
            if (b.prediction) m["prediction"] = b.prediction
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
                m["keypadTexts"] = ButtonPosition.keypadTextsOf(b)
                m["keypadBits"] = ButtonPosition.keypadBitsOf(b)
                val keypadTimings = ButtonPosition.keypadComboTimingsOf(b)
                if (keypadTimings.any { it != ComboTiming.SIMULTANEOUS }) {
                    m["keypadComboTimings"] = keypadTimings.map { it.name }
                }
                val keypadIntervals = ButtonPosition.keypadComboIntervalsOf(b)
                if (keypadIntervals.any { it != ComboTiming.DEFAULT_INTERVAL_MS }) {
                    m["keypadComboIntervals"] = keypadIntervals
                }
                if (b.keypadEightWay) m["keypadEightWay"] = true
            }
            if (b.centerShape != CenterShape.SQUARE) m["centerShape"] = b.centerShape.name
            if (b.centerSize >= 0) m["centerSize"] = b.centerSize.coerceIn(
                ButtonPosition.CENTER_SIZE_MIN, ButtonPosition.CENTER_SIZE_MAX,
            )
            if (b.gyroActivate) m["gyroActivate"] = b.gyroActivate
            if (b.autoHold) m["autoHold"] = b.autoHold
            list.add(m)
        }
        val obj = LinkedHashMap<String, Any?>()
        obj["version"] = version
        obj["buttons"] = list
        // 网格配置属于布局级设置，始终写出，便于网页编辑器与旧版本保留。
        obj["useGrid"] = useGrid
        obj["gridDensity"] = gridDensity.coerceIn(MIN_GRID_DENSITY, MAX_GRID_DENSITY)
        gyroOrientation?.let { obj["gyroOrientation"] = it.name }
        gyroActivateMode?.let { obj["gyroActivateMode"] = it.name }
        gyroMode?.let { obj["gyroMode"] = it.name }
        gyroModeSensitivity?.let { obj["gyroModeSensitivity"] = it }
        gyroDeadZone?.let { obj["gyroDeadZone"] = it }
        gyroReverseDeadZone?.let { obj["gyroReverseDeadZone"] = it }
        if (!gyroStickCurve.isNullOrEmpty()) obj["gyroStickCurve"] = gyroStickCurve
        if (physicalInputMappings.isNotEmpty()) obj["physicalInputMappings"] = physicalInputMappings
        if (leftStickSettings != null && !leftStickSettings.isDefault()) obj["leftStickSettings"] = leftStickSettings
        if (rightStickSettings != null && !rightStickSettings.isDefault()) obj["rightStickSettings"] = rightStickSettings
        if (volumeUpBits.isNotEmpty()) obj["volumeUpBits"] = volumeUpBits
        if (volumeDownBits.isNotEmpty()) obj["volumeDownBits"] = volumeDownBits
        if (volumeUpComboTiming != ComboTiming.SIMULTANEOUS) {
            obj["volumeUpComboTiming"] = volumeUpComboTiming.name
        }
        if (volumeUpComboIntervalMs != ComboTiming.DEFAULT_INTERVAL_MS) {
            obj["volumeUpComboIntervalMs"] = volumeUpComboIntervalMs
        }
        if (volumeDownComboTiming != ComboTiming.SIMULTANEOUS) {
            obj["volumeDownComboTiming"] = volumeDownComboTiming.name
        }
        if (volumeDownComboIntervalMs != ComboTiming.DEFAULT_INTERVAL_MS) {
            obj["volumeDownComboIntervalMs"] = volumeDownComboIntervalMs
        }
        return gson.toJson(obj)
    }

}