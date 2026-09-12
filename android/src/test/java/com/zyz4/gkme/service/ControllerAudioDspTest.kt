package com.zyz4.gkme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * JVM tests for the pure controller-audio DSP. These cover the byte-exact parts
 * of the pipeline that cannot be exercised on a device without a DualSense:
 * USB frame packing, streaming resampling, and the voice-coil test tone.
 */
class ControllerAudioDspTest {

    // ── USB frame packing ────────────────────────────────────────────────

    @Test
    fun buildUsbFrame_mapsSpeakerToChannels0And1_andVoiceCoilToChannels2And3() {
        // One frame of 4-channel input: ch0..ch3 = 100, 200, 300, 400.
        val input = ByteArray(8)
        ControllerAudioDsp.writeShortLe(input, 0, 100)
        ControllerAudioDsp.writeShortLe(input, 2, 200)
        ControllerAudioDsp.writeShortLe(input, 4, 300)
        ControllerAudioDsp.writeShortLe(input, 6, 400)

        val frame = ControllerAudioDsp.buildUsbFrame(
            pcm = input, inputCh = 4, startSample = 0, sampleCount = 1,
            controllerCh = 1, leftVcmCh = 2, rightVcmCh = 3,
            includeControllerAudio = true, includeVoiceCoil = true, swap = false,
        )

        assertEquals(ControllerAudioDsp.BYTES_PER_USB_FRAME, frame.size)
        // Speaker (source ch1 = 200) is duplicated onto USB ch0 and ch1.
        assertEquals(200, ControllerAudioDsp.readShortLe(frame, 0))
        assertEquals(200, ControllerAudioDsp.readShortLe(frame, 2))
        // Voice coil: USB ch2 = source ch2 (300), USB ch3 = source ch3 (400).
        assertEquals(300, ControllerAudioDsp.readShortLe(frame, 4))
        assertEquals(400, ControllerAudioDsp.readShortLe(frame, 6))
    }

    @Test
    fun buildUsbFrame_swapExchangesVoiceCoilChannels() {
        val input = ByteArray(8)
        ControllerAudioDsp.writeShortLe(input, 4, 300)
        ControllerAudioDsp.writeShortLe(input, 6, 400)

        val frame = ControllerAudioDsp.buildUsbFrame(
            pcm = input, inputCh = 4, startSample = 0, sampleCount = 1,
            controllerCh = 1, leftVcmCh = 2, rightVcmCh = 3,
            includeControllerAudio = false, includeVoiceCoil = true, swap = true,
        )

        assertEquals(400, ControllerAudioDsp.readShortLe(frame, 4))
        assertEquals(300, ControllerAudioDsp.readShortLe(frame, 6))
    }

    @Test
    fun buildUsbFrame_voiceCoilOnlyLeavesSpeakerSilent() {
        val input = ByteArray(8)
        ControllerAudioDsp.writeShortLe(input, 2, 1234)
        ControllerAudioDsp.writeShortLe(input, 4, 300)
        ControllerAudioDsp.writeShortLe(input, 6, 400)

        val frame = ControllerAudioDsp.buildUsbFrame(
            pcm = input, inputCh = 4, startSample = 0, sampleCount = 1,
            controllerCh = 1, leftVcmCh = 2, rightVcmCh = 3,
            includeControllerAudio = false, includeVoiceCoil = true, swap = false,
        )

        assertEquals(0, ControllerAudioDsp.readShortLe(frame, 0))
        assertEquals(0, ControllerAudioDsp.readShortLe(frame, 2))
        assertEquals(300, ControllerAudioDsp.readShortLe(frame, 4))
        assertEquals(400, ControllerAudioDsp.readShortLe(frame, 6))
    }

    // ── Streaming resampler ──────────────────────────────────────────────

    @Test
    fun resampler_preservesFrequencyAndSampleCount() {
        val fromRate = 44100
        val toRate = 48000
        val freq = 1000.0
        val blockFrames = 512

        val resampler = PcmResampler(4).apply { configure(fromRate, toRate, 4) }
        val output = ArrayList<Short>()

        var produced = 0
        while (produced < fromRate) {
            val block = sineBlock(produced, blockFrames, fromRate, freq)
            val resampled = resampler.process(block)
            for (i in resampled.indices step 8) {
                output.add(ControllerAudioDsp.readShortLe(resampled, i).toShort())
            }
            produced += blockFrames
        }

        // ~1 second in, ~1 second out (within one block of slack).
        assertTrue(
            "resampled sample count ${output.size} not within a block of $toRate",
            abs(output.size - toRate) <= blockFrames * 2,
        )
        val estimated = estimateFrequency(output, toRate)
        assertEquals("resampled frequency", freq, estimated, 25.0)
    }

