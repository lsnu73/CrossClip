package com.crossclip.app.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.crossclip.app.R
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
    private const val KEY_PREF_DIR_OPENER = "preferred_dir_opener"

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
     * 构造可打开当前保存目录的候选 Intent，顺序为「自定义目录 → 精确默认目录 → 兜底下载目录」。
     *
     * 注意：Android 没有「打开任意目录」的官方标准 Intent，且 FileProvider 无法为它自己配置的
     * 根目录生成 URI（对根目录本身调用 getUriForFile 会抛 StringIndexOutOfBoundsException），
     * 因此这里统一使用外部存储 SAF 文档 URI。
     *
     * 目录 MIME 有三套互不覆盖的注册口径：系统 DocumentsUI 认 `vnd.android.document/directory`，
     * 而大量 OEM/第三方文件管理器只注册了 `resource/folder`（实测 vivo 自带文件管理不在前者的
     * 解析结果里），另有部分管理器（如 Total Commander）只注册 `application/x-directory`。
     * 每个目录都要生成三种 MIME 的候选，缺哪套哪类应用就不出现。
     */
    private fun buildDirIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val dirMimes = arrayOf(
            DocumentsContract.Document.MIME_TYPE_DIR, // vnd.android.document/directory
            "resource/folder",
            "application/x-directory"
        )
        // 优先：用户授权过的自定义目录（SAF tree URI，只支持目录 MIME 这一种形态）
        getCustomDirUri(context)?.let { treeUri ->
            intents += Intent(Intent.ACTION_VIEW).apply {
                // 必须转成「挂在 tree 授权下的 document URI」再外发：裸 tree URI 只有
                // DocumentsContract 的 tree API 认识，第三方管理器按 document 形态解析
                // （getDocumentId 要求路径含 /document/ 段）会直接失败并静默退出 ——
                // startActivity 发射后不管，日志只会记「已调起」看不出对端死活。
                // buildDocumentUriUsingTree 生成的 URI 内嵌 tree 前缀，授权可随 flag
                // 转发，document 路径形态才是各管理器（MT「定位所在位置」等）认识的
                setDataAndType(
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                    ),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        // 默认目录：Download/CrossClip；兜底：下载根目录（个别 ROM 不支持直接定位到子目录）
        for (documentId in arrayOf("primary:Download/$DEFAULT_SUBDIR", "primary:Download")) {
            for (mime in dirMimes) {
                intents += Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId),
                        mime
                    )
                    // 注意绝不能加 FLAG_GRANT_READ_URI_PERMISSION：grant flag 只能转发
                    // 调用方自己持有的 URI 访问权，而这些 document URI 是字符串合成的，
                    // 本应用对它们没有任何 SAF 授权（写 Download 走的是 File API 豁免通道，
                    // 与 SAF 授权体系无关）——加 flag 会让 startActivity 对所有目标直接抛
                    // SecurityException（连系统 DocumentsUI 也被误伤）。不加 flag 时只有
                    // 特权系统组件（DocumentsUI）能打开；第三方管理器要访问目录必须走
                    // SAF 授权（即下方自定义目录通道，那条候选是带 flag 的）
                }
            }
        }
        return intents
    }

    /** OEM 文件管理器的包名/类名特征：命中者排在选择列表最前，避免被网盘、浏览器类噪音淹没 */
    private val FILE_MANAGER_HINTS = listOf(
        "filemanager", "fileexplorer", "file_manager", "filebrowser",
        "documentsui", "myfiles", "esfileexplorer", "filemaster"
    )

    /** 「打开保存目录」的一个候选应用 */
    class DirOpener(val label: CharSequence, val icon: Drawable, val component: ComponentName)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 用户上次在选择列表中选中的默认打开方式（null 表示尚未选择） */
    fun getPreferredOpener(context: Context): ComponentName? {
        val saved = prefs(context).getString(KEY_PREF_DIR_OPENER, null) ?: return null
        val parts = saved.split("/", limit = 2)
        if (parts.size != 2) return null
        return ComponentName(parts[0], parts[1])
    }

    /**
     * 是否为可免授权打开「合成 document URI」的系统文档组件（DocumentsUI）。
     * 默认目录（Download/CrossClip）本应用未持有 SAF 授权，只有这类特权组件能打开；
     * 第三方管理器必然访问失败，调用方可据此向用户提示「改用自定义目录授权」的出路。
     */
    fun isPrivilegedDirOpener(component: ComponentName): Boolean =
        component.packageName.contains("documentsui")

    /** 记住用户选中的默认打开方式 */
    fun setPreferredOpener(context: Context, component: ComponentName) {
        prefs(context).edit()
            .putString(KEY_PREF_DIR_OPENER, "${component.packageName}/${component.className}")
            .apply()
    }

    /**
     * 解析所有「能打开保存目录」的应用。
     *
     * 合并**全部**候选 Intent 的解析结果（去重）并按文件管理器特征排序。目录 MIME 有三套
     * 互不覆盖的注册口径：系统 DocumentsUI 认 `vnd.android.document/directory`，而大量 OEM/
     * 第三方文件管理器只注册 `resource/folder`（实测 vivo 自带文件管理不在前者的解析结果里），
     * 还有部分管理器只注册 `application/x-directory` —— 三套都要探测并取并集。
     * 旧实现「取第一个有结果的 Intent 就返回」会让只认其他口径的应用永远不出现，
     * 用户在选择列表里只能看到「文件」一个选项。
     *
     * 「自定义目录优先」语义不受影响：候选 Intent 仍按自定义目录 → 精确默认目录 → 兜底下载
     * 目录排序，[launchDirWith] 按同样顺序找到该组件能响应的那一个来调起。
     */
    fun resolveDirOpeners(context: Context): List<DirOpener> {
        val pm = context.packageManager
        data class Entry(val opener: DirOpener, val isFileManager: Boolean)
        val seen = HashSet<String>()
        val entries = mutableListOf<Entry>()
        for (intent in buildDirIntents(context)) {
            val resolvers = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in resolvers) {
                val pkg = ri.activityInfo.packageName
                val cls = ri.activityInfo.name
                if (!seen.add("$pkg/$cls")) continue
                val lowered = "${pkg.lowercase()}/${cls.lowercase()}"
                val isFileManager = FILE_MANAGER_HINTS.any { lowered.contains(it) }
                entries += Entry(
                    DirOpener(
                        label = ri.loadLabel(pm),
                        icon = ri.loadIcon(pm),
                        component = ComponentName(pkg, cls)
                    ),
                    isFileManager
                )
            }
        }
        return entries.sortedByDescending { it.isFileManager }.map { it.opener }
    }

    /**
     * 用指定应用打开当前保存目录。
     *
     * 会遍历候选 Intent 找到该组件能响应的那一个（不同应用注册的 MIME 口径不同），逐个尝试
     * 直到调起成功。
     *
     * @return 是否成功调起
     */
    fun launchDirWith(context: Context, component: ComponentName): Boolean {
        for (intent in buildDirIntents(context)) {
            val resolvers = try {
                pm(context)?.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                null
            }
            val matches = resolvers?.any {
                it.activityInfo.packageName == component.packageName &&
                    it.activityInfo.name == component.className
            } == true
            if (!matches) continue
            try {
                val target = Intent(intent).setComponent(component)
                if (context !is Activity) {
                    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(target)
                } catch (se: SecurityException) {
                    // 个别 ROM 对 grant flag 的发送方校验更激进：即使我们持有持久授权也可能
                    // 误判，剥掉授权标志兜底重试一次（系统 DocumentsUI 本就不需要 flag）
                    if (target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) throw se
                    target.flags = target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
                    context.startActivity(target)
                    DebugLogger.log(TAG, "去除 URI 授权标志后重试调起成功 (${component.packageName})")
                }
                DebugLogger.log(TAG, "已用 ${component.packageName} 打开保存目录: ${getDisplayPath(context)}")
                return true
            } catch (e: Exception) {
                DebugLogger.log(TAG, "用 ${component.packageName} 打开目录失败: ${e.message}")
            }
        }
        return false
    }

    private fun pm(context: Context) = context.packageManager

    /**
     * 「打开保存目录」的统一入口。
     *
     * 优先用用户记住的默认应用直接打开（成功后回调 [onOpened]）；没有默认应用、默认应用已
     * 卸载/失效，或 [forceChooser] 为 true（长按重新选择）时，回调 [onNeedChoose] 交给调用方
     * 弹出选择列表 —— 系统 chooser 拿不到「用户选了哪个」，无法记住默认应用，所以选择列表
     * 必须用应用内对话框实现；用户选中后调 [setPreferredOpener] + [launchDirWith] 完成闭环。
     *
     * @return false 表示没有任何应用可处理（调用方需提示用户）；true 表示已直接打开或已回调
     */
    fun openDir(
        context: Context,
        forceChooser: Boolean = false,
        onOpened: () -> Unit = {},
        onNeedChoose: (List<DirOpener>) -> Unit
    ): Boolean {
        val openers = resolveDirOpeners(context)
        if (openers.isEmpty()) {
            DebugLogger.log(TAG, "没有可打开保存目录的应用（候选目录均无法调起）")
            return false
        }

        val preferred = getPreferredOpener(context)
        if (!forceChooser && preferred != null) {
            val stillValid = openers.any { it.component == preferred }
            if (stillValid && launchDirWith(context, preferred)) {
                onOpened()
                return true
            }
            DebugLogger.log(TAG, "默认打开方式已失效: $preferred，改为弹出选择列表")
        }
        onNeedChoose(openers)
        return true
    }

    /**
     * 弹出「选择打开保存目录的应用」对话框（图标 + 应用名的列表）。
     *
     * 用应用内对话框而不是系统 chooser 的原因见 [openDir]：需要捕获用户的选择以记住默认应用。
     * setItems 只支持纯文本，图标需要自定义行布局（item_dir_opener）。
     */
    fun showOpenerPickerDialog(
        activity: Activity,
        openers: List<DirOpener>,
        onPicked: (DirOpener) -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        val adapter = object : ArrayAdapter<DirOpener>(activity, R.layout.item_dir_opener, openers) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.item_dir_opener, parent, false)
                val opener = getItem(position)!!
                view.findViewById<ImageView>(R.id.iv_opener_icon).setImageDrawable(opener.icon)
                view.findViewById<TextView>(R.id.tv_opener_label).text = opener.label
                return view
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择打开保存目录的应用")
            .setAdapter(adapter) { _, which ->
                onPicked(openers[which])
            }
            .setNegativeButton("取消", null)
            .show()
        // 选中与取消都会触发 dismiss：调用方（如透明跳板 Activity）借此完成自身收尾
        dialog.setOnDismissListener { onDismiss() }
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
