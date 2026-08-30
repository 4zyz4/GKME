package com.zyz4.gkme.view

import android.view.MotionEvent
import android.view.View
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.view.inputdispatcher.isTouchpadId

/**
 * Side-effect-free follow-area trigger logic extracted from [GamepadLayout].
 *
 * The original functions lived in [GamepadLayout] and directly accessed layout children,
 * button positions, [MotionEvent] dispatch, and touch-session state. This class accepts
 * the data it needs as parameters and uses callbacks for side effects so that the caller
 * retains full control over what actually happens (dispatching, setting `forceFollowFinger`,
 * writing to `touchSession`, etc.).
 */
class GamepadFollowAreaTrigger(
    private val dispatchToChild: (child: View, event: MotionEvent, action: Int, pointerIdx: Int) -> Unit = { _, _, _, _ -> },
    private val setForceFollowFinger: (child: View, enabled: Boolean) -> Unit = { _, _ -> },
    private val setTouchTargets: (pid: Int, targets: List<View>) -> Unit = { _, _ -> },
) {

    /**
     * Activate follow-area trigger for a joystick or dpadPad.
     *
     * Returns true if a matching view was found and side effects were applied.
     */
    fun tryFollowAreaTrigger(
        x: Float, y: Float,
        pid: Int,
        event: MotionEvent,
        idx: Int,
        children: List<View>,
        findChildrenAt: (x: Float, y: Float) -> List<View>,
        posForView: (child: View) -> ButtonPosition?,
        cellW: Float, cellH: Float,
): Boolean {
        val followAreaChild = mutableListOf<Pair<View, ButtonPosition>>()
        for (child in children) {
            if (child.visibility != View.VISIBLE) continue
            val pos = posForView(child) ?: continue
            if (isTouchpadId(pos.id)) continue
            if (!pos.followAreaEnabled) continue
            val areaLeft = pos.followAreaX * cellW
            val areaTop = pos.followAreaY * cellH
            val areaRight = (pos.followAreaX + pos.followAreaW) * cellW
            val areaBottom = (pos.followAreaY + pos.followAreaH) * cellW
            if (x >= areaLeft && x <= areaRight && y >= areaTop && y <= areaBottom) {
                followAreaChild.add(child to pos)
            }
        }
        if (followAreaChild.isEmpty()) return false

        // Check if there are other (non-follow-area) children at this point.
        // If so, the follow-area control should NOT fire here — let normal dispatch handle it.
        val otherChildren = findChildrenAt(x, y).filter { it !in followAreaChild.map { it.first } }
        if (otherChildren.isNotEmpty()) return false

        for ((child, _) in followAreaChild) {
            setForceFollowFinger(child, true)
        }

        val toDispatch = mutableListOf<View>()
        for ((child, _) in followAreaChild) {
            toDispatch.add(child)
        }

        if (toDispatch.size > 1) {
            toDispatch[0] = toDispatch[1]
            toDispatch[1] = followAreaChild.first().first
        }

        setTouchTargets(pid, toDispatch)
        dispatchToChild(followAreaChild.first().first, event, MotionEvent.ACTION_DOWN, idx)
        for (c in toDispatch) {
            if (c != followAreaChild.first().first) {
                dispatchToChild(c, event, MotionEvent.ACTION_DOWN, idx)
            }
        }
        return true
    }

    /**
     * Called when a touch point lands inside a non-joystick control's bounds AND inside a
     * follow-area rect. Only fires if `followAreaOverlapTrigger` is true.
     *
     * Returns true if side effects were applied.
     */
    fun tryFollowAreaOverlapTrigger(
        x: Float, y: Float,
        pid: Int,
        event: MotionEvent,
        idx: Int,
        children: List<View>,
        findChildrenAt: (x: Float, y: Float) -> List<View>,
        posForView: (child: View) -> ButtonPosition?,
        cellW: Float, cellH: Float,
    ): Boolean {
        val followAreaChild = mutableListOf<View>()
        for (child in children) {
            if (child.visibility != View.VISIBLE) continue
            val pos = posForView(child) ?: continue
            if (isTouchpadId(pos.id)) continue
            if (!pos.followAreaEnabled || !pos.followAreaOverlapTrigger) continue
            if (isInFollowArea(x, y, pos, cellW, cellH)) {
                followAreaChild.add(child)
            }
        }
        if (followAreaChild.isEmpty()) return false

        for (child in followAreaChild) {
            setForceFollowFinger(child, true)
        }

        val toDispatch = mutableListOf<View>()
        toDispatch.addAll(followAreaChild)

        // Also dispatch to overlapping children (button-like overlap triggering).
        for (other in findChildrenAt(x, y)) {
            if (other !in followAreaChild) {
                val otherPos = posForView(other) ?: continue
                if (otherPos.overlapTrigger) {
                    toDispatch.add(other)
                }
            }
        }

        setTouchTargets(pid, toDispatch)
        for (c in toDispatch) {
            dispatchToChild(c, event, MotionEvent.ACTION_DOWN, idx)
        }
        return true
    }

    /**
     * Returns true when the point `(x, y)` is on the resize handle of the follow-area rect
     * defined by [pos]. The handle is a `handleHitPx`-wide strip at the bottom-right corner.
     */
    fun isOnFollowAreaHandle(x: Float, y: Float, pos: ButtonPosition, cellW: Float, cellH: Float, handleHitPx: Float): Boolean {
        val fLeft = pos.followAreaX * cellW
        val fTop = pos.followAreaY * cellH
        val fRight = (pos.followAreaX + pos.followAreaW) * cellW
        val fBottom = (pos.followAreaY + pos.followAreaH) * cellH
        val hx = fRight - handleHitPx
        val hy = fBottom - handleHitPx
        return x >= hx && x <= fRight && y >= hy && y <= fBottom
    }

    /**
     * Returns true when the point `(x, y)` is inside the follow-area rect defined by [pos].
     */
    fun isInFollowArea(x: Float, y: Float, pos: ButtonPosition, cellW: Float, cellH: Float): Boolean {
        val fLeft = pos.followAreaX * cellW
        val fTop = pos.followAreaY * cellH
        val fRight = (pos.followAreaX + pos.followAreaW) * cellW
        val fBottom = (pos.followAreaY + pos.followAreaH) * cellH
        return x >= fLeft && x <= fRight && y >= fTop && y <= fBottom
    }
}