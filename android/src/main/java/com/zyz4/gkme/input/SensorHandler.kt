package com.zyz4.gkme.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.zyz4.gkme.model.GyroOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class SensorData(
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val rotVecX: Float = 0f,
    val rotVecY: Float = 0f,
    val rotVecZ: Float = 0f,
    val rotVecW: Float = 0f,
    val worldDx: Float = 0f,
    val worldDy: Float = 0f,
)

class SensorHandler(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    var gyroOrientation: GyroOrientation = GyroOrientation.LANDSCAPE
    var isDeviceInverted: Boolean = false

    private var _gyroX = 0f
    private var _gyroY = 0f
    private var _gyroZ = 0f
    private var _accelX = 0f
    private var _accelY = 0f
    private var _accelZ = 0f
    private var _rotVecX = 0f
    private var _rotVecY = 0f
    private var _rotVecZ = 0f
    private var _rotVecW = 0f
    private var _worldDx = 0f
    private var _worldDy = 0f
    private var _lastQuatX = 0f
    private var _lastQuatY = 0f
    private var _lastQuatZ = 0f
    private var _lastQuatW = 1f

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    fun start() {
        val rate = SensorManager.SENSOR_DELAY_GAME
        gyroscope?.let { sensorManager.registerListener(this, it, rate) }
        accelerometer?.let { sensorManager.registerListener(this, it, rate) }
        gameRotationVector?.let { sensorManager.registerListener(this, it, rate) }
        rotationVector?.let { sensorManager.registerListener(this, it, rate) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun resetOrientation() {
        _lastQuatX = 0f
        _lastQuatY = 0f
        _lastQuatZ = 0f
        _lastQuatW = 1f
    }

    private fun remapToOrientation(
        rawX: Float, rawY: Float, rawZ: Float,
    ): Triple<Float, Float, Float> {
        val base = when (gyroOrientation) {
            GyroOrientation.LANDSCAPE -> Triple(-rawY, rawZ, -rawX)
            GyroOrientation.PORTRAIT -> Triple(rawX, rawZ, -rawY)
            GyroOrientation.PORTRAIT_INVERTED -> Triple(-rawX, rawZ, rawY)
        }
        return if (isDeviceInverted) {
            Triple(-base.first, base.second, -base.third)
        } else {
            base
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val (rx, ry, rz) = remapToOrientation(
                    event.values[0], event.values[1], event.values[2]
                )
                _gyroX = rx
                _gyroY = ry
                _gyroZ = rz
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (ax, ay, az) = remapToOrientation(
                    event.values[0], event.values[1], event.values[2]
                )
                _accelX = ax
                _accelY = ay
                _accelZ = az

                computeWorldDelta(_gyroX, _gyroY, _gyroZ, _accelX, _accelY, _accelZ)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val quat = FloatArray(4)
                SensorManager.getOrientation(rotationMatrix, quat)
                _rotVecX = quat[0]
                _rotVecY = quat[1]
                _rotVecZ = quat[2]
                _rotVecW = quat[3]

                val lastQ = floatArrayOf(_lastQuatX, _lastQuatY, _lastQuatZ, _lastQuatW)
                val nowQ = orientationToQuat(quat[0], quat[1], quat[2])

                if (_lastQuatW != 1f || _lastQuatX != 0f || _lastQuatY != 0f || _lastQuatZ != 0f) {
                    val dq = quatMultiply(nowQ, quatConjugate(lastQ))
                    val norm = sqrt(dq[0] * dq[0] + dq[1] * dq[1] + dq[2] * dq[2])
                    val angle = 2f * atan2(norm, dq[3])

                    val axisX = if (norm > 0.001f) dq[0] / norm else 0f
                    val axisY = if (norm > 0.001f) dq[1] / norm else 0f
                    val axisZ = if (norm > 0.001f) dq[2] / norm else 0f

                    _rotVecX = angle * axisY
                    _rotVecY = angle * axisX
                    _rotVecZ = angle * axisZ
                }

                _lastQuatX = nowQ[0]
                _lastQuatY = nowQ[1]
                _lastQuatZ = nowQ[2]
                _lastQuatW = nowQ[3]
            }
        }
        _sensorData.value = SensorData(
            _gyroX, _gyroY, _gyroZ,
            _accelX, _accelY, _accelZ,
            _rotVecX, _rotVecY, _rotVecZ, _rotVecW,
            _worldDx, _worldDy,
        )
    }

    private fun computeWorldDelta(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
    ) {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        if (mag < 0.1f) {
            _worldDx = 0f
            _worldDy = 0f
            return
        }

        val gravX = ax / mag
        val gravY = ay / mag
        val gravZ = az / mag

        val posY = max(0f, gravY)
        val negY = max(0f, -gravY)
        val posX = max(0f, gravX)
        val negX = max(0f, -gravX)
        val negZ = max(0f, -gravZ)

        val total = (posY + negY + posX + negX + negZ).coerceAtLeast(0.001f)

        val yawDx = gy
        val yawDy = gx
        val rollDx = -gz
        val rollDy = gx
        val swapNegYDx = gx
        val swapNegYDy = -gy
        val swapNegXDx = -gx
        val swapNegXDy = gy

        _worldDx = (posY * yawDx + negZ * rollDx + posX * swapNegYDx + negX * swapNegXDx) / total
        _worldDy = (posY * yawDy + negZ * rollDy + posX * swapNegYDy + negX * swapNegXDy) / total
    }

    private fun orientationToQuat(yaw: Float, pitch: Float, roll: Float): FloatArray {
        val cosYaw = cos(yaw / 2f)
        val sinYaw = sin(yaw / 2f)
        val cosPitch = cos(pitch / 2f)
        val sinPitch = sin(pitch / 2f)
        val cosRoll = cos(roll / 2f)
        val sinRoll = sin(roll / 2f)
        return floatArrayOf(
            sinYaw * cosPitch * cosRoll - cosYaw * sinPitch * sinRoll,
            cosYaw * sinPitch * cosRoll + sinYaw * cosPitch * sinRoll,
            cosYaw * cosPitch * sinRoll - sinYaw * sinPitch * cosRoll,
            cosYaw * cosPitch * cosRoll + sinYaw * sinPitch * sinRoll,
        )
    }

    private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        )
    }

    private fun quatConjugate(q: FloatArray): FloatArray {
        return floatArrayOf(-q[0], -q[1], -q[2], q[3])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}