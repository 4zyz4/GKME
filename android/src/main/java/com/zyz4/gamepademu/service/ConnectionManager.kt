package com.zyz4.gamepademu.service

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.zyz4.gamepademu.data.PairingStateRepository
import com.zyz4.gamepademu.data.SettingsRepository
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.proto.ClientToServer
import com.zyz4.gamepademu.proto.GamepadInput
import com.zyz4.gamepademu.proto.Hello
import com.zyz4.gamepademu.proto.ServerToClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

data class ConnectionState(
    val connected: Boolean = false,
    val statusText: String = "未启动",
    val batteryLevel: Int = 100,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val transportType: BluetoothTransportType? = null,
)

@Singleton
class ConnectionManager @Inject constructor(
    private val context: Context,
    private val pairingStateRepository: PairingStateRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val pairedDeviceName: StateFlow<String?> = pairingStateRepository.pairedDeviceName
        .stateIn(scope, SharingStarted.Eagerly, null)
    private val udpService = UdpService()
    private var bluetoothService: BluetoothHidService? = null
    val isBluetoothRunning: Boolean get() = bluetoothService != null
    private var dsuService: DsuService? = null
    private var serverJob: Job? = null
    private var btPhaseJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private var currentPollingRate = 125
    private val _seq = java.util.concurrent.atomic.AtomicLong(0L)
    private val _rttRing = LongArray(64) { -1L }

    companion object {
        const val POLLING_INTERVAL_MS = 8
        const val CONNECTION_TIMEOUT_MS = 3000L
    }

    init {
        _settings.value = runBlocking(Dispatchers.IO) {
            settingsRepository.settings.first()
        }
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        if (!newSettings.gameVibrationEnabled) {
            vibrator.cancel()
        }
        _settings.value = newSettings
        scope.launch {
            settingsRepository.saveSettings(newSettings)
        }
    }

    fun startServer(scope: CoroutineScope) {
        val s = _settings.value
        _connectionState.value = _connectionState.value.copy(statusText = "启动服务...")
        when (s.connectionMode) {
            ConnectionMode.WIFI -> {
                serverJob = scope.launch { startWifiServer(s) }
            }
            ConnectionMode.BLUETOOTH -> {
                startBluetooth(scope, s)
            }
            ConnectionMode.CEMUHOOK -> {
                serverJob = scope.launch { startDsuServer(s) }
            }
        }
    }

    private suspend fun startWifiServer(settings: AppSettings) {
        currentPollingRate = 1000 / POLLING_INTERVAL_MS
        try {
            val ip = getServerIp()
            if (ip.isEmpty()) {
                _connectionState.value = _connectionState.value.copy(
                    phase = ConnectionPhase.ERROR,
                    statusText = "无法获取本机 IP"
                )
                return
            }
            udpService.start(ip, getRealDeviceName()) { msg ->
                handleServerToClient(msg)
            }
            _connectionState.value = _connectionState.value.copy(
                phase = ConnectionPhase.LISTENING,
                statusText = "服务已启动，等待连接..."
            )
            while (true) {
                delay(1000)
                if (udpService.pcAddress != null &&
                    System.currentTimeMillis() - udpService.lastReceiveTime > CONNECTION_TIMEOUT_MS) {
                    udpService.clearPcAddress()
                    _connectionState.value = _connectionState.value.copy(
                        connected = false, phase = ConnectionPhase.LISTENING,
                        statusText = "连接已断开，等待重连..."
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _connectionState.value = _connectionState.value.copy(
                connected = false, phase = ConnectionPhase.ERROR,
                statusText = "服务异常: ${e.message}"
            )
        }
    }

    private suspend fun startDsuServer(settings: AppSettings) {
        try {
            val ip = getServerIp()
            if (ip.isEmpty()) {
                _connectionState.value = _connectionState.value.copy(
                    phase = ConnectionPhase.ERROR,
                    statusText = "无法获取本机 IP"
                )
                return
            }
            dsuService = DsuService(
                scope = scope,
                serverIp = ip,
                onRumble = { largeMotor, smallMotor ->
                    if (_settings.value.gameVibrationEnabled) {
                        onRumbleRequest?.invoke(largeMotor, smallMotor)
                    }
                },
                onError = { msg ->
                    _connectionState.value = _connectionState.value.copy(
                        statusText = "DSU 错误: $msg"
                    )
                },
                onConnected = {
                    _connectionState.value = _connectionState.value.copy(
                        connected = true,
                        phase = ConnectionPhase.CONNECTED,
                        statusText = "已连接 (DSU)"
                    )
                }
            )
            val started = dsuService?.start() ?: false
            if (!started) {
                _connectionState.value = _connectionState.value.copy(
                    phase = ConnectionPhase.ERROR,
                    statusText = "DSU 服务启动失败"
                )
                return
            }
            _connectionState.value = _connectionState.value.copy(
                phase = ConnectionPhase.LISTENING,
                statusText = "DSU 服务已启动，等待连接..."
            )
            while (true) {
                delay(1000)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _connectionState.value = _connectionState.value.copy(
                connected = false, phase = ConnectionPhase.ERROR,
                statusText = "DSU 服务异常: ${e.message}"
            )
        }
    }

    private fun startBluetooth(scope: CoroutineScope, settings: AppSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _connectionState.value = _connectionState.value.copy(
                connected = false, statusText = "蓝牙 HID 需要 Android 9+"
            )
            return
        }
        if (bluetoothService != null) return

        val transport: BluetoothHidService = ClassicHidTransport(context, pairingStateRepository)
        bluetoothService = transport

        _connectionState.value = _connectionState.value.copy(
            transportType = transport.transportType
        )

        transport.start(settings) { outputReport -> handleBtOutputReport(outputReport) }

        btPhaseJob = scope.launch {
            transport.connectionPhase.collect { phase ->
                updateBtState(phase)
            }
        }
    }

    private fun updateBtState(phase: ConnectionPhase) {
        val (connected, text) = when (phase) {
            ConnectionPhase.IDLE -> false to "未启动"
            ConnectionPhase.REQUESTING_PERMISSIONS -> false to "请求蓝牙权限..."
            ConnectionPhase.REGISTERING_PROFILE -> false to "正在注册 HID 配置文件..."
            ConnectionPhase.RECONNECTING -> false to "正在自动回连已配对设备..."
            ConnectionPhase.LISTENING -> false to "等待主机连接..."
            ConnectionPhase.DISCOVERABLE -> false to "等待主机连接 — 手机可被发现 (蓝牙)"
            ConnectionPhase.PAIRING -> false to "正在配对..."
            ConnectionPhase.CONNECTED -> true to "已连接 (蓝牙)"
            ConnectionPhase.DISCONNECTED -> false to "主机已断开"
            ConnectionPhase.ERROR -> false to "蓝牙错误"
        }
        _connectionState.value = _connectionState.value.copy(
            connected = connected,
            statusText = text,
            phase = phase,
        )
    }

    private fun handleBtOutputReport(data: ByteArray) {
    }

    fun unpairDevice() {
        scope.launch {
            pairingStateRepository.clearPairedDevice()
            stopBluetooth()
            _connectionState.value = ConnectionState()
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        btPhaseJob?.cancel()
        btPhaseJob = null
        udpService.stop()
        stopBluetooth()
        dsuService?.stop()
        dsuService = null
        vibrator.cancel()
        _connectionState.value = ConnectionState()
    }

    private fun stopBluetooth() {
        btPhaseJob?.cancel()
        btPhaseJob = null
        bluetoothService?.stop()
        bluetoothService = null
        vibrator.cancel()
    }

    private fun handleServerToClient(msg: ServerToClient) {
        if (msg.payloadCase != ServerToClient.PayloadCase.DISCONNECT &&
            !_connectionState.value.connected
        ) {
            doReconnect()
        }
        when (msg.payloadCase) {
            ServerToClient.PayloadCase.VIBRATION -> {
                if (_settings.value.gameVibrationEnabled) {
                    val v = msg.vibration
                    onRumbleRequest?.invoke(v.largeMotor, v.smallMotor)
                }
            }
            ServerToClient.PayloadCase.DISCONNECT -> {
                udpService.clearPcAddress()
                _connectionState.value = ConnectionState(statusText = "已断开")
            }
            ServerToClient.PayloadCase.RTT_REPORT -> {}
            else -> {}
        }
    }

    private fun doReconnect() {
        _connectionState.value = _connectionState.value.copy(
            connected = true, phase = ConnectionPhase.CONNECTED,
            statusText = "已连接 (WiFi)"
        )
        scope.launch {
            val hello = Hello.newBuilder()
                .setProtocolVersion(1)
                .setDeviceName(getRealDeviceName())
                .build()
            val msg = ClientToServer.newBuilder()
                .setHello(hello)
                .build()
            udpService.sendClientToServer(msg)
        }
    }

    var onRumbleRequest: ((largeMotor: Int, smallMotor: Int) -> Unit)? = null

    suspend fun sendGamepadState(state: GamepadInput) {
        when (_settings.value.connectionMode) {
            ConnectionMode.WIFI -> {
                if (udpService.pcAddress == null) return
                val input = state.toBuilder().setSeq(_seq.incrementAndGet()).build()
                udpService.sendGamepadInput(input)
            }
            ConnectionMode.BLUETOOTH -> {
                val target = _settings.value.targetPlatform
                val report = GamepadStateMapper.map(state, target)
                bluetoothService?.sendReport(report)
            }
            ConnectionMode.CEMUHOOK -> {
                val gs = GamepadState(
                    buttons = state.buttons.toUInt(),
                    leftStickX = state.leftStickX.toShort(),
                    leftStickY = state.leftStickY.toShort(),
                    rightStickX = state.rightStickX.toShort(),
                    rightStickY = state.rightStickY.toShort(),
                    leftTrigger = state.leftTrigger,
                    rightTrigger = state.rightTrigger,
                    dpad = state.dpad,
                    gyroX = state.gyroX,
                    gyroY = state.gyroY,
                    gyroZ = state.gyroZ,
                    accelX = state.accelX,
                    accelY = state.accelY,
                    accelZ = state.accelZ,
                    touchpadX = state.touchpadX,
                    touchpadY = state.touchpadY,
                    touchpadTouch = state.touchpadTouch,
                    touchpadClick = state.touchpadClick,
                    batteryLevel = state.batteryLevel,
                    isCharging = state.isCharging,
                    touches = state.touchesList.map { tp ->
                        com.zyz4.gamepademu.model.TouchPoint(
                            id = tp.id, x = tp.x, y = tp.y, active = tp.active
                        )
                    }
                )
                dsuService?.updateGamepadState(gs)
            }
        }
    }

    private fun getRealDeviceName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: BluetoothAdapter.getDefaultAdapter()?.name
                ?: Build.MODEL
        } else {
            BluetoothAdapter.getDefaultAdapter()?.name ?: Build.MODEL
        }
    }

    fun getServerIp(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) return addr.hostAddress ?: ""
                }
            }
            ""
        } catch (_: Exception) { "" }
    }
}
