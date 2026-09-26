package com.zyz4.gkme.input

import com.zyz4.gkme.model.GyroBaseDirection
import com.zyz4.gkme.model.GyroOrientation
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * JVM tests for the accelerometer -> steering mapper. Gravity samples are expressed in the
 * already-remapped frame produced by [SensorHandler.remapToOrientation] (y = screen normal,
 * x/z = screen plane), which is what the mapper receives at runtime.
 */
class AccelSteeringMapperTest {

    private val g = 9.81f

    private fun update(
        mapper: AccelSteeringMapper,
        aX: Float, aY: Float, aZ: Float,
        base: GyroBaseDirection = GyroBaseDirection.VERTICAL,
        orientation: GyroOrientation = GyroOrientation.LANDSCAPE,
        inverted: Boolean = false,
        sensitivity: Int = 100,
        deadZone: Int = 0,
        reverseDeadZone: Int = 0,
        gyroX: Float = 0f,
        gyroY: Float = 0f,
        gyroZ: Float = 0f,
        dt: Float = 0.02f,
    ): AccelStick = mapper.update(
        aX, aY, aZ, base, orientation, inverted, sensitivity,
        deadZone = deadZone, reverseDeadZone = reverseDeadZone,
        gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ, dt = dt,
    )

    private fun settle(
        mapper: AccelSteeringMapper,
        aX: Float, aY: Float, aZ: Float,
        base: GyroBaseDirection = GyroBaseDirection.VERTICAL,
        orientation: GyroOrientation = GyroOrientation.LANDSCAPE,
        inverted: Boolean = false,
        sensitivity: Int = 100,
        deadZone: Int = 0,
        reverseDeadZone: Int = 0,
    ): AccelStick {
        var result = AccelStick(0f, 0f)
        repeat(60) {
            result = update(
                mapper, aX, aY, aZ, base, orientation, inverted, sensitivity,
                deadZone = deadZone, reverseDeadZone = reverseDeadZone,
            )
        }
        return result
    }

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.03f, msg: String = "") {
        assertTrue("$msg expected≈$expected but was $actual", abs(expected - actual) <= tolerance)
    }

    // ── 竖放 (original axis sources, extended past ±90°) ────────────────

    @Test
    fun verticalLandscape_upright_isCentered() {
        val stick = settle(AccelSteeringMapper(), 0f, 0f, -g)
        assertClose(0f, stick.x, msg = "steering")
        assertClose(0f, stick.y, msg = "forward")
    }

    @Test
    fun verticalLandscape_30deg_matchesOriginalAxisResponse() {
        // Original: X = -(aX/mag) * sens. At 30° roll, aX/mag = -sin30 = -0.5 -> X = +0.5.
        val phi = (30.0 * PI / 180.0).toFloat()
        val stick = settle(AccelSteeringMapper(), -g * sin(phi), 0f, -g * cos(phi))
        assertClose(0.5f, stick.x)
    }

    @Test
    fun verticalLandscape_90deg_fullDeflection() {
        val stick = settle(AccelSteeringMapper(), -g, 0f, 0f)
        assertClose(1f, stick.x)
    }

    @Test
    fun verticalLandscape_sensitivityScalesAmplitude() {
        val stick = settle(AccelSteeringMapper(), -g, 0f, 0f, sensitivity = 20)
        assertClose(0.2f, stick.x)
    }

    @Test
    fun verticalLandscape_rotationUnwrapsPast90Degrees() {
        val mapper = AccelSteeringMapper()
        var step = 0
        while (step <= 190) {
            val phi = (step.toDouble() * PI / 180.0).toFloat()
            repeat(150) { update(mapper, -g * sin(phi), 0f, -g * cos(phi), sensitivity = 20) }
            step += 10
        }
        val stick = update(
            mapper,
            -g * sin((190.0 * PI / 180.0).toFloat()), 0f,
            -g * cos((190.0 * PI / 180.0).toFloat()),
            sensitivity = 20,
        )
        // 190° / 450° ≈ 0.42, but still increasing (not collapsing back towards 0).
        assertClose(0.435f, stick.x, tolerance = 0.03f)
    }

    @Test
    fun verticalLandscape_outOfPlaneGravityDrivesForwardAxis() {
        val stick = settle(AccelSteeringMapper(), 0f, g * 0.5f, -g * 0.866f)
        assertClose(-0.5f, stick.y)
    }

    @Test
    fun verticalLandscape_pitchPast90DoesNotFlipSteering() {
        val mapper = AccelSteeringMapper()
        val dt = 0.02f

        // Turn the wheel to +45° using the screen-normal gyro.
        val wheelRate = (45.0 * PI / 180.0 / 1.0).toFloat() // rad/s -> 45° after 1s
        var phi = 0f
        var stick = AccelStick(0f, 0f)
        repeat(50) {
            phi += wheelRate * dt
            stick = update(
                mapper,
                -g * sin(phi), 0f, -g * cos(phi),
                gyroY = -wheelRate, dt = dt,
            )
        }
        assertClose(sin(phi), stick.x, tolerance = 0.05f, msg = "wheel turned")

        // Pitch forward through 120° (past the old aZ hemisphere boundary) with the wheel fixed.
        val pitchRate = (120.0 * PI / 180.0 / 1.2).toFloat() // rad/s
        var theta = 0f
        repeat(60) {
            theta += pitchRate * dt
            stick = update(
                mapper,
                -g * sin(phi),
                -g * sin(theta) * cos(phi),
                -g * cos(theta) * cos(phi),
                gyroX = pitchRate, dt = dt,
            )
        }
        // Steering must keep tracking the wheel angle instead of jumping when the pitch crosses
        // the old hemisphere boundary.
        assertClose(sin(phi), stick.x, tolerance = 0.1f, msg = "steering after pitch")
    }

    @Test
    fun verticalLandscape_gyroTrackedUnwrapMatchesAccel() {
        val mapper = AccelSteeringMapper()
        val dt = 0.02f
        val dPhi = (0.2 * PI / 180.0).toFloat()
        val rate = dPhi / dt
        var phi = 0f
        var stick = AccelStick(0f, 0f)
        repeat(950) { // 950 * 0.2° = 190°
            phi += dPhi
            stick = update(
                mapper,
                -g * sin(phi), 0f, -g * cos(phi),
                sensitivity = 20, gyroY = -rate, dt = dt,
            )
        }
        // Same as the accelerometer-only unwrap test: still increasing past ±90°.
        assertClose(0.435f, stick.x, tolerance = 0.03f)
    }

    @Test
    fun verticalLandscape_yRotationUnwraps() {
        val mapper = AccelSteeringMapper()
        val dt = 0.02f
        val dPsi = (0.2 * PI / 180.0).toFloat()
        val rate = dPsi / dt
        var psi = 0f
        var stick = AccelStick(0f, 0f)
        repeat(950) { // 190° about the in-plane x axis
            psi += dPsi
            stick = update(
                mapper,
                0f, -g * sin(psi), -g * cos(psi),
                sensitivity = 20, gyroX = rate, dt = dt,
            )
        }
        assertClose(0.435f, stick.y, tolerance = 0.03f, msg = "vertical Y unwrap")
    }

    @Test
    fun flat_xRotationUnwraps() {
        val mapper = AccelSteeringMapper()
        val dt = 0.02f
        val dPhi = (0.2 * PI / 180.0).toFloat()
        val rate = dPhi / dt
        var phi = 0f
        var stick = AccelStick(0f, 0f)
        repeat(950) { // 190° about the in-plane z axis
            phi += dPhi
            stick = update(
                mapper,
                g * sin(phi), g * cos(phi), 0f,
                base = GyroBaseDirection.HORIZONTAL, sensitivity = 20,
                gyroZ = rate, dt = dt,
            )
        }
        assertClose(-0.435f, stick.x, tolerance = 0.03f, msg = "flat X unwrap")
    }

    @Test
    fun flat_yRotationUnwraps() {
        val mapper = AccelSteeringMapper()
        val dt = 0.02f
        val dPsi = (0.2 * PI / 180.0).toFloat()
        val rate = dPsi / dt
        var psi = 0f
        var stick = AccelStick(0f, 0f)
        repeat(950) { // 190° about the in-plane x axis
            psi += dPsi
            stick = update(
                mapper,
                0f, g * cos(psi), -g * sin(psi),
                base = GyroBaseDirection.HORIZONTAL, sensitivity = 20,
                gyroX = rate, dt = dt,
            )
        }
        assertClose(0.435f, stick.y, tolerance = 0.03f, msg = "flat Y unwrap")
    }

    @Test
    fun verticalPortraitUpright_isCentered() {
        val stick = settle(AccelSteeringMapper(), 0f, 0f, -g, orientation = GyroOrientation.PORTRAIT)
        assertClose(0f, stick.x, msg = "portrait steering")
    }

    @Test
    fun verticalPortraitInvertedUpright_isCentered() {
        val stick = settle(AccelSteeringMapper(), 0f, 0f, g,
            orientation = GyroOrientation.PORTRAIT, inverted = true)
        assertClose(0f, stick.x, msg = "inverted portrait steering")
    }

    // ── 平放 ────────────────────────────────────────────────────────────

    @Test
    fun flatScreenUp_isCentered() {
        val stick = settle(AccelSteeringMapper(), 0f, g, 0f, base = GyroBaseDirection.HORIZONTAL)
        assertClose(0f, stick.x, msg = "flat steering")
        assertClose(0f, stick.y, msg = "flat forward")
    }

    @Test
    fun flatLeftTilt_matchesOriginalAxisResponse() {
        val phi = (30.0 * PI / 180.0).toFloat()
        val stick = settle(AccelSteeringMapper(), g * sin(phi), g * cos(phi), 0f,
            base = GyroBaseDirection.HORIZONTAL)
        assertClose(-0.5f, stick.x)
    }

    @Test
    fun reset_recentersAfterTilt() {
        val mapper = AccelSteeringMapper()
        settle(mapper, -g, 0f, 0f)
        mapper.reset()
        val stick = settle(mapper, 0f, 0f, -g)
        assertClose(0f, stick.x)
    }

    // ── 死区 / 反死区 ──────────────────────────────────────────────────

    @Test
    fun deadZone_suppressesSmallTilt() {
        // 30° roll gives raw 0.5; a 60% dead zone must zero it out.
        val phi = (30.0 * PI / 180.0).toFloat()
        val stick = settle(
            AccelSteeringMapper(), -g * sin(phi), 0f, -g * cos(phi),
            deadZone = 60,
        )
        assertClose(0f, stick.x, msg = "inside dead zone")
    }

    @Test
    fun deadZone_fullDeflectionStillReachesFullOutput() {
        val stick = settle(AccelSteeringMapper(), -g, 0f, 0f, deadZone = 60)
        assertClose(1f, stick.x, msg = "full lock outside dead zone")
    }

    @Test
    fun deadZone_remapsOutsideRange() {
        // 45° roll -> raw sin45 = 0.7071; 50% dead zone remaps to (0.7071-0.5)/0.5 = 0.4142.
        val phi = (45.0 * PI / 180.0).toFloat()
        val stick = settle(
            AccelSteeringMapper(), -g * sin(phi), 0f, -g * cos(phi),
            deadZone = 50,
        )
        assertClose(0.4142f, stick.x, tolerance = 0.01f)
    }

    @Test
    fun reverseDeadZone_raisesFloorOutsideDeadZone() {
        // 30° roll -> raw 0.5; 50% reverse dead zone maps 0.5 to 0.5*(1-0.5)+0.5 = 0.75.
        val phi = (30.0 * PI / 180.0).toFloat()
        val stick = settle(
            AccelSteeringMapper(), -g * sin(phi), 0f, -g * cos(phi),
            reverseDeadZone = 50,
        )
        assertClose(0.75f, stick.x, tolerance = 0.01f)
    }

    // ── 陀螺仪融合 (translation must not steer) ─────────────────────────

    @Test
    fun translationImpulse_isRejected() {
        val mapper = AccelSteeringMapper()
        // At rest, upright (gravity along -z).
        repeat(60) { update(mapper, 0f, 0f, -g) }

        // 0.2s of sideways translation: the gyro reads ~0 while linear acceleration tilts the
        // measured vector by ~25°. Steering should stay near center instead of tracking the tilt.
        val phi = (25.0 * PI / 180.0).toFloat()
        var stick = AccelStick(0f, 0f)
        repeat(10) { stick = update(mapper, -g * sin(phi), 0f, -g * cos(phi)) }

        assertTrue("translation steered the stick: ${stick.x}", abs(stick.x) < 0.15f)
    }

    @Test
    fun gyroRotation_tracksTilt() {
        val mapper = AccelSteeringMapper()
        repeat(60) { update(mapper, 0f, 0f, -g) }

        // Roll the phone about the screen normal at 1 rad/s for 0.5s (phi -> 0.5rad ≈ 28.6°).
        // gyroY = -dphi/dt for this rotation direction; accel is fed consistently.
        val dt = 0.02f
        val phiDot = 1f
        var phi = 0f
        var stick = AccelStick(0f, 0f)
        repeat(25) {
            phi += phiDot * dt
            stick = update(
                mapper,
                -g * sin(phi), 0f, -g * cos(phi),
                gyroY = -phiDot, dt = dt,
            )
        }
        assertClose(sin(0.5f), stick.x, tolerance = 0.05f, msg = "gyro-tracked steering")
    }
}
