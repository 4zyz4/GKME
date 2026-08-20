package com.zyz4.gamepademu.view.inputdispatcher

import android.view.MotionEvent

/**
 * Mouse-mode gesture dispatcher running on the phone touchpad via pointer capture.
 *
 * Gesture map:
 *   Single finger move      -> cursor move
 *   Double finger move      -> vertical / horizontal scroll
 *   Single finger tap       -> left click (tap-to-click)
 *   Double finger tap       -> right click
 *   Triple finger tap       -> middle click
 *   Triple finger drag      -> hold middle button + drag cursor
 *   Double-tap-press mode   -> on left-down, keep left button held continuously
 */
object MouseInputDispatcher {

    /** Per-frame state accumulated by the dispatcher. */
    data class State(
        val scrollActive: Boolean = false,
        val lastScrollTime: Long = 0L,
        var dragging: Boolean = false,
        var dragButton: Byte = 0,       // 1=left, 2=right, 4=middle, 0=none
        val tapInfo: Map<Int, TapInfo> = emptyMap(),
    )

    /** Track a finger's state: is it tapping (single tap not yet resolved)? */
    data class TapInfo(
        val downTime: Long,
        val x: Float,
        val y: Float,
    )

    data class MouseResult(
        val buttonDown: Byte = 0,
        val buttonUp: Byte = 0,
        val dx: Short = 0,
        val dy: Short = 0,
        val wheelV: Short = 0,
        val wheelH: Short = 0,
        val newState: State = State(),
    )

    private const val TAP_TIMEOUT = 200L

    /**
     * Main dispatcher called from GamepadLayout.onCapturedPointerEvent when mouseMode=true.
     */
    @Suppress("SameParameterValue")
    fun dispatch(
        actionMasked: Int,
        pointerCount: Int,
        event: MotionEvent,
        prevState: State,
        doubleTapDrag: Boolean,
        keepLeftDown: Boolean,
        sensitivity: Float,
    ): MouseResult {

        var newS = prevState
        var buttonDown: Byte = 0
        var buttonUp: Byte = 0
        var dx: Short = 0
        var dy: Short = 0
        var wheelV: Short = 0
        var wheelH: Short = 0

        val now = System.currentTimeMillis()

        // Clean up expired tap timeouts
        val cleanedTaps = newS.tapInfo.filterValues {
            now - it.downTime < TAP_TIMEOUT
        }
        if (cleanedTaps.size != newS.tapInfo.size) {
            newS = newS.copy(tapInfo = cleanedTaps)
        }

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pid = event.getPointerId(0)
                newS = newS.copy(
                    tapInfo = newS.tapInfo + Pair(pid, TapInfo(now, event.getX(0), event.getY(0)))
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = actionMasked and MotionEvent.ACTION_POINTER_INDEX_MASK
                val pid = event.getPointerId(idx)
                val px = event.getX(idx)
                val py = event.getY(idx)

                when (pointerCount) {
                    2 -> {
                        newS = newS.copy(tapInfo = newS.tapInfo + Pair(pid, TapInfo(now, px, py)))
                    }
                    3 -> {
                        newS = newS.copy(
                            dragging = true,
                            dragButton = 4,
                            tapInfo = newS.tapInfo + Pair(pid, TapInfo(now, px, py)),
                        )
                        buttonDown = 4
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (pointerCount) {
                    1 -> {
                        if (newS.dragging) {
                            val hx = event.getHistoricalX(0, 0)
                            val hy = event.getHistoricalY(0, 0)
                            val cx = event.getX(0)
                            val cy = event.getY(0)
                            dx = ((cx - hx) * sensitivity).toInt().toShort()
                            dy = ((cy - hy) * sensitivity).toInt().toShort()
                            if (dx != 0.toShort() && dy != 0.toShort() && keepLeftDown && newS.dragButton == 0.toByte()) {
                                newS = newS.copy(dragButton = 1)
                                buttonDown = 1
                            }
                        } else {
                            val hx = event.getHistoricalX(0, 0)
                            val hy = event.getHistoricalY(0, 0)
                            dx = ((event.getX(0) - hx) * sensitivity).toInt().toShort()
                            dy = ((event.getY(0) - hy) * sensitivity).toInt().toShort()
                        }
                    }

                    2 -> {
                        val (v, h) = computeTwoFingerScroll(event, sensitivity)
                        wheelV = v
                        wheelH = h
                        newS = newS.copy(
                            scrollActive = true,
                            lastScrollTime = now,
                            tapInfo = emptyMap(),
                        )
                    }

                    3 -> {
                        if (newS.dragging && newS.dragButton == 4.toByte()) {
                            buttonDown = 4
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIdx = actionMasked and MotionEvent.ACTION_POINTER_INDEX_MASK
                val liftedPid = event.getPointerId(liftedIdx)

                when (pointerCount) {
                    1 -> {
                        if (newS.tapInfo.containsKey(liftedPid)) {
                            buttonUp = 2
                        }
                        newS = newS.copy(tapInfo = newS.tapInfo - liftedPid)
                    }
                    2 -> {
                        newS = newS.copy(tapInfo = newS.tapInfo - liftedPid)
                    }
                    3 -> {
                        if (newS.dragging && newS.dragButton == 4.toByte()) {
                            newS = newS.copy(dragging = false, dragButton = 0)
                            buttonUp = 4
                        } else {
                            newS = newS.copy(tapInfo = newS.tapInfo - liftedPid)
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (newS.dragging) {
                    buttonUp = newS.dragButton
                    newS = newS.copy(dragging = false, dragButton = 0)
                } else if (newS.tapInfo.isNotEmpty()) {
                    val count = newS.tapInfo.size
                    newS = newS.copy(tapInfo = emptyMap())

                    when (count) {
                        1 -> {
                            if (!keepLeftDown) {
                                buttonDown = 1
                            } else {
                                if (doubleTapDrag) {
                                    newS = newS.copy(dragging = true, dragButton = 1)
                                    buttonDown = 1
                                } else {
                                    buttonDown = 1
                                }
                            }
                        }
                        2 -> {
                            buttonDown = 2
                        }
                        3 -> {
                            buttonDown = 4
                        }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (newS.dragging) {
                    buttonUp = newS.dragButton
                    newS = newS.copy(dragging = false, dragButton = 0)
                }
            }
        }

        return MouseResult(
            buttonDown = buttonDown,
            buttonUp = buttonUp,
            dx = dx, dy = dy,
            wheelV = wheelV, wheelH = wheelH,
            newState = newS,
        )
    }

    private fun computeTwoFingerScroll(
        event: MotionEvent,
        sensitivity: Float,
    ): Pair<Short, Short> {
        if (event.pointerCount != 2) return 0.toShort() to 0.toShort()

        val cx = (event.getX(0) + event.getX(1)) / 2f
        val cy = (event.getY(0) + event.getY(1)) / 2f
        val hx = (event.getHistoricalX(0, 0) + event.getHistoricalX(1, 0)) / 2f
        val hy = (event.getHistoricalY(0, 0) + event.getHistoricalY(1, 0)) / 2f

        val dX = ((hx - cx) * sensitivity).toInt().toShort()
        val dY = ((hy - cy) * sensitivity).toInt().toShort()

        return dX to dY
    }
}