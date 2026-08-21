package com.zyz4.gamepademu.view

import android.view.View

/**
 * Pure data container for run-mode touch dispatch state in GamepadLayout.
 * Tracks touch routing targets, touchpad ownership, swipe button state, and
 * force-follow-finger metadata across multiple call sites.
 */
class TouchSession {

    var touchTargets: MutableMap<Int, MutableList<View>> = mutableMapOf()

    var touchpadTarget: View? = null

    var touchpadPointerIds: MutableSet<Int> = mutableSetOf()

    var mousepadTarget: View? = null

    var mousepadPointerIds: MutableSet<Int> = mutableSetOf()

    var activeSwipeButtons: MutableMap<String, View> = mutableMapOf()

    companion object {
        fun reset(): TouchSession = TouchSession()
    }

    fun clear() {
        touchTargets.clear()
        touchpadTarget = null
        touchpadPointerIds.clear()
        mousepadTarget = null
        mousepadPointerIds.clear()
        activeSwipeButtons.clear()
    }
}