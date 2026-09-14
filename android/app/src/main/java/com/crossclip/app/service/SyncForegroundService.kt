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
import com.crossclip.app.R
import com.crossclip.app.crypto.CryptoUtil
import com.crossclip.app.network.HttpUploader
import com.crossclip.app.network.LanDiscovery
import com.crossclip.app.network.LocalHttpServer
import com.crossclip.app.network.NsdHelper
import com.crossclip.app.network.FileReceiver
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

/**
 * CrossClip 前台守护服务 —— 手机端核心枢纽。
 *
 * 职责: 配对与连接状态机、SSE 出站长连接调度、剪贴板监听与转发、
 * 心跳节流上报、文件收发的通知与落盘、保活相关的广播接收。
 *
 * 接手开发前请先阅读 `docs/ai-handover.md`(AI 开发交接契约),
 * 重点关注 §3.1(出站长连接)、§3.7(心跳节流)、§6.5(资源释放约定)。
 */
class SyncForegroundService : Service() {

    private val TAG = "CrossClipService"

    data class DiscoveredDevice(
        val deviceId: String,
        val name: String,
        var ip: String,
        val httpPort: Int,
        var lastSeen: Long = System.currentTimeMillis()
    )

    companion object {
        /** 静默守护通知渠道（IMPORTANCE_MIN，可在系统设置中关闭展示） */
        const val CHANNEL_ID = "cross_clip_silent_v2"

        /** 文件传输通知渠道（IMPORTANCE_LOW，需要展示可见进度条，故与静默渠道分离） */
        const val CHANNEL_ID_FILE = "crossclip_file_transfer"

        const val NOTIFICATION_ID = 1001

        /** 文件传输通知 ID（与常驻通知错开，避免互相覆盖） */
        const val NOTIFICATION_ID_FILE = 2002

        /**
         * 「接收完成/失败」终态通知的专用 ID。
         *
         * 不复用 [NOTIFICATION_ID_FILE]（进行中卡片）是刻意的：进行中卡片在同一 ID 上
         * 高频更新过若干次，而个别 ROM 的通知优化会对「同 ID 的快速连续更新」做合并/限流，
         * 实测过最后的终态更新被吞掉、通知栏永久停在「正在接收文件 / 进度: 100%」。
         * 终态改用全新 ID 发布（同时显式 cancel 进行中卡片），彻底绕开同 ID 更新合并，
         * 让终态成为一条「新的通知」而不是「第 N 次更新」。
         */
        const val NOTIFICATION_ID_FILE_SETTLED = 2003

        /**
         * 所有通知共用的**小图标**。
         *
         * 这里要的是「图案」，不是「图标」：Android 会把通知小图标当成 alpha 蒙版来渲染 ——
         * 只取形状、丢弃颜色。而 `@mipmap/ic_launcher` 是自适应图标的**完整图**
         * （前景层 + 不透明背景层），整张都不透明，套上蒙版就是一坨**实心方块**；
         * `ic_launcher_foreground` 是透明底 + 图案的那一层，蒙版后才是应用图标本身的形状剪影。
         *
         * 所以换应用图标时要同步更新的是这张前景图，**不要**顺手改成 `@mipmap/ic_launcher`。
         * 分享通知（com.crossclip.app.receiver.ShareReceiveActivity）复用同一常量，
         * 保证通知栏里所有 CrossClip 通知看起来来自同一个 App。
         */
        val NOTIFICATION_SMALL_ICON = R.drawable.ic_launcher_foreground

        const val ACTION_MANUAL_SEND = "com.crossclip.app.ACTION_MANUAL_SEND"
        const val ACTION_WATCHDOG = "com.crossclip.app.ACTION_WATCHDOG"
        private const val WATCHDOG_INTERVAL_MS = 2 * 60 * 1000L

        /**
         * 心跳上报周期（秒）。
         * 电脑端要 10 分钟（rust_desktop/src/server.rs 中 peers 超时 600s）才判定手机离线，
         * 30 秒一次余量充足；手机端感知电脑掉线主要靠 SSE 断开（秒级）与局域网探测，
         * 因此把原先的 15 秒降到 30 秒，功耗减半且不影响断线感知速度。
         */
        private const val HEARTBEAT_INTERVAL_SEC = 30L

        /**
         * 心跳最小间隔（毫秒）。
         * 定时心跳线程与 Shell(UID 2000) 唤醒脉冲共用 [sendHeartbeatThrottled] 这一个入口，
         * 用它去重，避免同一时刻两路各发一次 HTTP 上报；同时保证进程被解冻后能及时补发
         * （只要距上次上报超过该窗口就立即发出）。
         */
        private const val HEARTBEAT_MIN_GAP_MS = 25_000L

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

    // 局域网在线设备字典 (以 deviceId 为主键)
    private val discoveredDevices = java.util.concurrent.ConcurrentHashMap<String, DiscoveredDevice>()
    // 连接事务令牌，避免旧 IP 超时回调覆盖新连接状态
    private val connectTokenCounter = java.util.concurrent.atomic.AtomicLong(0)

    var currentTargetDeviceId: String = ""
        private set
    var lastSavedPcIp: String = ""
        private set
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

    /** 是否开启「持续自动搜索电脑」。关闭后需用户手动点击「重新扫描」 */
    private var autoSearchEnabled: Boolean = true

    /**
     * 用户是否刚手动点过「断开连接」。
     *
     * 断开后扫描线程即使因时序原因多跑了一轮、或 mDNS 恰好回调了一次设备，
     * [onDeviceDiscovered] 也绝不能顺手把连接自动接回去 —— 用户刚刚明确表达过「别连」。
     * 用户主动发起连接（重新扫描 / 输 PIN / 选设备 / 开自动搜索）时清除该标志。
     */
    @Volatile
    private var manualDisconnected = false

    @Volatile
    private var selfTestWriteInProgress = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var heartbeatExecutor: ScheduledExecutorService? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    private var heartbeatCount = 0L
    private var lastHeartbeatNotify = 0L
    private var lastSyncEvent = "等待剪贴板变化"

    /** 最近一次真正发出心跳上报的时间戳：定时线程与唤醒脉冲共用，用于统一节流去重 */
    @Volatile
    private var lastHeartbeatSentAt = 0L

    /** 最近一次上报的接收进度百分比，用于通知节流（-1 表示尚未开始） */
    @Volatile
    private var lastFileReceiveProgress = -1

    /**
     * 当前文件传输是否已进入终态（完成 / 失败）。
     *
     * 分块进度通知是 `setOngoing(true)` 的「进行中」卡片，而完成通知同样要经 `mainHandler.post`
     * 异步投递，两者存在竞态：分块数多的大文件（实测 .apk / .rar 必现）会让最后那条
     * 「进度 100%」的通知落在完成通知之后并覆盖它，而 ongoing 通知不会自动消失，
     * 于是通知栏永久停在「正在接收文件 / 进度: 100%」。
     * 因此终态一旦确定就置位该标志，丢弃一切迟到的进度刷新。
     */
    @Volatile
    private var fileTransferSettled = false

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

        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
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
        // 通知栏初始文案同样走统一的状态文案来源，保证与「自动搜索电脑」开关状态一致
        startForeground(NOTIFICATION_ID, buildNotification(statusTextForNotification()))
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

        // 设置文件传输回调（接收电脑端推送的文件）
        localHttpServer?.setFileTransferCallback(object : LocalHttpServer.FileTransferCallback {

            /** 准备接收：先做去重判定，再决定弹「正在接收」还是直接判完成 */
            override fun onFilePrepareReceived(
                fileId: String,
                filename: String,
                fileSize: Long,
                mimeType: String,
                senderId: String,
                fileHash: String?
            ): Boolean {
                DebugLogger.log("FILE_RECEIVE", "收到电脑端文件准备: $filename ($fileSize bytes)")
                lastFileReceiveProgress = -1
                // 新一轮传输开始，撤销上一轮可能已置位的终态标记
                fileTransferSettled = false

                // 顺序很重要：必须**先**做去重判定，再决定是否弹「正在接收」。
                // 命中去重时发送端一个分块都不会发，紧接着就发 complete 收尾 ——
                // 这里若仍先弹「正在接收」，那条通知就会空转（不定进度条表现为 0→100 循环）。
                //
                // 刻意**不**在 prepare 阶段直接判完成：那条路径会提前把传输记录移除，
                // 使 HTTP 层随后查不到去重结果、回给发送端 already_exists=false，
                // 结果是发送端照常发来整份文件而接收端已经没了记录。
                val accepted = FileReceiver.prepareReceive(
                    applicationContext, fileId, filename, fileSize, fileHash
                )

                if (accepted && FileReceiver.isDedupHit(fileId)) {
                    DebugLogger.log("FILE_RECEIVE", "目标目录已有同一文件，等待发送端结束信号")
                    return accepted
                }

                // 通知直接在本线程发出，不经主线程转发（原因见 onFileChunkReceived 的注释）
                updateNotification("📥 正在接收文件: $filename")
                showFileReceiveNotification(filename, fileSize)
                return accepted
            }

            /** 本次接收是否已判定为「目标目录已有同一文件」（供 HTTP 层回告发送端） */
            override fun isDedupHit(fileId: String): Boolean = FileReceiver.isDedupHit(fileId)

            /**
             * 接收分块：payload 为二进制密文，此处解密后写入临时文件。
             *
             * ### 为什么通知必须在这个线程上**同步**发出
             * 早先的实现把每次通知都 `mainHandler.post{}` 转投到主线程。这让「进度通知」与
             * 「完成通知」的实际投递顺序脱离了**串行处理这条 HTTP 连接**的传输线程：
             * 一帧迟到的「进度 100%」可能落在完成通知之后，而它是 `setOngoing(true)` 的，
             * 于是通知栏永久停在「正在接收文件 / 进度: 100%」。
             * `NotificationManager.notify` 本身线程安全，绕道主线程纯属多余的间接层 ——
             * 去掉之后，通知的落地顺序严格等于请求的处理顺序。
             */
            override fun onFileChunkReceived(
                fileId: String, chunkIndex: Int, totalChunks: Int, payload: ByteArray
            ): Pair<Int, Int>? {
                return try {
                    val decrypted = CryptoUtil.decryptFromBytes(payload, pinCode)
                    val result = FileReceiver.receiveChunk(fileId, chunkIndex, totalChunks, decrypted)
                    if (result != null) {
                        val (received, total) = result
                        if (total > 0) {
                            val progress = (received * 100) / total
                            val willUpdate = progress >= lastFileReceiveProgress + 5 || progress == 100
                            val settled = fileTransferSettled
                            DebugLogger.log("DIAG_CHUNK", "chunk=$chunkIndex/$totalChunks progress=$progress lastProg=$lastFileReceiveProgress willUpdate=$willUpdate settled=$settled thread=${Thread.currentThread().name}")
                            if (willUpdate) {
                                lastFileReceiveProgress = progress
                                if (!settled) {
                                    DebugLogger.log("DIAG_CHUNK", "→ 发送进度通知 progress=$progress (ongoing=true) BEFORE notify")
                                    showFileReceiveProgressNotification(progress)
                                    DebugLogger.log("DIAG_CHUNK", "→ 进度通知已发送 progress=$progress (ongoing=true) AFTER notify")
                                    updateNotification("📥 接收文件中: $progress%")
                                } else {
                                    DebugLogger.log("DIAG_CHUNK", "→ 跳过进度通知（settled=true）")
                                }
                            }
                        }
                    }
                    result
                } catch (e: Exception) {
                    DebugLogger.log("FILE_RECEIVE", "解密文件块失败: ${e.message}", e)
                    null
                }
            }

            /** 接收完成：校验哈希并落盘到用户配置的目录 */
            override fun onFileCompleteReceived(fileId: String, fileHash: String): String? {
                DebugLogger.log("DIAG_COMPLETE", "→ onFileCompleteReceived 开始 thread=${Thread.currentThread().name} settled=$fileTransferSettled")
                // 先钉住终态：此后所有迟到的分块进度刷新都会被上面的判断丢弃
                fileTransferSettled = true
                val result = FileReceiver.completeReceive(applicationContext, fileId, fileHash)
                if (result != null) {
                    DebugLogger.log("FILE_RECEIVE", "文件接收完成: $result")
                    DebugLogger.log("DIAG_COMPLETE", "→ 即将调用 showFileReceiveCompleteNotification")
                    showFileReceiveCompleteNotification(result)
                    DebugLogger.log("DIAG_COMPLETE", "→ showFileReceiveCompleteNotification 已返回")
                    updateNotification("✅ 文件接收完成")
                } else {
                    DebugLogger.log("FILE_RECEIVE", "文件接收失败: 哈希不匹配或数据不完整")
                    showFileReceiveFailedNotification()
                    updateNotification("❌ 文件接收失败")
                }
                DebugLogger.log("DIAG_COMPLETE", "→ onFileCompleteReceived 结束")
                return result
            }
        })

        localHttpServer?.start()

        initNetwork()
    }

