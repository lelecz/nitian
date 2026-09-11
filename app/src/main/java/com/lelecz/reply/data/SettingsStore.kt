package com.lelecz.reply.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置存储：API Key / Base URL / 模型 / 风格强度 / 开关
 * 全部保存在本地 SharedPreferences，不上传。
 */
class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("reply_settings", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_BASE_URL = "https://token.sensenova.cn/v1"
        const val DEFAULT_MODEL = "sensenova-6.8-flash-lite"
    }

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString("base_url", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    /** 服务总开关 */
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", true)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    /** 抽象逆天程度 1~5 */
    var craziness: Int
        get() = sp.getInt("craziness", 3)
        set(v) = sp.edit().putInt("craziness", v.coerceIn(1, 5)).apply()

    /** 软情趣程度 1~10 */
    var flirty: Int
        get() = sp.getInt("flirty", 2)
        set(v) = sp.edit().putInt("flirty", v.coerceIn(1, 10)).apply()

    /** 只监听指定群（逗号分隔的群名关键词） */
    var groupFilter: String
        get() = sp.getString("group_filter", "") ?: ""
        set(v) = sp.edit().putString("group_filter", v.trim()).apply()

    /** 是否自动发送（true=点悬浮窗直接发；false=点后复制到剪贴板） */
    var autoSend: Boolean
        get() = sp.getBoolean("auto_send", true)
        set(v) = sp.edit().putBoolean("auto_send", v).apply()

    /** 当前正在回复的会话包名（如 com.tencent.mobileqq） */
    var lastChatPackage: String
        get() = sp.getString("last_chat_pkg", "") ?: ""
        set(v) = sp.edit().putString("last_chat_pkg", v).apply()
}
