package com.zyz4.gamepademu.model

data class GamepadState(
    val buttons: UInt = 0u,
    val leftStickX: Short = 0,
    val leftStickY: Short = 0,
    val rightStickX: Short = 0,
    val rightStickY: Short = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val dpad: Int = 0,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val touchpadX: Int = 0,
    val touchpadY: Int = 0,
    val touchpadTouch: Boolean = false,
    val touchpadClick: Boolean = false,
    val touches: List<TouchPoint> = emptyList(),
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    // Mouse fields for WiFi/UDP mode
    val mouseButtons: Int = 0,
    val mouseDx: Short = 0,
    val mouseDy: Short = 0,
    val mouseWheel: Short = 0,
    val mousePan: Short = 0,
    // Keyboard fields for WiFi/UDP mode
    val keyboardModifier: UInt = 0u,
    val keyboardKeys: List<UInt> = emptyList(),
) {
    companion object {
        // Bits 0-5 = A/B/X/Y/LB/RB
        // Bits 6-7 = LT/RT (digital triggers)
        // Bits 8-11 = SELECT/START/L3/R3
        // Bits 12-15 = DPAD Up/Down/Left/Right
        // Bit 16 = HOME
        const val A = 0x00001
        const val B = 0x00002
        const val X = 0x00004
        const val Y = 0x00008
        const val LB = 0x00010
        const val RB = 0x00020
        const val LT = 0x00040          // bit 6
        const val RT = 0x00080          // bit 7
        const val SELECT = 0x00100      // bit 8
        const val START = 0x00200       // bit 9
        const val L3 = 0x00400          // bit 10
        const val R3 = 0x00800          // bit 11
        val DPAD_BIT_UP = 0x01000   // bit 12
        val DPAD_BIT_DOWN = 0x02000 // bit 13
        val DPAD_BIT_LEFT = 0x04000 // bit 14
        val DPAD_BIT_RIGHT = 0x08000 // bit 15
        const val HOME = 0x10000        // bit 16
        val TOUCHPAD_CLICK = 0x20000  // bit 17
        const val MIC_MUTE = 0x40000  // bit 18
        const val MOUSE_LMB = 0x80000  // bit 19
        const val MOUSE_RMB = 0x100000  // bit 20
        const val MOUSE_MMB = 0x200000  // bit 21
        const val DPAD_UP = 1
        const val DPAD_DOWN = 2
        const val DPAD_LEFT = 4
        const val DPAD_RIGHT = 8
        const val DPAD_UP_LEFT = 5
        const val DPAD_UP_RIGHT = 9
        const val DPAD_DOWN_LEFT = 6
        const val DPAD_DOWN_RIGHT = 10
    }
}

data class TouchPoint(
    val id: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val active: Boolean = false,
)
