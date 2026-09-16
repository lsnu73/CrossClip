package com.crossclip.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.crossclip.app.R
import com.crossclip.app.util.DebugLogger
import com.crossclip.app.util.LogLevel

/**
 * 屏 4 · 诊断与排查工具（二级页）。
 *
 * 从 DebugLogger 解析最近 500 条结构化日志，按四级语义色（INFO/OK/WARN/ERR）渲染在
 * 深色块内；筛选 chip 支持 全部 / 同步 / 网络 / 警告 / 错误（后两者按级别）；
 * 统计条联动错误与警告数；导航栏右侧「刷新」重新读取日志文件。
 */
class DiagnosticsActivity : AppCompatActivity() {

    private enum class Filter { ALL, SYNC, NET, WARN, ERR }

    private lateinit var chips: Map<Filter, TextView>
    private lateinit var logScroll: ScrollView
    private lateinit var tvLogs: TextView
    private lateinit var tvErrCount: TextView
    private lateinit var tvWarnCount: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var tvLogSize: TextView

    private var entries: List<DebugLogger.LogEntry> = emptyList()
    private var filter = Filter.ALL

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) writeLogTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_refresh).setOnClickListener { reload() }

        logScroll = findViewById(R.id.log_scroll)
        tvLogs = findViewById(R.id.tv_logs)
        tvErrCount = findViewById(R.id.tv_err_count)
        tvWarnCount = findViewById(R.id.tv_warn_count)
        tvTotalCount = findViewById(R.id.tv_total_count)
        tvLogSize = findViewById(R.id.tv_log_size)

        val chipAll = findViewById<TextView>(R.id.chip_all)
        val chipSync = findViewById<TextView>(R.id.chip_sync)
        val chipNet = findViewById<TextView>(R.id.chip_net)
        val chipWarn = findViewById<TextView>(R.id.chip_warn)
        val chipErr = findViewById<TextView>(R.id.chip_err)
        chips = mapOf(
            Filter.ALL to chipAll,
            Filter.SYNC to chipSync,
            Filter.NET to chipNet,
            Filter.WARN to chipWarn,
            Filter.ERR to chipErr,
        )
        chips.forEach { (f, chip) ->
            chip.setOnClickListener {
                filter = f
                applyChipStates()
                render()
            }
        }

        findViewById<TextView>(R.id.tv_log_path).apply {
            text = DebugLogger.getLogFilePath()
            setOnClickListener {
                // 打开「目录」在分区存储下走不通（详见 DebugLogger.openLogDirectory 注释），
                // 这里直接用文本查看器打开日志文件本体
                if (!DebugLogger.openLogDirectory(this@DiagnosticsActivity)) {
                    Toast.makeText(this@DiagnosticsActivity, "没有可打开日志的应用，可点「导出 .txt」", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btn_copy_logs).setOnClickListener {
            val all = DebugLogger.readAll()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CrossClip Logs", all))
            Toast.makeText(this, "已将全量运行日志复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_export_logs).setOnClickListener {
            exportLauncher.launch("crossclip-log.txt")
        }

        findViewById<Button>(R.id.btn_share_logs).setOnClickListener {
            DebugLogger.shareLogFile(this)
        }

        findViewById<TextView>(R.id.btn_clear_logs).setOnClickListener { confirmClearLogs() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Thread {
            val parsed = DebugLogger.readParsedTail(500)
            val sizeBytes = DebugLogger.getLogFileSizeBytes()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                entries = parsed
                tvLogSize.text = formatSize(sizeBytes)
                applyChipStates()
                render()
            }
        }.start()
    }

    // ==================== 渲染 ====================

    private fun applyChipStates() {
        val errCount = entries.count { it.level == LogLevel.ERR }
        chips.forEach { (f, chip) ->
            val selected = filter == f
            chip.isSelected = selected
            chip.setTextColor(
                when {
                    selected -> Color.WHITE
                    f == Filter.WARN -> getColor(R.color.log_warn)
                    f == Filter.ERR -> getColor(R.color.danger)
                    else -> getColor(R.color.text_2)
                }
            )
        }
        chips[Filter.ERR]?.text = "错误 $errCount"
    }

    private fun render() {
        val errCount = entries.count { it.level == LogLevel.ERR }
        val warnCount = entries.count { it.level == LogLevel.WARN }
        tvErrCount.text = errCount.toString()
        tvWarnCount.text = warnCount.toString()
        tvTotalCount.text = entries.size.toString()

        val filtered = when (filter) {
            Filter.ALL -> entries
            Filter.SYNC -> entries.filter { it.category == "sync" }
            Filter.NET -> entries.filter { it.category == "net" }
            Filter.WARN -> entries.filter { it.level == LogLevel.WARN }
            Filter.ERR -> entries.filter { it.level == LogLevel.ERR }
        }

        if (filtered.isEmpty()) {
            tvLogs.text = "— 当前筛选下暂无日志 —"
            tvLogs.setTextColor(getColor(R.color.log_dim))
            tvLogs.gravity = android.view.Gravity.CENTER
            return
        }

        tvLogs.gravity = android.view.Gravity.START
        tvLogs.setTextColor(getColor(R.color.log_text))
        val sb = SpannableStringBuilder()
        val tsColor = getColor(R.color.log_ts)
        val dimColor = getColor(R.color.log_dim)
        filtered.forEach { e ->
            sb.appendColored(e.tsDisplay, tsColor)
            sb.append(' ')
            sb.appendColored(e.level.padded(), colorFor(e.level))
            sb.append(' ')
            sb.appendColored("[${e.tag}] ", dimColor)
            sb.appendColored(e.msg, dimColor)
            sb.append('\n')
        }
        tvLogs.text = sb
        // 跟随最新日志滚动到底部
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun colorFor(level: LogLevel): Int = when (level) {
        LogLevel.INFO -> getColor(R.color.log_info)
        LogLevel.OK -> getColor(R.color.log_ok)
        LogLevel.WARN -> getColor(R.color.log_warn)
        LogLevel.ERR -> getColor(R.color.log_err)
    }

    private fun SpannableStringBuilder.appendColored(text: String, color: Int) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ==================== 导出 / 清空 ====================

    private fun writeLogTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(DebugLogger.readAll().toByteArray())
            }
            Toast.makeText(this, "已导出 crossclip-log.txt", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("清空全部日志？")
            .setMessage("将删除 ${entries.size} 条记录，此操作不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                DebugLogger.clear()
                reload()
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
