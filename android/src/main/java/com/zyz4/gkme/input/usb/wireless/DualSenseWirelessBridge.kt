package com.zyz4.gkme.input.usb.wireless

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.zyz4.gkme.input.usb.LimeLog
import com.zyz4.gkme.input.usb.UsbDriverListener
import com.zyz4.gkme.input.usb.wireless.dualsense.DualSenseWirelessBridgeFailure
import com.zyz4.gkme.input.usb.wireless.dualsense.DualSenseWirelessBridgeListener
import com.zyz4.gkme.input.usb.wireless.dualsense.DualSenseWirelessBridgeManager
import com.zyz4.gkme.input.usb.wireless.dualsense.DualSenseWirelessBridgeState
import com.zyz4.gkme.input.usb.wireless.dualsense.HciDualSenseWirelessBridgeHost
import com.zyz4.gkme.input.usb.wireless.hci.AndroidKeystoreHciLinkKeyStore
import com.zyz4.gkme.input.usb.wireless.hci.EphemeralHciLinkKeyStore
import com.zyz4.gkme.input.usb.wireless.hci.HciAdapterBootstrap
import com.zyz4.gkme.input.usb.wireless.hci.HciAdapterCapabilities
import com.zyz4.gkme.input.usb.wireless.hci.HciDiscoveredDevice
import com.zyz4.gkme.input.usb.wireless.hci.HciLinkKeyStore
import com.zyz4.gkme.input.usb.wireless.hci.HciUsbDeviceProbe
import com.zyz4.gkme.input.usb.wireless.hci.HciUsbTransportFactory

/**
 * Application-facing facade over the ported DualSense Bluetooth bridge.
 *
 * Owns one external USB Bluetooth HCI adapter and one DualSense connection. The Java
 * [com.zyz4.gkme.input.usb.UsbDriverService] hands USB devices here after permission is granted;
 * the controller lifecycle is reported through [controllerListener] like any wired driver.
 */
class DualSenseWirelessBridge(
    private val context: Context,
    private val controllerListener: UsbDriverListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: DualSenseWirelessBridgeManager? = null
    private var adapterDeviceId = -1
    private var discoveryStarted = false
    private var connectAttempted = false

    @Volatile
    var state: String = DualSenseWirelessBridgeState.DETACHED.name
        private set

    val isActive: Boolean
        get() = manager != null

    /** The USB device ID of the claimed adapter, or -1 when detached. */
    fun attachedDeviceId(): Int = adapterDeviceId

    /** True when [device] exposes a Bluetooth HCI profile this bridge can drive. */
    fun supports(device: UsbDevice): Boolean = HciUsbDeviceProbe.probe(device) != null

    /**
     * Claims [device] as the bridge adapter and starts discovery. The caller must already hold
     * USB permission. [allocateControllerId] assigns IDs from the host service's shared counter.
     */
    fun start(usbManager: UsbManager, device: UsbDevice, allocateControllerId: () -> Int): Boolean {
        if (manager != null) return false
        val descriptor = HciUsbDeviceProbe.probe(device) ?: return false
        val connection = runCatching { usbManager.openDevice(device) }.getOrNull() ?: return false
        val keyStore: HciLinkKeyStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AndroidKeystoreHciLinkKeyStore(context)
        } else {
            EphemeralHciLinkKeyStore()
        }
        val bridge = DualSenseWirelessBridgeManager(
            controllerListener = controllerListener,
            controllerIdProvider = allocateControllerId,
            linkKeyStore = keyStore,
            hostFactory = { bootstrapListener ->
                HciDualSenseWirelessBridgeHost(
                    HciAdapterBootstrap(
                        HciUsbTransportFactory.create(connection, descriptor),
                        bootstrapListener
                    )
                )
            },
            listener = bridgeListener
        )
        adapterDeviceId = device.deviceId
        discoveryStarted = false
        connectAttempted = false
        manager = bridge
        if (!bridge.start()) {
            manager = null
            adapterDeviceId = -1
            return false
        }
        return true
    }

    /** Releases the adapter if [device] is the one currently claimed. */
    fun detach(device: UsbDevice) {
        if (device.deviceId != adapterDeviceId) return
        closeInternal(adapterPresent = false)
    }

    /** Releases the adapter and any active controller. */
    fun close() {
        closeInternal(adapterPresent = true)
    }

    private fun closeInternal(adapterPresent: Boolean) {
        val bridge = manager
        manager = null
        adapterDeviceId = -1
        discoveryStarted = false
        connectAttempted = false
        runCatching { bridge?.close(adapterPresent) }
        state = DualSenseWirelessBridgeState.DETACHED.name
    }

    private val bridgeListener = object : DualSenseWirelessBridgeListener {
        override fun onAdapterReady(capabilities: HciAdapterCapabilities) {
            LimeLog.info("DualSense wireless bridge adapter ready")
        }

        override fun onStateChanged(state: DualSenseWirelessBridgeState) {
            this@DualSenseWirelessBridge.state = state.name
            LimeLog.info("DualSense wireless bridge state: $state")
            if (state == DualSenseWirelessBridgeState.READY) {
                mainHandler.post { drive() }
            }
        }

        override fun onDeviceFound(device: HciDiscoveredDevice) {
            LimeLog.info("DualSense wireless candidate: ${device.name ?: "unnamed"}")
        }

        override fun onFailure(failure: DualSenseWirelessBridgeFailure) {
            LimeLog.warning("DualSense wireless bridge failure: $failure")
        }
    }

    private fun drive() {
        val bridge = manager ?: return
        if (bridge.state != DualSenseWirelessBridgeState.READY) return
        val candidates = bridge.discoveredDevices()
        if (candidates.isEmpty()) {
            if (!discoveryStarted) {
                discoveryStarted = true
                bridge.startDiscovery()
            }
            return
        }
        if (!connectAttempted) {
            connectAttempted = true
            bridge.connect(candidates.first().address.value)
        }
    }
}
