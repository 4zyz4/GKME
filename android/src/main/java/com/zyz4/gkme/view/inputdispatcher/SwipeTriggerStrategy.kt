package com.zyz4.gkme.view.inputdispatcher

import com.zyz4.gkme.model.ButtonPosition

/**
 * Pure swipe-trigger evaluation strategy.
 *
 * Each swipe-triggered button independently checks ALL pointers.
 * If ANY pointer is within a button's bounds → pressed.
 * If NO pointer is within bounds → released.
 * Allows multiple swipe buttons to be pressed simultaneously.
 *
 * This replaces updateSwipeButtons() from the original GamepadLayout.
 */
object SwipeTriggerStrategy {

    /**
     * Re-evaluate all swipe-triggered buttons.
     *
     * @param buttons all button positions
     * @param childBounds buttonId → Rect in layout coordinates
     * @param pointers all pointers in the event
     * @param previouslyActive the set of swipe button IDs that were active last frame
     * @return which buttons transition to pressed / released
     */
    fun evaluate(
        buttons: List<ButtonPosition>,
        childBounds: Map<String, android.graphics.Rect>,
        pointers: List<Pointer>,
        previouslyActive: Set<String>,
    ): SwipeResult {
        // Determine which buttons should be active based on all pointers
        val newlyActive = mutableSetOf<String>()

        for (pos in buttons) {
            if (!pos.swipeTrigger) continue
            val rect = childBounds[pos.id] ?: continue

            for (pointer in pointers) {
                if (pointer.x >= rect.left && pointer.x <= rect.right &&
                    pointer.y >= rect.top && pointer.y <= rect.bottom) {
                    // Non-overlapping check: if not overlapTrigger, skip if
                    // another non-follow-area child also exists at this point.
                    // This is handled by the caller via filterOverlapChildren
                    // before passing childBounds.
                    if (!pos.overlapTrigger) {
                        // Check if this exact point is also inside another
                        // child (handled by caller — we assume childBounds
                        // already passed the overlap filter).
                        // For simplicity in the pure function, we rely on
                        // the caller to have filtered.
                    }
                    newlyActive.add(pos.id)
                    break
                }
            }
        }

        val prevActive = previouslyActive

        return SwipeResult(
            presses = (newlyActive - prevActive),
            releases = (prevActive - newlyActive),
        )
    }

    /**
     * Check if pointer is inside a button's bounds.
     */
    fun isPointerInButton(
        pointer: Pointer,
        rect: android.graphics.Rect,
    ): Boolean {
        return pointer.x >= rect.left && pointer.x <= rect.right &&
            pointer.y >= rect.top && pointer.y <= rect.bottom
    }
}