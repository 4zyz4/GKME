package com.zyz4.gkme.view

import android.view.MotionEvent
import android.view.View

object GamepadTouchDispatchUtils {

    fun dispatchFilteredToTouchpad(
        child: View,
        event: MotionEvent,
        touchpadPointerIds: Set<Int>,
        indices: List<Int>?,
    ) {
        val include = indices ?: (0 until event.pointerCount).filter { event.getPointerId(it) in touchpadPointerIds }
        if (include.isEmpty()) return

        if (include.size == event.pointerCount) {
            val ev = MotionEvent.obtain(event)
            ev.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
            child.dispatchTouchEvent(ev)
            ev.recycle()
            return
        }

        val props = Array(include.size) { i ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(include[i], it) }
        }
        val coords = Array(include.size) { i ->
            MotionEvent.PointerCoords().also { event.getPointerCoords(include[i], it) }
        }

        val rawAction = event.actionMasked
        val newAction = if (include.size == 1 && rawAction == MotionEvent.ACTION_POINTER_DOWN) {
            MotionEvent.ACTION_DOWN
        } else if (include.size == 1 && rawAction == MotionEvent.ACTION_POINTER_UP) {
            MotionEvent.ACTION_UP
        } else {
            rawAction
        }

        val ev = MotionEvent.obtain(
            event.downTime, event.eventTime,
            newAction, include.size,
            props, coords,
            event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags,
            event.source, event.flags
        )
        ev.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
        child.dispatchTouchEvent(ev)
        ev.recycle()
    }

    fun dispatchToChild(
        child: View,
        event: MotionEvent,
        action: Int,
        pointerIdx: Int,
    ) {
        val ev = MotionEvent.obtain(
            event.downTime, event.eventTime,
            action,
            event.getX(pointerIdx) - child.left,
            event.getY(pointerIdx) - child.top,
            event.metaState
        )
        child.dispatchTouchEvent(ev)
        ev.recycle()
    }

    fun resetForceFollowFinger(children: List<View>) {
        for (child in children) {
            when (child) {
                is JoystickView -> child.forceFollowFinger = false
                is DpadPadView -> child.forceFollowFinger = false
                is CustomKeypadView -> child.forceFollowFinger = false
            }
        }
    }

    private fun getButtonId(child: View): String? {
        val tag = child.tag as? String
        if (tag != null) return tag
        return try {
            child.context.resources.getResourceEntryName(child.id)
        } catch (e: Exception) {
            null
        }
    }
}