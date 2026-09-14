package com.crossclip.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import com.crossclip.app.R
import com.crossclip.app.BuildConfig
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.shizuku.ShizukuClipboardManager
import com.crossclip.app.util.PermissionHelper
import com.crossclip.app.util.DebugLogger
import com.crossclip.app.util.SaveDirManager
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAppTitle: TextView
    private lateinit var tvVersionBadge: TextView
    private lateinit var tvDiscoveredDevice: TextView
    private lateinit var btnSwitchDevice: TextView
    private lateinit var llDeviceHeader: View
    /** 「重新扫描」按钮容器（含旋转箭头与文案）；点击区域覆盖整个按钮，而不只是文字 */
    private lateinit var btnRefreshScan: View
    private lateinit var etPinCode: EditText
    private lateinit var btnConnectPc: Button
    private lateinit var tvToggleManualIp: TextView
    private lateinit var llManualIpContainer: LinearLayout
    private lateinit var etManualIp: EditText
    private lateinit var btnApplyManualIp: Button

    private lateinit var switchAutoSync: SwitchCompat
    private lateinit var btnLockTaskGuide: Button
    private lateinit var switchHideRecents: SwitchCompat

    // 自动搜索开关（省电）
    private lateinit var switchAutoSearch: SwitchCompat
    private lateinit var tvAutoSearchDesc: TextView

    // 通知权限检测
    private lateinit var tvNotificationBadge: TextView
    private lateinit var btnNotificationPerm: Button

    // 接收文件保存目录
    private lateinit var tvSaveDirPath: TextView
    private lateinit var btnChangeSaveDir: Button
    private lateinit var tvResetSaveDir: TextView

    private lateinit var tvShizukuBadge: TextView
    private lateinit var tvShizukuDesc: TextView
    private lateinit var tvShizukuDebug: TextView
    private lateinit var btnShizukuAuth: Button
    private lateinit var cardManualSync: View
    private lateinit var btnTestSend: Button
    private lateinit var btnClipboardPerm: Button
    private lateinit var btnBatteryPerm: Button
    private lateinit var btnAutoStartPerm: Button
    private lateinit var llShizukuDebugContainer: LinearLayout
    private lateinit var tvCollapseDebug: TextView
    private val collapseDebugRunnable = Runnable {
        llShizukuDebugContainer.visibility = View.GONE
    }

    private lateinit var tvLogFilePath: TextView
    private lateinit var btnViewLogs: Button
    private lateinit var btnCopyLogs: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnClearLogs: Button

    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyedActivity = false

    /** 初始化阶段回填控件状态时置为 true，避免误触发开关的切换副作用 */
    private var isInitializingUi = false

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
        updateNotificationBadge()
        Toast.makeText(
            this,
            if (granted) "通知权限已开启，可正常显示文件传输进度" else "未授予通知权限，文件传输进度将无法在通知栏展示",
            Toast.LENGTH_LONG
        ).show()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread {
            updateShizukuUI()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuClipboardManager.onPermissionGranted()
                DebugLogger.log("UI", "Shizuku 特权授权成功")
                Toast.makeText(this, "Shizuku 特权授权成功！已开启系统级静默后台互传", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 不主动弹窗强行索取通知权限，若用户未授予通知权限，系统将天然不在通知中心展示常驻通知，前台服务仍可正常保活运行
        // 用户若需要通知可在系统设置中随时开启

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
        runShizukuSelfTest()
        promptMiuiKeepAliveIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyHideFromRecentsPreference()
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

    private fun initViews() {
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvVersionBadge = findViewById(R.id.tv_version_badge)
        tvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"
        tvStatus = findViewById(R.id.tv_status)
        tvDiscoveredDevice = findViewById(R.id.tv_discovered_device)
        btnSwitchDevice = findViewById(R.id.btn_switch_device)
        llDeviceHeader = findViewById(R.id.ll_device_header)
        btnRefreshScan = findViewById(R.id.btn_refresh_scan)
        etPinCode = findViewById(R.id.et_pin_code)
        btnConnectPc = findViewById(R.id.btn_connect_pc)
        tvToggleManualIp = findViewById(R.id.tv_toggle_manual_ip)
        llManualIpContainer = findViewById(R.id.ll_manual_ip_container)
        etManualIp = findViewById(R.id.et_manual_ip)
        btnApplyManualIp = findViewById(R.id.btn_apply_manual_ip)

        val onDeviceSelectClick = View.OnClickListener {
            showDeviceSelectionDialog()
        }
        btnSwitchDevice.setOnClickListener(onDeviceSelectClick)
        llDeviceHeader.setOnClickListener(onDeviceSelectClick)

        switchAutoSync = findViewById(R.id.switch_auto_sync)
        btnLockTaskGuide = findViewById(R.id.btn_lock_task_guide)
        switchHideRecents = findViewById(R.id.switch_hide_recents)

        // 自动搜索 / 通知权限 / 保存目录 相关控件
        switchAutoSearch = findViewById(R.id.switch_auto_search)
        tvAutoSearchDesc = findViewById(R.id.tv_auto_search_desc)
        tvNotificationBadge = findViewById(R.id.tv_notification_badge)
        btnNotificationPerm = findViewById(R.id.btn_notification_perm)
        tvSaveDirPath = findViewById(R.id.tv_save_dir_path)
        btnChangeSaveDir = findViewById(R.id.btn_change_save_dir)
        tvResetSaveDir = findViewById(R.id.tv_reset_save_dir)

        tvShizukuBadge = findViewById(R.id.tv_shizuku_badge)
        tvShizukuDesc = findViewById(R.id.tv_shizuku_desc)
        tvShizukuDebug = findViewById(R.id.tv_shizuku_debug)
        btnShizukuAuth = findViewById(R.id.btn_shizuku_auth)
        cardManualSync = findViewById(R.id.card_manual_sync)
        btnTestSend = findViewById(R.id.btn_test_send)
        btnClipboardPerm = findViewById(R.id.btn_clipboard_perm)
        btnBatteryPerm = findViewById(R.id.btn_battery_perm)
        btnAutoStartPerm = findViewById(R.id.btn_autostart_perm)
        llShizukuDebugContainer = findViewById(R.id.ll_shizuku_debug_container)
        tvCollapseDebug = findViewById(R.id.tv_collapse_debug)

        tvLogFilePath = findViewById(R.id.tv_log_file_path)
        btnViewLogs = findViewById(R.id.btn_view_logs)
        btnCopyLogs = findViewById(R.id.btn_copy_logs)
        btnExportLogs = findViewById(R.id.btn_export_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)

        tvLogFilePath.text = "日志路径（点击用其他应用打开所在目录）: ${DebugLogger.getLogFilePath()}"
        // 点击路径 → 用其他应用打开日志所在目录（已删除「复制日志路径」逻辑）
        tvLogFilePath.setOnClickListener {
            openLogDirWithOtherApps()
        }

        btnViewLogs.setOnClickListener {
            showLogsDialog()
        }

        btnCopyLogs.setOnClickListener {
            val allLogs = DebugLogger.readAll()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CrossClip Logs", allLogs))
            Toast.makeText(this, "已将全量运行日志复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        btnExportLogs.setOnClickListener {
            DebugLogger.shareLogFile(this)
        }

        btnClearLogs.setOnClickListener {
            DebugLogger.clear()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        btnShizukuAuth.setOnClickListener {
            when (ShizukuClipboardManager.getState()) {
                ShizukuClipboardManager.State.READY -> {
                    handler.removeCallbacks(collapseDebugRunnable)
                    llShizukuDebugContainer.visibility = View.VISIBLE
                    tvShizukuDebug.text = "正在自检 Shizuku 读写链路..."
                    Thread {
                        SyncForegroundService.instance?.setSelfTestWriteMode(true)
                        val result = try {
                            ShizukuClipboardManager.debugFullSelfTest()
                        } finally {
                            SyncForegroundService.instance?.setSelfTestWriteMode(false)
                        }
                        runOnUiThread {
                            tvShizukuDebug.text = result
                            // 展示 8 秒后自动收起
                            handler.postDelayed(collapseDebugRunnable, 8000L)
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

        btnRefreshScan.setOnClickListener {
            // 点击反馈改为「箭头旋转 + 文案切换为正在扫描中」(见 ScanRefreshController)；
            // 原先在此弹出的底部 Toast 与旋转变达的是同一件事，重复提示既遮挡视线又转瞬即逝，已移除。
            ScanRefreshController.onScanClicked(this)
            val service = SyncForegroundService.instance
            if (service != null) {
                service.triggerRescan()
            } else {
                startSyncService()
            }
        }

        tvToggleManualIp.setOnClickListener {
            if (llManualIpContainer.visibility == View.VISIBLE) {
                llManualIpContainer.visibility = View.GONE
            } else {
                llManualIpContainer.visibility = View.VISIBLE
            }
        }

        btnApplyManualIp.setOnClickListener {
            val ip = etManualIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入电脑的有效 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val service = SyncForegroundService.instance
            if (service != null) {
                val pin = etPinCode.text.toString().trim()
                service.connectWithPin(ip, pin) { success, statusCode, name ->
                    if (success) {
                        Toast.makeText(this, "成功连接到电脑: $name", Toast.LENGTH_SHORT).show()
                    } else if (statusCode == 403) {
                        Toast.makeText(this, "PIN 码不匹配，请查看电脑托盘显示的 6 位数", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "连接超时，请确认 IP 是否正确且已启动 CrossClip", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            cardManualSync.visibility = if (isChecked) View.GONE else View.VISIBLE
            val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
            sp.edit().putBoolean("auto_sync", isChecked).apply()
            startSyncService()
        }

        btnLockTaskGuide.setOnClickListener {
            showLockTaskGuideDialog()
        }

        switchHideRecents.setOnCheckedChangeListener { _, isChecked ->
            val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
            sp.edit().putBoolean("hide_recents", isChecked).apply()
            applyHideFromRecentsPreference()
            if (isChecked) {
                Toast.makeText(this, "已开启最近任务隐藏，退至桌面后在多任务中隐身，彻底免疫一键清理", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "最近任务隐藏已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // ---------- 自动搜索开关（省电策略） ----------
        switchAutoSearch.setOnCheckedChangeListener { _, isChecked ->
            // 初始化回填状态时不应触发真实切换（否则会重启搜索并弹出 Toast）
            if (isInitializingUi) return@setOnCheckedChangeListener
            SyncForegroundService.instance?.setAutoSearchEnabled(isChecked)
            refreshAutoSearchUI(isChecked)
            // 立即刷新主页状态文案，避免开关已关闭但文案仍显示「搜索中」造成困惑
            refreshStatusFromService()
            Toast.makeText(
                this,
                if (isChecked) "已开启自动搜索电脑" else "已关闭自动搜索，可点击「重新扫描」手动查找",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ---------- 通知权限检测 ----------
        btnNotificationPerm.setOnClickListener {
            if (isNotificationEnabled()) {
                Toast.makeText(this, "通知权限已开启，文件传输进度可正常展示", Toast.LENGTH_SHORT).show()
            } else {
                requestNotificationPermission()
            }
        }

        // ---------- 接收文件保存目录 ----------
        btnChangeSaveDir.setOnClickListener {
            // 调起系统文件选择器（SAF）让用户挑选目录
            try {
                pickSaveDirLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开目录选择器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        tvSaveDirPath.setOnClickListener {
            // 用其他应用打开：交给系统选择器，由用户挑选文件管理器或任意可处理目录的应用
            openSaveDirWithOtherApps()
        }

        tvResetSaveDir.setOnClickListener {
            SaveDirManager.resetToDefault(this)
            updateSaveDirUI()
            Toast.makeText(this, "已恢复默认保存目录", Toast.LENGTH_SHORT).show()
        }

        btnConnectPc.setOnClickListener {
            val service = SyncForegroundService.instance
            if (service == null) {
                Toast.makeText(this, "后台服务启动中，请稍候...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (service.connectionState == 1) {
                service.disconnectCurrentPc()
                etPinCode.isEnabled = true
                etPinCode.setText("")
                btnConnectPc.text = "一键连接"
                Toast.makeText(this, "已断开与电脑的连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pin = etPinCode.text.toString().trim()
            if (pin.isEmpty()) {
                Toast.makeText(this, "请输入电脑托盘显示的 6 位 PIN 码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var targetIp = service.currentPcIp
            if (targetIp.isEmpty()) {
                val manualIp = etManualIp.text.toString().trim()
                if (manualIp.isNotEmpty()) {
                    targetIp = manualIp
                }
            }

            if (targetIp.isEmpty()) {
                Toast.makeText(this, "尚未搜到电脑，请确保电脑已启动 CrossClip，或点击下方手动输入电脑 IP", Toast.LENGTH_LONG).show()
                llManualIpContainer.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnConnectPc.isEnabled = false
            btnConnectPc.text = "配对中..."

            service.connectWithPin(targetIp, pin) { success, statusCode, name ->
                btnConnectPc.isEnabled = true
                if (success) {
                    btnConnectPc.text = "断开连接"
                    etPinCode.isEnabled = false
                    Toast.makeText(this, "配对成功！设备: $name", Toast.LENGTH_SHORT).show()
                } else if (statusCode == 403) {
                    btnConnectPc.text = "一键连接"
                    etPinCode.isEnabled = true
                    Toast.makeText(this, "PIN 码不匹配！电脑端已生成新 PIN 码，请右键电脑右下角托盘查看", Toast.LENGTH_LONG).show()
                } else {
                    btnConnectPc.text = "一键连接"
                    etPinCode.isEnabled = true
                    Toast.makeText(this, "连接超时，请确认手机和电脑在同一局域网", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnTestSend.setOnClickListener {
            SyncForegroundService.instance?.sendCurrentClipboardManual()
                ?: Toast.makeText(this, "后台服务未就绪，正在拉起...", Toast.LENGTH_SHORT).show()
        }

        btnClipboardPerm.setOnClickListener {
            PermissionHelper.openClipboardPermissionSetting(this)
        }

        btnBatteryPerm.setOnClickListener {
            PermissionHelper.openBatteryKeepAliveSettings(this)
        }

        btnAutoStartPerm.setOnClickListener {
            PermissionHelper.openAutoStartPermissionSetting(this)
        }

        tvCollapseDebug.setOnClickListener {
            handler.removeCallbacks(collapseDebugRunnable)
            llShizukuDebugContainer.visibility = View.GONE
        }

        tvShizukuDebug.setOnClickListener {
            showLogsDialog()
        }
    }

    private fun showLogsDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("CrossClip 运行日志 (最新 200 行)")
            .setMessage(DebugLogger.readTail(200))
            .setPositiveButton("确定", null)
            .setNegativeButton("复制全部") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CrossClip Logs", DebugLogger.readAll()))
                Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("导出文件") { _, _ ->
                DebugLogger.shareLogFile(this)
            }
            .create()
        dialog.show()
    }

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
            .append("提示：若开启了下方的【多任务隐藏】，多任务栏将不会展示本应用卡片。建议保持多任务可见并给卡片加锁，兼顾后台驻留与防误杀。")
            .toString()

        AlertDialog.Builder(this)
            .setTitle("多任务卡片加锁操作指引")
            .setMessage(guideMessage)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun loadConfig() {
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        val isAuto = sp.getBoolean("auto_sync", true)
        switchAutoSync.isChecked = isAuto
        switchHideRecents.isChecked = sp.getBoolean("hide_recents", false)
        cardManualSync.visibility = if (isAuto) View.GONE else View.VISIBLE

        val savedPin = sp.getString("pin_code", "") ?: ""
        if (savedPin.isNotEmpty()) {
            etPinCode.setText(savedPin)
        }

        val lastIp = sp.getString("last_pc_ip", "") ?: ""
        if (lastIp.isNotEmpty()) {
            etManualIp.setText(lastIp)
        }

        // 自动搜索开关（默认开启）
        val autoSearch = sp.getBoolean("auto_search_enabled", true)
        isInitializingUi = true
        switchAutoSearch.isChecked = autoSearch
        isInitializingUi = false
        refreshAutoSearchUI(autoSearch)

        // 保存目录与通知权限状态的初始刷新
        updateSaveDirUI()
        updateNotificationBadge()
    }

    // ==================== 自动搜索开关 ====================

    /** 刷新自动搜索开关的说明文案（不弹 Toast，供初始化与切换共用） */
    private fun refreshAutoSearchUI(enabled: Boolean) {
        tvAutoSearchDesc.text = if (enabled) {
            "5 分钟后降频、15 分钟后停止，避免夜间空转耗电"
        } else {
            "已关闭自动搜索，点击「重新扫描」手动查找电脑"
        }
    }

    // ==================== 通知权限 ====================

    /** 是否已授予通知权限 */
    private fun isNotificationEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    /** 刷新通知权限徽标与按钮文案 */
    private fun updateNotificationBadge() {
        val enabled = isNotificationEnabled()
        tvNotificationBadge.text = if (enabled) "✅ 已开启" else "⚠️ 未开启"
        tvNotificationBadge.setTextColor(Color.parseColor(if (enabled) "#10B981" else "#EF4444"))
        btnNotificationPerm.text = if (enabled) "已开启" else "去开启"
    }

    /**
     * 申请通知权限。
     * Android 13+ 走运行时权限申请；更低版本不存在该运行时权限，直接跳系统通知设置页。
     */
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

    /** 跳转到本应用的通知设置页 */
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

    // ==================== 保存目录 ====================

    /** 刷新保存目录的展示路径与「恢复默认」入口可见性 */
    private fun updateSaveDirUI() {
        tvSaveDirPath.text = SaveDirManager.getDisplayPath(this)
        tvResetSaveDir.visibility = if (SaveDirManager.hasCustomDir(this)) View.VISIBLE else View.GONE
    }

    // ==================== 路径点击：用其他应用打开 ====================

    /**
     * 点击日志路径：用其他应用打开日志所在目录。
     *
     * 日志固定写在 App 私有外部目录（`Android/data/<包名>/files/`），而 FileProvider 中
     * `external-files-path` 的根恰好就是该目录本身（对其直接调用 getUriForFile 会越界抛
     * StringIndexOutOfBoundsException: length=56; index=57），因此 DebugLogger 内部改用
     * 父级 root 生成目录 URI，再交给系统「用其他应用打开」选择器。
     */
    private fun openLogDirWithOtherApps() {
        if (!DebugLogger.openLogDirectory(this)) {
            Toast.makeText(this, "没有可打开日志目录的应用，可点「导出」分享日志文件", Toast.LENGTH_LONG).show()
        }
    }

    /** 点击保存目录路径：用其他应用打开当前保存目录 */
    private fun openSaveDirWithOtherApps() {
        if (!SaveDirManager.openDir(this)) {
            Toast.makeText(this, "没有可打开该目录的应用，请手动前往该目录", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeviceSelectionDialog() {
        val service = SyncForegroundService.instance
        if (service == null) {
            Toast.makeText(this, "后台服务未就绪，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        val devices = service.getDiscoveredDeviceList()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("未发现在线电脑")
                .setMessage("当前局域网未探测到任何在线电脑。\n\n请确认：\n1. 手机与电脑已连入同一局域网/Wi-Fi\n2. 电脑端 CrossClip.exe 是否已在运行\n3. 若电脑刚重启或切换了网络，可点击重新扫描")
                .setPositiveButton("重新扫描") { _, _ ->
                    service.triggerRescan()
                    Toast.makeText(this, "正在重新搜索局域网电脑...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .setNeutralButton("清除历史配置") { _, _ ->
                    service.clearSavedHistory()
                    etPinCode.setText("")
                    etManualIp.setText("")
                    Toast.makeText(this, "已清除历史电脑与配对记录", Toast.LENGTH_SHORT).show()
                }
                .show()
            return
        }

        val items = devices.map { dev ->
            val isCurrent = (service.connectionState == 1 && dev.ip == service.currentPcIp)
            val tag = if (isCurrent) " [当前连接]" else ""
            "${dev.name} (${dev.ip})$tag"
        }.toTypedArray()

        var selectedIndex = devices.indexOfFirst { service.connectionState == 1 && it.ip == service.currentPcIp }
        if (selectedIndex < 0) {
            selectedIndex = devices.indexOfFirst { it.deviceId == service.currentTargetDeviceId || it.ip == service.currentPcIp }
        }
        if (selectedIndex < 0) selectedIndex = 0

        AlertDialog.Builder(this)
            .setTitle("选择连接的电脑 (${devices.size} 台在线)")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                val chosen = devices[which]
                service.selectTargetDevice(chosen)
                val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
                val devPin = sp.getString("pin_code_${chosen.deviceId}", "") ?: ""
                etPinCode.setText(devPin)
                etPinCode.isEnabled = true
                btnConnectPc.text = "一键连接"
                Toast.makeText(this, "已切换目标电脑: ${chosen.name} (${chosen.ip})", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("重新扫描") { _, _ ->
                service.triggerRescan()
                Toast.makeText(this, "正在重新搜索局域网电脑...", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startStatusPolling() {
        handler.post(object : Runnable {
            override fun run() {
                if (isDestroyedActivity) return
                refreshStatusFromService()
                handler.postDelayed(this, 1500)
            }
        })
    }

    /** 依据后台服务的实时状态刷新主页文案（状态轮询与自动搜索开关切换共用） */
    private fun refreshStatusFromService() {
        val service = SyncForegroundService.instance
        if (service != null) {
            val pcName = if (service.currentPcName.isNotEmpty() && service.currentPcName != "未连接") {
                service.currentPcName
            } else {
                "Windows 电脑"
            }

            val deviceList = service.getDiscoveredDeviceList()
            val devCount = deviceList.size

            // 自动搜索开关 / 扫描线程状态：用于区分「搜索中 / 已关闭 / 已暂停」，避免文案误导
            val autoSearchOn = service.isAutoSearchEnabled()
            val lanSearching = service.isLanSearching()

            // 优先从实时探测列表中提取当前目标电脑，杜绝展示未连通的旧死 IP
            val liveTarget = deviceList.firstOrNull { it.deviceId == service.currentTargetDeviceId }
                ?: deviceList.firstOrNull { service.currentPcIp.isNotEmpty() && it.ip == service.currentPcIp }
            val activeIp = if (service.connectionState == 1) {
                service.currentPcIp
            } else {
                liveTarget?.ip ?: ""
            }
            val activeName = if (service.connectionState == 1) {
                pcName
            } else {
                liveTarget?.name ?: pcName
            }

            if (activeIp.isNotEmpty() && (service.connectionState == 1 || liveTarget != null)) {
                if (devCount > 1) {
                    tvDiscoveredDevice.text = "🟢 局域网已发现 ($devCount 台): $activeName ($activeIp)"
                    btnSwitchDevice.visibility = View.VISIBLE
                    btnSwitchDevice.text = "切换 ($devCount)"
                } else {
                    tvDiscoveredDevice.text = "🟢 局域网已发现: $activeName ($activeIp)"
                    btnSwitchDevice.visibility = View.VISIBLE
                    btnSwitchDevice.text = "选择"
                }
                tvDiscoveredDevice.setTextColor(Color.parseColor("#10B981"))
            } else {
                if (devCount > 0) {
                    tvDiscoveredDevice.text = "🟢 局域网发现其他电脑 ($devCount 台)"
                    tvDiscoveredDevice.setTextColor(Color.parseColor("#10B981"))
                    btnSwitchDevice.visibility = View.VISIBLE
                    btnSwitchDevice.text = "选择 ($devCount)"
                } else {
                    tvDiscoveredDevice.text = when {
                        !autoSearchOn -> "🔍 自动搜索已关闭，点「重新扫描」查找电脑"
                        !lanSearching -> "🔍 搜索已暂停，点「重新扫描」继续查找"
                        else -> "🔍 正在局域网全网段搜索电脑..."
                    }
                    tvDiscoveredDevice.setTextColor(Color.parseColor("#64748B"))
                    btnSwitchDevice.visibility = View.GONE
                }
            }

            when (service.connectionState) {
                1 -> {
                    tvStatus.text = "● 已连接至 $pcName (${service.currentPcIp})"
                    tvStatus.setTextColor(Color.parseColor("#10B981"))
                    btnConnectPc.text = "断开连接"
                    btnConnectPc.isEnabled = true
                    etPinCode.isEnabled = false
                }
                2 -> {
                    tvStatus.text = "● PIN 码不匹配 (电脑已换码，请输入新码重连)"
                    tvStatus.setTextColor(Color.parseColor("#EF4444"))
                    btnConnectPc.text = "一键连接"
                    btnConnectPc.isEnabled = true
                    etPinCode.isEnabled = true
                }
                -1 -> {
                    tvStatus.text = "● 正在验证密文挑战握手..."
                    tvStatus.setTextColor(Color.parseColor("#F59E0B"))
                    btnConnectPc.text = "配对中..."
                    btnConnectPc.isEnabled = false
                }
                else -> {
                    btnConnectPc.text = "一键连接"
                    btnConnectPc.isEnabled = true
                    etPinCode.isEnabled = true
                    when {
                        liveTarget != null -> {
                            tvStatus.text = "● 已发现目标电脑，请输入 6 位 PIN 码连接"
                            tvStatus.setTextColor(Color.parseColor("#0284C7"))
                        }
                        !autoSearchOn -> {
                            tvStatus.text = "● 电脑离线，自动搜索已关闭（可点「重新扫描」查找）"
                            tvStatus.setTextColor(Color.parseColor("#64748B"))
                        }
                        !lanSearching -> {
                            tvStatus.text = "● 电脑离线，搜索已暂停（可点「重新扫描」继续）"
                            tvStatus.setTextColor(Color.parseColor("#64748B"))
                        }
                        else -> {
                            tvStatus.text = "● 电脑离线中，局域网搜索中..."
                            tvStatus.setTextColor(Color.parseColor("#64748B"))
                        }
                    }
                }
            }
        }
        btnBatteryPerm.text = "去设置"
        updateShizukuUI()
    }

    private fun promptMiuiKeepAliveIfNeeded() {
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        if (sp.getBoolean("miui_keepalive_prompted", false)) return
        val manufacturer = Build.MANUFACTURER.lowercase()
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

    private fun updateShizukuUI() {
        when (ShizukuClipboardManager.getState()) {
            ShizukuClipboardManager.State.READY -> {
                tvShizukuBadge.text = "🟢 特权已激活"
                tvShizukuBadge.setTextColor(Color.parseColor("#10B981"))
                tvShizukuDesc.text = "已连接系统底层的剪贴板服务，享受零弹窗、零焦点切换的 100% 后台静默互传。"
                btnShizukuAuth.text = "重新自检"
                btnShizukuAuth.isEnabled = true
            }
            ShizukuClipboardManager.State.UNAUTHORIZED -> {
                tvShizukuBadge.text = "🟡 待授权"
                tvShizukuBadge.setTextColor(Color.parseColor("#F59E0B"))
                tvShizukuDesc.text = "检测到 Shizuku 服务正在运行，点击下方按钮授权以开启完全静默互传。"
                btnShizukuAuth.text = "去授权"
                btnShizukuAuth.isEnabled = true
            }
            ShizukuClipboardManager.State.NOT_RUNNING -> {
                tvShizukuBadge.text = "⚪ 服务未运行"
                tvShizukuBadge.setTextColor(Color.parseColor("#64748B"))
                tvShizukuDesc.text = "Shizuku 未运行或未安装，当前已自动启用前台服务与兼容模式保障正常互传。"
                btnShizukuAuth.text = "打开 Shizuku"
                btnShizukuAuth.isEnabled = true
            }
        }
    }

    private fun startSyncService() {
        val intent = Intent(this, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun runShizukuSelfTest() {
        tvShizukuDebug.text = "正在自检 Shizuku 读取链路..."
        Thread {
            val result = ShizukuClipboardManager.debugReadClipboard()
            runOnUiThread {
                tvShizukuDebug.text = "自检: $result"
            }
        }.start()
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
        DebugLogger.log("APP_LIFECYCLE", "MainActivity.onDestroy")
    }
}
