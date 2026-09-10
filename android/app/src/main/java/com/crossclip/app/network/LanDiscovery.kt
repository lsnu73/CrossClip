package com.crossclip.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.crossclip.app.util.DebugLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanDiscovery(
    private val context: Context,
    private val udpPort: Int = 18234,
    private val onDeviceFound: (deviceId: String, deviceName: String, ip: String, httpPort: Int, wsPort: Int) -> Unit
) {
    private val TAG = "CrossClipDiscovery"

    @Volatile
    private var isSearching = false
    @Volatile
    var isConnected = false

    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var scanExecutor: java.util.concurrent.ExecutorService? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build()

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("CrossClipMulticastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 MulticastLock 异常: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 MulticastLock 异常: ${e.message}")
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取广播地址异常: ${e.message}")
        }
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}
        return addresses.distinct()
    }

    private fun getLocalIpv4List(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (addr in networkInterface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(addr.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取本地 IPv4 异常: ${e.message}")
        }
        return ips
    }

    private var currentHintIp: String = ""
    private var currentDeviceId: String = "android"

    // ==================== 自动搜索策略（省电） ====================

    @Volatile
    private var autoSearchEnabledState: Boolean = true

    /**
     * 是否开启「持续自动搜索」。关闭后仅在手动点击「重新扫描」时做有限轮次扫描。
     *
     * 这里声明为只读属性：写入统一走带副作用的 [setAutoSearchEnabled]（会立即中断
     * 搜索循环并释放 socket / MulticastLock）。若写成 `var`，其自动生成的 setter 会与
     * 该方法产生 JVM 签名冲突（Platform declaration clash: setAutoSearchEnabled(Z)V）。
     */
    val autoSearchEnabled: Boolean
        get() = autoSearchEnabledState

    /** 本轮自动搜索的时间窗起点，用于 0-5 / 5-15 / >15 分钟的分级降频 */
    @Volatile
    private var searchWindowStartMs: Long = 0L

    /** 手动扫描模式下剩余的扫描轮数 */
    @Volatile
    private var manualRoundsLeft: Int = 0

    companion object {
        /** 0-5 分钟：正常扫描间隔 */
        private const val SCAN_INTERVAL_FAST_MS = 2500L

        /** 5-15 分钟：降频后的扫描间隔（每分钟一次） */
        private const val SCAN_INTERVAL_SLOW_MS = 60_000L

        /** 超过该时长仍未发现电脑就停止自动搜索，等待用户手动触发 */
        private const val AUTO_SEARCH_STOP_MS = 15 * 60 * 1000L

        /** 超过该时长开始降频 */
        private const val AUTO_SEARCH_SLOWDOWN_MS = 5 * 60 * 1000L

        /** 手动扫描模式下实际执行的扫描轮数 */
        private const val MANUAL_SCAN_ROUNDS = 3
    }

    /**
     * 设置是否开启持续自动搜索。
     *
     * 关闭时会**立即**中断当前搜索循环并释放 UDP socket 与 MulticastLock，
     * 避免夜间电脑关机后手机端空转 8 小时以上持续耗电。
     */
    fun setAutoSearchEnabled(enabled: Boolean) {
        if (autoSearchEnabledState == enabled) return
        autoSearchEnabledState = enabled
        DebugLogger.log(TAG, "自动搜索开关变更为: ${if (enabled) "开启" else "关闭"}")
        if (!enabled) {
            isSearching = false
            try {
                socket?.close()
            } catch (_: Exception) {}
            socket = null
            releaseMulticastLock()
        }
    }

    fun startDiscovery(
        deviceId: String = "android",
        deviceName: String = "安卓手机",
        hintIp: String = "",
        manualScan: Boolean = false
    ) {
        if (isSearching) return
        isSearching = true
        currentHintIp = hintIp
        currentDeviceId = deviceId
        // 每次重新搜索都重置时间窗；手动扫描模式下只执行有限轮数
        searchWindowStartMs = System.currentTimeMillis()
        manualRoundsLeft = if (manualScan) MANUAL_SCAN_ROUNDS else 0
        acquireMulticastLock()

        if (scanExecutor == null || scanExecutor?.isShutdown == true || scanExecutor?.isTerminated == true) {
            scanExecutor = Executors.newFixedThreadPool(16)
        }

        // 1. 启动 UDP 广播探测与应答监听
        Thread {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(udpPort))
                }
            } catch (e: Exception) {
                try {
                    socket = DatagramSocket().apply { broadcast = true }
                } catch (ex: Exception) {
                    Log.e(TAG, "创建 UDP Socket 失败: ${ex.message}")
                }
            }

            // 监听应答线程
            Thread {
                val buf = ByteArray(2048)
                while (isSearching) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket?.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val fromIp = packet.address.hostAddress ?: ""
                        if (fromIp.isNotEmpty() && !fromIp.startsWith("127.")) {
                            val localIps = getLocalIpv4List()
                            // 1. 过滤本机回环广播
                            if (localIps.contains(fromIp)) {
                                continue
                            }

                            val json = JSONObject(text)
                            val msgType = json.optString("type", "")
                            // 2. 严格校验报文类型：只处理电脑端响应的 OFFER 报文，直接丢弃手机发出的 DISCOVER 广播
                            if (msgType != "OFFER") {
                                continue
                            }

                            val devId = json.optString("device_id", "")
                            // 3. 过滤自身设备 ID 或包含 android 前缀的移动端设备
                            if (devId == deviceId || devId.startsWith("android_")) {
                                continue
                            }

                            val actualDevId = if (devId.isNotEmpty()) devId else "pc_${fromIp.replace('.', '_')}"
                            val httpPort = json.optInt("http_port", 18236)
                            val wsPort = json.optInt("ws_port", 18238)
                            val pcName = json.optString("device_name", "Windows 电脑")

                            // 优先使用数据包物理源 IP，若报文内包含与手机同子网的候选 IP 则优选该候选 IP
                            var targetIp = fromIp
                            val ipsArray = json.optJSONArray("ips")
                            if (ipsArray != null && ipsArray.length() > 0) {
                                for (i in 0 until ipsArray.length()) {
                                    val candidateIp = ipsArray.optString(i)
                                    if (candidateIp.isNotEmpty() && isSameSubnetAny(candidateIp, localIps)) {
                                        targetIp = candidateIp
                                        break
                                    }
                                }
                            }

                            Log.i(TAG, "通过 UDP 发现电脑: $pcName ($actualDevId) -> $targetIp:$httpPort")
                            onDeviceFound(actualDevId, pcName, targetIp, httpPort, wsPort)
                        }
                    } catch (e: Exception) {
                        if (!isSearching) break
                    }
                }
            }.start()

            // 广播发送循环
            val reqMsg = JSONObject().apply {
                put("type", "DISCOVER")
                put("device_id", deviceId)
                put("device_name", deviceName)
            }.toString().toByteArray(Charsets.UTF_8)

            while (isSearching) {
                // 1. 向所有广播地址发送 UDP DISCOVER 探测
                val broadcastAddrs = getBroadcastAddresses()
                for (addr in broadcastAddrs) {
                    try {
                        val packet = DatagramPacket(reqMsg, reqMsg.size, addr, udpPort)
                        socket?.send(packet)
                    } catch (_: Exception) {}
                }

                // 2. 已连接：转入低频保活（8 秒一轮），仅用于感知网络变化与 IP 漂移
                if (isConnected) {
                    try {
                        Thread.sleep(8000)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }

                // 3. 未连接：并发探测当前子网全部 IP
                fastSubnetScan()

                // 4. 自动搜索已关闭：仅完成手动扫描的有限轮数后停止
                if (!autoSearchEnabled) {
                    if (manualRoundsLeft > 0) {
                        manualRoundsLeft--
                        try {
                            Thread.sleep(SCAN_INTERVAL_FAST_MS)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }
                    DebugLogger.log(TAG, "自动搜索已关闭，停止局域网扫描（等待用户手动触发）")
                    isSearching = false
                    break
                }

                // 5. 分阶段降频：0-5 分钟正常 → 5-15 分钟每分钟一次 → 超过 15 分钟停止
                val elapsed = System.currentTimeMillis() - searchWindowStartMs
                val interval = when {
                    elapsed < AUTO_SEARCH_SLOWDOWN_MS -> SCAN_INTERVAL_FAST_MS
                    elapsed < AUTO_SEARCH_STOP_MS -> SCAN_INTERVAL_SLOW_MS
                    else -> {
                        DebugLogger.log(
                            TAG,
                            "自动搜索已持续 ${elapsed / 60000} 分钟仍未发现电脑，停止搜索等待手动触发"
                        )
                        isSearching = false
                        break
                    }
                }

                try {
                    Thread.sleep(interval)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun isSameSubnetAny(ip: String, localIps: List<String>): Boolean {
        val ipParts = ip.split(".")
        if (ipParts.size != 4) return false
        val prefix = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}."
        return localIps.any { it.startsWith(prefix) }
    }

    /**
     * 快速并发扫描当前子网所有 IP 的 18236 端口 (确保 100% 发现)
     */
    private fun fastSubnetScan() {
        val executor = scanExecutor ?: return
        val localIps = getLocalIpv4List()
        val prefixesToScan = mutableSetOf<String>()

        for (localIp in localIps) {
            val parts = localIp.split(".")
            if (parts.size == 4) {
                prefixesToScan.add("${parts[0]}.${parts[1]}.${parts[2]}")
            }
        }

        // 如果存在历史 IP 且其处于不同网段，将其前缀也纳入扫描
        if (currentHintIp.isNotEmpty()) {
            val hintParts = currentHintIp.split(".")
            if (hintParts.size == 4) {
                prefixesToScan.add("${hintParts[0]}.${hintParts[1]}.${hintParts[2]}")
                // 首先单发探测 hintIp
                try {
                    executor.submit {
                        probeIp(currentHintIp)
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {}
            }
        }

        for (subnetPrefix in prefixesToScan) {
            for (i in 1..254) {
                val targetIp = "$subnetPrefix.$i"
                if (localIps.contains(targetIp)) continue
                try {
                    executor.submit {
                        if (!isSearching || isConnected) return@submit
                        probeIp(targetIp)
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    return
                }
            }
        }
    }

    private fun probeIp(targetIp: String) {
        try {
            val req = Request.Builder()
                .url("http://$targetIp:18236/ping")
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val devName = json.optString("device_name", "Windows 电脑")
                val devId = json.optString("device_id", "").ifEmpty { "pc_${targetIp.replace('.', '_')}" }
                // 排除自身 ID 以及任何包含 android 前缀的设备
                if (!devId.startsWith("android_") && devId != currentDeviceId) {
                    Log.i(TAG, "通过主动探测发现电脑: $devName ($devId) -> $targetIp:18236")
                    onDeviceFound(devId, devName, targetIp, 18236, 18238)
                }
            }
            resp.close()
        } catch (_: Exception) {}
    }

    fun stopDiscovery() {
        isSearching = false
        try {
            scanExecutor?.shutdownNow()
        } catch (_: Exception) {}
        scanExecutor = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        releaseMulticastLock()
    }
}
