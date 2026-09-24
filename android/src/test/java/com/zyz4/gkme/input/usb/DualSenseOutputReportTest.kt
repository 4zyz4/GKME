package com.zyz4.gkme.input.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the DualSense output-report flags. The audio-haptics
 * (voice-coil) stream and the compatible-vibration HID report are mutually
 * exclusive, so a report must only carry the motor flags when rumble is
 * actually wanted.
 */
class DualSenseOutputReportTest {

    private fun report(hasTriggers: Boolean, enableRumble: Boolean): ByteArray =
        DualSenseOutputReport.compactFrame(
            0, 0,
            0x01.toByte(), 0x01.toByte(),
            ByteArray(10), ByteArray(10),
            hasTriggers,
            1, 2, 3, 0,
            enableRumble,
        )

    @Test
    fun compactFrame_withRumble_setsMotorFlags() {
        val r = report(hasTriggers = false, enableRumble = true)
        assertEquals(DualSenseOutputReport.ENABLE_RUMBLE.toInt(), r[1].toInt() and 0x03)
    }

    @Test
    fun compactFrame_withoutRumble_omitsMotorFlagsButKeepsTriggers() {
        val r = report(hasTriggers = true, enableRumble = false)
        assertEquals(0, r[1].toInt() and 0x03)
        assertEquals(
            (DualSenseOutputReport.ENABLE_LEFT_TRIGGER.toInt() or
                DualSenseOutputReport.ENABLE_RIGHT_TRIGGER.toInt()),
            r[1].toInt() and 0x0C,
        )
    }

    @Test
    fun standaloneRumbleReport_setsMotorFlags() {
        val r = DualSenseOutputReport.rumble(100, 200)
        assertEquals(DualSenseOutputReport.ENABLE_RUMBLE.toInt(), r[1].toInt() and 0x03)
    }

    @Test
    fun adaptiveTriggerReport_neverTouchesMotorFlags() {
        val r = DualSenseOutputReport.adaptiveTriggers(
            0x0C.toByte(), 0x01.toByte(), 0x01.toByte(), ByteArray(10), ByteArray(10),
        )
        assertEquals(0, r[1].toInt() and 0x03)
    }
}
