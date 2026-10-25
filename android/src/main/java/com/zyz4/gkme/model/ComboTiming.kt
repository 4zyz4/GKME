package com.zyz4.gkme.model

import androidx.annotation.StringRes
import com.zyz4.gkme.R

/**
 * Timing used when a mapped output contains more than one key/button (a chord).
 *
 * - [SIMULTANEOUS]: every key/button is sent in the same HID report (the historical behavior).
 * - [FRAME_BY_FRAME]: keys are pressed in mapping order, one per frame, then released in reverse
 *   order. This is the most compatible mode for games that poll the keyboard.
 * - [CUSTOM_INTERVAL]: same sequential behavior, but with a user-configurable interval.
 */
enum class ComboTiming(@StringRes val labelRes: Int) {
    SIMULTANEOUS(R.string.i18n_combo_timing_simultaneous),
    FRAME_BY_FRAME(R.string.i18n_combo_timing_framed),
    CUSTOM_INTERVAL(R.string.i18n_combo_timing_custom);

    val isSequential: Boolean get() = this != SIMULTANEOUS

    companion object {
        /** One frame at 60 Hz; used by [FRAME_BY_FRAME]. */
        const val DEFAULT_INTERVAL_MS = 16
        const val MIN_INTERVAL_MS = 1
        const val MAX_INTERVAL_MS = 100

        /** Delay between two mapped keys, in milliseconds; 0 means "send together". */
        fun effectiveIntervalMs(timing: ComboTiming, customIntervalMs: Int): Int = when (timing) {
            SIMULTANEOUS -> 0
            FRAME_BY_FRAME -> DEFAULT_INTERVAL_MS
            CUSTOM_INTERVAL -> customIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        }

        fun fromNameOrSimultaneous(name: String?): ComboTiming =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SIMULTANEOUS
    }
}
