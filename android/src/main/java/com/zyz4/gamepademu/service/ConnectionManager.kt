package com.zyz4.gamepademu.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.zyz4.gamepademu.data.PairingStateRepository
import com.zyz4.gamepademu.data.SettingsRepository
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.ControllerMode
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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
    private val tcpServer = TcpServerService()
    private var bluetoothService: BluetoothHidService? = null
    val isBluetoothRunning: Boolean get() = bluetoothService != null
    private var serverJob: Job? = null
    private var broadcastJob: Job? = null
    private var btPhaseJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private var currentPollingRate = 144
    private var _seq = 0L
    private val _rttRing = LongArray(64) { -1L }
    private var _rttEma = 0.0

    private val _pollingIntervalMs = MutableStateFlow(1000 / 144)
    val pollingIntervalMs: StateFlow<Int> = _pollingIntervalMs.asStateFlow()

    companion object {
        private const val MIN_RATE = 30
        private const val MAX_RATE = 120
        private const val EMA_ALPHA = 0.2
        private const val RTT_TARGET_MS = 8.0
        private const val RTT_TARGET_MS_HIGH = 5.0
        private const val RTT_BACKOFF_MS = 25.0
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
        }
    }

    private suspend fun startWifiServer(settings: AppSettings) {
        _rttEma = 0.0
        currentPollingRate = MAX_RATE
        _pollingIntervalMs.value = 1000 / MAX_RATE
        val port = settings.wifiServerPort
        try {
            startBroadcast(settings.deviceName, port)
            _connectionState.value = _connectionState.value.copy(
                phase = ConnectionPhase.LISTENING,
                statusText = "服务已启动，等待连接..."
            )
            tcpServer.start(port,
                onClientConnected = {
                    _connectionState.value = _connectionState.value.copy(
                        connected = true, phase = ConnectionPhase.CONNECTED,
                        statusText = "已连接 (WiFi)"
                    )
                    val protoMode = com.zyz4.gamepademu.proto.ControllerMode.forNumber(
                        settings.controllerMode.ordinal
                    ) ?: com.zyz4.gamepademu.proto.ControllerMode.XBOX_360
                    val hello = Hello.newBuilder()
                        .setProtocolVersion(1)
                        .setDeviceName(Build.MODEL)
                        .setControllerMode(protoMode)
                        .build()
                    val msg = ClientToServer.newBuilder()
                        .setHello(hello)
                        .build()
                    scope.launch { tcpServer.send(msg) }
                },
                onClientDisconnected = {
                    _connectionState.value = _connectionState.value.copy(
                        connected = false, phase = ConnectionPhase.DISCONNECTED,
                        statusText = "已断开"
                    )
                },
                onMessage = { data -> handleClientMessage(data) }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _connectionState.value = _connectionState.value.copy(
                connected = false, phase = ConnectionPhase.ERROR,
                statusText = "服务异常: ${e.message}"
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
        stopBluetooth()

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
        if (data.size < 8) return
        if (!_settings.value.gameVibrationEnabled) return
        val leftMotor = data[1].toInt() and 0xFF
        val rightMotor = data[2].toInt() and 0xFF
        if (leftMotor > 0 || rightMotor > 0) {
            triggerVibration(leftMotor, rightMotor)
        }
    }

    private fun startBroadcast(deviceName: String, port: Int) {
        broadcastJob?.cancel()
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                val msg = "GAMEPAD_SERVER:$deviceName".toByteArray()
                while (true) {
                    val packet = DatagramPacket(msg, msg.size,
                        InetAddress.getByName("255.255.255.255"), port)
                    socket.send(packet)
                    delay(1000.milliseconds)
                }
            } catch (_: Exception) {}
        }
    }

    fun unpairDevice() {
        scope.launch {
            pairingStateRepository.clearPairedDevice()
            stopBluetooth()
            _connectionState.value = ConnectionState()
        }
    }

    fun stopServer() {
        broadcastJob?.cancel()
        broadcastJob = null
        serverJob?.cancel()
        serverJob = null
        btPhaseJob?.cancel()
        btPhaseJob = null
        tcpServer.stop()
        stopBluetooth()
        _connectionState.value = ConnectionState()
    }

    private fun stopBluetooth() {
        btPhaseJob?.cancel()
        btPhaseJob = null
        bluetoothService?.stop()
        bluetoothService = null
    }

    var onControllerModeChanged: ((ControllerMode) -> Unit)? = null

    private fun handleClientMessage(data: ByteArray) {
        try {
            val msg = ServerToClient.parseFrom(data)
            when (msg.payloadCase) {
                ServerToClient.PayloadCase.VIBRATION -> {
                    if (_settings.value.gameVibrationEnabled) {
                        val v = msg.vibration
                        triggerVibration(v.largeMotor, v.smallMotor)
                    }
                }
                ServerToClient.PayloadCase.DISCONNECT -> {
                    tcpServer.stop()
                    _connectionState.value = ConnectionState(
                        statusText = "已断开"
                    )
                }
                ServerToClient.PayloadCase.SET_CONTROLLER_MODE -> {
                    val protoMode = msg.setControllerMode.mode
                    val newMode = if (protoMode == com.zyz4.gamepademu.proto.ControllerMode.DS4)
                        ControllerMode.DS4 else ControllerMode.XBOX_360
                    _settings.value = _settings.value.copy(controllerMode = newMode)
                    scope.launch { settingsRepository.saveSettings(_settings.value) }
                    resendHello(newMode)
                    onControllerModeChanged?.invoke(newMode)
                }
                ServerToClient.PayloadCase.RTT_REPORT -> {
                    val r = msg.rttReport
                    val idx = (r.ackSeq % 64).toInt()
                    val sendTime = _rttRing[idx]
                    if (sendTime >= 0) {
                        val rtt = (System.nanoTime() - sendTime) / 1_000_000.0
                        _rttRing[idx] = -1L
                        updateRateByRtt(rtt)
                    }
                }
                ServerToClient.PayloadCase.SERVER_HELLO -> {
                    val h = msg.serverHello
                    val recommendedUs = h.recommendedUplinkIntervalUs
                    if (recommendedUs > 0) {
                        val recommendedMs = (recommendedUs / 1000.0).coerceIn(1.0, (1000.0 / MIN_RATE))
                        val newRate = (1000.0 / recommendedMs).toInt().coerceIn(MIN_RATE, MAX_RATE)
                        currentPollingRate = newRate
                        _pollingIntervalMs.value = 1000 / newRate
                    }
                }
                else -> {}
            }
        } catch (_: Exception) {}
    }

    private fun resendHello(mode: ControllerMode) {
        val protoMode = com.zyz4.gamepademu.proto.ControllerMode.forNumber(mode.ordinal)
            ?: com.zyz4.gamepademu.proto.ControllerMode.XBOX_360
        val hello = Hello.newBuilder()
            .setProtocolVersion(1)
            .setDeviceName(Build.MODEL)
            .setControllerMode(protoMode)
            .build()
        val msg = ClientToServer.newBuilder()
            .setHello(hello)
            .build()
        scope.launch { tcpServer.send(msg) }
    }

    private var _vibSpeed = -1

    fun triggerVibration(largeMotor: Int, smallMotor: Int) {
        val speed = maxOf(largeMotor, smallMotor).coerceIn(0, 255)

        if (speed < 1) {
            vibrator.cancel()
            _vibSpeed = -1
            return
        }

        if (speed == _vibSpeed) return

        _vibSpeed = speed
        val effect = VibrationEffect.createWaveform(
            longArrayOf(1000),
            intArrayOf(speed),
            0
        )
        vibrator.cancel()
        vibrator.vibrate(effect)
    }

    private fun updateRateByRtt(rttMs: Double) {
        _rttEma = EMA_ALPHA * rttMs + (1.0 - EMA_ALPHA) * _rttEma

        val newRate = if (_rttEma < RTT_TARGET_MS_HIGH) {
            // 很好，向最高速率靠近
            minOf((currentPollingRate * 1.15).toInt(), MAX_RATE)
        } else if (_rttEma < RTT_TARGET_MS) {
            // 略高，微调上升
            minOf((currentPollingRate * 1.05).toInt(), MAX_RATE)
        } else if (_rttEma > RTT_BACKOFF_MS) {
            // 太高，大幅降速
            maxOf((currentPollingRate * 0.7).toInt(), MIN_RATE)
        } else {
            // 略高，缓慢降速
            maxOf((currentPollingRate * 0.92).toInt(), MIN_RATE)
        }

        if (newRate != currentPollingRate) {
            currentPollingRate = newRate
            _pollingIntervalMs.value = 1000 / newRate
        }
    }

    suspend fun sendGamepadState(state: GamepadInput) {
        when (_settings.value.connectionMode) {
            ConnectionMode.WIFI -> {
                if (tcpServer.isClientConnected) {
                    _seq++
                    val idx = (_seq % 64).toInt()
                    _rttRing[idx] = System.nanoTime()
                    val msg = ClientToServer.newBuilder()
                        .setGamepadInput(state.toBuilder().setSeq(_seq).build()).build()
                    tcpServer.send(msg)
                }
            }
            ConnectionMode.BLUETOOTH -> {
                val target = _settings.value.targetPlatform
                val report = GamepadStateMapper.map(state, target)
                bluetoothService?.sendReport(report)
            }
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
