package com.zyz4.gkme.service

import com.zyz4.gkme.proto.ClientToServer
import com.zyz4.gkme.proto.GamepadInput
import com.zyz4.gkme.proto.ServerToClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * USB transport: the phone is the TCP server and the PC connects to it through
 * `adb forward tcp:<local> tcp:<PORT>`.
 *
 * The existing protobuf messages are reused verbatim; over the stream transport
 * each message is framed as
 * `[4-byte big-endian length][1-byte type][protobuf payload]`, where the length
 * counts the type byte plus the payload. This mirrors the PC's `UsbConnection`.
 */
class UsbService {

    companion object {
        const val PORT = 37284
        const val TYPE_CLIENT_TO_SERVER: Byte = 0x00
        const val TYPE_SERVER_TO_CLIENT: Byte = 0x01
        const val TYPE_GAMEPAD_INPUT: Byte = 0x02
        private const val MAX_FRAME = 16 * 1024 * 1024
        private const val LOOPBACK = "127.0.0.1"
    }

    private var serverSocket: ServerSocket? = null
    private var client: Socket? = null
    private var out: DataOutputStream? = null
    private val sendLock = Any()
    private var acceptJob: Job? = null
    private var readJob: Job? = null
    private var onMessage: ((ServerToClient) -> Unit)? = null
    private var onPeerClosed: (() -> Unit)? = null

    @Volatile var lastReceiveTime: Long = 0
        private set
    @Volatile var peerConnected: Boolean = false
        private set

    val isActive: Boolean get() = serverSocket != null

    /** Binds the loopback TCP server. Returns false when the port cannot be
     * bound. `onPeerClosed` fires whenever the connected PC drops. */
    fun start(
        onMessage: (ServerToClient) -> Unit,
        onPeerClosed: () -> Unit = {},
    ): Boolean {
        stop()
        this.onMessage = onMessage
        this.onPeerClosed = onPeerClosed

        serverSocket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), PORT))
            }
        } catch (_: Exception) {
            // Some devices resolve adbd's "tcp:" remote to the wildcard address;
            // fall back to binding all interfaces.
            try {
                ServerSocket(PORT).apply { reuseAddress = true }
            } catch (_: Exception) {
                null
            }
        }

        if (serverSocket == null) return false
        acceptJob = CoroutineScope(Dispatchers.IO).launch { acceptLoop() }
        return true
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (isActive) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                if (!isActive) break
                continue
            }
            try {
                socket.tcpNoDelay = true
            } catch (_: Exception) {}
            handleClient(socket)
        }
    }

    private fun handleClient(socket: Socket) {
        synchronized(sendLock) {
            try { client?.close() } catch (_: Exception) {}
            client = socket
            out = try {
                DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            } catch (_: Exception) {
                null
            }
            peerConnected = out != null
            lastReceiveTime = System.currentTimeMillis()
        }
        readJob?.cancel()
        readJob = CoroutineScope(Dispatchers.IO).launch { readLoop(socket) }
    }

    private fun readLoop(socket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (isActive && !socket.isClosed) {
                val len = input.readInt()
                if (len <= 0 || len > MAX_FRAME) break
                val data = ByteArray(len)
                input.readFully(data)
                lastReceiveTime = System.currentTimeMillis()
                if (len < 1) continue
                val type = data[0]
                if (type == TYPE_SERVER_TO_CLIENT) {
                    try {
                        val msg = ServerToClient.parseFrom(data.copyOfRange(1, len))
                        onMessage?.invoke(msg)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            var closed = false
            synchronized(sendLock) {
                if (client === socket) {
                    client = null
                    out = null
                    closed = peerConnected
                    peerConnected = false
                }
            }
            try { socket.close() } catch (_: Exception) {}
            if (closed) {
                try { onPeerClosed?.invoke() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        readJob?.cancel()
        readJob = null
        synchronized(sendLock) {
            try { client?.close() } catch (_: Exception) {}
            client = null
            out = null
            peerConnected = false
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        lastReceiveTime = 0
    }

    private fun send(type: Byte, payload: ByteArray) {
        if (payload.size + 1 > MAX_FRAME) return
        synchronized(sendLock) {
            if (!peerConnected) return
            val o = out ?: return
            try {
                o.writeInt(1 + payload.size)
                o.writeByte(type.toInt())
                o.write(payload)
                o.flush()
            } catch (_: Exception) {
                peerConnected = false
            }
        }
    }

    suspend fun sendGamepadInput(input: GamepadInput) {
        withContext(Dispatchers.IO) { send(TYPE_GAMEPAD_INPUT, input.toByteArray()) }
    }

    suspend fun sendClientToServer(msg: ClientToServer) {
        withContext(Dispatchers.IO) { send(TYPE_CLIENT_TO_SERVER, msg.toByteArray()) }
    }
}
