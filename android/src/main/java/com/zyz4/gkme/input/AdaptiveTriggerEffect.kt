package com.zyz4.gkme.input

/**
 * Parsed PC adaptive-trigger effect.
 *
 * The PC sends an 11-byte packet per trigger:
 *  - byte0: effect type
 *  - byte1..2: positions / active-zone mask
 *  - byte3..6: force / amplitude parameters
 *  - byte7..8: extra parameters (period/frequency)
 *  - byte9: vibration frequency
 *  - byte10: reserved
 *
 * Motor targets cannot reproduce trigger resistance, so the effect is reduced to a
 * position -> amplitude curve: [amplitudeFor] returns the vibration intensity (0..255)
 * to play for a given trigger position (0..255).
 */
class AdaptiveTriggerEffect private constructor(
    val type: Int,
    val active: Boolean,
    private val positionStart: Int,
    private val positionEnd: Int,
    private val strength: Int,
    private val zoneAmplitudes: IntArray?,
    private val mode: Mode,
) {
    private enum class Mode { ZONE, RANGE, FROM_POSITION }

    fun amplitudeFor(position: Int): Int {
        if (!active) return 0
        val p = position.coerceIn(0, 255)
        // PC position/zone values are 1-based: effect position 0 means physical position 1
        // (minimum press), not released. A released trigger therefore produces no output.
        if (p <= 0) return 0
        return when (mode) {
            Mode.ZONE -> {
                val zones = zoneAmplitudes ?: return 0
                zones[((p - 1) * 10 / 255).coerceIn(0, 9)]
            }
            Mode.FROM_POSITION -> if (p >= maxOf(1, positionStart)) strength else 0
            Mode.RANGE -> {
                val lo = maxOf(1, minOf(positionStart, positionEnd))
                val hi = maxOf(lo, maxOf(positionStart, positionEnd))
                if (p in lo..hi) strength else 0
            }
        }
    }

    companion object {
        private const val SIMPLE_FEEDBACK = 0x01
        private const val SIMPLE_WEAPON = 0x02
        private const val OFF = 0x05
        private const val SIMPLE_VIBRATION = 0x06
        private const val LIMITED_FEEDBACK = 0x11
        private const val LIMITED_WEAPON = 0x12
        private const val FEEDBACK = 0x21
        private const val BOW = 0x22
        private const val GALLOPING = 0x23
        private const val WEAPON = 0x25
        private const val VIBRATION = 0x26
        private const val MACHINE = 0x27

        /** Parses a raw effect packet. Returns an inactive effect for [null]/empty/unknown types. */
        fun parse(raw: ByteArray?): AdaptiveTriggerEffect {
            if (raw == null || raw.isEmpty()) return inactive()
            val type = u(raw, 0)
            return when (type) {
                OFF, 0x00, 0xFC, 0xFD, 0xFE -> inactive()
                FEEDBACK, VIBRATION -> zoneEffect(type, raw)
                WEAPON -> rangeFromMask(type, raw, strength3(u(raw, 3)))
                BOW, GALLOPING, MACHINE ->
                    rangeFromMask(type, raw, strength3(maxOf(u(raw, 3) and 0x07, (u(raw, 3) shr 3) and 0x07)))
                SIMPLE_FEEDBACK -> fromPosition(type, u(raw, 1), u(raw, 3))
                SIMPLE_VIBRATION -> fromPosition(type, u(raw, 7), u(raw, 3))
                LIMITED_FEEDBACK -> fromPosition(type, u(raw, 1), scaleLevel(u(raw, 3)))
                SIMPLE_WEAPON -> range(type, u(raw, 1), u(raw, 3), u(raw, 7))
                LIMITED_WEAPON -> range(type, u(raw, 1), u(raw, 3), scaleLevel(u(raw, 7)))
                else -> inactive()
            }
        }

        private fun inactive() = AdaptiveTriggerEffect(
            type = 0, active = false, positionStart = 0, positionEnd = 0,
            strength = 0, zoneAmplitudes = null, mode = Mode.RANGE,
        )

        private fun zoneEffect(type: Int, raw: ByteArray): AdaptiveTriggerEffect {
            val mask = u(raw, 1) or (u(raw, 2) shl 8)
            val packed = u(raw, 3) or (u(raw, 4) shl 8) or (u(raw, 5) shl 16) or (u(raw, 6) shl 24)
            val zones = IntArray(10)
            for (i in 0 until 10) {
                if ((mask shr i) and 1 != 0) {
                    zones[i] = strength3((packed shr (3 * i)) and 0x07)
                }
            }
            return AdaptiveTriggerEffect(type, mask != 0, 0, 255, 0, zones, Mode.ZONE)
        }

        private fun rangeFromMask(type: Int, raw: ByteArray, strength: Int): AdaptiveTriggerEffect {
            val mask = u(raw, 1) or (u(raw, 2) shl 8)
            var lowZone = -1
            var highZone = -1
            for (i in 0 until 10) {
                if ((mask shr i) and 1 != 0) {
                    if (lowZone < 0) lowZone = i
                    highZone = i
                }
            }
            if (lowZone < 0) return inactive()
            return range(type, lowZone * 256 / 10, (highZone + 1) * 256 / 10 - 1, strength)
        }

        private fun range(type: Int, start: Int, end: Int, strength: Int): AdaptiveTriggerEffect {
            val s = start.coerceIn(0, 255)
            val e = end.coerceIn(0, 255)
            return AdaptiveTriggerEffect(type, true, s, e, strength, null, Mode.RANGE)
        }

        private fun fromPosition(type: Int, position: Int, strength: Int): AdaptiveTriggerEffect {
            val p = position.coerceIn(0, 255)
            return AdaptiveTriggerEffect(type, true, p, 255, strength, null, Mode.FROM_POSITION)
        }

        /** Trigger-rumble buzz frequency (Hz). A non-zero frequency is mandatory: the
         *  controller ignores a 0x26 Vibration effect whose frequency byte is 0. */
        private const val VIBRATION_FREQUENCY_HZ = 40

        /**
         * Builds a raw 11-byte DualSense "Vibration" (0x26) effect that vibrates across the
         * whole trigger travel at an amplitude derived from [amplitude] (0..255). It is the
         * closest native equivalent of an Xbox trigger-rumble amplitude: position-independent
         * and unconditional. [amplitude] 0 produces an OFF packet (all-zero bytes).
         *
         * Wire layout (matching the reference TriggerEffectGenerator::Vibration):
         * [0]=type 0x26, [1..2]=active-zone mask, [3..6]=3-bit amplitude per zone,
         * [9]=frequency. Without [9] the controller renders no vibration.
         */
        fun vibrationPacket(
            amplitude: Int,
            frequency: Int = VIBRATION_FREQUENCY_HZ,
        ): ByteArray {
            val raw = ByteArray(11)
            val amp = amplitude.coerceIn(0, 255)
            if (amp == 0) return raw
            // DualSense maps amplitude 1..8 onto the 3-bit per-zone value 0..7
            // (inverse of [strength3]): code = round(amp * 8 / 255) - 1, clamped to 0..7.
            val code = ((amp * 8 + 127) / 255 - 1).coerceIn(0, 0x07)
            raw[0] = VIBRATION.toByte()
            raw[1] = 0xFF.toByte() // zones 0..7 active
            raw[2] = 0x03          // zones 8..9 active
            var packed = 0
            for (i in 0 until 10) packed = packed or (code shl (3 * i))
            raw[3] = (packed and 0xFF).toByte()
            raw[4] = ((packed ushr 8) and 0xFF).toByte()
            raw[5] = ((packed ushr 16) and 0xFF).toByte()
            raw[6] = ((packed ushr 24) and 0xFF).toByte()
            raw[9] = frequency.coerceIn(1, 255).toByte()
            return raw
        }

        /** 3-bit force code (0..7) scaled so that even code 0 is the minimum, non-zero amplitude. */
        private fun strength3(code: Int): Int = ((code and 0x07) + 1) * 255 / 8

        /** 0..10 level scaled so that even level 0 is the minimum, non-zero amplitude. */
        private fun scaleLevel(level: Int): Int = (level.coerceIn(0, 10) + 1) * 255 / 11

        private fun u(raw: ByteArray, index: Int): Int =
            if (index < raw.size) raw[index].toInt() and 0xFF else 0
    }
}
