package com.zyz4.gkme.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the physical-stick shaping fields on [LayoutPreset]. */
class LayoutPresetStickSettingsTest {

    @Test
    fun stickSettings_roundTrip() {
        val preset = LayoutPreset(
            leftStickSettings = PhysicalStickSettings(deadZone = 15, reverseDeadZone = 5, curve = listOf(0.5f, 0.7f)),
            rightStickSettings = PhysicalStickSettings(deadZone = 20),
        )
        val restored = LayoutPreset.fromJson(preset.toJson())

        assertEquals(15, restored.leftStickSettings?.deadZone)
        assertEquals(5, restored.leftStickSettings?.reverseDeadZone)
        val curve = restored.leftStickSettings?.curve
        assertNotNull(curve)
        // assertEquals(Float, Float) also fails if Gson degraded the element to Double.
        assertEquals(0.5f, curve!![0], 0f)
        assertEquals(0.7f, curve[1], 0f)

        assertEquals(20, restored.rightStickSettings?.deadZone)
        assertNull(restored.rightStickSettings?.curve)
    }

    @Test
    fun defaultStickSettings_omittedFromJson() {
        val json = LayoutPreset(leftStickSettings = PhysicalStickSettings()).toJson()
        assertFalse(json.contains("leftStickSettings"))
    }
}
