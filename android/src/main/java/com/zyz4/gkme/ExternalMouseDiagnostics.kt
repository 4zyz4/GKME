package com.zyz4.gkme

import android.view.InputDevice
import android.view.MotionEvent
import com.zyz4.gkme.input.ExternalMousePassthrough

/** Bounded metadata/counters only. Never retain pointer coordinates or button contents. */
internal class ExternalMouseDiagnostics {
    private val counts = linkedMapOf<String, Long>()
    private var captureGranted = 0L
    private var captureLost = 0L

    fun captureChanged(captured: Boolean) {
        if (captured) captureGranted++ else captureLost++
    }

    fun record(stage: String, event: MotionEvent, state: String) {
        val device = event.device
        val tool = if (event.pointerCount > 0) event.getToolType(0) else -1
        val reason = if (device == null) "missing_device" else ExternalMousePassthrough.rejectionReason(
            event.source, device.sources, device.isExternalMouseDevice(), device.isVirtual, tool)
        // Ignore touchscreen traffic; preserve rejected mouse-like sources/tools as evidence.
        if (tool != MotionEvent.TOOL_TYPE_MOUSE &&
            event.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE &&
            event.source and InputDevice.SOURCE_MOUSE_RELATIVE != InputDevice.SOURCE_MOUSE_RELATIVE &&
            event.source and InputDevice.SOURCE_TOUCHPAD != InputDevice.SOURCE_TOUCHPAD) return
        val entry = "$stage device=${event.deviceId} source=0x${event.source.toString(16)} " +
            "action=${event.actionMasked} tool=$tool filter=${reason ?: "eligible"} $state"
        if (entry !in counts && counts.size >= 48) counts.remove(counts.keys.first())
        counts[entry] = (counts[entry] ?: 0) + 1
    }

    fun render(state: String): String = buildString {
        appendLine("GKME external mouse diagnostics (no pointer coordinates recorded)")
        appendLine("build=${BuildConfig.VERSION_NAME}")
        appendLine("Current state (opening settings pauses capture): $state")
        appendLine("capture-granted=$captureGranted capture-lost=$captureLost")
        appendLine("Input device metadata:")
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val reason = ExternalMousePassthrough.rejectionReason(InputDevice.SOURCE_MOUSE,
                device.sources, device.isExternalMouseDevice(), device.isVirtual, MotionEvent.TOOL_TYPE_MOUSE)
            appendLine("id=$id name=${device.name} vendor=${device.vendorId} product=${device.productId} " +
                "sources=0x${device.sources.toString(16)} type=${device.keyboardType} external=${device.isExternalMouseDevice()} " +
                "virtual=${device.isVirtual} mouse-filter=${reason ?: "eligible"}")
        }
        appendLine("Mouse events this session:")
        if (counts.isEmpty()) appendLine("No mouse-like events reached the mouse entry points.")
        for ((entry, count) in counts) appendLine("$count x $entry")
    }
}
