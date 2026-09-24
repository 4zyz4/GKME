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
        gyroX: Float = 0f,
        gyroY: Float = 0f,
        gyroZ: Float = 0f,
        dt: Float = 0.02f,
    ): AccelStick = mapper.update(aX, aY, aZ, base, orientation, inverted, sensitivity, gyroX, gyroY, gyroZ, dt)

    private fun settle(
        mapper: AccelSteeringMapper,
        aX: Float, aY: Float, aZ: Float,
        base: GyroBaseDirection = GyroBaseDirection.VERTICAL,
        orientation: GyroOrientation = GyroOrientation.LANDSCAPE,
        inverted: Boolean = false,
        sensitivity: Int = 100,
    ): AccelStick {
        var result = AccelStick(0f, 0f)
        repeat(60) { result = update(mapper, aX, aY, aZ, base, orientation, inverted, sensitivity) }
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

    // ── 平放 (unchanged original response) ──────────────────────────────

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
