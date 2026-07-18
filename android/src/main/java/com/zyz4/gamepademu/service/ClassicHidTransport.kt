package com.zyz4.gamepademu.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zyz4.gamepademu.data.PairingStateRepository
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.TargetPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class ClassicHidTransport(
    private val context: Context,
    private val pairingStateRepository: PairingStateRepository,
) : BluetoothHidService {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        bluetoothManager.adapter
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var lastReport: ByteArray = ByteArray(0)
    private var currentSettings: AppSettings? = null
    private var onOutputReport: ((ByteArray) -> Unit)? = null
    private var reRegisterAttempts = 0

    override val transportType: BluetoothTransportType = BluetoothTransportType.CLASSIC

    private val _connectionPhase = MutableStateFlow(ConnectionPhase.IDLE)
    override val connectionPhase: StateFlow<ConnectionPhase> = _connectionPhase.asStateFlow()

    private val deviceCallback by lazy {
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (registered) {
                    reRegisterAttempts = 0
                    scope.launch { tryAutoReconnect() }
                } else {
                    if (reRegisterAttempts < 3 && _connectionPhase.value != ConnectionPhase.IDLE) {
                        reRegisterAttempts++
                        _connectionPhase.value = ConnectionPhase.REGISTERING_PROFILE
                        scope.launch {
                            delay(1000)
                            if (_connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE) {
                                restartService()
                            }
                        }
                    } else {
                        _connectionPhase.value = ConnectionPhase.ERROR
                    }
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        _connectionPhase.value = ConnectionPhase.CONNECTED
                        scope.launch { pairingStateRepository.savePairedDevice(device) }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (_connectionPhase.value == ConnectionPhase.RECONNECTING) {
                            connectedDevice = null
                            enterDiscoverable()
                            _connectionPhase.value = ConnectionPhase.DISCOVERABLE
                        } else {
                            connectedDevice = null
                            enterDiscoverable()
                            _connectionPhase.value = ConnectionPhase.DISCOVERABLE
                        }
                    }
                }
            }

            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                val size = bufferSize.coerceIn(1, 64)
                val data = lastReport.copyOf(size)
                try {
                    hidDevice?.replyReport(device, type, id, data)
                } catch (_: Exception) {}
            }

            override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                onOutputReport?.invoke(data)
            }

            override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {}

            override fun onVirtualCableUnplug(device: BluetoothDevice) {
                connectedDevice = null
                enterDiscoverable()
                _connectionPhase.value = ConnectionPhase.DISCOVERABLE
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hidDevice = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
        }
    }

    override fun start(settings: AppSettings, onOutputReport: (ByteArray) -> Unit) {
        if (!isBluetoothEnabled()) {
            _connectionPhase.value = ConnectionPhase.ERROR
            return
        }
        reRegisterAttempts = 0
        this.onOutputReport = onOutputReport
        currentSettings = settings
        val reportSize = if (settings.targetPlatform == TargetPlatform.ANDROID) 8 else 11
        lastReport = ByteArray(reportSize)
        _connectionPhase.value = ConnectionPhase.REGISTERING_PROFILE
        bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    override fun sendReport(report: ByteArray) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return
        lastReport = report
        val reportId = if (currentSettings?.targetPlatform == TargetPlatform.ANDROID) 0 else 1
        try {
            hid.sendReport(device, reportId, report)
        } catch (_: Exception) {}
    }

    override fun stop() {
        cleanup()
        onOutputReport = null
        _connectionPhase.value = ConnectionPhase.IDLE
    }

    private fun cleanup() {
        try {
            hidDevice?.unregisterApp()
        } catch (_: Exception) {}
        hidDevice?.let {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        hidDevice = null
        connectedDevice = null
    }

    private fun restartService() {
        cleanup()
        reRegisterAttempts = 0
        _connectionPhase.value = ConnectionPhase.REGISTERING_PROFILE
        bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    private fun isBluetoothEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return bluetoothAdapter?.isEnabled == true
    }

    private suspend fun tryAutoReconnect() {
        val address = pairingStateRepository.getPairedDeviceAddress()
        if (address != null) {
            try {
                val device = bluetoothAdapter.getRemoteDevice(address)
                _connectionPhase.value = ConnectionPhase.RECONNECTING
                val ok = hidDevice?.connect(device) ?: false
                if (!ok) {
                    enterDiscoverable()
                    _connectionPhase.value = ConnectionPhase.DISCOVERABLE
                }
            } catch (e: IllegalArgumentException) {
                enterDiscoverable()
                _connectionPhase.value = ConnectionPhase.DISCOVERABLE
            }
        } else {
            enterDiscoverable()
            _connectionPhase.value = ConnectionPhase.DISCOVERABLE
        }
    }

    private fun getRealDeviceName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME
            ) ?: bluetoothAdapter.name ?: Build.MODEL
        } else {
            bluetoothAdapter.name ?: Build.MODEL
        }
    }

    private fun registerApp() {
        val subclass: Byte = 0x02
        val desc = if (currentSettings?.targetPlatform == TargetPlatform.ANDROID) {
            ANDROID_HID_DESCRIPTOR
        } else {
            HID_DESCRIPTOR
        }
        val deviceName = getRealDeviceName()
        val sdp = BluetoothHidDeviceAppSdpSettings(
            deviceName,
            "Virtual Xbox 360 Controller",
            deviceName,
            subclass,
            desc,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            0, 0, 0, 0, 0,
        )
        try {
            hidDevice?.registerApp(sdp, qos, qos, Executors.newSingleThreadExecutor(), deviceCallback)
        } catch (e: Exception) {
            _connectionPhase.value = ConnectionPhase.ERROR
        }
    }

    private fun enterDiscoverable() {
        val hasPairedDevice = runCatching {
            bluetoothAdapter.bondedDevices.isNotEmpty()
        }.getOrDefault(false)
        if (!hasPairedDevice) {
            try {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private companion object {
        private fun b(v: Int) = v.toByte()

        /** 11‑byte report for Windows/Apple Classic (matches XInput layout). */
        private val HID_DESCRIPTOR = byteArrayOf(
            b(0x05), b(0x01),       // Usage Page (Generic Desktop)
            b(0x09), b(0x05),       // Usage (Game Pad)
            b(0xA1), b(0x01),       // Collection (Application)
            b(0x85), b(0x01),       //   Report ID (1)

            // Buttons (17 buttons + 7 padding = 24 bits / 3 bytes)
            b(0x05), b(0x09),       //   Usage Page (Button)
            b(0x19), b(0x01),       //   Usage Minimum (1)
            b(0x29), b(0x11),       //   Usage Maximum (17)
            b(0x15), b(0x00),       //   Logical Minimum (0)
            b(0x25), b(0x01),       //   Logical Maximum (1)
            b(0x75), b(0x01),       //   Report Size (1)
            b(0x95), b(0x11),       //   Report Count (17)
            b(0x81), b(0x02),       //   Input (Data,Var,Abs)

            b(0x75), b(0x01),       //   Report Size (1)
            b(0x95), b(0x07),       //   Report Count (7)
            b(0x81), b(0x01),       //   Input (Const)

            // Axes (LX, LY, RX, RY - 4 x 16-bit = 8 bytes)
            b(0x05), b(0x01),       //   Usage Page (Generic Desktop)
            b(0x09), b(0x30),       //   Usage (X)  → LX
            b(0x09), b(0x31),       //   Usage (Y)  → LY
            b(0x09), b(0x32),       //   Usage (Z)  → RX
            b(0x09), b(0x33),       //   Usage (Ry) → RY
            b(0x16), b(0x00), b(0x80),  // Logical Minimum (-32768)
            b(0x26), b(0xFF), b(0x7F),  // Logical Maximum (32767)
            b(0x75), b(0x10),       //   Report Size (16)
            b(0x95), b(0x04),       //   Report Count (4)
            b(0x81), b(0x02),       //   Input (Data,Var,Abs)

            b(0xC0),                // End Collection
        )

        /**
         * 8‑byte report for Android Bluetooth (HID descriptor).
         *   byte  0    Buttons 1‑8 (bits 0‑7)
         *   byte  1    Buttons 9‑10 (bits 0‑1) + padding (bits 2‑7)
         *   byte  2    LX (s8)
         *   byte  3    LY (s8)
         *   byte  4    Z / RX (s8)
         *   byte  5    Rx / RY (s8)
         *   byte  6    Ry / triggers combined (s8)
         *   byte  7    Hat switch (0‑7, 15=null)
         */
        private val ANDROID_HID_DESCRIPTOR = byteArrayOf(
            b(0x05), b(0x01),           // Usage Page (Generic Desktop)
            b(0x09), b(0x05),           // Usage (Game Pad)
            b(0xa1), b(0x01),           // Collection (Application)
            b(0xa1), b(0x00),           //   Collection (Physical)

            // Bytes 0‑1: Buttons 1‑10
            b(0x05), b(0x09),           //     Usage Page (Button)
            b(0x19), b(0x01),           //     Usage Minimum (Button 1)
            b(0x29), b(0x0a),           //     Usage Maximum (Button 10)
            b(0x15), b(0x00),           //     Logical Minimum (0)
            b(0x25), b(0x01),           //     Logical Maximum (1)
            b(0x95), b(0x0a),           //     Report Count (10)
            b(0x75), b(0x01),           //     Report Size (1)
            b(0x81), b(0x02),           //     Input (Data,Var,Abs)

            // Byte 1 padding
            b(0x95), b(0x01),           //     Report Count (1)
            b(0x75), b(0x06),           //     Report Size (6)
            b(0x81), b(0x03),           //     Input (Cnst,Var,Abs)

            // Bytes 2‑6: Axes (X,Y,Z,Rx,Ry) 5 × s8
            b(0x05), b(0x01),           //     Usage Page (Generic Desktop)
            b(0x09), b(0x30),           //     Usage (X)  → LX
            b(0x09), b(0x31),           //     Usage (Y)  → LY
            b(0x09), b(0x32),           //     Usage (Z)  → RX
            b(0x09), b(0x33),           //     Usage (Rx) → RY
            b(0x09), b(0x34),           //     Usage (Ry) → triggers
            b(0x15), b(0x81),           //     Logical Minimum (-127)
            b(0x25), b(0x7f),           //     Logical Maximum (127)
            b(0x75), b(0x08),           //     Report Size (8)
            b(0x95), b(0x05),           //     Report Count (5)
            b(0x81), b(0x02),           //     Input (Data,Var,Abs)

            // Byte 7: Hat switch (8 directions + null)
            b(0x05), b(0x01),           //     Usage Page (Generic Desktop)
            b(0x09), b(0x39),           //     Usage (Hat switch)
            b(0x15), b(0x00),           //     Logical Minimum (0)
            b(0x25), b(0x07),           //     Logical Maximum (7)
            b(0x75), b(0x08),           //     Report Size (8)
            b(0x95), b(0x01),           //     Report Count (1)
            b(0x81), b(0x42),           //     Input (Data,Var,Abs,Null)

            b(0xc0),                    //   End Collection
            b(0xc0),                    // End Collection
        )
    }
}
