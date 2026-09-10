package com.crossclip.app.receiver

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.util.DebugLogger
import java.io.File
import java.io.FileOutputStream

/**
 * 文件分享接收 Activity：接收其他应用通过系统分享菜单发送的文件，
 * 自动转发到已连接的电脑端。
 *
 * 支持单文件 (ACTION_SEND) 和多文件 (ACTION_SEND_MULTIPLE) 分享。
 */
class ShareReceiveActivity : Activity() {

    companion object {
        private const val CHANNEL_ID_FILE = "crossclip_file_transfer"
        private const val NOTIFICATION_ID_UPLOAD = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val service = SyncForegroundService.instance

        if (service == null || service.connectionState != 1) {
            Toast.makeText(this, "CrossClip 未连接电脑，请先打开 CrossClip 并连接", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        createFileNotificationChannel()

        when (action) {
            Intent.ACTION_SEND -> handleSingleShare(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleShare(intent)
            else -> {
                Toast.makeText(this, "不支持的分享操作", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleSingleShare(intent: Intent) {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "未获取到分享文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val mimeType = intent.type ?: "application/octet-stream"
        val filename = getFilenameFromUri(uri) ?: "shared_file"
        val fileSize = getFileSizeFromUri(uri)

        DebugLogger.log("SHARE_RECEIVE", "收到系统分享单文件: $filename ($fileSize bytes, $mimeType)")

        // 复制到临时目录后上传
        val tempFile = copyUriToTemp(uri, filename)
        if (tempFile != null) {
            startFileUpload(tempFile, filename, mimeType)
        } else {
            Toast.makeText(this, "读取分享文件失败", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleMultipleShare(intent: Intent) {
        val uriList = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (uriList.isNullOrEmpty()) {
            Toast.makeText(this, "未获取到分享文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        DebugLogger.log("SHARE_RECEIVE", "收到系统分享多文件: ${uriList.size} 个文件")

        // 逐个文件上传（串行，简化实现）
        Thread {
            for ((index, uri) in uriList.withIndex()) {
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val filename = getFilenameFromUri(uri) ?: "shared_file_$index"
                val tempFile = copyUriToTemp(uri, filename)
                if (tempFile != null) {
                    startFileUploadSync(tempFile, filename, mimeType)
                }
            }
            runOnUiThread {
                Toast.makeText(this, "全部 ${uriList.size} 个文件已发送", Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }

    /**
     * 异步上传文件到电脑
     */
    private fun startFileUpload(file: File, filename: String, mimeType: String) {
        val service = SyncForegroundService.instance ?: return
        val pcIp = service.currentPcIp
        val pinCode = service.pinCode
        val httpPort = service.currentHttpPort

        if (pcIp.isEmpty() || pinCode.isEmpty()) {
            Toast.makeText(this, "电脑连接信息不完整", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "开始发送: $filename", Toast.LENGTH_SHORT).show()

        // 发出「正在发送」通知
        showUploadProgressNotification(filename, 0)

        com.crossclip.app.network.FileUploader.uploadFile(
            pcIp = pcIp,
            httpPort = httpPort,
            pinCode = pinCode,
            deviceId = service.getLocalDeviceId(),
            file = file,
            filename = filename,
            mimeType = mimeType,
            onProgress = { progress ->
                // 不在 100% 时发进度通知：收尾一律交给 onSuccess / onError，
                // 否则一旦最后的 /file/complete 失败，通知就会永久停在「进度 100%」
                if (progress < 100) {
                    showUploadProgressNotification(filename, progress)
                }
            },
            onSuccess = {
                showUploadCompleteNotification(filename)
                runOnUiThread {
                    Toast.makeText(this, "文件已发送: $filename", Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
            onError = { error ->
                DebugLogger.log("SHARE_RECEIVE", "文件上传失败: $error")
                // 关键修复：失败时同样要更新通知，清除卡在进度条的「正在发送」状态
                showUploadFailedNotification(filename, error)
                runOnUiThread {
                    Toast.makeText(this, "文件发送失败: $error", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        )
    }

    /**
     * 同步上传文件（用于多文件串行上传）
     */
    private fun startFileUploadSync(file: File, filename: String, mimeType: String) {
        val service = SyncForegroundService.instance ?: return
        val pcIp = service.currentPcIp
        val pinCode = service.pinCode
        val httpPort = service.currentHttpPort

        if (pcIp.isEmpty() || pinCode.isEmpty()) return

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultError: String? = null

        // 多文件串行上传：同样给出进度与结果通知（复用单文件的三个通知方法）
        showUploadProgressNotification(filename, 0)

        com.crossclip.app.network.FileUploader.uploadFile(
            pcIp = pcIp,
            httpPort = httpPort,
            pinCode = pinCode,
            deviceId = service.getLocalDeviceId(),
            file = file,
            filename = filename,
            mimeType = mimeType,
            onProgress = { progress ->
                if (progress < 100) showUploadProgressNotification(filename, progress)
            },
            onSuccess = { _ ->
                DebugLogger.log("SHARE_RECEIVE", "多文件上传成功: $filename")
                showUploadCompleteNotification(filename)
                latch.countDown()
            },
            onError = { error ->
                showUploadFailedNotification(filename, error)
                resultError = error
                latch.countDown()
            }
        )

        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        if (resultError != null) {
            DebugLogger.log("SHARE_RECEIVE", "多文件上传失败: $filename, error=$resultError")
        }
    }

    private fun copyUriToTemp(uri: Uri, filename: String): File? {
        return try {
            val tempDir = File(cacheDir, "share_temp")
            tempDir.mkdirs()
            val tempFile = File(tempDir, filename)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            DebugLogger.log("SHARE_RECEIVE", "文件已复制到临时目录: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile
        } catch (e: Exception) {
            DebugLogger.log("SHARE_RECEIVE", "复制分享文件失败: ${e.message}", e)
            null
        }
    }

    private fun getFilenameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        var size = 0L
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0) {
                        size = cursor.getLong(idx)
                    }
                }
            }
        }
        return size
    }

    private fun createFileNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_FILE,
                "CrossClip 文件传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "文件发送进度"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * 发送/更新「正在发送」进度通知。
     * progress == 0 时显示不定进度条（表示刚开始建立连接）。
     */
    private fun showUploadProgressNotification(filename: String, progress: Int) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, CHANNEL_ID_FILE)
                .setContentTitle("📤 正在发送: $filename")
                .setContentText(if (progress == 0) "正在建立传输..." else "进度: $progress%")
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setProgress(100, progress, progress == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID_UPLOAD, notification)
        } catch (e: Exception) {
            DebugLogger.log("SHARE_RECEIVE", "更新发送进度通知失败: ${e.message}")
        }
    }

    /**
     * 发送完成通知。
     * 先 cancel 掉常驻的进度通知，再发一条可自动消失的完成通知，
     * 确保不会残留「进度 100%」的进行中状态。
     */
    private fun showUploadCompleteNotification(filename: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID_UPLOAD)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID_FILE)
                .setContentTitle("✅ 文件发送完成")
                .setContentText("$filename 已成功发送到电脑")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID_UPLOAD, notification)
        } catch (e: Exception) {
            DebugLogger.log("SHARE_RECEIVE", "发送完成通知失败: ${e.message}")
        }
    }

    /**
     * 发送失败通知。
     * 这是「传输到 100% 后通知仍停留在进行中」问题的关键修复：
     * 旧版 onError 分支只弹 Toast、从不更新通知，导致进度通知永久卡住。
     */
    private fun showUploadFailedNotification(filename: String, error: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID_UPLOAD)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID_FILE)
                .setContentTitle("❌ 文件发送失败")
                .setContentText("$filename: $error")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$filename 发送失败：$error"))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID_UPLOAD, notification)
        } catch (e: Exception) {
            DebugLogger.log("SHARE_RECEIVE", "发送失败通知失败: ${e.message}")
        }
    }
}
