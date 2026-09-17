package com.crossclip.app.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * 保存目录管理器 —— 统一管理「接收文件」的落地位置。
 *
 * 支持两种模式：
 *  1. **默认模式**：系统下载目录下的 CrossClip 子文件夹（`Download/CrossClip`），
 *     走 File API 直接读写，无需任何授权（Android 11+ 对 Download 目录放行 File 写入）；
 *  2. **自定义模式**：用户通过 SAF（系统文件选择器）任意指定目录，
 *     以持久化 `content://` URI 授权写入，规避 Android 10+ 分区存储对任意路径的写限制。
 *
 * 设计要点：
 *  - 自定义目录一律通过 ContentResolver 写入，不依赖存储权限；
 *  - 配置持久化在 `cross_clip_config` SharedPreferences 中，服务重启后依然生效。
 */
object SaveDirManager {

    private const val TAG = "SaveDirManager"
    private const val PREF_NAME = "cross_clip_config"
    private const val KEY_CUSTOM_DIR_URI = "custom_save_dir_uri"
    private const val KEY_CUSTOM_DIR_LABEL = "custom_save_dir_label"

    /** 默认模式下的子目录名（位于系统下载目录内） */
    private const val DEFAULT_SUBDIR = "CrossClip"

    /** 无扩展名时创建 SAF 文档使用的兜底 MIME */
    private const val FALLBACK_MIME = "application/octet-stream"

    /** 外部存储 SAF 文档提供者（DocumentsUI / 文件管理器据此解析 `primary:Download/...` 文档 ID） */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    // ==================== 目录配置读写 ====================

