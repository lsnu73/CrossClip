package com.crossclip.app.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「今日已同步 N 条 · 上次 N 分钟前」统计行的数据来源。
 *
 * 只统计**真正完成**的同步事件（发送成功 / 接收成功写入 / 文件传输成功终态），
 * 不把「尝试发送」「收到但校验失败」计入，避免统计行虚高。
 *
 * 计数按自然日重置：日期变化后的第一条记录会把当日计数清零再累加；
 * 「上次同步时间」跨天保留，用于相对时间展示。
 */
object SyncStats {

    private const val PREFS = "sync_stats"
    private const val KEY_DATE = "date"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST_AT = "last_at"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** 登记一次已完成的同步事件（可在非主线程调用） */
    @JvmStatic
    fun record(context: Context, count: Int = 1) {
        if (count <= 0) return
        synchronized(this) {
            val sp = prefs(context)
            val today = todayKey()
            val current = if (sp.getString(KEY_DATE, "") == today) sp.getInt(KEY_COUNT, 0) else 0
            sp.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_COUNT, current + count)
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .apply()
        }
    }

    data class Snapshot(val todayCount: Int, val lastAt: Long)

    @JvmStatic
    fun snapshot(context: Context): Snapshot {
        val sp = prefs(context)
        val today = todayKey()
        val count = if (sp.getString(KEY_DATE, "") == today) sp.getInt(KEY_COUNT, 0) else 0
        return Snapshot(count, sp.getLong(KEY_LAST_AT, 0L))
    }

    /** 统计行的「上次 …」相对时间文案 */
    @JvmStatic
    fun lastSyncDisplay(lastAt: Long): String {
        if (lastAt <= 0L) return "暂无"
        val diff = System.currentTimeMillis() - lastAt
        return when {
            diff < 10_000L -> "刚刚"
            diff < 60_000L -> "${diff / 1000} 秒前"
            diff < 3_600_000L -> "${diff / 60_000} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000} 小时前"
            else -> SimpleDateFormat("昨天 HH:mm", Locale.getDefault())
                .format(Date(lastAt))
        }
    }
}
