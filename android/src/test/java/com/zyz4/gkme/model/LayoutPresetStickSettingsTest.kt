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

    @Test
    fun stickModeAndDirections_roundTrip() {
        val left = PhysicalStickSettings(
            mode = PhysicalStickMode.EIGHT_WAY,
            mouseSensitivity = 150,
            directions = listOf(
                PhysicalStickDirectionSettings(listOf(GamepadState.DPAD_BIT_UP)),
                PhysicalStickDirectionSettings(),
                PhysicalStickDirectionSettings(
                    outputs = listOf(-0x04, GamepadState.A),
                    comboTiming = ComboTiming.CUSTOM_INTERVAL,
                    comboIntervalMs = 25,
                ),
            ),
        )
        val restored = LayoutPreset.fromJson(LayoutPreset(leftStickSettings = left).toJson())
        val settings = restored.leftStickSettings
        assertNotNull(settings)
        assertEquals(PhysicalStickMode.EIGHT_WAY, settings!!.mode)
        assertEquals(150, settings.mouseSensitivity)
        assertEquals(
            listOf(GamepadState.DPAD_BIT_UP),
            settings.directionAt(PhysicalStickDirection.UP).outputs,
        )
        // 未写入的索引回退为未绑定。
        assertEquals(
            emptyList<Int>(),
            settings.directionAt(PhysicalStickDirection.DOWN).outputs,
        )
        val leftDirection = settings.directionAt(PhysicalStickDirection.LEFT)
        assertEquals(listOf(-0x04, GamepadState.A), leftDirection.outputs)
        assertEquals(ComboTiming.CUSTOM_INTERVAL, leftDirection.comboTiming)
        assertEquals(25, leftDirection.comboIntervalMs)
    }

    @Test
    fun withDirection_trimsTrailingDefaults() {
        val settings = PhysicalStickSettings(mode = PhysicalStickMode.FOUR_WAY)
            .withDirection(
                PhysicalStickDirection.RIGHT,
                PhysicalStickDirectionSettings(listOf(GamepadState.A)),
            )
        assertEquals(PhysicalStickDirection.RIGHT.ordinal + 1, settings.directions?.size)

        val cleared = settings.withDirection(
            PhysicalStickDirection.RIGHT,
            PhysicalStickDirectionSettings(),
        )
        assertNull(cleared.directions)
        assertFalse(cleared.isDefault()) // mode 仍然需要保存
    }
}
