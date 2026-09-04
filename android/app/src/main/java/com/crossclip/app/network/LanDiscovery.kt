package com.crossclip.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
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
    private val onDeviceFound: (ip: String, httpPort: Int, wsPort: Int, deviceName: String) -> Unit
) {
    private val TAG = "CrossClipDiscovery"

    @Volatile
    private var isSearching = false
    @Volatile
    var isConnected = false

    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val scanExecutor = Executors.newFixedThreadPool(16)
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

    fun startDiscovery(deviceId: String = "android", deviceName: String = "安卓手机") {
        if (isSearching) return
        isSearching = true
        acquireMulticastLock()

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
                            val json = JSONObject(text)
                            val httpPort = json.optInt("http_port", 18236)
                            val wsPort = json.optInt("ws_port", 18238)
                            val pcName = json.optString("device_name", "Windows 电脑")
                            Log.i(TAG, "通过 UDP 发现电脑: $pcName -> $fromIp:$httpPort")
                            onDeviceFound(fromIp, httpPort, wsPort, pcName)
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

            while (isSearching && !isConnected) {
                val broadcastAddrs = getBroadcastAddresses()
                for (addr in broadcastAddrs) {
                    try {
                        val packet = DatagramPacket(reqMsg, reqMsg.size, addr, udpPort)
                        socket?.send(packet)
                    } catch (_: Exception) {}
                }

                // 2. 并发快速探测局域网网段 (每隔 3 秒一次)
                fastSubnetScan()

                try {
                    Thread.sleep(2500)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    /**
     * 快速并发扫描当前子网所有 IP 的 18236 端口 (确保 100% 发现)
     */
    private fun fastSubnetScan() {
        val localIps = getLocalIpv4List()
        for (localIp in localIps) {
            val parts = localIp.split(".")
            if (parts.size == 4) {
                val subnetPrefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                for (i in 1..254) {
                    val targetIp = "$subnetPrefix.$i"
                    if (targetIp == localIp) continue
                    scanExecutor.submit {
                        if (!isSearching || isConnected) return@submit
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
                                Log.i(TAG, "通过子网扫描发现电脑: $devName -> $targetIp:18236")
                                onDeviceFound(targetIp, 18236, 18238, devName)
                            }
                            resp.close()
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun stopDiscovery() {
        isSearching = false
        scanExecutor.shutdownNow()
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        releaseMulticastLock()
    }
}
