package com.crossclip.app.network

import android.util.Log
import com.crossclip.app.util.DebugLogger
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端本地轻量级 HTTP 服务端（监听端口 18237）
 *
 * 采用原生 ServerSocket + 线程池实现，体积极小，零第三方依赖。
 *
 * ## 与旧版的关键差异（性能优化）
 * 1. **支持 HTTP/1.1 keep-alive**：同一条 TCP 连接上连续处理多个请求。
 *    旧版每处理一个请求就 `Connection: close`，导致电脑端每发一个 256KB 分块
 *    都要重新做一次 TCP 三次握手，大文件传输时握手开销巨大。
 * 2. **Body 按字节读取**：旧版用 `CharArray(1024)` + `BufferedReader` 逐字符读，
 *    且把「字节数」当「字符数」用（只有 Base64 纯 ASCII 时才侥幸正确）。
 *    新版直接按 Content-Length 读原始字节，既支持二进制密文，又避免了逐字符解码开销。
 * 3. **关闭 Nagle 算法**（tcpNoDelay）：小分块场景下显著降低发送延迟。
 */
class LocalHttpServer(
    private val port: Int = 18237,
    // lamportClock：电脑端（hub）分配的单调时钟，-1 表示老版本电脑端未携带（不做时钟裁决）。
    // 与 SSE 通道收到的是同一条内容，靠时钟相等判定实现天然幂等。
    private val onSyncReceived: ((encrypted: String, lamportClock: Long, senderId: String) -> Unit)? = null
) {
    private val TAG = "LocalHttpServer"

    companion object {
        /** 连接空闲超时：keep-alive 状态下超过该时长没有新请求就关闭连接 */
        private const val IDLE_TIMEOUT_MS = 15_000

        /** socket 读写缓冲区大小 */
        private const val SOCKET_BUFFER_SIZE = 64 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    /**
     * 文件传输回调接口。
     *
     * 注意：分块回调传递的是**未解密的原始密文字节**，解密由上层（Service）完成，
     * 这样本类无需持有 PIN 码，职责更单一。
     */
    interface FileTransferCallback {
        /**
         * @param fileHash 发送端给出的整文件 SHA-256；老版本电脑端可能传 null。
         *                 有了它才能判定「目标目录里已经有同一个文件」。
         */
        fun onFilePrepareReceived(
            fileId: String,
            filename: String,
            fileSize: Long,
            mimeType: String,
            senderId: String,
            fileHash: String?
        ): Boolean

        /** @param payload AES-GCM 密文（nonce||ciphertext||tag） */
        fun onFileChunkReceived(fileId: String, chunkIndex: Int, totalChunks: Int, payload: ByteArray): Pair<Int, Int>?

        fun onFileCompleteReceived(fileId: String, fileHash: String): String?

        /**
         * 本次接收是否命中了「目标目录已有同名同内容文件」的去重判定。
         *
         * 这里给默认实现是刻意的：万一某个实现忘了覆写，行为也只是退化成「照常传输」，
         * 而不会编译不过或运行时报错。
         */
        fun isDedupHit(fileId: String): Boolean = false
    }

    private var fileTransferCallback: FileTransferCallback? = null

    fun setFileTransferCallback(callback: FileTransferCallback) {
        this.fileTransferCallback = callback
    }

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "本地 HTTP 服务已在运行中")
            return
        }

        acceptThread = Thread {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "本地微型 HTTP 服务已启动，监听端口: $port")
                DebugLogger.log("LOCAL_HTTP", "本地微型 HTTP 服务已启动，监听端口: $port")

                while (isRunning.get()) {
                    val clientSocket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.w(TAG, "accept 异常: ${e.message}")
                        continue
                    }
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "本地 HTTP 服务异常退出: ${e.message}")
                    DebugLogger.log("LOCAL_HTTP", "本地 HTTP 服务异常退出: ${e.message}", e)
                }
            }
        }.apply {
            isDaemon = true
            name = "CrossClip-LocalHttpServer"
            start()
        }
    }

    /**
     * 处理一条客户端连接。
     *
     * 采用 keep-alive 循环：在同一条连接上反复「读请求 → 处理 → 写响应」，
     * 直到客户端声明 `Connection: close`、空闲超时或连接出错。
     */
    private fun handleClient(socket: Socket) {
        val remoteIp = socket.inetAddress?.hostAddress ?: "未知"
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            // 关键性能开关：禁用 Nagle，避免小分块被攒包延迟
            socket.tcpNoDelay = true

            val input = BufferedInputStream(socket.getInputStream(), SOCKET_BUFFER_SIZE)
            val output = BufferedOutputStream(socket.getOutputStream(), SOCKET_BUFFER_SIZE)

            while (isRunning.get()) {
                val request = readHttpRequest(input) ?: break
                val keepAlive = dispatchRequest(request, output, remoteIp)
                output.flush()
                if (!keepAlive) break
            }
        } catch (_: SocketTimeoutException) {
            // keep-alive 空闲超时属于正常收尾，无需记录为异常
        } catch (e: Exception) {
            DebugLogger.log("LOCAL_HTTP", "处理来自 $remoteIp 的连接异常: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    // ==================== HTTP 报文解析 ====================

    /** 解析后的 HTTP 请求 */
    private class HttpRequest(
        val method: String,
        /** 不含 query 的路径，用于路由匹配 */
        val path: String,
        /** 含 query 的原始路径，用于解析参数 */
        val rawPath: String,
        val headers: Map<String, String>,
        /** 原始请求体字节（可能是二进制密文） */
        val body: ByteArray
    )

    /**
     * 从输入流解析一个完整的 HTTP 请求。
     * 返回 null 表示对端已关闭连接（正常收尾）。
     */
    private fun readHttpRequest(input: InputStream): HttpRequest? {
        // 1. 请求行
        val requestLine = readAsciiLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')

        // 2. 请求头（读到空行为止）
        val headers = HashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        // 3. 请求体：严格按 Content-Length 读取原始字节（支持二进制）
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readFully(input, contentLength) else ByteArray(0)

        return HttpRequest(method, path, rawPath, headers, body)
    }

    /** 逐字节读取一行 ASCII 文本（HTTP 起始行与头部均为 ASCII） */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(96)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b == '\r'.code) continue
            sb.append(b.toChar())
        }
    }

    /** 读满指定字节数（处理 TCP 分包） */
    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) buffer else buffer.copyOf(offset)
    }

    /** 解析 URL query 参数（形如 `?file_id=xxx&index=0`） */
    private fun parseQuery(rawPath: String): Map<String, String> {
        val query = rawPath.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val result = HashMap<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = try {
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                } catch (_: Exception) {
                    pair.substring(idx + 1)
                }
                result[key] = value
            }
        }
        return result
    }

    // ==================== 路由分发 ====================

    /** @return 是否继续保持连接（keep-alive） */
    private fun dispatchRequest(request: HttpRequest, out: OutputStream, remoteIp: String): Boolean {
        val keepAlive = request.headers["connection"]?.lowercase() != "close"

        when {
            request.path == "/sync" && request.method == "POST" -> {
                try {
                    val json = JSONObject(String(request.body, Charsets.UTF_8))
                    val enc = json.optString("encrypted", "")
                    val clock = json.optLong("lamport_clock", -1L)
                    val sender = json.optString("sender_id", "电脑端")
                    if (enc.isNotEmpty()) {
                        DebugLogger.log("LOCAL_HTTP", "收到电脑端对等 HTTP POST 推送: remote=$remoteIp, clock=$clock, 加密包长度=${enc.length}")
                        onSyncReceived?.invoke(enc, clock, sender)
                        writeJson(out, 200, "{\"status\":\"ok\"}", keepAlive)
                    } else {
                        writeJson(out, 400, "{\"error\":\"missing_encrypted_payload\"}", keepAlive)
                    }
                } catch (e: Exception) {
                    DebugLogger.log("LOCAL_HTTP", "解析电脑端推送 JSON 异常: ${e.message}")
                    writeJson(out, 400, "{\"error\":\"invalid_json\"}", keepAlive)
                }
            }

            request.path == "/ping" -> {
                writeJson(out, 200, "{\"status\":\"pong\"}", keepAlive)
            }

            // ==================== 文件传输端点 ====================

            request.path == "/file/prepare" && request.method == "POST" -> {
                try {
                    val json = JSONObject(String(request.body, Charsets.UTF_8))
                    val fileId = json.optString("file_id", "")
                    val filename = json.optString("filename", "unknown")
                    val fileSize = json.optLong("file_size", 0)
                    val mimeType = json.optString("mime_type", "application/octet-stream")
                    val senderId = json.optString("sender_id", "电脑端")
                    // 老版本电脑端不带 file_hash 字段，此时为 null，去重逻辑自动跳过
                    val fileHash = json.optString("file_hash", "").takeIf { it.isNotEmpty() }

                    if (fileId.isNotEmpty()) {
                        DebugLogger.log("LOCAL_HTTP", "收到电脑端文件准备: $filename ($fileSize bytes)")
                        val accepted = fileTransferCallback
                            ?.onFilePrepareReceived(fileId, filename, fileSize, mimeType, senderId, fileHash) ?: true
                        if (accepted) {
                            // 明确告知发送端「目标目录已有同一个文件」，让它一个分块都不用传
                            val alreadyExists = fileTransferCallback?.isDedupHit(fileId) ?: false
                            writeJson(
                                out,
                                200,
                                "{\"status\":\"ok\",\"file_id\":\"$fileId\",\"already_exists\":$alreadyExists}",
                                keepAlive
                            )
                        } else {
                            writeJson(out, 503, "{\"error\":\"rejected\"}", keepAlive)
                        }
                    } else {
                        writeJson(out, 400, "{\"error\":\"missing_file_id\"}", keepAlive)
                    }
                } catch (e: Exception) {
                    DebugLogger.log("LOCAL_HTTP", "解析文件准备 JSON 异常: ${e.message}")
                    writeJson(out, 400, "{\"error\":\"invalid_json\"}", keepAlive)
                }
            }

            request.path == "/file/chunk" && request.method == "POST" -> {
                // 二进制协议：分块密文直接作为请求体，元数据走 query 参数，省去 Base64 的 33% 膨胀
                val params = parseQuery(request.rawPath)
                val fileId = params["file_id"] ?: ""
                val chunkIndex = params["index"]?.toIntOrNull() ?: 0
                val totalChunks = params["total"]?.toIntOrNull() ?: 0

                if (fileId.isEmpty() || request.body.isEmpty()) {
                    writeJson(out, 400, "{\"error\":\"missing_fields\"}", keepAlive)
                } else {
                    val result = fileTransferCallback?.onFileChunkReceived(fileId, chunkIndex, totalChunks, request.body)
                    if (result != null) {
                        val (received, total) = result
                        writeJson(out, 200, "{\"status\":\"ok\",\"received\":$received,\"total\":$total}", keepAlive)
                    } else {
                        writeJson(out, 500, "{\"error\":\"handler_error\"}", keepAlive)
                    }
                }
            }

            request.path == "/file/complete" && request.method == "POST" -> {
                try {
                    val json = JSONObject(String(request.body, Charsets.UTF_8))
                    val fileId = json.optString("file_id", "")
                    val fileHash = json.optString("file_hash", "")

                    if (fileId.isNotEmpty()) {
                        DebugLogger.log("LOCAL_HTTP", "收到电脑端文件完成信号: $fileId")
                        val filePath = fileTransferCallback?.onFileCompleteReceived(fileId, fileHash)
                        if (filePath != null) {
                            writeJson(out, 200, "{\"status\":\"ok\",\"path\":\"$filePath\"}", keepAlive)
                        } else {
                            writeJson(out, 500, "{\"error\":\"complete_failed\"}", keepAlive)
                        }
                    } else {
                        writeJson(out, 400, "{\"error\":\"missing_file_id\"}", keepAlive)
                    }
                } catch (e: Exception) {
                    DebugLogger.log("LOCAL_HTTP", "解析文件完成 JSON 异常: ${e.message}")
                    writeJson(out, 400, "{\"error\":\"invalid_json\"}", keepAlive)
                }
            }

            else -> {
                writeJson(out, 404, "{\"error\":\"not_found\"}", keepAlive)
            }
        }

        return keepAlive
    }

    // ==================== 响应写出 ====================

    private fun writeJson(out: OutputStream, code: Int, jsonBody: String, keepAlive: Boolean) {
        writeResponse(out, code, jsonBody.toByteArray(Charsets.UTF_8), keepAlive)
    }

    private fun writeResponse(out: OutputStream, code: Int, body: ByteArray, keepAlive: Boolean) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val header = buildString(160) {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        DebugLogger.log("LOCAL_HTTP", "已停止本地微型 HTTP 服务")
    }
}
