package com.zyz4.gkme.input

import com.zyz4.gkme.model.GyroBaseDirection
import com.zyz4.gkme.model.GyroOrientation
import kotlin.math.sqrt

/** Normalized output of [AccelSteeringMapper], both components in `[-1, 1]`. */
data class AccelStick(val x: Float, val y: Float)

/**
 * Accelerometer -> stick mapping. The axis sources, signs and the sensitivity scaling are the
 * same as the original implementation:
 *
 * - 竖放 ([GyroBaseDirection.VERTICAL]): X = -aX, Y = -aY (normalized by gravity magnitude)
 * - 平放 ([GyroBaseDirection.HORIZONTAL]): X = -aX, Y = -aZ (normalized by gravity magnitude)
 *
 * The only behavioural addition is on the 竖放 horizontal axis: the phone behaves like a real
 * steering wheel, so rotating past ±90° must keep increasing instead of reversing. This uses
 * WheelSimu's hemisphere accumulation: the in-plane angle proxy stays `90°·(aX/g)` (the same
 * response as the original `aX`), while the perpendicular in-plane component (`aZ`) tracks which
 * half-turn the phone is in. Within ±90° the output is identical to the original.
 *
 * [sensitivity] (1..100) scales the output amplitude exactly as before, which also means it is
 * the full-lock angle: 100% -> full lock at 90°, 20% -> full lock at 450°.
 */
class AccelSteeringMapper {

    // Low-pass filtered gravity; light smoothing only, the axis response is unchanged.
    private var filterX = 0f
    private var filterY = 0f
    private var filterZ = 0f
    private var filterReady = false

    // Hemisphere tracking for the 竖放 wheel (WheelSimu's `Hp` / sign logic).
    private var lastSide = 1
    private var sideReady = false
    private var turns = 0

    fun reset() {
        filterX = 0f
        filterY = 0f
        filterZ = 0f
        filterReady = false
        lastSide = 1
        sideReady = false
        turns = 0
    }

    fun update(
        aX: Float,
        aY: Float,
        aZ: Float,
        baseDirection: GyroBaseDirection,
        orientation: GyroOrientation,
        inverted: Boolean,
        sensitivity: Int,
    ): AccelStick {
        if (!filterReady) {
            filterX = aX
            filterY = aY
            filterZ = aZ
            filterReady = true
        } else {
            filterX += (aX - filterX) * SMOOTH_ALPHA
            filterY += (aY - filterY) * SMOOTH_ALPHA
            filterZ += (aZ - filterZ) * SMOOTH_ALPHA
        }

        val mag = sqrt(filterX * filterX + filterY * filterY + filterZ * filterZ)
        if (mag < MIN_GRAVITY) return AccelStick(0f, 0f)

        val sens = sensitivity.coerceIn(1, 100) / 100f
        val nx = filterX / mag
        val ny = filterY / mag
        val nz = filterZ / mag

        return if (baseDirection == GyroBaseDirection.VERTICAL) {
            // Horizontal axis: original aX response, extended across half-turns.
            val refZ = if (orientation == GyroOrientation.LANDSCAPE || !inverted) -1f else 1f
            val side = if (nz * refZ >= 0f) 1 else -1
            val yDeg = 90f * nx
            if (!sideReady) {
                lastSide = side
                sideReady = true
            } else if (side != lastSide) {
                if (lastSide > 0 && side < 0) {
                    turns += if (yDeg < 0f) -1 else 1
                } else if (lastSide < 0 && side > 0) {
                    turns += if (yDeg < 0f) 1 else -1
                }
                lastSide = side
            }
            val wheelDeg = 180f * turns + yDeg * side
            AccelStick(
                x = (-(wheelDeg / 90f) * sens).coerceIn(-1f, 1f),
                y = (-ny * sens).coerceIn(-1f, 1f),
            )
        } else {
            // 平放: exactly the original response.
            AccelStick(
                x = (-nx * sens).coerceIn(-1f, 1f),
                y = (-nz * sens).coerceIn(-1f, 1f),
            )
        }
    }

    private companion object {
        const val SMOOTH_ALPHA = 0.5f
        const val MIN_GRAVITY = 0.1f
    }
}
