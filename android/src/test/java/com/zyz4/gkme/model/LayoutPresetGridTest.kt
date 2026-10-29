package com.zyz4.gkme.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the per-layout grid settings and free-geometry fields. */
class LayoutPresetGridTest {

    @Test
    fun gridDefaults_areGridOnAnd120() {
        val preset = LayoutPreset()
        assertTrue(preset.useGrid)
        assertEquals(LayoutPreset.DEFAULT_GRID_DENSITY, preset.gridDensity)
        assertEquals(120, LayoutPreset.BASE_GRID_COLS)
        assertEquals(240, LayoutPreset.FREE_GRID_COLS)
    }

    @Test
    fun gridSettings_roundTrip() {
        val preset = LayoutPreset(useGrid = false, gridDensity = 200)
        val json = preset.toJson()

        assertTrue(json.contains("\"useGrid\":false"))
        assertTrue(json.contains("\"gridDensity\":200"))

        val restored = LayoutPreset.fromJson(json)
        assertFalse(restored.useGrid)
        assertEquals(200, restored.gridDensity)
    }

    @Test
    fun density_isClampedAndDefaultsWhenMissing() {
        assertEquals(
            240,
            LayoutPreset.fromJson("""{"buttons":[],"gridDensity":999}""").gridDensity,
        )
        assertEquals(
            120,
            LayoutPreset.fromJson("""{"buttons":[],"gridDensity":1}""").gridDensity,
        )
        val legacy = LayoutPreset.fromJson("""{"buttons":[]}""")
        assertTrue(legacy.useGrid)
        assertEquals(120, legacy.gridDensity)
    }

    @Test
    fun freeGeometry_roundTrip() {
        val button = ButtonPosition(
            id = "btnA",
            x = 30,
            y = 40,
            width = 12,
            height = 9,
            freeX = 25f,
            freeY = 33.5f,
            freeWidth = 10.5f,
            freeHeight = 7.5f,
            freeFollowAreaX = 20f,
            freeFollowAreaY = 30f,
            freeFollowAreaW = 40f,
            freeFollowAreaH = 50f,
            freeTravelDistance = 8.5f,
        )
        val preset = LayoutPreset(useGrid = false, gridDensity = 180, buttons = listOf(button))
        val restored = LayoutPreset.fromJson(preset.toJson())

        assertFalse(restored.useGrid)
        assertEquals(180, restored.gridDensity)
        val restoredButton = restored.buttons.single()
        // Legacy 120-grid coordinates stay untouched for older readers.
        assertEquals(30, restoredButton.x)
        assertEquals(40, restoredButton.y)
        assertEquals(12, restoredButton.width)
        assertEquals(9, restoredButton.height)
        assertEquals(25f, restoredButton.freeX!!, 0f)
        assertEquals(33.5f, restoredButton.freeY!!, 0f)
        assertEquals(10.5f, restoredButton.freeWidth!!, 0f)
        assertEquals(7.5f, restoredButton.freeHeight!!, 0f)
        assertEquals(20f, restoredButton.freeFollowAreaX!!, 0f)
        assertEquals(30f, restoredButton.freeFollowAreaY!!, 0f)
        assertEquals(40f, restoredButton.freeFollowAreaW!!, 0f)
        assertEquals(50f, restoredButton.freeFollowAreaH!!, 0f)
        assertEquals(8.5f, restoredButton.freeTravelDistance!!, 0f)
    }

    @Test
    fun freeGeometry_isOmittedWhenUnset() {
        val json = LayoutPreset(
            useGrid = true,
            buttons = listOf(ButtonPosition(id = "btnA", x = 1, y = 2, width = 3, height = 4)),
        ).toJson()
        assertFalse(json.contains("freeX"))
        assertNull(LayoutPreset.fromJson(json).buttons.single().freeX)
    }

    @Test
    fun freeGeometry_isKeptWithGridOnToPreserveDensityPrecision() {
        val button = ButtonPosition(
            id = "btnA",
            x = 1,
            y = 2,
            width = 3,
            height = 4,
            freeX = 1.5f,
            freeWidth = 2.5f,
        )
        val json = LayoutPreset(useGrid = true, gridDensity = 240, buttons = listOf(button)).toJson()
        assertTrue(json.contains("\"freeX\":1.5"))
        assertTrue(json.contains("\"freeWidth\":2.5"))
        assertEquals(1.5f, LayoutPreset.fromJson(json).buttons.single().freeX!!, 0f)
    }
}
