package com.crossclip.app.network

import com.crossclip.app.crypto.CryptoUtil
import com.crossclip.app.util.DebugLogger
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 文件上传器 —— 把手机本地文件分块上传到电脑端。
 *
 * ## 协议（二进制分块，v2）
 * 1. `POST /file/prepare`  —— JSON 元数据（file_id / filename / file_size / mime_type）
 * 2. `POST /file/chunk?file_id=..&index=..&total=..`
 *    —— **密文直接作为请求体**（`application/octet-stream`），不套 JSON、不做 Base64
 * 3. `POST /file/complete` —— JSON（file_id / file_hash），触发电脑端整文件校验与落盘
 *
 * ## 性能设计（相对旧版的关键改动）
 * - **启用 OkHttp 连接池**：旧版 `ConnectionPool(0, ...)` 不缓存任何空闲连接，
 *   导致 256KB 分块时每个分块都要重新 TCP 三次握手；现改为复用长连接。
 * - **二进制 body 取代 Base64 JSON**：省去 33% 的体积膨胀与 JSON 序列化开销。
 * - **流式读写文件**：旧版把整个文件 `readBytes()` 进内存 3 次（分块 + 两次算哈希，
 *   且其中一次 `readText` 对二进制文件本身就是错的）；现改为边读边发热，哈希也走流式，
 *   内存占用恒定为 1 个分块大小，GB 级文件也不会 OOM。
 * - **分块放大到 1MB**：请求数减少 4 倍（必须与电脑端 `server.rs` 的 CHUNK_SIZE 保持一致）。
 */
object FileUploader {

    private const val TAG = "FileUploader"

    /** 分块大小：必须与电脑端 rust_desktop/src/server.rs 的 CHUNK_SIZE 保持一致 */
    const val CHUNK_SIZE = 1024 * 1024 // 1MB

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()

