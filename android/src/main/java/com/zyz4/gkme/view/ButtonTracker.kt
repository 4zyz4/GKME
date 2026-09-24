package com.zyz4.gkme.view

import android.view.MotionEvent
import android.view.View

/**
 * Layered button touch architecture:
 *
 * Layer 1 - View layer: OnTouchListener consumes MotionEvent → delegates to ButtonTracker
 * Layer 2 - ButtonTracker: tracks per-view pressed/autoHold state, calls handler
 * Layer 3 - StateManagement (handler impl): consumes events → manages bit state, haptic, gyro
 */

internal class ButtonTracker {
    private val holdState = mutableMapOf<String, Boolean>()

    fun feed(
        event: MotionEvent,
        view: View,
        viewId: String,
        bit: Int,
        holdEnabled: Boolean,
        gyroActivate: Boolean,
        handler: ButtonEventHandler,
    ): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                view.performClick()
                if (holdEnabled) {
                    val oldHeld = holdState[viewId] == true
                    if (oldHeld) {
                        holdState[viewId] = false
                        handler.onRelease(viewId, bit)
                        handler.onGyroActivateUp(viewId)
                    } else {
                        holdState[viewId] = true
                        handler.onPress(viewId, bit)
                        handler.onGyroActivateDown(viewId)
                    }
                } else {
                    handler.onPress(viewId, bit)
                    handler.onGyroActivateDown(viewId)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (holdEnabled && holdState[viewId] == true) {
                    view.isPressed = true
                } else {
                    val wasHeld = holdState[viewId] == true
                    view.isPressed = false
                    holdState[viewId] = false
                    if (!wasHeld && !holdEnabled) {
                        handler.onRelease(viewId, bit)
                        handler.onGyroActivateUp(viewId)
                    }
                }
            }
        }
        return true
    }

    fun clear(viewId: String) { holdState.remove(viewId) }
}

internal interface ButtonEventHandler {
    fun onPress(viewId: String, bit: Int)
    fun onRelease(viewId: String, bit: Int)
    fun onGyroActivateDown(viewId: String)
    fun onGyroActivateUp(viewId: String)
}