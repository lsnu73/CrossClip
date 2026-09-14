package com.crossclip.app.network

import android.util.Log
import com.crossclip.app.crypto.CryptoUtil
import com.crossclip.app.util.DebugLogger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object HttpUploader {
    private const val TAG = "CrossClipHttp"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    /**
     * 验证 PIN 码与电脑连通性并注册手机端对等接收端口
     * @return success: 是否成功, statusCode: HTTP状态码 (200成功, 403密码错误, -1网络不可达), deviceName: 电脑名称, deviceId: 电脑设备指纹
     */
    fun verifyPin(
        pcIp: String,
        httpPort: Int = 18236,
        pinCode: String,
        deviceId: String = "",
        deviceName: String = "",
        clientPort: Int = 18237,
        onResult: (success: Boolean, statusCode: Int, deviceName: String?, deviceId: String?) -> Unit
    ) {
        val url = "http://$pcIp:$httpPort/auth"
        DebugLogger.log("HTTP", "发起 PIN 码密文挑战握手: $url (对等接收端口: $clientPort)")
        val nowMs = System.currentTimeMillis()

        // 构建握手挑战内层数据包
        val innerPayload = JSONObject().apply {
            put("timestamp", nowMs)
            put("client_port", clientPort)
            put("device_id", deviceId)
            put("device_name", deviceName)
        }.toString()

        // 使用 PIN 派生密钥进行 AES-256-GCM 加密 (杜绝明文 PIN 传输)
        val authCipher = CryptoUtil.encrypt(innerPayload, pinCode)

        val json = JSONObject().apply {
            put("auth_cipher", authCipher)
            put("timestamp", nowMs)
        }.toString()

        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "连接电脑失败 ($url): ${e.message}")
                DebugLogger.log("HTTP", "连接电脑失败 ($url): ${e.javaClass.simpleName}: ${e.message}")
                onResult(false, -1, null, null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    DebugLogger.log("HTTP", "PIN 码验证响应状态码: ${response.code}")
                    if (response.code == 200) {
                        val body = response.body?.string() ?: ""
                        try {
                            val respJson = JSONObject(body)
                            val devName = respJson.optString("device_name", "Windows 电脑")
                            val retDevId = respJson.optString("device_id", "")
                            DebugLogger.log("HTTP", "PIN 码验证成功，电脑名称: $devName, 设备ID: $retDevId")
                            onResult(true, 200, devName, retDevId)
                        } catch (e: Exception) {
                            onResult(true, 200, "Windows 电脑", null)
                        }
                    } else if (response.code == 403) {
                        Log.w(TAG, "PIN 码不匹配，电脑端拒绝配对")
                        DebugLogger.log("HTTP", "PIN 码不匹配，电脑端拒绝配对 (403)")
                        onResult(false, 403, null, null)
                    } else {
                        onResult(false, response.code, null, null)
                    }
                }
            }
        })
    }

    /**
     * 向电脑端发送剪贴板数据
     */
    fun sendClipboard(
        pcIp: String,
        httpPort: Int = 18236,
        text: String,
        deviceId: String,
        pinCode: String,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val url = "http://$pcIp:$httpPort/sync"
        val encryptedPayload = CryptoUtil.encrypt(text, pinCode)
        val textHash = CryptoUtil.computeHash(text)
        val preview = if (text.length > 20) text.take(20) + "..." else text
        val startTime = System.currentTimeMillis()

        DebugLogger.log("HTTP", "开始发送剪贴板到电脑 ($url): 长度=${text.length}, hash=$textHash, 预览=[$preview]")

        val json = JSONObject().apply {
            put("type", "CLIP_SYNC")
            put("sender_id", deviceId)
            put("hash", textHash)
            put("encrypted", encryptedPayload)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.w(TAG, "发送剪贴板到电脑失败 ($url): ${e.message}")
                DebugLogger.log("HTTP", "发送剪贴板失败 (耗时 ${elapsed}ms): ${e.javaClass.simpleName}: ${e.message}", e)
                onResult?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val elapsed = System.currentTimeMillis() - startTime
                response.use {
                    if (response.isSuccessful) {
                        Log.i(TAG, "成功通过 HTTP 将剪贴板同步至电脑")
                        DebugLogger.log("HTTP", "成功将剪贴板同步至电脑 (耗时 ${elapsed}ms, 状态码: ${response.code})")
                        onResult?.invoke(true)
                    } else {
                        Log.w(TAG, "电脑端返回错误码: ${response.code}")
                        DebugLogger.log("HTTP", "电脑端返回错误码: ${response.code} (耗时 ${elapsed}ms)")
                        onResult?.invoke(false)
                    }
                }
            }
        })
    }

    /**
     * 周期性向电脑端发送保活心跳与当前 IP/端口注册 (刷新路由器 ARP 与电脑端设备表)
     */
    fun sendHeartbeat(
        pcIp: String,
        httpPort: Int = 18236,
        pinCode: String,
        deviceId: String,
        deviceName: String,
        clientPort: Int = 18237,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (pcIp.isEmpty() || pinCode.isEmpty()) return
        val url = "http://$pcIp:$httpPort/heartbeat"
        val pinHash = CryptoUtil.computeHash(pinCode).substring(0, 16)
        val json = JSONObject().apply {
            put("pin_hash", pinHash)
            put("client_port", clientPort)
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(requestBody).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLogger.log("HEARTBEAT_SEND", "心跳上报失败: ${e.message}")
                onResult?.invoke(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                response.close()
                DebugLogger.log("HEARTBEAT_SEND", "心跳上报成功 (状态码: ${response.code})")
                onResult?.invoke(ok)
            }
        })
    }

    /**
     * 告知电脑端「手机端已主动断开连接」。
     *
     * 电脑端的「已连接手机」原本只靠 600 秒心跳超时清理，用户点了断开后托盘还会继续挂
     * 10 分钟。这里在断开时主动打一声招呼，电脑端收到即摘掉该设备的连接记录。
     *
     * 属于「尽力而为」：手机侧网络已不可用时静默失败即可，电脑端仍由 SSE 长连接结束
     * 或心跳超时兜底 —— 差几秒不影响正确性，绝不能因为这次通知失败就卡住断开流程。
     */
    fun notifyPcDisconnected(
        pcIp: String,
        httpPort: Int = 18236,
        pinCode: String,
        deviceId: String = ""
    ) {
        if (pcIp.isEmpty() || pinCode.isEmpty()) return
        val url = "http://$pcIp:$httpPort/disconnect"
        val pinHash = CryptoUtil.computeHash(pinCode).substring(0, 16)
        val json = JSONObject().apply {
            put("pin_hash", pinHash)
            put("device_id", deviceId)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(requestBody).build()
        DebugLogger.log("HTTP", "通知电脑端「本机已主动断开」: $url")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLogger.log("HTTP", "通知电脑端断开失败（忽略，交由 SSE 断开/心跳超时兜底）: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()
                DebugLogger.log("HTTP", "已通知电脑端断开连接 (状态码: $code)")
            }
        })
    }
}
