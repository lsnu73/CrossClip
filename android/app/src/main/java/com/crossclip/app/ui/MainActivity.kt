package com.crossclip.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.crossclip.app.R
import com.crossclip.app.BuildConfig
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.shizuku.ShizukuClipboardManager
import com.crossclip.app.util.DebugLogger
import com.crossclip.app.util.PermissionHelper
import com.crossclip.app.util.SaveDirManager
import com.crossclip.app.util.SyncStats
import com.google.android.material.bottomsheet.BottomSheetDialog
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale

/**
 * 屏 1 · 设置（hub，栈底）。
 *
 * 按《CrossClip-最终规范与AI提示词.md》复原：白底直铺的连接总览 hero + 单行设置列表 +
 * ⓘ 折叠说明 + 底部居中下划线诊断入口。连接/设备状态每 1.5 秒自前台服务轮询刷新。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvConnTag: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var codeRow: View
    private lateinit var tvPairingCode: TextView
    private lateinit var btnDisconnect: TextView
    private lateinit var btnConnectHero: TextView
    private lateinit var tvStats: TextView

    private lateinit var rowAutoSync: View
    private lateinit var switchAutoSync: androidx.appcompat.widget.SwitchCompat
    private lateinit var tvAutoSyncDesc: TextView
    private lateinit var rowAutoSearch: View
    private lateinit var switchAutoSearch: androidx.appcompat.widget.SwitchCompat
    private lateinit var tvAutoSearchDesc: TextView
    private lateinit var rowManualSend: View
    private lateinit var rowHideRecents: View
    private lateinit var switchHideRecents: androidx.appcompat.widget.SwitchCompat
    private lateinit var rowLockTask: View
    private lateinit var rowNotification: View
    private lateinit var tvNotificationValue: TextView
    private lateinit var tvSaveDirPath: TextView
    private lateinit var btnChangeDir: TextView
    private lateinit var tvResetSaveDir: TextView
    private lateinit var tvCacheSize: TextView
    private lateinit var btnClearCache: TextView
    private lateinit var rowShizuku: View
    private lateinit var tvShizukuValue: TextView
    private lateinit var rowBattery: View
    private lateinit var rowAutostart: View
    private lateinit var tvDiagEntry: TextView
    private lateinit var tvFooter: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyedActivity = false
    private var isInitializingUi = false

    /** ⓘ 图标 → 说明条 的对应关系；互斥展开由 [toggleExplain] 保证 */
    private val explainMap = linkedMapOf(
        R.id.info_auto to R.id.ex_auto,
        R.id.info_search to R.id.ex_search,
        R.id.info_hide to R.id.ex_hide,
        R.id.info_lock to R.id.ex_lock,
        R.id.info_notice to R.id.ex_notice,
        R.id.info_dir to R.id.ex_dir,
        R.id.info_cache to R.id.ex_cache,
        R.id.info_shizuku to R.id.ex_shizuku,
        R.id.info_battery to R.id.ex_battery,
        R.id.info_autostart to R.id.ex_autostart
    )

    /**
     * 目录选择器：用于让用户自定义「接收文件保存目录」。
     * 选中的目录会以持久化 content:// URI 授权保存，进程重启后依然可写入。
     */
    private val pickSaveDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SaveDirManager.setCustomDir(this, uri)
            updateSaveDirUI()
            Toast.makeText(this, "保存目录已更新", Toast.LENGTH_SHORT).show()
        }
    }

    /** 通知权限申请器（Android 13+ 需要运行时授权才能显示文件传输进度） */
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateNotificationRow()
        Toast.makeText(
            this,
            if (granted) "通知权限已开启，可正常显示文件传输进度" else "未授予通知权限，文件传输进度将无法在通知栏展示",
            Toast.LENGTH_LONG
        ).show()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread {
            updateShizukuRow()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuClipboardManager.onPermissionGranted()
                DebugLogger.ok("UI", "Shizuku 特权授权成功")
                Toast.makeText(this, "Shizuku 特权授权成功！已开启系统级静默后台互传", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}

        ShizukuClipboardManager.init(applicationContext)
        DebugLogger.init(applicationContext)
        DebugLogger.log("APP_LIFECYCLE", "MainActivity.onCreate 版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        initViews()
        loadConfig()
        applyHideFromRecentsPreference()
        startSyncService()
        startStatusPolling()
        promptMiuiKeepAliveIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyHideFromRecentsPreference()
        updateSaveDirUI()
        updateNotificationRow()
        refreshCacheSize()
    }

    private fun applyHideFromRecentsPreference() {
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        val hideRecents = sp.getBoolean("hide_recents", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.appTasks?.forEach { task ->
                try {
                    task.setExcludeFromRecents(hideRecents)
                } catch (_: Exception) {}
            }
        }
    }

    // ==================== 视图绑定与基础接线 ====================

    private fun initViews() {
        tvConnTag = findViewById(R.id.tv_conn_tag)
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        codeRow = findViewById(R.id.code_row)
        tvPairingCode = findViewById(R.id.tv_pairing_code)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        btnConnectHero = findViewById(R.id.btn_connect_hero)
        tvStats = findViewById(R.id.tv_stats)

        rowAutoSync = findViewById(R.id.row_auto_sync)
        switchAutoSync = findViewById(R.id.switch_auto_sync)
        tvAutoSyncDesc = findViewById(R.id.tv_auto_sync_desc)
        rowAutoSearch = findViewById(R.id.row_auto_search)
        switchAutoSearch = findViewById(R.id.switch_auto_search)
        tvAutoSearchDesc = findViewById(R.id.tv_auto_search_desc)
        rowManualSend = findViewById(R.id.row_manual_send)
        rowHideRecents = findViewById(R.id.row_hide_recents)
        switchHideRecents = findViewById(R.id.switch_hide_recents)
        rowLockTask = findViewById(R.id.row_lock_task)
        rowNotification = findViewById(R.id.row_notification)
        tvNotificationValue = findViewById(R.id.tv_notification_value)
        tvSaveDirPath = findViewById(R.id.tv_save_dir_path)
        btnChangeDir = findViewById(R.id.btn_change_dir)
        tvResetSaveDir = findViewById(R.id.tv_reset_save_dir)
        tvCacheSize = findViewById(R.id.tv_cache_size)
        btnClearCache = findViewById(R.id.btn_clear_cache)
        rowShizuku = findViewById(R.id.row_shizuku)
        tvShizukuValue = findViewById(R.id.tv_shizuku_value)
        rowBattery = findViewById(R.id.row_battery)
        rowAutostart = findViewById(R.id.row_autostart)
        tvDiagEntry = findViewById(R.id.tv_diag_entry)
        tvFooter = findViewById(R.id.tv_footer)

        tvFooter.text = "CrossClip v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"

        // 诊断入口：居中下划线 → 二级页
        tvDiagEntry.paintFlags = tvDiagEntry.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvDiagEntry.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        // ⓘ 功能说明：互斥展开，不占额外行高
        explainMap.forEach { (infoId, exId) ->
            findViewById<View>(infoId).setOnClickListener { toggleExplain(infoId, exId) }
        }

        // ---------- 连接总览 ----------
        btnDisconnect.setOnClickListener {
            val service = SyncForegroundService.instance ?: return@setOnClickListener
            val devName = service.currentPcName.ifEmpty { "电脑" }
            AlertDialog.Builder(this)
                .setTitle("断开与 $devName 的连接？")
                .setMessage("断开后需重新输入配对码，正在传输的内容会被中断。")
                .setPositiveButton("断开") { _, _ ->
                    service.disconnectCurrentPc()
                    DebugLogger.warn("UI", "用户在设置页确认断开连接")
                    Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
                    refreshHero()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        btnConnectHero.setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }

        // ---------- 同步 ----------
        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            if (isInitializingUi) return@setOnCheckedChangeListener
            val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
            sp.edit().putBoolean("auto_sync", isChecked).apply()
            rowManualSend.visibility = if (isChecked) View.GONE else View.VISIBLE
            DebugLogger.log("SVC_ACTION", "自动互传模式变更为: ${if (isChecked) "开启" else "关闭"}")
            startSyncService()
        }
        rowAutoSync.setOnClickListener {
            // 未连接锁定（规范 §3.2）：点击拦截并提示
            if (SyncForegroundService.instance?.connectionState != 1) {
                Toast.makeText(this, "未连接设备，该开关暂不可用", Toast.LENGTH_SHORT).show()
            } else {
                switchAutoSync.toggle()
            }
        }
        rowManualSend.setOnClickListener {
            SyncForegroundService.instance?.sendCurrentClipboardManual()
                ?: Toast.makeText(this, "后台服务未就绪，正在拉起...", Toast.LENGTH_SHORT).show()
        }

        switchAutoSearch.setOnCheckedChangeListener { _, isChecked ->
            if (isInitializingUi) return@setOnCheckedChangeListener
            SyncForegroundService.instance?.setAutoSearchEnabled(isChecked)
            refreshAutoSearchDesc(isChecked)
            DebugLogger.log("SVC_ACTION", "自动搜索电脑变更为: ${if (isChecked) "开启" else "关闭"}")
            Toast.makeText(
                this,
                if (isChecked) "已开启自动搜索电脑" else "已关闭自动搜索，可到「电脑配对」手动查找",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ---------- 隐私与显示 ----------
        switchHideRecents.setOnCheckedChangeListener { _, isChecked ->
            if (isInitializingUi) return@setOnCheckedChangeListener
            val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
            sp.edit().putBoolean("hide_recents", isChecked).apply()
            applyHideFromRecentsPreference()
            Toast.makeText(
                this,
                if (isChecked) "已开启最近任务隐藏，退至桌面后在多任务中隐身，彻底免疫一键清理" else "最近任务隐藏已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }

        rowLockTask.setOnClickListener { showLockTaskGuideDialog() }

        rowNotification.setOnClickListener {
            if (isNotificationEnabled()) {
                Toast.makeText(this, "通知权限已开启，文件传输进度可正常展示", Toast.LENGTH_SHORT).show()
            } else {
                requestNotificationPermission()
            }
        }

        // ---------- 存储 ----------
        btnChangeDir.setOnClickListener {
            try {
                pickSaveDirLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开目录选择器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        tvSaveDirPath.setOnClickListener {
            // 单击：系统「打开方式」直接打开（未设默认时弹 仅此一次/总是）
            openSaveDir(forceChooser = false)
        }
        tvSaveDirPath.setOnLongClickListener {
            // 长按：强制重弹系统「打开方式」重新选择
            openSaveDir(forceChooser = true)
            true
        }
        tvResetSaveDir.setOnClickListener {
            SaveDirManager.resetToDefault(this)
            updateSaveDirUI()
            Toast.makeText(this, "已恢复默认保存目录", Toast.LENGTH_SHORT).show()
        }

        btnClearCache.setOnClickListener { confirmClearCache() }

        // ---------- 系统权限 ----------
        rowShizuku.setOnClickListener { onShizukuRowClicked() }
        rowBattery.setOnClickListener {
            PermissionHelper.openBatteryKeepAliveSettings(this)
        }
        rowAutostart.setOnClickListener {
            PermissionHelper.openAutoStartPermissionSetting(this)
        }

        findViewById<TextView>(R.id.btn_about).setOnClickListener { showAboutSheet() }
    }

    private fun loadConfig() {
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        isInitializingUi = true
        switchAutoSync.isChecked = sp.getBoolean("auto_sync", true)
        switchHideRecents.isChecked = sp.getBoolean("hide_recents", false)
        switchAutoSearch.isChecked = sp.getBoolean("auto_search_enabled", true)
        isInitializingUi = false
        rowManualSend.visibility = if (sp.getBoolean("auto_sync", true)) View.GONE else View.VISIBLE
        refreshAutoSearchDesc(sp.getBoolean("auto_search_enabled", true))

        updateSaveDirUI()
        updateNotificationRow()
    }

    // ==================== ⓘ 功能说明 ====================

    /** 互斥展开：先收起全部，再展开当前项；点击已展开项 = 收起 */
    private fun toggleExplain(infoId: Int, exId: Int) {
        val target = findViewById<View>(exId)
        val opening = target.visibility != View.VISIBLE
        explainMap.values.forEach { id -> findViewById<View>(id).visibility = View.GONE }
        explainMap.keys.forEach { id ->
            findViewById<TextView>(id).apply {
                setBackgroundResource(R.drawable.bg_info_circle)
                setTextColor(getColor(R.color.info_circle_text))
            }
        }
        if (opening) {
            target.visibility = View.VISIBLE
            findViewById<TextView>(infoId).apply {
                setBackgroundResource(R.drawable.bg_info_circle_on)
                setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    // ==================== 连接总览 hero ====================

    private fun isServiceConnected(): Boolean =
        SyncForegroundService.instance?.connectionState == 1

    private fun startStatusPolling() {
        handler.post(object : Runnable {
            override fun run() {
                if (isDestroyedActivity) return
                refreshHero()
                handler.postDelayed(this, 1500)
            }
        })
    }

    /** hero 区随服务状态刷新（状态轮询与断开确认后共用） */
    private fun refreshHero() {
        val service = SyncForegroundService.instance
        val connected = service?.connectionState == 1

        if (connected && service != null) {
            tvConnTag.text = "● 已连接"
            tvConnTag.setTextColor(getColor(R.color.accent))
            tvConnTag.setBackgroundResource(R.drawable.bg_tag_connected)
            tvDeviceName.text = service.currentPcName.ifEmpty { "Windows 电脑" }
            tvDeviceIp.text = "${service.currentPcIp} · 局域网"
            codeRow.visibility = View.VISIBLE
            tvPairingCode.text = formatPin(service.pinCode)
            btnConnectHero.visibility = View.GONE
        } else {
            tvConnTag.text = "○ 未连接"
            tvConnTag.setTextColor(getColor(R.color.text_2))
            tvConnTag.setBackgroundResource(R.drawable.bg_tag_idle)
            tvDeviceName.text = "未连接设备"
            tvDeviceIp.text = when {
                service == null -> "后台服务启动中…"
                service.connectionState == -1 -> "正在配对连接电脑…"
                else -> disconnectedHint(service)
            }
            codeRow.visibility = View.GONE
            btnConnectHero.visibility = View.VISIBLE
        }

        // 统计行：今日已同步 N 条 ｜ 上次 N 前
        val snap = SyncStats.snapshot(this)
        tvStats.text = if (snap.lastAt > 0) {
            "今日已同步 %,d 条 ｜ 上次 %s".format(
                Locale.getDefault(), snap.todayCount, SyncStats.lastSyncDisplay(snap.lastAt)
            )
        } else {
            "今日已同步 %,d 条".format(Locale.getDefault(), snap.todayCount)
        }

        // 未连接锁定：自动互传行置灰、开关禁用、副说明改口
        rowAutoSync.alpha = if (connected) 1f else 0.45f
        switchAutoSync.isEnabled = connected
        tvAutoSyncDesc.text = if (connected) "复制内容后秒级静默同步至电脑" else "需先连接设备后启用"

        updateShizukuRow()
    }

    private fun disconnectedHint(service: SyncForegroundService): String {
        val devCount = service.getDiscoveredDeviceList().size
        return when {
            devCount > 0 -> "局域网内发现 $devCount 台可用设备"
            !service.isAutoSearchEnabled() -> "自动搜索已关闭，可手动添加地址"
            !service.isLanSearching() -> "搜索已暂停，进入「电脑配对」重新扫描"
            else -> "正在搜索局域网内的电脑…"
        }
    }

    /** 配对码展示：6 位按 3+3 分组，缺位时占位 */
    private fun formatPin(pin: String): String = when {
        pin.length == 6 -> "${pin.substring(0, 3)} ${pin.substring(3)}"
        pin.isNotEmpty() -> pin
        else -> "— — — —"
    }

    // ==================== 各行状态刷新 ====================

    private fun refreshAutoSearchDesc(enabled: Boolean) {
        tvAutoSearchDesc.text = if (enabled) {
            "5 分钟后降频、15 分钟后停止，避免夜间空转耗电"
        } else {
            "已关闭自动搜索，到「电脑配对」手动查找电脑"
        }
    }

    private fun isNotificationEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun updateNotificationRow() {
        tvNotificationValue.text = if (isNotificationEnabled()) "已开启" else "未开启"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
                openAppNotificationSettings()
            }
        } else {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "请在系统设置中开启 CrossClip 的通知权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSaveDirUI() {
        if (!this::tvSaveDirPath.isInitialized) return
        tvSaveDirPath.text = SaveDirManager.getDisplayPath(this)
        tvResetSaveDir.visibility = if (SaveDirManager.hasCustomDir(this)) View.VISIBLE else View.GONE
    }

    // ==================== 本地缓存 ====================

    /** cacheDir 体积（文件传输中转分块与分享中转都在 cacheDir 下） */
    private fun refreshCacheSize() {
        Thread {
            val bytes = dirSize(cacheDir)
            runOnUiThread {
                if (!isDestroyedActivity) {
                    tvCacheSize.text = if (bytes < 1024) "0 MB" else "${bytes / (1024 * 1024)} MB"
                }
            }
        }.start()
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return try {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    private fun confirmClearCache() {
        val bytes = tvCacheSize.text.toString().removeSuffix(" MB").toLongOrNull() ?: 0L
        if (bytes <= 0) {
            Toast.makeText(this, "缓存已是清洁状态", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清除本地缓存？")
            .setDescription(bytes)
            .setPositiveButton("清除") { _, _ ->
                Thread {
                    clearCacheDir(cacheDir)
                    runOnUiThread {
                        refreshCacheSize()
                        Toast.makeText(this, "已清除本地缓存", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun AlertDialog.Builder.setDescription(bytes: Long): AlertDialog.Builder {
        val mb = bytes / (1024.0 * 1024.0)
        val shown = if (mb >= 1) String.format(Locale.getDefault(), "%.1f MB", mb) else "${bytes / 1024} KB"
        setMessage("将删除 $shown 临时文件（含传输中断的残留分块与分享中转副本），不会删除已保存的接收文件。")
        return this
    }

    private fun clearCacheDir(dir: File) {
        try {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
            DebugLogger.log("UI", "本地缓存已清除: ${dir.absolutePath}")
        } catch (e: Exception) {
            DebugLogger.warn("UI", "清除本地缓存失败: ${e.message}")
        }
    }

    // ==================== Shizuku ====================

    private fun updateShizukuRow() {
        if (!this::tvShizukuValue.isInitialized) return
        when (ShizukuClipboardManager.getState()) {
            ShizukuClipboardManager.State.READY -> {
                tvShizukuValue.text = "已激活"
                tvShizukuValue.setTextColor(getColor(R.color.accent))
            }
            ShizukuClipboardManager.State.UNAUTHORIZED -> {
                tvShizukuValue.text = "待授权"
                tvShizukuValue.setTextColor(getColor(R.color.log_warn))
            }
            ShizukuClipboardManager.State.NOT_RUNNING -> {
                tvShizukuValue.text = "未运行"
                tvShizukuValue.setTextColor(getColor(R.color.text_3))
            }
        }
    }

    private fun onShizukuRowClicked() {
        when (ShizukuClipboardManager.getState()) {
            ShizukuClipboardManager.State.READY -> {
                // 已激活：跑一次读写链路自检，结果以 toast 呈现
                Toast.makeText(this, "正在自检 Shizuku 读写链路...", Toast.LENGTH_SHORT).show()
                Thread {
                    SyncForegroundService.instance?.setSelfTestWriteMode(true)
                    val result = try {
                        ShizukuClipboardManager.debugFullSelfTest()
                    } finally {
                        SyncForegroundService.instance?.setSelfTestWriteMode(false)
                    }
                    runOnUiThread {
                        Toast.makeText(this, result.split("\n").firstOrNull() ?: result, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            ShizukuClipboardManager.State.UNAUTHORIZED -> {
                ShizukuClipboardManager.requestPermission()
            }
            ShizukuClipboardManager.State.NOT_RUNNING -> {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this, "未检测到 Shizuku 应用，请先安装并启动 Shizuku", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "未检测到 Shizuku，当前使用标准兼容模式", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==================== 路径点击：用其他应用打开 ====================

    /**
     * 点击/长按保存目录路径（对齐 LocalSend 的方案，详见 [SaveDirManager.openDir]）：
     * 自定义目录发隐式 VIEW Intent，交系统「打开方式」选应用（仅此一次/总是），
     * 长按 forceChooser=true 强制重弹系统选择框；默认目录只有系统「文件」能打开，直接调起。
     */
    private fun openSaveDir(forceChooser: Boolean) {
        if (!SaveDirManager.openDir(this, forceChooser)) {
            Toast.makeText(this, "没有可打开该目录的应用，请手动前往该目录", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 指引 / 关于 ====================

    private fun showLockTaskGuideDialog() {
        val guideMessage = StringBuilder()
            .append("在系统多任务切换界面（近期任务）为 CrossClip 应用卡片加锁，可彻底防止系统清理后台或一键清理全部任务时误杀同步进程：\n\n")
            .append("1. 小米、澎湃 OS (HyperOS / MIUI)：\n")
            .append("   从屏幕底部上滑悬停进入多任务，长按 CrossClip 卡片，点击弹出菜单中的锁头图标；部分版本亦可直接向下拉动卡片加锁。\n\n")
            .append("2. OPPO、一加、真我 (ColorOS / RealmeUI)：\n")
            .append("   进入多任务后台，点击 CrossClip 卡片右上角设置菜单（三点图标），点击【锁定】。\n\n")
            .append("3. vivo、iQOO (OriginOS)：\n")
            .append("   进入多任务后台，向下拉动 CrossClip 卡片，卡片出现小锁图标即锁定成功。\n\n")
            .append("4. 华为、荣耀 (HarmonyOS / MagicOS)：\n")
            .append("   进入多任务后台，向下拉动 CrossClip 卡片，卡片出现锁头标记即锁定完成。\n\n")
            .append("提示：若开启了【最近任务隐藏】，多任务栏将不会展示本应用卡片。建议保持多任务可见并给卡片加锁，兼顾后台驻留与防误杀。")
            .toString()

        AlertDialog.Builder(this)
            .setTitle("多任务卡片加锁操作指引")
            .setMessage(guideMessage)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showAboutSheet() {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.dialog_about)
        dialog.findViewById<TextView>(R.id.about_version)?.apply {
            text = "版本 v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
            setOnClickListener { Toast.makeText(context, "已是最新版本 v${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show() }
        }
        dialog.findViewById<TextView>(R.id.about_license)?.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CrossClip License", "MIT License"))
            Toast.makeText(this, "MIT 许可信息已复制", Toast.LENGTH_SHORT).show()
        }
        dialog.findViewById<TextView>(R.id.about_privacy)?.setOnClickListener {
            Toast.makeText(this, "纯局域网点对点同步，数据不出局域网；日志仅保存在本机", Toast.LENGTH_LONG).show()
        }
        dialog.show()
    }

    // ==================== 服务与生命周期 ====================

    private fun startSyncService() {
        val intent = Intent(this, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun promptMiuiKeepAliveIfNeeded() {
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        if (sp.getBoolean("miui_keepalive_prompted", false)) return
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        if (!manufacturer.contains("xiaomi") && !manufacturer.contains("redmi")) return

        sp.edit().putBoolean("miui_keepalive_prompted", true).apply()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle("开启后台无限制")
            .setMessage(
                "MIUI/HyperOS 默认会冻结后台应用，导致剪贴板自动互传几秒后失效。\n" +
                    "请前往设置，把 CrossClip 的自启动打开、省电策略设为无限制，" +
                    "并在权限管理中允许后台运行和后台弹出界面。"
            )
            .setPositiveButton("前往设置") { _, _ ->
                PermissionHelper.openBatteryKeepAliveSettings(this)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        DebugLogger.log("UI", "用户触发返回键，将 Activity 移至后台 (moveTaskToBack)")
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
        isDestroyedActivity = true
        handler.removeCallbacksAndMessages(null)
        DebugLogger.log("APP_LIFECYCLE", "MainActivity.onDestroy")
    }
}
