package com.zyz4.gkme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** JVM tests for the voice-coil PCM -> dominant pitch (Hz) tracker. */
class PcmPitchTrackerTest {

    private val rate = 48000
    private val channels = 4
    private val blockFrames = rate / 100

    @Test
    fun singleTone_pitchIsTrackedAccurately() {
        for (target in listOf(120.0, 200.0, 350.0, 480.0)) {
            val tracker = PcmPitchTracker()
            var pitch: PcmPitchTracker.Pitch? = null
            var frameIndex = 0
            repeat(20) {
                val pcm = toneBlock(frameIndex, target, 20000.0)
                frameIndex += blockFrames
                tracker.process(pcm, channels, rate)?.let { pitch = it }
            }
            val got = pitch!!.freqHz
            assertEquals("tone $target Hz", target, got, 3.0)
        }
    }

    @Test
    fun amplitudeIsNormalized() {
        val tracker = PcmPitchTracker()
        var pitch: PcmPitchTracker.Pitch? = null
        var frameIndex = 0
        repeat(20) {
            tracker.process(toneBlock(frameIndex, 200.0, 32767.0), channels, rate)?.let { pitch = it }
            frameIndex += blockFrames
        }
        assertTrue("amp ${pitch!!.amp} should be positive", pitch!!.amp > 0.2)
        assertTrue("amp ${pitch.amp} should be <= 1", pitch.amp <= 1.0)
    }

    @Test
    fun silence_hasNoOutputUntilWarmedUp() {
        val tracker = PcmPitchTracker()
        for (i in 0 until 5) {
            // Not enough samples yet -> null.
            val r = tracker.process(ByteArray(blockFrames * channels * 2), channels, rate)
            if (i < 3) assertTrue("should still be warming up", r == null)
        }
    }

    @Test
    fun broadbandNoise_isUnvoicedAndFallsBackToResonance() {
        val tracker = PcmPitchTracker()
        val rng = java.util.Random(1234)
        var pitch: PcmPitchTracker.Pitch? = null
        repeat(30) {
            tracker.process(noiseBlock(rng), channels, rate)?.let { pitch = it }
        }
        val p = pitch!!
        assertTrue("宽带噪声不应被判为有声调", !p.voiced)
        assertEquals("unvoiced 时音高应为 0（下游用谐振驱动）", 0.0, p.freqHz, 1e-9)
    }

    @Test
    fun frequencySweep_isTrackedMonotonically() {
        val tracker = PcmPitchTracker()
        val results = mutableListOf<Double>()
        var frameIndex = 0
        repeat(50) {
            val pcm = sweepBlock(frameIndex)
            frameIndex += blockFrames
            tracker.process(pcm, channels, rate)?.let { results.add(it.freqHz) }
        }
        assertTrue("should have output", results.size > 5)
        val first = results.take(5).average()
        val last = results.takeLast(5).average()
        assertTrue("sweep should rise: first=$first last=$last", last - first > 80.0)
    }

    private fun toneBlock(startFrame: Int, freq: Double, amp: Double): ByteArray {
        val out = ByteArray(blockFrames * channels * 2)
        for (i in 0 until blockFrames) {
            val t = (startFrame + i).toDouble() / rate
            val s = (sin(2.0 * PI * freq * t) * amp).roundToInt().coerceIn(-32768, 32767)
            // Channels 2 and 3 are the voice-coil / actuator lanes.
            writeShort(out, i * channels * 2 + 2 * 2, s)
            writeShort(out, i * channels * 2 + 3 * 2, s)
        }
        return out
    }

    /** 120Hz → 300Hz 线性扫频；相位用解析积分，保证瞬时频率准确。 */
    private fun sweepBlock(startFrame: Int): ByteArray {
        val f0 = 120.0
        val f1 = 300.0
        val sweepSeconds = 0.5
        val out = ByteArray(blockFrames * channels * 2)
        for (i in 0 until blockFrames) {
            val t = (startFrame + i).toDouble() / rate
            val phase = 2.0 * PI * (f0 * t + (f1 - f0) * t * t / (2.0 * sweepSeconds))
            val s = (sin(phase) * 20000.0).roundToInt().coerceIn(-32768, 32767)
            writeShort(out, i * channels * 2 + 2 * 2, s)
            writeShort(out, i * channels * 2 + 3 * 2, s)
        }
        return out
    }

    /** 带限不明显的伪随机噪声（宽带）。 */
    private fun noiseBlock(rng: java.util.Random): ByteArray {
        val out = ByteArray(blockFrames * channels * 2)
        for (i in 0 until blockFrames) {
            val s = rng.nextInt(40001) - 20000
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