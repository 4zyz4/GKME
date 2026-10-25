package com.zyz4.gkme.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the optional chord timing attached to every mapped output list. */
class ComboTimingSerializationTest {

    @Test
    fun customButtonTiming_roundTrip() {
        val preset = LayoutPreset(
            buttons = listOf(
                ButtonPosition(
                    id = "btnCustomCircle",
                    isCustom = true,
                    customBits = listOf(-0x06, -0x01),
                    comboTiming = ComboTiming.CUSTOM_INTERVAL,
                    comboIntervalMs = 7,
                )
            )
        )

        val restored = LayoutPreset.fromJson(preset.toJson()).buttons.single()

        assertEquals(ComboTiming.CUSTOM_INTERVAL, restored.comboTiming)
        assertEquals(7, restored.comboIntervalMs)
    }

    @Test
    fun keypadRegionTiming_roundTrip() {
        val timings = List(ButtonPosition.KEYPAD_EIGHT_WAY_COUNT) { ComboTiming.SIMULTANEOUS }
            .toMutableList()
        timings[2] = ComboTiming.FRAME_BY_FRAME
        val intervals = List(ButtonPosition.KEYPAD_EIGHT_WAY_COUNT) { 16 }
            .toMutableList()
        intervals[2] = 3
        val preset = LayoutPreset(
            buttons = listOf(
                ButtonPosition(
                    id = "customKeypad",
                    isKeypad = true,
                    keypadComboTimings = timings,
                    keypadComboIntervals = intervals,
                )
            )
        )

        val restored = LayoutPreset.fromJson(preset.toJson()).buttons.single()

        assertEquals(ComboTiming.FRAME_BY_FRAME, ButtonPosition.keypadComboTimingOf(restored, 2))
        assertEquals(3, ButtonPosition.keypadComboIntervalOf(restored, 2))
        assertEquals(ComboTiming.SIMULTANEOUS, ButtonPosition.keypadComboTimingOf(restored, 0))
    }

    @Test
    fun volumeAndPhysicalMappingTiming_roundTrip() {
        val preset = LayoutPreset(
            volumeUpBits = listOf(-0x06, -0x01),
            volumeUpComboTiming = ComboTiming.CUSTOM_INTERVAL,
            volumeUpComboIntervalMs = 42,
            volumeDownComboTiming = ComboTiming.FRAME_BY_FRAME,
            volumeDownComboIntervalMs = 9,
            physicalInputMappings = mapOf(
                PhysicalInputs.KEY_A to PhysicalInputMapping(
                    outputs = listOf(-0x06, -0x01),
                    comboTiming = ComboTiming.CUSTOM_INTERVAL,
                    comboIntervalMs = 25,
                )
            ),
        )

        val restored = LayoutPreset.fromJson(preset.toJson())

        assertEquals(ComboTiming.CUSTOM_INTERVAL, restored.volumeUpComboTiming)
        assertEquals(42, restored.volumeUpComboIntervalMs)
        assertEquals(ComboTiming.FRAME_BY_FRAME, restored.volumeDownComboTiming)
        assertEquals(9, restored.volumeDownComboIntervalMs)
        val mapping = restored.physicalInputMappings[PhysicalInputs.KEY_A]
        assertEquals(ComboTiming.CUSTOM_INTERVAL, mapping?.comboTiming)
        assertEquals(25, mapping?.comboIntervalMs)
    }

    @Test
    fun legacyJson_defaultsToSimultaneous() {
        val json = """
            {
              "version": 1,
              "buttons": [{"id": "btnA", "x": 0, "y": 0, "width": 10, "height": 10}],
              "physicalInputMappings": {"A": {"outputs": [1]}}
            }
        """.trimIndent()

        val restored = LayoutPreset.fromJson(json)
        val button = restored.buttons.single()
        val mapping = restored.physicalInputMappings[PhysicalInputs.KEY_A]

        assertEquals(ComboTiming.SIMULTANEOUS, button.comboTiming)
        assertEquals(ComboTiming.DEFAULT_INTERVAL_MS, button.comboIntervalMs)
        assertEquals(ComboTiming.SIMULTANEOUS, ButtonPosition.keypadComboTimingOf(button, 0))
        assertEquals(ComboTiming.SIMULTANEOUS, mapping?.comboTiming)
        assertEquals(ComboTiming.DEFAULT_INTERVAL_MS, mapping?.comboIntervalMs)
    }

    @Test
    fun effectiveInterval_isModeDependent() {
        assertEquals(0, ComboTiming.effectiveIntervalMs(ComboTiming.SIMULTANEOUS, 50))
        assertEquals(
            ComboTiming.DEFAULT_INTERVAL_MS,
            ComboTiming.effectiveIntervalMs(ComboTiming.FRAME_BY_FRAME, 50),
        )
        assertEquals(50, ComboTiming.effectiveIntervalMs(ComboTiming.CUSTOM_INTERVAL, 50))
        assertEquals(
            ComboTiming.MAX_INTERVAL_MS,
            ComboTiming.effectiveIntervalMs(ComboTiming.CUSTOM_INTERVAL, 999),
        )
        assertEquals(
            ComboTiming.MIN_INTERVAL_MS,
            ComboTiming.effectiveIntervalMs(ComboTiming.CUSTOM_INTERVAL, 0),
        )
    }
}
