package com.crossclip.app

import android.app.Application
import android.os.Process
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
