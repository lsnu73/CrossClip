package com.crossclip.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log

class ClipWriteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra("EXTRA_TEXT") ?: ""
        if (text.isNotEmpty()) {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("CrossClip", text)
                cm.setPrimaryClip(clipData)
                Log.i("CrossClipWriter", "通过前台隐形窗口成功写入剪贴板 (长度: ${text.length})")
            } catch (e: Exception) {
                Log.e("CrossClipWriter", "写入剪贴板异常: ${e.message}")
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
