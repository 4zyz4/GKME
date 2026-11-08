package com.zyz4.gkme.haptic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HE2.0 parser/writer + HE2→HE1 conversion tests. */
class He2JsonTest {

    private val he2Sample = """
        {
          "Metadata": {"Version": 2, "Created": "test", "Description": "sample"},
          "PatternList": [
            {
              "AbsoluteTime": 0,
              "Pattern": [
                {
                  "Event": {
                    "Type": "continuous",
                    "RelativeTime": 0,
                    "Duration": 300,
                    "Index": 1,
                    "Parameters": {
                      "Frequency": 56,
                      "Intensity": 100,
                      "Curve": [
                        {"Time": 0, "Intensity": 0.0, "Frequency": 0},
                        {"Time": 100, "Intensity": 1.0, "Frequency": 5},
                        {"Time": 200, "Intensity": 0.5, "Frequency": -3},
                        {"Time": 300, "Intensity": 0.0, "Frequency": 0}
                      ]
                    }
                  }
                },
                {
                  "Event": {
                    "Type": "transient",
                    "RelativeTime": 50,
                    "Index": 2,
                    "Parameters": {"Frequency": 70, "Intensity": 80}
                  }
                }
              ]
            },
            {
              "AbsoluteTime": 300,
              "Pattern": [
                {
                  "Event": {
                    "Type": "continuous",
                    "RelativeTime": 0,
                    "Duration": 100,
                    "Parameters": {
                      "Frequency": -1,
                      "Intensity": 90,
                      "Curve": [
                        {"Time": 0, "Intensity": 1.0, "Frequency": 7},
                        {"Time": 100, "Intensity": 1.0, "Frequency": 7}
                      ]
                    }
                  }
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesHe20PatternListAndIndex() {
        val doc = He2Json.parse(he2Sample)
        assertNotNull(doc)
        assertEquals(2, doc!!.version)
        assertEquals(2, doc.patterns.size)
        assertEquals(300, doc.patterns[1].absoluteTimeMs)
        assertEquals(2, doc.patterns[0].events.size)

        val continuous = doc.patterns[0].events[0]
        assertEquals("continuous", continuous.type)
        assertEquals(1, continuous.vibrationId)
        assertEquals(4, continuous.params.curve.size)
        assertEquals(-3, continuous.params.curve[2].freqOffset)

        // SDK 对 HE2.0 transient 的默认 duration = 48ms。
        val transient = doc.patterns[0].events[1]
        assertEquals("transient", transient.type)
        assertEquals(48, transient.durationMs)
        assertEquals(2, transient.vibrationId)
    }

    @Test
    fun parsesHe10TransientWithoutDurationAsZero() {
        val json = """
            {"Metadata":{"Version":1},"Pattern":[
              {"Event":{"Type":"transient","RelativeTime":0,
                "Parameters":{"Frequency":60,"Intensity":100}}}]}
        """.trimIndent()
        val doc = He2Json.parse(json)!!
        assertEquals(1, doc.version)
        assertEquals(1, doc.patterns.size)
        assertEquals(0, doc.patterns[0].events[0].durationMs)
        assertEquals(0, doc.patterns[0].events[0].vibrationId)
    }

    @Test
    fun writeHe20RoundTripKeepsPatternsAndIndex() {
        val doc = He2Json.parse(he2Sample)!!
        val json = He2Json.writeHe20(doc)
        val round = He2Json.parse(json)!!
        assertEquals(2, round.version)
        assertEquals(2, round.patterns.size)
        assertEquals(1, round.patterns[0].events[0].vibrationId)
        assertEquals(48, round.patterns[0].events[1].durationMs)
        // transient 的 Duration 在 SDK 序列化里被省略。
        val root = JSONObject(json)
        val ev = root.getJSONArray("PatternList")
            .getJSONObject(0).getJSONArray("Pattern")
            .getJSONObject(1).getJSONObject("Event")
        assertFalse(ev.has("Duration"))
    }

    @Test
    fun writeHe10BestEffortFlattensAllPatternsAndPadsCurves() {
        val doc = He2Json.parse(he2Sample)!!
        val he10 = He2Converter.toHe10BestEffort(doc)!!
        assertEquals(1, he10.version)
        assertEquals(1, he10.patterns.size)

        // transient 与第一条 continuous 重叠 → 被 trimOverlapEvent 删除。
        // 第二条 pattern 的 AbsoluteTime=300 被加到 RelativeTime。
        val events = he10.patterns[0].events
        assertEquals(2, events.size)
        assertEquals(0, events[0].relativeTimeMs)
        assertEquals(300, events[1].relativeTimeMs)
        assertTrue(events.all { it.type == "continuous" })
        assertTrue(events.all { it.params.curve.size == 4 })
        // Frequency=-1 的 continuous 被归一化为 56，曲线偏移清零。
        assertEquals(56, events[1].params.frequency)
        assertTrue(events[1].params.curve.all { it.freqOffset == 0 })

        val json = He2Json.writeHe10(doc)
        val round = He2Json.parse(json)!!
        assertEquals(1, round.version)
        assertEquals(2, round.patterns[0].events.size)
    }

    @Test
    fun sdkCompatibleConversionKeepsOnlyFirstPattern() {
        val doc = He2Json.parse(he2Sample)!!
        val he10 = He2Converter.toHe10Sdk(doc)!!
        assertEquals(1, he10.patterns.size)
        // 第一段里 transient 与 continuous 重叠 → 删除；第二段整体丢弃。
        assertEquals(1, he10.patterns[0].events.size)
        assertEquals(0, he10.patterns[0].events[0].relativeTimeMs)
    }

    @Test
    fun he10InputKeepsOverlappingEventsInBestEffort() {
        // 预置 10027 等 HE1 效果本身允许事件重叠；type1 直接下发时不应被重排/截断。
        val json = """
            {"Metadata":{"Version":1},"Pattern":[
              {"Event":{"Type":"continuous","RelativeTime":0,"Duration":300,
                "Parameters":{"Frequency":56,"Intensity":100,"Curve":[
                  {"Time":0,"Intensity":0.0,"Frequency":0},
                  {"Time":300,"Intensity":1.0,"Frequency":0},
                  {"Time":300,"Intensity":1.0,"Frequency":0},
                  {"Time":300,"Intensity":0.0,"Frequency":0}]}}},
              {"Event":{"Type":"continuous","RelativeTime":200,"Duration":300,
                "Parameters":{"Frequency":80,"Intensity":100,"Curve":[
                  {"Time":0,"Intensity":0.0,"Frequency":0},
                  {"Time":300,"Intensity":1.0,"Frequency":0},
                  {"Time":300,"Intensity":1.0,"Frequency":0},
                  {"Time":300,"Intensity":0.0,"Frequency":0}]}}}
            ]}
        """.trimIndent()
        val doc = He2Json.parse(json)!!
        val he10 = He2Converter.toHe10BestEffort(doc)!!
        assertEquals(2, he10.patterns[0].events.size)
        assertEquals(200, he10.patterns[0].events[1].relativeTimeMs)
    }
}
