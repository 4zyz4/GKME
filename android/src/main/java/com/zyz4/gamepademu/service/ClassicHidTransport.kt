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
        val reportSize = if (settings.targetPlatform != TargetPlatform.WINDOWS) 9 else 11
        lastReport = ByteArray(reportSize)
        _connectionPhase.value = ConnectionPhase.REGISTERING_PROFILE
        bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    override fun sendReport(report: ByteArray) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return
        lastReport = report
        val reportId = 1
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
        val desc = when (currentSettings?.targetPlatform) {
            TargetPlatform.ANDROID -> ANDROID_HID_DESCRIPTOR
            TargetPlatform.LINUX -> LINUX_HID_DESCRIPTOR
            else -> WINDOWS_HID_DESCRIPTOR
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

        /** 11‑byte report for Windows Classic (matches DInput layout). */
        private val WINDOWS_HID_DESCRIPTOR = byteArrayOf(
            b(0x05), b(0x01),       // Usage Page (Generic Desktop)
            b(0x09), b(0x05),       // Usage (Game Pad)
            b(0xA1), b(0x01),       // Collection (Application)
            b(0x85), b(0x01),       //   Report ID (1)

            // Buttons (18 buttons + 6 padding = 24 bits / 3 bytes)
            b(0x05), b(0x09),       //   Usage Page (Button)
            b(0x19), b(0x01),       //   Usage Minimum (1)
            b(0x29), b(0x12),       //   Usage Maximum (18)
            b(0x15), b(0x00),       //   Logical Minimum (0)
            b(0x25), b(0x01),       //   Logical Maximum (1)
            b(0x75), b(0x01),       //   Report Size (1)
            b(0x95), b(0x12),       //   Report Count (18)
            b(0x81), b(0x02),       //   Input (Data,Var,Abs)

            b(0x75), b(0x01),       //   Report Size (1)
            b(0x95), b(0x06),       //   Report Count (6)
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
         * Standard 9‑byte report for Android Bluetooth (HID descriptor, Report ID 1).
         */
        private val ANDROID_HID_DESCRIPTOR = byteArrayOf(
            b(0x05), b(0x01),             // Usage Page (Generic Desktop)
            b(0x09), b(0x05),             // Usage (Game Pad)
            b(0xA1), b(0x01),             // Collection (Application)
            b(0x85), b(0x01),             //   Report ID (1)

            // Bytes 0‑1: 16 buttons
            b(0x05), b(0x09),             //   Usage Page (Button)
            b(0x19), b(0x01),             //   Usage Minimum (1)
            b(0x29), b(0x10),             //   Usage Maximum (16)
            b(0x15), b(0x00),             //   Logical Minimum (0)
            b(0x25), b(0x01),             //   Logical Maximum (1)
            b(0x95), b(0x10),             //   Report Count (16)
            b(0x75), b(0x01),             //   Report Size (1)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // Bytes 2‑3: Left stick X (X), Y (Y)
            b(0x05), b(0x01),             //   Usage Page (Generic Desktop)
            b(0x09), b(0x30),             //   Usage (X)
            b(0x09), b(0x31),             //   Usage (Y)
            b(0x15), b(0x81),             //   Logical Minimum (-127)
            b(0x25), b(0x7F),             //   Logical Maximum (127)
            b(0x75), b(0x08),             //   Report Size (8)
            b(0x95), b(0x02),             //   Report Count (2)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // Byte 4: Hat switch (D-Pad, 4 bits) + padding (4 bits)
            b(0x05), b(0x01),             //   Usage Page (Generic Desktop)
            b(0x09), b(0x39),             //   Usage (Hat switch)
            b(0x15), b(0x01),             //   Logical Minimum (1)
            b(0x25), b(0x08),             //   Logical Maximum (8)
            b(0x55), b(0x00),             //   Unit Exponent (0)
            b(0x46), b(0x3B), b(0x01),    //   Physical Maximum (315)
            b(0x65), b(0x14),             //   Unit (System: English Rotation, Length: Centimeter)
            b(0x75), b(0x04),             //   Report Size (4)
            b(0x95), b(0x01),             //   Report Count (1)
            b(0x81), b(0x42),             //   Input (Data,Var,Abs,Null State)

            b(0x75), b(0x04),             //   Report Size (4)
            b(0x95), b(0x01),             //   Report Count (1)
            b(0x81), b(0x03),             //   Input (Const,Var,Abs)

            // Bytes 5‑6: Right stick X (Z), Y (Rz)
            b(0x05), b(0x01),             //   Usage Page (Generic Desktop)
            b(0x09), b(0x32),             //   Usage (Z)
            b(0x09), b(0x35),             //   Usage (Rz)
            b(0x15), b(0x81),             //   Logical Minimum (-127)
            b(0x25), b(0x7F),             //   Logical Maximum (127)
            b(0x75), b(0x08),             //   Report Size (8)
            b(0x95), b(0x02),             //   Report Count (2)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // Bytes 7‑8: LT (Brake), RT (Accelerator)
            b(0x05), b(0x02),             //   Usage Page (Sim Ctrls)
            b(0x09), b(0xC4),             //   Usage (Brake)
            b(0x09), b(0xC5),             //   Usage (Accelerator)
            b(0x15), b(0x00),             //   Logical Minimum (0)
            b(0x25), b(0xFF),             //   Logical Maximum (255)
            b(0x75), b(0x08),             //   Report Size (8)
            b(0x95), b(0x02),             //   Report Count (2)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            b(0xC0),                      // End Collection
        )

        /**
         * 9‑byte report for Linux Bluetooth (based on Android, but X/Y swapped, right stick uses Rx/Ry).
         *   byte  0-1  Buttons (same remap as Android)
         *   byte  2    LY (s8, -127..127) ← swapped order
         *   byte  3    LX (s8, -127..127) ← swapped order
         *   byte  4    Hat switch + padding
         *   byte  5    Rx (s8, -127..127)
         *   byte  6    Ry (s8, -127..127)
         *   byte  7    LT / Brake (u8, 0..255)
         *   byte  8    RT / Accelerator (u8, 0..255)
         */
        private val LINUX_HID_DESCRIPTOR = byteArrayOf(
            b(0x05), b(0x01),             // Usage Page (Generic Desktop)
            b(0x09), b(0x05),             // Usage (Game Pad)
            b(0xA1), b(0x01),             // Collection (Application)
            b(0x85), b(0x01),             //   Report ID (1)

            // Bytes 0‑1: 16 buttons
            b(0x05), b(0x09),
            b(0x19), b(0x01),
            b(0x29), b(0x10),
            b(0x15), b(0x00),
            b(0x25), b(0x01),
            b(0x95), b(0x10),
            b(0x75), b(0x01),
            b(0x81), b(0x02),

            // Bytes 2‑3: Left stick X (X), Y (Y) — same as Android
            b(0x05), b(0x01),
            b(0x09), b(0x30),             // Usage (X)
            b(0x09), b(0x31),             // Usage (Y)
            b(0x15), b(0x81),
            b(0x25), b(0x7F),
            b(0x75), b(0x08),
            b(0x95), b(0x02),
            b(0x81), b(0x02),

            // Byte 4: Hat switch + padding
            b(0x05), b(0x01),
            b(0x09), b(0x39),
            b(0x15), b(0x01),
            b(0x25), b(0x08),
            b(0x55), b(0x00),
            b(0x46), b(0x3B), b(0x01),
            b(0x65), b(0x14),
            b(0x75), b(0x04),
            b(0x95), b(0x01),
            b(0x81), b(0x42),
            b(0x75), b(0x04),
            b(0x95), b(0x01),
            b(0x81), b(0x03),

            // Bytes 5‑6: Right stick Rx, Ry
            b(0x05), b(0x01),
            b(0x09), b(0x33),             // Usage (Rx)
            b(0x09), b(0x34),             // Usage (Ry)
            b(0x15), b(0x81),
            b(0x25), b(0x7F),
            b(0x75), b(0x08),
            b(0x95), b(0x02),
            b(0x81), b(0x02),

            // Bytes 7‑8: LT (Brake), RT (Accelerator)
            b(0x05), b(0x02),
            b(0x09), b(0xC4),
            b(0x09), b(0xC5),
            b(0x15), b(0x00),
            b(0x25), b(0xFF),
            b(0x75), b(0x08),
            b(0x95), b(0x02),
            b(0x81), b(0x02),

            b(0xC0),
        )
    }
}
