package com.zyz4.gamepademu.view.inputdispatcher

import com.zyz4.gamepademu.model.ButtonPosition

/**
 * Pure hit test. Given a point, all child bounds and their overlapTrigger
 * settings, returns the set of buttons hit, filtered by overlapTrigger,
 * topmost first.
 *
 * Zero Android dependencies. The caller (GamepadLayout) maps the returned
 * buttonIds to actual View instances.
 */
object HitResolver {

    /**
     * Resolve all visible children at the given point, topmost first.
     */
    fun findAllChildrenAt(
        x: Float,
        y: Float,
        childBounds: Map<String, android.graphics.Rect>,
    ): List<Pair<String, android.graphics.Rect>> {
        val result = mutableListOf<Pair<String, android.graphics.Rect>>()
        // Note: caller should pass children in z-order (topmost last so we reverse).
        // Here we iterate in insertion order; caller reverses if needed.
        for ((buttonId, rect) in childBounds) {
            if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                result.add(buttonId to rect)
            }
        }
        return result
    }

    /**
     * Filter a list of hit results by overlapTrigger.
     * When multiple children overlap, exclude those with overlapTrigger == false.
     */
    fun filterOverlapChildren(
        hits: List<Pair<String, android.graphics.Rect>>,
        buttons: List<ButtonPosition>,
    ): List<Pair<String, android.graphics.Rect>> {
        if (hits.size <= 1) return hits

        val buttonMap = buttons.associateBy { it.id }
        return hits.filter { (buttonId, _) ->
            val pos = buttonMap[buttonId]
            pos == null || pos.overlapTrigger != false
        }
    }

    /**
     * Convenience: full resolve — find hits, filter by overlap.
     * Returns buttonIds that a point hits.
     */
    fun resolve(
        x: Float,
        y: Float,
        buttons: List<ButtonPosition>,
        childBounds: Map<String, android.graphics.Rect>,
    ): List<String> {
        val allHits = findAllChildrenAt(x, y, childBounds)
        val filtered = filterOverlapChildren(allHits, buttons)
        return filtered.map { it.first }
    }
}