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
)
