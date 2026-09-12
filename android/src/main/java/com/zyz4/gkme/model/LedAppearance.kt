package com.zyz4.gkme.model

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Helpers for appearance colors that follow the live controller LED color.
 *
 * The PC forwards the emulated controller's LED color (0xRRGGBB). A user can bind
 * any appearance color to it from the color picker; bound fields are then updated in
 * real time. To keep controls visible even when the game dims (or turns off) its
 * lightbar, the LED color is clamped to a minimum brightness of #1A1A1A.
 */
object LedAppearance {

    /** The darkest an LED-bound appearance color may become (value component). */
    const val MIN_COLOR: Int = 0xFF1A1A1A.toInt()

    /** Appearance color fields bound to the controller LED color out of the box. */
    val DEFAULT_BOUND_COLORS: Set<String> = setOf("tpOutlineColor")

    /** All AppSettings fields that can be bound to the controller LED color. */
    val colorFieldNames: List<String> = listOf(
        "bgColor",
        "btnColor",
        "btnOutlineColor",
        "joyBaseColor",
        "joyBaseOutlineColor",
        "joyCapColor",
        "joyCapOutlineColor",
        "joyTriggerOutlineColor",
        "tpTriggerOutlineColor",
        "linearTriggerBoxOutlineColor",
        "tpColor",
        "tpOutlineColor",
        "dpadPadColor",
        "dpadPadOutlineColor",
        "dpadPadTriggerOutlineColor",
    )

    /**
     * Raises a dim LED color to the #1A1A1A brightness floor while preserving its hue
     * and saturation, so a nearly-off lightbar still yields a visible control color.
     */
    fun clamp(ledColor: Int): Int {
        val r = Color.red(ledColor)
        val g = Color.green(ledColor)
        val b = Color.blue(ledColor)
        val maxChannel = maxOf(r, g, b)
        val floor = Color.red(MIN_COLOR) // 0x1A
        if (maxChannel >= floor) {
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        if (maxChannel == 0) return MIN_COLOR
        val scale = floor.toFloat() / maxChannel
        val nr = (r * scale).roundToInt().coerceIn(0, 255)
        val ng = (g * scale).roundToInt().coerceIn(0, 255)
        val nb = (b * scale).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (nr shl 16) or (ng shl 8) or nb
    }

    /** Returns a copy of [settings] with every LED-bound color field set to [ledColor]. */
    fun applyLedColor(settings: AppSettings, ledColor: Int): AppSettings {
        if (settings.ledBoundColors.isEmpty()) return settings
        val color = clamp(ledColor)
        var result = settings
        for (field in settings.ledBoundColors) {
            result = setColorValue(result, field, color)
        }
        return result
    }

    /**
     * Sets [field] to [color]. When [bind] is true the field is also marked to follow the
     * live LED color; when false any previous binding is removed (manual color choice).
     */
    fun setColorField(
        settings: AppSettings,
        field: String,
        color: Int,
        bind: Boolean = false,
    ): AppSettings {
        val bound = if (bind) settings.ledBoundColors + field else settings.ledBoundColors - field
        return setColorValue(settings, field, color).copy(ledBoundColors = bound)
    }

    private fun setColorValue(s: AppSettings, field: String, color: Int): AppSettings = when (field) {
        "bgColor" -> s.copy(bgColor = color)
        "btnColor" -> s.copy(btnColor = color)
        "btnOutlineColor" -> s.copy(btnOutlineColor = color)
        "joyBaseColor" -> s.copy(joyBaseColor = color)
        "joyBaseOutlineColor" -> s.copy(joyBaseOutlineColor = color)
        "joyCapColor" -> s.copy(joyCapColor = color)
        "joyCapOutlineColor" -> s.copy(joyCapOutlineColor = color)
        "joyTriggerOutlineColor" -> s.copy(joyTriggerOutlineColor = color)
        "tpTriggerOutlineColor" -> s.copy(tpTriggerOutlineColor = color)
        "linearTriggerBoxOutlineColor" -> s.copy(linearTriggerBoxOutlineColor = color)
        "tpColor" -> s.copy(tpColor = color)
        "tpOutlineColor" -> s.copy(tpOutlineColor = color)
        "dpadPadColor" -> s.copy(dpadPadColor = color)
        "dpadPadOutlineColor" -> s.copy(dpadPadOutlineColor = color)
        "dpadPadTriggerOutlineColor" -> s.copy(dpadPadTriggerOutlineColor = color)
        else -> s
    }
}
