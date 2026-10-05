package com.zyz4.gkme.input.usb.wireless.dualsense

import android.content.Context
import android.view.InputDevice
import com.zyz4.gkme.input.usb.LimeLog
import java.io.Closeable

/**
 * Manages output to DualSense controllers that Android exposed as system InputDevices over its
 * own Bluetooth stack. It owns one [AndroidBluetoothHidHostTransport] and a
 * [DirectDualSenseBluetoothOutput] per attached DualSense.
 *
 * The hidden Bluetooth HID Host profile is only touched lazily, when the first Bluetooth
 * DualSense is present, so devices without such a controller pay nothing.
 */
class DirectDualSenseBluetoothManager(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var transport: AndroidBluetoothHidHostTransport? = null
    private val outputs = HashMap<Int, DirectDualSenseBluetoothOutput>()

    /** Invoked with the InputDevice id when an output write fails, so the caller can fall back. */
    var onSendFailure: ((Int) -> Unit)? = null

    val isAvailable: Boolean
        get() = AndroidBluetoothHidHostTransport.isAvailable(appContext)

    /** True when [device] is a DualSense reachable through the system Bluetooth stack. */
    fun isDualSenseBluetooth(device: InputDevice): Boolean =
        isAvailable && DirectDualSenseBluetoothOutput.supports(device)

    /** Creates/closes per-device outputs so they track the attached gamepads. */
    fun syncDevices(devices: List<InputDevice>) {
        if (!isAvailable) {
            close()
            return
        }
        val active = devices.filter { DirectDualSenseBluetoothOutput.supports(it) }
        synchronized(lock) {
            val present = active.map { it.id }.toHashSet()
            val iterator = outputs.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in present) {
                    runCatching { entry.value.close() }
                    iterator.remove()
                }
            }
            if (active.isEmpty()) return
            val tx = transport ?: createTransport() ?: return
            for (device in active) {
                if (outputs.containsKey(device.id)) continue
                outputs[device.id] = DirectDualSenseBluetoothOutput(device, tx) {
                    onSendFailure?.invoke(device.id)
                }
                LimeLog.info("Direct DualSense Bluetooth output enabled for ${device.name}")
            }
        }
    }

    internal fun outputFor(deviceId: Int): DirectDualSenseBluetoothOutput? =
        synchronized(lock) { outputs[deviceId] }

    override fun close() {
        synchronized(lock) {
            outputs.values.forEach { runCatching { it.close() } }
            outputs.clear()
            transport?.let { runCatching { it.close() } }
            transport = null
        }
    }

    private fun createTransport(): AndroidBluetoothHidHostTransport? {
        if (!isAvailable) return null
        val created = runCatching {
            AndroidBluetoothHidHostTransport(appContext) {
                synchronized(lock) { outputs.values.forEach { it.onTransportReady() } }
            }.also { if (!it.start()) return@runCatching null }
        }.getOrElse {
            LimeLog.warning("Direct DualSense Bluetooth transport unavailable: ${it.message}")
            null
        }
        transport = created
        return created
    }
}
