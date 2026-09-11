package com.lelecz.reply.ai

import com.lelecz.reply.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 商汤 SenseNova（OpenAI 兼容接口）客户端。
 * 用法：设置页填 API Key → generate 生成 3 条建议回复。
 */
class AiClient(private val settings: SettingsStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 生成建议回复列表，风格按 settings 的 craziness / flirty 调节 */
    suspend fun generateReplies(
        chatContext: List<String>,   // 最近聊天记录（最后一条是对方消息）
        lastMessage: String
    ): List<String> = withContext(Dispatchers.IO) {
        val key = settings.apiKey
        if (key.isBlank()) throw Exception("请先在设置页填写 API Key")

        // Key 校验：必须是 sk- 开头的纯 ASCII 密钥，不是 URL
        if (key.startsWith("http") || !key.all { it.code < 128 } || !key.startsWith("sk-")) {
            throw Exception("API Key 格式不对：应填 sk- 开头的密钥，不要填网址")
        }

        val prompt = PromptBuilder.build(
            craziness = settings.craziness,
            flirty = settings.flirty,
            chatContext = chatContext,
            lastMessage = lastMessage
        )

        val payload = JSONObject()
            .put("model", settings.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt.system))
                .put(JSONObject().put("role", "user").put("content", prompt.user)))
            .put("temperature", 0.95)
            .put("max_tokens", 200)

        val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                // 限流/错误：抛给上层友好提示
                throw Exception("HTTP ${resp.code}: ${errBody.take(120)}")
            }
            val body = resp.body?.string() ?: ""
            parseReplies(body)
        }
    }

    private fun parseReplies(body: String): List<String> {
        val json = JSONObject(body)
        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?: return emptyList()

        // 期望模型输出用换行或序号分隔的多条建议；逐行清洗
        return content.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("^\\d+[.、)]\\s*"), "").trim() }
            .filter { it.isNotBlank() && it.length <= 60 }
            .take(3)
    }
}

data class BuiltPrompt(val system: String, val user: String)

/**
 * 提示词构造：抽象逆天思维回路 + 软情趣，按强度缩放。
 */
object PromptBuilder {

    fun build(craziness: Int, flirty: Int, chatContext: List<String>, lastMessage: String): BuiltPrompt {
        val crazy = craziness.coerceIn(1, 5)
        val flirt = flirty.coerceIn(1, 10)

        // 抽象逆天等级描述
        val crazyDesc = when (crazy) {
            1 -> "回复正常、礼貌、简洁。"
            2 -> "偶尔玩梗，语气轻松。"
            3 -> "脑回路清奇，爱接梗，会用离谱但好笑的比喻，带点阴阳怪气。"
            4 -> "非常抽象：跳脱的脑回路、无厘头比喻、网络热梗、一本正经胡说八道。"
            else -> "极致抽象：思维完全逆天，用最离谱的联想回答最普通的问题，梗密度拉满，令人蚌埠住。"
        }

        // 软情趣等级描述（1~10）
        val flirtDesc = when (flirt) {
            1 -> "保持正常朋友距离，不暧昧。"
            2 -> "语气亲切，偶尔撒娇。"
            3 -> "带一点软软的暧昧感，会撩但不过火，像互有好感的朋友。"
            4 -> "明显打情骂俏，暧昧拉满，但保持可爱不低俗。"
            5 -> "暧昧浓度再升一档：主动撩拨、俏皮挑逗，半真半假地开撩。"
            6 -> "大胆示好：甜腻腻地撒娇、若即若离的试探，把对方撩得心痒。"
            7 -> "挑逗升级：带暗示的双关、坏笑着逗弄，让对话充满来电感。"
            8 -> "火花四溅：用俏皮又露骨的双关和暧昧挑逗步步紧逼，氛围感拉满。"
            9 -> "极致撩拨：几乎明示的暧昧挑逗、带诱惑意味的暗示，只差捅破窗户纸。"
            else -> "撩人天花板：挑逗、撩拨、暧昧暗示全开，字字带着电流，让人招架不住。"
        }

        val system = """
            你是一个群聊气氛组高手，平时在朋友的群里聊天。你的任务是：对群里最新一条消息，给出【3条】可以直接发送的回复建议。
            要求：
            1. $crazyDesc
            2. $flirtDesc
            3. 每条回复不超过 40 字，口语化，像真人打字，不要有"作为AI""好的呢"这类机器感。
            4. 不要客套、不要解释，直接给 3 条回复，每条一行。
            5. 适当使用 emoji，但不要每个都用。
            6. 暧昧挑逗要含蓄风趣有分寸，绝不低俗下流，不做露骨描写。
        """.trimIndent()

        val user = buildString {
            append("群聊最近记录：\n")
            chatContext.takeLast(8).forEach { append(it).append("\n") }
            append("\n最新消息：").append(lastMessage)
            append("\n\n请给出 3 条回复建议：")
        }

        return BuiltPrompt(system, user)
    }
}
