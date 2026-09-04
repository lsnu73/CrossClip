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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.crossclip.app.R
import com.crossclip.app.BuildConfig
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.shizuku.ShizukuClipboardManager
import com.crossclip.app.util.PermissionHelper
import com.crossclip.app.util.DebugLogger
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAppTitle: TextView
    private lateinit var tvVersionBadge: TextView
    private lateinit var tvDiscoveredDevice: TextView
    private lateinit var btnRefreshScan: TextView
    private lateinit var etPinCode: EditText
    private lateinit var btnConnectPc: Button
    private lateinit var tvToggleManualIp: TextView
    private lateinit var llManualIpContainer: LinearLayout
    private lateinit var etManualIp: EditText
    private lateinit var btnApplyManualIp: Button

    private lateinit var switchAutoSync: SwitchCompat
    private lateinit var btnLockTaskGuide: Button
    private lateinit var switchHideRecents: SwitchCompat
    private lateinit var btnSilenceNotification: Button

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
        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
        val hideRecents = sp.getBoolean("hide_recents", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
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
        btnRefreshScan = findViewById(R.id.btn_refresh_scan)
        etPinCode = findViewById(R.id.et_pin_code)
        btnConnectPc = findViewById(R.id.btn_connect_pc)
        tvToggleManualIp = findViewById(R.id.tv_toggle_manual_ip)
        llManualIpContainer = findViewById(R.id.ll_manual_ip_container)
        etManualIp = findViewById(R.id.et_manual_ip)
        btnApplyManualIp = findViewById(R.id.btn_apply_manual_ip)

        switchAutoSync = findViewById(R.id.switch_auto_sync)
        btnLockTaskGuide = findViewById(R.id.btn_lock_task_guide)
        switchHideRecents = findViewById(R.id.switch_hide_recents)
        btnSilenceNotification = findViewById(R.id.btn_silence_notification)

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

        tvLogFilePath.text = "日志路径: ${DebugLogger.getLogFilePath()}"

        btnViewLogs.setOnClickListener {
            showLogsDialog()
        }

        btnCopyLogs.setOnClickListener {
            val allLogs = DebugLogger.readAll()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
            Toast.makeText(this, "正在重新搜索局域网电脑...", Toast.LENGTH_SHORT).show()
            startSyncService()
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
            val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
            sp.edit().putBoolean("auto_sync", isChecked).apply()
            startSyncService()
        }

        btnLockTaskGuide.setOnClickListener {
            showLockTaskGuideDialog()
        }

        switchHideRecents.setOnCheckedChangeListener { _, isChecked ->
            val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
            sp.edit().putBoolean("hide_recents", isChecked).apply()
            applyHideFromRecentsPreference()
            if (isChecked) {
                Toast.makeText(this, "已开启最近任务隐藏，退至桌面后在多任务中隐身，彻底免疫一键清理", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "最近任务隐藏已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        btnSilenceNotification.setOnClickListener {
            SyncForegroundService.instance?.hideForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, SyncForegroundService.CHANNEL_ID)
                    }
                    startActivity(intent)
                    Toast.makeText(this, "已隐藏通知！在系统页面将【允许通知】关闭即可彻底无痕", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    val appIntent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(appIntent)
                }
            } else {
                Toast.makeText(this, "已隐藏通知中心常驻通知", Toast.LENGTH_SHORT).show()
            }
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
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
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
    }

    private fun startStatusPolling() {
        handler.post(object : Runnable {
            override fun run() {
                if (isDestroyedActivity) return
                val service = SyncForegroundService.instance
                if (service != null) {
                    val pcName = if (service.currentPcName.isNotEmpty() && service.currentPcName != "未连接") {
                        service.currentPcName
                    } else {
                        "Windows 电脑"
                    }

                    if (service.currentPcIp.isNotEmpty()) {
                        tvDiscoveredDevice.text = "🟢 局域网已发现: $pcName (${service.currentPcIp})"
                        tvDiscoveredDevice.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        tvDiscoveredDevice.text = "🔍 正在局域网全网段搜索电脑..."
                        tvDiscoveredDevice.setTextColor(Color.parseColor("#64748B"))
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
                            if (service.currentPcIp.isNotEmpty()) {
                                tvStatus.text = "● 已发现目标电脑，请输入 6 位 PIN 码连接"
                                tvStatus.setTextColor(Color.parseColor("#0284C7"))
                            } else {
                                tvStatus.text = "● 局域网搜索中..."
                                tvStatus.setTextColor(Color.parseColor("#64748B"))
                            }
                        }
                    }
                }
                btnBatteryPerm.text = "去设置"
                updateShizukuUI()
                handler.postDelayed(this, 1500)
            }
        })
    }

    private fun promptMiuiKeepAliveIfNeeded() {
        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
        if (sp.getBoolean("miui_keepalive_prompted", false)) return
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (!manufacturer.contains("xiaomi") && !manufacturer.contains("redmi")) return

        sp.edit().putBoolean("miui_keepalive_prompted", true).apply()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
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
