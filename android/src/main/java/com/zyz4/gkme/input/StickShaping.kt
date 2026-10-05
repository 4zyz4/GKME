package com.zyz4.gkme.input

import kotlin.math.sqrt

/**
 * Radial shaping for an analog stick: dead zone -> anti (reverse) dead zone -> sensitivity curve,
 * applied to the vector magnitude while keeping its direction.
 *
 * This mirrors the on-screen [com.zyz4.gkme.view.JoystickView] pipeline and [AccelSteeringMapper]:
 * an input below the dead zone produces no output, the anti dead zone raises the output floor just
 * past the dead zone, and the curve remaps the remaining range. The result is clamped to a circle
 * of radius [fullScale] so diagonal input can never exceed full deflection.
 */
object StickShaping {

    /** True when no shaping is configured, i.e. the vector should pass through unchanged. */
    fun isDefault(deadZone: Int, reverseDeadZone: Int, curve: List<Float>?): Boolean =
        deadZone <= 0 && reverseDeadZone <= 0 && curve.isNullOrEmpty()

    /**
     * Applies the configured shaping to `(x, y)` where [fullScale] is the magnitude that maps to
     * `1.0`. Returns the shaped vector (clamped to a circle of radius [fullScale]).
     */
    fun apply(
        x: Float,
        y: Float,
        fullScale: Float,
        deadZone: Int,
        reverseDeadZone: Int,
        curve: List<Float>?,
    ): Pair<Float, Float> {
        if (fullScale <= 0f) return x to y
        val mag = sqrt(x * x + y * y)
        if (mag < 1e-6f) return 0f to 0f

        if (isDefault(deadZone, reverseDeadZone, curve)) {
            if (mag <= fullScale) return x to y
            val scale = fullScale / mag
            return x * scale to y * scale
        }

        val normalized = (mag / fullScale).coerceIn(0f, 1f)
        val dz = (deadZone / 100f).coerceIn(0f, 0.99f)
        val rdz = (reverseDeadZone / 100f).coerceIn(0f, 0.99f)
        // 死区优先：死区内恒输出 0，反死区只在死区之外抬升下限。
        val afterDeadZone = if (normalized <= dz) 0f else (normalized - dz) / (1f - dz)
        val afterReverseDeadZone = if (afterDeadZone == 0f) 0f else afterDeadZone * (1f - rdz) + rdz
        val shaped = SensitivityCurve.evaluate(curve, afterReverseDeadZone)
        val scale = shaped * fullScale / mag
        return x * scale to y * scale
    }
}
