package com.zyz4.gkme.view.inputdispatcher

/**
 * Pure 2-finger touchpad slot assignment strategy.
 *
 * Implements the Moonlight-inspired slot matching algorithm.
 * Zero mutable state — old state passed in, new state returned.
 */
object SlotMatcher {

    /**
     * Assign touch candidates to slots.
     */
    fun assign(
        oldState: OldSlotState,
        candidates: List<TouchCandidate>,
        releasedCandidateIndex: Int? = null,
    ): SlotResult {

        var slot0Active = oldState.slot0Active
        var slot1Active = oldState.slot1Active
        var slot0X = oldState.slot0X; var slot0Y = oldState.slot0Y
        var slot1X = oldState.slot1X; var slot1Y = oldState.slot1Y

        if (releasedCandidateIndex != null && releasedCandidateIndex in candidates.indices) {
            val released = candidates[releasedCandidateIndex]
            if (slot0Active && slot1Active) {
                val d0 = slotDist(slot0X, slot0Y, released.x, released.y)
                val d1 = slotDist(slot1X, slot1Y, released.x, released.y)
                if (d0 <= d1) slot0Active = false else slot1Active = false
            } else if (slot0Active) {
                slot0Active = false
            } else if (slot1Active) {
                slot1Active = false
            }
        }

        val live = if (releasedCandidateIndex != null) {
            candidates.filterIndexed { idx, _ -> idx != releasedCandidateIndex }
        } else {
            candidates
        }

        val used = mutableSetOf<Int>()
        var aTo0 = -1; var aTo1 = -1

        if (slot0Active) {
            var best = -1; var bestD = Float.MAX_VALUE
            for (ci in live.indices) {
                if (ci in used) continue
                val d = slotDist(slot0X, slot0Y, live[ci].x, live[ci].y)
                if (d < bestD) { bestD = d; best = ci }
            }
            if (best >= 0) {
                used.add(best); slot0X = live[best].x; slot0Y = live[best].y; aTo0 = best
            }
        }

        if (slot1Active) {
            var best = -1; var bestD = Float.MAX_VALUE
            for (ci in live.indices) {
                if (ci in used) continue
                val d = slotDist(slot1X, slot1Y, live[ci].x, live[ci].y)
                if (d < bestD) { bestD = d; best = ci }
            }
            if (best >= 0) {
                used.add(best); slot1X = live[best].x; slot1Y = live[best].y; aTo1 = best
            }
        }

        for (ci in live.indices) {
            if (ci in used) continue
            if (!slot0Active) { slot0X = live[ci].x; slot0Y = live[ci].y; aTo0 = ci; slot0Active = true }
            else if (!slot1Active) { slot1X = live[ci].x; slot1Y = live[ci].y; aTo1 = ci; slot1Active = true }
        }

        return SlotResult(
            slotAssignment = SlotAssignment(
                slot0TouchId = if (slot0Active && aTo0 >= 0) live[aTo0].touchId else -1,
                slot0X = slot0X, slot0Y = slot0Y,
                slot1TouchId = if (slot1Active && aTo1 >= 0) live[aTo1].touchId else -1,
                slot1X = slot1X, slot1Y = slot1Y,
            ),
            newState = OldSlotState(
                slot0Active = slot0Active, slot0X = slot0X, slot0Y = slot0Y,
                slot1Active = slot1Active, slot1X = slot1X, slot1Y = slot1Y,
            ),
        )
    }

    /** Build TouchCandidates from RawTouchEvent pointers. */
    fun buildCandidates(
        pointers: List<Pointer>,
        releasedCandidateIndex: Int? = null,
    ): List<TouchCandidate> {
        return pointers.mapIndexed { idx, p ->
            TouchCandidate(p.id, p.x / 1920f, p.y / 942f, releasedCandidateIndex == idx)
        }
    }

    private fun slotDist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return dx * dx + dy * dy
    }

    data class SlotResult(
        val slotAssignment: SlotAssignment,
        val newState: OldSlotState,
    )
}