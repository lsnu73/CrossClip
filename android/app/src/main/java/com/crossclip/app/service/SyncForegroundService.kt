package com.crossclip.app.service

import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.crossclip.app.crypto.CryptoUtil
import com.crossclip.app.network.HttpUploader
import com.crossclip.app.network.LanDiscovery
import com.crossclip.app.network.LocalHttpServer
import com.crossclip.app.network.NsdHelper
import com.crossclip.app.network.SseClient
import com.crossclip.app.shizuku.ShizukuClipboardManager
import com.crossclip.app.shizuku.ShizukuPrivilegeHelper
import com.crossclip.app.ui.ClipWriteActivity
import com.crossclip.app.ui.MainActivity
import com.crossclip.app.util.DebugLogger
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SyncForegroundService : Service() {

    private val TAG = "CrossClipService"

    companion object {
        const val CHANNEL_ID = "cross_clip_silent_v2"
        const val NOTIFICATION_ID = 1001
        const val ACTION_MANUAL_SEND = "com.crossclip.app.ACTION_MANUAL_SEND"
        const val ACTION_WATCHDOG = "com.crossclip.app.ACTION_WATCHDOG"
        private const val WATCHDOG_INTERVAL_MS = 2 * 60 * 1000L

        @Volatile
        var instance: SyncForegroundService? = null
            private set
    }

    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentHashes = ArrayDeque<String>(50)

    private lateinit var lanDiscovery: LanDiscovery
    private lateinit var nsdHelper: NsdHelper
    private lateinit var sseClient: SseClient
    private var localHttpServer: LocalHttpServer? = null

    var currentPcIp: String = ""
        private set
    var currentHttpPort: Int = 18236
        private set
    var currentPcName: String = "未连接"
        private set
    var pinCode: String = ""
        private set

    // 连接状态: 0=未连接/搜索中, 1=已连接, 2=PIN码错误, -1=连接中
    @Volatile
    var connectionState: Int = 0
        private set

    private var deviceId: String = "android_phone"
    private var deviceName: String = "安卓手机"
    private var autoSync: Boolean = true
    @Volatile
    private var selfTestWriteInProgress = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var heartbeatExecutor: ScheduledExecutorService? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    private var heartbeatCount = 0L
    private var lastHeartbeatNotify = 0L
    private var lastSyncEvent = "等待剪贴板变化"

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        DebugLogger.log("CLIP_SYS", "原生 PrimaryClipChangedListener 触发")
        onLocalClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        DebugLogger.init(applicationContext)
        DebugLogger.log("SVC_LIFECYCLE", "SyncForegroundService.onCreate 服务创建")

        ShizukuClipboardManager.init(applicationContext)
        ShizukuPrivilegeHelper.applySystemWhitelists(applicationContext)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)

        // 注册 Shizuku 底层特权剪贴板变化监听
        ShizukuClipboardManager.registerListener {
            DebugLogger.log("CLIP_SHIZUKU", "Shizuku 底层特权监听回调触发")
            onLocalClipboardChanged()
        }
        // 轮询兜底：独立后台守护线程定期感知复制
        ShizukuClipboardManager.startPolling {
            onLocalClipboardChanged()
        }

        loadPreferences()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在自动搜索局域网电脑..."))
        registerScreenStateReceiver()
        startHeartbeat()

        // 启动本地微型 HTTP 服务端 (监听端口 18237)
        localHttpServer = LocalHttpServer(18237) { encrypted, hash, _ ->
            if (pinCode.isNotEmpty()) {
                try {
                    val decrypted = CryptoUtil.decrypt(encrypted, pinCode)
                    if (CryptoUtil.computeHash(decrypted) == hash) {
                        onNetworkTextReceived(decrypted)
                    } else {
                        DebugLogger.log("LOCAL_HTTP", "解密文本 Hash 不匹配: 计算=${CryptoUtil.computeHash(decrypted)} vs 接收=$hash")
                    }
                } catch (e: Exception) {
                    DebugLogger.log("LOCAL_HTTP", "解密对等推送剪贴板异常: ${e.message}", e)
                }
            }
        }
        localHttpServer?.start()

        initNetwork()
    }

    private fun maybeUpdateHeartbeatNotification() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatNotify < 15000) return
        lastHeartbeatNotify = now
        updateNotification("后台守护运行中 · 心跳 $heartbeatCount · $lastSyncEvent")
    }

    private fun startHeartbeat() {
        heartbeatExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "CrossClip-Heartbeat").apply { isDaemon = true }
        }
        heartbeatExecutor = executor
        executor.scheduleWithFixedDelay({
            heartbeatCount++
            DebugLogger.log("HEARTBEAT", "后台守护心跳计数: $heartbeatCount (已连接=$connectionState, PC=$currentPcIp)")
            if (currentPcIp.isNotEmpty() && pinCode.isNotEmpty()) {
                HttpUploader.sendHeartbeat(currentPcIp, currentHttpPort, pinCode, deviceId, deviceName, 18237) { ok ->
                    if (!ok && connectionState == 1) {
                        DebugLogger.log("HEARTBEAT", "心跳上报失败，电脑端可能已退出，切换为搜索中")
                        connectionState = 0
                        lanDiscovery.isConnected = false
                        mainHandler.post {
                            updateNotification("正在重新搜索局域网电脑...")
                        }
                    }
                }
            }
            mainHandler.post {
                maybeUpdateHeartbeatNotification()
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    private val workerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CrossClip-Worker").apply { isDaemon = true }
    }

    private fun registerScreenStateReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction("com.crossclip.app.WAKEUP")
                @Suppress("DEPRECATION")
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    workerExecutor.execute {
                        if (action == "com.crossclip.app.WAKEUP") {
                            DebugLogger.log("WAKEUP_PULSE", "收到 Shell (UID 2000) 守护心跳唤醒脉冲")
                            onLocalClipboardChanged()
                            if (currentPcIp.isNotEmpty() && pinCode.isNotEmpty()) {
                                HttpUploader.sendHeartbeat(currentPcIp, currentHttpPort, pinCode, deviceId, deviceName, 18237)
                            }
                            return@execute
                        }

                        if (action == Intent.ACTION_SCREEN_OFF) {
                            DebugLogger.log("SVC_SCREEN", "系统息屏，通知 Shizuku 暂停轮询")
                            ShizukuClipboardManager.pausePollingForScreenOff()
                            return@execute
                        }

                        DebugLogger.log("SVC_BROADCAST", "收到系统广播: $action")
                        if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                            ShizukuClipboardManager.resumePollingForScreenOn()
                            onLocalClipboardChanged()
                            if (currentPcIp.isNotEmpty() && pinCode.isNotEmpty()) {
                                HttpUploader.sendHeartbeat(currentPcIp, currentHttpPort, pinCode, deviceId, deviceName, 18237)
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            screenStateReceiver = receiver
            Log.i(TAG, "已动态注册屏幕与网络事件监听广播 (含 Shell 守护唤醒)")
            DebugLogger.log("SVC", "已动态注册屏幕与网络事件监听广播 (含 Shell 守护唤醒)")
        } catch (e: Exception) {
            Log.w(TAG, "注册屏幕广播失败: ${e.message}")
            DebugLogger.log("SVC", "注册屏幕广播失败: ${e.message}")
        }
    }

    /**
     * 在处理剪贴板互传的瞬态短暂持锁（最多 3 秒自动释放），防止数据收发途中被系统挂起，
     * 避免常驻持锁触发 MIUI/HyperOS 电量守护（PowerKeeper）的功耗查杀（SIGKILL）。
     */
    fun acquireTransientWakeLock(timeoutMs: Long = 3000L) {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CrossClip::TransientSync"
            )
            lock.setReferenceCounted(false)
            lock.acquire(timeoutMs)
            DebugLogger.log("SVC_LOCK", "获取瞬态 WakeLock (${timeoutMs}ms 超时自动释放)")
        } catch (e: Exception) {
            DebugLogger.log("SVC_LOCK", "获取瞬态 WakeLock 失败: ${e.message}")
        }
    }

    private fun loadPreferences() {
        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
        pinCode = sp.getString("pin_code", "") ?: ""
        deviceId = sp.getString("device_id", "android_" + Build.MODEL.replace(" ", "_")) ?: "android"
        deviceName = sp.getString("device_name", Build.MODEL) ?: "安卓手机"
        autoSync = sp.getBoolean("auto_sync", true)
        currentPcIp = sp.getString("last_pc_ip", "") ?: ""
        currentHttpPort = sp.getInt("last_http_port", 18236)
        DebugLogger.log("SVC_CONFIG", "加载配置: PC_IP=$currentPcIp, port=$currentHttpPort, pin.len=${pinCode.length}, autoSync=$autoSync")
    }

    private fun initNetwork() {
        // SSE 客户端：长连接监听电脑端复制下发的剪贴板内容
        sseClient = SseClient(
            onMessageReceived = { encrypted, hash, _ ->
                if (pinCode.isNotEmpty()) {
                    try {
                        val decrypted = CryptoUtil.decrypt(encrypted, pinCode)
                        if (CryptoUtil.computeHash(decrypted) == hash) {
                            onNetworkTextReceived(decrypted)
                        } else {
                            DebugLogger.log("SVC_NET", "解密文本 Hash 不匹配: 计算=${CryptoUtil.computeHash(decrypted)} vs 接收=$hash")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "解密电脑端下发剪贴板异常: ${e.message}")
                        DebugLogger.log("SVC_NET", "解密电脑端下发剪贴板异常: ${e.message}", e)
                    }
                }
            },
            onConnectionChanged = { connected ->
                DebugLogger.log("SVC_NET", "SSE 连接状态变更: connected=$connected")
                if (connected) {
                    connectionState = 1
                    lanDiscovery.isConnected = true
                } else {
                    if (connectionState == 1) {
                        connectionState = 0
                    }
                    lanDiscovery.isConnected = false
                }
                mainHandler.post {
                    val statusText = if (connected) "已连接电脑 (${currentPcName})" else "搜索电脑中..."
                    updateNotification(statusText)
                }
            }
        )

        // 1. 原生 mDNS 零配置自动发现
        nsdHelper = NsdHelper(
            this,
            onDeviceFound = { name, ip, port ->
                DebugLogger.log("DISCOVERY", "mDNS 发现设备: name=$name, ip=$ip, port=$port")
                onDeviceDiscovered(name, ip, port)
            },
            onDeviceLost = { name ->
                DebugLogger.log("DISCOVERY", "mDNS 设备丢失: name=$name")
                if (currentPcName == name) {
                    connectionState = 0
                }
            }
        )
        nsdHelper.startDiscovery()

        // 2. UDP 局域网广播 + 当前子网并发快速探测 (强力自发现)
        lanDiscovery = LanDiscovery(this) { ip, httpPort, _, name ->
            DebugLogger.log("DISCOVERY", "UDP/并发探测发现设备: name=$name, ip=$ip, port=$httpPort")
            onDeviceDiscovered(name, ip, httpPort)
        }
        lanDiscovery.startDiscovery(deviceId, deviceName)

        // 若已有保存的 IP 与 PIN 码，自动尝试连接
        if (currentPcIp.isNotEmpty() && pinCode.isNotEmpty()) {
            DebugLogger.log("SVC_NET", "检测到历史 PC 配置，自动发起连接")
            connectWithPin(currentPcIp, pinCode, currentHttpPort) { _, _, _ -> }
        }
    }

    private fun onDeviceDiscovered(name: String, ip: String, httpPort: Int) {
        if (connectionState == 1) return
        currentPcIp = ip
        currentHttpPort = httpPort
        currentPcName = name

        if (pinCode.isNotEmpty() && connectionState != 1 && connectionState != -1) {
            DebugLogger.log("DISCOVERY", "已有 PIN 码，自动验证连接与对等注册: $ip:$httpPort")
            HttpUploader.verifyPin(ip, httpPort, pinCode, deviceId, deviceName, 18237) { success, statusCode, devName ->
                mainHandler.post {
                    if (success) {
                        connectionState = 1
                        lanDiscovery.isConnected = true
                        currentPcName = devName ?: name
                        updateNotification("已连接电脑 ($currentPcName)")
                        val sseUrl = "http://$ip:$httpPort/events?pin=$pinCode"
                        sseClient.connect(sseUrl)
                    } else if (statusCode == 403) {
                        connectionState = 2
                        updateNotification("PIN 码不匹配，请核对电脑 PIN 码")
                    }
                }
            }
        }
    }

    fun disconnectCurrentPc() {
        DebugLogger.log("SVC_ACTION", "手动断开与电脑连接")
        sseClient.disconnect()
        pinCode = ""
        currentPcName = "未连接"
        connectionState = 0
        lanDiscovery.isConnected = false

        getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
            .edit()
            .remove("pin_code")
            .apply()

        updateNotification("已断开连接")
    }

    fun connectWithPin(
        ip: String,
        pin: String,
        httpPort: Int = currentHttpPort,
        callback: (success: Boolean, statusCode: Int, name: String?) -> Unit
    ) {
        currentPcIp = ip
        pinCode = pin
        currentHttpPort = httpPort
        connectionState = -1 // 验证中
        DebugLogger.log("SVC_ACTION", "发起 PIN 码配对连接与对等注册: $ip:$httpPort")

        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
        sp.edit()
            .putString("last_pc_ip", ip)
            .putString("pin_code", pin)
            .putInt("last_http_port", httpPort)
            .apply()

        HttpUploader.verifyPin(ip, httpPort, pin, deviceId, deviceName, 18237) { success, statusCode, devName ->
            mainHandler.post {
                if (success) {
                    connectionState = 1
                    lanDiscovery.isConnected = true
                    currentPcName = devName ?: "Windows 电脑"
                    updateNotification("已连接电脑 ($currentPcName)")
                    val sseUrl = "http://$ip:$httpPort/events?pin=$pin"
                    sseClient.connect(sseUrl)
                    callback(true, 200, currentPcName)
                } else if (statusCode == 403) {
                    connectionState = 2
                    updateNotification("PIN 码错误")
                    callback(false, 403, null)
                } else {
                    connectionState = 0
                    updateNotification("无法连接到电脑")
                    callback(false, statusCode, null)
                }
            }
        }
    }

    private fun onLocalClipboardChanged() {
        if (!autoSync || selfTestWriteInProgress) return
        try {
            var text: String? = null
            var readSource = "none"
            if (ShizukuClipboardManager.isReady()) {
                text = ShizukuClipboardManager.readClipboard()
                readSource = "Shizuku"
            }
            if (text.isNullOrEmpty()) {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    text = clip.getItemAt(0).text?.toString()
                    readSource = "SystemClip"
                }
            }
            if (!text.isNullOrEmpty()) {
                val hash = CryptoUtil.computeHash(text)
                synchronized(recentHashes) {
                    if (recentHashes.contains(hash)) {
                        return
                    }
                    recentHashes.add(hash)
                    if (recentHashes.size > 50) recentHashes.removeFirst()
                }
                val preview = if (text.length > 20) text.take(20) + "..." else text
                Log.i(TAG, "检测到本地复制 ($readSource)，正在静默自动同步至电脑...")
                lastSyncEvent = "手机复制: 长度 ${text.length}"
                DebugLogger.log("CLIP_DETECT", "检测到本地新剪贴板 [$readSource]: 长度=${text.length}, hash=$hash, 预览=[$preview]")
                doBroadcastText(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取剪贴板异常: ${e.message}")
            DebugLogger.log("CLIP_DETECT", "读取剪贴板异常: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun doBroadcastText(text: String, onComplete: ((Boolean) -> Unit)? = null) {
        acquireTransientWakeLock(3000L)
        if (currentPcIp.isNotEmpty() && pinCode.isNotEmpty()) {
            DebugLogger.log("SVC_SEND", "触发自动同步到 PC ($currentPcIp:$currentHttpPort)")
            HttpUploader.sendClipboard(currentPcIp, currentHttpPort, text, deviceId, pinCode) { success ->
                DebugLogger.log("SVC_SEND", "自动同步结果: $success")
                onComplete?.invoke(success)
            }
        } else {
            DebugLogger.log("SVC_SEND", "尚未连接电脑，跳过自动同步")
            onComplete?.invoke(false)
        }
    }

    private fun onNetworkTextReceived(text: String) {
        acquireTransientWakeLock(3000L)
        val hash = CryptoUtil.computeHash(text)
        synchronized(recentHashes) {
            recentHashes.add(hash)
            if (recentHashes.size > 50) recentHashes.removeFirst()
        }

        val preview = if (text.length > 20) text.take(20) + "..." else text
        DebugLogger.log("CLIP_RECV", "收到电脑端下发剪贴板: 长度=${text.length}, hash=$hash, 预览=[$preview]")

        mainHandler.post {
            // 1. 优先尝试 Shizuku 特权静默写入
            if (ShizukuClipboardManager.isReady()) {
                val written = ShizukuClipboardManager.writeClipboard(text)
                if (written) {
                    Log.i(TAG, "已通过 Shizuku 成功静默写入系统剪贴板")
                    lastSyncEvent = "电脑复制: 已写入"
                    DebugLogger.log("CLIP_WRITE", "已通过 Shizuku 成功写入系统剪贴板")
                    return@post
                }
                Log.w(TAG, "Shizuku 写入失败，切换到前台兜底写入")
                DebugLogger.log("CLIP_WRITE", "Shizuku 写入失败，切换到前台兜底")
            }

            // 2. 降级方案：常规直接写入
            try {
                val clipData = ClipData.newPlainText("CrossClip", text)
                clipboardManager.setPrimaryClip(clipData)
                DebugLogger.log("CLIP_WRITE", "普通 setPrimaryClip 写入完成")
            } catch (e: Exception) {
                DebugLogger.log("CLIP_WRITE", "普通 setPrimaryClip 异常: ${e.message}")
            }

            // 3. 降级方案：拉起透明瞬时 Activity 确保获得前台焦点写入系统剪贴板
            try {
                val intent = Intent(this, ClipWriteActivity::class.java).apply {
                    putExtra("EXTRA_TEXT", text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                startActivity(intent)
                DebugLogger.log("CLIP_WRITE", "拉起 ClipWriteActivity 兜底写入")
            } catch (e: Exception) {
                Log.w(TAG, "降级拉起 ClipWriteActivity 写入: ${e.message}")
                DebugLogger.log("CLIP_WRITE", "降级拉起 ClipWriteActivity 失败: ${e.message}")
            }
        }
    }

    fun sendTextManual(text: String, callback: ((Boolean) -> Unit)? = null) {
        if (text.isNotEmpty()) {
            val hash = CryptoUtil.computeHash(text)
            synchronized(recentHashes) {
                recentHashes.add(hash)
                if (recentHashes.size > 50) recentHashes.removeFirst()
            }
            if (currentPcIp.isEmpty() || pinCode.isEmpty()) {
                Toast.makeText(applicationContext, "尚未连接电脑，请输入电脑显示的 6 位 PIN 码", Toast.LENGTH_SHORT).show()
                callback?.invoke(false)
                return
            }

            DebugLogger.log("SVC_MANUAL", "手动发送剪贴板 (长度 ${text.length})")
            HttpUploader.sendClipboard(currentPcIp, currentHttpPort, text, deviceId, pinCode) { success ->
                mainHandler.post {
                    if (success) {
                        Toast.makeText(applicationContext, "已发送至电脑", Toast.LENGTH_SHORT).show()
                        callback?.invoke(true)
                    } else {
                        Toast.makeText(applicationContext, "发送失败，请确认电脑端已启动且 PIN 码正确", Toast.LENGTH_SHORT).show()
                        callback?.invoke(false)
                    }
                }
            }
        }
    }

    fun sendCurrentClipboardManual() {
        try {
            var text: String? = null
            if (ShizukuClipboardManager.isReady()) {
                text = ShizukuClipboardManager.readClipboard()
            }
            if (text.isNullOrEmpty()) {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    text = clip.getItemAt(0).text?.toString()
                }
            }
            val finalText = text
            if (!finalText.isNullOrEmpty()) {
                sendTextManual(finalText)
            } else {
                Toast.makeText(applicationContext, "当前剪贴板为空或无读取权限", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "读取剪贴板异常: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun setSelfTestWriteMode(enabled: Boolean) {
        selfTestWriteInProgress = enabled
        DebugLogger.log("SVC_MODE", "设置自检写入屏蔽状态: $enabled")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // 彻底删除历史版本残留的高优先级通知渠道
            try {
                nm?.deleteNotificationChannel("cross_clip_sync_channel")
                nm?.deleteNotificationChannel("cross_clip_channel")
            } catch (_: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "CrossClip 静默守护",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持后台剪贴板极速互传，可在系统设置中直接关闭"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm?.createNotificationChannel(channel)
        }
    }

    fun hideForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            DebugLogger.log("SVC_NOTIFY", "已隐藏通知中心常驻通知")
        } catch (e: Exception) {
            DebugLogger.log("SVC_NOTIFY", "隐藏常驻通知异常: ${e.message}")
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CrossClip 剪贴板互传")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "NORMAL_START"
        DebugLogger.log("SVC_LIFECYCLE", "SyncForegroundService.onStartCommand: action=$action, flags=$flags, startId=$startId")
        loadPreferences()
        if (intent?.action == ACTION_MANUAL_SEND) {
            sendCurrentClipboardManual()
        }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLogger.logTrimMemory(level)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DebugLogger.log("SVC_LIFECYCLE", "SyncForegroundService.onTaskRemoved (用户从最近任务划掉应用)")
        try {
            val restartIntent = Intent(applicationContext, SyncForegroundService::class.java)
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                3,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pendingIntent
            )
            Log.i(TAG, "任务被移除，已调度 1 秒后自动重启后台服务")
            DebugLogger.log("SVC_LIFECYCLE", "已通过 AlarmManager 调度 1 秒后自动拉起服务")
        } catch (e: Exception) {
            Log.w(TAG, "onTaskRemoved 重启调度失败: ${e.message}")
            DebugLogger.log("SVC_LIFECYCLE", "onTaskRemoved 重启调度失败: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.log("SVC_LIFECYCLE", "SyncForegroundService.onDestroy 服务销毁")
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
        wifiLock = null

        try {
            screenStateReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        screenStateReceiver = null

        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null

        clipboardManager.removePrimaryClipChangedListener(clipListener)
        ShizukuClipboardManager.unregisterListener()
        ShizukuClipboardManager.stopPolling()
        nsdHelper.stopDiscovery()
        lanDiscovery.stopDiscovery()
        sseClient.disconnect()
        localHttpServer?.stop()
        localHttpServer = null
        instance = null
    }
}
