package com.crossclip.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {

    private const val TAG = "CrossClipDebug"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var internalLogFile: File? = null
    private var externalLogFile: File? = null

    val sessionId: String = java.util.UUID.randomUUID().toString().take(8)
    private val appStartTimeMs = System.currentTimeMillis()

    fun init(context: Context) {
        try {
            val appCtx = context.applicationContext
            internalLogFile = File(appCtx.filesDir, "crossclip_debug.log")
            
            val extDir = appCtx.getExternalFilesDir(null)
            if (extDir != null) {
                externalLogFile = File(extDir, "crossclip_debug.log")
            }
        } catch (e: Exception) {
            Log.w(TAG, "初始化日志文件失败: ${e.message}")
        }
    }

    @Synchronized
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val nowStr = synchronized(dateFormat) { dateFormat.format(Date()) }
        val elapsedSec = (System.currentTimeMillis() - appStartTimeMs) / 1000
        val threadName = Thread.currentThread().name
        val pid = Process.myPid()
        
        val sb = StringBuilder()
        sb.append("[$nowStr] [+$elapsedSec s] [S:$sessionId] [PID:$pid/$threadName] [$tag] $message\n")
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString()).append("\n")
        }
        val entry = sb.toString()

        Log.i(TAG, "[$tag] $message")
        if (throwable != null) {
            Log.w(TAG, throwable)
        }

        writeToFile(internalLogFile, entry)
        writeToFile(externalLogFile, entry)
    }

    private fun writeToFile(file: File?, text: String) {
        if (file == null) return
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            if (file.length() > 5 * 1024 * 1024) { // 5MB 轮转
                file.delete()
                file.createNewFile()
            }
            file.appendText(text)
        } catch (_: Exception) {}
    }

    fun getPrimaryLogFile(): File? {
        return externalLogFile?.takeIf { it.exists() } ?: internalLogFile
    }

    fun getLogFilePath(): String {
        return externalLogFile?.absolutePath ?: internalLogFile?.absolutePath ?: "未知路径"
    }

    @Synchronized
    fun readTail(maxLines: Int = 200): String {
        return try {
            val file = getPrimaryLogFile() ?: return "日志文件未初始化"
            if (!file.exists()) return "暂无日志记录"
            val lines = file.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Synchronized
    fun readAll(): String {
        return try {
            val file = getPrimaryLogFile() ?: return "日志文件未初始化"
            if (!file.exists()) return "暂无日志记录"
            file.readText()
        } catch (e: Exception) {
            "读取全部日志失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Synchronized
    fun clear(): Boolean {
        return try {
            internalLogFile?.delete()
            externalLogFile?.delete()
            log("LOG", "日志文件已清空")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun shareLogFile(context: Context) {
        val file = getPrimaryLogFile()
        if (file == null || !file.exists() || file.length() == 0L) {
            android.widget.Toast.makeText(context, "暂无日志可导出", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = try {
                FileProvider.getUriForFile(context, authority, file)
            } catch (_: Exception) {
                Uri.fromFile(file)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CrossClip 调试日志文档")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "导出 CrossClip 日志文档").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "导出失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 用其他应用打开日志文件。
     *
     * #### 为什么打开「文件」而不是「所在目录」
     * 日志位于 `Android/data/<包名>/files`，Android 11+ 分区存储下**任何**应用（包括各 OEM
     * 自带文件管理器）都无权浏览其他应用的 `Android/data` 目录 —— 打开目录的选择器里只会
     * 出现网盘/浏览器类噪音，连系统「文件」都不出现，这条路本身走不通。
     * 改为经 FileProvider 以 `text/plain` 直接打开日志文件本体，文本查看器/编辑器都能接手；
     * 应用对 `filesDir` 自有读权，FileProvider 授予临时读权后对端即可正常读取。
     *
     * @return 是否成功调起选择器
     */
    fun openLogDirectory(context: Context): Boolean {
        val file = getPrimaryLogFile()
        if (file == null || !file.exists() || file.length() == 0L) {
            android.widget.Toast.makeText(context, "暂无日志可查看", android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = try {
                FileProvider.getUriForFile(context, authority, file)
            } catch (_: Exception) {
                Uri.fromFile(file)
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 从 Application/Service 上下文启动时需要 NEW_TASK
            if (context !is android.app.Activity) {
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(viewIntent, "用其他应用打开日志").apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
            log("LOG_DIR", "已用「其他应用打开」调起日志文件: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            log("LOG_DIR", "用其他应用打开日志失败: ${e.javaClass.simpleName}: ${e.message}")
            android.widget.Toast.makeText(
                context,
                "没有可打开日志的应用\n路径: ${file.absolutePath}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("CRASH", "捕获未捕获异常: 线程=${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun dumpHistoricalProcessExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("EXIT_REASON", "当前系统版本低于 Android 11 (API 30)，不支持 getHistoricalProcessExitReasons")
            return
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exitList = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exitList.isEmpty()) {
                log("EXIT_REASON", "系统未记录到历史进程退出记录 (首次运行或记录已被系统清理)")
                return
            }
            log("EXIT_REASON", "==== 历史进程退出诊断报告 (最近 ${exitList.size} 次) ====")
            for ((idx, info) in exitList.withIndex()) {
                val reasonStr = when (info.reason) {
                    ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF (应用主动退出)"
                    ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED (被操作系统信号杀死)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY (系统内存不足 LMK 回收)"
                    ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH (Java 未捕获异常崩溃)"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE (底层 C/C++ 崩溃)"
                    ApplicationExitInfo.REASON_ANR -> "REASON_ANR (主线程无响应卡死)"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE (初始化失败)"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE (权限变更导致重启)"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE (资源/功耗超标被系统杀死)"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED (用户主动停止应用)"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED (用户划掉多任务卡片/Force Stop)"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED (强依赖进程死亡)"
                    ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER (系统其他策略)"
                    else -> "REASON_UNKNOWN (${info.reason})"
                }

                val statusStr = if (info.reason == ApplicationExitInfo.REASON_SIGNALED) {
                    when (info.status) {
                        9 -> "信号 9 (SIGKILL，强制直接击杀)"
                        15 -> "信号 15 (SIGTERM，终止请求)"
                        11 -> "信号 11 (SIGSEGV，内存段错误)"
                        6 -> "信号 6 (SIGABRT，异常中止)"
                        else -> "信号 ${info.status}"
                    }
                } else {
                    "状态码=${info.status}"
                }

                val importanceStr = when (info.importance) {
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND (前台交互中)"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE (前台服务常驻)"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE (前台可见)"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE (后台服务)"
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED (后台缓存进程)"
                    else -> "等级=${info.importance}"
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.timestamp))
                val desc = info.description ?: "无额外系统描述"
                val pssMb = info.pss / 1024
                val rssMb = info.rss / 1024

                log("EXIT_REASON", "#${idx + 1} [$dateStr] PID=${info.pid} | $reasonStr | $statusStr | 进程级别=$importanceStr | 内存(PSS=${pssMb}MB, RSS=${rssMb}MB) | 系统描述=$desc")
            }
            log("EXIT_REASON", "==================================================")
        } catch (e: Throwable) {
            log("EXIT_REASON", "提取历史进程退出记录异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun dumpSystemStatus(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            val isIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm?.isDeviceIdleMode ?: false else false
            val isPowerSave = pm?.isPowerSaveMode ?: false

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isMetered = cm?.isActiveNetworkMetered ?: false

            log("SYS_STATE", "电池优化已忽略(白名单)=$isIgnored | 设备处于Doze空闲=$isIdle | 全局省电模式=$isPowerSave | 计费网络=$isMetered")
        } catch (e: Exception) {
            log("SYS_STATE", "检查系统状态异常: ${e.message}")
        }
    }

    fun dumpCgroupStatus() {
        try {
            val cgroupFile = File("/proc/self/cgroup")
            if (cgroupFile.exists()) {
                val lines = cgroupFile.readLines().take(5).joinToString(" ; ")
                log("CGROUP", "当前进程 Cgroup: $lines")
            }
        } catch (e: Exception) {
            log("CGROUP", "读取 /proc/self/cgroup 异常: ${e.message}")
        }
    }

    fun logTrimMemory(level: Int) {
        val desc = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE (前台运行但系统内存吃紧)"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW (前台运行且系统内存极度匮乏)"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL (前台运行且系统内存极度危急，即将启动杀后台)"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN (界面退入后台)"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND (位于后台缓存列表开始处)"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE (位于后台缓存列表中间)"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE (位于后台缓存末尾，系统即将杀死本进程)"
            else -> "LEVEL_$level"
        }
        log("SYS_MEMORY", "收到系统内存告警 onTrimMemory: $desc")
    }
}
