package com.zyz4.gamepademu.service

import com.google.protobuf.AbstractMessageLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class TcpServerService {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val writeMutex = Mutex()
    var isRunning = false
        private set
    var isClientConnected = false
        private set

    suspend fun start(
        port: Int,
        onClientConnected: () -> Unit,
        onClientDisconnected: () -> Unit,
        onMessage: (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        stop()
        serverSocket = ServerSocket(port)
        isRunning = true
        while (isRunning && !isClientConnected) {
            try {
                serverSocket?.soTimeout = 1000
                val socket = serverSocket!!.accept()
                socket.keepAlive = true
                socket.tcpNoDelay = true
                clientSocket = socket
                outputStream = socket.getOutputStream()
                inputStream = socket.getInputStream()
                isClientConnected = true
                onClientConnected()
                readLoop(onMessage)
                isClientConnected = false
                onClientDisconnected()
                try { clientSocket?.close() } catch (_: Exception) {}
                clientSocket = null
                outputStream = null
                inputStream = null
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: java.net.SocketException) {
            }
        }
    }

    private fun readLoop(onMessage: (ByteArray) -> Unit) {
        val header = ByteArray(4)
        while (isRunning && isClientConnected) {
            try {
                var offset = 0
                while (offset < 4) {
                    val n = inputStream?.read(header, offset, 4 - offset) ?: -1
                    if (n < 0) return
                    offset += n
                }
                val len = ((header[0].toInt() and 0xFF) shl 24) or
                          ((header[1].toInt() and 0xFF) shl 16) or
                          ((header[2].toInt() and 0xFF) shl 8) or
                          (header[3].toInt() and 0xFF)
                if (len <= 0 || len > 65536) break
                val buf = ByteArray(len)
                offset = 0
                while (offset < len) {
                    val n = inputStream?.read(buf, offset, len - offset) ?: -1
                    if (n < 0) return
                    offset += n
                }
                onMessage(buf)
            } catch (_: Exception) { break }
        }
    }

    suspend fun send(message: AbstractMessageLite<*, *>) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val data = message.toByteArray()
                val len = data.size
                val header = byteArrayOf(
                    (len shr 24).toByte(), (len shr 16).toByte(),
                    (len shr 8).toByte(), len.toByte()
                )
                outputStream?.write(header)
                outputStream?.write(data)
                outputStream?.flush()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        isClientConnected = false
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
        outputStream = null
        inputStream = null
    }
}
