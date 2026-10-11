package com.lelecz.reply.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lelecz.reply.R
import com.lelecz.reply.ai.AiClient
import com.lelecz.reply.data.SettingsStore
import com.lelecz.reply.service.ReplyFloatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** API 页：接口三要素、服务商预设、拉取模型、超时、连接测试 */
class ApiActivity : BaseActivity() {

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        val container = createPage()
        buildUi(container)
        playEnter(container)
    }

    override fun onResume() {
        super.onResume()
        // 收起悬浮窗，避免遮挡弹窗/系统界面
        ReplyFloatService.hide(this)
    }

    private fun buildUi(container: LinearLayout) {
        container.addView(pageHeader("API 设置"))

        val card1 = card()
        card1.addView(sectionTitle("接口配置"))
        val keyInput = EditText(this).apply {
            hint = "API Key（从你的服务商获取）"
            setText(settings.apiKey)
            setSingleLine(true)
            qInput()
        }
        card1.addView(field("API Key", keyInput))
        val urlInput = EditText(this).apply {
            hint = "https://xxx/v1 或其他地址"
            setText(settings.baseUrl)
            setSingleLine(true)
            qInput()
        }
        card1.addView(field("Base URL", urlInput))
        val modelInput = EditText(this).apply {
            hint = "模型名（如 gpt-4、glm-4 等）"
            setText(settings.model)
            setSingleLine(true)
            qInput()
        }
        card1.addView(field("模型", modelInput))
        card1.addView(spacer(dp(6)))

        // 服务商预设快捷按钮
        val presetLabel = TextView(this).apply {
            text = "一键预设（点一下自动填入）"
            textSize = 12.5f
            setTextColor(cTextLight)
            setPadding(dp(4), 0, 0, dp(6))
        }
        card1.addView(presetLabel)
        val presetScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = listOf(
            Triple("商汤", "https://token.sensenova.cn/v1", "sensenova-6.8-flash-lite"),
            Triple("火山引擎", "https://ark.cn-beijing.volces.com/api/v3", "doubao-1-5-pro-32k-250115"),
            Triple("智谱AI", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            Triple("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        )
        presets.forEach { (name, url, model) ->
            val btn = Button(this).apply {
                text = name
                textSize = 12f
                setTextColor(cPinkDark)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = resources.getDrawable(R.drawable.bg_q_btn_soft, null)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    urlInput.setText(url)
                    modelInput.setText(model)
                    Toast.makeText(this@ApiActivity, "已填入 $name 预设", Toast.LENGTH_SHORT).show()
                }
                qbounce()
            }
            presetRow.addView(btn)
        }
        presetScroll.addView(presetRow)
        card1.addView(presetScroll)
        card1.addView(spacer(dp(10)))

        // 保存 + 拉取模型列表：并排一组
        val saveBtn = qBtn("保存设置", true)
        val fetchModelsBtn = qBtn("拉取模型", false, true)
        fetchModelsBtn.setOnClickListener {
            // 用输入框当前值发请求（Key/URL 可能还没点保存）
            settings.apiKey = keyInput.text.toString()
            settings.baseUrl = urlInput.text.toString()
            fetchModelsBtn.text = "拉取中…"
            fetchModelsBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val models = AiClient(settings).fetchModels()
                    if (models.isEmpty()) {
                        Toast.makeText(this@ApiActivity, "接口返回了空列表", Toast.LENGTH_LONG).show()
                    } else {
                        showModelPicker(models, modelInput)
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ApiActivity,
                        "拉取失败：${e.message?.take(80) ?: "未知错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                fetchModelsBtn.text = "拉取模型"
                fetchModelsBtn.isEnabled = true
            }
        }
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(saveBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(6) })
        saveRow.addView(fetchModelsBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        card1.addView(saveRow, lp())

        // 响应超时自定义（10~300 秒，步进 10，默认 60 = 原固定值）
        card1.addView(spacer(dp(10)))
        val timeoutBar = sliderRow(card1, "响应超时（10~300 秒）", 29, (settings.timeoutSec / 10) - 1) {
            "${(it + 1) * 10} 秒"
        }

        val testBtn = qBtn("测试 AI 连接", false)
        testBtn.setOnClickListener {
            settings.apiKey = keyInput.text.toString()
            settings.baseUrl = urlInput.text.toString()
            settings.model = modelInput.text.toString()
            settings.timeoutSec = (timeoutBar.progress + 1) * 10
            testBtn.text = "测试中…"
            testBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val t0 = System.currentTimeMillis()
                try {
                    val replies = AiClient(settings).generateReplies(
                        listOf("群友: 在吗"), "在吗"
                    )
                    val cost = System.currentTimeMillis() - t0
                    if (replies.isEmpty()) {
                        Toast.makeText(this@ApiActivity, "连接成功（延迟 ${cost}ms），但模型没返回内容", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@ApiActivity,
                            "连接成功 · 延迟 ${cost}ms\n示例回复：${replies.first()}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    val cost = System.currentTimeMillis() - t0
                    Toast.makeText(this@ApiActivity, "失败（延迟 ${cost}ms）：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
                testBtn.text = "测试 AI 连接"
                testBtn.isEnabled = true
            }
        }
        card1.addView(testBtn, lp())
        container.addView(card1)

        // 保存：仅 API 三要素 + 超时
        saveBtn.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isNotBlank() && key.startsWith("http")) {
                Toast.makeText(
                    this,
                    "注意：API Key 看起来不对：应填密钥，不是网址",
                    Toast.LENGTH_LONG
                ).show()
            }
            settings.apiKey = key
            settings.baseUrl = urlInput.text.toString()
            settings.model = modelInput.text.toString()
            settings.timeoutSec = (timeoutBar.progress + 1) * 10
            Toast.makeText(this, "已保存，喵~", Toast.LENGTH_SHORT).show()
        }

        val hint = TextView(this).apply {
            text = "提示：Base URL 必须以 /v1 结尾、不要带 /chat/completions；模型名不确定就点「拉取模型」选。" +
                "改完建议点「测试 AI 连接」验证（会显示往返延迟）。"
            textSize = 12.5f
            setTextColor(cTextLight)
            setPadding(dp(6), dp(14), dp(6), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        container.addView(hint)
    }

    /** 可滑动模型列表弹窗（液态玻璃）：点选后回填模型输入框并立即保存 */
    private fun showModelPicker(models: List<String>, target: EditText) {
        Glass.builder(this)
            .setTitle("选择模型（共 ${models.size} 个）")
            .setItems(models.toTypedArray()) { _, which ->
                val m = models[which]
                target.setText(m)
                settings.model = m
                Toast.makeText(this, "已选择：$m", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .showGlass()
    }
}