    /**
     * 文件传输专用 HTTP 客户端。
     * 连接池保留 8 个空闲连接、保活 5 分钟 —— 这是大文件传输提速的关键。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 上传文件到电脑端（在独立后台线程中执行，回调也在该线程触发）。
     *
     * @param onProgress 进度回调（0-100），仅在整数百分比变化时触发
     * @param onSuccess  成功回调，参数为提示文本
     * @param onError    失败回调，参数为错误描述
     */
    fun uploadFile(
        pcIp: String,
        httpPort: Int = 18236,
        pinCode: String,
        deviceId: String,
        file: File,
        filename: String,
        mimeType: String,
        onProgress: ((Int) -> Unit)? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val fileId = UUID.randomUUID().toString()
        val fileSize = file.length()
        val totalChunks = if (fileSize == 0L) 0 else ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

        DebugLogger.log(TAG, "开始上传文件: $filename ($fileSize bytes, $totalChunks 块) -> $pcIp:$httpPort")

        Thread {
            try {
                // ---------- 0. 流式计算整文件 SHA-256（不把文件读进内存） ----------
                val fileHash = CryptoUtil.computeHashFile(file)

                // ---------- 1. 发送文件准备消息（带上整文件哈希，供电脑端判定是否已有同一文件） ----------
                val preparePayload = JSONObject().apply {
                    put("type", "FILE_PREPARE")
                    put("file_id", fileId)
                    put("filename", filename)
                    put("file_size", fileSize)
                    put("mime_type", mimeType)
                    put("sender_id", deviceId)
                    put("file_hash", fileHash)
                }.toString()

                val prepareResponse = postJson("http://$pcIp:$httpPort/file/prepare", preparePayload)
                if (prepareResponse == null) {
                    onError("电脑端未响应文件准备请求")
                    return@Thread
                }

                // 电脑端已有同名同内容的文件：一个分块都不用传。
                //
                // 但**结束信号仍然必须发** —— 电脑端靠它回收这次传输的记录、落定状态。
                // 少了这一步，电脑端 incoming 表会把这条记录一直挂着，
                // 而且它也就无法把「已存在」这一结果回复给我们。
                if (prepareResponse.contains("\"already_exists\":true")) {
                    DebugLogger.log(TAG, "电脑端已存在相同文件，跳过全部分块: $filename")
                    val completePayload = JSONObject().apply {
                        put("type", "FILE_COMPLETE")
                        put("file_id", fileId)
                        put("file_hash", fileHash)
                    }.toString()
                    if (postJson("http://$pcIp:$httpPort/file/complete", completePayload) == null) {
                        onError("文件已存在，但结束信号发送失败")
                        return@Thread
                    }
                    onSuccess("电脑端已有该文件，无需重复传输")
                    return@Thread
                }

                // ---------- 2. 流式分块上传 ----------
                val buffer = ByteArray(CHUNK_SIZE)
                FileInputStream(file).use { fis ->
                    var chunkIndex = 0
                    var lastPercent = -1
                    var offset = 0L

                    while (offset < fileSize) {
                        val want = minOf(CHUNK_SIZE.toLong(), fileSize - offset).toInt()
                        val read = readFully(fis, buffer, want)
                        if (read <= 0) break

                        // 取实际读到的字节（最后一块通常不足 1MB）
                        val chunkData = if (read == buffer.size) buffer else buffer.copyOf(read)

                        // 加密为二进制密文（nonce||ciphertext||tag），直接作为 body 发送
                        val encrypted = CryptoUtil.encryptToBytes(chunkData, pinCode)

                        val url = "http://$pcIp:$httpPort/file/chunk" +
                                "?file_id=$fileId&index=$chunkIndex&total=$totalChunks"
                        if (!postBytes(url, encrypted)) {
                            onError("发送第 $chunkIndex 块失败")
                            return@Thread
                        }

                        chunkIndex++
                        offset += read

                        // 进度节流：只在整数百分比变化时回调，避免高频 UI 刷新
                        val percent = if (fileSize > 0) ((offset * 100) / fileSize).toInt() else 100
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress?.invoke(percent)
                        }
                    }
                }

                // ---------- 3. 发送完成信号（携带整文件哈希供电脑端校验） ----------
                val completePayload = JSONObject().apply {
                    put("type", "FILE_COMPLETE")
                    put("file_id", fileId)
                    put("file_hash", fileHash)
                }.toString()

                if (postJson("http://$pcIp:$httpPort/file/complete", completePayload) == null) {
                    onError("文件已传输完毕，但完成校验未通过")
                    return@Thread
                }

                DebugLogger.log(TAG, "文件上传完成: $filename")
                onSuccess("文件已保存到电脑")

            } catch (e: Exception) {
                DebugLogger.log(TAG, "文件上传异常: ${e.message}", e)
                onError("上传异常: ${e.message}")
            }
        }.apply { name = "CrossClip-FileUpload" }.start()
    }

    /** 发送 JSON 请求体（同步阻塞，调用方需处于后台线程）；成功返回响应体文本，失败返回 null */
    private fun postJson(url: String, jsonBody: String): String? {
        return executeRequest(url, jsonBody.toRequestBody(JSON_MEDIA_TYPE), "/json")
    }

    /** 发送二进制请求体（同步阻塞） */
    private fun postBytes(url: String, data: ByteArray): Boolean {
        return executeRequest(url, data.toRequestBody(BINARY_MEDIA_TYPE), "/binary") != null
    }

    /**
     * 统一执行 POST 请求。
     *
     * 成功时把**响应体文本**一并返回：`prepare` 阶段要靠它读 `already_exists` 字段来决定
     * 是否跳过整轮分块上传。失败（网络异常 / 非 2xx）返回 null。
     */
    private fun executeRequest(url: String, body: okhttp3.RequestBody, tag: String): String? {
        return try {
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLogger.log(TAG, "HTTP 请求返回错误 $tag: ${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: Exception) {
            DebugLogger.log(TAG, "HTTP 请求异常 $tag: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 尽量读满 [length] 字节；返回实际读取到的字节数（0 表示流已结束） */
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }
}
