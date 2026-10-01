package com.zyz4.gkme.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the PC `TriggerEffectGenerator` (revision 6) wire layout.
 *
 * They pin the frequency byte of each automated effect and the rising-only flag of the
 * resistance effects, which the motor/HD conversion relies on.
 */
class AdaptiveTriggerEffectTest {

    private fun packet(type: Int, vararg data: Int): ByteArray {
        val raw = ByteArray(11)
        raw[0] = type.toByte()
        data.forEachIndexed { index, value -> raw[index + 1] = value.toByte() }
        return raw
    }

    @Test
    fun feedback_isRisingOnly_withoutFrequency() {
        val effect = AdaptiveTriggerEffect.parse(packet(0x21, 0xFF, 0x03, 0, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(effect.active)
        assertTrue(effect.risingOnly)
        assertEquals(0.0, effect.frequencyHz, 0.0)
        assertEquals(0, effect.amplitudeFor(0))
        assertEquals(31, effect.amplitudeFor(255))
    }

    @Test
    fun weapon_isRisingOnly_andResolvesZoneRange() {
        // Weapon(start=3, end=7, strength=8): start/stop mask 0x88, force code 7.
        val effect = AdaptiveTriggerEffect.parse(packet(0x25, 0x88, 0x00, 0x07, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(effect.active)
        assertTrue(effect.risingOnly)
        assertEquals(0.0, effect.frequencyHz, 0.0)
        assertEquals(0, effect.amplitudeFor(50))
        assertEquals(255, effect.amplitudeFor(100))
        assertEquals(0, effect.amplitudeFor(220))
    }

    @Test
    fun vibration_carriesFrequencyAtByte9() {
        val effect = AdaptiveTriggerEffect.parse(packet(0x26, 0xFF, 0x03, 0, 0, 0, 0, 0, 0, 40, 0))
        assertTrue(effect.active)
        assertFalse(effect.risingOnly)
        assertEquals(40.0, effect.frequencyHz, 0.0)
    }

    @Test
    fun gallopingAndMachine_carryFrequencyAtByte4() {
        val galloping = AdaptiveTriggerEffect.parse(packet(0x23, 0x0C, 0x00, 0x08, 55, 0, 0, 0, 0, 0, 0))
        assertEquals(55.0, galloping.frequencyHz, 0.0)
        assertFalse(galloping.risingOnly)

        val machine = AdaptiveTriggerEffect.parse(packet(0x27, 0x0C, 0x00, 0x08, 60, 9, 0, 0, 0, 0, 0))
        assertEquals(60.0, machine.frequencyHz, 0.0)
        assertFalse(machine.risingOnly)
    }

    @Test
    fun bow_hasNoFrequency() {
        val effect = AdaptiveTriggerEffect.parse(packet(0x22, 0x0C, 0x00, 0x24, 0x11, 0, 0, 0, 0, 0, 0))
        assertTrue(effect.active)
        assertFalse(effect.risingOnly)
        assertEquals(0.0, effect.frequencyHz, 0.0)
    }

    @Test
    fun simpleVibration_frequencyAtByte1_positionAtByte3() {
        // Simple_Vibration(frequency=30, amplitude=4, position=100).
        val effect = AdaptiveTriggerEffect.parse(packet(0x06, 30, 4, 100, 0, 0, 0, 0, 0, 0, 0))
        assertFalse(effect.risingOnly)
        assertEquals(30.0, effect.frequencyHz, 0.0)
        assertEquals(0, effect.amplitudeFor(50))
        assertEquals(4, effect.amplitudeFor(100))
    }

    @Test
    fun simpleWeapon_isRisingOnly() {
        // Simple_Weapon(start=0x90, end=0xa0, strength=0xff).
        val effect = AdaptiveTriggerEffect.parse(packet(0x02, 0x90, 0xa0, 0xff, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(effect.risingOnly)
        assertEquals(0, effect.amplitudeFor(140))
        assertEquals(255, effect.amplitudeFor(150))
    }

    @Test
    fun limitedWeapon_scalesStrengthAndIsRisingOnly() {
        // Limited_Weapon(start=0x20, end=0x40, strength=5) -> scaleLevel(5) = 139.
        val effect = AdaptiveTriggerEffect.parse(packet(0x12, 0x20, 0x40, 5, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(effect.risingOnly)
        assertEquals(139, effect.amplitudeFor(50))
    }
}
