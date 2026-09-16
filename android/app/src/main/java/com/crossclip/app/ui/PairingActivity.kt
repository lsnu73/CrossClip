package com.crossclip.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.crossclip.app.R
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.util.DebugLogger

/**
 * 屏 2 · 电脑配对。
 *
 * 进入即显示扫描态；扫描出结果后切换为「已发现的设备」列表（在线直接连接，历史电脑以
 * 离线条目展示）；底部保留「手动添加 IP」入口。点击设备进入屏 3 输入 PIN 码。
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var scanContainer: View
    private lateinit var emptyState: View
    private lateinit var tvEmptyHint: TextView
    private lateinit var btnRescanEmpty: Button
    private lateinit var deviceSection: View
    private lateinit var deviceList: LinearLayout
    private lateinit var etManualIp: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyedActivity = false

    /** 上次渲染的设备指纹，避免每 500ms 重建列表导致点击态闪烁 */
    private var lastRenderKey = ""
    private var scanSpinnerMinShownUntil = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isDestroyedActivity) return
            renderScanState()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        scanContainer = findViewById(R.id.scan_container)
        emptyState = findViewById(R.id.empty_state)
        tvEmptyHint = findViewById(R.id.tv_empty_hint)
        btnRescanEmpty = findViewById(R.id.btn_rescan_empty)
        deviceSection = findViewById(R.id.device_section)
        deviceList = findViewById(R.id.device_list)
        etManualIp = findViewById(R.id.et_manual_ip)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnRescanEmpty.setOnClickListener { rescan() }

        findViewById<Button>(R.id.btn_add_ip).setOnClickListener {
            val ip = etManualIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入电脑的有效 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 手动地址直接进 PIN 码屏；目标名以 IP 兜底展示
            openPinScreen(name = ip, ip = ip)
        }

        // 扫描 spinner 至少展示 1.2 秒，避免一进页面就闪空列表
        scanSpinnerMinShownUntil = System.currentTimeMillis() + 1200
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyedActivity = true
        handler.removeCallbacksAndMessages(null)
    }

    // ==================== 扫描态与设备列表 ====================

    private fun renderScanState() {
        val service = SyncForegroundService.instance
        if (service == null) {
            showOnly(scanContainer)
            return
        }
        val devices = service.getDiscoveredDeviceList().toMutableList()
        val connected = service.connectionState == 1

        // 已连接的电脑始终算作在线并排在最前
        if (connected && devices.none { it.ip == service.currentPcIp }) {
            devices.add(0, SyncForegroundService.DiscoveredDevice(
                deviceId = service.currentTargetDeviceId.ifEmpty { "pc_${service.currentPcIp.replace('.', '_')}" },
                name = service.currentPcName.ifEmpty { "Windows 电脑" },
                ip = service.currentPcIp,
                httpPort = service.currentHttpPort
            ))
        }

        val spinnerVisible = service.isLanSearching() && devices.isEmpty() ||
            System.currentTimeMillis() < scanSpinnerMinShownUntil

        if (spinnerVisible) {
            showOnly(scanContainer)
            return
        }

        if (devices.isEmpty()) {
            showOnly(emptyState)
            tvEmptyHint.text = when {
                !service.isAutoSearchEnabled() -> "自动搜索已关闭，请手动添加 IP 或点击重新扫描"
                else -> "未发现在线电脑\n请确认电脑端 CrossClip 已运行且两端在同一网络"
            }
            return
        }

        showOnly(deviceSection)
        renderDeviceList(service, devices, connected)
    }

    private fun showOnly(visible: View) {
        scanContainer.visibility = if (visible === scanContainer) View.VISIBLE else View.GONE
        emptyState.visibility = if (visible === emptyState) View.VISIBLE else View.GONE
        deviceSection.visibility = if (visible === deviceSection) View.VISIBLE else View.GONE
    }

    private fun renderDeviceList(
        service: SyncForegroundService,
        devices: MutableList<SyncForegroundService.DiscoveredDevice>,
        connected: Boolean
    ) {
        // 在线 + 离线（历史电脑）合并渲染
        val offlineIp = service.lastSavedPcIp
        val offlineName = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
            .getString("last_pc_name", "") ?: ""
        val hasOfflineEntry = offlineIp.isNotEmpty() && devices.none { it.ip == offlineIp } && offlineName.isNotEmpty()

        val key = buildString {
            devices.forEach { append(it.deviceId).append(':').append(it.ip).append(';') }
            append('|').append(connected).append('|').append(hasOfflineEntry).append('|').append(offlineName)
        }
        if (key == lastRenderKey) return
        lastRenderKey = key

        deviceList.removeAllViews()
        devices.forEachIndexed { index, dev ->
            val isCurrent = connected && dev.ip == service.currentPcIp
            addDeviceRow(
                name = dev.name,
                ip = dev.ip,
                online = true,
                onlineSuffix = if (isCurrent) "当前连接" else "刚刚在线",
                onConnect = {
                    service.selectTargetDevice(dev)
                    openPinScreen(name = dev.name, ip = dev.ip)
                }
            )
            if (index < devices.size - 1 || hasOfflineEntry) addDivider()
        }

        // 历史电脑（不在线）：离线条目 + 重连
        if (hasOfflineEntry) {
            addDeviceRow(
                name = offlineName,
                ip = offlineIp,
                online = false,
                onlineSuffix = "离线",
                onConnect = {
                    openPinScreen(name = offlineName, ip = offlineIp)
                }
            )
        }
    }

    private fun addDeviceRow(name: String, ip: String, online: Boolean, onlineSuffix: String, onConnect: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_device, deviceList, false)
        val tvName = row.findViewById<TextView>(R.id.tv_device_name)
        val tvSub = row.findViewById<TextView>(R.id.tv_device_sub)
        val badge = row.findViewById<TextView>(R.id.tv_badge)
        val btn = row.findViewById<Button>(R.id.btn_action)

        tvName.text = name
        tvSub.text = "$ip · $onlineSuffix"

        if (online) {
            badge.text = "在线"
            badge.setBackgroundResource(R.drawable.bg_badge_live)
            badge.setTextColor(getColor(R.color.accent))
            btn.setBackgroundResource(R.drawable.bg_btn_dark)
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.text = "连接"
            row.setOnClickListener { onConnect() }
            btn.setOnClickListener { onConnect() }
        } else {
            row.findViewById<View>(R.id.iv_device_icon).alpha = 0.5f
            tvName.setTextColor(getColor(R.color.text_2))
            badge.text = "离线"
            badge.setBackgroundResource(R.drawable.bg_badge)
            badge.setTextColor(getColor(R.color.text_3))
            btn.setBackgroundResource(R.drawable.bg_btn_ghost)
            btn.setTextColor(getColor(android.R.color.white))
            btn.text = "重连"
            row.setOnClickListener { onConnect() }
            btn.setOnClickListener { onConnect() }
        }
        deviceList.addView(row)
    }

    private fun addDivider() {
        val div = View(this)
        div.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { marginStart = resources.displayMetrics.density.toInt() * 20 }
        div.setBackgroundColor(getColor(R.color.line_color))
        deviceList.addView(div)
    }

    private fun rescan() {
        val service = SyncForegroundService.instance
        if (service == null) {
            Toast.makeText(this, "后台服务未就绪，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        scanSpinnerMinShownUntil = System.currentTimeMillis() + 1200
        lastRenderKey = ""
        service.triggerRescan()
        Toast.makeText(this, "正在重新搜索局域网电脑...", Toast.LENGTH_SHORT).show()
    }

    private fun openPinScreen(name: String, ip: String) {
        val service = SyncForegroundService.instance
        if (service == null) {
            Toast.makeText(this, "后台服务未就绪，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        // 预填该电脑记忆中的 PIN 码（可能直接命中，免去重输）
        val sp = getSharedPreferences("cross_clip_config", MODE_PRIVATE)
        val deviceId = service.currentTargetDeviceId
        val savedPin = sp.getString("pin_code_$deviceId", "") ?: sp.getString("pin_code", "") ?: ""
        DebugLogger.log("UI", "进入 PIN 码配对屏: $name ($ip) savedPin.len=${savedPin.length}")
        startActivity(
            Intent(this, PairCodeActivity::class.java)
                .putExtra(PairCodeActivity.EXTRA_DEVICE_NAME, name)
                .putExtra(PairCodeActivity.EXTRA_DEVICE_IP, ip)
                .putExtra(PairCodeActivity.EXTRA_SAVED_PIN, savedPin)
        )
    }
}
