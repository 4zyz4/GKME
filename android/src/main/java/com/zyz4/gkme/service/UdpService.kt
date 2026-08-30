package com.zyz4.gkme.service

import com.zyz4.gkme.proto.ClientToServer
import com.zyz4.gkme.proto.GamepadInput
import com.zyz4.gkme.proto.Hello
import com.zyz4.gkme.proto.ServerToClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds

class UdpService {

    companion object {
        const val PORT = 37284
        const val TYPE_CLIENT_TO_SERVER: Byte = 0x00
        const val TYPE_SERVER_TO_CLIENT: Byte = 0x01
        const val TYPE_GAMEPAD_INPUT: Byte = 0x02
    }

    private var sockets = mutableListOf<DatagramSocket>()
    private var broadcastJobs = mutableListOf<Job>()
    private var receiveJobs = mutableListOf<Job>()
    @Volatile var pcAddress: InetSocketAddress? = null
        private set
    @Volatile var lastReceiveTime: Long = 0
    @Volatile private var connected = false

    private var onMessage: ((ServerToClient) -> Unit)? = null
    private var broadcastName: String? = null

    val isActive: Boolean get() = sockets.isNotEmpty()

    fun setConnected(connected: Boolean) {
        this.connected = connected
        if (connected) {
            stopBroadcast()
        }
    }

    fun resumeBroadcast() {
        val name = broadcastName ?: return
        if (broadcastJobs.isNotEmpty()) return
        for (socket in sockets) {
            startBroadcastForSocket(socket, name)
        }
    }

    private fun stopBroadcast() {
        broadcastJobs.forEach { it.cancel() }
        broadcastJobs.clear()
    }

    fun start(deviceName: String, onMessage: (ServerToClient) -> Unit) {
        rebind(deviceName, onMessage, keepPcAddress = false)
    }

    /**
     * Rebinds all UDP sockets to the current local IPs without dropping the
     * known PC address. A WiFi drop/reconnect can invalidate sockets that are
     * bound to a stale local IP — auto-reconnect calls this so the handshake
     * keeps flowing even after the phone changed networks/IP.
     */
    fun refresh() {
        rebind(broadcastName, onMessage, keepPcAddress = true)
    }

    private fun rebind(deviceName: String?, onMessage: ((ServerToClient) -> Unit)?, keepPcAddress: Boolean) {
        val savedPc = pcAddress
        stop()
        this.onMessage = onMessage
        this.broadcastName = deviceName
        val allIps = com.zyz4.gkme.service.ConnectionManager.getAllLocalIpAddressesInternal()
        for (localIp in allIps) {
            try {
                val bindAddr = InetAddress.getByName(localIp)
                val socket = DatagramSocket(PORT, bindAddr).also { it.broadcast = true }
                sockets.add(socket)
                startBroadcastForSocket(socket, deviceName ?: return)
                startReceiveLoopForSocket(socket)
            } catch (_: Exception) {}
        }
        if (sockets.isEmpty()) {
            try {
                val socket = DatagramSocket(PORT).also { it.broadcast = true }
                sockets.add(socket)
                startBroadcastForSocket(socket, deviceName ?: return)
                startReceiveLoopForSocket(socket)
            } catch (_: Exception) {}
        }
        if (keepPcAddress) pcAddress = savedPc
    }

    fun clearPcAddress() {
        pcAddress = null
    }

    fun stop() {
        broadcastJobs.forEach { it.cancel() }
        broadcastJobs.clear()
        receiveJobs.forEach { it.cancel() }
        receiveJobs.clear()
        for (s in sockets) {
            try { s.close() } catch (_: Exception) {}
        }
        sockets.clear()
        pcAddress = null
    }

    suspend fun sendGamepadInput(input: GamepadInput) {
        if (pcAddress == null) return
        withContext(Dispatchers.IO) {
            try {
                val payload = input.toByteArray()
                val data = ByteArray(1 + payload.size).also {
                    it[0] = TYPE_GAMEPAD_INPUT
                    payload.copyInto(it, 1)
                }
                for (socket in sockets) {
                    try {
                        val dp = DatagramPacket(data, data.size, pcAddress)
                        socket.send(dp)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun sendClientToServer(msg: ClientToServer) {
        if (pcAddress == null) return
        withContext(Dispatchers.IO) {
            try {
                val payload = msg.toByteArray()
                val data = ByteArray(1 + payload.size).also {
                    it[0] = TYPE_CLIENT_TO_SERVER
                    payload.copyInto(it, 1)
                }
                for (socket in sockets) {
                    try {
                        val dp = DatagramPacket(data, data.size, pcAddress)
                        socket.send(dp)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun sendKeyboardReport(
        modifier: Byte,
        keys: ByteArray,
    ) {
        if (pcAddress == null) {
            android.util.Log.d("UdpService", "sendKeyboardReport: pcAddress is null, skipping")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val pressedList = mutableListOf<Int>()
                for (i in keys.indices) {
                    if (keys[i].toInt() != 0) {
                        pressedList.add(keys[i].toInt() and 0xFF)
                    }
                }
                if (pressedList.isEmpty() && modifier.toInt() == 0) {
                    android.util.Log.d("UdpService", "sendKeyboardReport: empty report, skipping")
                    return@withContext
                }
                android.util.Log.d("UdpService", "sendKeyboardReport: pressed=${pressedList.joinToString()}, mod=${modifier.toInt() and 0xFF}")
                val builder = GamepadInput.newBuilder()
                for (sc in pressedList) {
                    builder.addPressedScanCodes(sc)
                }
                builder.setKeyboardModifiers(modifier.toInt() and 0xFF)
                val gamepadInput = builder.build()
                val payload = gamepadInput.toByteArray()
                val data = ByteArray(1 + payload.size).also {
                    it[0] = TYPE_GAMEPAD_INPUT
                    payload.copyInto(it, 1)
                }
                for (socket in sockets) {
                    try {
                        val dp = DatagramPacket(data, data.size, pcAddress)
                        socket.send(dp)
                        android.util.Log.d("UdpService", "sendKeyboardReport: sent ${data.size} bytes to ${pcAddress}")
                    } catch (e: Exception) {
                        android.util.Log.e("UdpService", "sendKeyboardReport: send error", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UdpService", "sendKeyboardReport: error", e)
            }
        }
    }

    private fun startReceiveLoopForSocket(socket: DatagramSocket) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(65535)
            while (isActive) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    socket.receive(dp)
                    lastReceiveTime = System.currentTimeMillis()
                    val len = dp.length
                    if (len < 1) continue
                    val type = buf[0]
                    val payload = buf.copyOfRange(1, len)
                    when (type) {
                        TYPE_SERVER_TO_CLIENT -> {
                            pcAddress = InetSocketAddress(dp.address, PORT)
                            val msg = ServerToClient.parseFrom(payload)
                            onMessage?.invoke(msg)
                        }
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }
        receiveJobs.add(job)
    }

    private fun startBroadcastForSocket(socket: DatagramSocket, deviceName: String) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val msg = "GAMEPAD_SERVER:$deviceName".toByteArray()
                val bcAddr = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    try {
                        val dp = DatagramPacket(msg, msg.size, bcAddr, PORT)
                        socket.send(dp)
                    } catch (_: Exception) {}
                    delay(1000.milliseconds)
                }
            } catch (_: Exception) {}
        }
        broadcastJobs.add(job)
    }
}
