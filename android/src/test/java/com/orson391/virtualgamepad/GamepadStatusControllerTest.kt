package com.orson391.virtualgamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadStatusControllerTest {
    @Test
    fun followsStartRunStopLifecycle() {
        val controller = GamepadStatusController()

        assertTrue(controller.beginStart())
        assertEquals(DeviceStatus.Starting, controller.status.value)
        controller.running()
        assertEquals(DeviceStatus.Running, controller.status.value)
        assertTrue(controller.beginStop())
        assertEquals(DeviceStatus.Stopping, controller.status.value)
        controller.stopped()
        assertEquals(DeviceStatus.Stopped, controller.status.value)
    }

    @Test
    fun rejectsDuplicateStartAndStop() {
        val controller = GamepadStatusController()

        assertFalse(controller.beginStop())
        assertTrue(controller.beginStart())
        assertFalse(controller.beginStart())
        controller.error()
        assertTrue(controller.beginStart())
    }
}
