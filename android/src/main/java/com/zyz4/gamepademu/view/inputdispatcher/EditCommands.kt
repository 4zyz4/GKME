package com.zyz4.gamepademu.view.inputdispatcher

import com.zyz4.gamepademu.model.ButtonPosition

// ================================================================
// Constants used by multiple files in this module
// ================================================================

const val SETTINGS_BUTTON_ID = "btnSettings"

// ================================================================
// Utility functions
// ================================================================

fun isTouchpadId(id: String): Boolean = id.substringBefore("_") == "touchpad"

fun screenGridSize(pos: ButtonPosition): Pair<Int, Int> {
    val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
    return if (isSwapped) pos.height to pos.width else pos.width to pos.height
}

fun sanitizeSettingsButton(
    pos: ButtonPosition,
    gridX: Int = pos.x,
    gridY: Int = pos.y,
): ButtonPosition {
    return pos.copy(x = gridX, y = gridY)
}

/** Shrink touchpad so it fits inside follow-area rectangle. */
fun shrinkTouchpadToArea(pos: ButtonPosition, oldPos: ButtonPosition): ButtonPosition {
    if (!isTouchpadId(pos.id) || !pos.followAreaEnabled) return pos
    val (sw, sh) = screenGridSize(pos)
    val nw = minOf(sw, (pos.followAreaX + pos.followAreaW - pos.x).coerceAtLeast(1))
    val nh = minOf(sh, (pos.followAreaY + pos.followAreaH - pos.y).coerceAtLeast(1))
    if (nw == sw && nh == sh) return pos
    val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
    val width = if (isSwapped) nh else nw
    val height = if (isSwapped) nw else nh
    return pos.copy(width = width, height = height)
}

// ================================================================
// EditCommand sealed interface
// ================================================================

/**
 * Edit-mode commands. Pure data — applyTo returns a new list, no side effects.
 *
 * Only gesture-recognised operations become commands. UI-control-triggered
 * operations (rotation slider, transparency seekbar, curve editor, delete,
 * duplicate) remain as onButtonUpdated callbacks in FloatingEditorPanel.
 *
 * Open-closed: new command types can be added without modifying existing code.
 */
sealed interface EditCommand {
    fun applyTo(current: List<ButtonPosition>): List<ButtonPosition>

    data class MoveButton(
        val id: String,
        val gridX: Int,
        val gridY: Int,
    ) : EditCommand {
        override fun applyTo(current: List<ButtonPosition>): List<ButtonPosition> {
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return current
            val sanitized = if (id == SETTINGS_BUTTON_ID) {
                sanitizeSettingsButton(current[idx], gridX, gridY)
            } else {
                current[idx].copy(x = gridX, y = gridY)
            }
            return current.toMutableList().also { it[idx] = sanitized }
        }
    }

    data class ResizeButton(
        val id: String,
        val gridWidth: Int,
        val gridHeight: Int,
    ) : EditCommand {
        override fun applyTo(current: List<ButtonPosition>): List<ButtonPosition> {
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return current
            var updated = current[idx].copy(width = gridWidth, height = gridHeight)
            if (id == SETTINGS_BUTTON_ID) {
                updated = sanitizeSettingsButton(updated)
            }
            return current.toMutableList().also { it[idx] = updated }
        }
    }

    data class MoveFollowArea(
        val id: String,
        val newX: Int,
        val newY: Int,
    ) : EditCommand {
        override fun applyTo(current: List<ButtonPosition>): List<ButtonPosition> {
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return current
            return current.toMutableList().also {
                it[idx] = current[idx].copy(followAreaX = newX, followAreaY = newY)
            }
        }
    }

    data class ResizeFollowArea(
        val id: String,
        val newW: Int,
        val newH: Int,
    ) : EditCommand {
        override fun applyTo(current: List<ButtonPosition>): List<ButtonPosition> {
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return current
            val old = current[idx]
            var updated = old.copy(followAreaW = newW, followAreaH = newH)
            if (isTouchpadId(id) && old.followAreaEnabled) {
                updated = shrinkTouchpadToArea(updated, old)
            }
            return current.toMutableList().also { it[idx] = updated }
        }
    }
}