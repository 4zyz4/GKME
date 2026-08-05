package com.zyz4.gamepademu.view

import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.view.inputdispatcher.isTouchpadId

/**
 * Touchpad-related utility functions extracted from GamepadLayout.
 */
object GamepadTouchpadUtils {

    private const val GRID_COLS = 120

    /** Settings button: no rotation, swipe trigger always off, overlap trigger always on, fully visible on screen. */
    fun sanitizeSettingsButton(
        pos: ButtonPosition,
        maxCol: Int,
        maxRow: Int,
    ): ButtonPosition {
        var p = pos.copy(rotation = 0, swipeTrigger = false, overlapTrigger = true, lockAspect = true,
            idleTransparency = 0, activeTransparency = 0)
        p = p.copy(
            x = p.x.coerceIn(0, maxCol),
            y = p.y.coerceIn(0, maxRow)
        )
        return p
    }

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
}