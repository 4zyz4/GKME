package com.zyz4.gkme.input

import org.junit.Assert.assertEquals
import org.junit.Test

class JoyConStickDecoderTest {
    @Test
    fun leftHatReportsVerticalStickMovementAndRecenters() {
        val decoder = JoyConStickDecoder(JoyConSide.LEFT)

        assertEquals(JoyConStickDecoder.Sample(32767, 0, usesHat = true), decoder.decode(0, 0, 0f, -1f))
        assertEquals(JoyConStickDecoder.Sample(32767, 32767, usesHat = true), decoder.decode(0, 0, 1f, -1f))
        assertEquals(JoyConStickDecoder.Sample(0, 0), decoder.decode(0, 0, 0f, 0f))
    }

    @Test
    fun rightHatRotatesInTheOppositeDirection() {
        val decoder = JoyConStickDecoder(JoyConSide.RIGHT)

        assertEquals(JoyConStickDecoder.Sample(-32767, 0, usesHat = true), decoder.decode(0, 0, 0f, -1f))
        assertEquals(JoyConStickDecoder.Sample(-32767, -32767, usesHat = true), decoder.decode(0, 0, 1f, -1f))
    }

    @Test
    fun measuredAnalogStickKeepsTheHatSeparateAfterRecentering() {
        val decoder = JoyConStickDecoder(JoyConSide.LEFT)

        assertEquals(JoyConStickDecoder.Sample(12000, -5000), decoder.decode(12000, -5000, 0f, -1f))
        assertEquals(JoyConStickDecoder.Sample(0, 0), decoder.decode(0, 0, 0f, -1f))
    }
}
