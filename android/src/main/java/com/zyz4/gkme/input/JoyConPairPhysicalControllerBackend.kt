package com.zyz4.gkme.input

import android.content.Context
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.model.GamepadState

internal fun joyConLeftDpadBit(scanCode: Int): Int = when (scanCode) {
    // Android's generic HID layout exposes the simple report's first four buttons in this order.
    0x130, 0x222 -> GamepadState.DPAD_BIT_LEFT
    0x131, 0x221 -> GamepadState.DPAD_BIT_DOWN
    0x132, 0x220 -> GamepadState.DPAD_BIT_UP
    0x133, 0x223 -> GamepadState.DPAD_BIT_RIGHT
    else -> 0
}

/** Combines a pair of original Nintendo Joy-Cons already paired by Android. */
internal class JoyConPairPhysicalControllerBackend(context: Context) :
    InputManagerPhysicalControllerBackend(context) {

    private data class HalfDevice(
        val device: InputDevice,
        val side: JoyConSide,
        val stickX: Int,
        val stickY: Int,
        val trigger: Int,
        val simpleHid: Boolean,
    ) {
        val stick = JoyConStickDecoder(side)
        var reportedHatStick = false
    }

    private val pair = JoyConPairState()
    private val playerLights = JoyConPlayerLights(context)
    private var left: HalfDevice? = null
    private var right: HalfDevice? = null

    override fun acceptDevice(device: InputDevice): Boolean =
        sideOf(device) != null &&
            (super.acceptDevice(device) || device.supportsSource(InputDevice.SOURCE_DPAD))

    override fun onDevicesChanged(devices: List<InputDevice>) {
        left = findHalf(devices, JoyConSide.LEFT, left)
        right = findHalf(devices, JoyConSide.RIGHT, right)
        pair.bind(JoyConSide.LEFT, left?.device?.id)
        pair.bind(JoyConSide.RIGHT, right?.device?.id)
        playerLights.sync(left?.device?.id, right?.device?.id)
        describeJoyConBindings()
        publishPair()
    }

    fun describeJoyConBindings() {
        if (!JoyConDiagnostics.recording) return
        val halves = listOfNotNull(left, right).joinToString(" ") {
            "${it.side}:id=${it.device.id},stick=${MotionEvent.axisToString(it.stickX)}/" +
                "${MotionEvent.axisToString(it.stickY)},trigger=${MotionEvent.axisToString(it.trigger)}," +
                "simple-hid=${it.simpleHid}"
        }.ifEmpty { "none" }
        JoyConDiagnostics.note("BIND $halves")
    }

    override fun describeControllers(devices: List<InputDevice>): List<ControllerInfo> {
        val id = left?.device?.id ?: right?.device?.id ?: return emptyList()
        val label = when {
            left == null -> "Joy-Con 双手柄（仅右侧已连接）"
            right == null -> "Joy-Con 双手柄（仅左侧已连接）"
            else -> "Joy-Con 双手柄（左右均已连接）"
        }
        return listOf(ControllerInfo(id, label, motorCount = 0, supportedButtons = LEFT_KEYS or RIGHT_KEYS))
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean = handleKeyEvent(event, trace = false)

    fun handleKeyEvent(event: KeyEvent, trace: Boolean): Boolean {
        val half = activeHalf(event.deviceId) ?: return false
        val simpleBit = if (half.simpleHid) simpleJoyConButton(half.side, event.scanCode) else null
        val bit = simpleBit ?: when (half.side) {
            JoyConSide.LEFT -> {
                val direction = joyConLeftDpadBit(event.scanCode)
                if (direction != 0) direction else keyCodeToBit(event.keyCode) and LEFT_KEYS
            }
            JoyConSide.RIGHT -> keyCodeToBit(event.keyCode) and RIGHT_KEYS
        }
        if (bit == 0) {
            if (trace) JoyConDiagnostics.mappedKey(event, half.side, bit, null)
            return false
        }
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        pair.updateKey(half.side, bit, pressed)
        publishPair()
        if (trace) JoyConDiagnostics.mappedKey(event, half.side, bit, controllerState.value)
        return true
    }

    override fun handleMotionEvent(event: MotionEvent): Boolean {
        val half = activeHalf(event.deviceId)
        if (half == null) {
            if (JoyConDiagnostics.recording) JoyConDiagnostics.motion(event) { "MAP no-active-side" }
            return false
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE ||
            (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
                !event.isFromSource(InputDevice.SOURCE_GAMEPAD))
        ) {
            if (JoyConDiagnostics.recording) JoyConDiagnostics.motion(event) { "MAP unsupported-motion-source-or-action" }
            return false
        }

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stick = half.stick.decode(
            normalizedStick(half.device, half.stickX, axisValue(event, half.stickX)).toShort(),
            normalizedStick(half.device, half.stickY, axisValue(event, half.stickY)).toShort(),
            hatX,
            hatY,
        )
        if (stick.usesHat && !half.reportedHatStick) {
            half.reportedHatStick = true
            Log.d(TAG, "${half.side}: using the eight-direction HAT for the stick")
        }
        val dpad = if (half.side == JoyConSide.LEFT && !stick.usesHat) {
            hatToBits(hatX, hatY)
        } else 0
        pair.updateMotion(
            half.side,
            stick.x,
            stick.y,
            dpad,
            normalizedTrigger(half.device, half.trigger, axisValue(event, half.trigger)),
        )
        publishPair()
        if (JoyConDiagnostics.recording) JoyConDiagnostics.motion(event) {
            "MAP side=${half.side} stick-source=${if (stick.usesHat) "hat" else "analog"} " +
                JoyConDiagnostics.physicalState(controllerState.value)
        }
        return true
    }

    override fun resetInputState() {
        pair.clear()
        super.resetInputState()
    }

    override fun stop() {
        playerLights.close()
        super.stop()
        left = null
        right = null
        pair.bind(JoyConSide.LEFT, null)
        pair.bind(JoyConSide.RIGHT, null)
    }

    fun releaseInput() = resetInputState()

    private fun publishPair() {
        if (inputControllerIndex == 0) _controllerState.value = pair.snapshot()
    }

    private fun activeHalf(deviceId: Int): HalfDevice? {
        if (inputControllerIndex != 0) return null
        return when (deviceId) {
            left?.device?.id -> left
            right?.device?.id -> right
            else -> null
        }
    }

    private fun findHalf(devices: List<InputDevice>, side: JoyConSide, previous: HalfDevice?): HalfDevice? {
        val device = devices.firstOrNull { it.id == previous?.device?.id && sideOf(it) == side }
            ?: devices.firstOrNull { sideOf(it) == side }
            ?: return null
        val stickAxes = if (side == JoyConSide.LEFT) LEFT_STICK_AXES else RIGHT_STICK_AXES
        val analog = stickAxes.firstOrNull { (x, y) ->
            motionRangeForJoystickAxis(device, x) != null && motionRangeForJoystickAxis(device, y) != null
        }
        val triggerAxes = if (side == JoyConSide.LEFT) LEFT_TRIGGER_AXES else RIGHT_TRIGGER_AXES
        val trigger = triggerAxes.firstOrNull { motionRangeForJoystickAxis(device, it) != null } ?: -1
        // The generic HID button bank includes both C and Z. hid-nintendo exposes
        // the actual per-side keys and uses different scan codes for the shoulders.
        val simpleHid = device.hasKeys(KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z).all { it }
        val updated = HalfDevice(device, side, analog?.first ?: -1, analog?.second ?: -1, trigger, simpleHid)
        if (updated == previous) return previous
        Log.d(TAG, "$side: reported axes ${device.motionRanges.map { MotionEvent.axisToString(it.axis) }}")
        return updated
    }

    private fun sideOf(device: InputDevice): JoyConSide? {
        if (device.vendorId != 0x057e) return null
        return when (device.productId) {
            0x2006 -> JoyConSide.LEFT
            0x2007 -> JoyConSide.RIGHT
            else -> null
        }
    }

    private fun axisValue(event: MotionEvent, axis: Int): Float =
        if (axis < 0) 0f else event.getAxisValue(axis)

    companion object {
        private const val TAG = "GkmeJoyCon"
        private val XY = MotionEvent.AXIS_X to MotionEvent.AXIS_Y
        private val RX_RY = MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        private val Z_RZ = MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        private val LEFT_STICK_AXES = listOf(XY, RX_RY, Z_RZ)
        private val RIGHT_STICK_AXES = listOf(RX_RY, XY, Z_RZ)
        private val LEFT_TRIGGER_AXES = intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        private val RIGHT_TRIGGER_AXES = intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        private val LEFT_KEYS = GamepadState.LB or GamepadState.LT or GamepadState.SELECT or GamepadState.L3 or
            GamepadState.DPAD_BIT_UP or GamepadState.DPAD_BIT_DOWN or
            GamepadState.DPAD_BIT_LEFT or GamepadState.DPAD_BIT_RIGHT
        private val RIGHT_KEYS = GamepadState.A or GamepadState.B or GamepadState.X or GamepadState.Y or
            GamepadState.RB or GamepadState.RT or GamepadState.START or GamepadState.R3 or GamepadState.HOME
    }
}
