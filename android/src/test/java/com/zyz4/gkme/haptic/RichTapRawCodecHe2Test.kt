package com.zyz4.gkme.haptic

import com.zyz4.gkme.haptic.RichTapRawCodec.CurvePoint
import com.zyz4.gkme.haptic.RichTapRawCodec.EventParams
import com.zyz4.gkme.haptic.RichTapRawCodec.HeEvent
import com.zyz4.gkme.haptic.RichTapRawCodec.HePattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** HE2.0 raw int[] encoding tests (SDK `base.b.a(String, version, core, ...)`). */
class RichTapRawCodecHe2Test {

    private fun point(t: Int, v: Double, f: Int = 0) = CurvePoint(t, v, f)

    private fun continuous(
        rel: Int,
        dur: Int,
        freq: Int,
        index: Int,
        intensity: Int = 100,
    ) = HeEvent(
        type = "continuous",
        relativeTimeMs = rel,
        durationMs = dur,
        vibrationId = index,
        params = EventParams(
            intensity = intensity,
            frequency = freq,
            curve = listOf(
                point(0, 0.0),
                point(dur / 3, 1.0),
                point(dur * 2 / 3, 1.0),
                point(dur, 0.0),
            ),
        ),
    )

    private fun transient(rel: Int, freq: Int, index: Int, intensity: Int = 100) = HeEvent(
        type = "transient",
        relativeTimeMs = rel,
        durationMs = 0,
        vibrationId = index,
        params = EventParams(intensity, freq, emptyList()),
    )

    private fun document(): HeDocument = HeDocument(
        HeMetadata(2, "test", "raw"),
        listOf(
            HePattern(
                absoluteTimeMs = 0,
                events = listOf(
                    continuous(0, 100, 56, index = 1),
                    transient(50, 70, index = 2, intensity = 80),
                ),
            ),
            HePattern(
                absoluteTimeMs = 300,
                events = listOf(continuous(0, 100, -1, index = 0, intensity = 90)),
            ),
        ),
    )

    @Test
    fun nativeHe20RawKeepsPatternsIndexAndTransientDuration() {
        val raw = RichTapRawCodec.encode(
            document = document(),
            coreMajorRichTap = 30,
            minorRichTap = 0,
            pid = 777,
            sid = 65537,
            swapVibrationIndex = false,
        )
        assertNotNull(raw)
        val r = raw!!
        assertEquals(58, r.size)

        // header: [2, 2, pid, sid, patternCount]
        assertEquals(2, r[0])
        assertEquals(2, r[1])
        assertEquals(777, r[2])
        assertEquals(65537, r[3])
        assertEquals(2 or (2 shl 16), r[4])

        // pattern 0: index, absoluteTime, eventCount
        assertEquals(0, r[5])
        assertEquals(0, r[6])
        assertEquals(2, r[7])

        // continuous 0: tag, len, id, rel, intensity, freq, duration, n, 4*3 points
        assertEquals(RichTapRawCodec.TAG_CONTINUOUS, r[8])
        assertEquals(18, r[9])
        assertEquals(1, r[10])
        assertEquals(0, r[11])
        assertEquals(100, r[12])
        assertEquals(56, r[13])
        assertEquals(100, r[14])
        assertEquals(4, r[15])
        assertEquals(33, r[19])
        assertEquals(100, r[20])

        // transient: tag, len, id, rel, intensity, freq, duration=48
        assertEquals(RichTapRawCodec.TAG_TRANSIENT, r[28])
        assertEquals(5, r[29])
        assertEquals(2, r[30])
        assertEquals(50, r[31])
        assertEquals(80, r[32])
        assertEquals(70, r[33])
        assertEquals(48, r[34])

        // pattern 1: index, absoluteTime, eventCount
        assertEquals(1, r[35])
        assertEquals(300, r[36])
        assertEquals(1, r[37])
        // Frequency=-1 → 56；曲线频率偏移被清零。
        assertEquals(RichTapRawCodec.TAG_CONTINUOUS, r[38])
        assertEquals(18, r[39])
        assertEquals(0, r[40])
        assertEquals(0, r[41])
        assertEquals(90, r[42])
        assertEquals(56, r[43])
        assertEquals(100, r[44])
        assertEquals(4, r[45])
    }

