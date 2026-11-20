package com.zyz4.gkme.input

import kotlin.math.abs

internal class JoyConStickDecoder(private val side: JoyConSide) {
    data class Sample(val x: Short, val y: Short, val usesHat: Boolean = false)

    private var analogMoved = false

    fun decode(analogX: Short, analogY: Short, hatX: Float, hatY: Float): Sample {
        // Report 0x3F has placeholder analog fields at center. Once analog input has
        // actually moved, keep using it even at center, where the hat may be a D-pad.
        if (abs(analogX.toInt()) >= ANALOG_MOVEMENT || abs(analogY.toInt()) >= ANALOG_MOVEMENT) {
            analogMoved = true
        }
        if (analogMoved || (abs(hatX) < HAT_THRESHOLD && abs(hatY) < HAT_THRESHOLD)) {
            return Sample(analogX, analogY)
        }

        // The simple report's hat directions are relative to each sideways Joy-Con.
        return when (side) {
            JoyConSide.LEFT -> Sample(hatAxis(-hatY), hatAxis(hatX), usesHat = true)
            JoyConSide.RIGHT -> Sample(hatAxis(hatY), hatAxis(-hatX), usesHat = true)
        }
    }

    private fun hatAxis(value: Float): Short = when {
        value >= HAT_THRESHOLD -> Short.MAX_VALUE
        value <= -HAT_THRESHOLD -> -Short.MAX_VALUE
        else -> 0
    }.toShort()

    private companion object {
        const val ANALOG_MOVEMENT = 4096
        const val HAT_THRESHOLD = 0.5f
    }
}
