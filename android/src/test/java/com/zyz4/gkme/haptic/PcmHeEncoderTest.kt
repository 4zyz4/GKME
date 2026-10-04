package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the chunked PCM -> HE 1.0 multi-event encoder. */
class PcmHeEncoderTest {

    private fun countOccurrences(hay: String, needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val j = hay.indexOf(needle, i)
            if (j < 0) break
            n++
            i = j + needle.length
        }
        return n
    }

    @Test
    fun emitsOneChunkWith16EventsAnd4PointsEach() {
        val enc = PcmHeEncoder(eventsPerChunk = 16, eventMs = 200)
        var json: String? = null
        // 10ms 帧，3200ms = 320 帧后凑满分块
        for (i in 0 until 400) {
            json = enc.addSample(10, 0.8f, 56) ?: json
        }
        assertNotNull("应产出一个完整分块", json)
        val s = json!!
        assertEquals("16 个事件", 16, countOccurrences(s, "\"Event\":"))
        assertEquals("每事件 4 个控制点", 64, countOccurrences(s, "\"Time\":"))
        assertTrue("基础频率应反映输入", s.contains("\"Frequency\":56"))
        // 事件相对时间覆盖整个分块
        assertTrue(s.contains("\"RelativeTime\":0"))
        assertTrue(s.contains("\"RelativeTime\":3000"))
    }

    @Test
    fun resetsAfterChunk() {
        val enc = PcmHeEncoder(eventsPerChunk = 16, eventMs = 200)
        var emitted = 0
        for (i in 0 until 800) {
            if (enc.addSample(10, 0.5f, 56) != null) emitted++
        }
        assertEquals("两个分块", 2, emitted)
    }

    @Test
    fun alwaysFourPointsEvenWhenSparse() {
        val enc = PcmHeEncoder(eventsPerChunk = 16, eventMs = 200)
        // 只在事件 0 的 0-50ms 桶里放两帧；其余 3 个桶为空也必须补成 4 点。
        enc.addSample(10, 0.6f, 70)
        enc.addSample(10, 0.6f, 70)
        val json = enc.flush()!!
        assertEquals(1, countOccurrences(json, "\"Event\":"))
        assertEquals(4, countOccurrences(json, "\"Time\":"))
    }

    @Test
    fun flushReturnsNullWhenNoSamples() {
        val enc = PcmHeEncoder()
        assertNull(enc.flush())
    }

    @Test
    fun frequencyValuesStayBounded() {
        val enc = PcmHeEncoder(eventsPerChunk = 16, eventMs = 200)
        var json: String? = null
        for (i in 0 until 400) {
            val he = if (i % 2 == 0) 10 else 100
            json = enc.addSample(10, 1f, he) ?: json
        }
        val values = Regex("\"Frequency\":(-?\\d+(?:\\.\\d+)?)").findAll(json!!)
            .map { it.groupValues[1].toDouble() }
            .toList()
        assertTrue("应有频率值", values.isNotEmpty())
        // 基础频率被限制在 20..80，曲线偏移被限制在 ±60。
        for (v in values) assertTrue("频率值应受限: $v", v >= -60.0 - 1e-6 && v <= 80.0 + 1e-6)
    }

    @Test
    fun seamFadeStartsFromPreviousChunkTail() {
        // 第一块稳定 0.8，第二块稳定 0.2；开启跨接后第二块首点应从 0.8 淡入到 0.2。
        fun secondChunkHead(fadeMs: Int): List<Double> {
            val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, seamFadeMs = fadeMs)
            for (i in 0 until 20) enc.addSample(10, 0.8f, 56)
            enc.flush()
            for (i in 0 until 20) enc.addSample(10, 0.2f, 56)
            val json = enc.flush()!!
            // values[0]=Parameters.Intensity(100)，values[1..4]=各控制点曲线强度。
            return Regex("\"Intensity\":([0-9.]+)").findAll(json)
                .map { it.groupValues[1].toDouble() }.toList()
        }
        val withFade = secondChunkHead(50)
        val noFade = secondChunkHead(0)
        val tail08 = RichTapEngine.amplitudeToCurve(0.8)
        val target02 = RichTapEngine.amplitudeToCurve(0.2)
        // 无跨接：首点直接跟随当前值 0.2。
        assertEquals("无跨接首点应跟随当前值", target02, noFade[1], 0.02)
        // 有跨接：首点从上一分块收尾电平 0.8 起（高于 0.2），末点回落到 0.2。
        assertEquals("跨接首点应从 0.8 起", tail08, withFade[1], 0.02)
        assertEquals("跨接末点应回到 0.2", target02, withFade[4], 0.02)
        assertTrue("跨接首点应明显高于无跨接", withFade[1] > noFade[1] + 0.05)
    }

    @Test
    fun resetStreamClearsSeam() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, seamFadeMs = 50)
        for (i in 0 until 20) enc.addSample(10, 0.8f, 56)
        enc.flush()
        enc.resetStream() // 整条流停止后，跨接状态清零
        for (i in 0 until 20) enc.addSample(10, 0.2f, 56)
        val json = enc.flush()!!
        val intensities = Regex("\"Intensity\":([0-9.]+)").findAll(json)
            .map { it.groupValues[1].toDouble() }.toList()
        // 流复位后首点从 0 淡入（而非陈旧的 0.8），末点回到当前值 0.2。
        assertEquals("流复位后首点应从 0 淡入", 0.0, intensities[1], 0.02)
        assertEquals(
            "末点应回到当前值",
            RichTapEngine.amplitudeToCurve(0.2), intensities[4], 0.02,
        )
    }

    @Test
    fun onsetAccentAddsShortMaxEvent() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, accentMs = 5)
        enc.addSample(10, 0.05f, 56)
        enc.addSample(10, 0.9f, 56) // 突然增大
        enc.addSample(10, 0.9f, 56)
        val json = enc.flush()!!
        assertTrue("应插入 5ms 强调事件", json.contains("\"Duration\":5,"))
        assertTrue("强调应为满强度曲线", json.contains("\"Intensity\":1.0"))
    }

    @Test
    fun noAccentOnGradualIncrease() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, accentMs = 5)
        var a = 0f
        while (a < 1f) { enc.addSample(10, a, 56); a += 0.1f }
        val json = enc.flush()!!
        assertFalse("平滑上升不应触发强调", json.contains("\"Duration\":5,"))
    }

    @Test
    fun accentDisabledByDefault() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50)
        enc.addSample(10, 0.05f, 56)
        enc.addSample(10, 0.9f, 56)
        val json = enc.flush()!!
        assertFalse("默认不插入强调", json.contains("\"Duration\":5,"))
    }

    @Test
    fun shortBurstFromSilenceAddsSinglePulse() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, burstMs = 8)
        enc.addSample(10, 0.0f, 56) // 静音
        enc.addSample(10, 0.8f, 56) // 从静音突变
        enc.addSample(10, 0.9f, 56)
        enc.addSample(10, 0.0f, 56) // 20ms 后回到静音
        val json = enc.flush()!!
        assertEquals("一次瞬态只出一条 8ms 强调", 1, countOccurrences(json, "\"Duration\":8,"))
        assertTrue("应为满强度", json.contains("\"Intensity\":1.0"))
    }

    @Test
    fun sustainedToneDoesNotTriggerBurst() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, burstMs = 8)
        val out = StringBuilder()
        for (i in 0 until 40) enc.addSample(10, 0.8f, 56)?.let { out.append(it) }
        enc.flush()?.let { out.append(it) }
        assertFalse("持续音不应产生瞬态响应", out.contains("\"Duration\":8,"))
    }

    @Test
    fun longToneAfterSilenceIsNotBurst() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, burstMs = 8)
        enc.addSample(10, 0.0f, 56)
        for (i in 0 until 8) enc.addSample(10, 0.7f, 56) // 80ms > 50ms
        enc.addSample(10, 0.0f, 56)
        val json = enc.flush()!!
        assertFalse("超过 50ms 的持续音不算瞬态", json.contains("\"Duration\":8,"))
    }

    @Test
    fun burstWithInternalGapAddsSinglePulse() {
        // 真机录音：一次短音常是“主峰 → 短暂凹陷 → 余响”。凹陷（≤40ms）不应把一次瞬态拆成两条。
        val enc = PcmHeEncoder(eventsPerChunk = 8, eventMs = 50, burstMs = 8)
        enc.addSample(10, 0.0f, 56) // 静音
        enc.addSample(10, 0.8f, 56) // 主峰
        enc.addSample(10, 0.0f, 56) // 凹陷
        enc.addSample(10, 0.0f, 56)
        enc.addSample(10, 0.9f, 56) // 余响（仍属同一次瞬态）
        for (i in 0 until 6) enc.addSample(10, 0.0f, 56) // 凹陷超过容限 -> 结束
        val json = enc.flush()!!
        assertEquals("内部凹陷不应拆成两条脉冲", 1, countOccurrences(json, "\"Duration\":8,"))
    }

    @Test
    fun burstDisabledByDefault() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50)
        enc.addSample(10, 0.0f, 56)
        enc.addSample(10, 0.8f, 56)
        enc.addSample(10, 0.0f, 56)
        val json = enc.flush()!!
        assertFalse("默认不插入瞬态响应", json.contains("\"Duration\":8,"))
    }

    @Test
    fun frequencyCompensationRaisesOffResonanceIntensity() {
        fun firstPointIntensity(he: Int): Double {
            val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50)
            for (i in 0 until 30) enc.addSample(10, 0.4f, he)
            // values[0] 是 Parameters.Intensity(=100)；values[1] 是首控制点的曲线强度。
            return Regex("\"Intensity\":([0-9.]+)").findAll(enc.flush()!!)
                .map { it.groupValues[1].toDouble() }.toList()[1]
        }
        val atResonance = firstPointIntensity(RichTapEngine.HE_AT_RESONANCE)
        val offResonance = firstPointIntensity(30)
        assertTrue(
            "偏离谐振应抬升曲线强度: $offResonance > $atResonance",
            offResonance > atResonance,
        )
    }

    @Test
    fun peakWithinBucketIsPreservedAboveMean() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50)
        // 同一事件、同一控制点桶内先低后高：均值会被拉低，峰值保持应保留攻击瞬态。
        enc.addSample(1, 0.1f, 56)
        enc.addSample(1, 0.9f, 56)
        val intensities = Regex("\"Intensity\":([0-9.]+)").findAll(enc.flush()!!)
            .map { it.groupValues[1].toDouble() }.toList()
        // values[0] 是 Parameters.Intensity(=100)，values[1] 是首控制点曲线强度。
        val meanCurve = RichTapEngine.amplitudeToCurve(0.5)
        assertTrue(
            "峰值保持应高于桶内均值: ${intensities[1]} > $meanCurve",
            intensities[1] > meanCurve,
        )
    }

    @Test
    fun resonancePointIntensityUnchangedByCompensation() {
        // he=56 时补偿增益为 1，曲线强度只由 amplitudeToCurve 决定。
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50)
        for (i in 0 until 30) enc.addSample(10, 0.4f, RichTapEngine.HE_AT_RESONANCE)
        val intensities = Regex("\"Intensity\":([0-9.]+)").findAll(enc.flush()!!)
            .map { it.groupValues[1].toDouble() }.toList()
        assertEquals(RichTapEngine.amplitudeToCurve(0.4), intensities[1], 1e-6)
    }
}