    /**
     * 获取默认保存目录：`<外部存储>/Download/CrossClip`
     * 注意：这里只返回 File 句柄，不保证已创建，调用方按需 mkdirs()
     */
    fun getDefaultDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DEFAULT_SUBDIR
        )
    }

    /** 读取用户自定义目录的持久化 SAF URI，未设置时返回 null */
    fun getCustomDirUri(context: Context): Uri? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_CUSTOM_DIR_URI, null) ?: return null
        return try {
            Uri.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    /** 是否处于自定义目录模式 */
    fun hasCustomDir(context: Context): Boolean = getCustomDirUri(context) != null

    /**
     * 保存用户选择的自定义目录。
     * 关键步骤：必须 takePersistableUriPermission，否则重启后 URI 授权失效无法写入。
     */
    fun setCustomDir(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        // 持久化读 + 写权限，保证进程重启后仍可写入该目录
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            // 部分厂商 ROM 或已授权的 URI 会抛异常，此处不阻断主流程
            Log.w(TAG, "持久化 URI 授权失败(可忽略): ${e.message}")
        }

        val label = queryDirDisplayName(context, uri) ?: uri.lastPathSegment ?: "自定义目录"
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_DIR_URI, uri.toString())
            .putString(KEY_CUSTOM_DIR_LABEL, label)
            .apply()

        Log.i(TAG, "已设置自定义保存目录: $label ($uri)")
        DebugLogger.log(TAG, "已设置自定义保存目录: $label, uri=$uri")
    }

    /** 恢复为默认目录（清除自定义配置） */
    fun resetToDefault(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CUSTOM_DIR_URI)
            .remove(KEY_CUSTOM_DIR_LABEL)
            .apply()
        DebugLogger.log(TAG, "已恢复默认保存目录: ${getDefaultDir().absolutePath}")
    }

    /**
     * 获取当前保存目录的展示文本（用于 UI 显示）。
     * 默认模式展示人类可读的绝对路径；自定义模式展示目录名 + 授权来源。
     */
    fun getDisplayPath(context: Context): String {
        val customUri = getCustomDirUri(context)
        if (customUri != null) {
            val label = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_DIR_LABEL, null)
            val name = label ?: queryDirDisplayName(context, customUri) ?: "自定义目录"
            return "$name（自定义目录）"
        }
        // 默认目录：拼成「内部存储/Download/CrossClip」这种直观形式
        return "内部存储/Download/$DEFAULT_SUBDIR"
    }

    // ==================== 文件落盘 ====================

    /**
     * 将临时文件保存到当前配置的目标目录。
     *
     * @param context    上下文
     * @param filename   期望的文件名（会自动做安全化与重名规避）
     * @param sourceFile 已完成接收的临时文件
     * @return 保存成功时返回最终文件的展示路径；失败返回 null
     */
    fun saveFile(context: Context, filename: String, sourceFile: File): String? {
        val safeName = sanitizeFilename(filename)
        return try {
            val customUri = getCustomDirUri(context)
            if (customUri != null) {
                saveToCustomDir(context, customUri, safeName, sourceFile)
            } else {
                saveToDefaultDir(safeName, sourceFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存文件失败: ${e.message}", e)
            DebugLogger.log(TAG, "保存文件到目标目录失败: ${filename}, ${e.message}", e)
            null
        }
    }

    /**
     * 默认模式落盘：直接 File API 复制到 `Download/CrossClip`
     */
    private fun saveToDefaultDir(filename: String, sourceFile: File): String? {
        val dir = getDefaultDir()
        if (!dir.exists() && !dir.mkdirs()) {
            DebugLogger.log(TAG, "创建默认目录失败: ${dir.absolutePath}")
            return null
        }
        val target = uniqueFile(File(dir, filename))
        sourceFile.copyTo(target, overwrite = false)
        Log.i(TAG, "文件已保存(默认目录): ${target.absolutePath}")
        DebugLogger.log(TAG, "文件已保存(默认目录): ${target.absolutePath}")
        return target.absolutePath
    }

    /**
     * 自定义模式落盘：通过 SAF DocumentsContract 在用户所选目录下创建文件并写入。
     *
     * 采用「先查已有子项名称集合 → 生成不重名 → 创建文档 → 流式拷贝」的流程，
     * 因为 SAF 没有原生的「重名自动加序号」能力，需要自行实现。
     */
    private fun saveToCustomDir(context: Context, treeUri: Uri, filename: String, sourceFile: File): String? {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

        // 1. 查询目录下已有文件名，用于规避重名
        val existingNames = queryChildNames(context, dirUri)
        val finalName = uniqueName(filename, existingNames)

        // 2. 在目标目录创建文档（SAF 会同时创建实际文件）
        val mime = guessMimeType(finalName)
        val newDocUri = DocumentsContract.createDocument(resolver, dirUri, mime, finalName)
            ?: run {
                DebugLogger.log(TAG, "SAF 创建文档失败(可能无写权限): $finalName")
                return null
            }

        // 3. 流式写入内容
        resolver.openOutputStream(newDocUri)?.use { out ->
            sourceFile.inputStream().use { input ->
                input.copyTo(out)
            }
        } ?: run {
            DebugLogger.log(TAG, "SAF 打开输出流失败: $newDocUri")
            return null
        }

        val label = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_DIR_LABEL, "自定义目录")
        val displayPath = "$label/$finalName"
        Log.i(TAG, "文件已保存(自定义目录): $displayPath")
        DebugLogger.log(TAG, "文件已保存(自定义目录): $displayPath")
        return displayPath
    }

    // ==================== 用其他应用打开目录 ====================

    /**
     * 打开当前保存目录（对齐 LocalSend 的方案：隐式 Intent 交系统 resolver）。
     *
     * Android 没有「打开任意目录」的官方标准 Intent，可行路径只有两条：
     *  - **自定义目录**：本应用持有 SAF 持久授权，把「挂在 tree 授权下的 document URI」
     *    （裸 tree URI 第三方解析不了，见 §7 #20）以隐式 VIEW Intent 外发并转发读授权，
     *    候选枚举 / 图标 / 「仅此一次/总是」记住默认全部交给系统「打开方式」——resolver
     *    在系统侧解析，不受本应用包可见性约束（§7 #18），第三方管理器（MT「定位所在
     *    位置」等）可正常打开。此前自绘选择列表 + 三套 MIME 探测 + 自记默认的全套机制
     *    在排障三层后仍有目标静默退出，整体废弃（§7 #21）。
     *  - **默认目录**：document URI 是合成的、本应用无 SAF 授权可转发（§7 #19），第三方
     *    必然访问失败，只有特权系统组件 DocumentsUI（「文件」）能开 —— 直接显式调起它，
     *    不弹一堆必然失败的候选。
     *
     * @param forceChooser true（长按）时用系统选择框强制重选；不影响系统里已设的「总是」默认
     * @return 是否成功调起（false 时调用方应提示用户）
     */
    fun openDir(context: Context, forceChooser: Boolean = false): Boolean {
        val customUri = getCustomDirUri(context)
        if (customUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    DocumentsContract.buildDocumentUriUsingTree(
                        customUri, DocumentsContract.getTreeDocumentId(customUri)
                    ),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                // 自定义目录有 SAF 持久授权在握，grant flag 转发安全（§7 #19）
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // createChooser 同样会把 target 的 grant flag 转发给用户选中的目标（API 24+）
            val toStart = if (forceChooser) {
                Intent.createChooser(intent, "选择打开保存目录的应用")
            } else {
                intent
            }
            return startDirActivity(context, toStart, "系统「打开方式」")
        }

        // 默认目录：唯一确定能打开的是系统「文件」（DocumentsUI，特权组件），直接调起
        findDocumentsUi(context)?.let { component ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    DocumentsContract.buildDocumentUri(
                        EXTERNAL_STORAGE_AUTHORITY, "primary:Download/$DEFAULT_SUBDIR"
                    ),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                setComponent(component)
            }
            return startDirActivity(context, intent, "系统「文件」(${component.packageName})")
        }
        // 极少数精简 ROM 没有 DocumentsUI：退回隐式调起，能开就开
        DebugLogger.log(TAG, "未找到系统文档组件（DocumentsUI），隐式调起默认目录")
        val fallback = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                DocumentsContract.buildDocumentUri(
                    EXTERNAL_STORAGE_AUTHORITY, "primary:Download/$DEFAULT_SUBDIR"
                ),
                DocumentsContract.Document.MIME_TYPE_DIR
            )
        }
        return startDirActivity(context, fallback, "隐式")
    }

    /** 查询系统文档组件（DocumentsUI，包名含 documentsui）中能开目录的那个 Activity */
    private fun findDocumentsUi(context: Context): ComponentName? {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Download"),
                DocumentsContract.Document.MIME_TYPE_DIR
            )
        }
        val resolvers = try {
            context.packageManager.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        return resolvers.firstOrNull { it.activityInfo.packageName.contains("documentsui") }
            ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    private fun startDirActivity(context: Context, intent: Intent, via: String): Boolean {
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DebugLogger.log(TAG, "已发起打开保存目录 ($via): ${getDisplayPath(context)}")
            true
        } catch (e: Exception) {
            DebugLogger.log(TAG, "打开保存目录失败 ($via): ${e.message}")
            false
        }
    }

    // ==================== 内部工具函数 ====================

    /**
     * 通过 SAF 查询某个目录文档的显示名称。
     * 用于把 `content://...tree/primary%3ADownload` 这类难看 URI 转成 "Download"。
     */
    private fun queryDirDisplayName(context: Context, treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询目录显示名失败: ${e.message}")
            null
        }
    }

    /** 查询某目录已知的子项（可以是文件或目录） */
    private fun queryChildNames(context: Context, dirDocumentUri: Uri): Set<String> {
        val names = mutableSetOf<String>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                dirDocumentUri,
                DocumentsContract.getDocumentId(dirDocumentUri)
            )
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { names.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询目录子项失败: ${e.message}")
        }
        return names
    }

    /** 生成不与已有名称冲突的文件名（`a.txt` → `a (1).txt`） */
    private fun uniqueName(filename: String, existing: Set<String>): String {
        if (!existing.contains(filename)) return filename
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        for (i in 1..9999) {
            val candidate = "$stem ($i)$ext"
            if (!existing.contains(candidate)) return candidate
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    /** 在 File 目录下生成不冲突的文件句柄 */
    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val name = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        val parent = file.parentFile ?: return file
        for (i in 1..9999) {
            val candidate = File(parent, "$name ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(parent, "${name}_${System.currentTimeMillis()}$ext")
    }

    /** 文件名安全化：剔除路径分隔符与控制字符 */
    private fun sanitizeFilename(name: String): String {
        val safe = name
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("[\\x00-\\x1f]"), "_")
        return if (safe.isBlank()) "unnamed_file" else safe
    }

    /** 依据扩展名粗略推断 MIME（SAF 建文档时需要一个 MIME 类型） */
    private fun guessMimeType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> FALLBACK_MIME
        }
    }
}
