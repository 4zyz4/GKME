package com.zyz4.gkme.haptic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [RichTapRawCodec] 的 raw int[] 编码测试（对照逆向出的 SDK `base.b.a`）。 */
class RichTapRawCodecTest {

    private fun point(t: Int, scale: Double, off: Int) = RichTapRawCodec.CurvePoint(t, scale, off)

    private fun continuous(
        rel: Int = 0,
        dur: Int = 800,
        intensity: Int = 100,
        freq: Int = 56,
        curve: List<RichTapRawCodec.CurvePoint> = listOf(point(0, 0.0, 0), point(120, 1.0, 0), point(600, 1.0, 0), point(800, 0.0, 0)),
    ) = RichTapRawCodec.HeEvent(
        type = RichTapRawCodec.TYPE_CONTINUOUS,
        relativeTimeMs = rel,
        durationMs = dur,
        vibrationId = 0,
        params = RichTapRawCodec.EventParams(intensity, freq, curve),
    )

    @Test
    fun he10_modernCore_usesHeader3AndPadsTo16Points() {
        val raw = RichTapRawCodec.encode(
            events = listOf(continuous()),
            heVersion = 1, coreMajorRichTap = 30, minorRichTap = 0,
            pid = 1, sid = 2, swapVibrationIndex = false,
        )!!
        // 头 + [tag, rel, intensity, freq, dur, 0, pointCount, 12 curve, (16-4)*3 zeros]
        assertEquals(56, raw.size)
        assertEquals(3, raw[0])
        assertEquals(RichTapRawCodec.TAG_CONTINUOUS, raw[1])
        assertEquals(0, raw[2])
        assertEquals(100, raw[3])
        assertEquals(56, raw[4])
        assertEquals(800, raw[5])
        assertEquals(0, raw[6])
        assertEquals(4, raw[7])
        assertArrayEquals(intArrayOf(0, 0, 0), raw.copyOfRange(8, 11))
        assertArrayEquals(intArrayOf(120, 100, 0), raw.copyOfRange(11, 14))
        assertArrayEquals(intArrayOf(600, 100, 0), raw.copyOfRange(14, 17))
        assertArrayEquals(intArrayOf(800, 0, 0), raw.copyOfRange(17, 20))
        // 尾部补零到 16 点
        for (i in 20 until raw.size) assertEquals(0, raw[i])
    }

    @Test
    fun he10_legacyCore_usesHeader1AndInlineCurve() {
        val raw = RichTapRawCodec.encode(
            events = listOf(continuous()),
            heVersion = 1, coreMajorRichTap = 20, minorRichTap = 0,
            pid = 1, sid = 2, swapVibrationIndex = false,
        )!!
        assertEquals(18, raw.size)
        assertEquals(1, raw[0])
        assertEquals(RichTapRawCodec.TAG_CONTINUOUS, raw[1])
        assertArrayEquals(intArrayOf(120, 100, 0), raw.copyOfRange(9, 12))
    }

    @Test
    fun he10_legacyCore_transientPads12Zeros() {
        val transient = RichTapRawCodec.HeEvent(
            type = RichTapRawCodec.TYPE_TRANSIENT,
            relativeTimeMs = 0,
            durationMs = 40,
            vibrationId = 0,
            params = RichTapRawCodec.EventParams(100, 56, emptyList()),
        )
        val raw = RichTapRawCodec.encode(
            events = listOf(transient),
            heVersion = 1, coreMajorRichTap = 20, minorRichTap = 0,
            pid = 1, sid = 2, swapVibrationIndex = false,
        )!!
        assertEquals(18, raw.size)
        assertEquals(1, raw[0])
        assertEquals(RichTapRawCodec.TAG_TRANSIENT, raw[1])
        assertEquals(40, raw[5])
        for (i in 6 until raw.size) assertEquals(0, raw[i])
    }

    @Test
    fun he20_wrapsSinglePatternWithSenderId() {
        val raw = RichTapRawCodec.encode(
            events = listOf(continuous()),
            heVersion = 2, coreMajorRichTap = 30, minorRichTap = 0,
            pid = 777, sid = 65537, swapVibrationIndex = false,
        )!!
        assertEquals(28, raw.size)
        assertArrayEquals(intArrayOf(2, 2, 777, 65537), raw.copyOfRange(0, 4))
        // patternCount = 1 → (1) | (1<<16)
        assertEquals(65537, raw[4])
        assertEquals(0, raw[5]) // pattern index
        assertEquals(0, raw[6]) // absoluteTime
        assertEquals(1, raw[7]) // eventCount
        assertEquals(RichTapRawCodec.TAG_CONTINUOUS, raw[8])
        assertEquals(4 * 3 + 6, raw[9])
        assertEquals(0, raw[10]) // vibrationId
        assertEquals(56, raw[13])
        assertEquals(4, raw[15])
        assertArrayEquals(intArrayOf(120, 100, 0), raw.copyOfRange(19, 22))
    }

    @Test
    fun defaultFrequencyMinusOne_becomes56_andCurveOffsetZero() {
        val event = continuous(freq = -1, curve = listOf(point(0, 1.0, 7), point(100, 1.0, -3), point(200, 1.0, 9), point(300, 0.0, 0)))
        val raw = RichTapRawCodec.encode(
            events = listOf(event),
            heVersion = 1, coreMajorRichTap = 30, minorRichTap = 0,
            pid = 1, sid = 2, swapVibrationIndex = false,
        )!!
        assertEquals(56, raw[4])
        // freq == -1 时曲线频率偏移被强制为 0
        assertArrayEquals(intArrayOf(0, 100, 0), raw.copyOfRange(8, 11))
    }

    @Test
    fun he20_requiresCoreAtLeast24() {
        val raw = RichTapRawCodec.encode(
            events = listOf(continuous()),
            heVersion = 2, coreMajorRichTap = 20, minorRichTap = 0,
            pid = 1, sid = 2, swapVibrationIndex = false,
        )
        assertNull(raw)
    }

    @Test
    fun swapVibrationIndex_swapsContinuousKinds() {
        val event = continuous().copy(vibrationId = 1)
        val raw = RichTapRawCodec.encode(
            events = listOf(event),
            heVersion = 2, coreMajorRichTap = 30, minorRichTap = 0,
            pid = 1, sid = 2, swapVibrationIndex = true,
        )!!
        // 交换后 1 → 2
        assertEquals(2, raw[10])
    }
}
