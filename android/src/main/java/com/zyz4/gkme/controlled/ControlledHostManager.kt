package com.zyz4.gkme.controlled

import android.util.Log
import com.zyz4.gkme.proto.ClientToServer
import com.zyz4.gkme.proto.CompactFrame
import com.zyz4.gkme.proto.Disconnect
import com.zyz4.gkme.proto.GamepadInput
import com.zyz4.gkme.proto.ServerHello
import com.zyz4.gkme.proto.ServerToClient
import com.zyz4.gkme.proto.Vibration
import com.zyz4.gkme.service.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * 被控端主机：监听 GKME 协议，发现控制端设备，接收 [GamepadInput] 并通过 Shizuku
 * 注入到本地 uinput 虚拟手柄。行为对齐 GKME-Windows 的主机端。
 */
object ControlledHostManager {

    private const val TAG = "GKME_ControlledHost"

    const val PORT = 37284
    private const val BROADCAST_PREFIX = "GKME|"

    private const val TYPE_CLIENT_TO_SERVER: Byte = 0x00
    private const val TYPE_SERVER_TO_CLIENT: Byte = 0x01
    private const val TYPE_GAMEPAD_INPUT: Byte = 0x02

    private val BROADCAST_PREFIX_BYTES = BROADCAST_PREFIX.toByteArray(Charsets.UTF_8)

    private const val DEVICE_TIMEOUT_MS = 15_000L
    private const val INPUT_TIMEOUT_MS = 3_000L
    private const val UDP_SOCKET_BUFFER_SIZE = 4 * 1024 * 1024

    enum class Phase { IDLE, STARTING, LISTENING, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    data class HostState(val phase: Phase = Phase.IDLE, val statusText: String = "未启动")

    private val _devices = MutableStateFlow<List<ControlledDeviceCard>>(emptyList())
    val devices: StateFlow<List<ControlledDeviceCard>> = _devices.asStateFlow()

    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _session = MutableStateFlow<ControlledDevice?>(null)
    val session: StateFlow<ControlledDevice?> = _session.asStateFlow()

    /** 正在尝试连接的端点 IP（尚未建立会话），供合并卡片显示“连接中…”。 */
    private val _connectingIp = MutableStateFlow<String?>(null)
    val connectingIp: StateFlow<String?> = _connectingIp.asStateFlow()

    private val _shizukuStatus = MutableStateFlow("")
    val shizukuStatus: StateFlow<String> = _shizukuStatus.asStateFlow()

    /** Shizuku 增强保活（Doze 白名单 + 待机桶 active）的当前状态，供界面展示。 */
    private val _keepAliveStatus = MutableStateFlow("")
    val keepAliveStatus: StateFlow<String> = _keepAliveStatus.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, ControlledDevice>()
    private val sockets = mutableListOf<DatagramSocket>()
    private val jobs = mutableListOf<Job>()
    private var scope: CoroutineScope? = null
    private var connectJob: Job? = null
    private var rumbleJob: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var active: ControlledDevice? = null

    /** 最近一次收到 active 控制端的包（Hello 或 GamepadInput）的时间。
     *  与电脑端 GamepadSession._lastReceiveTime 一致，两类包都会刷新。 */
    @Volatile
    private var lastReceiveAt = 0L

    /** 输入超时后进入的“重连中”状态：保留会话与 active，等待控制端重新握手。
     *  对齐电脑端 GamepadSession 的 watchdog 行为（超时不销毁会话）。 */
    @Volatile
    private var reconnecting = false

    /** 防止多次 Hello/广播并发的重复重建。 */
    @Volatile
    private var recovering = false

    @Volatile
    private var portInUse = false

    fun start() {
        if (running) return
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        _state.value = HostState(Phase.STARTING, "正在启动服务…")
        bindSockets()
        if (sockets.isEmpty()) {
            _state.value = HostState(
                Phase.ERROR,
                if (portInUse) "端口 $PORT 被占用，可能已有服务在运行" else "无法获取本机 IP",
            )
            running = false
            scope = null
            return
        }
        _state.value = HostState(Phase.LISTENING, "正在扫描…")
        jobs += s.launch { pruneLoop() }
        jobs += s.launch { watchdogLoop() }
        updateShizukuStatus()
        _keepAliveStatus.value = KeepAliveInjector.statusText()
    }

