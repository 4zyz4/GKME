package com.zyz4.gamepademu.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class LayoutPreset(
    val version: Int = 1,
    val buttons: List<ButtonPosition> = emptyList(),
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): LayoutPreset {
            val type = object : TypeToken<LayoutPreset>() {}.type
            val preset = gson.fromJson<LayoutPreset>(json, type)
            return preset.copy(
                buttons = (preset.buttons ?: emptyList()).map { it.sanitize() }
            )
        }

        fun toJson(preset: LayoutPreset): String = gson.toJson(preset)
    }

    fun toJson(): String = gson.toJson(this)
}
