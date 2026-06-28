package com.zyz4.gamepademu.service

import com.google.protobuf.AbstractMessageLite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class TcpClientService {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var running = false

    suspend fun connect(
        host: String,
        port: Int,
        onMessage: (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        disconnect()
        socket = Socket(host, port)
        outputStream = socket!!.getOutputStream()
        inputStream = socket!!.getInputStream()
        running = true
        withContext(Dispatchers.IO) {
            readLoop(onMessage)
        }
    }

    private fun readLoop(onMessage: (ByteArray) -> Unit) {
        val buf = ByteArray(4096)
        while (running) {
            try {
                val len = inputStream?.read(buf) ?: -1
                if (len <= 0) break
                val data = buf.copyOf(len)
                onMessage(data)
            } catch (_: Exception) {
                break
            }
        }
        running = false
    }

    suspend fun send(message: AbstractMessageLite<*, *>) = withContext(Dispatchers.IO) {
        try {
            val data = message.toByteArray()
            val len = data.size
            val header = byteArrayOf(
                (len shr 24).toByte(),
                (len shr 16).toByte(),
                (len shr 8).toByte(),
                len.toByte()
            )
            outputStream?.write(header)
            outputStream?.write(data)
            outputStream?.flush()
        } catch (_: Exception) {}
    }

    suspend fun sendKeepAlive(timestamp: Long) {
        val ka = com.zyz4.gamepademu.proto.KeepAlive.newBuilder()
            .setTimestamp(timestamp)
            .build()
        val msg = com.zyz4.gamepademu.proto.ClientToServer.newBuilder()
            .setKeepAlive(ka)
            .build()
        send(msg)
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
    }
}