    fun stop() {
        running = false
        connectJob?.cancel()
        connectJob = null
        rumbleJob?.cancel()
        rumbleJob = null
        active = null
        reconnecting = false
        recovering = false
        lastReceiveAt = 0L
        _session.value = null
        _connectingIp.value = null
        GamepadInjector.release()
        jobs.forEach { it.cancel() }
        jobs.clear()
        for (socket in sockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        sockets.clear()
        scope?.cancel()
        scope = null
        deviceMap.clear()
        _devices.value = emptyList()
        _state.value = HostState(Phase.IDLE, "未启动")
    }

    fun isRunning(): Boolean = running

    fun refresh() {
        if (!running) return
        val activeIp = active?.ip
        deviceMap.keys.retainAll { it == activeIp }
        publish()
        if (active == null) {
            _state.value = HostState(Phase.LISTENING, "正在扫描…")
        }
    }

    fun connect(device: ControlledDevice) {
        val s = scope ?: return
        connectJob?.cancel()
        connectJob = s.launch {
            try {
                if (active != null) disconnectInternal(null)
                _connectingIp.value = device.ip
                reconnecting = false
                _state.value = HostState(Phase.CONNECTING, "正在连接到 ${device.ip}…")
                val ready = awaitInjectorReady(8_000)
                if (!ready) {
                    _state.value = HostState(
                        Phase.ERROR,
                        "连接失败: ${device.ip}（${GamepadInjector.statusText()}）",
                    )
                    return@launch
                }
                active = device
                lastReceiveAt = System.currentTimeMillis()
                _session.value = device
                deviceMap[device.ip] = device
                sendServerHello(device.ip)
                startRumblePump(device)
                _state.value = HostState(Phase.CONNECTED, "已连接: ${device.ip}")
            } finally {
                if (_connectingIp.value == device.ip) _connectingIp.value = null
                publish()
            }
        }
    }

    fun connectManual(rawIp: String) {
        if (!running) return
        val ip = rawIp.trim()
        if (ip.isEmpty() || !isValidIp(ip)) {
            _state.value = HostState(_state.value.phase, "IP 地址格式无效")
            return
        }
        val device = deviceMap[ip] ?: ControlledDevice(ip = ip)
        connect(device)
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        scope?.launch { disconnectInternal("已断开") }
    }

    fun updateShizukuStatus() {
        _shizukuStatus.value = GamepadInjector.statusText()
    }

    // ── 内部实现 ──────────────────────────────────────────────

    private fun isValidIp(ip: String): Boolean = try {
        val addr = InetAddress.getByName(ip)
        addr is java.net.Inet4Address
    } catch (_: Exception) {
        false
    }

    private fun startsWith(buf: ByteArray, len: Int, prefix: ByteArray): Boolean {
        if (len < prefix.size) return false
        for (i in prefix.indices) {
            if (buf[i] != prefix[i]) return false
        }
        return true
    }

    private fun bindSockets() {
        portInUse = false
        val s = scope ?: return
        val allIps = ConnectionManager.getAllLocalIpAddressesInternal()
        for (localIp in allIps) {
            try {
                val socket = DatagramSocket(PORT, InetAddress.getByName(localIp))
                configureSocket(socket)
                sockets.add(socket)
                s.launch { receiveLoop(socket) }
            } catch (_: BindException) {
                portInUse = true
            } catch (_: Exception) {
            }
        }
        if (sockets.isEmpty()) {
            try {
                val socket = DatagramSocket(PORT)
                configureSocket(socket)
                sockets.add(socket)
                s.launch { receiveLoop(socket) }
            } catch (_: BindException) {
                portInUse = true
            } catch (_: Exception) {
            }
        }
    }

    private fun configureSocket(socket: DatagramSocket) {
        try {
            socket.broadcast = true
        } catch (_: Exception) {
        }
        try {
            socket.receiveBufferSize = UDP_SOCKET_BUFFER_SIZE
        } catch (_: Exception) {
        }
        try {
            socket.sendBufferSize = UDP_SOCKET_BUFFER_SIZE
        } catch (_: Exception) {
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running && scope?.isActive == true) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                socket.receive(dp)
                val len = dp.length
                if (len < 1) continue
                val srcIp = dp.address?.hostAddress ?: continue

                if (startsWith(buf, len, BROADCAST_PREFIX_BYTES)) {
                    handleBroadcast(srcIp, String(buf, 0, len))
                    continue
                }

                val type = buf[0]
                val payload = buf.copyOfRange(1, len)
                when (type) {
                    TYPE_CLIENT_TO_SERVER -> handleClientToServer(srcIp, payload)
                    TYPE_GAMEPAD_INPUT -> handleGamepadInput(srcIp, payload)
                }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleBroadcast(srcIp: String, text: String) {
        val parts = text.split('|')
        val name = parts.getOrNull(1)?.trim().orEmpty()
        val mac = parts.getOrNull(2)?.trim().orEmpty()
        val existing = deviceMap[srcIp]
        deviceMap[srcIp] = ControlledDevice(
            ip = srcIp,
            name = name.ifBlank { existing?.name.orEmpty() },
            mac = mac.ifBlank { existing?.mac.orEmpty() },
            lastSeen = System.currentTimeMillis(),
        )
        if (active == null && existing == null) {
            _state.value = HostState(Phase.LISTENING, "发现设备: $srcIp")
        }
        // 控制端在重连时会恢复广播（ConnectionManager.startAutoReconnect），此时
        // 重新下发 ServerHello 让它完成握手。对齐电脑端 HandleDiscovery 的行为。
        val current = active
        if (current != null && current.ip == srcIp) {
            sendServerHello(srcIp)
        }
        publish()
    }

    private fun handleClientToServer(srcIp: String, payload: ByteArray) {
        try {
            val cts = ClientToServer.parseFrom(payload)
            if (!cts.hasHello()) return
            val hello = cts.hello
            val existing = deviceMap[srcIp]
            val name = hello.deviceName.ifBlank { existing?.name.orEmpty() }
            val mac = hello.macAddress.ifBlank { existing?.mac.orEmpty() }
            deviceMap[srcIp] = ControlledDevice(
                ip = srcIp,
                name = name,
                mac = mac,
                lastSeen = System.currentTimeMillis(),
            )
            val current = active
            if (current != null && current.ip == srcIp) {
                val updated = current.copy(name = name, mac = mac)
                active = updated
                _session.value = updated
                lastReceiveAt = System.currentTimeMillis()
                // 收到 Hello 说明控制端已在（重新）握手：重建虚拟手柄并回 ServerHello
                // 让它完成握手。对齐电脑端 GamepadSession.HandleHello。
                recoverFromReconnect(srcIp)
            }
            publish()
        } catch (_: Exception) {
        }
    }

    /**
     * 控制端重新握手（收到 Hello / 重连广播）时，重建被超时释放的虚拟手柄并回
     * ServerHello。电脑端在同一路径上由 [GamepadSession.HandleHello] 重建控制器。
     */
    private fun recoverFromReconnect(srcIp: String) {
        if (recovering) return
        recovering = true
        val s = scope ?: run { recovering = false; return }
        s.launch {
            try {
                val ready = awaitInjectorReady(8_000)
                if (active?.ip != srcIp) return@launch
                if (ready) {
                    reconnecting = false
                    lastReceiveAt = System.currentTimeMillis()
                    sendServerHello(srcIp)
                    val dev = active ?: return@launch
                    startRumblePump(dev)
                    _state.value = HostState(Phase.CONNECTED, "已连接: $srcIp")
                } else {
                    _state.value = HostState(Phase.RECONNECTING, GamepadInjector.statusText())
                }
            } finally {
                recovering = false
            }
        }
    }

    private fun handleGamepadInput(srcIp: String, payload: ByteArray) {
        val current = active ?: return
        if (current.ip != srcIp) return
        try {
            val input = GamepadInput.parseFrom(payload)
            lastReceiveAt = System.currentTimeMillis()
            // 手柄尚未就绪（重连中/超时释放后）：只刷新保活时间，等待控制端的
            // Hello 重新握手。对齐电脑端 ReportGamepadInput 的 !IsReady 分支。
            if (reconnecting || !GamepadInjector.isReady()) return
            GamepadInjector.update(input)
        } catch (_: Exception) {
        }
    }

    private suspend fun awaitInjectorReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && scope?.isActive == true) {
            if (GamepadInjector.ensureReady()) return true
            _state.value = HostState(Phase.CONNECTING, GamepadInjector.statusText())
            updateShizukuStatus()
            delay(250)
        }
        return GamepadInjector.ensureReady()
    }

