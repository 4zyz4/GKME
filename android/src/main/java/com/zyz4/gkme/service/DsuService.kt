package com.zyz4.gkme.service

import com.zyz4.gkme.model.GamepadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class DsuService(
    private val scope: CoroutineScope,
    private val serverIp: String,
    private val onRumble: ((largeMotor: Int, smallMotor: Int) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onConnected: (() -> Unit)? = null
) {
    private var discoverySockets = mutableListOf<DatagramSocket>()
    private var dataSocket: DatagramSocket? = null
    private var jobs = mutableListOf<Job>()

    private val codec = DsuCodec()
    private var pcAddress: InetAddress? = null
    private var pcPort: Int = 0
    private var clientId: Int = 0
    private var packetNum: Int = 0
    private var isDataActive: Boolean = false

    private var lastGamepadState: GamepadState = GamepadState()
    private var allLocalIpBytes: List<ByteArray> = emptyList()
    private var allLocalAddresses: Set<InetAddress> = emptySet()

    private fun getPrimaryIpBytes(): ByteArray? {
        return allLocalIpBytes.firstOrNull()
    }

    @Volatile
    var lastPacketTime: Long = 0L

    fun start(): Boolean {
        stop()
        try {
            allLocalIpBytes = resolveAllLocalIpBytes()
            allLocalAddresses = resolveAllLocalAddresses()
            for (ipBytes in allLocalIpBytes) {
                val ds = DatagramSocket(DsuConstants.PORT_DISCOVERY, InetAddress.getByAddress(
                    ipBytes
                )).apply {
                    broadcast = true
                    soTimeout = 1000
                }
                discoverySockets.add(ds)
            }
            dataSocket = DatagramSocket(DsuConstants.PORT_DATA).apply {
                soTimeout = 8
            }
        } catch (e: java.net.BindException) {
            onError?.invoke("端口被占用，可能另一个实例已在运行: ${e.message}")
            stop()
            return false
        } catch (e: Exception) {
            onError?.invoke("创建 DSU 套接字失败: ${e.message}")
            stop()
            return false
        }

        if (discoverySockets.isEmpty()) return false
        val dt = dataSocket ?: return false

        for (ds in discoverySockets) {
            val ip = allLocalIpBytes[discoverySockets.indexOf(ds)]
            jobs.add(scope.launch(Dispatchers.IO) {
                while (isActive && !isDataActive) {
                    try {
                        val packet = DatagramPacket(
                            ip, ip.size,
                            InetAddress.getByName("255.255.255.255"),
                            DsuConstants.PORT_DISCOVERY
                        )
                        ds.send(packet)
                    } catch (_: Exception) {}
                    delay(1000)
                }
            })
        }

        for (ds in discoverySockets) {
            jobs.add(scope.launch(Dispatchers.IO) {
                val buf = ByteArray(512)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        ds.receive(packet)
                        val data = packet.data.copyOf(packet.length)
                        val from = packet.address
                        val port = packet.port
                        if (from in allLocalAddresses) continue
                        if (data.size >= 20) {
                            val header = codec.decodeHeader(data)
                            if (header != null) {
                                handleDsuPacket(ds, from, port, header)
                            }
                        }
                    } catch (_: Exception) {
                        if (!isActive) break
                    }
                }
            })
        }

        jobs.add(scope.launch(Dispatchers.IO) {
            val buf = ByteArray(DsuConstants.TOTAL_PACKET_SIZE)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    dt.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    val header = codec.decodeHeader(data)
                    if (header != null) {
                        handleDsuPacket(dt, packet.address, packet.port, header)
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }

                if (isDataActive) {
                    val addr = pcAddress
                    val port = pcPort
                    if (addr != null && port != 0) {
                        packetNum++
                        val data = codec.encodeGamepadData(
                            clientId = clientId,
                            slot = 0,
                            state = lastGamepadState,
                            packetNum = packetNum
                        )
                        try {
                            val pkt = DatagramPacket(data, data.size, addr, port)
                            dt.send(pkt)
                        } catch (_: Exception) {}
                    }
                }

            }
        })

        return true
    }

    private fun handleDsuPacket(socket: DatagramSocket, from: InetAddress, port: Int, header: DsuPacketHeader) {
        pcAddress = from
        pcPort = port
        clientId = header.clientId
        lastPacketTime = System.currentTimeMillis()

        when (header.eventType) {
            DsuConstants.TYPE_CONTROLLER_INFO -> {
                val response = codec.encodeControllerInfo(
                    clientId = header.clientId,
                    slot = 0,
                    battery = DsuConstants.BATTERY_FULL
                )
                sendPacket(socket, response, from, port)
                if (!isDataActive) {
                    isDataActive = true
                    onConnected?.invoke()
                }
            }
            DsuConstants.TYPE_CONTROLLER_DATA -> {
                if (!isDataActive) {
                    isDataActive = true
                    onConnected?.invoke()
                }
            }
            DsuConstants.TYPE_RUMBLE -> {
                val rumble = codec.parseRumbleRequest(header)
                android.util.Log.d("DsuService", "TYPE_RUMBLE payloadSize=${header.payload.size} rumble=$rumble")
                if (rumble != null) {
                    onRumble?.invoke(rumble.largeMotor, rumble.smallMotor)
                }
            }
        }
    }

    private fun resolveAllLocalAddresses(): Set<InetAddress> {
        val set = mutableSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address) set.add(addr)
                }
            }
        } catch (_: Exception) {}
        return set
    }

    private fun resolveAllLocalIpBytes(): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address) {
                        result.add(addr.address)
                    }
                }
            }
        } catch (_: Exception) {}
        if (result.isEmpty()) {
            result.add(serverIp.split(".").map { it.toInt().toByte() }.toByteArray())
        }
        return result
    }

    fun updateGamepadState(state: GamepadState) {
        lastGamepadState = state
    }

    private fun sendPacket(socket: DatagramSocket, data: ByteArray, addr: InetAddress, port: Int) {
        try {
            val packet = DatagramPacket(data, data.size, addr, port)
            socket.send(packet)
        } catch (_: Exception) {}
    }

    fun stop() {
        isDataActive = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        for (ds in discoverySockets) {
            try { ds.close() } catch (_: Exception) {}
        }
        discoverySockets.clear()
        try { dataSocket?.close() } catch (_: Exception) {}
        dataSocket = null
        pcAddress = null
        pcPort = 0
    }
}
