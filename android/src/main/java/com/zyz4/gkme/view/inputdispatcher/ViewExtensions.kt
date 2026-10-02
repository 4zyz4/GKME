package com.zyz4.gkme.view.inputdispatcher

import android.view.MotionEvent

/**
 * Adapter from Android MotionEvent to RawTouchEvent.
 *
 * This is the ONLY file in the inputdispatcher module that depends on
 * Android types beyond simple data classes. All strategy modules receive
 * RawTouchEvent which contains zero Android dependencies.
 *
 * Extension function on MotionEvent for ergonomic conversion.
 */
fun MotionEvent.toRawEvent(): RawTouchEvent {
    val masked = action and MotionEvent.ACTION_MASK
    return RawTouchEvent(
        action = masked,
        actionIndex = if (masked == MotionEvent.ACTION_POINTER_UP || masked == MotionEvent.ACTION_POINTER_DOWN) {
            // 指针索引在高 8 位：ACTION_POINTER_INDEX_MASK=0xff00, SHIFT=8。
            // 不能用 `action and 0xf`（那会得到动作码本身）。
            (action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
        } else 0,
        pointers = (0 until pointerCount).map { i ->
            Pointer(
                id = getPointerId(i),
                x = getX(i),
                y = getY(i),
            )
        },
        buttonState = buttonState,
    )
}