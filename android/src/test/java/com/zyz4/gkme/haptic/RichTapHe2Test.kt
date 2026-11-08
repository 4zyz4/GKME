package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** HE2.0 authoring API tests. */
class RichTapHe2Test {

    @Test
    fun patternListKeepsAbsoluteTimeIndexAnd16Points() {
        val curve = (0 until 16).map { RichTapHe.CurvePoint(it * 10, 1.0, it.toDouble()) }
        val json = RichTapHe2.patternList(
            listOf(
                RichTapHe2.PatternSpec(
                    absoluteTimeMs = 0,
                    events = listOf(
                        RichTapHe2.EventSpec(
                            relativeTimeMs = 0,
                            durationMs = 150,
                            baseFreq = 56,
                            points = curve,
                            index = 1,
                        )
                    ),
                ),
                RichTapHe2.PatternSpec(
                    absoluteTimeMs = 300,
                    events = listOf(RichTapHe2.transient(0, strength = 80, frequency = 70, index = 2)),
                ),
            )
        )
        val doc = He2Json.parse(json)
        assertNotNull(doc)
        assertEquals(2, doc!!.version)
        assertEquals(2, doc.patterns.size)
        assertEquals(300, doc.patterns[1].absoluteTimeMs)

        val first = doc.patterns[0].events[0]
        assertEquals(1, first.vibrationId)
        assertEquals(16, first.params.curve.size)

        val transient = doc.patterns[1].events[0]
        assertEquals("transient", transient.type)
        assertEquals(2, transient.vibrationId)
        assertEquals(48, transient.durationMs)
    }

    @Test
    fun continuousIsHe20WithFourPoints() {
        val doc = He2Json.parse(RichTapHe2.continuous(70, 500))!!
        assertEquals(2, doc.version)
        assertEquals(1, doc.patterns.size)
        val e = doc.patterns[0].events[0]
        assertEquals("continuous", e.type)
        assertEquals(500, e.durationMs)
        assertEquals(4, e.params.curve.size)
    }

    @Test
    fun patternFromEventsProducesHe20() {
        val events = listOf(
            RichTapHe.Event(
                relativeTimeMs = 0,
                durationMs = 100,
                baseFreq = 56,
                points = listOf(
                    RichTapHe.CurvePoint(0, 0.0, 0.0),
                    RichTapHe.CurvePoint(33, 1.0, 0.0),
                    RichTapHe.CurvePoint(66, 1.0, 0.0),
                    RichTapHe.CurvePoint(100, 0.0, 0.0),
                ),
            )
        )
        val doc = He2Json.parse(RichTapHe2.patternFromEvents(events))!!
        assertEquals(2, doc.version)
        assertEquals(100, doc.patterns[0].events[0].durationMs)
    }
}
