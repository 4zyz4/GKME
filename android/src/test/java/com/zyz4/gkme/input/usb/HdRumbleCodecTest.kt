package com.zyz4.gkme.input.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the Switch HD rumble wire encoding. */
class HdRumbleCodecTest {

    @Test
    fun encodedFreq_matchesTheDocumentedTable() {
        // 160 Hz is the 0x80 anchor of the table; 80 Hz is 0x60.
        assertEquals(0x80, HdRumbleCodec.encodedFreq(160.0))
        assertEquals(0x60, HdRumbleCodec.encodedFreq(80.0))
        assertEquals(0x40, HdRumbleCodec.encodedFreq(40.0))
        // 1252 Hz is the maximum and also the exponent for 1252.
        assertEquals(0xDF, HdRumbleCodec.encodedFreq(1252.0))
    }

    @Test
    fun encodedFreq_isMonotonic() {
        var previous = -1
        var hz = 20.0
        while (hz <= 1252.0) {
            val v = HdRumbleCodec.encodedFreq(hz)
            assertTrue("freq code must not decrease at $hz", v >= previous)
            previous = v
            hz += 5.0
        }
    }

    @Test
    fun encodedAmp_isMonotonic_andSilentAtZero() {
        assertEquals(0, HdRumbleCodec.encodedAmp(0.0))
        assertEquals(0, HdRumbleCodec.encodedAmp(0.1))
        var previous = -1
        var amp = 0.13
        while (amp <= 1.0) {
            val v = HdRumbleCodec.encodedAmp(amp)
            assertTrue("amp code must not decrease at $amp", v >= previous)
            previous = v
            amp += 0.01
        }
    }

    @Test
    fun writeClassicSide_packsFreqAndAmp() {
        val out = ByteArray(4)
        // high 160 Hz @ 0.5, low 80 Hz @ 0.5
        HdRumbleCodec.writeClassicSide(out, 0, 160.0, 0.5, 80.0, 0.5)

        assertEquals(0x80, out[0].toInt() and 0xFF) // hf low byte
        assertEquals(0x88, out[1].toInt() and 0xFF) // hfAmp(136) + hf high byte(0)
        assertEquals(0xA0, out[2].toInt() and 0xFF) // lf(0x20) + control(0x80)
        assertEquals(0x62, out[3].toInt() and 0xFF) // lfAmp
    }

    @Test
    fun proCon2Fields_areInRange() {
        assertEquals(0x80 * 4, HdRumbleCodec.proCon2Freq(160.0))
        assertEquals(29000, HdRumbleCodec.proCon2Amp(1.0))
        assertEquals(0, HdRumbleCodec.proCon2Amp(0.0))
        assertTrue(HdRumbleCodec.proCon2Freq(1252.0) <= 0x3FF)
    }
}