    @Test
    fun resampler_isContinuousAcrossBufferBoundaries() {
        val fromRate = 44100
        val toRate = 48000
        val freq = 1000.0
        val blockFrames = 512

        val resampler = PcmResampler(4).apply { configure(fromRate, toRate, 4) }
        val output = ArrayList<Short>()
        var produced = 0
        repeat(20) {
            val resampled = resampler.process(sineBlock(produced, blockFrames, fromRate, freq))
            for (i in resampled.indices step 2) {
                output.add(ControllerAudioDsp.readShortLe(resampled, i).toShort())
            }
            produced += blockFrames
        }

        // A continuous 1 kHz sine at 48 kHz advances at most ~0.14 * amplitude
        // per sample. A phase reset at a buffer edge would blow past that.
        val maxStep = maxAbsDelta(output)
        val oneSampleStep = 2.0 * Math.PI * freq / toRate * 32767.0
        assertTrue(
            "max step $maxStep exceeds a continuous sine's $oneSampleStep",
            maxStep <= oneSampleStep * 1.5,
        )
    }

    // ── Voice-coil test tone ─────────────────────────────────────────────

    @Test
    fun toneGenerator_producesSineOnVoiceCoilChannelsOnly() {
        val generator = PcmToneGenerator(
            sampleRate = ControllerAudioDsp.USB_PCM_RATE,
            frequencyHz = 220.0,
            amplitude = 20000.0,
            channels = 4,
            activeChannels = intArrayOf(2, 3),
        )

        val samples = ArrayList<Short>()
        repeat(100) {
            val frame = generator.nextFrame(48)
            for (i in frame.indices step 8) {
                // ch0/ch1 (speaker) stay silent; ch2/ch3 (voice coil) carry the tone.
                assertEquals(0, ControllerAudioDsp.readShortLe(frame, i))
                assertEquals(0, ControllerAudioDsp.readShortLe(frame, i + 2))
                assertEquals(
                    ControllerAudioDsp.readShortLe(frame, i + 4),
                    ControllerAudioDsp.readShortLe(frame, i + 6),
                )
                samples.add(ControllerAudioDsp.readShortLe(frame, i + 4).toShort())
            }
        }

        val estimated = estimateFrequency(samples, ControllerAudioDsp.USB_PCM_RATE)
        assertEquals("voice-coil tone frequency", 220.0, estimated, 5.0)

        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue("tone peak $peak should be close to 20000", peak in 19500..20500)
    }

    @Test
    fun toneGenerator_isPhaseContinuousAcrossFrames() {
        val generator = PcmToneGenerator(
            sampleRate = ControllerAudioDsp.USB_PCM_RATE,
            frequencyHz = 220.0,
            amplitude = 20000.0,
            channels = 4,
            activeChannels = intArrayOf(2, 3),
        )

        val first = generator.nextFrame(48)
        val second = generator.nextFrame(48)
        val last = ControllerAudioDsp.readShortLe(first, (48 - 1) * 8 + 4)
        val next = ControllerAudioDsp.readShortLe(second, 4)
        val oneSampleStep = 2.0 * Math.PI * 220.0 / ControllerAudioDsp.USB_PCM_RATE * 20000.0
        assertTrue(
            "frame boundary jump ${abs(next - last)} exceeds a sample step $oneSampleStep",
            abs(next - last) <= oneSampleStep * 1.5,
        )
    }

    // ── End-to-end: the test tone must survive frame packing ─────────────

    @Test
    fun voiceCoilTestTone_survivesUsbFramePacking() {
        val generator = PcmToneGenerator(
            sampleRate = ControllerAudioDsp.USB_PCM_RATE,
            frequencyHz = 220.0,
            amplitude = 20000.0,
            channels = 4,
            activeChannels = intArrayOf(2, 3),
        )
        val tone = generator.nextFrame(490)

        val frame = ControllerAudioDsp.buildUsbFrame(
            pcm = tone, inputCh = 4, startSample = 0, sampleCount = 490,
            controllerCh = 1, leftVcmCh = 2, rightVcmCh = 3,
            includeControllerAudio = true, includeVoiceCoil = true, swap = false,
        )

        assertEquals(490 * ControllerAudioDsp.BYTES_PER_USB_FRAME, frame.size)
        val vcm = ArrayList<Short>()
        for (i in 0 until 490) {
            val off = i * 8
            assertEquals("speaker must stay silent", 0, ControllerAudioDsp.readShortLe(frame, off))
            assertEquals("speaker must stay silent", 0, ControllerAudioDsp.readShortLe(frame, off + 2))
            vcm.add(ControllerAudioDsp.readShortLe(frame, off + 4).toShort())
        }
        assertEquals(220.0, estimateFrequency(vcm, ControllerAudioDsp.USB_PCM_RATE), 5.0)
    }

    // ── URB chunker ──────────────────────────────────────────────────────

