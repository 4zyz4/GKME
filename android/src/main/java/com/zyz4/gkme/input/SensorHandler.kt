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

data class SensorData(
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
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
            }
        }
        _sensorData.value = SensorData(_gyroX, _gyroY, _gyroZ, _accelX, _accelY, _accelZ)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
