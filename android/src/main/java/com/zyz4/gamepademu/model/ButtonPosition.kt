package com.zyz4.gamepademu.model

data class ButtonPosition(
    val id: String,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 10,
    val height: Int = 10,
    val visible: Boolean = true,
    val lockAspect: Boolean = false,
    val swipeTrigger: Boolean = false,
    val rotation: Int = 0,
    val isCustom: Boolean = false,
    val customText: String? = null,
    val customBits: List<Int>? = null,
    val roundShape: Boolean = true,
    val doubleClickEnable: Boolean = true,
    val isKeypad: Boolean = false,
    val keypadTexts: List<String>? = null,
    val keypadBits: List<List<Int>>? = null,
    val keypadCenterDoubleClick: Boolean = false,
    val sensitivityCurve: List<Float>? = null,
    val deadZone: Int = 0,
    val reverseDeadZone: Int = 0,
    val idleTransparency: Int = 0,
    val activeTransparency: Int = 0,
    val followAreaTransparency: Int = 0,
    val followAreaEnabled: Boolean = false,
    val followAreaX: Int = 0,
    val followAreaY: Int = 0,
    val followAreaW: Int = 0,
    val followAreaH: Int = 0,
    val overlapTrigger: Boolean = true,
    val followAreaOverlapTrigger: Boolean = false,
) {
    companion object {
        const val KEYPAD_BASE_ID = "customKeypad"
        const val KEYPAD_COUNT = 5 // up, down, left, right, center

        val KEYPAD_DEFAULT_TEXTS: List<String> = listOf("上", "下", "左", "右", "中")
        val KEYPAD_DEFAULT_BITS: List<List<Int>> = listOf(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        fun isKeypad(id: String): Boolean =
            id.substringBefore("_") == KEYPAD_BASE_ID

        fun keypadTextsOf(p: ButtonPosition): List<String> {
            val t = p.keypadTexts ?: return KEYPAD_DEFAULT_TEXTS
            if (t.size < KEYPAD_COUNT) return KEYPAD_DEFAULT_TEXTS
            return t
        }

        fun keypadBitsOf(p: ButtonPosition): List<List<Int>> {
            val b = p.keypadBits ?: return KEYPAD_DEFAULT_BITS
            if (b.size < KEYPAD_COUNT) return KEYPAD_DEFAULT_BITS
            return b
        }
    }

    fun sanitize(): ButtonPosition = if (isCustom) {
        copy(customText = customText ?: "自定义", customBits = customBits ?: emptyList(), id = id ?: "")
    } else if (isKeypad) {
        copy(
            keypadTexts = keypadTexts ?: KEYPAD_DEFAULT_TEXTS,
            keypadBits = keypadBits ?: KEYPAD_DEFAULT_BITS,
            id = id ?: "",
        )
    } else {
        copy(customText = null, customBits = null, id = id ?: "")
    }
}
