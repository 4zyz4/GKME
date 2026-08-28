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
import android.os.Build
import com.zyz4.gamepademu.data.PairingStateRepository
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.TargetPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log
import kotlin.time.Duration.Companion.milliseconds

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

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null
    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    private var lastReport: ByteArray = ByteArray(0)
    /** Resolution Multiplier feature value for the mouse (Report ID 18): bits0-1 vertical, bits2-3 horizontal, ×2^N. */
    private var resolutionFeature: Byte = 0x0A  // 0x0A = vertical 2, horizontal 2 => ×4 each
    private var currentSettings: AppSettings? = null
    private var onOutputReport: ((ByteArray) -> Unit)? = null

    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /** True while we are waiting for the unregister callback before registering. */
    private var pendingUnregisterThenRegister = false
    private var registerAttempts = 0
    private var registerJob: Job? = null
    private val registerExecutor = Executors.newSingleThreadExecutor()

    /** True during an explicit re-registration (e.g. target platform switch) to ignore disconnect callbacks. */
    private var restarting = false
    private val sendFailedCounter = AtomicInteger(0)

    override val transportType: BluetoothTransportType = BluetoothTransportType.CLASSIC

    private val _connectionPhase = MutableStateFlow(ConnectionPhase.IDLE)
    override val connectionPhase: StateFlow<ConnectionPhase> = _connectionPhase.asStateFlow()

    private val deviceCallback by lazy {
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (stopping.get()) return
                if (registered) {
                    registerAttempts = 0
                    pendingUnregisterThenRegister = false
                    restarting = false
                    cancelRegisterWatchdog()
                    scope.launch { tryAutoReconnect() }
                } else {
                    if (!started.get()) return
                    if (pendingUnregisterThenRegister) {
                        // Unregister finished — now (re)register with a clean slate.
                        pendingUnregisterThenRegister = false
                        scope.launch {
                            delay(300.milliseconds)
                            if (started.get() && !stopping.get() &&
                                _connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE
                            ) {
                                registerApp()
                            }
                        }
                    } else {
                        handleRegisterFailure()
                    }
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                if (stopping.get()) return
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        _connectionPhase.value = ConnectionPhase.CONNECTED
                        scope.launch { pairingStateRepository.savePairedDevice(device) }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!started.get()) return
                        connectedDevice = null
                        if (restarting || _connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE) return
                        _connectionPhase.value = ConnectionPhase.DISCONNECTED
                        scope.launch {
                            delay(500.milliseconds)
                            if (started.get() && !stopping.get() && _connectionPhase.value == ConnectionPhase.DISCONNECTED) {
                                tryAutoReconnect()
                            }
                        }
                    }
                }
            }

            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && id == 18.toByte()) {
                    try {
                        hidDevice?.replyReport(device, type, id, byteArrayOf(resolutionFeature))
                    } catch (_: Exception) {}
                    return
                }
                val size = bufferSize.coerceIn(1, 64)
                val data = lastReport.copyOf(size)
                try {
                    hidDevice?.replyReport(device, type, id, data)
                } catch (_: Exception) {}
            }

            override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && id == 18.toByte() && data.isNotEmpty()) {
                    resolutionFeature = data[0]
                    try {
                        hidDevice?.replyReport(device, type, id, data)
                    } catch (_: Exception) {}
                    return
                }
                onOutputReport?.invoke(data)
            }

            override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {}

            override fun onVirtualCableUnplug(device: BluetoothDevice) {
                if (stopping.get()) return
                connectedDevice = null
                if (restarting || _connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE) return
                scope.launch {
                    delay(500.milliseconds)
                    if (started.get() && !stopping.get()) {
                        tryAutoReconnect()
                    }
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!started.get() || stopping.get()) {
                bluetoothAdapter.closeProfileProxy(profile, proxy)
                return
            }
            hidDevice = proxy as BluetoothHidDevice
            startWithCleanRegistration()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (stopping.get()) return
            hidDevice = null
            if (started.get() && _connectionPhase.value != ConnectionPhase.IDLE) {
                scope.launch {
                    delay(500.milliseconds)
                    if (started.get() && !stopping.get() && hidDevice == null) {
                        requestProfileProxy()
                    }
                }
            }
        }
    }

    override fun start(settings: AppSettings, onOutputReport: (ByteArray) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        stopping.set(false)
        if (!isBluetoothEnabled()) {
            started.set(false)
            _connectionPhase.value = ConnectionPhase.ERROR
            return
        }
        registerAttempts = 0
        pendingUnregisterThenRegister = false
        restarting = false
        this.onOutputReport = onOutputReport
        currentSettings = settings
        val reportSize = 11
        lastReport = ByteArray(reportSize)
        _connectionPhase.value = ConnectionPhase.REGISTERING_PROFILE
        requestProfileProxy()
    }

    override fun sendReport(report: ByteArray) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return
        
        if (sendFailedCounter.get() > 50) {
            Log.w("ClassicHid", "sendReport dropped - too many consecutive failures")
            return
        }

        lastReport = report
        val reportId = 19
        try {
            val ok = hid.sendReport(device, reportId, report)
            if (!ok) {
                val count = sendFailedCounter.incrementAndGet()
                if (count % 10 == 0) {
                    Log.w("ClassicHid", "sendReport returned false, count=$count")
                }
            } else {
                sendFailedCounter.set(0)
            }
        } catch (e: Exception) {
            Log.e("ClassicHid", "sendReport exception", e)
            sendFailedCounter.incrementAndGet()
        }
    }

    private val mouseSendFailedCounter = AtomicInteger(0)

    override fun sendMouseReport(button: Byte, dx: Byte, dy: Byte, wheel: Byte, hWheel: Byte) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        if (mouseSendFailedCounter.get() > 50) {
            Log.w("ClassicHidMouse", "sendMouseReport dropped - too many consecutive failures")
            return
        }

        try {
            val ok = hid.sendReport(device, 18, byteArrayOf(
                (button.toInt() and 0x07).toByte(),
                dx, dy, wheel, hWheel,
            ))
            if (!ok) {
                val count = mouseSendFailedCounter.incrementAndGet()
                if (count % 10 == 0) {
                    Log.w("ClassicHidMouse", "sendMouseReport returned false, count=$count")
                }
            } else {
                mouseSendFailedCounter.set(0)
            }
        } catch (e: Exception) {
            Log.e("ClassicHidMouse", "sendMouseReport exception", e)
            mouseSendFailedCounter.incrementAndGet()
        }
    }

    private val keyboardSendFailedCounter = AtomicInteger(0)

    override fun sendKeyboardReport(modifier: Byte, keys: ByteArray) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        if (keyboardSendFailedCounter.get() > 50) {
            Log.w("ClassicHidKeyboard", "sendKeyboardReport dropped - too many consecutive failures")
            return
        }

        try {
            val report = ByteArray(7)
            report[0] = modifier
            keys.copyInto(report, 1, 0, minOf(keys.size, 6))
            val ok = hid.sendReport(device, 17, report)
            if (!ok) {
                val count = keyboardSendFailedCounter.incrementAndGet()
                if (count % 10 == 0) {
                    Log.w("ClassicHidKeyboard", "sendKeyboardReport returned false, count=$count")
                }
            } else {
                keyboardSendFailedCounter.set(0)
            }
        } catch (e: Exception) {
            Log.e("ClassicHidKeyboard", "sendKeyboardReport exception", e)
            keyboardSendFailedCounter.incrementAndGet()
        }
    }

    override fun stop() {
        stopping.set(true)
        started.set(false)
        cleanup()
        onOutputReport = null
        _connectionPhase.value = ConnectionPhase.IDLE
    }

    /**
     * Re-registers the HID profile with a new descriptor (e.g. after switching the target
     * platform) without stopping the service. The currently connected host is disconnected and
     * the saved pairing is expected to have been cleared by the caller.
     */
    override fun restart(settings: AppSettings, onOutputReport: (ByteArray) -> Unit) {
        if (stopping.get()) return
        if (!started.get()) {
            start(settings, onOutputReport)
            return
        }
        cancelRegisterWatchdog()
        pendingUnregisterThenRegister = false
        registerAttempts = 0
        restarting = true
        currentSettings = settings
        this.onOutputReport = onOutputReport
        val reportSize = 11
        lastReport = ByteArray(reportSize)
        connectedDevice?.let { device ->
            try {
                hidDevice?.disconnect(device)
            } catch (_: Exception) {}
        }
        connectedDevice = null
        _connectionPhase.value = ConnectionPhase.REGISTERING_PROFILE
        if (hidDevice == null) {
            requestProfileProxy()
        } else {
            startWithCleanRegistration()
        }
    }

    private fun cleanup() {
        cancelRegisterWatchdog()
        pendingUnregisterThenRegister = false
        restarting = false
        try {
            hidDevice?.unregisterApp()
        } catch (_: Exception) {}
        hidDevice?.let {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        hidDevice = null
        connectedDevice = null
    }

    private fun requestProfileProxy() {
        if (stopping.get() || !started.get()) return
        try {
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (_: Exception) {
            handleRegisterFailure()
        }
        scheduleRegisterWatchdog()
    }

    /**
     * Unregisters before registering so a stale system-level registration (e.g. left over from a
     * previous process) can never make registerApp() fail silently with no callback.
     */
    private fun startWithCleanRegistration() {
        if (stopping.get() || !started.get()) return
        if (_connectionPhase.value != ConnectionPhase.REGISTERING_PROFILE) return
        val hid = hidDevice ?: return
        pendingUnregisterThenRegister = true
        val ok = try {
            hid.unregisterApp()
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            // Nothing stale registered — no callback will arrive, register directly.
            pendingUnregisterThenRegister = false
            scope.launch {
                delay(300.milliseconds)
                if (started.get() && !stopping.get() &&
                    _connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE
                ) {
                    registerApp()
                }
            }
        }
    }

    private fun registerApp() {
        if (stopping.get() || !started.get()) return
        if (_connectionPhase.value != ConnectionPhase.REGISTERING_PROFILE) return
        val hid = hidDevice
        if (hid == null) {
            requestProfileProxy()
            return
        }
        val settings = currentSettings
        val desc = when (settings?.targetPlatform) {
            TargetPlatform.WINDOWS -> COMBO_WIN_HID_DESCRIPTOR
            TargetPlatform.LINUX -> COMBO_LINUX_HID_DESCRIPTOR
            else -> COMBO_ANDROID_HID_DESCRIPTOR
        }
        val deviceName = getRealDeviceName()
        val label = "Virtual HID Controller"
        val sdp = BluetoothHidDeviceAppSdpSettings(
            deviceName,
            label,
            deviceName,
            BluetoothHidDevice.SUBCLASS1_COMBO,
            desc,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            0, 0, 0, 0, 0,
        )
        try {
            val ok = hid.registerApp(sdp, qos, qos, registerExecutor, deviceCallback)
            if (ok) {
                scheduleRegisterWatchdog()
            } else {
                handleRegisterFailure()
            }
        } catch (_: Exception) {
            handleRegisterFailure()
        }
    }

    private fun handleRegisterFailure() {
        if (stopping.get() || !started.get()) return
        if (_connectionPhase.value == ConnectionPhase.IDLE) return
        cancelRegisterWatchdog()
        registerAttempts++
        if (registerAttempts > MAX_REGISTER_ATTEMPTS) {
            _connectionPhase.value = ConnectionPhase.ERROR
            return
        }
        _connectionPhase.value = ConnectionPhase.REGISTERING_PROFILE
        scope.launch {
            delay(REGISTER_RETRY_DELAY_MS.milliseconds)
            if (started.get() && !stopping.get() &&
                _connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE
            ) {
                recoveryCycle()
            }
        }
    }

    private fun recoveryCycle() {
        if (stopping.get() || !started.get()) return
        if (_connectionPhase.value != ConnectionPhase.REGISTERING_PROFILE) return
        val hid = hidDevice
        if (hid == null) {
            requestProfileProxy()
            return
        }
        pendingUnregisterThenRegister = true
        val ok = try {
            hid.unregisterApp()
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            pendingUnregisterThenRegister = false
            scope.launch {
                delay(300.milliseconds)
                if (started.get() && !stopping.get() &&
                    _connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE
                ) {
                    registerApp()
                }
            }
        }
    }

    private fun scheduleRegisterWatchdog() {
        cancelRegisterWatchdog()
        registerJob = scope.launch {
            delay(REGISTER_TIMEOUT_MS.milliseconds)
            if (started.get() && !stopping.get() &&
                _connectionPhase.value == ConnectionPhase.REGISTERING_PROFILE
            ) {
                handleRegisterFailure()
            }
        }
    }

    private fun cancelRegisterWatchdog() {
        registerJob?.cancel()
        registerJob = null
    }

    private fun isBluetoothEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return bluetoothAdapter.isEnabled
    }

    private suspend fun tryAutoReconnect() {
        if (!started.get() || stopping.get()) return
        val address = pairingStateRepository.getPairedDeviceAddress()
        if (address != null) {
            try {
                val device = bluetoothAdapter.getRemoteDevice(address)
                _connectionPhase.value = ConnectionPhase.RECONNECTING
                val ok = hidDevice?.connect(device) ?: false
                if (!ok) {
                    enterDiscoverable()
                }
            } catch (_: IllegalArgumentException) {
                enterDiscoverable()
            }
        } else {
            enterDiscoverable()
        }
    }

    private fun getRealDeviceName(): String {
        return android.provider.Settings.Global.getString(
            context.contentResolver,
            android.provider.Settings.Global.DEVICE_NAME
        ) ?: bluetoothAdapter.name ?: Build.MODEL
    }

    private fun enterDiscoverable() {
        _connectionPhase.value = ConnectionPhase.DISCOVERABLE
    }

    private companion object {
        /** How long to wait for the register/unregister callback before forcing a recovery cycle. */
        private const val REGISTER_TIMEOUT_MS = 8000L

        /** Delay between registration retry cycles. */
        private const val REGISTER_RETRY_DELAY_MS = 1500L

        /** Maximum registration attempts before giving up and showing an error. */
        private const val MAX_REGISTER_ATTEMPTS = 5

        private fun b(v: Int) = v.toByte()

        /** Combo device (Keyboard + Mouse + Windows Gamepad 11-byte) HID descriptor. */
        private val COMBO_WIN_HID_DESCRIPTOR = byteArrayOf(
            // KEYBOARD — Report ID 17
            b(0x05), b(0x01),          // Usage Page (Generic Desktop)
            b(0x09), b(0x06),          // Usage (Keyboard)
            b(0xA1), b(0x01),          // Collection (Application)
            b(0x85), b(0x11),          //   Report ID (17)
            // Modifier bytes
            b(0x05), b(0x07),          //   Usage Page (Kbrd/Keypad)
            b(0x19), b(0xE0),          //   Usage Minimum (0xE0)
            b(0x29), b(0xE7),          //   Usage Maximum (0xE7)
            b(0x15), b(0x00),          //   Logical Minimum (0)
            b(0x25), b(0x01),          //   Logical Maximum (1)
            b(0x75), b(0x01),          //   Report Size (1)
            b(0x95), b(0x08),          //   Report Count (8)
            b(0x81), b(0x02),          //   Input (Data,Var,Abs)
            // LEDs
            b(0x95), b(0x05),          //   Report Count (5)
            b(0x05), b(0x08),          //   Usage Page (LEDs)
            b(0x19), b(0x01),          //   Usage Minimum (Num Lock)
            b(0x29), b(0x05),          //   Usage Maximum (Kana)
            b(0x91), b(0x02),          //   Output (Data,Var,Abs)
            b(0x95), b(0x01),          //   Report Count (1)
            b(0x75), b(0x03),          //   Report Size (3)
            b(0x91), b(0x03),          //   Output (Const)
            // Keys (6 bytes)
            b(0x95), b(0x06),          //   Report Count (6)
            b(0x75), b(0x08),          //   Report Size (8)
            b(0x15), b(0x00),          //   Logical Minimum (0)
            b(0x25), b(0x65),          //   Logical Maximum (101)
            b(0x05), b(0x07),          //   Usage Page (Kbrd/Keypad)
            b(0x19), b(0x00),          //   Usage Minimum (0x00)
            b(0x29), b(0x65),          //   Usage Maximum (0x65)
            b(0x81), b(0x00),          //   Input (Data,Array)
            b(0xC0),                     // End Collection

            // MOUSE — Report ID 18 — high-resolution wheel (vertical + horizontal AC Pan)
            b(0x05), b(0x01),          // Usage Page (Generic Desktop)
            b(0x09), b(0x02),          // Usage (Mouse)
            b(0xA1), b(0x01),          // Collection (Application)
            b(0x85), b(0x12),          //   Report ID (18)
            b(0x09), b(0x02),          //   Usage (Mouse)
            b(0xA1), b(0x02),          //   Collection (Logical)
            b(0x09), b(0x01),          //     Usage (Pointer)
            b(0xA1), b(0x00),          //     Collection (Physical)
            // Buttons (5) + padding (3) = 1 byte
            b(0x05), b(0x09),          //       Usage Page (Button)
            b(0x19), b(0x01),          //       Usage Minimum (Button 1)
            b(0x29), b(0x05),          //       Usage Maximum (Button 5)
            b(0x15), b(0x00),          //       Logical Minimum (0)
            b(0x25), b(0x01),          //       Logical Maximum (1)
            b(0x75), b(0x01),          //       Report Size (1)
            b(0x95), b(0x05),          //       Report Count (5)
            b(0x81), b(0x02),          //       Input (Data,Var,Abs)
            b(0x75), b(0x03),          //       Report Size (3)
            b(0x95), b(0x01),          //       Report Count (1)
            b(0x81), b(0x03),          //       Input (Const,Var,Abs)
            // X, Y
            b(0x05), b(0x01),          //       Usage Page (Generic Desktop)
            b(0x09), b(0x30),          //       Usage (X)
            b(0x09), b(0x31),          //       Usage (Y)
            b(0x15), b(0x81),          //       Logical Minimum (-127)
            b(0x25), b(0x7F),          //       Logical Maximum (127)
            b(0x75), b(0x08),          //       Report Size (8)
            b(0x95), b(0x02),          //       Report Count (2)
            b(0x81), b(0x06),          //       Input (Data,Var,Rel)
            b(0xA1), b(0x02),          //       Collection (Logical) — Vertical wheel
            b(0x09), b(0x48),          //         Usage (Resolution Multiplier)
            b(0x15), b(0x00),          //         Logical Minimum (0)
            b(0x25), b(0x02),          //         Logical Maximum (2)
            b(0x35), b(0x01),          //         Physical Minimum (1)
            b(0x45), b(0x08),          //         Physical Maximum (8)
            b(0x75), b(0x02),          //         Report Size (2)
            b(0x95), b(0x01),          //         Report Count (1)
            b(0xA4),                    //         PUSH
            b(0xB1), b(0x02),          //         Feature (Data,Var,Abs)
            b(0x09), b(0x38),          //         Usage (Wheel)
            b(0x15), b(0x81),          //         Logical Minimum (-127)
            b(0x25), b(0x7F),          //         Logical Maximum (127)
            b(0x35), b(0x00),          //         Physical Minimum (0)
            b(0x45), b(0x00),          //         Physical Maximum (0)
            b(0x75), b(0x08),          //         Report Size (8)
            b(0x81), b(0x06),          //         Input (Data,Var,Rel)
            b(0xC0),                     //       End Collection
            b(0xA1), b(0x02),          //       Collection (Logical) — Horizontal wheel
            b(0x09), b(0x48),          //         Usage (Resolution Multiplier)
            b(0xB4),                    //         POP
            b(0xB1), b(0x02),          //         Feature (Data,Var,Abs)
            b(0x35), b(0x00),          //         Physical Minimum (0)
            b(0x45), b(0x00),          //         Physical Maximum (0)
            b(0x75), b(0x04),          //         Report Size (4)
            b(0xB1), b(0x03),          //         Feature (Const,Var,Abs)
            b(0x05), b(0x0C),          //         Usage Page (Consumer Devices)
            b(0x0A), b(0x38), b(0x02), //         Usage (AC Pan)
            b(0x15), b(0x81),          //         Logical Minimum (-127)
            b(0x25), b(0x7F),          //         Logical Maximum (127)
            b(0x75), b(0x08),          //         Report Size (8)
            b(0x81), b(0x06),          //         Input (Data,Var,Rel)
            b(0xC0),                     //       End Collection
            b(0xC0),                     //     End Collection (Physical)
            b(0xC0),                     //   End Collection (Logical)
            b(0xC0),                     // End Collection

            // GAMEPAD — Report ID 19 — using Windows 11-byte layout
            // (18 buttons + 6 padding + 4 x 16-bit axes)
            b(0x05), b(0x01),       // Usage Page (Generic Desktop)
            b(0x09), b(0x05),       // Usage (Game Pad)
            b(0xA1), b(0x01),       // Collection (Application)
            b(0x85), b(0x13),       //   Report ID (19)

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

        /** Combo device (Keyboard + Mouse + Android Gamepad 9-byte) descriptor. */
        private val COMBO_ANDROID_HID_DESCRIPTOR = byteArrayOf(
            // KEYBOARD — Report ID 17
            b(0x05), b(0x01),          // Usage Page (Generic Desktop)
            b(0x09), b(0x06),          // Usage (Keyboard)
            b(0xA1), b(0x01),          // Collection (Application)
            b(0x85), b(0x11),          //   Report ID (17)
            // Modifier bytes
            b(0x05), b(0x07),          //   Usage Page (Kbrd/Keypad)
            b(0x19), b(0xE0),          //   Usage Minimum (0xE0)
            b(0x29), b(0xE7),          //   Usage Maximum (0xE7)
            b(0x15), b(0x00),          //   Logical Minimum (0)
            b(0x25), b(0x01),          //   Logical Maximum (1)
            b(0x75), b(0x01),          //   Report Size (1)
            b(0x95), b(0x08),          //   Report Count (8)
            b(0x81), b(0x02),          //   Input (Data,Var,Abs)
            // LEDs
            b(0x95), b(0x05),          //   Report Count (5)
            b(0x05), b(0x08),          //   Usage Page (LEDs)
            b(0x19), b(0x01),          //   Usage Minimum (Num Lock)
            b(0x29), b(0x05),          //   Usage Maximum (Kana)
            b(0x91), b(0x02),          //   Output (Data,Var,Abs)
            b(0x95), b(0x01),          //   Report Count (1)
            b(0x75), b(0x03),          //   Report Size (3)
            b(0x91), b(0x03),          //   Output (Const)
            // Keys (6 bytes)
            b(0x95), b(0x06),          //   Report Count (6)
            b(0x75), b(0x08),          //   Report Size (8)
            b(0x15), b(0x00),          //   Logical Minimum (0)
            b(0x25), b(0x65),          //   Logical Maximum (101)
            b(0x05), b(0x07),          //   Usage Page (Kbrd/Keypad)
            b(0x19), b(0x00),          //   Usage Minimum (0x00)
            b(0x29), b(0x65),          //   Usage Maximum (0x65)
            b(0x81), b(0x00),          //   Input (Data,Array)
            b(0xC0),                     // End Collection

            // MOUSE — Report ID 18 — high-resolution wheel (vertical + horizontal AC Pan)
            b(0x05), b(0x01),          // Usage Page (Generic Desktop)
            b(0x09), b(0x02),          // Usage (Mouse)
            b(0xA1), b(0x01),          // Collection (Application)
            b(0x85), b(0x12),          //   Report ID (18)
            b(0x09), b(0x02),          //   Usage (Mouse)
            b(0xA1), b(0x02),          //   Collection (Logical)
            b(0x09), b(0x01),          //     Usage (Pointer)
            b(0xA1), b(0x00),          //     Collection (Physical)
            // Buttons (5) + padding (3) = 1 byte
            b(0x05), b(0x09),          //       Usage Page (Button)
            b(0x19), b(0x01),          //       Usage Minimum (Button 1)
            b(0x29), b(0x05),          //       Usage Maximum (Button 5)
            b(0x15), b(0x00),          //       Logical Minimum (0)
            b(0x25), b(0x01),          //       Logical Maximum (1)
            b(0x75), b(0x01),          //       Report Size (1)
            b(0x95), b(0x05),          //       Report Count (5)
            b(0x81), b(0x02),          //       Input (Data,Var,Abs)
            b(0x75), b(0x03),          //       Report Size (3)
            b(0x95), b(0x01),          //       Report Count (1)
            b(0x81), b(0x03),          //       Input (Const,Var,Abs)
            // X, Y
            b(0x05), b(0x01),          //       Usage Page (Generic Desktop)
            b(0x09), b(0x30),          //       Usage (X)
            b(0x09), b(0x31),          //       Usage (Y)
            b(0x15), b(0x81),          //       Logical Minimum (-127)
            b(0x25), b(0x7F),          //       Logical Maximum (127)
            b(0x75), b(0x08),          //       Report Size (8)
            b(0x95), b(0x02),          //       Report Count (2)
            b(0x81), b(0x06),          //       Input (Data,Var,Rel)
            b(0xA1), b(0x02),          //       Collection (Logical) — Vertical wheel
            b(0x09), b(0x48),          //         Usage (Resolution Multiplier)
            b(0x15), b(0x00),          //         Logical Minimum (0)
            b(0x25), b(0x02),          //         Logical Maximum (2)
            b(0x35), b(0x01),          //         Physical Minimum (1)
            b(0x45), b(0x08),          //         Physical Maximum (8)
            b(0x75), b(0x02),          //         Report Size (2)
            b(0x95), b(0x01),          //         Report Count (1)
            b(0xA4),                    //         PUSH
            b(0xB1), b(0x02),          //         Feature (Data,Var,Abs)
            b(0x09), b(0x38),          //         Usage (Wheel)
            b(0x15), b(0x81),          //         Logical Minimum (-127)
            b(0x25), b(0x7F),          //         Logical Maximum (127)
            b(0x35), b(0x00),          //         Physical Minimum (0)
            b(0x45), b(0x00),          //         Physical Maximum (0)
            b(0x75), b(0x08),          //         Report Size (8)
            b(0x81), b(0x06),          //         Input (Data,Var,Rel)
            b(0xC0),                     //       End Collection
            b(0xA1), b(0x02),          //       Collection (Logical) — Horizontal wheel
            b(0x09), b(0x48),          //         Usage (Resolution Multiplier)
            b(0xB4),                    //         POP
            b(0xB1), b(0x02),          //         Feature (Data,Var,Abs)
            b(0x35), b(0x00),          //         Physical Minimum (0)
            b(0x45), b(0x00),          //         Physical Maximum (0)
            b(0x75), b(0x04),          //         Report Size (4)
            b(0xB1), b(0x03),          //         Feature (Const,Var,Abs)
            b(0x05), b(0x0C),          //         Usage Page (Consumer Devices)
            b(0x0A), b(0x38), b(0x02), //         Usage (AC Pan)
            b(0x15), b(0x81),          //         Logical Minimum (-127)
            b(0x25), b(0x7F),          //         Logical Maximum (127)
            b(0x75), b(0x08),          //         Report Size (8)
            b(0x81), b(0x06),          //         Input (Data,Var,Rel)
            b(0xC0),                     //       End Collection
            b(0xC0),                     //     End Collection (Physical)
            b(0xC0),                     //   End Collection (Logical)
            b(0xC0),                     // End Collection

            // GAMEPAD — Report ID 19 — 9-byte Android layout
            b(0x05), b(0x01),             // Usage Page (Generic Desktop)
            b(0x09), b(0x05),             // Usage (Game Pad)
            b(0xA1), b(0x01),             // Collection (Application)
            b(0x85), b(0x13),             //   Report ID (19)

            // Buttons (2 bytes — 16 buttons)
            b(0x05), b(0x09),             //   Usage Page (Button)
            b(0x19), b(0x01),             //   Usage Minimum (1)
            b(0x29), b(0x10),             //   Usage Maximum (16)
            b(0x15), b(0x00),             //   Logical Minimum (0)
            b(0x25), b(0x01),             //   Logical Maximum (1)
            b(0x95), b(0x10),             //   Report Count (16)
            b(0x75), b(0x01),             //   Report Size (1)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // LX, LY (bytes 2-3)
            b(0x05), b(0x01),             //   Usage Page (Generic Desktop)
            b(0x09), b(0x30),             //   Usage (X)
            b(0x09), b(0x31),             //   Usage (Y)
            b(0x15), b(0x81),             //   Logical Minimum (-127)
            b(0x25), b(0x7F),             //   Logical Maximum (127)
            b(0x75), b(0x08),             //   Report Size (8)
            b(0x95), b(0x02),             //   Report Count (2)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // Hat switch (byte 4)
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

            // Right stick Z, Rz — bytes 5-6 (Android)
            b(0x05), b(0x01),             //   Usage Page (Generic Desktop)
            b(0x09), b(0x32),             //   Usage (Z)
            b(0x09), b(0x35),             //   Usage (Rz)
            b(0x15), b(0x81),             //   Logical Minimum (-127)
            b(0x25), b(0x7F),             //   Logical Maximum (127)
            b(0x75), b(0x08),             //   Report Size (8)
            b(0x95), b(0x02),             //   Report Count (2)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // Brake, Accelerator — bytes 7-8
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

        /** Combo device (Keyboard + Mouse + Linux Gamepad 9-byte) descriptor. */
        private val COMBO_LINUX_HID_DESCRIPTOR = byteArrayOf(
            // KEYBOARD — Report ID 17
            b(0x05), b(0x01),          // Usage Page (Generic Desktop)
            b(0x09), b(0x06),          // Usage (Keyboard)
            b(0xA1), b(0x01),          // Collection (Application)
            b(0x85), b(0x11),          //   Report ID (17)
            // Modifier bytes
            b(0x05), b(0x07),          //   Usage Page (Kbrd/Keypad)
            b(0x19), b(0xE0),          //   Usage Minimum (0xE0)
            b(0x29), b(0xE7),          //   Usage Maximum (0xE7)
            b(0x15), b(0x00),          //   Logical Minimum (0)
            b(0x25), b(0x01),          //   Logical Maximum (1)
            b(0x75), b(0x01),          //   Report Size (1)
            b(0x95), b(0x08),          //   Report Count (8)
            b(0x81), b(0x02),          //   Input (Data,Var,Abs)
            // LEDs
            b(0x95), b(0x05),          //   Report Count (5)
            b(0x05), b(0x08),          //   Usage Page (LEDs)
            b(0x19), b(0x01),          //   Usage Minimum (Num Lock)
            b(0x29), b(0x05),          //   Usage Maximum (Kana)
            b(0x91), b(0x02),          //   Output (Data,Var,Abs)
            b(0x95), b(0x01),          //   Report Count (1)
            b(0x75), b(0x03),          //   Report Size (3)
            b(0x91), b(0x03),          //   Output (Const)
            // Keys (6 bytes)
            b(0x95), b(0x06),          //   Report Count (6)
            b(0x75), b(0x08),          //   Report Size (8)
            b(0x15), b(0x00),          //   Logical Minimum (0)
            b(0x25), b(0x65),          //   Logical Maximum (101)
            b(0x05), b(0x07),          //   Usage Page (Kbrd/Keypad)
            b(0x19), b(0x00),          //   Usage Minimum (0x00)
            b(0x29), b(0x65),          //   Usage Maximum (0x65)
            b(0x81), b(0x00),          //   Input (Data,Array)
            b(0xC0),                     // End Collection

            // MOUSE — Report ID 18 — high-resolution wheel (vertical + horizontal AC Pan)
            b(0x05), b(0x01),          // Usage Page (Generic Desktop)
            b(0x09), b(0x02),          // Usage (Mouse)
            b(0xA1), b(0x01),          // Collection (Application)
            b(0x85), b(0x12),          //   Report ID (18)
            b(0x09), b(0x02),          //   Usage (Mouse)
            b(0xA1), b(0x02),          //   Collection (Logical)
            b(0x09), b(0x01),          //     Usage (Pointer)
            b(0xA1), b(0x00),          //     Collection (Physical)
            // Buttons (5) + padding (3) = 1 byte
            b(0x05), b(0x09),          //       Usage Page (Button)
            b(0x19), b(0x01),          //       Usage Minimum (Button 1)
            b(0x29), b(0x05),          //       Usage Maximum (Button 5)
            b(0x15), b(0x00),          //       Logical Minimum (0)
            b(0x25), b(0x01),          //       Logical Maximum (1)
            b(0x75), b(0x01),          //       Report Size (1)
            b(0x95), b(0x05),          //       Report Count (5)
            b(0x81), b(0x02),          //       Input (Data,Var,Abs)
            b(0x75), b(0x03),          //       Report Size (3)
            b(0x95), b(0x01),          //       Report Count (1)
            b(0x81), b(0x03),          //       Input (Const,Var,Abs)
            // X, Y
            b(0x05), b(0x01),          //       Usage Page (Generic Desktop)
            b(0x09), b(0x30),          //       Usage (X)
            b(0x09), b(0x31),          //       Usage (Y)
            b(0x15), b(0x81),          //       Logical Minimum (-127)
            b(0x25), b(0x7F),          //       Logical Maximum (127)
            b(0x75), b(0x08),          //       Report Size (8)
            b(0x95), b(0x02),          //       Report Count (2)
            b(0x81), b(0x06),          //       Input (Data,Var,Rel)
            b(0xA1), b(0x02),          //       Collection (Logical) — Vertical wheel
            b(0x09), b(0x48),          //         Usage (Resolution Multiplier)
            b(0x15), b(0x00),          //         Logical Minimum (0)
            b(0x25), b(0x02),          //         Logical Maximum (2)
            b(0x35), b(0x01),          //         Physical Minimum (1)
            b(0x45), b(0x08),          //         Physical Maximum (8)
            b(0x75), b(0x02),          //         Report Size (2)
            b(0x95), b(0x01),          //         Report Count (1)
            b(0xA4),                    //         PUSH
            b(0xB1), b(0x02),          //         Feature (Data,Var,Abs)
            b(0x09), b(0x38),          //         Usage (Wheel)
            b(0x15), b(0x81),          //         Logical Minimum (-127)
            b(0x25), b(0x7F),          //         Logical Maximum (127)
            b(0x35), b(0x00),          //         Physical Minimum (0)
            b(0x45), b(0x00),          //         Physical Maximum (0)
            b(0x75), b(0x08),          //         Report Size (8)
            b(0x81), b(0x06),          //         Input (Data,Var,Rel)
            b(0xC0),                     //       End Collection
            b(0xA1), b(0x02),          //       Collection (Logical) — Horizontal wheel
            b(0x09), b(0x48),          //         Usage (Resolution Multiplier)
            b(0xB4),                    //         POP
            b(0xB1), b(0x02),          //         Feature (Data,Var,Abs)
            b(0x35), b(0x00),          //         Physical Minimum (0)
            b(0x45), b(0x00),          //         Physical Maximum (0)
            b(0x75), b(0x04),          //         Report Size (4)
            b(0xB1), b(0x03),          //         Feature (Const,Var,Abs)
            b(0x05), b(0x0C),          //         Usage Page (Consumer Devices)
            b(0x0A), b(0x38), b(0x02), //         Usage (AC Pan)
            b(0x15), b(0x81),          //         Logical Minimum (-127)
            b(0x25), b(0x7F),          //         Logical Maximum (127)
            b(0x75), b(0x08),          //         Report Size (8)
            b(0x81), b(0x06),          //         Input (Data,Var,Rel)
            b(0xC0),                     //       End Collection
            b(0xC0),                     //     End Collection (Physical)
            b(0xC0),                     //   End Collection (Logical)
            b(0xC0),                     // End Collection

            // GAMEPAD — Report ID 19 — 9-byte Linux layout
            b(0x05), b(0x01),             // Usage Page (Generic Desktop)
            b(0x09), b(0x05),             // Usage (Game Pad)
            b(0xA1), b(0x01),             // Collection (Application)
            b(0x85), b(0x13),             //   Report ID (19)

            // Buttons (2 bytes — 16 buttons)
            b(0x05), b(0x09),             //   Usage Page (Button)
            b(0x19), b(0x01),             //   Usage Minimum (1)
            b(0x29), b(0x10),             //   Usage Maximum (16)
            b(0x15), b(0x00),             //   Logical Minimum (0)
            b(0x25), b(0x01),             //   Logical Maximum (1)
            b(0x95), b(0x10),             //   Report Count (16)
            b(0x75), b(0x01),             //   Report Size (1)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // LX, LY (bytes 2-3)
            b(0x05), b(0x01),             //   Usage Page (Generic Desktop)
            b(0x09), b(0x30),             //   Usage (X)
            b(0x09), b(0x31),             //   Usage (Y)
            b(0x15), b(0x81),             //   Logical Minimum (-127)
            b(0x25), b(0x7F),             //   Logical Maximum (127)
            b(0x75), b(0x08),             //   Report Size (8)
            b(0x95), b(0x02),             //   Report Count (2)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // Hat switch (byte 4)
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

            // Right stick Rx, Ry — bytes 5-6 (Linux)
            b(0x05), b(0x01),             //   Usage Page (Generic Desktop)
            b(0x09), b(0x33),             //   Usage (Rx)
            b(0x09), b(0x34),             //   Usage (Ry)
            b(0x15), b(0x81),             //   Logical Minimum (-127)
            b(0x25), b(0x7F),             //   Logical Maximum (127)
            b(0x75), b(0x08),             //   Report Size (8)
            b(0x95), b(0x02),             //   Report Count (2)
            b(0x81), b(0x02),             //   Input (Data,Var,Abs)

            // Brake, Accelerator — bytes 7-8
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

    }
}
