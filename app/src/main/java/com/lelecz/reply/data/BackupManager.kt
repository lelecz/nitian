package com.lelecz.reply.data

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置备份与恢复：把 API 配置、风格、人设等设置（可选含统计）
 * 打包成 JSON 文件，或从 JSON 文件恢复。
 */
class BackupManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val stats: StatsManager
) {

    companion object {
        const val APP_TAG = "ReReply"
        const val BACKUP_VERSION = 1
        const val DEFAULT_FILENAME = "nitian_settings_backup.json"
    }

    /** 生成备份 JSON 文本 */
    fun buildBackupJson(includeStats: Boolean = true): String {
        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("version", BACKUP_VERSION)
        root.put("exportedAt",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))

        // 设置
        val settingsJson = JSONObject()
        settings.exportAll().forEach { (k, v) ->
            when (v) {
                null -> settingsJson.put(k, JSONObject.NULL)
                is String -> settingsJson.put(k, v)
                is Int -> settingsJson.put(k, v)
                is Boolean -> settingsJson.put(k, v)
                is Long -> settingsJson.put(k, v)
                is Float -> settingsJson.put(k, v.toDouble())
                else -> settingsJson.put(k, v.toString())
            }
        }
        root.put("settings", settingsJson)

        // 统计（可选）
        if (includeStats) {
            val statsJson = JSONObject()
            stats.export().forEach { (k, v) ->
                when (v) {
                    is Int -> statsJson.put(k, v)
                    is Long -> statsJson.put(k, v)
                    is Boolean -> statsJson.put(k, v)
                    else -> statsJson.put(k, v.toString())
                }
            }
            root.put("stats", statsJson)
        }

        return root.toString(2)
    }

    /** 从 JSON 文本恢复，返回恢复了哪些内容的描述；失败抛异常 */
    fun restoreFromJson(jsonText: String): String {
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw Exception("文件不是有效的备份（JSON 解析失败）")
        }

        if (root.optString("app") != APP_TAG) {
            throw Exception("这不是逆天回复的备份文件")
        }

        var restoredSettings = 0
        var restoredStats = 0

        val settingsJson = root.optJSONObject("settings")
        if (settingsJson != null) {
            val map = mutableMapOf<String, Any?>()
            val keys = settingsJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = settingsJson.get(k)
                if (v == JSONObject.NULL) {
                    map[k] = ""
                } else {
                    map[k] = v
                }
                restoredSettings++
            }
            settings.importAll(map)
        }

        val statsJson = root.optJSONObject("stats")
        if (statsJson != null) {
            val map = mutableMapOf<String, Any>()
            val keys = statsJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = statsJson.get(k)
                when (v) {
                    is Int -> { map[k] = v; restoredStats++ }
                    is Long -> { map[k] = v; restoredStats++ }
                    is Boolean -> { map[k] = v; restoredStats++ }
                }
            }
            if (map.isNotEmpty()) stats.import(map)
        }

        return "已恢复设置 $restoredSettings 项" +
            if (restoredStats > 0) "、统计 $restoredStats 项" else ""
    }
}
