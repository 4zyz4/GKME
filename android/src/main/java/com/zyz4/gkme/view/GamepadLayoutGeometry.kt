package com.zyz4.gkme.view

import android.view.View
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.view.inputdispatcher.isTouchpadId

/**
 * Pure geometry utility functions extracted from GamepadLayout.
 * These functions operate on data (ButtonPosition) and primitive
 * types — they do not depend on any Android View objects
 * (except findChildAt, which needs the children list for hit-testing).
 */
object GamepadLayoutGeometry {

    /** On-screen size of a control in grid units (accounts for 90/270 rotation swap). */
    fun onScreenGridSize(pos: ButtonPosition): Pair<Int, Int> {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        return if (isSwapped) (pos.height to pos.width) else (pos.width to pos.height)
    }

    /** True when the touchpad control is fully inside the extended range rectangle. */
    fun touchpadContainedByArea(pos: ButtonPosition): Boolean {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return true
        val (sw, sh) = onScreenGridSize(pos)
        return pos.x >= pos.followAreaX && pos.y >= pos.followAreaY &&
            pos.x + sw <= pos.followAreaX + pos.followAreaW &&
            pos.y + sh <= pos.followAreaY + pos.followAreaH
    }

    /** Expands the extended range rectangle if it no longer contains the touchpad. */
    fun normalizeTouchpadArea(pos: ButtonPosition): ButtonPosition {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return pos
        if (touchpadContainedByArea(pos)) return pos
        val (sw, sh) = onScreenGridSize(pos)
        val ax = minOf(pos.followAreaX, pos.x)
        val ay = minOf(pos.followAreaY, pos.y)
        val aw = maxOf(pos.followAreaW, pos.x + sw - ax)
        val ah = maxOf(pos.followAreaH, pos.y + sh - ay)
        return pos.copy(followAreaX = ax, followAreaY = ay, followAreaW = aw, followAreaH = ah)
    }

    /** Shrinks the touchpad so it fits inside the extended range rectangle. */
    fun shrinkTouchpadToArea(pos: ButtonPosition): ButtonPosition {
        if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return pos
        val (sw, sh) = onScreenGridSize(pos)
        val nw = minOf(sw, (pos.followAreaX + pos.followAreaW - pos.x).coerceAtLeast(1))
        val nh = minOf(sh, (pos.followAreaY + pos.followAreaH - pos.y).coerceAtLeast(1))
        if (nw == sw && nh == sh) return pos
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val width = if (isSwapped) nh else nw
        val height = if (isSwapped) nw else nh
        return pos.copy(width = width, height = height)
    }

    /** Returns [left, top, width, height] in grid coordinates for the visual extent. */
    fun visualBounds(pos: ButtonPosition): FloatArray {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val lw = if (isSwapped) pos.height else pos.width
        val lh = if (isSwapped) pos.width else pos.height
        return floatArrayOf(pos.x.toFloat(), pos.y.toFloat(), lw.toFloat(), lh.toFloat())
    }

    /** Checks whether the point (x, y) falls within a small handle region
     *  at the bottom-right corner of the control's visual bounds.
     *  Uses [buttonBounds] as a map of buttonId -> the rect returned by visualBounds * cellW/cellH.
     */
    fun isOnHandle(x: Float, y: Float, buttonId: String, buttonBounds: Map<String, FloatArray>, cellW: Float, cellH: Float, handleHitDp: Float): Boolean {
        val vb = buttonBounds[buttonId] ?: return false
        val vx = vb[0] * cellW
        val vy = vb[1] * cellH
        val vw = vb[2] * cellW
        val vh = vb[3] * cellH
        val hx = vx + vw - handleHitDp
        val hy = vy + vh - handleHitDp
        return x >= hx && x <= vx + vw && y >= hy && y <= vy + vh
    }

    /** Finds the topmost visible child whose pixel bounds contain (x, y). */
    fun findChildAt(x: Float, y: Float, children: List<View>): View? {
        for (i in children.size - 1 downTo 0) {
            val child = children[i]
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                return child
            }
        }
        return null
    }

    /** Returns all visible children at the grid-coordinate (cellW, cellH) given by (x/y, y/cellH),
     *  topmost first. Uses grid-coordinate based bounds from [buttons] instead of viewport
     *  pixel bounds to avoid missing hits on some Android devices. */
    fun findAllChildrenAt(
        x: Float, y: Float,
        cellW: Float, cellH: Float,
        children: List<View>,
        currentButtons: List<ButtonPosition>,
    ): List<View> {
        val gridX = x / cellW
        val gridY = y / cellH
        val result = mutableListOf<View>()
        for (i in children.size - 1 downTo 0) {
            val child = children[i]
            if (child.visibility != View.VISIBLE) continue
            val cid = getButtonId(child) ?: continue
            val pos = currentButtons.find { it.id == cid } ?: continue
            val vb = visualBounds(pos)
            if (gridX >= vb[0] && gridX <= vb[0] + vb[2] && gridY >= vb[1] && gridY <= vb[1] + vb[3]) {
                result.add(child)
            }
        }
        return result
    }

    /** When multiple children overlap, exclude those with [overlapTrigger] = false. */
    fun filterOverlapChildren(
        children: List<View>,
        currentButtons: List<ButtonPosition>,
    ): List<View> {
        if (children.size <= 1) return children
        return children.filter { child ->
            val id = getButtonId(child)
            id == null || currentButtons.find { it.id == id }?.overlapTrigger != false
        }
    }

    /** Extracts the button ID from a child View.
     *  Checks [View.tag] first (set at creation time), then falls back to
     *  [android.content.Context.resources.getResourceEntryName]. */
    private fun getButtonId(child: View): String? {
        val tag = child.tag as? String
        if (tag != null) return tag
        return try {
            // This fallback requires a Context, which we don't have here.
            // In practice, all children have their tag set, so this branch
            // should never be reached when called from GamepadLayout methods.
            // We return null to keep the signature safe.
            null
        } catch (e: Exception) {
            null
        }
    }
}