package com.zyz4.gkme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** JVM tests for the voice-coil PCM -> HD rumble band analysis. */
class PcmHdRumbleAnalyzerTest {

    private val rate = 48000
    private val channels = 4
    private val blockFrames = rate / 100

    @Test
    fun singleTone_landsInTheLowBand() {
        val analyzer = PcmHdRumbleAnalyzer()
        var bands: HdBands? = null
        var frameIndex = 0
        repeat(20) {
            val pcm = toneBlock(frameIndex, listOf(150.0 to 20000.0))
            frameIndex += blockFrames
            analyzer.process(pcm, channels, rate)?.let { bands = it }
        }
        bands!!
        assertTrue("low amp ${bands.leftLowAmp} should be audible", bands.leftLowAmp > 0.1f)
        assertEquals(150.0, bands.leftLowFreq.toDouble(), 45.0)
    }

    @Test
    fun twoTones_splitIntoLowAndHighBands() {
        val analyzer = PcmHdRumbleAnalyzer()
        var bands: HdBands? = null
        var frameIndex = 0
        repeat(20) {
            val pcm = toneBlock(
                frameIndex,
                listOf(100.0 to 18000.0, 400.0 to 18000.0),
            )
            frameIndex += blockFrames
            analyzer.process(pcm, channels, rate)?.let { bands = it }
        }
        bands!!
        assertTrue("low amp ${bands.leftLowAmp}", bands.leftLowAmp > 0.05f)
        assertTrue("high amp ${bands.leftHighAmp}", bands.leftHighAmp > 0.05f)
        assertTrue(
            "low ${bands.leftLowFreq} should be below high ${bands.leftHighFreq}",
            bands.leftLowFreq < bands.leftHighFreq,
        )
        assertEquals(100.0, bands.leftLowFreq.toDouble(), 50.0)
        assertEquals(400.0, bands.leftHighFreq.toDouble(), 80.0)
    }

    @Test
    fun silence_decaysToZero() {
        val analyzer = PcmHdRumbleAnalyzer()
        var bands: HdBands? = null
        // Drive it with a tone first so there is something to decay.
        var frameIndex = 0
        repeat(20) {
            analyzer.process(toneBlock(frameIndex, listOf(150.0 to 20000.0)), channels, rate)
                ?.let { bands = it }
            frameIndex += blockFrames
        }
        // Then feed silence for a while.
        repeat(30) {
            analyzer.process(ByteArray(blockFrames * channels * 2), channels, rate)?.let { bands = it }
        }
        bands!!
        assertTrue("low amp ${bands.leftLowAmp} should decay", bands.leftLowAmp < 0.02f)
    }

    private fun toneBlock(startFrame: Int, tones: List<Pair<Double, Double>>): ByteArray {
        val out = ByteArray(blockFrames * channels * 2)
        for (i in 0 until blockFrames) {
            val t = (startFrame + i).toDouble() / rate
            var v = 0.0
            for ((freq, amp) in tones) {
                v += sin(2.0 * PI * freq * t) * amp
            }
            val s = v.roundToInt().coerceIn(-32768, 32767)
            // Channels 2 and 3 are the voice-coil / actuator lanes.
            writeShort(out, i * channels * 2 + 2 * 2, s)
            writeShort(out, i * channels * 2 + 3 * 2, s)
        }
        return out
    }

    private fun writeShort(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
