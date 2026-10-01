package com.zyz4.gkme.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the HE-source priority arbitration. */
class HapticArbiterTest {

    @Test
    fun priorityOrder_matchesRequirement() {
        assertTrue(HapticSource.ADAPTIVE_TRIGGER.priority > HapticSource.AUDIO.priority)
        assertTrue(HapticSource.AUDIO.priority > HapticSource.GAME_RUMBLE.priority)
        assertTrue(HapticSource.GAME_RUMBLE.priority > HapticSource.BUTTON.priority)
    }

    @Test
    fun idle_allowsAnySource() {
        val arbiter = HapticArbiter()
        assertTrue(arbiter.canPlay(HapticSource.BUTTON))
        assertTrue(arbiter.acquire(HapticSource.BUTTON))
        assertEquals(HapticSource.BUTTON, arbiter.current())
    }

    @Test
    fun lowerSource_isSuppressedByHigher() {
        val arbiter = HapticArbiter()
        assertTrue(arbiter.acquire(HapticSource.ADAPTIVE_TRIGGER))
        assertFalse(arbiter.canPlay(HapticSource.AUDIO))
        assertFalse(arbiter.canPlay(HapticSource.GAME_RUMBLE))
        assertFalse(arbiter.canPlay(HapticSource.BUTTON))
        assertFalse(arbiter.acquire(HapticSource.GAME_RUMBLE))
        assertEquals(HapticSource.ADAPTIVE_TRIGGER, arbiter.current())
    }

    @Test
    fun higherSource_preemptsLower() {
        val arbiter = HapticArbiter()
        assertTrue(arbiter.acquire(HapticSource.GAME_RUMBLE))
        assertTrue(arbiter.acquire(HapticSource.AUDIO))
        assertEquals(HapticSource.AUDIO, arbiter.current())
        assertTrue(arbiter.acquire(HapticSource.ADAPTIVE_TRIGGER))
        assertEquals(HapticSource.ADAPTIVE_TRIGGER, arbiter.current())
    }

    @Test
    fun sameSource_canRefresh() {
        val arbiter = HapticArbiter()
        assertTrue(arbiter.acquire(HapticSource.GAME_RUMBLE))
        assertTrue(arbiter.canPlay(HapticSource.GAME_RUMBLE))
        assertTrue(arbiter.acquire(HapticSource.GAME_RUMBLE))
    }

    @Test
    fun release_letsLowerSourceIn() {
        val arbiter = HapticArbiter()
        arbiter.acquire(HapticSource.ADAPTIVE_TRIGGER)
        arbiter.release(HapticSource.ADAPTIVE_TRIGGER)
        assertEquals(null, arbiter.current())
        assertTrue(arbiter.acquire(HapticSource.GAME_RUMBLE))
    }

    @Test
    fun releaseOnlyByOwner_leavesOtherSource() {
        val arbiter = HapticArbiter()
        arbiter.acquire(HapticSource.AUDIO)
        arbiter.release(HapticSource.GAME_RUMBLE)
        assertEquals(HapticSource.AUDIO, arbiter.current())
    }

    @Test
    fun clear_resetsToIdle() {
        val arbiter = HapticArbiter()
        arbiter.acquire(HapticSource.AUDIO)
        arbiter.clear()
        assertEquals(null, arbiter.current())
        assertTrue(arbiter.canPlay(HapticSource.BUTTON))
    }
}