    /** 心跳通知：定期把「状态 + 心跳数 + 最近同步事件」写回通知栏（状态文案来自统一来源） */
    private fun maybeUpdateHeartbeatNotification() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatNotify < 15000) return
        lastHeartbeatNotify = now
        updateNotification("${statusTextForNotification()} · 心跳 $heartbeatCount · $lastSyncEvent")
    }

    /**
     * 统一的心跳上报入口（带最小间隔节流）。
     *
     * 定时心跳线程与 Shell(UID 2000) 唤醒脉冲都调用这里：
     *  - 正常情况：每 [HEARTBEAT_INTERVAL_SEC] 秒上报一次即可，多余的调用会被节流直接丢弃；
     *  - 进程被 ROM 冻结后：定时线程随之停摆，脉冲解冻进程时会立刻补发一次
     *    （此时距上次上报已超过节流窗口），电脑端不会把手机误判为离线。
     */
    private fun sendHeartbeatThrottled() {
        if (currentPcIp.isEmpty() || pinCode.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatSentAt < HEARTBEAT_MIN_GAP_MS) return
        lastHeartbeatSentAt = now
        HttpUploader.sendHeartbeat(currentPcIp, currentHttpPort, pinCode, deviceId, deviceName, 18237) { ok ->
            if (ok && connectionState == 1) {
                val ts = System.currentTimeMillis()
                val devId = if (currentTargetDeviceId.isNotEmpty()) currentTargetDeviceId else "pc_${currentPcIp.replace('.', '_')}"
                val dev = discoveredDevices[devId]
                if (dev != null) {
                    dev.lastSeen = ts
                } else {
                    discoveredDevices[devId] = DiscoveredDevice(devId, currentPcName, currentPcIp, currentHttpPort, ts)
                }
            } else if (!ok && connectionState == 1) {
                DebugLogger.log("HEARTBEAT", "心跳上报失败，电脑端已离线，重启 5/15 分钟搜索计时")
                connectionState = 0
                lanDiscovery.isConnected = false
                currentPcIp = ""
                currentPcName = "未连接"
                // 掉线统一处理：重置搜索时间窗（必要时重启扫描）并刷新通知栏文案
                mainHandler.post { onPcDisconnected() }
            }
        }
    }

    /**
     * 后台守护心跳线程。
     *
     * 周期为 [HEARTBEAT_INTERVAL_SEC] 秒（原为 15 秒）：它只承担「让电脑端知道手机在线」
     * 与「兜底发现电脑端假死」，而电脑端要 10 分钟才判定手机离线（见 rust_desktop
     * server.rs 的 peers 超时 600s），手机端感知电脑掉线又主要靠 SSE 断开（秒级），
     * 因此降到 30 秒既省电也不影响断线感知速度。
     */
    private fun startHeartbeat() {
        heartbeatExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "CrossClip-Heartbeat").apply { isDaemon = true }
        }
        heartbeatExecutor = executor
        executor.scheduleWithFixedDelay({
            heartbeatCount++
            DebugLogger.log("HEARTBEAT", "后台守护心跳计数: $heartbeatCount (已连接=$connectionState, PC=$currentPcIp)")
            // 统一入口自带节流：与唤醒脉冲 / 亮屏事件不会重复上报
            sendHeartbeatThrottled()
            mainHandler.post {
                maybeUpdateHeartbeatNotification()
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS)
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
                            // 心跳统一走节流入口：脉冲只负责「解冻进程 + 兜底读剪贴板」，
                            // 不必每次（10 秒）都发一次 HTTP 上报
                            sendHeartbeatThrottled()
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
                            // 亮屏同样走节流入口，连续亮灭屏时不会高频重复上报
                            sendHeartbeatThrottled()
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
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
            val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
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
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        deviceId = sp.getString("device_id", "android_" + Build.MODEL.replace(" ", "_")) ?: "android"
        deviceName = sp.getString("device_name", Build.MODEL) ?: "安卓手机"
        autoSync = sp.getBoolean("auto_sync", true)
        autoSearchEnabled = sp.getBoolean("auto_search_enabled", true)
        currentTargetDeviceId = sp.getString("last_device_id", "") ?: ""
        lastSavedPcIp = sp.getString("last_pc_ip", "") ?: ""
        currentHttpPort = sp.getInt("last_http_port", 18236)
        val devPin = if (currentTargetDeviceId.isNotEmpty()) {
            sp.getString("pin_code_$currentTargetDeviceId", "") ?: ""
        } else ""
        pinCode = if (devPin.isNotEmpty()) devPin else (sp.getString("pin_code", "") ?: "")
        currentPcIp = ""
        currentPcName = "未连接"
        DebugLogger.log("SVC_CONFIG", "加载配置: targetId=$currentTargetDeviceId, hintIp=$lastSavedPcIp, port=$currentHttpPort, pin.len=${pinCode.length}, autoSync=$autoSync")
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
                    mainHandler.post { updateNotification(statusTextForNotification()) }
                } else {
                    // 只有「已连接 → 断开」这一次跳变才算真正掉线：
                    // SSE 重连失败会反复回调 false，若每次都重置搜索时间窗，省电策略就失效了
                    val wasConnected = connectionState == 1
                    if (wasConnected) {
                        connectionState = 0
                    }
                    lanDiscovery.isConnected = false
                    if (wasConnected) {
                        // 掉线统一处理：重新计时 5/15 分钟并（必要时）重启扫描
                        onPcDisconnected()
                    } else {
                        mainHandler.post { updateNotification(statusTextForNotification()) }
                    }
                }
            },
            onFileEvent = { eventType, data ->
                DebugLogger.log("SVC_NET", "SSE 收到文件事件: $eventType")
                when (eventType) {
                    "FILE_PROGRESS" -> {
                        val fileId = data.optString("file_id", "")
                        val received = data.optInt("received_chunks", 0)
                        val total = data.optInt("total_chunks", 0)
                        if (total > 0) {
                            val progress = (received * 100) / total
                            mainHandler.post {
                                updateNotification("📤 发送文件中: $progress% ($received/$total 块)")
                            }
                        }
                    }
                    "FILE_RECEIVED" -> {
                        val fileId = data.optString("file_id", "")
                        val path = data.optString("path", "")
                        DebugLogger.log("SVC_NET", "文件已保存到电脑: $path")
                        mainHandler.post {
                            updateNotification("✅ 文件已发送到电脑")
                        }
                    }
                }
            }
        )

        // 1. 原生 mDNS 零配置自动发现
        nsdHelper = NsdHelper(
            this,
            onDeviceFound = { devId, name, ip, port ->
                DebugLogger.log("DISCOVERY", "mDNS 发现设备: id=$devId, name=$name, ip=$ip, port=$port")
                onDeviceDiscovered(devId, name, ip, port)
            },
            onDeviceLost = { name ->
                DebugLogger.log("DISCOVERY", "mDNS 设备丢失: name=$name")
                if (currentPcName == name && connectionState == 1) {
                    connectionState = 0
                    lanDiscovery.isConnected = false
                    // 与 SSE / 心跳掉线保持一致：重置 5/15 分钟搜索时间窗并刷新通知栏文案
                    onPcDisconnected()
                }
            }
        )
        nsdHelper.startDiscovery()

        // 2. UDP 局域网广播 + 当前子网并发快速探测 (强力自发现)
        lanDiscovery = LanDiscovery(this) { devId, name, ip, httpPort, _ ->
            DebugLogger.log("DISCOVERY", "UDP/并发探测发现设备: id=$devId, name=$name, ip=$ip, port=$httpPort")
            onDeviceDiscovered(devId, name, ip, httpPort)
        }
        // 应用持久化的自动搜索开关：关闭时只做有限轮次扫描，不进入持续搜索
        // （此时尚未 startDiscovery，socket 未创建，该调用是安全的初始化写入）
        lanDiscovery.setAutoSearchEnabled(autoSearchEnabled)
        lanDiscovery.startDiscovery(deviceId, deviceName, lastSavedPcIp, manualScan = !autoSearchEnabled)
    }

    /** 查询自动搜索开关状态（供 UI 显示） */
    fun isAutoSearchEnabled(): Boolean = autoSearchEnabled

    /** 局域网扫描线程是否仍在运行（开关开启时也可能因 15 分钟超时自动停止） */
    fun isLanSearching(): Boolean = try {
        lanDiscovery.isSearching
    } catch (_: Throwable) {
        false
    }

    /**
     * 通知栏状态文案的**唯一来源**。
     *
     * 通知栏与 App 页面必须表达同一件事：此前通知栏在电脑掉线后固定显示「搜索电脑中...」，
     * 而「自动搜索关闭」或「搜索已超时停止」时页面已改口，导致两处提示互相矛盾。
     * 这里按「已连接 / 配对中 / 自动搜索已关闭 / 搜索已暂停 / 搜索中」分级生成文案。
     */
    private fun statusTextForNotification(): String {
        // lanDiscovery 在 initNetwork() 中才初始化，启动早期的 startForeground 需要兜底
        val searching = if (this::lanDiscovery.isInitialized) lanDiscovery.isSearching else true
        return when {
            connectionState == 1 -> "✅ 已连接电脑 ($currentPcName)"
            connectionState == -1 -> "⏳ 正在配对连接电脑..."
            !autoSearchEnabled -> "⏸ 自动搜索已关闭，点开应用「重新扫描」手动查找"
            !searching -> "⏸ 搜索已暂停，点开应用「重新扫描」继续查找"
            else -> "🔍 搜索电脑中..."
        }
    }

    /**
     * 电脑端掉线后的统一处理。
     *
     * 1. 自动搜索开启时，重置「5 分钟降频 / 15 分钟停止」时间窗，让省电策略从**掉线时刻**
     *    重新计时（否则连接期间流逝的时间会让断线瞬间就被判定超时、扫描线程立即停止）；
     * 2. 若扫描线程已停止（例如上一轮已超时退出），重新拉起局域网自动搜索；
     * 3. 刷新通知栏文案，使其与自动搜索开关状态、页面文案保持一致。
     */
    private fun onPcDisconnected() {
        if (autoSearchEnabled) {
            lanDiscovery.restartAutoSearchWindow()
            if (!lanDiscovery.isSearching) {
                DebugLogger.log("DISCOVERY", "电脑端掉线且扫描线程已停止，重新拉起局域网自动搜索")
                // 先彻底释放旧 socket / MulticastLock，避免旧 socket 仍占用 UDP 端口
                lanDiscovery.stopDiscovery()
                lanDiscovery.startDiscovery(deviceId, deviceName, lastSavedPcIp, manualScan = false)
            }
        }
        mainHandler.post { updateNotification(statusTextForNotification()) }
    }

    /**
     * 设置自动搜索开关。
     *
     * - 开启：重置搜索时间窗并立即开始搜索；
     * - 关闭：立即中断当前搜索循环，之后仅能通过「重新扫描」手动查找。
     * 状态会持久化到 SharedPreferences，服务重启后依然生效。
     */
    fun setAutoSearchEnabled(enabled: Boolean) {
        autoSearchEnabled = enabled
        // 重新开启自动搜索等同于用户主动要求「继续找电脑」，解除手动断开的抑制
        manualDisconnected = false
        getSharedPreferences("cross_clip_config", MODE_PRIVATE)
            .edit()
            .putBoolean("auto_search_enabled", enabled)
            .apply()
        DebugLogger.log("SVC_ACTION", "自动搜索开关变更为: ${if (enabled) "开启" else "关闭"}")

        if (enabled) {
            // 重新开启：重置时间窗并立即搜索
            triggerRescan()
        } else {
            lanDiscovery.setAutoSearchEnabled(false)
        }
        // 开关切换后统一刷新通知栏，确保「通知栏 = 开关状态 = 页面文案」三者一致
        mainHandler.post { updateNotification(statusTextForNotification()) }
    }

    fun getDiscoveredDeviceList(): List<DiscoveredDevice> {
        val now = System.currentTimeMillis()
        val it = discoveredDevices.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            // 若为当前正常连接中的电脑，保证其在线状态不被超时剔除
            if (connectionState == 1 && (entry.value.ip == currentPcIp || entry.key == currentTargetDeviceId)) {
                entry.value.lastSeen = now
                continue
            }
            // 其余设备 15 秒内无探活回应则判定离线，严格剔除
            if (now - entry.value.lastSeen > 15000L) {
                it.remove()
            }
        }
        return ArrayList(discoveredDevices.values)
    }

    /**
     * 当前目标电脑是否真实在线并被局域网探测到
     */
    fun isCurrentDeviceOnline(): Boolean {
        if (connectionState == 1) return true
        val target = discoveredDevices[currentTargetDeviceId]
        if (target != null && System.currentTimeMillis() - target.lastSeen < 15000L) {
            return true
        }
        return discoveredDevices.values.any { it.ip == currentPcIp && System.currentTimeMillis() - it.lastSeen < 15000L }
    }

    /**
     * 用户主动点击重新扫描时，强制切断旧连接并清空全部探测缓存
     */
    fun triggerRescan() {
        DebugLogger.log("DISCOVERY", "用户主动触发局域网重新扫描，切断旧连接并清空全部缓存")
        // 手动断开后的「重新扫描」是用户主动发起的，解除抑制恢复自动连接
        manualDisconnected = false
        connectTokenCounter.incrementAndGet()
        sseClient.disconnect()
        connectionState = 0
        lanDiscovery.isConnected = false
        currentPcIp = ""
        currentPcName = "未连接"
        discoveredDevices.clear()
        try {
            nsdHelper.stopDiscovery()
            lanDiscovery.stopDiscovery()
        } catch (_: Exception) {}
        nsdHelper.startDiscovery()
        // 手动触发的扫描：即使自动搜索开关处于关闭状态，也要执行有限轮次的搜索
        lanDiscovery.startDiscovery(deviceId, deviceName, lastSavedPcIp, manualScan = !autoSearchEnabled)
    }

    /**
     * 清除本地持久化的历史电脑与 PIN 码记录
     */
    fun clearSavedHistory() {
        DebugLogger.log("SVC_ACTION", "清除保存的历史配置与当前连接")
        connectTokenCounter.incrementAndGet()
        sseClient.disconnect()
        connectionState = 0
        lanDiscovery.isConnected = false
        currentTargetDeviceId = ""
        currentPcIp = ""
        lastSavedPcIp = ""
        currentPcName = "未连接"
        pinCode = ""
        discoveredDevices.clear()

        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        sp.edit()
            .remove("last_pc_ip")
            .remove("last_device_id")
            .remove("pin_code")
            .apply()
        triggerRescan()
        // 通知栏文案统一走状态来源：清空历史并重新扫描后即为「搜索电脑中...」
        updateNotification(statusTextForNotification())
    }

    private fun onDeviceDiscovered(devId: String, name: String, ip: String, httpPort: Int) {
        if (manualDisconnected) {
            // 用户刚手动断开：忽略任何扫描结果，不自动重连（要连由用户点「重新扫描」发起）
            DebugLogger.log("DISCOVERY", "已手动断开连接，忽略扫描结果: $name ($ip)")
            return
        }
        val now = System.currentTimeMillis()
        
        // 物理 IP 强力归并去重：检索是否存在相同物理 IP 或相同 ID 的已有记录
        var foundKey: String? = null
        for ((k, v) in discoveredDevices) {
            if (v.ip == ip || k == devId) {
                foundKey = k
                break
            }
        }
        val finalDevId: String
        if (foundKey != null) {
            val oldItem = discoveredDevices.remove(foundKey)
            // 优先保留非虚拟 IP 的硬件指纹
            finalDevId = if (!devId.startsWith("pc_")) devId else (oldItem?.deviceId ?: devId)
            val finalName = if (name.isNotEmpty() && name != "Windows 电脑") name else (oldItem?.name ?: name)
            discoveredDevices[finalDevId] = DiscoveredDevice(finalDevId, finalName, ip, httpPort, now)
        } else {
            finalDevId = devId
            discoveredDevices[devId] = DiscoveredDevice(devId, name, ip, httpPort, now)
        }

        // 1. 若当前已成功连接，检测是否发生动态 IP 漂移
        if (connectionState == 1) {
            if (finalDevId == currentTargetDeviceId && ip != currentPcIp) {
                DebugLogger.log("DISCOVERY", "已连接电脑 IP 动态漂移: $currentPcIp -> $ip，触发静默热重连")
                val token = connectTokenCounter.incrementAndGet()
                HttpUploader.verifyPin(ip, httpPort, pinCode, deviceId, deviceName, 18237) { success, statusCode, devName, _ ->
                    if (token != connectTokenCounter.get()) return@verifyPin
                    mainHandler.post {
                        if (token != connectTokenCounter.get()) return@post
                        if (success) {
                            currentPcIp = ip
                            currentHttpPort = httpPort
                            currentPcName = devName ?: name
                            val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
                            sp.edit().putString("last_pc_ip", ip).apply()
                            val sseUrl = "http://$ip:$httpPort/events?pin=$pinCode"
                            sseClient.connect(sseUrl)
                            // 通知栏文案统一走状态来源
                            updateNotification(statusTextForNotification())
                        }
                    }
                }
            }
            return
        }

        // 2. 若未连接，检查是否为记忆中的目标电脑，若是则自动触发后台静默握手
        var shouldTriggerConnect = false
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        val devPin = sp.getString("pin_code_$finalDevId", "") ?: ""
        val candidatePin = if (devPin.isNotEmpty()) devPin else pinCode

        if ((currentTargetDeviceId.isEmpty() || currentTargetDeviceId == finalDevId) && candidatePin.isNotEmpty()) {
            if (connectionState == 0 || (connectionState == -1 && ip != currentPcIp)) {
                shouldTriggerConnect = true
            }
        }

        if (shouldTriggerConnect) {
            val token = connectTokenCounter.incrementAndGet()
            connectionState = -1 // 标记正在后台验证，绝不提前污染 currentPcIp
            DebugLogger.log("DISCOVERY", "目标电脑在线 ($name, $ip, token=$token)，自动执行挑战握手")
            HttpUploader.verifyPin(ip, httpPort, candidatePin, deviceId, deviceName, 18237) { success, statusCode, devName, retDevId ->
                if (token != connectTokenCounter.get()) {
                    DebugLogger.log("SVC_NET", "丢弃过期握手回调 (token: $token)")
                    return@verifyPin
                }
                mainHandler.post {
                    if (token != connectTokenCounter.get()) return@post
                    if (success) {
                        // 握手真正通过！原子转正为已连接，更新通信变量
                        connectionState = 1
                        lanDiscovery.isConnected = true
                        val boundId = retDevId ?: finalDevId
                        currentTargetDeviceId = boundId
                        currentPcIp = ip
                        currentHttpPort = httpPort
                        currentPcName = devName ?: name
                        pinCode = candidatePin

                        val editor = sp.edit()
                            .putString("last_device_id", boundId)
                            .putString("last_pc_ip", ip)
                            .putInt("last_http_port", httpPort)
                            .putString("pin_code_$boundId", candidatePin)
                        editor.apply()

                        // 通知栏文案统一走状态来源
                        updateNotification(statusTextForNotification())
                        val sseUrl = "http://$ip:$httpPort/events?pin=$candidatePin"
                        sseClient.connect(sseUrl)
                    } else if (statusCode == 403) {
                        connectionState = 2
                        updateNotification("PIN 码不匹配，请核对电脑 PIN 码")
                    } else {
                        connectionState = 0
                    }
                }
            }
        }
    }

    fun selectTargetDevice(device: DiscoveredDevice) {
        if (currentTargetDeviceId == device.deviceId && currentPcIp == device.ip && connectionState == 1) return
        DebugLogger.log("SVC_ACTION", "切换目标电脑: ${device.name} (${device.ip})")
        // 用户主动选择设备，解除手动断开的抑制
        manualDisconnected = false

        // 优雅切断旧连接通道，废弃旧事务
        connectTokenCounter.incrementAndGet()
        sseClient.disconnect()
        connectionState = 0
        lanDiscovery.isConnected = false

        currentTargetDeviceId = device.deviceId
        currentPcIp = device.ip
        currentHttpPort = device.httpPort
        currentPcName = device.name

        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        val devPin = sp.getString("pin_code_${device.deviceId}", "") ?: ""
        if (devPin.isNotEmpty()) {
            pinCode = devPin
        }

        sp.edit()
            .putString("last_device_id", device.deviceId)
            .putString("last_pc_ip", device.ip)
            .putInt("last_http_port", device.httpPort)
            .apply()

        // 切换目标后通知栏文案统一切换成当前真实状态（未连接时即为「搜索电脑中...」）
        updateNotification(statusTextForNotification())
    }

    fun disconnectCurrentPc() {
        DebugLogger.log("SVC_ACTION", "手动断开与电脑连接：停止设备搜索，等待用户手动「重新扫描」")
        // 1. 先落「已断开」状态，再关连接。
        //    顺序不能反：sseClient.disconnect() 会同步回调 onConnectionChanged(false)，
        //    那里用 connectionState == 1 判断是否属于「已连接 → 断开」跳变；此刻若仍是 1，
        //    就会走 onPcDisconnected() 把扫描线程重新拉起来，用户刚点的断开立刻失效。
        connectionState = 0
        // 抑制自动重连：扫描线程可能在 stopDiscovery() 生效前又跑完一轮
        manualDisconnected = true
        // 作废在途握手，避免旧回调把状态改回「已连接」
        connectTokenCounter.incrementAndGet()
        lanDiscovery.isConnected = false

        // 2. 主动告知电脑端，让它立刻摘掉「已连接手机」，不必等 600 秒心跳超时。
        //    必须在清空 currentPcIp / pinCode 之前发起。
        HttpUploader.notifyPcDisconnected(currentPcIp, currentHttpPort, pinCode, deviceId)

        // 3. 关闭出站长连接（此后心跳因 currentPcIp 已清空而不再上报，不会再注册回来）
        currentPcIp = ""
        currentPcName = "未连接"
        sseClient.disconnect()

        // 4. 停止设备搜索：UDP 广播 / 子网探测 / mDNS 全部停下。
        //    与「自动搜索 15 分钟超时」是同一个终态 —— 通知栏改口为「搜索已暂停」，
        //    用户想重新连就点应用里的「重新扫描」。
        try {
            nsdHelper.stopDiscovery()
        } catch (e: Exception) {
            DebugLogger.log("SVC_ACTION", "手动断开时停止 mDNS 失败: ${e.message}")
        }
        try {
            lanDiscovery.stopDiscovery()
        } catch (e: Exception) {
            DebugLogger.log("SVC_ACTION", "手动断开时停止局域网扫描失败: ${e.message}")
        }

        mainHandler.post { updateNotification(statusTextForNotification()) }
    }

    fun connectWithPin(
        ip: String,
        pin: String,
        httpPort: Int = currentHttpPort,
        callback: (success: Boolean, statusCode: Int, name: String?) -> Unit
    ) {
        // 用户手动发起配对，解除「断开后不再自动重连」的抑制
        manualDisconnected = false
        val token = connectTokenCounter.incrementAndGet()
        currentPcIp = ip
        pinCode = pin
        currentHttpPort = httpPort
        connectionState = -1 // 验证中
        DebugLogger.log("SVC_ACTION", "发起 PIN 码配对连接 (token=$token): $ip:$httpPort")

        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        val editor = sp.edit()
            .putString("last_pc_ip", ip)
            .putString("pin_code", pin)
            .putInt("last_http_port", httpPort)
        if (currentTargetDeviceId.isNotEmpty()) {
            editor.putString("pin_code_$currentTargetDeviceId", pin)
        }
        editor.apply()

        HttpUploader.verifyPin(ip, httpPort, pin, deviceId, deviceName, 18237) { success, statusCode, devName, retDevId ->
            if (token != connectTokenCounter.get()) {
                DebugLogger.log("SVC_NET", "丢弃过期的 connectWithPin 回调 (token: $token)")
                return@verifyPin
            }
            mainHandler.post {
                if (token != connectTokenCounter.get()) return@post
                if (success) {
                    connectionState = 1
                    lanDiscovery.isConnected = true
                    currentPcName = devName ?: "Windows 电脑"
                    if (!retDevId.isNullOrEmpty()) {
                        currentTargetDeviceId = retDevId
                        val prefs = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
                        prefs.edit()
                            .putString("last_device_id", retDevId)
                            .putString("pin_code_$retDevId", pin)
                            .apply()
                    }
                    // 通知栏文案统一走状态来源
                    updateNotification(statusTextForNotification())
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

    /**
     * 获取当前设备 ID（供文件传输等外部模块使用）
     */
    fun getLocalDeviceId(): String = deviceId

    /**
     * 显示文件「正在接收」通知（不定进度，带文件名与大小）
     */
    private fun showFileReceiveNotification(filename: String, fileSize: Long) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val sizeStr = formatFileSize(fileSize)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID_FILE)
                .setContentTitle("📥 正在接收文件")
                .setContentText("$filename ($sizeStr)")
                .setSmallIcon(NOTIFICATION_SMALL_ICON)
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID_FILE, notification)
        } catch (e: Exception) {
            DebugLogger.log("SVC_NOTIFY", "显示文件接收通知失败: ${e.message}")
        }
    }

    /**
     * 更新文件「接收进度」通知（确定性进度条）
     */
    private fun showFileReceiveProgressNotification(progress: Int) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, CHANNEL_ID_FILE)
                .setContentTitle("📥 正在接收文件")
                .setContentText("进度: $progress%")
                .setSmallIcon(NOTIFICATION_SMALL_ICON)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            DebugLogger.log("DIAG_NOTIFY", "nm.notify(PROGRESS) ID=$NOTIFICATION_ID_FILE ongoing=true progress=$progress% thread=${Thread.currentThread().name}")
            nm.notify(NOTIFICATION_ID_FILE, notification)
            DebugLogger.log("DIAG_NOTIFY", "nm.notify(PROGRESS) 已返回")
        } catch (e: Exception) {
            DebugLogger.log("SVC_NOTIFY", "更新文件接收进度通知失败: ${e.message}")
        }
    }

    /**
     * 显示文件接收完成通知。
     *
     * 点击通知会打开「接收文件保存目录」（经 `OpenSaveDirActivity` 中转）：
     * 用户刚收到文件，最自然的下一步就是去看它落在哪，直接给出入口比只展示一段路径更好用。
     * @param savedPath 实际落盘路径（默认目录为绝对路径，自定义目录为「目录名/文件名」）
     */
    private fun showFileReceiveCompleteNotification(savedPath: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 「点击通知打开保存目录」单独 try：它一旦抛异常（个别 ROM 会对指向显式组件的
        // PendingIntent 做额外校验），绝不能连带把这条「完成通知」一起吞掉 ——
        // 那种情况下用户看到的就是永远停在最后一条进度通知，即「正在接收文件 / 进度: 100%」。
        val openDirPendingIntent = try {
            val openDirIntent = Intent(this, com.crossclip.app.ui.OpenSaveDirActivity::class.java)
            PendingIntent.getActivity(
                this,
                2101,
                openDirIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (e: Exception) {
            DebugLogger.log("SVC_NOTIFY", "创建「打开保存目录」PendingIntent 失败，降级为不可点击: ${e.message}", e)
            null
        }

        try {
            // 终态走全新通知 ID（见 NOTIFICATION_ID_FILE_SETTLED 的说明），并显式收掉
            // 进行中卡片 —— 双保险：即使某 ROM 吞掉同 ID 的终态更新，新 ID 也一定是新通知
            nm.cancel(NOTIFICATION_ID_FILE)
            val builder = NotificationCompat.Builder(this, CHANNEL_ID_FILE)
                .setContentTitle("✅ 文件接收完成")
                .setContentText(
                    if (openDirPendingIntent != null) {
                        "已保存到: $savedPath（点击打开所在文件夹）"
                    } else {
                        "已保存到: $savedPath"
                    }
                )
                .setStyle(NotificationCompat.BigTextStyle().bigText("已保存到: $savedPath"))
                .setSmallIcon(NOTIFICATION_SMALL_ICON)
                // 显式收掉上一帧残留的进度条：不写这一句时是否残留取决于各 ROM 的替换实现
                .setProgress(0, 0, false)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (openDirPendingIntent != null) {
                builder.setContentIntent(openDirPendingIntent)
            }

            DebugLogger.log("DIAG_NOTIFY", "nm.notify(COMPLETE) ID=$NOTIFICATION_ID_FILE_SETTLED ongoing=false thread=${Thread.currentThread().name}")
            nm.notify(NOTIFICATION_ID_FILE_SETTLED, builder.build())
            DebugLogger.log("DIAG_NOTIFY", "nm.notify(COMPLETE) 已返回")
            DebugLogger.log("SVC_NOTIFY", "已发出「文件接收完成」通知: $savedPath")
        } catch (e: Exception) {
            DebugLogger.log("SVC_NOTIFY", "显示文件接收完成通知失败: ${e.message}", e)
        }
    }

    /**
     * 显示文件接收失败通知（哈希校验失败 / 落盘失败 / 数据不完整）
     */
    private fun showFileReceiveFailedNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // 与完成通知同理：终态走全新 ID，并收掉进行中卡片
            nm.cancel(NOTIFICATION_ID_FILE)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID_FILE)
                .setContentTitle("❌ 文件接收失败")
                .setContentText("传输中断或校验未通过，请重新发送")
                .setSmallIcon(NOTIFICATION_SMALL_ICON)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID_FILE_SETTLED, notification)
        } catch (e: Exception) {
            DebugLogger.log("SVC_NOTIFY", "显示文件接收失败通知失败: ${e.message}")
        }
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
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

            // 文件传输渠道：用 IMPORTANCE_LOW（而非 MIN）才能让用户在通知栏看到进度条
            val fileChannel = NotificationChannel(
                CHANNEL_ID_FILE,
                "CrossClip 文件传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示文件收发的实时进度与结果"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm?.createNotificationChannel(fileChannel)
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
            .setSmallIcon(NOTIFICATION_SMALL_ICON)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
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
