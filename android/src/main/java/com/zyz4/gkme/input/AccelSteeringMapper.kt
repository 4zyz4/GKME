package com.zyz4.gkme.input

import com.zyz4.gkme.model.GyroBaseDirection
import com.zyz4.gkme.model.GyroOrientation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
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
 * Both stick axes behave like real multi-turn wheels in either hold: rotating past ±90° keeps
 * increasing instead of reversing. The in-plane angle proxy stays `90°·(a/g)` (the same response
 * as the original `aX`/`aY`/`aZ`), while each axis' half-turn is tracked continuously from the gyro
 * about its own rotation axis (with a slow accelerometer correction), so tilting about the
 * perpendicular axis cannot flip the direction. X rotates about the screen normal in 竖放 and
 * about the in-plane z axis in 平放; Y rotates about the in-plane x axis in both. When no gyro data
 * is available the 竖放 X axis falls back to the sign of the out-of-plane gravity component
 * (`aZ`); the other axes stay a plain single-turn response. Within ±90° the output is identical to
 * the original.
 *
 * [sensitivity] (1..100) scales the output amplitude exactly as before, which also means it is
 * the full-lock angle: 100% -> full lock at 90°, 20% -> full lock at 450°.
 *
 * [deadZone] and [reverseDeadZone] (0..100) apply the same radial shaping as the on-screen
 * joystick and the gyro path: a deflection below [deadZone]% of the full-lock angle produces no
 * output, and [reverseDeadZone]% raises the output floor just past the dead zone. Both are applied
 * before [sensitivity] so the dead zone tracks the configured full-lock angle.
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

    // Continuous per-axis wheel trackers (degrees, code convention `proxy = -sin(angle)`) so
    // rotating past ±90° keeps accumulating instead of reversing. Each axis is tracked from the
    // gyro about its own rotation axis, which the perpendicular tilt does not affect.
    private class AxisWheel {
        var angleDeg = 0f
        var angleReady = false
        var lastSide = 1
        var sideReady = false
        var turns = 0

        fun reset() {
            angleDeg = 0f
            angleReady = false
            lastSide = 1
            sideReady = false
            turns = 0
        }
    }

    private val wheelX = AxisWheel()
    private val wheelY = AxisWheel()
    private var gyroSeen = false

    fun reset() {
        dirX = 0f
        dirY = 0f
        dirZ = 0f
        gravityMag = 0f
        ready = false
        wheelX.reset()
        wheelY.reset()
        gyroSeen = false
    }

    fun update(
        aX: Float,
        aY: Float,
        aZ: Float,
        baseDirection: GyroBaseDirection,
        orientation: GyroOrientation,
        inverted: Boolean,
        sensitivity: Int,
        deadZone: Int = 0,
        reverseDeadZone: Int = 0,
        gyroX: Float = 0f,
        gyroY: Float = 0f,
        gyroZ: Float = 0f,
        dt: Float = DEFAULT_DT,
    ): AccelStick {
        val aMag = sqrt(aX * aX + aY * aY + aZ * aZ)
        val step = dt.coerceIn(0f, MAX_DT)

        if (abs(gyroX) + abs(gyroY) + abs(gyroZ) > GYRO_EPS) gyroSeen = true

        if (!ready) {
            if (aMag < MIN_GRAVITY) return AccelStick(0f, 0f)
            dirX = aX / aMag
            dirY = aY / aMag
            dirZ = aZ / aMag
            gravityMag = aMag
            ready = true
        } else {
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

        val rawX: Float
        val rawY: Float
        if (baseDirection == GyroBaseDirection.VERTICAL) {
            // 竖放: both axes act as multi-turn wheels. X rotates about the screen normal (gyroY),
            // Y about the in-plane x axis (gyroX); the shared neutral-gravity reference is aZ.
            val refZ = if (orientation == GyroOrientation.LANDSCAPE || !inverted) -1f else 1f
            val (turnsX, sideX) = resolveWheel(
                wheelX, nx, ref = nz, refSign = refZ,
                rateDeg = refZ * gyroY * RAD2DEG, step = step, accelFallback = true,
            )
            val (turnsY, sideY) = resolveWheel(
                wheelY, ny, ref = nz, refSign = refZ,
                rateDeg = -refZ * gyroX * RAD2DEG, step = step, accelFallback = false,
            )
            rawX = -(180f * turnsX + 90f * nx * sideX) / 90f
            rawY = -(180f * turnsY + 90f * ny * sideY) / 90f
        } else {
            // 平放: X rotates about the in-plane z axis (gyroZ), Y about x (gyroX); the shared
            // neutral-gravity reference is aY.
            val (turnsX, sideX) = resolveWheel(
                wheelX, nx, ref = ny, refSign = 1f,
                rateDeg = -gyroZ * RAD2DEG, step = step, accelFallback = false,
            )
            val (turnsY, sideY) = resolveWheel(
                wheelY, nz, ref = ny, refSign = 1f,
                rateDeg = gyroX * RAD2DEG, step = step, accelFallback = false,
            )
            rawX = -(180f * turnsX + 90f * nx * sideX) / 90f
            rawY = -(180f * turnsY + 90f * nz * sideY) / 90f
        }

        // Apply the dead zone / reverse dead zone (radial, mirrors the joystick and gyro
        // behaviour). The output is normalized by one full deflection (|output| == 1 at the
        // full-lock angle) so a small tilt below the dead zone produces no output.
        val dz = (deadZone / 100f).coerceIn(0f, 0.99f)
        val rdz = (reverseDeadZone / 100f).coerceIn(0f, 0.99f)
        var outX = rawX
        var outY = rawY
        if (dz > 0f || rdz > 0f) {
            val mag = sqrt(rawX * rawX + rawY * rawY)
            if (mag > 0f) {
                val normalized = mag.coerceIn(0f, 1f)
                val afterDeadZone = if (normalized <= dz) 0f
                                    else (normalized - dz) / (1f - dz)
                val afterReverseDeadZone = if (afterDeadZone == 0f) rdz
                                           else afterDeadZone * (1f - rdz) + rdz
                val scale = afterReverseDeadZone / mag
                outX = rawX * scale
                outY = rawY * scale
            }
        }

        return AccelStick(
            x = (outX * sens).coerceIn(-1f, 1f),
            y = (outY * sens).coerceIn(-1f, 1f),
        )
    }

    /**
     * Resolves `(turns, side)` for one stick axis.
     *
     * [proxy] is the gravity component tracking the wheel angle (`proxy = -sin(angle)`), [ref] the
     * neutral-gravity component and [refSign] its sign at rest (accel-only fallback). [rateDeg] is
     * the tracked angle rate (degrees/second) from the gyro about this axis' rotation axis, so a
     * perpendicular tilt cannot flip the direction. When no gyro is available, only [accelFallback]
     * axes use the legacy out-of-plane sign; the rest stay a plain single-turn response.
     */
    private fun resolveWheel(
        wheel: AxisWheel,
        proxy: Float,
        ref: Float,
        refSign: Float,
        rateDeg: Float,
        step: Float,
        accelFallback: Boolean,
    ): Pair<Int, Int> {
        if (gyroSeen) {
            if (!wheel.angleReady) {
                wheel.angleDeg = -asin(proxy.coerceIn(-1f, 1f)) * RAD2DEG
                wheel.angleReady = true
            }
            wheel.angleDeg += rateDeg * step
            // Slow accelerometer correction to cancel gyro drift. The accelerometer angle is
            // ambiguous between `measured` and `180 - measured`, so correct towards whichever
            // candidate is closer to the gyro-tracked angle.
            val measured = -asin(proxy.coerceIn(-1f, 1f)) * RAD2DEG
            var rep = measured
            while (rep - wheel.angleDeg > 180f) rep -= 360f
            while (wheel.angleDeg - rep > 180f) rep += 360f
            var alt = 180f - measured
            while (alt - wheel.angleDeg > 180f) alt -= 360f
            while (wheel.angleDeg - alt > 180f) alt += 360f
            if (abs(alt - wheel.angleDeg) < abs(rep - wheel.angleDeg)) rep = alt
            wheel.angleDeg += (rep - wheel.angleDeg) *
                (step / WHEEL_TAU).coerceIn(0f, MAX_WHEEL_CORRECTION)
            val side = if (cos(wheel.angleDeg * DEG2RAD) >= 0f) 1 else -1
            val turns = -floor((wheel.angleDeg + 90f) / 180f).toInt()
            return turns to side
        }

        if (!accelFallback) return 0 to 1

        // Legacy fallback (no gyro data): infer the half-turn from the out-of-plane gravity sign.
        val side = if (ref * refSign >= 0f) 1 else -1
        if (!wheel.sideReady) {
            wheel.lastSide = side
            wheel.sideReady = true
        } else if (side != wheel.lastSide) {
            val yDeg = 90f * proxy
            if (wheel.lastSide > 0 && side < 0) {
                wheel.turns += if (yDeg < 0f) -1 else 1
            } else if (wheel.lastSide < 0 && side > 0) {
                wheel.turns += if (yDeg < 0f) 1 else -1
            }
            wheel.lastSide = side
        }
        return wheel.turns to side
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

        /** Gyro magnitude below which the sensor is treated as absent (accel-only fallback). */
        const val GYRO_EPS = 1e-3f

        /** Time constant of the accelerometer's pull on the gyro-tracked wheel angle. */
        const val WHEEL_TAU = 2.0f

        /** Cap on the per-frame wheel-angle correction so pitch cannot skew the angle. */
        const val MAX_WHEEL_CORRECTION = 0.05f

        const val RAD2DEG = (180.0 / PI).toFloat()
        const val DEG2RAD = (PI / 180.0).toFloat()
    }
}
