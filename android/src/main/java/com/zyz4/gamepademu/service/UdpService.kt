package com.zyz4.gamepademu.service

import com.zyz4.gamepademu.proto.ClientToServer
import com.zyz4.gamepademu.proto.GamepadInput
import com.zyz4.gamepademu.proto.ServerToClient
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

    private var socket: DatagramSocket? = null
    private var broadcastJob: Job? = null
    private var receiveJob: Job? = null
    @Volatile var pcAddress: InetSocketAddress? = null
        private set
    @Volatile var phoneIp: String = ""
    @Volatile var lastReceiveTime: Long = 0

    private var onMessage: ((ServerToClient) -> Unit)? = null

    val isActive: Boolean get() = socket != null

    fun start(ip: String, deviceName: String, onMessage: (ServerToClient) -> Unit) {
        stop()
        phoneIp = ip
        this.onMessage = onMessage
        socket = DatagramSocket(PORT).also { it.broadcast = true }
        startBroadcast(deviceName)
        startReceiveLoop()
    }

    fun clearPcAddress() {
        pcAddress = null
    }

    fun stop() {
        broadcastJob?.cancel()
        receiveJob?.cancel()
        socket?.close()
        socket = null
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
                socket?.send(DatagramPacket(data, data.size, pcAddress))
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
                socket?.send(DatagramPacket(data, data.size, pcAddress))
            } catch (_: Exception) {}
        }
    }

    private fun startReceiveLoop() {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(65535)
            while (isActive) {
                try {
                    val s = socket ?: break
                    val dp = DatagramPacket(buf, buf.size)
                    s.receive(dp)
                    val len = dp.length
                    if (len < 1) continue
                    val type = buf[0]
                    val payload = buf.copyOfRange(1, len)
                    when (type) {
                        TYPE_SERVER_TO_CLIENT -> {
                            pcAddress = InetSocketAddress(dp.address, PORT)
                            lastReceiveTime = System.currentTimeMillis()
                            val msg = ServerToClient.parseFrom(payload)
                            onMessage?.invoke(msg)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun startBroadcast(deviceName: String) {
        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val msg = "GAMEPAD_SERVER:$deviceName".toByteArray()
                val bcAddr = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    try {
                        val s = socket ?: break
                        val dp = DatagramPacket(msg, msg.size, bcAddr, PORT)
                        s.send(dp)
                    } catch (_: Exception) {}
                    delay(1000.milliseconds)
                }
            } catch (_: Exception) {}
        }
    }
}
