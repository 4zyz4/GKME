package com.zyz4.gkme.web

import com.zyz4.gkme.R
import com.zyz4.gkme.i18n.AppStrings
import android.content.Context
import android.util.Base64
import android.util.Log
import com.zyz4.gkme.controlled.ControlledDevice
import com.zyz4.gkme.controlled.ControlledHostManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

/**
 * 被控端内置的 HTTP + WebSocket 服务器：托管 `assets/web` 下的 GKME-Web 网页手柄，
 * 并把浏览器的输入桥接到 [ControlledHostManager]（注入本机虚拟手柄）。
 *
 * 浏览器使用与 Android 客户端完全相同的 protobuf 协议，仅帧格式不同：每个
 * WebSocket 二进制帧为 `[1 字节类型][protobuf 负载]`，类型常量与 UDP 传输一致
 * （[ControlledHostManager] 的 `TYPE_CLIENT_TO_SERVER` / `TYPE_GAMEPAD_INPUT` 上行，
 * `TYPE_SERVER_TO_CLIENT` 下行）。
 *
 * 逻辑对齐电脑端 `Web/WebServer.cs`。
 */
object WebServer {

    private const val TAG = "GKME-WebServer"
    const val HTTP_PORT = 37285
    private const val WEB_ASSET_ROOT = "web"
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    @Volatile
    private var running = false
    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var appContext: Context? = null

