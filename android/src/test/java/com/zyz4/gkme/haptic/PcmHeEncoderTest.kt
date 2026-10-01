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
    fun seamBoostLiftsFirstEventLeadingPoints() {
        fun curveIntensities(boost: Double): List<Double> {
            val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, seamBoost = boost)
            var json: String? = null
            for (i in 0 until 30) json = enc.addSample(10, 0.4f, 56) ?: json
            // values[0] 是 Parameters.Intensity(=100)；其后依次是各控制点的曲线强度。
            return Regex("\"Intensity\":([0-9.]+)").findAll(json!!)
                .map { it.groupValues[1].toDouble() }.toList()
        }
        val plain = curveIntensities(1.0)
        val boosted = curveIntensities(1.5)
        // 首事件第 0 点被抬升。
        assertTrue("起振补偿应抬升首点: ${boosted[1]} > ${plain[1]}", boosted[1] > plain[1])
        // 曲线强度受 [0,1] 约束。
        assertTrue("强度不应越界", boosted[1] <= 1.0)
        // 第二个事件的第 0 点（values[5]）不受影响。
        assertEquals("非首事件不应被补偿", plain[5], boosted[5], 1e-9)
    }

    @Test
    fun onsetAccentAddsShortMaxEvent() {
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, seamBoost = 1.0, accentMs = 5)
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
    fun frequencyCompensationRaisesOffResonanceIntensity() {
        fun firstPointIntensity(he: Int): Double {
            val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, seamBoost = 1.0)
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
    fun resonancePointIntensityUnchangedByCompensation() {
        // he=56 时补偿增益为 1，曲线强度只由 amplitudeToCurve 决定。
        val enc = PcmHeEncoder(eventsPerChunk = 4, eventMs = 50, seamBoost = 1.0)
        for (i in 0 until 30) enc.addSample(10, 0.4f, RichTapEngine.HE_AT_RESONANCE)
        val intensities = Regex("\"Intensity\":([0-9.]+)").findAll(enc.flush()!!)
            .map { it.groupValues[1].toDouble() }.toList()
        assertEquals(RichTapEngine.amplitudeToCurve(0.4), intensities[1], 1e-6)
    }
}
