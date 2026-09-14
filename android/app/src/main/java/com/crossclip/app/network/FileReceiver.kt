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
        var state: String = "preparing", // preparing, receiving, completed, failed
        /** 去重命中：目标目录已有「同名 + 同大小 + 同哈希」的文件，完成时直接复用它 */
        var dedupHitPath: String? = null
    )

    /**
     * 准备接收文件：创建临时文件、登记传输状态，并顺带做一次「接收端去重」预检。
     *
     * #### 去重的开销模型（低开销版）
     * 只做「同名 + 同大小」这两个近乎零成本的判定，两者都命中才为**这一个**文件流式算一次
     * SHA-256；绝不遍历整个目录逐个算哈希 —— 那样在大目录下会不可控地卡住接收线程。
     *
     * @param context      用于取 App 私有缓存目录（必须是 applicationContext，避免持有 Activity 引用）
     * @param expectedHash 发送端给出的整文件 SHA-256；为 null（老版本电脑端）时不做去重
     * @return 是否受理本次接收
     */
    fun prepareReceive(
        context: Context,
        fileId: String,
        filename: String,
        fileSize: Long,
        expectedHash: String? = null
    ): Boolean {
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

            // 去重预检：目标目录已经有同名同大小同内容的文件，就没有必要再收一遍
            val dedupHit = findExistingDuplicate(context, filename, fileSize, expectedHash)
            if (dedupHit != null) {
                DebugLogger.log(TAG, "目标目录已存在相同文件，将跳过落盘并复用: $dedupHit")
            }

            activeTransfers[fileId] = ReceiveTransfer(
                fileId = fileId,
                filename = filename,
                fileSize = fileSize,
                totalChunks = totalChunks,
                tempFile = tempFile,
                dedupHitPath = dedupHit
            )

            DebugLogger.log(TAG, "准备接收文件: $filename ($fileSize bytes, $totalChunks 块)")
            true
        } catch (e: Exception) {
            DebugLogger.log(TAG, "准备接收文件失败: ${e.message}", e)
            false
        }
    }

    /** 本次接收是否命中了去重（供本地 HTTP 层在 prepare 响应里告知发送端不必再传分块） */
    fun isDedupHit(fileId: String): Boolean = activeTransfers[fileId]?.dedupHitPath != null

    /**
     * 在目标保存目录里查找「同名 + 同大小 + 同哈希」的既有文件。
     *
     * 先比大小（读元数据，几乎零成本），只有大小一致才值得付出一次哈希。
     *
     * @return 命中则返回该文件的展示路径，否则 null
     */
    private fun findExistingDuplicate(
        context: Context,
        filename: String,
        expectedSize: Long,
        expectedHash: String?
    ): String? {
        if (expectedHash.isNullOrEmpty()) return null
        return try {
            // 只在默认目录（Download/CrossClip）里做去重：用户若改用 SAF 自定义目录，
            // 列举子项并逐个读取的代价明显更高、收益不成正比，那里直接放弃去重。
            if (SaveDirManager.hasCustomDir(context)) return null

            // 文件名来自对端，必须先安全化再拼路径 —— 否则 `../` 之类可以逃出保存目录。
            // 规则必须与 SaveDirManager 落盘时用的一致，否则永远命不中同一个文件。
            val candidate = File(SaveDirManager.getDefaultDir(), sanitizeFilename(filename))
            if (!candidate.isFile || candidate.length() != expectedSize) return null

            val actualHash = com.crossclip.app.crypto.CryptoUtil.computeHashFile(candidate)
            if (actualHash.equals(expectedHash, ignoreCase = true)) candidate.absolutePath else null
        } catch (e: Exception) {
            DebugLogger.log(TAG, "去重预检失败(按未命中处理): ${e.message}")
            null
        }
    }

    /**
     * 文件名安全化：剔除路径分隔符与控制字符。
     *
     * 与 `SaveDirManager` 落盘时的规则保持一致 —— 两边必须算出同一个文件名，否则去重永远判不中。
     */
    private fun sanitizeFilename(name: String): String {
        val safe = name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("[\\x00-\\x1f]"), "_")
        return if (safe.isBlank()) "unnamed_file" else safe
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

        // 去重命中：目标目录里已经有同名、同大小、同内容的文件。
        // 把收到的临时数据直接丢掉并复用已有文件 —— 无论发送端是否识别了 already_exists
        // 而跳过传输，这条路径都成立。
        transfer.dedupHitPath?.let { existing ->
            transfer.tempFile.delete()
            transfer.state = "completed"
            DebugLogger.log(TAG, "文件已存在，跳过落盘并复用: $existing")
            return existing
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
