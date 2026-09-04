package com.crossclip.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent

class CrossClipAccessibilityService : AccessibilityService() {

    private lateinit var clipboardManager: ClipboardManager

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 捕获系统所有视图焦点的复制事件 / 文本变化事件
        if (event == null) return
        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            tryCheckClipboard()
        }
    }

    private fun tryCheckClipboard() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty()) {
                    SyncForegroundService.instance?.let { service ->
                        // 自动触发同步
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
