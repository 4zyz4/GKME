package com.zyz4.gkme.service

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.zyz4.gkme.data.PairingStateRepository
import com.zyz4.gkme.data.SettingsRepository
import com.zyz4.gkme.model.AppSettings
import com.zyz4.gkme.model.AudioOutput
import com.zyz4.gkme.model.ConnectionMode
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.TargetPlatform
import com.zyz4.gkme.model.VibrationDeviceType
import com.zyz4.gkme.model.gameVibrationDeviceFor
import com.zyz4.gkme.model.voiceCoilDeviceFor
import com.zyz4.gkme.proto.ClientToServer
import com.zyz4.gkme.proto.GamepadInput
import com.zyz4.gkme.proto.Hello
import com.zyz4.gkme.proto.ServerToClient
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

/** LED state of the emulated controller as reported by the PC. [color] is 0xRRGGBB
 *  (0x000000 for controllers without an LED, e.g. Xbox 360). [playerLed] is the
 *  player-indicator bitmask (bit0 = LED 1 …). */
data class LedState(
    val color: Int = 0,
    val playerLed: Int = 0,
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

    // 持久化的鼠标按键电平状态。普通手柄状态包会以较高速率持续发送，
    // 若其中包含 mouse_buttons=0 会把 Windows 端"按住"的按键清零，造成连点。
    // 因此在每次发送普通状态包时注入当前真实电平。
    // 鼠标状态更新回调 —— sendMouseReport 通过它把鼠标字段写入 ViewModel 的
    // _gamepadState，使鼠标数据跟普通手柄数据合并到同一个 UDP 包里发送。
    var onMouseReport: ((button: Int, dx: Int, dy: Int, wheel: Int, hWheel: Int) -> Unit)? = null

    private var _lastMouseButtonsBt = 0

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Whether a physical gamepad is currently connected; selects the active device set. */
    @Volatile
    var physicalControllerConnected: Boolean = false
        private set

    private val _ledState = MutableStateFlow(LedState())
    val ledState: StateFlow<LedState> = _ledState.asStateFlow()

    // Audio DSP runs on its own thread so a burst of audio frames can never block
    // the UDP receive loop (which also carries vibration/LED/control messages).
    // The write into AudioTrack is blocking, so this thread now paces itself to
    // real time. The queue absorbs WiFi bursts; DiscardOldestPolicy stays as a
    // last-resort latency bound (drop the stalest frame, not the newest).
    private val audioExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        { r -> Thread(r, "GkmeAudioDsp").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    init {
        _settings.value = runBlocking(Dispatchers.IO) {
            settingsRepository.settings.first()
        }
        applyEffectiveAudioSettings()
        audioPlaybackService.onControllerMotorOutput = { controllerIndex, leftAmp, rightAmp ->
            onControllerVibrationRequest?.invoke(controllerIndex, leftAmp, rightAmp)
        }
        audioPlaybackService.onVoiceCoilAmplitudes = { leftAmp, rightAmp ->
            onVoiceCoilMotorOutputUpdate?.invoke(leftAmp, rightAmp)
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
        applyEffectiveAudioSettings()
        stopVibrationIfDisabled()
        scope.launch {
            settingsRepository.saveSettings(newSettings)
        }
    }

    /** Called when a physical controller connects/disconnects so the audio/vibration
     *  settings switch to the matching saved set. */
    fun setPhysicalControllerConnected(connected: Boolean) {
        if (physicalControllerConnected == connected) return
        physicalControllerConnected = connected
        applyEffectiveAudioSettings()
        stopVibrationIfDisabled()
    }

    fun clearTriggerEffects() {
        onTriggerEffectsRequest?.invoke(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        )
    }

    private fun applyEffectiveAudioSettings() {
        val s = _settings.value
        audioPlaybackService.setSettings(
            voiceCoilDevice = s.voiceCoilDeviceFor(physicalControllerConnected),
            voiceCoilSwap = s.swapVoiceCoilMotors,
            controllerAudio = s.controllerAudioOutput,
            motorOutputEnabled = s.gameVibrationDeviceFor(physicalControllerConnected).type != VibrationDeviceType.NONE,
        )
    }

    private fun stopVibrationIfDisabled() {
        if (_settings.value.gameVibrationDeviceFor(physicalControllerConnected).type == VibrationDeviceType.NONE) {
            vibrator.cancel()
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
            udpService.start(getRealDeviceName(), getMacAddress()) { msg ->
                handleServerToClient(msg)
            }
            if (udpService.portInUse) {
                _connectionState.value = _connectionState.value.copy(
                    phase = ConnectionPhase.ERROR,
                    statusText = "端口 ${UdpService.PORT} 被占用，可能另一个实例已在运行"
                )
                return
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
        clearTriggerEffects()
    }

    private fun stopBluetooth() {
        btPhaseJob?.cancel()
        btPhaseJob = null
        bluetoothService?.stop()
        bluetoothService = null
        vibrator.cancel()
    }

    private fun handleServerToClient(msg: ServerToClient) {
        // 电脑发来 Hello（或任何非断开消息）而手机尚未进入 WiFi 协议时，手机再次
        // 发送带设备名和 MAC 的 Hello 完成握手；手动输入 IP 连接时电脑端就是从
        // 这里读取设备名和 MAC。已连接后不再回应，避免与电脑的 Hello 应答形成循环。
        if (msg.payloadCase != ServerToClient.PayloadCase.DISCONNECT &&
            activeProtocol != ActiveProtocol.WIFI
        ) {
            doReconnect()
        }
        when (msg.payloadCase) {
            ServerToClient.PayloadCase.COMPACT_FRAME -> {
                val cf = msg.compactFrame
                val rumbleLow = if (cf.hasVibration()) cf.vibration.largeMotor.toInt() else 0
                val rumbleHigh = if (cf.hasVibration()) cf.vibration.smallMotor.toInt() else 0
                if (cf.hasVibration() && _settings.value.gameVibrationDeviceFor(physicalControllerConnected).type != VibrationDeviceType.NONE) {
                    onRumbleRequest?.invoke(rumbleLow, rumbleHigh)
                }
                if (cf.hasTriggerEffects() && cf.triggerEffects.leftTriggerEffect.size() > 0) {
                    onTriggerEffectsRequest?.invoke(
                        cf.triggerEffects.leftTriggerEffect.toByteArray(),
                        cf.triggerEffects.rightTriggerEffect.toByteArray(),
                    )
                }
                if (cf.hasTriggerEffects() && cf.triggerEffects.leftTriggerEffect.size() == 0 && cf.triggerEffects.rightTriggerEffect.size() == 0) {
                    onTriggerEffectsRequest?.invoke(
                        byteArrayOf(),
                        byteArrayOf(),
                    )
                }
                if (cf.hasLedState()) {
                    _ledState.value = LedState(
                        color = cf.ledState.color.toInt() and 0xFFFFFF,
                        playerLed = cf.ledState.playerLed.toInt(),
                    )
                }
                if (cf.hasTestTone()) {
                    audioPlaybackService.setTestTone(cf.testTone.enabled)
                }
            }
            ServerToClient.PayloadCase.AUDIO_FRAME -> {
                val af = msg.audioFrame
                val pcm = af.pcm.toByteArray()
                logAudioFrameDiag(af.frameIndex, af.sampleCount, af.sampleRateHz.toInt(),
                    af.channels.toInt(), pcm.size)
                if (pcm.isNotEmpty()) {
                    val rate = af.sampleRateHz.toInt()
                    val ch = af.channels.toInt()
                    val bits = af.bitsPerSample.toInt()
                    audioExecutor.execute {
                        audioPlaybackService.submitAudio(pcm, rate, ch, bits)
                    }
                }
            }
            ServerToClient.PayloadCase.LED_STATE -> {
                val led = msg.ledState
                _ledState.value = LedState(
                    color = led.color.toInt() and 0xFFFFFF,
                    playerLed = led.playerLed.toInt(),
                )
            }
            ServerToClient.PayloadCase.TEST_TONE -> {
                audioPlaybackService.setTestTone(msg.testTone.enabled)
            }
            ServerToClient.PayloadCase.TRIGGER_EFFECTS -> {
                val te = msg.triggerEffects
                onTriggerEffectsRequest?.invoke(
                    te.leftTriggerEffect.toByteArray(),
                    te.rightTriggerEffect.toByteArray(),
                )
            }
            ServerToClient.PayloadCase.DISCONNECT -> {
                stopAutoReconnect()
                udpService.clearPcAddress()
                udpService.resumeBroadcast()
                activeProtocol = ActiveProtocol.NONE
                _connectionState.value = ConnectionState(statusText = "已断开")
                clearTriggerEffects()
            }
            else -> {}
        }
    }

    private var lastAudioDiagAt = 0L
    private var audioFramesSinceDiag = 0
    private var audioBytesSinceDiag = 0L
    private var lastAudioFrameIndex = -1
    private var missingAudioFrames = 0L

    private fun logAudioFrameDiag(frameIndex: Int, sampleCount: Int, rate: Int, channels: Int, bytes: Int) {
        audioFramesSinceDiag++
        audioBytesSinceDiag += bytes
        if (lastAudioFrameIndex >= 0) {
            val gap = frameIndex - lastAudioFrameIndex - 1
            if (gap in 1..10000) missingAudioFrames += gap
        }
        lastAudioFrameIndex = frameIndex
        val now = System.currentTimeMillis()
        if (lastAudioDiagAt == 0L) {
            lastAudioDiagAt = now
            return
        }
        if (now - lastAudioDiagAt < 2000) return
        val elapsed = now - lastAudioDiagAt
        android.util.Log.i(
            "GkmeAudio",
            "Audio in: ${audioFramesSinceDiag * 1000L / elapsed}/s ${audioBytesSinceDiag * 1000L / elapsed} B/s " +
                "missing=$missingAudioFrames frameIndex=$frameIndex samples=$sampleCount rate=$rate ch=$channels pcm=$bytes"
        )
        audioFramesSinceDiag = 0
        audioBytesSinceDiag = 0
        missingAudioFrames = 0
        lastAudioDiagAt = now
    }

    private fun doReconnect() {
        activeProtocol = ActiveProtocol.WIFI
        udpService.setConnected(true)
        stopAutoReconnect()
        _connectionState.value = _connectionState.value.copy(
            connected = true, phase = ConnectionPhase.CONNECTED,
            statusText = "已连接（WiFi）"
        )
        sendDeviceHello()
    }

    /** Sends the ClientToServer Hello carrying the device name and MAC. Sent when
     *  the PC's Hello establishes the connection (handshake) and on auto-reconnect,
     *  so the PC can learn the phone identity even for manual IP connections. */
    private fun sendDeviceHello() {
        CoroutineScope(Dispatchers.IO).launch {
            val hello = Hello.newBuilder()
                .setProtocolVersion(1)
                .setDeviceName(getRealDeviceName())
                .setMacAddress(getMacAddress())
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
                    sendDeviceHello()
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
    var onControllerVibrationRequest: ((controllerIndex: Int, leftAmp: Int, rightAmp: Int) -> Unit)? = null
    var onVoiceCoilMotorOutputUpdate: ((leftAmp: Int, rightAmp: Int) -> Unit)? = null
    var onTriggerEffectsRequest: ((left: ByteArray?, right: ByteArray?) -> Unit)? = null

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
                            batteryLevel = state.batteryLevel,
                            isCharging = state.isCharging,
                            touches = state.touchesList.map { tp ->
                                com.zyz4.gkme.model.TouchPoint(
                                    id = tp.id, x = tp.x, y = tp.y, active = tp.active
                                )
                            }
                        )
                        dsuService?.updateGamepadState(gs)
                    }
                    else -> {
                        if (activeProtocol != ActiveProtocol.WIFI) return
                        if (udpService.pcAddress == null) return
                        if (state.pressedScanCodesCount > 0 || state.keyboardModifiers != 0) {
                            android.util.Log.d("ConnectionManager", "sendGamepadState: keyboard pressed=${state.pressedScanCodesList}, mod=${state.keyboardModifiers}")
                        }
                        udpService.sendGamepadInput(state)
                    }
                }
            }
            ConnectionMode.BLUETOOTH -> {
                val phase = _connectionState.value.phase
                if (phase != ConnectionPhase.CONNECTED) return
                val target = _settings.value.targetPlatform
                if (target == TargetPlatform.UNIVERSAL_KM) return
                val report = GamepadStateMapper.map(state, target)
                bluetoothService?.sendReport(report)
            }
        }
    }

    /** Send a mouse report for WiFi/UDP mode. */
    fun sendMouseReport(
        button: Byte, dx: Byte, dy: Byte, wheel: Byte, hWheel: Byte = 0
    ) {
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> {
                val phase = _connectionState.value.phase
                if (phase != ConnectionPhase.CONNECTED) return
                var btn = button.toInt()
                if (btn == 0 && (dx != 0.toByte() || dy != 0.toByte())) {
                    btn = _lastMouseButtonsBt
                } else {
                    _lastMouseButtonsBt = btn
                }
                bluetoothService?.sendMouseReport(btn.toByte(), dx, dy, wheel, hWheel)
            }
            ConnectionMode.WIFI -> {
                // 鼠标数据不再走独立 UDP 包，而是写入 ViewModel 的 _gamepadState。
                // 普通手柄循环包（每 ~10ms）自动带上最新的鼠标字段，合并为一个包。
                if (activeProtocol != ActiveProtocol.WIFI) return
                onMouseReport?.invoke(
                    button.toInt(), dx.toInt(), dy.toInt(), wheel.toInt(), hWheel.toInt()
                )
            }
        }
    }

    fun sendKeyboardReport(modifier: Byte, keys: ByteArray) {
        when (_settings.value.connectionMode) {
            ConnectionMode.BLUETOOTH -> {
                val phase = _connectionState.value.phase
                if (phase != ConnectionPhase.CONNECTED) return
                bluetoothService?.sendKeyboardReport(modifier, keys)
            }
            ConnectionMode.WIFI -> {
                scope.launch {
                    udpService.sendKeyboardReport(modifier, keys)
                }
            }
        }
    }

    private fun getRealDeviceName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: Build.MODEL
        } else {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name ?: Build.MODEL
        }
    }

    /** Stable identity of the physical phone, broadcast to the PC (the "MAC"
     *  field of `GKME|name|mac`). Uses ANDROID_ID, which survives reboots and app
     *  updates and is unique per app-signing-key + user. */
    fun getMacAddress(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.uppercase()
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun getServerIp(): String {
        return getAllLocalIpAddresses().firstOrNull() ?: ""
    }

    fun getAllLocalIpAddresses(): List<String> {
        return getAllLocalIpAddressesInternal()
    }
}
