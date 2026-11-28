package com.zyz4.gkme.view

import com.zyz4.gkme.model.PrecisionTouchpadContact
import com.zyz4.gkme.model.PrecisionTouchpadState
import kotlin.math.roundToInt

/**
 * Pure helpers for the on-screen precision touchpad (mousepad in "precision"
 * mode). The control reports raw contacts in the Windows Precision Touchpad
 * logical range (0..4095); the host performs cursor motion, scrolling, taps and
 * multi-finger gestures.
 */
object PrecisionTouchpad {

    const val LOGICAL_MAX = PrecisionTouchpadState.PRECISION_TOUCHPAD_MAX

    /**
     * Rotates a normalized control coordinate so the reported surface follows
     * the control's rotation. The mapping mirrors the delta rotation used by
     * the mouse gesture path: at 90° a rightward screen move becomes an upward
     * move on the pad (dx' = dy, dy' = -dx).
     */
    fun rotate(nx: Float, ny: Float, rotation: Int): Pair<Float, Float> =
        when (((rotation % 360) + 360) % 360) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }

    /** Builds one contact from a normalized (0..1) position inside the control. */
    fun contact(
        id: Int,
        nx: Float,
        ny: Float,
        rotation: Int = 0,
    ): PrecisionTouchpadContact {
        val (rx, ry) = rotate(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f), rotation)
        return PrecisionTouchpadContact(
            id = id.coerceIn(0, 255),
            x = (rx * LOGICAL_MAX).roundToInt().coerceIn(0, LOGICAL_MAX),
            y = (ry * LOGICAL_MAX).roundToInt().coerceIn(0, LOGICAL_MAX),
        )
    }
}
