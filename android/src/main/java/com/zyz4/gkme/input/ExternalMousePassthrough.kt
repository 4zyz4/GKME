package com.zyz4.gkme.input

import android.view.InputDevice
import android.view.MotionEvent

internal data class MouseReport(
    val buttons: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val wheel: Int = 0,
    val pan: Int = 0,
) {
    /** The existing mouse channel accepts shorts. Split instead of wrapping or dropping counts. */
    fun chunks(): List<MouseReport> {
        val result = mutableListOf<MouseReport>()
        var x = dx; var y = dy; var v = wheel; var h = pan
        do {
            val chunk = MouseReport(buttons, x.coerceIn(-32768, 32767), y.coerceIn(-32768, 32767),
                v.coerceIn(-32768, 32767), h.coerceIn(-32768, 32767))
            result += chunk
            x -= chunk.dx; y -= chunk.dy; v -= chunk.wheel; h -= chunk.pan
        } while (x != 0 || y != 0 || v != 0 || h != 0)
        return result
    }
}

enum class MouseReportSource { VIRTUAL, EXTERNAL }

/** Per-device button ownership and fractional relative motion/scroll, independent of Android Views. */
internal class ExternalMousePassthrough(private val onReport: (MouseReport) -> Unit) {
    private class DeviceState {
        var buttons = 0
        val fractions = DoubleArray(4)
        fun take(axis: Int, delta: Float): Int {
            if (!delta.isFinite()) return 0
            val total = (fractions[axis] + delta.toDouble()).coerceIn(-1_000_000.0, 1_000_000.0)
            val whole = total.toInt()
            fractions[axis] = total - whole
            return whole
        }
    }
    private val devices = mutableMapOf<Int, DeviceState>()
    private var buttons = 0

    fun handle(deviceId: Int, action: Int, buttonState: Int,
        dx: Float = 0f, dy: Float = 0f, wheel: Float = 0f, pan: Float = 0f): Boolean {
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseDevice(deviceId)
            return true
        }
        if (action !in ACTIONS) return false
        val device = devices.getOrPut(deviceId) { DeviceState() }
        device.buttons = buttonState and 0x1F
        val merged = devices.values.fold(0) { mask, value -> mask or value.buttons }
        val moving = action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE
        val report = MouseReport(merged,
            if (moving) device.take(0, dx) else 0,
            if (moving) device.take(1, dy) else 0,
            if (action == MotionEvent.ACTION_SCROLL) device.take(2, wheel) else 0,
            if (action == MotionEvent.ACTION_SCROLL) device.take(3, pan) else 0)
        val changed = buttons != merged
        buttons = merged
        if (changed || report.dx != 0 || report.dy != 0 || report.wheel != 0 || report.pan != 0) onReport(report)
        return true
    }

    fun releaseDevice(deviceId: Int) {
        devices.remove(deviceId)
        publishRelease()
    }

    fun releaseAll() {
        devices.clear()
        publishRelease()
    }

    private fun publishRelease() {
        val remaining = devices.values.fold(0) { mask, device -> mask or device.buttons }
        if (remaining != buttons) {
            buttons = remaining
            onReport(MouseReport(buttons))
        }
    }

    companion object {
        private val ACTIONS = setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)

        fun isExternalMouse(eventSource: Int, deviceSources: Int, external: Boolean,
            virtual: Boolean, toolType: Int): Boolean =
            rejectionReason(eventSource, deviceSources, external, virtual, toolType) == null

        fun rejectionReason(eventSource: Int, deviceSources: Int, external: Boolean,
            virtual: Boolean, toolType: Int): String? {
            if (virtual) return "virtual_device"
            if (!external) return "not_external"
            if (toolType != MotionEvent.TOOL_TYPE_MOUSE) return "not_mouse_tool"
            fun isMouse(source: Int) = source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
                source and InputDevice.SOURCE_MOUSE_RELATIVE == InputDevice.SOURCE_MOUSE_RELATIVE
            if (!isMouse(eventSource)) return "not_mouse_event_source"
            if (!isMouse(deviceSources)) return "not_mouse_device_source"
            for ((source, name) in listOf(InputDevice.SOURCE_GAMEPAD to "gamepad",
                InputDevice.SOURCE_JOYSTICK to "joystick", InputDevice.SOURCE_TOUCHPAD to "touchpad")) {
                if ((eventSource or deviceSources) and source == source) {
                    // Mouse HID receivers may also advertise joystick capability.
                    // A mouse-tool/mouse-source event is independent of keyboard type
                    // (SPK7328 Bluetooth type=2, YICHIP receiver type=1).
                    val compositeMouse = source == InputDevice.SOURCE_JOYSTICK &&
                        eventSource and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK
                    if (compositeMouse) continue
                    return if (eventSource and source == source) "${name}_event" else "${name}_device"
                }
            }
            return null
        }
    }
}
