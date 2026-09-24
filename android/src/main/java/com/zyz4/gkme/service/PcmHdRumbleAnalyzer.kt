package com.zyz4.gkme.service

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-actuator HD rumble bands for a Nintendo Switch family controller.
 * Frequencies are in Hz, amplitudes are normalized to 0..1.
 */
data class HdBands(
    val leftHighFreq: Float,
    val leftHighAmp: Float,
    val leftLowFreq: Float,
    val leftLowAmp: Float,
    val rightHighFreq: Float,
    val rightHighAmp: Float,
    val rightLowFreq: Float,
    val rightLowAmp: Float,
) {
    companion object {
        val SILENT = HdBands(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}

/**
 * Turns the PC's voice-coil PCM into the two-band model a Switch controller's
 * HD rumble expects.
 *
 * The voice-coil channels carry audio haptics, which are typically narrowband
 * tones. A short Goertzel filter bank estimates the two strongest tones per
 * side; they are emitted as the "high" and "low" HD bands, with amplitudes
 * normalized to 0..1 and smoothed across blocks. The result feeds
 * [com.zyz4.gkme.input.usb.HdRumbleCodec] on the controller side.
 *
 * Pure JVM code so the analysis can be unit tested without a device.
 */
class PcmHdRumbleAnalyzer {

    private companion object {
        const val MIN_FREQ_HZ = 40.0
        const val MAX_FREQ_HZ = 1252.0
        const val CANDIDATE_COUNT = 18
        // Ignore tones this weak so silence and dither do not drive the actuator.
        const val NOISE_FLOOR = 0.015f
        // Fraction of the new block blended into the running amplitude.
        const val SMOOTH = 0.6f
        const val FREQ_UPDATE_MIN_AMP = 0.02f
        // A second tone is only kept when it is at least this far from the first
        // (frequency ratio) and this strong relative to it.
        const val MIN_BAND_RATIO = 1.8
        const val MIN_SECOND_LEVEL = 0.3
        const val DEFAULT_LOW_FREQ = 80f
        const val DEFAULT_HIGH_FREQ = 400f
    }

    private val candidates = DoubleArray(CANDIDATE_COUNT) { i ->
        MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow(i.toDouble() / (CANDIDATE_COUNT - 1))
    }

    private var coeffs = DoubleArray(CANDIDATE_COUNT)
    private var cosines = DoubleArray(CANDIDATE_COUNT)
    private var sines = DoubleArray(CANDIDATE_COUNT)

    private var sampleRate = 0
    private var blockFrames = 480
    private var fill = 0
    private var leftBuf = ShortArray(blockFrames)
    private var rightBuf = ShortArray(blockFrames)

    private var hasOutput = false
    private var lLowFreq = DEFAULT_LOW_FREQ
    private var lLowAmp = 0f
    private var lHighFreq = DEFAULT_HIGH_FREQ
    private var lHighAmp = 0f
    private var rLowFreq = DEFAULT_LOW_FREQ
    private var rLowAmp = 0f
    private var rHighFreq = DEFAULT_HIGH_FREQ
    private var rHighAmp = 0f

    private class SideTones(
        var lowFreq: Float, var lowAmp: Float,
        var highFreq: Float, var highAmp: Float,
    )

    fun reset() {
        fill = 0
        hasOutput = false
        lLowFreq = DEFAULT_LOW_FREQ; lLowAmp = 0f
        lHighFreq = DEFAULT_HIGH_FREQ; lHighAmp = 0f
        rLowFreq = DEFAULT_LOW_FREQ; rLowAmp = 0f
        rHighFreq = DEFAULT_HIGH_FREQ; rHighAmp = 0f
    }

    private fun configure(rate: Int) {
        if (rate <= 0 || rate == sampleRate) return
        sampleRate = rate
        blockFrames = maxOf(64, rate / 100) // ~10 ms
        leftBuf = ShortArray(blockFrames)
        rightBuf = ShortArray(blockFrames)
        fill = 0
        val twoPi = 2.0 * Math.PI
        for (c in 0 until CANDIDATE_COUNT) {
            val w = twoPi * candidates[c] / sampleRate
            cosines[c] = cos(w)
            sines[c] = sin(w)
            coeffs[c] = 2.0 * cosines[c]
        }
    }

    /**
     * Feeds interleaved S16LE PCM and returns the current smoothed bands, or
     * null until the first full analysis block has been seen. Voice-coil frames
     * are read from channels 2/3 when present, otherwise channels 0/1.
     */
    fun process(pcm: ByteArray, channels: Int, sampleRate: Int): HdBands? {
        configure(sampleRate)
        if (pcm.isEmpty()) return if (hasOutput) currentBands() else null

        val inputCh = maxOf(channels, 1)
        val bytesPerFrame = inputCh * 2
        val numFrames = pcm.size / bytesPerFrame
        if (numFrames <= 0) return if (hasOutput) currentBands() else null

        val leftCh = if (inputCh >= 4) 2 else 0
        val rightCh = if (inputCh >= 4) 3 else if (inputCh >= 2) 1 else 0

        for (i in 0 until numFrames) {
            val base = i * bytesPerFrame
            leftBuf[fill] = ControllerAudioDsp.readShortLe(pcm, base + leftCh * 2).toShort()
            rightBuf[fill] = ControllerAudioDsp.readShortLe(pcm, base + rightCh * 2).toShort()
            fill++
            if (fill >= blockFrames) {
                analyzeBlock()
                fill = 0
                hasOutput = true
            }
        }
        return if (hasOutput) currentBands() else null
    }

    private fun currentBands(): HdBands = HdBands(
        leftHighFreq = lHighFreq, leftHighAmp = lHighAmp,
        leftLowFreq = lLowFreq, leftLowAmp = lLowAmp,
        rightHighFreq = rHighFreq, rightHighAmp = rHighAmp,
        rightLowFreq = rLowFreq, rightLowAmp = rLowAmp,
    )

    private fun analyzeBlock() {
        val left = analyzeSide(leftBuf)
        val right = analyzeSide(rightBuf)

        lLowFreq = blendFreq(lLowFreq, left.lowFreq, left.lowAmp)
        lLowAmp += (left.lowAmp - lLowAmp) * SMOOTH
        lHighFreq = blendFreq(lHighFreq, left.highFreq, left.highAmp)
        lHighAmp += (left.highAmp - lHighAmp) * SMOOTH

        rLowFreq = blendFreq(rLowFreq, right.lowFreq, right.lowAmp)
        rLowAmp += (right.lowAmp - rLowAmp) * SMOOTH
        rHighFreq = blendFreq(rHighFreq, right.highFreq, right.highAmp)
        rHighAmp += (right.highAmp - rHighAmp) * SMOOTH
    }

    private fun blendFreq(current: Float, measured: Float, measuredAmp: Float): Float =
        if (measuredAmp > FREQ_UPDATE_MIN_AMP) measured else current

    private fun analyzeSide(buf: ShortArray): SideTones {
        val mags = DoubleArray(CANDIDATE_COUNT)
        for (c in 0 until CANDIDATE_COUNT) {
            mags[c] = goertzel(buf, coeffs[c], cosines[c], sines[c])
        }

        // Keep only local maxima so the two lobes of one tone are not mistaken
        // for two bands.
        val localMaxima = ArrayList<Int>(CANDIDATE_COUNT)
        for (c in 0 until CANDIDATE_COUNT) {
            val leftOk = c == 0 || mags[c] >= mags[c - 1]
            val rightOk = c == CANDIDATE_COUNT - 1 || mags[c] >= mags[c + 1]
            if (leftOk && rightOk) localMaxima.add(c)
        }
        localMaxima.sortByDescending { mags[it] }

        var first = -1
        var firstMag = 0.0
        for (c in localMaxima) {
            if (mags[c] > firstMag) {
                firstMag = mags[c]
                first = c
            }
        }

        // A second band must be clearly separated in frequency and comparable in
        // level, otherwise it is leakage or a sidelobe.
        var second = -1
        var secondMag = 0.0
        if (first >= 0) {
            for (c in localMaxima) {
                if (c == first) continue
                val ratio = candidates[c] / candidates[first]
                if (ratio in (1.0 / MIN_BAND_RATIO)..MIN_BAND_RATIO) continue
                if (mags[c] < firstMag * MIN_SECOND_LEVEL) continue
                if (mags[c] > secondMag) {
                    secondMag = mags[c]
                    second = c
                }
            }
        }

        val peaks = ArrayList<Pair<Double, Double>>(2)
        if (first >= 0) peaks.add(candidates[first] to firstMag)
        if (second >= 0) peaks.add(candidates[second] to secondMag)
        peaks.sortBy { it.first }

        val result = SideTones(DEFAULT_LOW_FREQ, 0f, DEFAULT_HIGH_FREQ, 0f)
        if (peaks.size >= 2) {
            result.lowFreq = peaks[0].first.toFloat()
            result.lowAmp = magToAmp(peaks[0].second)
            result.highFreq = peaks[1].first.toFloat()
            result.highAmp = magToAmp(peaks[1].second)
        } else if (peaks.size == 1) {
            result.lowFreq = peaks[0].first.toFloat()
            result.lowAmp = magToAmp(peaks[0].second)
        }
        return result
    }

    private fun magToAmp(mag: Double): Float {
        val amp = 2.0 * mag / (blockFrames * 32767.0)
        val f = amp.toFloat()
        return if (f < NOISE_FLOOR) 0f else min(1f, f)
    }

    private fun goertzel(buf: ShortArray, coeff: Double, cosw: Double, sinw: Double): Double {
        var s1 = 0.0
        var s2 = 0.0
        for (i in buf.indices) {
            val s0 = buf[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val real = s1 - s2 * cosw
        val imag = s2 * sinw
        return sqrt(real * real + imag * imag)
    }
}
