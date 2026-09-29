package com.lelecz.reply.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 用量统计：回复次数、token 消耗，按日期 / 人设维度记录。
 * 全部保存在本地 SharedPreferences，不上传。
 */
class StatsManager(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("reply_stats", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOTAL_REPLIES = "total_replies"
        private const val KEY_TOTAL_TOKENS = "total_tokens"
        private const val KEY_PREFIX_DAY = "day_"        // day_20260929
        private const val KEY_PREFIX_PERSONA = "persona_" // persona_normal
    }

    private fun dayKey(date: Date = Date()): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        return KEY_PREFIX_DAY + fmt.format(date)
    }

    /** 记录一次实际发送/复制的回复 */
    fun recordReply(personaId: String) {
        sp.edit()
            .putInt(KEY_TOTAL_REPLIES, totalReplies() + 1)
            .putInt(dayKey(), repliesOnDay(Date()) + 1)
            .putInt(KEY_PREFIX_PERSONA + personaId, repliesByPersona(personaId) + 1)
            .apply()
    }

    /** 记录一次 API 调用消耗的 token（total_tokens） */
    fun recordTokens(totalTokens: Int) {
        if (totalTokens <= 0) return
        sp.edit()
            .putInt(KEY_TOTAL_TOKENS, totalTokensConsumed() + totalTokens)
            .apply()
    }

    fun totalReplies(): Int = sp.getInt(KEY_TOTAL_REPLIES, 0)

    fun totalTokensConsumed(): Int = sp.getInt(KEY_TOTAL_TOKENS, 0)

    fun repliesOnDay(date: Date): Int = sp.getInt(dayKey(date), 0)

    fun todayReplies(): Int = repliesOnDay(Date())

    fun repliesByPersona(personaId: String): Int =
        sp.getInt(KEY_PREFIX_PERSONA + personaId, 0)

    /** 最近 n 天的回复数（旧 → 新），返回 (日期标签, 数量) */
    fun recentDays(n: Int = 7): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        val cal = Calendar.getInstance()
        val labelFmt = SimpleDateFormat("MM-dd", Locale.US)
        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        // 从 n-1 天前到今天
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
        repeat(n) {
            val date = cal.time
            val count = sp.getInt(KEY_PREFIX_DAY + keyFmt.format(date), 0)
            result.add(labelFmt.format(date) to count)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    /** 所有人设的回复统计，返回 (personaId, 数量)，按数量降序 */
    fun allPersonaStats(): List<Pair<String, Int>> {
        return sp.all.entries
            .filter { it.key.startsWith(KEY_PREFIX_PERSONA) }
            .map { it.key.removePrefix(KEY_PREFIX_PERSONA) to (it.value as? Int ?: 0) }
            .sortedByDescending { it.second }
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    /** 导出为 Map（用于设置备份） */
    fun export(): Map<String, Any> = sp.all.mapValues { it.value ?: 0 }

    /** 从 Map 导入（合并） */
    fun import(data: Map<String, Any>) {
        val ed = sp.edit()
        data.forEach { (k, v) ->
            when (v) {
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Boolean -> ed.putBoolean(k, v)
            }
        }
        ed.apply()
    }
}
