package com.crossclip.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress

class NsdHelper(
    private val context: Context,
    private val onDeviceFound: (deviceId: String, deviceName: String, hostIp: String, port: Int) -> Unit,
    private val onDeviceLost: (deviceName: String) -> Unit
) {
    private val TAG = "CrossClipNsd"
    private val SERVICE_TYPE = "_crossclip._tcp."
    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    @Volatile
    private var isDiscovering = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS 服务发现已启动: $regType")
                isDiscovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "发现 mDNS 服务: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("crossclip") || serviceInfo.serviceName.contains("CrossClip")) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "丢失 mDNS 服务: ${serviceInfo.serviceName}")
                onDeviceLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS 服务发现已停止")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "启动 mDNS 服务发现失败: $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "停止 mDNS 服务发现失败: $errorCode")
                isDiscovering = false
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "注册 mDNS 发现异常: ${e.message}")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "解析 mDNS 服务失败: $errorCode")
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val host: InetAddress = resolvedInfo.host
                    val port = resolvedInfo.port
                    val ip = host.hostAddress ?: ""
                    val deviceName = resolvedInfo.serviceName
                    val attributes = resolvedInfo.attributes
                    val deviceIdAttr = if (attributes != null && attributes.containsKey("device_id")) {
                        String(attributes["device_id"] ?: byteArrayOf(), Charsets.UTF_8)
                    } else {
                        ""
                    }
                    val deviceId = if (deviceIdAttr.isNotEmpty()) deviceIdAttr else "pc_${ip.replace('.', '_')}"

                    if (ip.isNotEmpty() && !ip.startsWith("127.")) {
                        // 异步极速 HTTP 探活 (800ms 超时)，彻底拦截 Android 系统的 mDNS 离线幽灵缓存
                        Thread {
                            try {
                                val url = java.net.URL("http://$ip:$port/ping")
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 800
                                conn.readTimeout = 800
                                conn.requestMethod = "GET"
                                if (conn.responseCode == 200) {
                                    Log.i(TAG, "mDNS 设备物理在线确认: $deviceName ($deviceId) -> $ip:$port")
                                    onDeviceFound(deviceId, deviceName, ip, port)
                                }
                                conn.disconnect()
                            } catch (_: Exception) {
                                Log.d(TAG, "mDNS 解析出的设备已物理离线，丢弃系统幽灵缓存: $ip:$port")
                            }
                        }.start()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "发起 resolve 异常: ${e.message}")
        }
    }

    fun stopDiscovery() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.w(TAG, "停止 mDNS 异常: ${e.message}")
            }
            isDiscovering = false
        }
    }
}
