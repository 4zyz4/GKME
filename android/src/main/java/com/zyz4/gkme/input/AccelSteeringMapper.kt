package com.zyz4.gkme.input

import com.zyz4.gkme.model.GyroBaseDirection
import com.zyz4.gkme.model.GyroOrientation
import kotlin.math.abs
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
 *
 * ## Gyro fusion (anti-translation)
 *
 * A raw accelerometer cannot tell gravity apart from the linear acceleration produced by simply
 * translating the phone (moving it through space without rotating it). That linear component
 * tilts the measured "gravity" vector and used to steer the stick on its own.
 *
 * The gravity direction is therefore tracked by integrating the gyroscope (which reads ~0 for any
 * pure translation) and the accelerometer is only allowed to slowly pull that estimate back
 * (`[TAU]` seconds) to cancel gyro drift. The pull is additionally scaled down when the measured
 * acceleration magnitude departs from the tracked gravity magnitude, which is the signature of
 * linear acceleration. The steering response to actual rotations is driven by the gyro, so it
 * stays immediate while translation transients are rejected.
 */
class AccelSteeringMapper {

    // Gyro-integrated gravity direction (unit vector).
    private var dirX = 0f
    private var dirY = 0f
    private var dirZ = 0f

    // Low-passed gravity magnitude, used to detect linear acceleration.
    private var gravityMag = 0f
    private var ready = false

    // Hemisphere tracking for the 竖放 wheel (WheelSimu's `Hp` / sign logic).
    private var lastSide = 1
    private var sideReady = false
    private var turns = 0

    fun reset() {
        dirX = 0f
        dirY = 0f
        dirZ = 0f
        gravityMag = 0f
        ready = false
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
        gyroX: Float = 0f,
        gyroY: Float = 0f,
        gyroZ: Float = 0f,
        dt: Float = DEFAULT_DT,
    ): AccelStick {
        val aMag = sqrt(aX * aX + aY * aY + aZ * aZ)

        if (!ready) {
            if (aMag < MIN_GRAVITY) return AccelStick(0f, 0f)
            dirX = aX / aMag
            dirY = aY / aMag
            dirZ = aZ / aMag
            gravityMag = aMag
            ready = true
        } else {
            val step = dt.coerceIn(0f, MAX_DT)

            // Integrate the gyro to predict where gravity should have moved. dg/dt = -omega x g.
            if (step > 0f) {
                var px = dirX + (gyroZ * dirY - gyroY * dirZ) * step
                var py = dirY + (gyroX * dirZ - gyroZ * dirX) * step
                var pz = dirZ + (gyroY * dirX - gyroX * dirY) * step
                val pm = sqrt(px * px + py * py + pz * pz)
                if (pm > 1e-6f) {
                    px /= pm
                    py /= pm
                    pz /= pm
                }

                // Pull towards the measured gravity direction, but only as fast as [TAU] allows,
                // and even slower when the magnitude looks like linear acceleration.
                if (aMag >= MIN_GRAVITY) {
                    val magError = abs(aMag - gravityMag) / gravityMag.coerceAtLeast(MIN_GRAVITY)
                    val trust = (1f - magError / LINEAR_ACCEL_TOLERANCE).coerceIn(0f, 1f)
                    val alpha = (step / TAU) * trust
                    val ax = aX / aMag
                    val ay = aY / aMag
                    val az = aZ / aMag
                    px += (ax - px) * alpha
                    py += (ay - py) * alpha
                    pz += (az - pz) * alpha
                    val bm = sqrt(px * px + py * py + pz * pz)
                    if (bm > 1e-6f) {
                        px /= bm
                        py /= bm
                        pz /= bm
                    }
                    gravityMag += (aMag - gravityMag) *
                        (step * trust / GRAVITY_TAU).coerceIn(0f, 1f)
                }

                dirX = px
                dirY = py
                dirZ = pz
            }
        }

        val nx = dirX
        val ny = dirY
        val nz = dirZ
        val sens = sensitivity.coerceIn(1, 100) / 100f

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
        /** Assumed update interval when the caller does not provide one (unit tests, fallbacks). */
        const val DEFAULT_DT = 0.02f

        /** Upper bound on the integration step so a stalled loop cannot over-rotate the estimate. */
        const val MAX_DT = 0.05f

        /** Time constant of the accelerometer's pull on the gyro-tracked gravity direction. */
        const val TAU = 1.0f

        /** Time constant of the gravity magnitude low-pass. */
        const val GRAVITY_TAU = 2.0f

        /** Relative magnitude deviation that fully distrusts the accelerometer. */
        const val LINEAR_ACCEL_TOLERANCE = 0.4f

        const val MIN_GRAVITY = 0.1f
    }
}
