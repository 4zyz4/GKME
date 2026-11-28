package com.zyz4.gkme.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LayoutPresetPrecisionTouchpadTest {

    @Test
    fun precisionTouchpadFlag_roundTrips() {
        val preset = LayoutPreset(
            buttons = listOf(
                ButtonPosition(id = "mousepad", precisionTouchpad = true),
            ),
        )
        val restored = LayoutPreset.fromJson(preset.toJson())
        assertEquals(true, restored.buttons.single().precisionTouchpad)
    }

    @Test
    fun defaultPrecisionTouchpadFlag_isOffAndOmitted() {
        val preset = LayoutPreset(buttons = listOf(ButtonPosition(id = "mousepad")))
        assertFalse(preset.toJson().contains("precisionTouchpad"))
        assertFalse(LayoutPreset.fromJson(preset.toJson()).buttons.single().precisionTouchpad)
    }
}
