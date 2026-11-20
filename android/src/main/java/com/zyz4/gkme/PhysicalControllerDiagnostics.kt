package com.zyz4.gkme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.zyz4.gkme.input.ControllerInfo
import com.zyz4.gkme.input.PhysicalControllerHandler
import com.zyz4.gkme.service.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Activity-scoped diagnostics. All updates run on Main; only observations and metadata are retained. */
internal class PhysicalControllerDiagnostics(private val context: Context) {
    private data class Entry(val time: Long, val message: String)

    private val history = ArrayDeque<Entry>()
    private var receiverRegistered = false
    private var receiverError: String? = null
    private var inputChanges = 0L
    private var lastInputChange: Long? = null
    private val usbManager get() = context.getSystemService(UsbManager::class.java)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> "usb-attached"
                UsbManager.ACTION_USB_DEVICE_DETACHED -> "usb-detached"
                else -> return
            }
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            record("$event ${device?.let(::usbIdentity) ?: "device metadata unavailable"}")
        }
    }

    fun start(scope: CoroutineScope, handler: PhysicalControllerHandler, connection: StateFlow<ConnectionState>) {
        try {
            val actions = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, usbReceiver, actions, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: SecurityException) {
            receiverError = e.javaClass.simpleName
        }
        scope.launch { handler.activeDriver.collect { record("driver=${context.getString(it.displayNameRes)}") } }
        scope.launch { handler.isConnected.collect { record("controller-connected=$it") } }
        scope.launch { handler.connectedControllers.collect { record("driver-devices=${summarize(it)}") } }
        scope.launch {
            var previous = handler.controllerState.value
            handler.controllerState.collect { state ->
                if (handler.isConnected.value && state != previous) {
                    inputChanges++
                    lastInputChange = SystemClock.elapsedRealtime()
                }
                previous = state
            }
        }
        scope.launch {
            var previous: Pair<Boolean, String>? = null
            connection.collect { state ->
                val link = state.connected to state.phase.name
                if (link != previous) record("host-connected=${link.first} phase=${link.second}")
                previous = link
            }
        }
    }

    fun stop() {
        if (receiverRegistered) context.unregisterReceiver(usbReceiver)
        receiverRegistered = false
    }

    fun record(message: String) {
        if (history.size >= 64) history.removeFirst()
        history.addLast(Entry(System.currentTimeMillis(), message))
    }

    fun render(handler: PhysicalControllerHandler): String = buildString {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT)
        appendLine("GKME physical controller diagnostics")
        appendLine("copied-at=${clock.format(Date())}")
        val controllers = handler.connectedControllers.value
        val selected = controllers.getOrNull(handler.inputControllerIndex)
        appendLine("configured-driver=${context.getString(handler.driver.displayNameRes)} active-driver=${context.getString(handler.activeDriver.value.displayNameRes)} " +
            "controller-connected=${handler.isConnected.value} input-index=${handler.inputControllerIndex} " +
            "selected=${selected?.let { summarize(listOf(it)) } ?: "none"}")
        appendLine("driver-devices=${summarize(controllers)}")
        val age = lastInputChange?.let { "${SystemClock.elapsedRealtime() - it}ms" } ?: "none"
        appendLine("controller-state-changes=$inputChanges last-change-ago=$age (not raw USB packets)")

        appendLine("Current USB host devices:")
        val manager = usbManager
        val devices = try { manager?.deviceList?.values?.sortedBy { it.deviceId } } catch (_: SecurityException) { null }
        when {
            devices == null -> appendLine("USB inventory unavailable")
            devices.isEmpty() -> appendLine("No devices enumerated")
            else -> for (device in devices) {
                val permission = try { manager?.hasPermission(device) } catch (_: SecurityException) { null }
                appendLine("${usbIdentity(device)} permission=$permission")
            }
        }
        appendLine("Recent events observed by this GKME screen (oldest first):")
        receiverError?.let { appendLine("USB event listener unavailable: $it") }
        for (entry in history) appendLine("${clock.format(Date(entry.time))} ${entry.message}")
    }

    private fun usbIdentity(device: UsbDevice): String =
        "id=${device.deviceId} vid=%04x pid=%04x".format(Locale.ROOT, device.vendorId, device.productId)

    private fun summarize(controllers: List<ControllerInfo>): String =
        if (controllers.isEmpty()) "none" else controllers.joinToString { "${it.id}:${it.name}" }
}