    private suspend fun disconnectInternal(reason: String?) {
        val device = active
        active = null
        reconnecting = false
        lastReceiveAt = 0L
        rumbleJob?.cancel()
        rumbleJob = null
        _session.value = null
        _connectingIp.value = null
        if (device != null) {
            try {
                send(device.ip, TYPE_SERVER_TO_CLIENT, disconnectMessage())
            } catch (_: Exception) {
            }
        }
        GamepadInjector.release()
        _state.value = if (running) {
            HostState(Phase.LISTENING, reason ?: "已断开")
        } else {
            HostState(Phase.IDLE, "未启动")
        }
    }

    private suspend fun pruneLoop() {
        while (scope?.isActive == true) {
            val now = System.currentTimeMillis()
            val activeIp = active?.ip
            var changed = false
            for ((ip, device) in deviceMap) {
                if (ip == activeIp) continue
                if (now - device.lastSeen > DEVICE_TIMEOUT_MS) {
                    deviceMap.remove(ip)
                    changed = true
                }
            }
            if (changed) publish()
            _shizukuStatus.value = GamepadInjector.statusText()
            // 保活若尚未生效（Shizuku 刚授权/服务刚连上），在这里兜底重试并刷新状态。
            KeepAliveInjector.ensureActive()
            _keepAliveStatus.value = KeepAliveInjector.statusText()
            delay(1000.milliseconds)
        }
    }

