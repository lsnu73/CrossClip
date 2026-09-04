package com.crossclip.app.shizuku

import android.content.Context
import android.os.Process
import android.util.Log
import com.crossclip.app.util.DebugLogger
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuPrivilegeHelper {

    private const val TAG = "ShizukuPrivilege"
    private val executor = Executors.newSingleThreadExecutor()
    private val hasApplied = AtomicBoolean(false)
    private val pulseStarted = AtomicBoolean(false)

    /**
     * 自动通过 Shizuku 执行底层 Shell 命令将应用注入系统白名单并全开后台权限。
     * 针对 MIUI/HyperOS 增加内核 MiFreezer 与 Cgroup 冻结免除策略。
     */
    fun applySystemWhitelists(context: Context, force: Boolean = false) {
        if (!ShizukuClipboardManager.isReady()) {
            Log.w(TAG, "Shizuku 尚未就绪，暂不执行特权白名单注入")
            DebugLogger.log("SHIZUKU_PRIV", "Shizuku 尚未就绪，跳过白名单注入")
            return
        }

        if (!force && hasApplied.getAndSet(true)) {
            Log.d(TAG, "已执行过特权白名单注入，跳过重复执行")
            startBackgroundWakeupPulse(context)
            return
        }

        val pkg = context.packageName
        val pid = Process.myPid()
        executor.execute {
            try {
                Log.i(TAG, "开始执行 Shizuku 系统级白名单与防冻结注入...")
                DebugLogger.log("SHIZUKU_PRIV", "开始执行系统级白名单与防冻结注入 (包名: $pkg, PID: $pid)")

                val commands = listOf(
                    // 1. Android 原生系统 Doze / DeviceIdle 永久白名单
                    "cmd deviceidle whitelist +$pkg",
                    "dumpsys deviceidle whitelist +$pkg",

                    // 2. AppOps 原生后台运行、WakeLock、前台服务权限
                    "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                    "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
                    "cmd appops set $pkg WAKE_LOCK allow",
                    "cmd appops set $pkg START_FOREGROUND allow",
                    "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow",
                    "cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",

                    // 3. MIUI / HyperOS 定制私有权限码赋权
                    // 10008: MIUI 自启动 (AUTO_START)
                    "cmd appops set $pkg 10008 allow",
                    // 10021: MIUI 后台弹出界面 / 锁屏显示 (SHOW_WHEN_LOCKED)
                    "cmd appops set $pkg 10021 allow",
                    // 10022: MIUI 后台运行常驻 (BACKGROUND_START_ACTIVITY)
                    "cmd appops set $pkg 10022 allow",
                    // 10020: MIUI 显示悬浮窗
                    "cmd appops set $pkg 10020 allow",

                    // 4. MIUI 自启动广播
                    "am broadcast -a miui.intent.action.AUTOSTART_ENABLE --es extra_pkgname $pkg",

                    // 5. MIUI Powerkeeper 数据库级无限制策略写入 (bg_control: 0 代表无限制)
                    "content insert --uri content://com.miui.powerkeeper.configure/scene --bind pkg:s:$pkg --bind bg_control:i:0",
                    "content update --uri content://com.miui.powerkeeper.configure/scene --bind bg_control:i:0 --where \"pkg='$pkg'\"",

                    // 6. MIUI / HyperOS 进程冷冻器 (MiFreezer / Joyose) 免冻结名单
                    "settings put global miui_freeze_disabled_packages $pkg",
                    "settings put system miui_freeze_disabled_packages $pkg",
                    "settings put secure miui_freeze_disabled_packages $pkg"
                )

                var successCount = 0
                for (cmd in commands) {
                    val (exitCode, out, err) = runShellCommandWithDetails(cmd)
                    if (exitCode == 0) {
                        successCount++
                        DebugLogger.log("SHIZUKU_CMD", "OK (code $exitCode) -> [$cmd] ${if (out.isNotEmpty()) ": $out" else ""}")
                    } else {
                        DebugLogger.log("SHIZUKU_CMD", "WARN (code $exitCode) -> [$cmd] stdout=[$out] stderr=[$err]")
                    }
                }

                DebugLogger.log("SHIZUKU_PRIV", "特权白名单注入完成: $successCount/${commands.size} 成功")

                // 诊断：检查系统 DeviceIdle 白名单当前内容
                val (_, dumpOut, _) = runShellCommandWithDetails("dumpsys deviceidle whitelist")
                val isWhitelisted = dumpOut.contains(pkg)
                DebugLogger.log("SHIZUKU_CHECK", "当前 DeviceIdle 白名单包含本应用: $isWhitelisted")

                // 启动后台常驻 Shell 守护脉冲 (打破 Cgroup Freezer 挂起)
                startBackgroundWakeupPulse(context)
            } catch (e: Throwable) {
                Log.e(TAG, "执行 Shizuku 特权注入异常: ${e.javaClass.simpleName}: ${e.message}")
                DebugLogger.log("SHIZUKU_PRIV", "特权注入异常: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * 启动运行在 Shell (UID 2000) 特权下的独立后台常驻守护循环。
     * Shell 进程不受 MIUI/HyperOS 进程冷冻器影响。
     * 每 10 秒向 CrossClip 发送显式唤醒广播（解冻活进程）；
     * 若检测到进程已被系统击杀，立即通过 shell 拉起前台服务实现自愈复活
     * （运行时注册的 receiver 随进程死亡，仅靠广播无法唤醒已死进程）。
     */
    fun startBackgroundWakeupPulse(context: Context) {
        if (pulseStarted.getAndSet(true)) {
            return
        }
        val pkg = context.packageName
        executor.execute {
            try {
                // 先清理可能存在的旧守护循环
                runShellCommandWithDetails("pkill -f 'com.crossclip.app.WAKEUP'")
                // 在后台以 UID 2000 (Shell) 启动常驻循环：
                // 使用 -f 0x10000020 (FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES) 与 --receiver-foreground
                // 强制广播队列解冻处于 Cgroup Freezer 挂起状态的目标进程；
                // 进程存活探测结合 pidof 与 pgrep，拉起前台服务时同样附带穿透参数。
                val loopCmd = "nohup sh -c 'while true; do " +
                    "am broadcast -a com.crossclip.app.WAKEUP -p $pkg -f 0x10000020 --receiver-foreground >/dev/null 2>&1; " +
                    "(pidof $pkg >/dev/null 2>&1 || pgrep -f $pkg >/dev/null 2>&1) || " +
                    "am start-foreground-service -n $pkg/com.crossclip.app.service.SyncForegroundService -f 0x10000020 >/dev/null 2>&1; " +
                    "sleep 5; done' >/dev/null 2>&1 &"
                val res = runShellCommandWithDetails(loopCmd)
                DebugLogger.log("SHIZUKU_DAEMON", "已启动 Shell (UID 2000) 前台高优先解冻守护 (间隔 5s): code=${res.exitCode}")
            } catch (e: Exception) {
                DebugLogger.log("SHIZUKU_DAEMON", "启动 Shell 常驻唤醒守护异常: ${e.message}")
            }
        }
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runShellCommandWithDetails(command: String): CommandResult {
        return try {
            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stdoutBuilder.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }
            val stderrThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrBuilder.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()
            stdoutThread.join(1000)
            stderrThread.join(1000)

            CommandResult(exitCode, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
        } catch (e: Throwable) {
            CommandResult(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
