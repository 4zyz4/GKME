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
    val sensitivityCurve: List<Float>? = null,
    val deadZone: Int = 0,
    val alpha: Int = 255,
    val followAreaEnabled: Boolean = false,
    val followAreaX: Int = 0,
    val followAreaY: Int = 0,
    val followAreaW: Int = 0,
    val followAreaH: Int = 0,
) {
    fun sanitize(): ButtonPosition = if (isCustom) {
        copy(customText = customText ?: "自定义", customBits = customBits ?: emptyList(), id = id ?: "")
    } else {
        copy(customText = null, customBits = null, id = id ?: "")
    }
}
