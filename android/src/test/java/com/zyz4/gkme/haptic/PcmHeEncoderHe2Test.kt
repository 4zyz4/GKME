package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PCM encoder in HE2.0 / 16-point mode. */
class PcmHeEncoderHe2Test {

    @Test
    fun he2ModeEmits16PointsPerEvent() {
        val enc = PcmHeEncoder(
            eventsPerChunk = 2,
            eventMs = 100,
            pointsPerEvent = 16,
            he2 = true,
        )
        for (i in 0 until 20) enc.addSample(10, 0.8f, 56)
        val json = enc.flush()
        assertNotNull(json)

        val doc = He2Json.parse(json!!)
        assertNotNull(doc)
        assertEquals(2, doc!!.version)
        assertEquals(1, doc.patterns.size)
        assertEquals(2, doc.patterns[0].events.size)
        for (e in doc.patterns[0].events) {
            assertEquals("continuous", e.type)
            assertEquals(16, e.params.curve.size)
            assertEquals(0, e.params.curve.first().timeMs)
            assertEquals(100, e.params.curve.last().timeMs)
            for (i in 1 until e.params.curve.size) {
                assertTrue(e.params.curve[i].timeMs >= e.params.curve[i - 1].timeMs)
            }
        }
        assertEquals(0, doc.patterns[0].events[0].relativeTimeMs)
        assertEquals(100, doc.patterns[0].events[1].relativeTimeMs)
    }

    @Test
    fun he2ModeStillDegradesToFourPointsForType1() {
        val enc = PcmHeEncoder(
            eventsPerChunk = 2,
            eventMs = 100,
            pointsPerEvent = 16,
            he2 = true,
        )
        for (i in 0 until 20) enc.addSample(10, 0.5f, 60)
        val he2 = He2Json.parse(enc.flush()!!)!!
        val he10 = He2Converter.toHe10BestEffort(he2)!!
        assertEquals(1, he10.version)
        assertEquals(2, he10.patterns[0].events.size)
        for (e in he10.patterns[0].events) assertEquals(4, e.params.curve.size)
    }

    @Test
    fun defaultModeRemainsHe10WithFourPoints() {
        val enc = PcmHeEncoder(eventsPerChunk = 2, eventMs = 100)
        for (i in 0 until 20) enc.addSample(10, 0.5f, 56)
        val json = enc.flush()!!
        assertTrue(json.contains("\"Version\":1"))
        val doc = He2Json.parse(json)!!
        assertEquals(1, doc.version)
        assertEquals(2, doc.patterns[0].events.size)
        for (e in doc.patterns[0].events) assertEquals(4, e.params.curve.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedPointCount() {
        PcmHeEncoder(pointsPerEvent = 3)
    }
}