    @Test
    fun core24BoundarySelectsHe20WhileCore23SelectsHe10() {
        val doc = HeDocument(
            HeMetadata(2),
            listOf(HePattern(0, listOf(continuous(0, 100, 56, index = 0)))),
        )
        val he20 = RichTapRawCodec.encode(doc, 24, 0, 1, 2, false)!!
        assertEquals(2, he20[0])
        assertEquals(2, he20[1])

        val he10 = RichTapRawCodec.encode(doc, 23, 0, 1, 2, false)!!
        assertEquals(1, he10[0])
    }

    @Test
    fun coreBelow24ConvertsHe20ToHe10Raw() {
        val raw = RichTapRawCodec.encode(
            document = document(),
            coreMajorRichTap = 20,
            minorRichTap = 0,
            pid = 1,
            sid = 2,
            swapVibrationIndex = false,
        )!!
        // HE1 legacy header = 1；transient 与第一条 continuous 重叠 → 被删除。
        assertEquals(1, raw[0])
        assertEquals(35, raw.size)
        assertEquals(RichTapRawCodec.TAG_CONTINUOUS, raw[1])
        assertEquals(0, raw[2])
        assertEquals(100, raw[3])
        assertEquals(56, raw[4])
        assertEquals(100, raw[5])
        // 第二条 pattern 的 AbsoluteTime=300 → RelativeTime 300。
        assertEquals(RichTapRawCodec.TAG_CONTINUOUS, raw[18])
        assertEquals(300, raw[19])
    }

    @Test
    fun he10DocumentOnType2IsWrappedAsHe20Raw() {
        val he10 = HeDocument(
            HeMetadata(1),
            listOf(
                HePattern(
                    0,
                    listOf(HeEvent("transient", 0, 0, 0, EventParams(100, 60, emptyList()))),
                )
            ),
        )
        val raw = RichTapRawCodec.encode(
            document = he10,
            coreMajorRichTap = 30,
            minorRichTap = 0,
            pid = 1,
            sid = 2,
            swapVibrationIndex = false,
        )!!
        assertEquals(2, raw[0])
        assertEquals(2, raw[1])
        assertEquals(1 or (1 shl 16), raw[4])
        assertEquals(RichTapRawCodec.TAG_TRANSIENT, raw[8])
        assertEquals(48, raw[14])
    }

    @Test
    fun directHe20EncodeFillsTransientDuration48() {
        val raw = RichTapRawCodec.encode(
            events = listOf(transient(0, 70, index = 1)),
            heVersion = 2,
            coreMajorRichTap = 30,
            minorRichTap = 0,
            pid = 1,
            sid = 2,
            swapVibrationIndex = false,
        )!!
        assertEquals(RichTapRawCodec.TAG_TRANSIENT, raw[8])
        assertEquals(5, raw[9])
        assertEquals(1, raw[10])
        assertEquals(0, raw[11])
        assertEquals(100, raw[12])
        assertEquals(70, raw[13])
        assertEquals(48, raw[14])
    }

    @Test
    fun extendedCorePreservesMinusOneFrequencyAndCurveOffsets() {
        val base = continuous(0, 100, -1, index = 0)
        val withOffset = base.copy(
            params = base.params.copy(
                curve = base.params.curve.map { it.copy(freqOffset = 7) },
            ),
        )
        val doc = HeDocument(HeMetadata(2), listOf(HePattern(0, listOf(withOffset))))

        val extended = RichTapRawCodec.encode(
            document = doc,
            coreMajorRichTap = 40,
            minorRichTap = 0,
            pid = 1,
            sid = 2,
            swapVibrationIndex = false,
        )!!
        assertEquals(-1, extended[13])
        assertEquals(7, extended[18])

        val legacy = RichTapRawCodec.encode(
            document = doc,
            coreMajorRichTap = 30,
            minorRichTap = 0,
            pid = 1,
            sid = 2,
            swapVibrationIndex = false,
        )!!
        assertEquals(56, legacy[13])
        assertEquals(0, legacy[18])
    }

    @Test
    fun swapVibrationIndexAppliesToHe20() {
        val doc = HeDocument(
            HeMetadata(2),
            listOf(HePattern(0, listOf(continuous(0, 100, 56, index = 1)))),
        )
        val raw = RichTapRawCodec.encode(
            document = doc,
            coreMajorRichTap = 30,
            minorRichTap = 0,
            pid = 1,
            sid = 2,
            swapVibrationIndex = true,
        )!!
        // pattern header 3 ints + event header 8 ints → id at index 10. 1 → 2。
        assertEquals(2, raw[10])
    }
}
