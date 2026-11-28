package com.zyz4.gkme.input

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zyz4.gkme.input.usb.wireless.dualsense.AndroidBluetoothHidHostTransport

/** Applies the first player light to each half once per connection. Accessed on Main. */
internal class JoyConPlayerLights(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var transport: AndroidBluetoothHidHostTransport? = null
    private var connected = emptyMap<JoyConSide, Int>()
    private val submitted = mutableMapOf<JoyConSide, Int>()
    private var attempts = 0
    private var sequence = 0
    private val retry = Runnable { sendPending() }

    fun sync(leftId: Int?, rightId: Int?) {
        val next = buildMap {
            if (leftId != null) put(JoyConSide.LEFT, leftId)
            if (rightId != null) put(JoyConSide.RIGHT, rightId)
        }
        if (connected == next) return
        connected = next
        submitted.entries.removeAll { (side, id) -> next[side] != id }
        handler.removeCallbacks(retry)
        if (connected.isEmpty()) {
            close()
            return
        }
        attempts = 0
        handler.post(retry)
    }

    private fun sendPending() {
        val pending = connected.filter { (side, id) -> submitted[side] != id }
        if (pending.isEmpty()) return
        val output = transport ?: AndroidBluetoothHidHostTransport(
            context, minimumSdk = Build.VERSION_CODES.P,
        ).also { transport = it }
        attempts++
        if (output.start()) {
            for ((side, id) in pending) {
                val name = if (side == JoyConSide.LEFT) "Joy-Con (L)" else "Joy-Con (R)"
                if (output.sendToNamedDevice(name, joyConPlayerLightReport(sequence++))) {
                    submitted[side] = id
                    Log.i(TAG, "$name player light report submitted")
                }
            }
        }
        if (submitted.size < connected.size) {
            if (attempts < 5) {
                handler.postDelayed(retry, 400)
            } else {
                Log.w(TAG, "Player light report unavailable for ${connected.keys - submitted.keys}")
            }
        }
    }

    fun close() {
        handler.removeCallbacks(retry)
        transport?.close()
        transport = null
        connected = emptyMap()
        submitted.clear()
        sequence = 0
    }

    private companion object {
        const val TAG = "GkmeJoyConLights"
    }
}

/** 0x01 output report, neutral rumble for both sides, then Set Player Lights with LED 1 steady. */
internal fun joyConPlayerLightReport(sequence: Int): ByteArray = ByteArray(49).apply {
    this[0] = 0x01
    this[1] = (sequence and 0x0f).toByte()
    for (offset in listOf(2, 6)) {
        this[offset + 1] = 0x01
        this[offset + 2] = 0x40
        this[offset + 3] = 0x40
    }
    this[10] = 0x30
    this[11] = 0x01
}
