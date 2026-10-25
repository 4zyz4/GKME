package com.zyz4.gkme.input

import com.zyz4.gkme.model.ComboTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Turns a desired set of mapped outputs into HID changes, either in one batch or one item at a
 * time.
 *
 * Items use the same encoding as the rest of the mapping code: negative values are keyboard scan
 * codes (`-code`), non-negative values are gamepad/mouse output bits. Several sources can share
 * one sequencer; each source keeps its own applied set so releasing one button does not cancel a
 * scheduled chord belonging to another button.
 *
 * A source is identified by [key]. [setTarget] is idempotent: calling it every physical-controller
 * frame with the same target does not restart the sequence.
 */
class ComboSequencer(
    private val scope: CoroutineScope,
    private val applyChanges: (down: List<Int>, up: List<Int>) -> Unit,
) {
    private class State {
        var target: List<Int> = emptyList()
        val applied: LinkedHashSet<Int> = LinkedHashSet()
        var job: Job? = null
    }

    private val states = HashMap<String, State>()

    /** True while [key] has a non-empty target (pressed or in the middle of a press sequence). */
    fun hasTarget(key: String): Boolean = states[key]?.target?.isNotEmpty() == true

    /**
     * Replaces the desired outputs of [key]. New items are pressed in target order; removed items
     * are released in reverse order so a modifier never comes up before the key it modifies.
     * Calls with an unchanged [items] list are ignored.
     */
    fun setTarget(key: String, items: List<Int>, timing: ComboTiming, customIntervalMs: Int) {
        val target = items.distinct()
        val state = states.getOrPut(key) { State() }
        if (state.target == target) return
        state.target = target
        state.job?.cancel()
        state.job = null

        val up = state.applied.filter { it !in target }.asReversed()
        val down = target.filter { it !in state.applied }
        if (up.isEmpty() && down.isEmpty()) return

        val interval = ComboTiming.effectiveIntervalMs(timing, customIntervalMs)
        if (interval <= 0) {
            state.applied.removeAll(up.toSet())
            state.applied.addAll(down)
            applyChanges(down, up)
            return
        }

        state.job = scope.launch {
            for (item in up) {
                if (!isActive) return@launch
                applyChanges(emptyList(), listOf(item))
                state.applied.remove(item)
                delay(interval.toLong())
            }
            for (item in down) {
                if (!isActive) return@launch
                applyChanges(listOf(item), emptyList())
                state.applied.add(item)
                delay(interval.toLong())
            }
        }
    }

    /** Releases every applied output and cancels any pending sequence. */
    fun releaseAll() {
        for (state in states.values) {
            state.job?.cancel()
            state.job = null
            val up = state.applied.toList().asReversed()
            state.applied.clear()
            state.target = emptyList()
            if (up.isNotEmpty()) applyChanges(emptyList(), up)
        }
    }
}
