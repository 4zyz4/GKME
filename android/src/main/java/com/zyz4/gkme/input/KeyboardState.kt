package com.zyz4.gkme.input

/** An immutable snapshot shared by the UI thread and the network send loop. */
internal data class KeyboardReport(val modifier: Int = 0, val keys: List<Int> = emptyList())

internal enum class KeyboardSource { VIRTUAL, GAMEPAD }

/**
 * Tracks ownership, rather than putting duplicate usages in the six HID slots.
 * Keys beyond the report capacity remain held and enter the report when a slot opens.
 * Transitions are queued so a down/up between two polling ticks is still transmitted.
 */
internal class KeyboardState {
    private val owners = linkedMapOf<Int, MutableSet<Any>>()
    private var report = KeyboardReport()
    private val pending = ArrayDeque<KeyboardReport>()

    fun keyDown(code: Int, source: Any): Boolean =
        applyChanges(down = listOf(code), up = emptyList(), source = source)

    fun keyUp(code: Int, source: Any): Boolean =
        applyChanges(down = emptyList(), up = listOf(code), source = source)

    @Synchronized
    fun applyChanges(down: Collection<Int>, up: Collection<Int>, source: Any): Boolean {
        for (code in up) {
            val usage = normalize(code)
            val heldBy = owners[usage] ?: continue
            heldBy.remove(source)
            if (heldBy.isEmpty()) owners.remove(usage)
        }
        for (code in down) {
            val usage = normalize(code)
            if (usage in 0x04..0xE7) owners.getOrPut(usage) { mutableSetOf() }.add(source)
        }
        return publish()
    }

    @Synchronized
    fun clear() {
        owners.clear()
        publish()
    }

    @Synchronized
    fun snapshot(): KeyboardReport = report

    @Synchronized
    fun discardPendingReports() = pending.clear()

    @Synchronized
    fun drainReports(): List<KeyboardReport> {
        if (pending.isEmpty()) return listOf(report)
        return pending.toList().also { pending.clear() }
    }

    private fun publish(): Boolean {
        var modifier = 0
        for (usage in owners.keys) {
            if (usage in 0xE0..0xE7) modifier = modifier or (1 shl (usage - 0xE0))
        }
        val updated = KeyboardReport(modifier, owners.keys.filter { it < 0xE0 }.take(6))
        if (updated == report) return false
        report = updated
        pending.addLast(updated)
        return true
    }

    // Preserve the right-modifier aliases accepted by the existing ViewModel API.
    private fun normalize(code: Int): Int = if (code in 0xE8..0xEB) code - 4 else code
}
