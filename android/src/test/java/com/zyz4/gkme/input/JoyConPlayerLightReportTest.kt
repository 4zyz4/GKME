package com.zyz4.gkme.input

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class JoyConPlayerLightReportTest {
    @Test
    fun playerOneReportKeepsRumbleNeutralAndWrapsTheSequence() {
        val expected = byteArrayOf(
            0x01, 0x02,
            0x00, 0x01, 0x40, 0x40,
            0x00, 0x01, 0x40, 0x40,
            0x30, 0x01,
        ).copyOf(49)
        assertArrayEquals(expected, joyConPlayerLightReport(0x12))
    }
}
