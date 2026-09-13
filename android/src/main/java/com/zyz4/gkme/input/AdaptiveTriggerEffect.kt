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

        /** 3-bit force code (0..7) scaled so that even code 0 is the minimum, non-zero amplitude. */
        private fun strength3(code: Int): Int = ((code and 0x07) + 1) * 255 / 8

        /** 0..10 level scaled so that even level 0 is the minimum, non-zero amplitude. */
        private fun scaleLevel(level: Int): Int = (level.coerceIn(0, 10) + 1) * 255 / 11

        private fun u(raw: ByteArray, index: Int): Int =
            if (index < raw.size) raw[index].toInt() and 0xFF else 0
    }
}
