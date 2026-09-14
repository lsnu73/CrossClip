package com.crossclip.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.crossclip.app.receiver.ShareReceiveActivity
import com.crossclip.app.util.DebugLogger

class CrossClipApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 1. 初始化诊断日志文件与全局崩溃捕获
        DebugLogger.init(this)
        DebugLogger.setupCrashHandler()

        // 2. 记录当前 Session 启动头信息
        DebugLogger.log(
            "APP_LIFECYCLE",
            "CrossClip 应用实例创建: PID=${Process.myPid()} | SessionID=${DebugLogger.sessionId}"
        )

        // 3. 输出系统环境快照与 Cgroup 状态
        DebugLogger.dumpSystemStatus(this)
        DebugLogger.dumpCgroupStatus()

        // 4. 读取上次进程死亡的黑匣子退出原因
        DebugLogger.dumpHistoricalProcessExitReasons(this)

        // 5. 发布「直接分享」快捷方式：长按文件拖拽弹出的系统浮层据此列出 CrossClip
        publishShareShortcut()
    }

    /**
     * 发布「发送到电脑」分享快捷方式。
     *
     * Android 10+ 的「直接分享」(分享面板首行 + 各 ROM 长按拖拽弹出的应用浮层)
     * 不看 manifest 的 intent-filter，而是看应用通过 ShortcutManager 发布的、
     * categories 命中 `res/xml/shortcuts.xml` 里 `<share-target>` 的动态快捷方式。
     * 没有这一步，CrossClip 只会出现在分享面板下方的「完整应用列表」里，
     * 拖拽浮层里则完全不出现。
     *
     * 每次进程启动都重发一遍：幂等（set 同 id 即更新），顺带在应用升级/换图标后自动刷新。
     */
    private fun publishShareShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            // 已发布过同名快捷方式就跳过：addDynamicShortcuts 受系统限流约束
            // (短时间内频繁增删会被静默拒绝)，而本应用的进程经常被 ROM 杀掉重启，
            // 每次启动都重发一遍迟早撞上限流
            if (ShortcutManagerCompat.getDynamicShortcuts(this).any { it.id == "send_to_pc" }) {
                return
            }
            val shortcut = ShortcutInfoCompat.Builder(this, "send_to_pc")
                .setShortLabel(getString(R.string.share_shortcut_label))
                .setLongLabel(getString(R.string.share_shortcut_long_label))
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(this, ShareReceiveActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        type = "*/*"
                    }
                )
                .setCategories(setOf("com.crossclip.app.category.SEND_TO_PC"))
                .build()
            val ok = ShortcutManagerCompat.addDynamicShortcuts(this, listOf(shortcut))
            DebugLogger.log("APP_LIFECYCLE", "发布「直接分享」快捷方式: success=$ok")
        } catch (e: Exception) {
            // 分享快捷方式只是入口增强，失败不影响任何核心功能，记录即可
            DebugLogger.log("APP_LIFECYCLE", "发布「直接分享」快捷方式失败: ${e.message}")
            Log.w("CrossClipApp", "publishShareShortcut failed", e)
        }
    }


    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLogger.logTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DebugLogger.log("SYS_MEMORY", "收到系统最高级别内存匮乏通知 onLowMemory")
    }
}
