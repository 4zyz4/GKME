package com.zyz4.gkme

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent

/** Metadata/counters only: never retain key codes, scan codes or characters typed by the user. */
internal class ExternalKeyboardDiagnostics {
    private val counts = linkedMapOf<String, Long>()

    fun record(stage: String, event: KeyEvent, device: InputDevice?, enabled: Boolean,
        reason: String?, handled: Boolean) {
        val category = when (event.action) { KeyEvent.ACTION_DOWN -> "down"; KeyEvent.ACTION_UP -> "up"; else -> "other" }
        val entry = "$stage device=${event.deviceId} source=0x${event.source.toString(16)} " +
            "flags=0x${event.flags.toString(16)} action=$category repeat=${event.repeatCount > 0} " +
            "enabled=$enabled filter=${reason ?: "eligible"} handled=$handled " +
            "mapped=${com.zyz4.gkme.input.ExternalKeyboardPassthrough.toUsage(event.keyCode) != null} " +
            "type=${device?.keyboardType}"
        if (entry !in counts && counts.size >= 48) counts.remove(counts.keys.first())
        counts[entry] = (counts[entry] ?: 0) + 1
    }

    fun render(): String = buildString {
        appendLine("GKME external keyboard diagnostics (no typed text recorded)")
        appendLine("Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
        appendLine("Input devices:")
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.sources and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD) continue
            appendLine("id=$id name=${device.name} vendor=${device.vendorId} product=${device.productId} " +
                "sources=0x${device.sources.toString(16)} type=${device.keyboardType} " +
                "external=${device.isExternalKeyboardDevice()} virtual=${device.isVirtual}")
        }
        appendLine("Events this session:")
        if (counts.isEmpty()) appendLine("No events reached either keyboard entry point.")
        for ((entry, count) in counts) appendLine("$count x $entry")
    }
}

/** isExternal entered the public API in 29; older Android only has a conservative fallback. */
internal fun InputDevice.isExternalKeyboardDevice(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isExternal
    else !isVirtual && id >= 0 && keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
