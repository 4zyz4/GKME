package com.zyz4.gkme.input

import com.zyz4.gkme.model.ComboTiming
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ComboSequencerTest {

    @Test
    fun simultaneous_sendsOneBatch() = runBlocking {
        val events = mutableListOf<String>()
        val sequencer = ComboSequencer(this) { down, up ->
            if (down.isNotEmpty()) events.add("down:${down.joinToString(",")}")
            if (up.isNotEmpty()) events.add("up:${up.joinToString(",")}")
        }

        sequencer.setTarget("a", listOf(1, 2), ComboTiming.SIMULTANEOUS, 16)
        sequencer.setTarget("a", emptyList(), ComboTiming.SIMULTANEOUS, 16)

        assertEquals(listOf("down:1,2", "up:2,1"), events)
    }

    @Test
    fun sequential_pressesForwardAndReleasesBackward() = runBlocking {
        val events = mutableListOf<String>()
        val sequencer = ComboSequencer(this) { down, up ->
            for (item in down) events.add("down:$item")
            for (item in up) events.add("up:$item")
        }

        sequencer.setTarget("a", listOf(1, 2), ComboTiming.CUSTOM_INTERVAL, 1)
        delay(100)
        sequencer.setTarget("a", emptyList(), ComboTiming.CUSTOM_INTERVAL, 1)
        delay(100)

        assertEquals(listOf("down:1", "down:2", "up:2", "up:1"), events)
    }

    @Test
    fun unchangedTarget_doesNotRestartSequence() = runBlocking {
        var batches = 0
        val sequencer = ComboSequencer(this) { down, up ->
            if (down.isNotEmpty() || up.isNotEmpty()) batches++
        }

        repeat(5) { sequencer.setTarget("a", listOf(1, 2), ComboTiming.SIMULTANEOUS, 16) }

        assertEquals(1, batches)
    }
}
