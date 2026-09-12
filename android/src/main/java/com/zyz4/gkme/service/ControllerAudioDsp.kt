package com.zyz4.gkme.service

/**
 * Pure PCM helpers for the USB controller audio path.
 *
 * Everything here is free of Android dependencies so the DSP can be exercised
 * by plain JVM unit tests. [AudioPlaybackService] and the USB backend call into
 * this file instead of rolling their own byte math.
 */
object ControllerAudioDsp {

    /** The DualSense USB audio endpoint is fixed at 48 kHz, 4 channels, S16LE. */
    const val USB_PCM_RATE = 48000

    /** DS4 uses a 32 kHz USB audio endpoint. */
    const val DS4_USB_PCM_RATE = 32000

    /** Native USB PCM frame limit: 490 frames * 8 bytes = 3920 bytes. */
    const val USB_MAX_FRAMES = 490

    /**
     * Frames per native USB URB. The endpoint services one isochronous packet
     * per millisecond, so 48 kHz audio must arrive as 48 frames per packet. 480
     * frames (10 ms) is the largest multiple of 48 the 490-frame limit allows,
     * which makes [UsbFrameChunker] emit URBs the native sender splits into
     * exactly 48-frame packets. 490-frame URBs instead produce 49-frame packets
     * (49 kHz effective) and a mispaced remainder, which glitches the audio.
     */
    const val USB_CHUNK_FRAMES = 480

    /** Interleaved S16LE frame size the controller expects: 4 channels * 2 bytes. */
    const val BYTES_PER_USB_FRAME = 8

    fun readShortLe(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= bytes.size) return 0
        val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return raw.toShort().toInt()
    }

    fun writeShortLe(bytes: ByteArray, offset: Int, value: Int) {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[offset] = v.toByte()
        bytes[offset + 1] = (v shr 8).toByte()
    }

    /**
     * Re-packs an incoming interleaved frame into the DualSense USB audio layout
     * (ch0/ch1 = speaker, ch2/ch3 = voice coil) and returns exactly
     * [sampleCount] * [BYTES_PER_USB_FRAME] bytes.
     *
     * [inputCh] is the channel count of [pcm]; [controllerCh], [leftVcmCh] and
     * [rightVcmCh] index into it.
     */
    fun buildUsbFrame(
        pcm: ByteArray,
        inputCh: Int,
        startSample: Int,
        sampleCount: Int,
        controllerCh: Int,
        leftVcmCh: Int,
        rightVcmCh: Int,
        includeControllerAudio: Boolean,
        includeVoiceCoil: Boolean,
        swap: Boolean,
    ): ByteArray {
        val out = ByteArray(sampleCount * BYTES_PER_USB_FRAME)
        for (i in 0 until sampleCount) {
            val s = startSample + i
            val inBase = s * inputCh * 2
            val outOff = i * BYTES_PER_USB_FRAME

            if (includeControllerAudio) {
                val off = inBase + controllerCh * 2
                val v = readShortLe(pcm, off)
                writeShortLe(out, outOff, v)
                writeShortLe(out, outOff + 2, v)
            }

            if (includeVoiceCoil) {
                val offL = inBase + leftVcmCh * 2
                val offR = inBase + rightVcmCh * 2
                val l = readShortLe(pcm, offL)
                val r = readShortLe(pcm, offR)
                writeShortLe(out, outOff + 4, if (swap) r else l)
                writeShortLe(out, outOff + 6, if (swap) l else r)
            }
        }
        return out
    }
}

/**
 * Streaming linear-interpolation resampler for interleaved S16LE PCM.
 *
 * Unlike a stateless per-buffer conversion, the fractional read position and the
 * previous frame are carried across [process] calls, so consecutive buffers stay
 * sample-continuous. A fresh conversion every buffer would step the phase at
 * every buffer boundary, which shows up as a periodic buzz on top of the tone.
 */
class PcmResampler(private var channels: Int = 4) {

    private var fromRate = 0
    private var toRate = 0
    private var pos = 0.0
    private var prevFrame = ShortArray(channels)

    fun configure(fromRate: Int, toRate: Int, channels: Int) {
        if (fromRate <= 0 || toRate <= 0 || channels <= 0) return
        if (this.fromRate == fromRate && this.toRate == toRate && this.channels == channels) return
        this.fromRate = fromRate
        this.toRate = toRate
        this.channels = channels
        prevFrame = ShortArray(channels)
        pos = 0.0
    }

    fun reset() {
        pos = 0.0
        prevFrame = ShortArray(channels)
    }

