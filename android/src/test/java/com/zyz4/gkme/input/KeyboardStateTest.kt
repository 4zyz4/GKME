package com.zyz4.gkme.input

import org.junit.Assert.*
import org.junit.Test

class KeyboardStateTest {
    private val state = KeyboardState()
    private val keyboard = Any()

    @Test fun shortTapRetainsDownThenAllReleasedReportBetweenPollingTicks() {
        state.keyDown(0x04, keyboard)
        state.keyUp(0x04, keyboard)
        assertEquals(listOf(KeyboardReport(0, listOf(0x04)), KeyboardReport()), state.drainReports())
        assertEquals(listOf(KeyboardReport()), state.drainReports())
    }

    @Test fun simultaneousChordProducesOneReportPerTransition() {
        val chord = listOf(0xE0, 0x06)
        state.applyChanges(down = chord, up = emptyList(), source = KeyboardSource.GAMEPAD)
        state.applyChanges(down = emptyList(), up = chord, source = KeyboardSource.GAMEPAD)

        assertEquals(listOf(KeyboardReport(1, listOf(0x06)), KeyboardReport()), state.drainReports())
    }

    @Test fun repeatedDownDoesNotOccupyMoreSlotsOrRequireMoreReleases() {
        repeat(20) { state.keyDown(0x04, keyboard) }
        assertEquals(listOf(KeyboardReport(0, listOf(0x04))), state.drainReports())
        state.keyUp(0x04, keyboard)
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun modifiersHaveIndependentLeftAndRightBits() {
        for (code in 0xE0..0xE7) state.keyDown(code, keyboard)
        state.keyDown(0x04, keyboard)
        assertEquals(KeyboardReport(0xFF, listOf(0x04)), state.snapshot())
        state.keyUp(0xE1, keyboard)
        assertEquals(0xFD, state.snapshot().modifier)
        state.keyUp(0x04, keyboard)
        assertEquals(KeyboardReport(0xFD), state.snapshot())
    }

    @Test fun releasingOneSourcePreservesOtherSourcesOfTheSameKeyAndModifier() {
        for (source in listOf(keyboard, KeyboardSource.VIRTUAL, KeyboardSource.GAMEPAD)) {
            state.keyDown(0x04, source)
            state.keyDown(0xE1, source)
        }
        state.keyUp(0x04, keyboard)
        state.keyUp(0xE1, keyboard)
        state.keyUp(0x04, KeyboardSource.VIRTUAL)
        state.keyUp(0xE1, KeyboardSource.VIRTUAL)
        assertEquals(KeyboardReport(2, listOf(0x04)), state.snapshot())
        state.keyUp(0x04, KeyboardSource.GAMEPAD)
        state.keyUp(0xE1, KeyboardSource.GAMEPAD)
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun seventhHeldKeyEntersWhenASlotOpensAndCanBeReleased() {
        for (code in 0x04..0x0A) state.keyDown(code, keyboard)
        state.keyDown(0xE0, keyboard)
        assertEquals(KeyboardReport(1, (0x04..0x09).toList()), state.snapshot())
        state.keyUp(0x06, keyboard)
        assertEquals(listOf(0x04, 0x05, 0x07, 0x08, 0x09, 0x0A), state.snapshot().keys)
        for (code in 0x04..0x0A) state.keyUp(code, keyboard)
        state.keyUp(0xE0, keyboard)
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun releasingOverflowKeyBeforeASlotOpensDoesNotResurrectIt() {
        for (code in 0x04..0x0A) state.keyDown(code, keyboard)
        state.keyUp(0x0A, keyboard)
        state.keyUp(0x04, keyboard)
        assertEquals((0x05..0x09).toList(), state.snapshot().keys)
    }

    @Test fun unmatchedReleaseDoesNotChangeAnotherOwnersState() {
        state.keyDown(0x04, keyboard)
        assertFalse(state.keyUp(0x04, Any()))
        assertEquals(listOf(0x04), state.snapshot().keys)
    }

    @Test fun legacyRightModifierAliasesStillWork() {
        for (code in 0xE8..0xEB) state.keyDown(code, keyboard)
        assertEquals(0xF0, state.snapshot().modifier)
        for (code in 0xE8..0xEB) state.keyUp(code, keyboard)
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun overflowRetainsTheNewestTransitionsAndFinalRelease() {
        state.keyDown(0xE1, keyboard)
        repeat(150) {
            state.keyDown(0x04, keyboard)
            state.keyUp(0x04, keyboard)
        }
        state.keyUp(0xE1, keyboard)

        val reports = state.drainReports()
        assertEquals(256, reports.size)
        assertEquals(KeyboardReport(2), reports.first())
        assertEquals(KeyboardReport(2, listOf(0x04)), reports[1])
        assertEquals(KeyboardReport(), reports.last())
    }

    @Test fun clearPublishesReleaseAndDiscardDropsOldSessionTransitions() {
        state.keyDown(0xE0, keyboard)
        state.keyDown(0x04, keyboard)
        state.clear()
        assertEquals(KeyboardReport(), state.drainReports().last())
        state.discardPendingReports()
        assertEquals(listOf(KeyboardReport()), state.drainReports())
    }
}
