package com.crossclip.app.network

import android.content.Context
import com.crossclip.app.util.DebugLogger
import com.crossclip.app.util.SaveDirManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件接收器 —— 管理从电脑端接收的文件。
 *
 * 协议流程：
 *  1. [prepareReceive]    — 在 App 私有缓存目录创建临时文件，准备接收；
 *  2. [receiveChunk]      — 逐块写入临时文件；
 *  3. [completeReceive]   — 校验 SHA-256 完整性，再交给 [SaveDirManager] 落盘到用户配置的目录。
 *
 * 设计说明：
 *  - **临时文件一律写在 `cacheDir/file_transfer/`**，不直接写目标目录。
 *    这样即使用户把目标目录设成 SAF 授权目录，接收过程中也不会因为频繁创建/改名而中断；
 *    同时避免半截文件污染用户的下载目录。
 *  - 最终落盘位置由 [SaveDirManager] 决定（默认 `Download/CrossClip`，或用户自定义目录）。
 */
object FileReceiver {

    private const val TAG = "FileReceiver"

    /** 分块大小，必须与发送端保持一致（手机端 FileUploader.CHUNK_SIZE） */
    const val CHUNK_SIZE = 1024 * 1024 // 1MB

    /** 临时中转目录名（位于 App 私有缓存内） */
    private const val TEMP_DIR_NAME = "file_transfer"

    /** 活跃的接收任务表：fileId → 接收状态 */
    private val activeTransfers = ConcurrentHashMap<String, ReceiveTransfer>()

    data class ReceiveTransfer(
        val fileId: String,
        val filename: String,
        val fileSize: Long,
        var receivedBytes: Long = 0,
        var chunksReceived: Int = 0,
        var totalChunks: Int,
        val tempFile: File,
        var state: String = "preparing" // preparing, receiving, completed, failed
    )

    /**
     * 准备接收文件：创建临时文件并登记传输状态。
     *
     * @param context 用于取 App 私有缓存目录（必须是 applicationContext，避免持有 Activity 引用）
     */
    fun prepareReceive(context: Context, fileId: String, filename: String, fileSize: Long): Boolean {
        return try {
            val tempDir = File(context.cacheDir, TEMP_DIR_NAME)
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                DebugLogger.log(TAG, "创建临时目录失败: ${tempDir.absolutePath}")
                return false
            }

            val tempFile = File(tempDir, "$fileId.tmp")
            // 清掉可能残留的同名临时文件（例如上一次中断的传输）
            if (tempFile.exists()) tempFile.delete()

            val totalChunks = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)

            activeTransfers[fileId] = ReceiveTransfer(
                fileId = fileId,
                filename = filename,
                fileSize = fileSize,
                totalChunks = totalChunks,
                tempFile = tempFile
            )

            DebugLogger.log(TAG, "准备接收文件: $filename ($fileSize bytes, $totalChunks 块)")
            true
        } catch (e: Exception) {
            DebugLogger.log(TAG, "准备接收文件失败: ${e.message}", e)
            false
        }
    }

    /**
     * 接收一个数据块并追加写入临时文件。
     *
     * @param data 已解密的原始字节
     * @return Pair(已接收块数, 总块数)；失败返回 null
     */
    fun receiveChunk(
        fileId: String,
        chunkIndex: Int,
        totalChunks: Int,
        data: ByteArray
    ): Pair<Int, Int>? {
        val transfer = activeTransfers[fileId] ?: run {
            DebugLogger.log(TAG, "未知的文件传输 ID: $fileId")
            return null
        }

        return try {
            FileOutputStream(transfer.tempFile, true).use { fos ->
                fos.write(data)
            }

            transfer.receivedBytes += data.size
            transfer.chunksReceived++
            // 以发送端上报的总块数为准，防止两端 CHUNK_SIZE 不一致导致进度偏差
            if (totalChunks > 0) transfer.totalChunks = totalChunks
            transfer.state = "receiving"

            Pair(transfer.chunksReceived, transfer.totalChunks)
        } catch (e: Exception) {
            DebugLogger.log(TAG, "写入文件块失败: ${e.message}", e)
            transfer.state = "failed"
            null
        }
    }

    /**
     * 完成接收：校验哈希 → 落盘到用户配置的目录。
     *
     * @return 保存成功时返回最终文件的展示路径；失败返回 null
     */
    fun completeReceive(context: Context, fileId: String, expectedHash: String): String? {
        val transfer = activeTransfers.remove(fileId) ?: run {
            DebugLogger.log(TAG, "未知的文件传输 ID: $fileId")
            return null
        }

        return try {
            val tempFile = transfer.tempFile
            if (!tempFile.exists()) {
                DebugLogger.log(TAG, "临时文件不存在，接收已中断: ${tempFile.absolutePath}")
                return null
            }

            // 1. 完整性校验：整文件 SHA-256 必须与发送端一致（流式计算，不把整文件读进内存）
            val actualHash = com.crossclip.app.crypto.CryptoUtil.computeHashFile(tempFile)
            if (actualHash != expectedHash) {
                DebugLogger.log(TAG, "文件哈希不匹配: 期望=$expectedHash, 实际=$actualHash")
                tempFile.delete()
                transfer.state = "failed"
                return null
            }

            // 2. 交给目录管理器落盘（默认 Download/CrossClip，或用户自定义目录）
            val savedPath = SaveDirManager.saveFile(context, transfer.filename, tempFile)
            if (savedPath == null) {
                tempFile.delete()
                transfer.state = "failed"
                return null
            }

            // 3. 落盘成功后清理临时文件
            tempFile.delete()
            transfer.state = "completed"
            DebugLogger.log(TAG, "文件接收完成: $savedPath (${transfer.receivedBytes} bytes)")
            return savedPath
        } catch (e: Exception) {
            DebugLogger.log(TAG, "完成文件接收失败: ${e.message}", e)
            transfer.tempFile.delete()
            transfer.state = "failed"
            null
        }
    }

    /** 取消接收并清理临时文件（例如连接中断、用户主动取消） */
    fun cancelReceive(fileId: String) {
        activeTransfers.remove(fileId)?.let {
            it.tempFile.delete()
        }
        DebugLogger.log(TAG, "已取消文件接收: $fileId")
    }

    /** 清理所有残留的临时文件（服务销毁或启动时调用） */
    fun cleanupTempFiles(context: Context) {
        try {
            val tempDir = File(context.cacheDir, TEMP_DIR_NAME)
            tempDir.listFiles()?.forEach { it.delete() }
            activeTransfers.clear()
        } catch (e: Exception) {
            DebugLogger.log(TAG, "清理临时文件失败: ${e.message}")
        }
    }
}
