package com.crossclip.app.network

import android.util.Log
import com.crossclip.app.crypto.CryptoUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WsClient(
    private val onMessageReceived: (text: String, senderId: String) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, info: String) -> Unit
) {
    private val TAG = "CrossClipWs"
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 不设读取超时
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false
    private var lastConnectAttemptTime = 0L

    private var currentUrl: String = ""
    private var currentRoomKey: String = ""
    private var currentDeviceId: String = ""
    private var currentDeviceName: String = ""

    @Synchronized
    fun connect(url: String, roomKey: String, deviceId: String, deviceName: String) {
        val now = System.currentTimeMillis()
        // 若已连接同一个 URL，直接返回
        if (isConnected && currentUrl == url) {
            return
        }
        // 若正在连接中且距离上次尝试不足 4 秒，防止重复重入掐断
        if (isConnecting && (now - lastConnectAttemptTime) < 4000) {
            return
        }

        isConnecting = true
        lastConnectAttemptTime = now
        currentUrl = url
        currentRoomKey = roomKey
        currentDeviceId = deviceId
        currentDeviceName = deviceName

        Log.i(TAG, "正在发起 WebSocket 连接: $url")
        disconnectInternal()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isConnecting = false
                Log.i(TAG, "WebSocket 握手成功: $url")
                onConnectionStateChanged(true, "已连接: $url")

                // 如果是公网中继，发送加入房间请求
                if (url.contains("relay") || !url.contains(":18236")) {
                    val roomId = CryptoUtil.computeHash(currentRoomKey).substring(0, 16)
                    val joinMsg = JSONObject().apply {
                        put("type", "JOIN")
                        put("room_id", roomId)
                        put("device_name", currentDeviceName)
                    }.toString()
                    webSocket.send(joinMsg)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket 正在关闭: $reason")
                isConnected = false
                isConnecting = false
                onConnectionStateChanged(false, "连接关闭")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 连接失败 ($url): ${t.localizedMessage}")
                isConnected = false
                isConnecting = false
                onConnectionStateChanged(false, "连接失败: ${t.localizedMessage}")
            }
        })
    }

    private fun handleMessage(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            if (json.optString("type") == "CLIP_SYNC") {
                val senderId = json.optString("sender_id")
                if (senderId == currentDeviceId) return // 过滤自己发出的

                val encrypted = json.optString("encrypted")
                val hash = json.optString("hash")
                val decrypted = CryptoUtil.decrypt(encrypted, currentRoomKey)

                if (CryptoUtil.computeHash(decrypted) == hash) {
                    onMessageReceived(decrypted, senderId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析同步消息异常: ${e.message}")
        }
    }

    fun sendClipboard(text: String, deviceId: String, roomKey: String): Boolean {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "未连接到对端，无法发送剪贴板")
            return false
        }
        return try {
            val hash = CryptoUtil.computeHash(text)
            val encrypted = CryptoUtil.encrypt(text, roomKey)
            val payload = JSONObject().apply {
                put("type", "CLIP_SYNC")
                put("sender_id", deviceId)
                put("hash", hash)
                put("encrypted", encrypted)
                put("timestamp", System.currentTimeMillis())
            }.toString()

            val success = webSocket?.send(payload) ?: false
            Log.i(TAG, "发送剪贴板数据结果: $success (字符数: ${text.length})")
            success
        } catch (e: Exception) {
            Log.e(TAG, "发送剪贴板异常: ${e.message}")
            false
        }
    }

    private fun disconnectInternal() {
        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (e: Exception) {}
        webSocket = null
        isConnected = false
    }

    fun disconnect() {
        isConnecting = false
        disconnectInternal()
    }

    fun isOnline(): Boolean = isConnected
    fun isBusyConnecting(): Boolean = isConnecting
}
