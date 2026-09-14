package com.crossclip.app.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.crossclip.app.R
import com.crossclip.app.service.SyncForegroundService

/**
 * 「重新扫描」按钮的扫描中态动画。
 *
 * #### 交互约定
 *  - 点击后箭头开始无限旋转，右侧文案由「重新扫描」切换为「正在扫描中」；
 *  - 原先在屏幕底部弹的 Toast 已移除：它和这里的旋转表达的是同一件事，
 *    重复提示既遮挡视线又是转瞬即逝的，不如持续可见的旋转态直观。
 *
 * #### 为什么动画必须有终点
 * 手动扫描只跑有限轮次（手机端 `LanDiscovery.MANUAL_SCAN_ROUNDS`），
 * 所以这里既监听「已连上电脑」也设了绝对超时。少了任何一个，箭头都可能永远转下去，
 * 用户会误以为程序卡死。
 *
 * 本控制器刻意做成自包含的：由它自己 `findViewById`，MainActivity 不必新增字段与 import。
 */
object ScanRefreshController {

    private const val TAG = "SCAN_REFRESH"

    /** 单圈旋转时长：太快像故障，太慢看不出在转 */
    private const val ROTATE_DURATION_MS = 900L

    /** 绝对超时（毫秒）：超过即强制恢复静止态 */
    private const val MAX_SPIN_MS = 12_000L

    /** 扫描中态的判定轮询间隔 */
    private const val POLL_INTERVAL_MS = 500L

    private val handler = Handler(Looper.getMainLooper())

    private var animator: ObjectAnimator? = null
    private var iconRef: ImageView? = null
    private var labelRef: TextView? = null
    private var elapsedMs = 0L

    /**
     * 用户点击「重新扫描」时调用：进入扫描中态。
     * 重复点击只会重置计时，不会叠加出多个动画器。
     */
    fun onScanClicked(activity: Activity) {
        val icon = activity.findViewById<ImageView>(R.id.iv_refresh_icon) ?: return
        val label = activity.findViewById<TextView>(R.id.tv_refresh_label) ?: return

        iconRef = icon
        labelRef = label

        if (animator == null) {
            animator = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
                duration = ROTATE_DURATION_MS
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        label.text = "正在扫描中"
        startWatchdog()
    }

    /** 轮询「是否已连上电脑」，并在达到绝对超时后无条件收尾 */
    private fun startWatchdog() {
        handler.removeCallbacksAndMessages(null)
        elapsedMs = 0L
        val tick = object : Runnable {
            override fun run() {
                elapsedMs += POLL_INTERVAL_MS
                val connected = SyncForegroundService.instance?.connectionState == 1
                if (connected || elapsedMs >= MAX_SPIN_MS) {
                    stop()
                    return
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.postDelayed(tick, POLL_INTERVAL_MS)
    }

    /** 恢复静止态：停动画、角度归零、文案复原、释放控件引用 */
    private fun stop() {
        handler.removeCallbacksAndMessages(null)
        animator?.cancel()
        animator = null
        iconRef?.rotation = 0f
        labelRef?.text = "重新扫描"
        iconRef = null
        labelRef = null
        elapsedMs = 0L
    }
}
