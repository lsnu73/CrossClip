package com.crossclip.app.receiver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.crossclip.app.service.SyncForegroundService

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!selectedText.isNullOrEmpty()) {
            SyncForegroundService.instance?.sendTextManual(selectedText)
        }
        finish()
    }
}