    fun isRunning(): Boolean = running

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(HTTP_PORT))
                serverSocket = ss
                while (running && isActive) {
                    val client = try {
                        ss.accept()
                    } catch (_: Exception) {
                        if (!running) break else continue
                    }
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "HTTP 端口 $HTTP_PORT 不可用: ${e.message}")
            } finally {
                try { ss?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    /** 构造某个网卡地址对应的网页手柄 URL。 */
    fun buildUrl(address: String): String = "http://$address:$HTTP_PORT/"

    /** 列出可用的 IPv4 局域网网卡（跳过回环与链路本地地址）。 */
    fun enumerateLanInterfaces(): List<LanInterfaceInfo> {
        val list = mutableListOf<LanInterfaceInfo>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return list
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address) {
                        val addr = a.hostAddress ?: continue
                        if (addr.startsWith("169.254.")) continue
                        list.add(LanInterfaceInfo(ni.name ?: "", addr))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    /** 首选的局域网地址：优先私有网段，否则回退第一个可用地址。 */
    fun primaryLanAddress(): String? {
        val all = enumerateLanInterfaces()
        if (all.isEmpty()) return null
        return all.firstOrNull {
            it.address.startsWith("192.168.") || it.address.startsWith("10.") ||
                (it.address.startsWith("172.") && isPrivate172(it.address))
        }?.address ?: all.first().address
    }

    private fun isPrivate172(address: String): Boolean {
        val second = address.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }

    // ────────────────────────── 连接处理 ──────────────────────────

    private suspend fun handleClient(socket: Socket) {
        val input: InputStream
        val output: BufferedOutputStream
        val clientIp: String
        try {
            socket.tcpNoDelay = true
            input = socket.getInputStream()
            output = BufferedOutputStream(socket.getOutputStream())
            clientIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
            return
        }

        try {
            // HTTP/1.1 keep-alive：在同一 socket 上持续处理请求，直到对端关闭或升级为 WebSocket。
            while (running) {
                val header = readRequestHeader(input) ?: break
                val req = HttpRequest.parse(header) ?: break
                if (!req.method.equals("GET", ignoreCase = true)) {
                    writeResponse(output, 405, "Method Not Allowed", "text/plain",
                        "Only GET is supported.".toByteArray(Charsets.UTF_8))
                    break
                }
                if (req.path.equals("/ws", ignoreCase = true)) {
                    handleWebSocket(input, output, req, clientIp)
                    return // stream 现已归 WebSocket 所有
                }
                serveStatic(output, req)
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ────────────────────────── WebSocket 桥接 ──────────────────────────

    private fun handleWebSocket(input: InputStream, output: BufferedOutputStream, req: HttpRequest, clientIp: String) {
        val key = req.headers["sec-websocket-key"]
        if (key.isNullOrEmpty()) {
            writeResponse(output, 400, "Bad Request", "text/plain",
                "Missing Sec-WebSocket-Key.".toByteArray(Charsets.UTF_8))
            return
        }

        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP,
        )
        val handshake = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(handshake)
        output.flush()

        val ws = WebSocketConnection(input, output)

        val id = req.query["id"]?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "")
        val name = req.query["name"]?.takeIf { it.isNotBlank() } ?: AppStrings.get(R.string.i18n_web_browser)

        val device = ControlledDevice(ip = clientIp, name = name, mac = id, isWeb = true)

        ControlledHostManager.onWebClientConnected(device)
        ControlledHostManager.connectWeb(device) { type, payload -> ws.send(type, payload) }

        try {
            while (running && ws.isOpen) {
                val message = ws.readMessage() ?: break
                if (message.isEmpty()) continue
                val type = message[0]
                val payload = if (message.size > 1) message.copyOfRange(1, message.size) else ByteArray(0)
                ControlledHostManager.onWebFrame(device, type, payload)
            }
        } catch (_: Exception) {
        } finally {
            ws.closeQuietly()
            ControlledHostManager.onWebClientClosed(device)
        }
    }

    // ────────────────────────── 静态文件 ──────────────────────────

    private fun serveStatic(output: BufferedOutputStream, req: HttpRequest) {
        val context = appContext
        if (context == null) {
            writeResponse(output, 500, "Internal Server Error", "text/plain",
                "No context.".toByteArray(Charsets.UTF_8))
            return
        }

        var path = req.path
        if (path.isEmpty() || path == "/") path = "/index.html"

        val relative = try {
            URLDecoder.decode(path.trimStart('/'), "UTF-8")
        } catch (_: Exception) {
            path.trimStart('/')
        }

        // 目录穿越防护：解析后的路径不得离开 web 根。
        if (relative.split('/').any { it == ".." }) {
            writeResponse(output, 403, "Forbidden", "text/plain",
                "Forbidden.".toByteArray(Charsets.UTF_8))
            return
        }

        var assetPath = "$WEB_ASSET_ROOT/$relative"
        if (relative.endsWith("/") || relative.isEmpty()) assetPath += "index.html"

        var bytes = readAsset(context, assetPath)
        if (bytes == null) {
            // 无扩展名的未知路由回退到 index.html（对齐 SPA 行为）。
            val fallback = !assetPath.substringAfterLast('/').contains('.')
            if (fallback) bytes = readAsset(context, "$WEB_ASSET_ROOT/index.html")
        }

        if (bytes == null) {
            writeResponse(output, 404, "Not Found", "text/plain",
                "Not found: $path".toByteArray(Charsets.UTF_8))
            return
        }

        val ext = assetPath.substringAfterLast('.', "").lowercase()
        val mime = MIME_TYPES[ext] ?: "application/octet-stream"
        val cacheable = ext in CACHEABLE_EXTS
        writeResponse(output, 200, "OK", mime, bytes, cache = cacheable)
    }

    private fun readAsset(context: Context, assetPath: String): ByteArray? = try {
        context.assets.open(assetPath).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        cache: Boolean = false,
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: ").append(if (cache) "public, max-age=3600" else "no-cache").append("\r\n")
            append("Connection: keep-alive\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    // ────────────────────────── HTTP 解析 ──────────────────────────

    private fun readRequestHeader(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < 65536) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            buf.write(b)
            if (b == '\n'.code && buf.size() >= 4) {
                val arr = buf.toByteArray()
                val n = arr.size
                if (arr[n - 4] == '\r'.code.toByte() && arr[n - 3] == '\n'.code.toByte() &&
                    arr[n - 2] == '\r'.code.toByte() && arr[n - 1] == '\n'.code.toByte()
                ) {
                    return String(arr, Charsets.ISO_8859_1)
                }
            }
        }
        return null
    }

    private class HttpRequest {
        var method = ""
        var path = ""
        var queryString = ""
        val headers = HashMap<String, String>()
        val query = HashMap<String, String>()

        companion object {
            fun parse(header: String): HttpRequest? {
                val lines = header.split("\r\n")
                if (lines.isEmpty()) return null
                val requestLine = lines[0].split(' ')
                if (requestLine.size < 2) return null

                val req = HttpRequest()
                req.method = requestLine[0]
                val target = requestLine[1]
                val q = target.indexOf('?')
                if (q >= 0) {
                    req.path = target.substring(0, q)
                    req.queryString = target.substring(q + 1)
                    for (pair in req.queryString.split('&')) {
                        if (pair.isEmpty()) continue
                        val kv = pair.split('=', limit = 2)
                        val k = decode(kv[0])
                        val v = if (kv.size > 1) decode(kv[1]) else ""
                        req.query[k] = v
                    }
                } else {
                    req.path = target
                }

                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val k = line.substring(0, colon).trim()
                    val v = line.substring(colon + 1).trim()
                    req.headers[k.lowercase()] = v
                }
                return req
            }

            private fun decode(s: String): String = try {
                URLDecoder.decode(s, "UTF-8")
            } catch (_: Exception) {
                s
            }
        }
    }

    // ────────────────────────── WebSocket 帧编解码 ──────────────────────────

    /** 极简 RFC 6455 服务端实现：仅处理二进制/文本/关闭/ping 帧。 */
    private class WebSocketConnection(
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        @Volatile
        var isOpen: Boolean = true
            private set

        private val sendLock = Any()

        /** 读取一个完整的（可能分片）数据消息；对端关闭返回 null。 */
        fun readMessage(): ByteArray? {
            val acc = ByteArrayOutputStream()
            while (true) {
                val b0 = input.read()
                if (b0 < 0) { isOpen = false; return null }
                val b1 = input.read()
                if (b1 < 0) { isOpen = false; return null }

                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = readLength(2)
                else if (len == 127L) len = readLength(8)
                if (len < 0 || len > MAX_FRAME_BYTES) { isOpen = false; return null }

                val masked = (b1 and 0x80) != 0
                val mask = if (masked) readBytes(4) else null
                val payload = readBytes(len.toInt())
                if (mask != null) {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }

                when (opcode) {
                    0x8 -> { isOpen = false; sendControl(0x8, payload); return null }
                    0x9 -> sendControl(0xA, payload)
                    0xA -> {}
                    else -> {
                        acc.write(payload)
                        if (fin) return acc.toByteArray()
                    }
                }
            }
        }

        fun send(type: Byte, payload: ByteArray) {
            val frame = ByteArray(payload.size + 1)
            frame[0] = type
            if (payload.isNotEmpty()) System.arraycopy(payload, 0, frame, 1, payload.size)
            sendData(frame)
        }

        fun closeQuietly() {
            if (!isOpen) return
            isOpen = false
            sendControl(0x8, ByteArray(0))
        }

        private fun sendData(data: ByteArray) = sendFrame(0x2, data)

        private fun sendControl(opcode: Int, data: ByteArray) = sendFrame(opcode, data)

        private fun sendFrame(opcode: Int, data: ByteArray) {
            synchronized(sendLock) {
                if (!isOpen && opcode != 0x8) return
                try {
                    output.write(0x80 or opcode)
                    val len = data.size
                    when {
                        len <= 125 -> output.write(len)
                        len <= 0xFFFF -> {
                            output.write(126)
                            output.write(len ushr 8)
                            output.write(len and 0xFF)
                        }
                        else -> {
                            output.write(127)
                            for (i in 7 downTo 0) output.write((len.toLong() ushr (i * 8)).toInt() and 0xFF)
                        }
                    }
                    if (data.isNotEmpty()) output.write(data)
                    output.flush()
                } catch (_: Exception) {
                    isOpen = false
                }
            }
        }

        private fun readLength(count: Int): Long {
            var value = 0L
            for (i in 0 until count) {
                val b = input.read()
                if (b < 0) throw EOFException()
                value = (value shl 8) or b.toLong()
            }
            return value
        }

        private fun readBytes(count: Int): ByteArray {
            val data = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = input.read(data, off, count - off)
                if (n < 0) throw EOFException()
                off += n
            }
            return data
        }
    }

    private val MIME_TYPES = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "wasm" to "application/wasm",
        "txt" to "text/plain; charset=utf-8",
        "webmanifest" to "application/manifest+json",
    )

    private val CACHEABLE_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf",
    )

    private const val MAX_FRAME_BYTES = 16L * 1024 * 1024
}
