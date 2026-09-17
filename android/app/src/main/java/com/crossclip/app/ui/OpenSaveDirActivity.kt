package com.crossclip.app.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.crossclip.app.util.DebugLogger
import com.crossclip.app.util.SaveDirManager

/**
 * 「打开接收文件保存目录」的无界面跳板 Activity。
 *
 * #### 为什么需要它
 * 文件接收完成的通知希望「点击后直接打开保存目录」，但：
 *  - 通知的 `PendingIntent` 只能启动 Activity / Service / 发广播，无法直接执行
 *    `SaveDirManager.openDir()` 里的打开目录逻辑；
 *  - 那个逻辑又必须在 Activity 上下文中调起（它会再 `startActivity` 系统打开方式）。
 *
 * 于是用一个不绘制任何界面的透明 Activity 承接：点击通知 → 启动本 Activity →
 * 打开目录 → 立刻 `finish()`。系统「打开方式」是独立任务，不随本 Activity 结束而消失。
 *
 * #### 后台启动 Activity 的限制不适用
 * Android 10+ 限制「后台应用启动 Activity」，但本 Activity 由**用户点击通知**触发，
 * 属于用户交互，不在限制范围内。
 */
class OpenSaveDirActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handled = SaveDirManager.openDir(this, forceChooser = false)
        if (!handled) {
            // 设备上没有任何应用能处理「目录」类型的 Intent 时，必须给出明确反馈，
            // 否则用户点击通知后会「什么也没发生」，无从判断是失败还是卡住。
            Toast.makeText(this, "未找到可打开文件夹的应用", Toast.LENGTH_LONG).show()
        }
        DebugLogger.log("OPEN_SAVE_DIR", "通知点击打开保存目录: handled=$handled")
        finish()
    }
}