    private suspend fun watchdogLoop() {
        while (scope?.isActive == true) {
            val current = active
            if (current != null && !reconnecting &&
                System.currentTimeMillis() - lastReceiveAt > INPUT_TIMEOUT_MS
            ) {
                // 输入超时：释放虚拟手柄避免按键卡住，但保留会话与 active，等待
                // 控制端自动重连再次握手（收到 Hello 后重建）。对齐电脑端
                // GamepadSession.WatchdogLoop：超时只重置控制器，不销毁会话。
                reconnecting = true
                GamepadInjector.release()
                _state.value = HostState(Phase.RECONNECTING, "连接丢失: ${current.ip}")
            }
            delay(500.milliseconds)
        }
    }

    private fun startRumblePump(device: ControlledDevice) {
        rumbleJob?.cancel()
        rumbleJob = scope?.launch {
            var last = 0L
            var lastSendAt = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                // 重连中/手柄未就绪时不下发保活包，让控制端的 3s 看门狗触发自动
                // 重连并重新握手（电脑端 ReportLoop 也只在控制器存在时才发包）。
                if (!reconnecting && GamepadInjector.isReady()) {
                    val packed = GamepadInjector.pumpRumble()
                    // 震动变化时立即下发；否则每 500ms 发一次作为保活，避免控制端 3s 无包判定掉线。
                    if (packed != last || now - lastSendAt >= 500) {
                        last = packed
                        lastSendAt = now
                        val leftRaw = ((packed ushr 16) and 0xFFFF).toInt()
                        val rightRaw = (packed and 0xFFFF).toInt()
                        val left = (leftRaw * 255 + 32767) / 65535
                        val right = (rightRaw * 255 + 32767) / 65535
                        val frame = ServerToClient.newBuilder()
                            .setCompactFrame(
                                CompactFrame.newBuilder().setVibration(
                                    Vibration.newBuilder()
                                        .setLargeMotor(left)
                                        .setSmallMotor(right)
                                )
                            )
                            .build()
                        send(device.ip, TYPE_SERVER_TO_CLIENT, frame.toByteArray())
                    }
                }
                delay(8)
            }
        }
    }

    /** 按物理设备（MAC，未知时退回 IP）把多个 IP 端点合并成一张卡片。 */
    private fun publish() {
        val grouped = LinkedHashMap<String, MutableList<ControlledDevice>>()
        for (device in deviceMap.values.sortedBy { it.ip }) {
            grouped.getOrPut(device.groupKey) { mutableListOf() }.add(device)
        }
        _devices.value = grouped.map { (key, eps) ->
            ControlledDeviceCard(
                groupKey = key,
                name = eps.firstOrNull { it.name.isNotBlank() }?.name.orEmpty(),
                mac = eps.firstOrNull { it.mac.isNotBlank() }?.mac.orEmpty(),
                endpoints = eps,
            )
        }.sortedBy { it.title }
    }

    private fun sendServerHello(ip: String) {
        val msg = ServerToClient.newBuilder()
            .setServerHello(
                ServerHello.newBuilder()
                    .setProtocolVersion(1)
                    .setHostName(android.os.Build.MODEL ?: "Android")
                    .setMaxDownlinkRateHz(1000)
                    .setRecommendedUplinkIntervalUs(0)
            )
            .build()
        send(ip, TYPE_SERVER_TO_CLIENT, msg.toByteArray())
    }

    private fun disconnectMessage(): ByteArray {
        val msg = ServerToClient.newBuilder()
            .setDisconnect(Disconnect.newBuilder().setReason("User disconnected"))
            .build()
        return msg.toByteArray()
    }

    private fun send(ip: String, type: Byte, payload: ByteArray) {
        if (sockets.isEmpty()) return
        val data = ByteArray(1 + payload.size)
        data[0] = type
        System.arraycopy(payload, 0, data, 1, payload.size)
        val addr = try {
            InetAddress.getByName(ip)
        } catch (_: Exception) {
            return
        }
        for (socket in sockets) {
            try {
                socket.send(DatagramPacket(data, data.size, addr, PORT))
            } catch (_: Exception) {
            }
        }
    }
}
