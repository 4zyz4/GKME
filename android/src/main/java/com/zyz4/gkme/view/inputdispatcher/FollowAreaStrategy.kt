package com.zyz4.gkme.view.inputdispatcher

import com.zyz4.gkme.view.inputdispatcher.FollowAreaResult
import com.zyz4.gkme.model.ButtonPosition

/**
 * Pure follow-area activation strategy.
 *
 * Evaluates whether any follow-area is currently active for a given set
 * of pointers. Returns the button IDs that should be activated in
 * follow-mode, or null if no follow-area is active.
 *
 * This replaces tryFollowAreaTrigger() and
 * tryFollowAreaOverlapTrigger() from the original GamepadLayout.
 * They were near-identical branches that both computed which children
 * were inside a follow-area rect.
 */
object FollowAreaStrategy {

    /**
     * Evaluate which follow-areas contain any pointer.
     *
     * @param buttons all button positions that have follow-area rects defined
     * @param cellW one cell width in px
     * @param cellH one cell height in px
     * @param pointers all pointers in the event
     * @return set of button IDs whose follow-area is active, or emptySet
     */
    fun evaluate(
        buttons: List<ButtonPosition>,
        cellW: Float,
        cellH: Float,
        pointers: List<Pointer>,
    ): FollowAreaResult {
        val activated = mutableSetOf<String>()

        for (pos in buttons) {
            if (isTouchpadId(pos.id)) continue
            if (!pos.followAreaEnabled) continue

            // For follow-area-overlap-trigger buttons, only activate if
            // overlapTrigger is true.
            if (!pos.followAreaOverlapTrigger) {
                // Normal follow-area: finger must be inside rect AND no
                // non-follow-area child at that point (handled by caller
                // filtering childBounds before passing to this function).
                if (isAnyPointerInArea(pos, cellW, cellH, pointers)) {
                    activated.add(pos.id)
                }
            } else {
                // Overlap-trigger: finger inside rect, regardless of other
                // overlapping children.
                if (isAnyPointerInArea(pos, cellW, cellH, pointers)) {
                    activated.add(pos.id)
                }
            }
        }

        return FollowAreaResult(activatedButtonIds = activated)
    }

    /**
     * Check if any pointer falls within the follow-area rectangle of a
     * given ButtonPosition.
     */
    private fun isAnyPointerInArea(
        pos: ButtonPosition,
        cellW: Float,
        cellH: Float,
        pointers: List<Pointer>,
    ): Boolean {
        val areaLeft = pos.followAreaX * cellW
        val areaTop = pos.followAreaY * cellH
        val areaRight = (pos.followAreaX + pos.followAreaW) * cellW
        val areaBottom = (pos.followAreaY + pos.followAreaH) * cellH

        for (pointer in pointers) {
            if (pointer.x >= areaLeft && pointer.x <= areaRight &&
                pointer.y >= areaTop && pointer.y <= areaBottom) {
                return true
            }
        }
        return false
    }

    /**
     * Convenience: does the given button ID have an active follow-area?
     */
    fun isFollowAreaActive(
        pos: ButtonPosition,
        pointerX: Float,
        pointerY: Float,
        cellW: Float,
        cellH: Float,
    ): Boolean {
        if (isTouchpadId(pos.id)) return false
        if (!pos.followAreaEnabled) return false
        val areaLeft = pos.followAreaX * cellW
        val areaTop = pos.followAreaY * cellH
        val areaRight = (pos.followAreaX + pos.followAreaW) * cellW
        val areaBottom = (pos.followAreaY + pos.followAreaH) * cellH
        return pointerX >= areaLeft && pointerX <= areaRight &&
            pointerY >= areaTop && pointerY <= areaBottom
    }
}