    fun process(input: ByteArray): ByteArray {
        if (channels <= 0 || fromRate <= 0 || toRate <= 0) return ByteArray(0)
        val frameBytes = channels * 2
        val inFrames = input.size / frameBytes
        if (inFrames <= 0) return ByteArray(0)

        // Decode to shorts once so the interpolation loop is cheap.
        val src = ShortArray(inFrames * channels)
        for (i in 0 until inFrames * channels) {
            src[i] = ControllerAudioDsp.readShortLe(input, i * 2).toShort()
        }

        val lastOff = (inFrames - 1) * channels
        if (fromRate == toRate) {
            val out = ByteArray(inFrames * frameBytes)
            System.arraycopy(input, 0, out, 0, out.size)
            carry(src, lastOff)
            return out
        }

        val step = fromRate.toDouble() / toRate.toDouble()
        val capacity = ((inFrames * toRate).toLong() / fromRate + 2).toInt()
        val out = ByteArray(capacity * frameBytes)
        var outFrames = 0
        var p = pos

        // Interpolate only while the upper source sample is already in this block.
        while (p < inFrames - 1) {
            val a = Math.floor(p).toInt()
            val frac = (p - a).toFloat()
            val b = a + 1
            val outBase = outFrames * frameBytes
            for (ch in 0 until channels) {
                val sa = if (a < 0) prevFrame[ch] else src[a * channels + ch]
                val sb = src[b * channels + ch]
                val mixed = (sa + frac * (sb - sa)).toInt()
                ControllerAudioDsp.writeShortLe(out, outBase + ch * 2, mixed)
            }
            outFrames++
            p += step
        }

        pos = p - inFrames
        carry(src, lastOff)

        val trimmed = ByteArray(outFrames * frameBytes)
        System.arraycopy(out, 0, trimmed, 0, trimmed.size)
        return trimmed
    }

    private fun carry(src: ShortArray, lastOff: Int) {
        for (ch in 0 until channels) prevFrame[ch] = src[lastOff + ch]
    }
}

/**
 * Generates a continuous sine test tone as interleaved S16LE frames. Used by the
 * voice-coil test button; kept pure so the exact waveform can be asserted.
 */
class PcmToneGenerator(
    private val sampleRate: Int,
    private val frequencyHz: Double,
    private val amplitude: Double,
    private val channels: Int,
    private val activeChannels: IntArray,
) {
    private var phase = 0.0
    private val step = 2.0 * Math.PI * frequencyHz / sampleRate

    fun nextFrame(frameCount: Int): ByteArray {
        val out = ByteArray(frameCount * channels * 2)
        for (i in 0 until frameCount) {
            val v = Math.round(Math.sin(phase) * amplitude).toInt()
            phase += step
            if (phase >= 2.0 * Math.PI) phase -= 2.0 * Math.PI
            val base = i * channels * 2
            for (ch in activeChannels) {
                ControllerAudioDsp.writeShortLe(out, base + ch * 2, v)
            }
        }
        return out
    }
}

/**
 * Accumulates interleaved S16LE PCM and emits only whole
 * [framesPerChunk]-frame chunks, carrying the remainder into the next submit.
 *
 * The native USB sender turns each submitted frame into one isochronous URB,
 * and it sizes the packets from the URB's frame count. Feeding it arbitrary
 * frame counts (e.g. 490 then 22) makes the per-packet sample count wander, so
 * the effective sample rate of the stream wobbles and the controller underruns
 * or overruns. Emitting a constant 480-frame (10 ms) chunk keeps every packet
 * at exactly 48 frames.
 */
class UsbFrameChunker(
    private val framesPerChunk: Int = ControllerAudioDsp.USB_CHUNK_FRAMES,
    private val channels: Int = 4,
) {
    private val frameBytes = channels * 2
    private var pending = ByteArray(0)

    fun submit(pcm: ByteArray): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        val combined = if (pending.isEmpty()) {
            pcm
        } else {
            ByteArray(pending.size + pcm.size).also {
                System.arraycopy(pending, 0, it, 0, pending.size)
                System.arraycopy(pcm, 0, it, pending.size, pcm.size)
            }
        }

        val totalFrames = combined.size / frameBytes
        val emitFrames = totalFrames / framesPerChunk * framesPerChunk
        val chunks = ArrayList<ByteArray>(emitFrames / framesPerChunk)
        var offset = 0
        while (offset < emitFrames * frameBytes) {
            chunks.add(combined.copyOfRange(offset, offset + framesPerChunk * frameBytes))
            offset += framesPerChunk * frameBytes
        }
        pending = combined.copyOfRange(offset, combined.size)
        return chunks
    }

    fun reset() {
        pending = ByteArray(0)
    }
}
