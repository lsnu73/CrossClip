package com.crossclip.app.network

import android.util.Log
import com.crossclip.app.util.DebugLogger
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SseClient(
    private val onMessageReceived: (encrypted: String, hash: String, senderId: String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onFileEvent: ((eventType: String, data: JSONObject) -> Unit)? = null
) {
    private val TAG = "CrossClipSSE"
    @Volatile
    private var isClosed = false
    private var thread: Thread? = null
    @Volatile
    private var currentCall: Call? = null
    // 代际标记：connect/disconnect 都会使其自增。读线程只认自己启动时的那一代，
    // 一旦被取代就静默退出——不上报状态、不处理数据、不重试，杜绝僵尸连接
    private val generation = AtomicInteger(0)

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接不超时
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build()

    private fun isActive(gen: Int): Boolean = !isClosed && generation.get() == gen

    fun connect(url: String) {
        disconnect()
        isClosed = false
        val myGen = generation.incrementAndGet()

        thread = Thread {
            Log.i(TAG, "正在建立 SSE 剪贴板长连接: $url")
            DebugLogger.log("SSE", "开始发起 SSE 长连接: $url")
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .build()

            while (isActive(myGen)) {
                var call: Call? = null
                try {
                    call = client.newCall(request)
                    currentCall = call
                    // 建立连接的空窗期可能被 disconnect/新 connect 取代，此时必须取消而非继续
                    if (!isActive(myGen)) {
                        call.cancel()
                        break
                    }
                    val response = call.execute()
                    if (response.isSuccessful) {
                        // 读流期间绝不能把 currentCall 置空，否则 disconnect() 拿不到 Call
                        // 无法 cancel 底层 socket，而 interrupt 又叫不醒阻塞中的读操作，
                        // 旧线程会变成僵尸：一直挂到下一帧 keepalive 才抛 interrupted，
                        // 并误报一次假的断线回调，引发周期性重连风暴
                        if (isActive(myGen)) {
                            onConnectionChanged(true)
                            DebugLogger.log("SSE", "SSE 长连接建立成功 (状态码: ${response.code})")
                        }
                        val source = response.body?.source()
                        if (source != null) {
                            while (isActive(myGen) && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data: ")) {
                                    val data = line.substring(6).trim()
                                    if (data.isNotEmpty()) {
                                        try {
                                            val json = JSONObject(data)
                                            val type = json.optString("type", "")
                                            // 处理文件传输相关事件
                                            if (type.startsWith("FILE_")) {
                                                DebugLogger.log("SSE", "收到文件传输事件: type=$type")
                                                onFileEvent?.invoke(type, json)
                                            } else {
                                                val enc = json.optString("encrypted", "")
                                                val hash = json.optString("hash", "")
                                                val sender = json.optString("sender_id", "电脑端")
                                                if (enc.isNotEmpty()) {
                                                    DebugLogger.log("SSE", "收到电脑端下发数据: hash=$hash, sender=$sender, 加密包长度=${enc.length}")
                                                    onMessageReceived(enc, hash, sender)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            DebugLogger.log("SSE", "解析 data JSON 异常: ${e.message}")
                                        }
                                    }
                                } else if (line.startsWith(": ping") || line.startsWith(": keepalive")) {
                                    DebugLogger.log("SSE_PING", "收到电脑端保活心跳帧: $line")
                                }
                            }
                            // 内层读取循环结束（流断开或电脑端退出），立即触发断开通知；
                            // 被取代的旧代不通知——它的连接已被新连接顶替，不算掉线
                            if (isActive(myGen)) {
                                DebugLogger.log("SSE", "电脑端 SSE 数据流已断开或退出")
                                onConnectionChanged(false)
                            }
                        }
                    } else if (response.code == 403) {
                        Log.w(TAG, "SSE 连接被拒绝 (403)，PIN 码不匹配")
                        DebugLogger.log("SSE", "SSE 连接被拒绝 (403)，PIN 码不匹配")
                        if (isActive(myGen)) onConnectionChanged(false)
                        response.close()
                        break
                    } else {
                        DebugLogger.log("SSE", "SSE 建立失败，HTTP 状态码: ${response.code}")
                        if (isActive(myGen)) onConnectionChanged(false)
                    }
                    response.close()
                    if (currentCall === call) {
                        currentCall = null
                    }
                } catch (e: Exception) {
                    if (currentCall === call) {
                        currentCall = null
                    }
                    if (isActive(myGen)) {
                        Log.w(TAG, "SSE 连接异常断开: ${e.message}，将在 2 秒后自动重连...")
                        DebugLogger.log("SSE", "SSE 异常断开: ${e.javaClass.simpleName}: ${e.message}，2 秒后重试", e)
                        onConnectionChanged(false)
                        try {
                            Thread.sleep(2000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    } else {
                        // 被 disconnect() cancel 或被新 connect() 取代：属于预期内关闭，静默退出
                        DebugLogger.log("SSE", "SSE 旧代连接已关闭 (${e.javaClass.simpleName}: ${e.message})")
                    }
                }
            }
            DebugLogger.log("SSE", "SSE 接收循环退出 (isClosed=$isClosed, gen=$myGen, current=${generation.get()})")
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        isClosed = true
        // 先作废旧一代，再 cancel：cancel 会关闭底层 socket，阻塞中的读操作立刻抛异常退出，
        // 这是唯一能真正杀死读线程的手段（interrupt 无法唤醒阻塞的 socket read）
        generation.incrementAndGet()
        currentCall?.cancel()
        currentCall = null
        thread?.interrupt()
        thread = null
        onConnectionChanged(false)
        DebugLogger.log("SSE", "主动断开 SSE 长连接")
    }
}
