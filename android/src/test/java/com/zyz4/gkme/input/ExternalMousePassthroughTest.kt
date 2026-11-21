package com.zyz4.gkme.input

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class ExternalMousePassthroughTest {
    private val reports = mutableListOf<MouseReport>()
    private val mouse = ExternalMousePassthrough { reports += it }

    @Test fun movesUseRelativeCountsWithNoScreenEdgeAndRetainFractions() {
        mouse.handle(1, MotionEvent.ACTION_MOVE, 0, 1000f, -700f)
        repeat(4) { mouse.handle(1, MotionEvent.ACTION_MOVE, 0, .25f, -.25f) }
        assertEquals(MouseReport(0, 1000, -700), reports[0])
        assertEquals(MouseReport(0, 1, -1), reports[1])
        assertEquals(2, reports.size)
    }

    @Test fun fractionalVerticalAndHorizontalScrollAccumulateAndKeepDirection() {
        repeat(4) { mouse.handle(1, MotionEvent.ACTION_SCROLL, 0, wheel = .25f, pan = -.5f) }
        assertEquals(1, reports.sumOf { it.wheel })
        assertEquals(-2, reports.sumOf { it.pan })
        assertTrue(reports.all { it.dx == 0 && it.dy == 0 })
    }

    @Test fun clickDragRightMiddleAndSideButtonsReleaseIndependently() {
        mouse.handle(1, MotionEvent.ACTION_DOWN, 1)
        mouse.handle(1, MotionEvent.ACTION_MOVE, 1, 20f, 10f)
        mouse.handle(1, MotionEvent.ACTION_BUTTON_PRESS, 0x1F)
        mouse.handle(1, MotionEvent.ACTION_BUTTON_RELEASE, 0x1E)
        mouse.handle(1, MotionEvent.ACTION_UP, 0)
        assertEquals(listOf(1, 1, 0x1F, 0x1E, 0), reports.map { it.buttons })
        assertEquals(20, reports.sumOf { it.dx })
        assertEquals(10, reports.sumOf { it.dy })
    }

    @Test fun duplicateDownAndUpEventsDoNotGenerateExtraClicks() {
        mouse.handle(1, MotionEvent.ACTION_DOWN, 1)
        mouse.handle(1, MotionEvent.ACTION_BUTTON_PRESS, 1)
        mouse.handle(1, MotionEvent.ACTION_BUTTON_RELEASE, 0)
        mouse.handle(1, MotionEvent.ACTION_UP, 0)
        assertEquals(listOf(MouseReport(1), MouseReport()), reports)
    }

    @Test fun removingOneMousePreservesAnotherMousesHeldButton() {
        mouse.handle(1, MotionEvent.ACTION_DOWN, 1)
        mouse.handle(2, MotionEvent.ACTION_DOWN, 1)
        mouse.releaseDevice(1)
        assertEquals(listOf(MouseReport(1)), reports)
        mouse.releaseDevice(2)
        assertEquals(MouseReport(), reports.last())
    }

    @Test fun cancelAndCaptureLossReleaseAllButtonsAndForgetFractionalMotion() {
        mouse.handle(1, MotionEvent.ACTION_DOWN, 3)
        mouse.handle(1, MotionEvent.ACTION_MOVE, 3, .75f, .75f)
        mouse.handle(1, MotionEvent.ACTION_CANCEL, 3)
        assertEquals(MouseReport(), reports.last())
        mouse.handle(1, MotionEvent.ACTION_MOVE, 0, .25f, .25f)
        assertEquals(2, reports.size)
        mouse.handle(2, MotionEvent.ACTION_DOWN, 4)
        mouse.releaseAll()
        mouse.releaseAll()
        assertEquals(listOf(3, 0, 4, 0), reports.map { it.buttons })
    }

    @Test fun wheelCoordinatesAndButtonCoordinatesDoNotGenerateMovement() {
        mouse.handle(1, MotionEvent.ACTION_SCROLL, 0, 100f, 200f, 1f, -1f)
        mouse.handle(1, MotionEvent.ACTION_DOWN, 1, 100f, 200f)
        assertEquals(MouseReport(0, wheel = 1, pan = -1), reports.first())
        assertEquals(MouseReport(1), reports.last())
    }

    @Test fun invalidValuesAndUnsupportedActionsProduceNoMotion() {
        mouse.handle(1, MotionEvent.ACTION_MOVE, 0, Float.NaN, Float.POSITIVE_INFINITY)
        assertFalse(mouse.handle(1, MotionEvent.ACTION_HOVER_EXIT, 0, 20f, 20f))
        assertTrue(reports.isEmpty())
    }

    @Test fun largeMovesSplitThroughShortChannelWithoutWrappingOrLosingCounts() {
        val original = MouseReport(5, 70000, -70000, 50000, -50000)
        val chunks = original.chunks()
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.dx in -32768..32767 && it.dy in -32768..32767 && it.buttons == 5 })
        assertEquals(original.dx, chunks.sumOf { it.dx })
        assertEquals(original.dy, chunks.sumOf { it.dy })
        assertEquals(original.wheel, chunks.sumOf { it.wheel })
        assertEquals(original.pan, chunks.sumOf { it.pan })
        assertEquals(listOf(MouseReport()), MouseReport().chunks())
    }

    @Test fun fractionsArePerDeviceAndDoNotLeakAcrossHotplug() {
        mouse.handle(1, MotionEvent.ACTION_MOVE, 0, .75f)
        mouse.handle(2, MotionEvent.ACTION_MOVE, 0, .25f)
        mouse.releaseDevice(1)
        mouse.handle(1, MotionEvent.ACTION_MOVE, 0, .25f)
        assertTrue(reports.isEmpty())
    }

    @Test fun acceptsRealMouseAndKeyboardMouseReceiverButExcludesGamepadTouchpadAndTouch() {
        fun eligible(source: Int = InputDevice.SOURCE_MOUSE_RELATIVE,
            sources: Int = InputDevice.SOURCE_MOUSE, external: Boolean = true,
            virtual: Boolean = false, tool: Int = MotionEvent.TOOL_TYPE_MOUSE) =
            ExternalMousePassthrough.isExternalMouse(source, sources, external, virtual, tool)
        assertTrue(eligible())
        assertTrue(eligible(source = InputDevice.SOURCE_MOUSE))
        assertTrue(eligible(sources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE))
        assertFalse(eligible(external = false))
        assertFalse(eligible(virtual = true))
        assertFalse(eligible(tool = MotionEvent.TOOL_TYPE_FINGER))
        assertFalse(eligible(tool = MotionEvent.TOOL_TYPE_STYLUS))
        assertFalse(eligible(source = InputDevice.SOURCE_TOUCHSCREEN))
        assertFalse(eligible(sources = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_GAMEPAD))
        assertTrue(eligible(sources = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_JOYSTICK))
        assertFalse(eligible(sources = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TOUCHPAD))
    }

    @Test fun diagnosticsDistinguishDeviceCapabilitiesFromEventAndToolRejections() {
        fun reason(eventSource: Int = InputDevice.SOURCE_MOUSE_RELATIVE,
            deviceSources: Int = InputDevice.SOURCE_MOUSE, tool: Int = MotionEvent.TOOL_TYPE_MOUSE) =
            ExternalMousePassthrough.rejectionReason(eventSource, deviceSources, true, false, tool)
        assertNull(reason())
        assertNull(reason(deviceSources = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_JOYSTICK))
        assertEquals("joystick_event", reason(eventSource = InputDevice.SOURCE_MOUSE_RELATIVE or InputDevice.SOURCE_JOYSTICK))
        assertEquals("touchpad_device", reason(deviceSources = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TOUCHPAD))
        assertEquals("gamepad_event", reason(eventSource = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_GAMEPAD))
        assertEquals("not_mouse_tool", reason(tool = MotionEvent.TOOL_TYPE_FINGER))
        assertEquals("not_mouse_event_source", reason(eventSource = InputDevice.SOURCE_TOUCHSCREEN))
        assertEquals("not_mouse_device_source", reason(deviceSources = InputDevice.SOURCE_KEYBOARD))
    }

    @Test fun spk7328BluetoothMetadataAllowsMouseDetectionAndCapturedMotion() {
        // Real Android 10 diagnostics: SPK7328, vendor 4661 / product 43554.
        val sources = 0x1002313
        for (source in listOf(0x2002, InputDevice.SOURCE_MOUSE_RELATIVE)) {
            assertTrue(ExternalMousePassthrough.isExternalMouse(source, sources, true, false,
                MotionEvent.TOOL_TYPE_MOUSE))
            assertNull(ExternalMousePassthrough.rejectionReason(source, sources, true, false,
                MotionEvent.TOOL_TYPE_MOUSE))
        }
        mouse.handle(22, MotionEvent.ACTION_MOVE, 0, 7f, -4f)
        mouse.handle(22, MotionEvent.ACTION_BUTTON_PRESS, 1)
        mouse.handle(22, MotionEvent.ACTION_SCROLL, 1, wheel = 1f)
        mouse.handle(22, MotionEvent.ACTION_BUTTON_RELEASE, 0)
        assertEquals(listOf(MouseReport(dx = 7, dy = -4), MouseReport(1),
            MouseReport(1, wheel = 1), MouseReport()), reports)
    }

    @Test fun compositeMouseExceptionStillExcludesControllerEventsAndTouchpadDevices() {
        fun accepted(source: Int = InputDevice.SOURCE_MOUSE_RELATIVE, sources: Int = 0x1002313,
            tool: Int = MotionEvent.TOOL_TYPE_MOUSE) =
            ExternalMousePassthrough.isExternalMouse(source, sources, true, false, tool)
        assertTrue(accepted(sources = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_JOYSTICK))
        assertFalse(accepted(source = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_JOYSTICK))
        assertFalse(accepted(source = InputDevice.SOURCE_JOYSTICK))
        assertFalse(accepted(sources = 0x1002313 or InputDevice.SOURCE_GAMEPAD))
        assertFalse(accepted(sources = 0x1002313 or InputDevice.SOURCE_TOUCHPAD))
        assertFalse(accepted(tool = MotionEvent.TOOL_TYPE_FINGER))
        assertFalse(ExternalMousePassthrough.isExternalMouse(0x2002, 0x1002313, false, false,
            MotionEvent.TOOL_TYPE_MOUSE))
        assertFalse(ExternalMousePassthrough.isExternalMouse(0x2002, 0x1002313, true, true,
            MotionEvent.TOOL_TYPE_MOUSE))
    }

    @Test fun yichipReceiverMouseDoesNotRequireAlphabeticKeyboardOrAnyKeyboardCapability() {
        // Real Android 10 diagnostics: YICHIP 2.4G Receiver, VID=12625/PID=4128,
        // sources=0x1002313, keyboardType=NON_ALPHABETIC. Keyboard type is not a mouse gate.
        for (sources in listOf(0x1002313, InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_JOYSTICK)) {
            for (source in listOf(0x2002, InputDevice.SOURCE_MOUSE_RELATIVE)) {
                assertTrue(ExternalMousePassthrough.isExternalMouse(source, sources, true, false,
                    MotionEvent.TOOL_TYPE_MOUSE))
                assertNull(ExternalMousePassthrough.rejectionReason(source, sources, true, false,
                    MotionEvent.TOOL_TYPE_MOUSE))
            }
        }
        mouse.handle(25, MotionEvent.ACTION_MOVE, 0, 3f, -2f)
        mouse.handle(25, MotionEvent.ACTION_BUTTON_PRESS, 1)
        mouse.handle(25, MotionEvent.ACTION_MOVE, 1, 4f, 5f)
        mouse.handle(25, MotionEvent.ACTION_BUTTON_RELEASE, 0)
        mouse.handle(25, MotionEvent.ACTION_SCROLL, 0, wheel = -1f)
        assertEquals(listOf(MouseReport(dx = 3, dy = -2), MouseReport(1),
            MouseReport(1, dx = 4, dy = 5), MouseReport(), MouseReport(wheel = -1)), reports)
    }
}
