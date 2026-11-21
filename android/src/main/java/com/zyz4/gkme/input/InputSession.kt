package com.zyz4.gkme.input

import com.zyz4.gkme.proto.GamepadInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orders writes and teardown, and rejects batches captured before a reset/disconnect. */
internal class InputSession {
    private val writes = Mutex()
    @Volatile var generation: Long = 0
        private set
    private var needsNeutral = false

    @Synchronized
    fun invalidate(primeWithNeutral: Boolean = false) {
        generation++
        needsNeutral = needsNeutral || primeWithNeutral
    }

    suspend fun send(token: Long, input: GamepadInput, write: suspend (GamepadInput) -> Unit) {
        writes.withLock {
            val prime = synchronized(this) {
                if (token != generation) return
                needsNeutral.also { needsNeutral = false }
            }
            if (prime) write(GamepadInput.getDefaultInstance())
            if (token == generation) write(input)
        }
    }

    suspend fun finish(release: suspend () -> Unit, close: () -> Unit) {
        writes.withLock {
            try { release() } finally { close() }
        }
    }
}
