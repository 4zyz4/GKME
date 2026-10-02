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
    val worldDx: Float = 0f,
    val worldDy: Float = 0f,
)

class SensorHandler(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var gyroOrientation: GyroOrientation = GyroOrientation.LANDSCAPE
    var isDeviceInverted: Boolean = false

    private var _gyroX = 0f
    private var _gyroY = 0f
    private var _gyroZ = 0f
    private var _accelX = 0f
    private var _accelY = 0f
    private var _accelZ = 0f
    private var _worldDx = 0f
    private var _worldDy = 0f

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    fun start() {
        val rate = SensorManager.SENSOR_DELAY_GAME
        gyroscope?.let { sensorManager.registerListener(this, it, rate) }
        accelerometer?.let { sensorManager.registerListener(this, it, rate) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
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

    companion object {
        fun computeWorldDelta(
            gx: Float, gy: Float, gz: Float,
            ax: Float, ay: Float, az: Float,
        ): Pair<Float, Float> {
            val mag = sqrt(ax * ax + ay * ay + az * az)
            if (mag < 0.1f) {
                return 0f to 0f
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

            val worldDx = (posY * yawDx + negZ * rollDx + posX * swapNegYDx + negX * swapNegXDx) / total
            val worldDy = (posY * yawDy + negZ * rollDy + posX * swapNegYDy + negX * swapNegXDy) / total
            return worldDx to worldDy
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

                val (wdx, wdy) = computeWorldDelta(_gyroX, _gyroY, _gyroZ, _accelX, _accelY, _accelZ)
                _worldDx = wdx
                _worldDy = wdy
            }
        }
        _sensorData.value = SensorData(
            _gyroX, _gyroY, _gyroZ,
            _accelX, _accelY, _accelZ,
            _worldDx, _worldDy,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
