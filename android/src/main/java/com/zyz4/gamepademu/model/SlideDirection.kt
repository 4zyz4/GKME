package com.zyz4.gamepademu.model

enum class SlideDirection(val jsonValue: String) {
    DOWN("down"),
    UP("up"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun fromString(value: String): SlideDirection {
            return entries.find { it.jsonValue == value } ?: DOWN
        }
    }
}