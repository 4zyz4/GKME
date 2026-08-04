package com.zyz4.gamepademu.view.inputdispatcher

/**
 * Pure touchpad click detection via XOR of button state transitions.
 *
 * Inspired by the Moonlight Android touchpad protocol. Uses bitwise XOR
 * between old and new button states to detect rising/falling edges on
 * button bits.
 *
 * Zero state. Input = two button state integers, output = ClickResult.
 * Independent of slot matching — shares the same MotionEvent input but
 * is a separate concern.
 */
object TouchpadClickDetector {

    /**
     * Detect primary/secondary button edges from state transitions.
     *
     * @param oldButtonState previous MotionEvent.buttonState
     * @param newButtonState current MotionEvent.buttonState
     * @return click result with pressed/released flags for primary and secondary
     */
    fun detect(
        oldButtonState: Int,
        newButtonState: Int,
    ): ClickResult {
        val changed = oldButtonState xor newButtonState

        // BUTTON_PRIMARY = 0x0001
        val primaryPressed = (changed and android.view.MotionEvent.BUTTON_PRIMARY) != 0 &&
            (newButtonState and android.view.MotionEvent.BUTTON_PRIMARY) != 0
        val primaryReleased = (changed and android.view.MotionEvent.BUTTON_PRIMARY) != 0 &&
            (newButtonState and android.view.MotionEvent.BUTTON_PRIMARY) == 0

        // BUTTON_SECONDARY = 0x0002
        val secondaryPressed = (changed and android.view.MotionEvent.BUTTON_SECONDARY) != 0 &&
            (newButtonState and android.view.MotionEvent.BUTTON_SECONDARY) != 0
        val secondaryReleased = (changed and android.view.MotionEvent.BUTTON_SECONDARY) != 0 &&
            (newButtonState and android.view.MotionEvent.BUTTON_SECONDARY) == 0

        return ClickResult(
            primaryPressed = primaryPressed,
            primaryReleased = primaryReleased,
            secondaryPressed = secondaryPressed,
            secondaryReleased = secondaryReleased,
        )
    }
}