    @Test
    fun chunker_emitsOnlyWhole480FrameChunks_andCarriesRemainder() {
        val chunker = UsbFrameChunker()

        // 512 frames in: one 480-frame chunk out, 32 frames carried.
        val first = chunker.submit(frameBuffer(512))
        assertEquals(1, first.size)
        assertEquals(480 * ControllerAudioDsp.BYTES_PER_USB_FRAME, first[0].size)

        // 512 more frames: 32 + 512 = 544 -> one 480 chunk, 64 carried.
        val second = chunker.submit(frameBuffer(512))
        assertEquals(1, second.size)
        assertEquals(480 * ControllerAudioDsp.BYTES_PER_USB_FRAME, second[0].size)

        // 512 more: 64 + 512 = 576 -> one 480 chunk, 96 carried.
        val third = chunker.submit(frameBuffer(512))
        assertEquals(1, third.size)
    }

    @Test
    fun chunker_accumulatesUntilAFullChunkIsAvailable() {
        val chunker = UsbFrameChunker()
        // 100 frames at a time: the first four calls (400 frames) emit nothing.
        repeat(4) { assertEquals(0, chunker.submit(frameBuffer(100)).size) }
        // The fifth call reaches 500 frames -> one 480 chunk, 20 carried.
        val chunks = chunker.submit(frameBuffer(100))
        assertEquals(1, chunks.size)
        assertEquals(480 * ControllerAudioDsp.BYTES_PER_USB_FRAME, chunks[0].size)
    }

    @Test
    fun chunker_preservesPcmContentAcrossChunks() {
        val chunker = UsbFrameChunker()
        val source = ByteArray(1000 * 8) { (it % 251).toByte() }

        val chunks = chunker.submit(source)
        assertEquals(2, chunks.size) // 960 frames emitted, 40 carried

        // Concatenating the emitted chunks must reproduce the source prefix exactly.
        val joined = ByteArray(chunks.sumOf { it.size })
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, joined, off, c.size)
            off += c.size
        }
        for (i in joined.indices) assertEquals(source[i], joined[i])
    }

    @Test
    fun voiceCoilTestTone_chunkedThroughUsbPathStaysOnVoiceCoil() {
        val generator = PcmToneGenerator(
            sampleRate = ControllerAudioDsp.USB_PCM_RATE,
            frequencyHz = 220.0,
            amplitude = 20000.0,
            channels = 4,
            activeChannels = intArrayOf(2, 3),
        )
        val chunker = UsbFrameChunker()

        // Feed 512-frame blocks; the chunker must always emit whole 480-frame URBs.
        val vcm = ArrayList<Short>()
        repeat(3) {
            val chunks = chunker.submit(generator.nextFrame(512))
            for (chunk in chunks) {
                assertEquals(480 * ControllerAudioDsp.BYTES_PER_USB_FRAME, chunk.size)
                val frame = ControllerAudioDsp.buildUsbFrame(
                    chunk, 4, 0, 480, 1, 2, 3,
                    includeControllerAudio = true, includeVoiceCoil = true, swap = false,
                )
                for (i in 0 until 480) {
                    val off = i * 8
                    assertEquals(0, ControllerAudioDsp.readShortLe(frame, off))
                    assertEquals(0, ControllerAudioDsp.readShortLe(frame, off + 2))
                    vcm.add(ControllerAudioDsp.readShortLe(frame, off + 4).toShort())
                }
            }
        }
        assertEquals(220.0, estimateFrequency(vcm, ControllerAudioDsp.USB_PCM_RATE), 5.0)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun frameBuffer(frameCount: Int): ByteArray = ByteArray(frameCount * 8)

    private fun sineBlock(startFrame: Int, frameCount: Int, sampleRate: Int, freq: Double): ByteArray {
        val out = ByteArray(frameCount * 8)
        for (i in 0 until frameCount) {
            val t = (startFrame + i).toDouble() / sampleRate
            val v = Math.round(Math.sin(2.0 * Math.PI * freq * t) * 20000.0).toInt()
            ControllerAudioDsp.writeShortLe(out, i * 8, v)
            ControllerAudioDsp.writeShortLe(out, i * 8 + 2, v)
            ControllerAudioDsp.writeShortLe(out, i * 8 + 4, v)
            ControllerAudioDsp.writeShortLe(out, i * 8 + 6, v)
        }
        return out
    }

    private fun maxAbsDelta(samples: List<Short>): Int {
        var max = 0
        for (i in 1 until samples.size) {
            val d = abs(samples[i] - samples[i - 1])
            if (d > max) max = d
        }
        return max
    }

    private fun estimateFrequency(samples: List<Short>, sampleRate: Int): Double {
        var first = -1
        var last = -1
        var crossings = 0
        for (i in 1 until samples.size) {
            if (samples[i - 1] < 0 && samples[i] >= 0) {
                if (first < 0) first = i
                last = i
                crossings++
            }
        }
        if (crossings < 2 || last <= first) return 0.0
        return (crossings - 1).toDouble() * sampleRate / (last - first)
    }
}
