package com.zyz4.gamepademu.service

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.zyz4.gamepademu.data.PairingStateRepository
import com.zyz4.gamepademu.data.SettingsRepository
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.AudioOutput
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.GamepadState
import com.zyz4.gamepademu.model.TargetPlatform
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

private enum class ActiveProtocol { NONE, WIFI, EMOTION }

data class ConnectionState(
    val connected: Boolean = false,
    val statusText: String = "未启动",
    val batteryLevel: Int = 100,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val transportType: BluetoothTransportType? = null,
    val restartToken: Int = 0,
)

@Singleton
class ConnectionManager @Inject constructor(
    private val context: Context,
    private val pairingStateRepository: PairingStateRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val audioPlaybackService = AudioPlaybackService().also { it.initContext(context) }

    val pairedDeviceName: StateFlow<String?> = pairingStateRepository.pairedDeviceName
        .stateIn(scope, SharingStarted.Eagerly, null)
    private val udpService = UdpService()
    private var bluetoothService: BluetoothHidService? = null
    val isBluetoothRunning: Boolean get() = bluetoothService != null
    private var dsuService: DsuService? = null
    private var serverJob: Job? = null
    private var btPhaseJob: Job? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null

    private var activeProtocol = ActiveProtocol.NONE

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        _settings.value = runBlocking(Dispatchers.IO) {
            settingsRepository.settings.first()
        }
        audioPlaybackService.setSettings(
            leftOutput = _settings.value.leftVoiceCoilOutput,
            rightOutput = _settings.value.rightVoiceCoilOutput,
            controllerAudio = _settings.value.controllerAudioOutput,
            gameVibrationEnabled = _settings.value.gameVibrationEnabled,
        )
        audioPlaybackService.onVibroOutput = { strong, weak ->
            onRumbleRequest?.invoke(strong, weak)
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
        audioPlaybackService.setSettings(
            leftOutput = newSettings.leftVoiceCoilOutput,
            rightOutput = newSettings.rightVoiceCoilOutput,
            controllerAudio = newSettings.controllerAudioOutput,
            gameVibrationEnabled = newSettings.gameVibrationEnabled,
        )
        _settings.value = newSettings
        scope.launch {
            settingsRepository.saveSettings(newSettings)
        }
    }

    /**
     * Switches the HID target platform while the Bluetooth service is running. The saved paired
     * device (software-level) is cleared and the HID profile is unregistered/re-registered with the
     * new descriptor — no app restart required.
     */
    fun switchTargetPlatform(platform: TargetPlatform) {
        val newSettings = _settings.value.copy(targetPlatform = platform)
        _settings.value = newSettings
        _connectionState.value = _connectionState.value.copy(
            restartToken = _connectionState.value.restartToken + 1
        )
        scope.launch {
            settingsRepository.saveSettings(newSettings)
            pairingStateRepository.clearPairedDevice()
            bluetoothService?.restart(newSettings) { outputReport -> handleBtOutputReport(outputReport) }
        }
    }

    fun startServer(scope: CoroutineScope) {
        val s = _settings.value
        activeProtocol = ActiveProtocol.NONE
        _connectionState.value = _connectionState.value.copy(statusText = "启动服务...")
        when (s.connectionMode) {
            ConnectionMode.WIFI -> {
                serverJob = scope.launch {
                    // Start both WiFi UDP and DSU servers simultaneously for auto-detection
                    startWifiServer(s)
                    startDsuServer(s)
                    watchdogJob = launch { watchdogLoop() }
                }
            }
            ConnectionMode.BLUETOOTH -> {
                startBluetooth(scope, s)
            }
        }
    }

    companion object {
        const val POLLING_INTERVAL_MS = 8
        const val CONNECTION_TIMEOUT_MS = 3000L
        const val EMOTION_TIMEOUT_MS = 5000L

        fun getAllLocalIpAddressesInternal(): List<String> {
            return try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                val addresses = mutableListOf<String>()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (intf.isLoopback || !intf.isUp) continue
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is java.net.Inet4Address) {
                            addresses.add(addr.hostAddress ?: "")
                        }
                    }
                }
                addresses
            } catch (_: Exception) { emptyList() }
        }
    }

    private suspend fun watchdogLoop() {
        while (true) {
            delay(1000)
            when (activeProtocol) {
                ActiveProtocol.WIFI -> {
                    if (udpService.pcAddress != null &&
                        System.currentTimeMillis() - udpService.lastReceiveTime > CONNECTION_TIMEOUT_MS) {
                        activeProtocol = ActiveProtocol.NONE
                        // 保留 pcAddress 用于自动重连握手，同时恢复广播让主机端可重新发现
                        udpService.resumeBroadcast()
                        startAutoReconnect()
                        _connectionState.value = _connectionState.value.copy(
                            connected = false, phase = ConnectionPhase.LISTENING,
                            statusText = "连接已断开，正在重连..."
                        )
                    }
                }
                ActiveProtocol.EMOTION -> {
                    val dsu = dsuService
                    if (dsu != null && dsu.lastPacketTime != 0L &&
                        System.currentTimeMillis() - dsu.lastPacketTime > EMOTION_TIMEOUT_MS) {
                        activeProtocol = ActiveProtocol.NONE
                        _connectionState.value = _connectionState.value.copy(
                            connected = false, phase = ConnectionPhase.LISTENING,
                            statusText = "连接已断开，等待重连..."
                        )
                    }
                }
                else -> {}
            }
        }
    }

    private suspend fun startWifiServer(settings: AppSettings) {
        try {
            val ip = getServerIp()
            if (ip.isEmpty()) {
                if (activeProtocol == ActiveProtocol.NONE) {
                    _connectionState.value = _connectionState.value.copy(
                        phase = ConnectionPhase.ERROR,
                        statusText = "无法获取本机 IP"
                    )
                }
                return
            }
            udpService.start(getRealDeviceName()) { msg ->
                handleServerToClient(msg)
            }
            if (activeProtocol == ActiveProtocol.NONE) {
                _connectionState.value = _connectionState.value.copy(
                    phase = ConnectionPhase.LISTENING,
                    statusText = "服务已启动，等待连接..."
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (activeProtocol == ActiveProtocol.NONE) {
                _connectionState.value = _connectionState.value.copy(
                    connected = false, phase = ConnectionPhase.ERROR,
                    statusText = "服务异常: ${e.message}"
                )
            }
        }
    }

    private suspend fun startDsuServer(settings: AppSettings) {
        try {
            val ip = getServerIp()
            if (ip.isEmpty()) return
            dsuService = DsuService(
                scope = scope,
                serverIp = ip,
                onRumble = { largeMotor, smallMotor ->
                    onRumbleRequest?.invoke(largeMotor, smallMotor)
                },
                onError = { msg ->
                    if (activeProtocol == ActiveProtocol.EMOTION) {
                        _connectionState.value = _connectionState.value.copy(
                            statusText = "Emotion 错误: $msg"
                        )
                    }
                },
                onConnected = {
                    activeProtocol = ActiveProtocol.EMOTION
                    _connectionState.value = _connectionState.value.copy(
                        connected = true,
                        phase = ConnectionPhase.CONNECTED,
                        statusText = "已连接（Emotion兼容）"
                    )
                }
            )
            dsuService?.start()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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
        watchdogJob?.cancel()
        watchdogJob = null
        btPhaseJob?.cancel()
        btPhaseJob = null
        stopAutoReconnect()
        udpService.stop()
        stopBluetooth()
        dsuService?.stop()
        dsuService = null
        vibrator.cancel()
        audioPlaybackService.stop()
        activeProtocol = ActiveProtocol.NONE
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
            activeProtocol != ActiveProtocol.WIFI
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
            ServerToClient.PayloadCase.AUDIO_FRAME -> {
                val af = msg.audioFrame
                audioPlaybackService.submitAudio(
                    pcm = af.pcm.toByteArray(),
                    sampleRate = af.sampleRateHz.toInt(),
                    channels = af.channels.toInt(),
                    bitsPerSample = af.bitsPerSample.toInt(),
                )
            }
            ServerToClient.PayloadCase.DISCONNECT -> {
                stopAutoReconnect()
                udpService.clearPcAddress()
                udpService.resumeBroadcast()
                activeProtocol = ActiveProtocol.NONE
                _connectionState.value = ConnectionState(statusText = "已断开")
            }
            else -> {}
        }
    }

    private fun doReconnect() {
        activeProtocol = ActiveProtocol.WIFI
        udpService.setConnected(true)
        stopAutoReconnect()
        _connectionState.value = _connectionState.value.copy(
            connected = true, phase = ConnectionPhase.CONNECTED,
            statusText = "已连接（WiFi）"
        )
        CoroutineScope(Dispatchers.IO).launch {
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

    private fun startAutoReconnect() {
        if (reconnectJob != null) return
        reconnectJob = scope.launch {
            while (true) {
                // 网络/IP 变化时旧 socket 不再收发，先重新绑定本地 IP 恢复通道
                udpService.refresh()
                val addr = udpService.pcAddress
                if (addr != null && activeProtocol != ActiveProtocol.WIFI) {
                    val hello = Hello.newBuilder()
                        .setProtocolVersion(1)
                        .setDeviceName(getRealDeviceName())
                        .build()
                    val msg = ClientToServer.newBuilder()
                        .setHello(hello)
                        .build()
                    udpService.sendClientToServer(msg)
                }
                delay(2000)
            }
        }
    }

    private fun stopAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    var onRumbleRequest: ((largeMotor: Int, smallMotor: Int) -> Unit)? = null

    suspend fun sendGamepadState(state: GamepadInput) {
        when (_settings.value.connectionMode) {
            ConnectionMode.WIFI -> {
                when (activeProtocol) {
                    ActiveProtocol.EMOTION -> {
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
                    else -> {
                        if (activeProtocol != ActiveProtocol.WIFI) return
                        if (udpService.pcAddress == null) return
                        udpService.sendGamepadInput(state)
                    }
                }
            }
            ConnectionMode.BLUETOOTH -> {
                val phase = _connectionState.value.phase
                if (phase != ConnectionPhase.CONNECTED) return
                val target = _settings.value.targetPlatform
                val report = GamepadStateMapper.map(state, target)
                bluetoothService?.sendReport(report)
            }
        }
    }

    /** Send a mouse HID report (Report ID 2) directly to the connected Bluetooth host. */
    fun sendMouseReport(button: Byte, dx: Byte, dy: Byte, wheel: Byte) {
        val s = _settings.value
        if (s.connectionMode != ConnectionMode.BLUETOOTH) return
        val phase = _connectionState.value.phase
        if (phase != ConnectionPhase.CONNECTED) return
        bluetoothService?.sendMouseReport(button, dx, dy, wheel, 0.toByte())
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
        return getAllLocalIpAddresses().firstOrNull() ?: ""
    }

    fun getAllLocalIpAddresses(): List<String> {
        return getAllLocalIpAddressesInternal()
    }
}
