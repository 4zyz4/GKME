package com.zyz4.gkme.input

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.zyz4.gkme.proto.GamepadInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InputSessionTest {
    private fun key(code: Int) = GamepadInput.newBuilder().addPressedScanCodes(code).build()

    @Test fun clearingViewModelCancelsItsSenderBeforeTheReplacementCanType() = runBlocking {
        val session = InputSession()
        val store = ViewModelStore()
        val oldOwner = object : ViewModel(CoroutineScope(coroutineContext + Job())) {}
        store.put("input-owner", oldOwner)
        val resumeOldSender = CompletableDeferred<Unit>()
        val writes = mutableListOf<GamepadInput>()
        val oldSender = oldOwner.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            resumeOldSender.await()
            session.send(session.generation, key(4)) { writes += it }
        }
        store.clear()
        oldSender.join()
        assertTrue(oldSender.isCancelled)
        resumeOldSender.complete(Unit)
        val newOwner = object : ViewModel(CoroutineScope(coroutineContext + Job())) {}
        store.put("input-owner", newOwner)
        newOwner.viewModelScope.launch {
            session.send(session.generation, key(5)) { writes += it }
        }.join()
        store.clear()
        assertEquals(listOf(key(5)), writes)
    }

    @Test fun reconnectRejectsAlreadyDrainedOldBatchesAndPrimesNewConnectionOnce() = runBlocking {
        val session = InputSession()
        val old = session.generation
        val writes = mutableListOf<GamepadInput>()
        session.invalidate(primeWithNeutral = true)
        session.send(old, key(4)) { writes += it }
        session.send(old, GamepadInput.getDefaultInstance()) { writes += it }
        val current = session.generation
        session.send(current, key(5)) { writes += it }
        session.send(current, key(5)) { writes += it }
        session.send(current, GamepadInput.getDefaultInstance()) { writes += it }
        assertEquals(listOf(emptyList<Int>(), listOf(5), listOf(5), emptyList<Int>()),
            writes.map { it.pressedScanCodesList })
    }

    @Test fun backgroundResetDropsOldDownsWithoutResettingOtherInputFields() = runBlocking {
        val session = InputSession()
        val beforePause = session.generation
        val heldGamepad = GamepadInput.newBuilder().setButtons(0x10).setLeftStickX(12000).build()
        session.invalidate()
        val writes = mutableListOf<GamepadInput>()
        session.send(beforePause, key(4)) { writes += it }
        session.send(session.generation, heldGamepad) { writes += it }
        assertEquals(listOf(heldGamepad), writes)
    }

    @Test fun teardownWaitsForInflightWriteAndReleasesBeforeClosingAndReopening() = runBlocking {
        val session = InputSession()
        val inFlight = CompletableDeferred<Unit>()
        val continueWrite = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val oldToken = session.generation
        val oldWrite = launch(start = CoroutineStart.UNDISPATCHED) {
            session.send(oldToken, key(4)) {
                events += "old-down"
                inFlight.complete(Unit)
                continueWrite.await()
            }
            session.send(oldToken, key(4)) { events += "stale-repeat" }
        }
        inFlight.await()
        session.invalidate(primeWithNeutral = true)
        val close = launch(start = CoroutineStart.UNDISPATCHED) {
            session.finish({ events += "release" }, { events += "close" })
        }
        val reopen = launch(start = CoroutineStart.UNDISPATCHED) {
            close.join()
            session.send(session.generation, key(5)) {
                events += if (it.pressedScanCodesCount == 0) "new-neutral" else "new-down"
            }
        }
        assertEquals(listOf("old-down"), events)
        continueWrite.complete(Unit)
        oldWrite.join(); close.join(); reopen.join()
        assertEquals(listOf("old-down", "release", "close", "new-neutral", "new-down"), events)
    }

    @Test fun anotherResetDuringNeutralWriteCannotClearTheNextConnectionsNeutralRequirement() = runBlocking {
        val session = InputSession()
        session.invalidate(primeWithNeutral = true)
        val writes = mutableListOf<GamepadInput>()
        session.send(session.generation, key(4)) {
            writes += it
            session.invalidate(primeWithNeutral = true)
        }
        session.send(session.generation, key(5)) { writes += it }
        assertEquals(listOf(emptyList<Int>(), emptyList<Int>(), listOf(5)),
            writes.map { it.pressedScanCodesList })
    }

    @Test fun failedReleaseStillClosesTheOldTransport() = runBlocking {
        val session = InputSession()
        var closed = false
        try {
            session.finish({ throw IllegalStateException("Disconnected") }, { closed = true })
            fail("Release failure must propagate")
        } catch (_: IllegalStateException) {
            assertTrue(closed)
        }
    }
}
