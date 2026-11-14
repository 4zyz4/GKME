package com.zyz4.gkme.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the physical-controller touchpad mapping on [LayoutPreset]. */
class LayoutPresetTouchpadSettingsTest {

    @Test
    fun touchpadSettings_roundTrip() {
        val settings = PhysicalTouchpadSettings(
            mode = PhysicalTouchpadMode.MOUSE,
            mouseSensitivity = 180,
            scrollSensitivity = 60,
            invertScrollV = true,
            singleTapAction = MouseGestureAction.MIDDLE_CLICK,
            twoFingerTapAction = MouseGestureAction.LEFT_CLICK,
            doubleTapDragAction = MouseGestureAction.RIGHT_DRAG,
            oneFingerSwipeAction = MouseGestureAction.SCROLL,
            twoFingerSwipeAction = MouseGestureAction.MOVE_CURSOR,
        )
        val restored = LayoutPreset.fromJson(LayoutPreset(touchpadSettings = settings).toJson())
        assertEquals(settings, restored.touchpadSettings)
    }

    @Test
    fun defaultTouchpadSettings_omittedFromJson() {
        val json = LayoutPreset(touchpadSettings = PhysicalTouchpadSettings()).toJson()
        assertFalse(json.contains("touchpadSettings"))
    }

    @Test
    fun missingTouchpadSettings_staysNull() {
        assertNull(LayoutPreset.fromJson("""{"version":1,"buttons":[]}""").touchpadSettings)
    }

    @Test
    fun isDefault_requiresNativeModeAndNeutralMouseOptions() {
        assertTrue(PhysicalTouchpadSettings().isDefault())
        assertFalse(PhysicalTouchpadSettings(mode = PhysicalTouchpadMode.MOUSE).isDefault())
        assertFalse(PhysicalTouchpadSettings(mouseSensitivity = 150).isDefault())
    }

    @Test
    fun sensitivityScales_areClamped() {
        val low = PhysicalTouchpadSettings(mouseSensitivity = 1, scrollSensitivity = 1)
        assertEquals(
            PhysicalTouchpadSettings.MIN_SENSITIVITY / 100f,
            low.mouseSensitivityScale,
            0f,
        )
        assertEquals(
            PhysicalTouchpadSettings.MIN_SENSITIVITY / 100f,
            low.scrollSensitivityScale,
            0f,
        )
    }
}
