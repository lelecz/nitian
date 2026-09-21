package com.lelecz.reply.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lelecz.reply.R
import com.lelecz.reply.ai.Persona
import com.lelecz.reply.data.SettingsStore
import com.lelecz.reply.service.ReplyFloatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lelecz.reply.ai.AiClient

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    // Q弹配色
    private val cPink = 0xFFFF8FAB.toInt()
    private val cPinkDark = 0xFFF06E94.toInt()
    private val cPurple = 0xFFB8A9F0.toInt()
    private val cPurpleDark = 0xFF9D8AE8.toInt()
    private val cBlue = 0xFF8EC9F0.toInt()
    private val cBlueDark = 0xFF6FB4E8.toInt()
    private val cText = 0xFF5A4A52.toInt()
    private val cTextLight = 0xFF9B8A92.toInt()
    private val cSection = 0xFFE86A92.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // 回到设置页立即收起悬浮窗，避免遮挡系统权限弹窗
        ReplyFloatService.hide(this)
        // 刷新 OCR 授权状态显示
        findViewById<Button>(R.id.ocrBtn)?.let {
            it.text = if (com.lelecz.reply.service.OcrManager.isAuthorized())
                "🌸 屏幕截图（可选）· 已授权 ✓"
            else
                "🌸 屏幕截图（可选）· 未授权（不影响主功能）"
        }
        // 刷新无障碍守护状态
        findViewById<Button>(R.id.guardBtn)?.let {
            it.text = if (com.lelecz.reply.service.AccessibilityGuard.isEnabled(this))
                "🛡️ 无障碍守护 · 运行中 ✓"
            else
                "🛡️ 无障碍守护 · 已关闭（点我开启）"
        }
        // 刷新电池优化按钮状态
        findViewById<Button>(R.id.batteryBtn)?.let {
            it.text = if (com.lelecz.reply.service.AccessibilityGuard.isIgnoringBatteryOptimizations(this))
                "🔋 电池优化 · 已忽略（防清理 ✓）"
            else
                "🔋 电池优化（防清理必开）"
        }
        // OCR 授权发起后 4 秒内回到前台却没收到结果 → 系统不支持，提示跳过
        if (System.currentTimeMillis() - lastOcrClickTime < 4000 &&
            !com.lelecz.reply.service.OcrManager.isAuthorized()
        ) {
            Toast.makeText(
                this,
                "此设备系统不支持屏幕捕获，跳过即可，不影响主功能",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private var lastOcrClickTime: Long = 0

    private fun buildUi(): View {
        val root = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFFF0F5.toInt(), 0xFFF7F1FF.toInt(), 0xFFEFF6FF.toInt())
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(44))
        }

        // ── 标题区 ──
        container.addView(title())
        container.addView(subTitle("●ᴗ● 群聊整活小助手 · 今天也要元气满满"))
        container.addView(spacer(dp(6)))

        // ── 卡片1：API 设置 ──
        val card1 = card()
        card1.addView(sectionTitle("🧪 API 设置"))
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
            text = "🚀 一键预设（点一下自动填入）"
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
                    android.widget.Toast.makeText(this@SettingsActivity, "已填入 $name 预设", android.widget.Toast.LENGTH_SHORT).show()
                }
                qbounce()
            }
            presetRow.addView(btn)
        }
        presetScroll.addView(presetRow)
        card1.addView(presetScroll)
        card1.addView(spacer(dp(10)))

        val saveBtn = qBtn("💾 保存设置", true)
        card1.addView(saveBtn, lp())

        val testBtn = qBtn("⚡ 测试 AI 连接", false)
        testBtn.setOnClickListener {
            settings.apiKey = keyInput.text.toString()
            settings.baseUrl = urlInput.text.toString()
            settings.model = modelInput.text.toString()
            testBtn.text = "⏳ 测试中…"
            testBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val replies = AiClient(settings).generateReplies(
                        listOf("群友: 在吗"), "在吗"
                    )
                    if (replies.isEmpty()) {
                        Toast.makeText(this@SettingsActivity, "连接成功，但模型没返回内容", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "🎉 连接成功！示例回复：${replies.first()}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "失败：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
                testBtn.text = "⚡ 测试 AI 连接"
                testBtn.isEnabled = true
            }
        }
        card1.addView(testBtn, lp())
        container.addView(card1)

        // ── 卡片2：回复风格 ──
        val card2 = card()
        card2.addView(sectionTitle("🎭 回复风格"))

        val crazyLabel = TextView(this).apply {
            text = "🤪 抽象逆天程度：${settings.craziness} / 5"
            textSize = 14f; setTextColor(cText)
        }
        card2.addView(crazyLabel)
        val crazyBar = qSeekBar().apply {
            max = 4; progress = settings.craziness - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    crazyLabel.text = "🤪 抽象逆天程度：${p + 1} / 5"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card2.addView(crazyBar, lp())

        val flirtyLabel = TextView(this).apply {
            text = "💘 软情趣程度：${settings.flirty} / 10"
            textSize = 14f; setTextColor(cText)
        }
        card2.addView(flirtyLabel)
        val flirtyBar = qSeekBar().apply {
            max = 9; progress = settings.flirty - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    flirtyLabel.text = "💘 软情趣程度：${p + 1} / 10"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card2.addView(flirtyBar, lp())

        val toxicLabel = TextView(this).apply {
            text = "😏 毒舌程度：${settings.toxic} / 10"
            textSize = 14f; setTextColor(cText)
        }
        card2.addView(toxicLabel)
        val toxicBar = qSeekBar().apply {
            max = 9; progress = settings.toxic - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    toxicLabel.text = "😏 毒舌程度：${p + 1} / 10"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card2.addView(toxicBar, lp())

        // ── 人设包选择 ──
        card2.addView(sectionTitle("🎭 人设包"))
        val personaDesc = TextView(this).apply {
            text = "当前：${Persona.fromId(settings.persona).emoji} ${Persona.fromId(settings.persona).label} — ${Persona.fromId(settings.persona).desc}"
            textSize = 12f; setTextColor(cTextLight)
            setPadding(0, 0, 0, dp(8))
        }
        card2.addView(personaDesc)

        val personaRow = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(4))
        }
        val personaBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val personaBtnMap = mutableMapOf<Persona, Button>()
        Persona.all.forEach { p ->
            val btn = qBtn("${p.emoji} ${p.label}", primary = (p.id == settings.persona)).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    settings.persona = p.id
                    personaDesc.text = "当前：${p.emoji} ${p.label} — ${p.desc}"
                    personaBtnMap.forEach { (pp, b) ->
                        b.background = if (pp.id == p.id)
                            resources.getDrawable(R.drawable.bg_q_btn_pink, null)
                        else
                            resources.getDrawable(R.drawable.bg_q_btn_soft, null)
                        b.setTextColor(if (pp.id == p.id) 0xFFFFFFFF.toInt() else cPinkDark)
                    }
                    android.widget.Toast.makeText(this@SettingsActivity, "已切换：${p.emoji} ${p.label}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            personaBtnMap[p] = btn
            personaBtns.addView(btn)
        }
        personaRow.addView(personaBtns)
        card2.addView(personaRow, lp())
        container.addView(card2)

        // ── 卡片3：监听设置 ──
        val card3 = card()
        card3.addView(sectionTitle("👂 监听设置"))
        val groupInput = EditText(this).apply {
            hint = "只监听这些群（逗号分隔关键词，留空=全部）"
            setText(settings.groupFilter)
            setSingleLine(true)
            qInput()
        }
        card3.addView(field("群过滤", groupInput))

        val autoSendSwitch = qSwitch().apply {
            text = "点悬浮窗直接发送（关=只复制到剪贴板）"
            isChecked = settings.autoSend
        }
        card3.addView(field("发送方式", autoSendSwitch))

        val serviceSwitch = qSwitch().apply {
            text = "启用服务"
            isChecked = settings.serviceEnabled
        }
        card3.addView(field("总开关", serviceSwitch))
        container.addView(card3)

        // ── 卡片4：权限与工具 ──
        val card4 = card()
        card4.addView(sectionTitle("⚙️ 权限与工具"))

        // 无障碍守护：状态 + 一键开启 + 防清理引导
        val guardBtn = qBtn("🛡️ 无障碍守护 · 检查中…", false, blue = true)
        guardBtn.id = R.id.guardBtn
        guardBtn.setOnClickListener {
            val enabled = com.lelecz.reply.service.AccessibilityGuard.isEnabled(this)
            if (enabled) {
                // 已开启：弹出防清理引导（vivo/小米等 ROM 会后台清理）
                val hasBatteryOpt = com.lelecz.reply.service.AccessibilityGuard.isIgnoringBatteryOptimizations(this)
                val batteryStatus = if (hasBatteryOpt) "✓ 已忽略电池优化" else "✗ 未忽略电池优化（建议开启）"
                android.app.AlertDialog.Builder(this)
                    .setTitle("🛡️ 无障碍守护 · 运行中")
                    .setMessage(
                        "服务正常！\n\n电池优化状态：$batteryStatus\n\n" +
                            "但部分系统会后台自动清理，建议做防清理设置：\n\n" +
                            com.lelecz.reply.service.AccessibilityGuard.romGuide() +
                            "\n\n点「去设置」可直达本应用的详情页："
                    )
                    .setPositiveButton("去设置", { _, _ ->
                        com.lelecz.reply.service.AccessibilityGuard.openAppSettings(this)
                    })
                    .setNeutralButton("电池优化", { _, _ ->
                        com.lelecz.reply.service.AccessibilityGuard.openBatteryOptimization(this)
                    })
                    .setNegativeButton("知道了", null)
                    .show()
            } else {
                // 被关了：一键跳转无障碍设置
                Toast.makeText(this, "正在打开无障碍设置，找到「逆天回复」打开开关", Toast.LENGTH_LONG).show()
                ReplyFloatService.hide(this)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        card4.addView(guardBtn, lp())

        val permissionBtn = qBtn("🔓 开启无障碍服务", false, blue = true)
        permissionBtn.setOnClickListener {
            // 先收起悬浮窗，避免遮挡系统权限确认框（否则系统提示"无法验证回应"）
            ReplyFloatService.hide(this)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        card4.addView(permissionBtn, lp())

        val diagBtn = qBtn("🔍 诊断：为什么开不了无障碍", false)
        diagBtn.setOnClickListener {
            ReplyFloatService.hide(this)
            showDiagnosis()
        }
        card4.addView(diagBtn, lp())

        val overlayBtn = qBtn("🪟 开启悬浮窗权限", false, blue = true)
        overlayBtn.setOnClickListener {
            ReplyFloatService.hide(this)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        card4.addView(overlayBtn, lp())

        val batteryBtn = qBtn("🔋 电池优化（防清理必开）", false)
        batteryBtn.id = R.id.batteryBtn
        batteryBtn.setOnClickListener {
            val ignoring = com.lelecz.reply.service.AccessibilityGuard.isIgnoringBatteryOptimizations(this)
            if (ignoring) {
                Toast.makeText(this, "已忽略电池优化，状态正常 ✓", Toast.LENGTH_SHORT).show()
            } else {
                com.lelecz.reply.service.AccessibilityGuard.openBatteryOptimization(this)
            }
        }
        card4.addView(batteryBtn, lp())

        val ocrBtn = qBtn("🌸 屏幕截图（可选）· 未授权（不影响主功能）", false)
        ocrBtn.id = R.id.ocrBtn
        ocrBtn.setOnClickListener {
            // 先给用户反馈，避免重复点击
            Toast.makeText(this@SettingsActivity, "正在打开屏幕捕获授权…", Toast.LENGTH_SHORT).show()
            lastOcrClickTime = System.currentTimeMillis()
            // 延迟执行，避免与其他操作竞争导致 ROM 兼容问题
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.lelecz.reply.service.OcrManager.requestAuth(this@SettingsActivity)
            }, 300)
        }
        card4.addView(ocrBtn, lp())

        val testFloatBtn = qBtn("🎈 测试悬浮窗（弹个示例）", false)
        testFloatBtn.setOnClickListener {
            try {
                ReplyFloatService.show(this, "群友: 今天好无聊啊", listOf("那我给你表演个才艺，用脚趾头比耶", "无聊就来找我，我专业陪聊二十年", "你无聊？那你猜猜我现在在干嘛，在数蚂蚁"))
            } catch (e: Exception) {
                Toast.makeText(this, "悬浮窗启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        card4.addView(testFloatBtn, lp())

        val crashBtn = qBtn("🐛 查看崩溃日志（闪退时点这个）", false)
        crashBtn.setOnClickListener {
            val log = com.lelecz.reply.App.readCrashLog(this)
            android.app.AlertDialog.Builder(this)
                .setTitle("崩溃日志（复制发给开发者）")
                .setMessage(log)
                .setPositiveButton("复制", { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                })
                .setNegativeButton("关闭", null)
                .show()
        }
        card4.addView(crashBtn, lp())
        container.addView(card4)

        // 保存按钮逻辑（放在所有输入控件声明之后）
        saveBtn.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isNotBlank() && key.startsWith("http")) {
                Toast.makeText(
                    this,
                    "⚠️ API Key 看起来不对：应填密钥，不是网址",
                    Toast.LENGTH_LONG
                ).show()
            }
            settings.apiKey = key
            settings.baseUrl = urlInput.text.toString()
            settings.model = modelInput.text.toString()
            settings.groupFilter = groupInput.text.toString()
            settings.autoSend = autoSendSwitch.isChecked
            settings.serviceEnabled = serviceSwitch.isChecked
            settings.craziness = crazyBar.progress + 1
            settings.flirty = flirtyBar.progress + 1
            settings.toxic = toxicBar.progress + 1
            Toast.makeText(this, "✨ 已保存，喵~", Toast.LENGTH_SHORT).show()
        }

        val hint = TextView(this).apply {
            text = "💡 使用小贴士\n1. 选择你的服务商（点上方预设按钮），填入 API Key\n2. 先开无障碍 → 成功后再开悬浮窗权限\n3. 打开 QQ 聊天界面，新消息→读屏→悬浮窗弹建议→点一下自动发送\n\n支持：商汤、火山引擎（豆包）、智谱AI、DeepSeek、通义千问、OpenAI 等所有兼容 OpenAI 格式的 API\n\n⚠ 开无障碍被提示\"应用遮挡\"时：点\"诊断\"找出挡道的悬浮窗关掉再开。\n\n无需 root / Shizuku。"
            textSize = 12.5f
            setTextColor(cTextLight)
            setPadding(dp(6), dp(14), dp(6), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        container.addView(hint)

        root.addView(container)

        // 页面进入 Q 弹动画
        container.alpha = 0f
        container.translationY = dp(30).toFloat()
        container.animate().alpha(1f).translationY(0f).setDuration(400)
            .setInterpolator(OvershootInterpolator(0.6f)).start()

        return root
    }

    // ─────────── Q弹组件 ───────────

    /** 白色圆角卡片 */
    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_q_card)
            elevation = dp(2).toFloat()
            val mlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mlp.bottomMargin = dp(14)
            layoutParams = mlp
        }
    }

    /** Q弹主按钮：粉紫渐变 + 按压回弹 */
    private fun qBtn(text: String, primary: Boolean, blue: Boolean = false): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setAllCaps(false)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = if (blue)
                resources.getDrawable(R.drawable.bg_q_btn_blue, null)
            else if (primary)
                resources.getDrawable(R.drawable.bg_q_btn_pink, null)
            else
                resources.getDrawable(R.drawable.bg_q_btn_soft, null)
            if (!primary && !blue) setTextColor(cPinkDark)
            qbounce()
        }
    }

    /** 按压弹性回弹（Q弹手感核心） */
    private fun View.qbounce() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(1.6f)).start()
                    false
                }
                else -> false
            }
        }
    }

    /** Q弹输入框 */
    private fun EditText.qInput() {
        textSize = 15f
        setTextColor(cText)
        setHintTextColor(0xFFC9B3BB.toInt())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = resources.getDrawable(R.drawable.bg_q_input, null)
        maxLines = 1
    }

    /** Q弹开关 */
    private fun qSwitch(): Switch {
        return Switch(this).apply {
            textSize = 14f
            setTextColor(cText)
            trackDrawable = resources.getDrawable(R.drawable.bg_q_switch_track, null)
            thumbDrawable = resources.getDrawable(R.drawable.bg_q_switch_thumb, null)
        }
    }

    /** Q弹滑块 */
    private fun qSeekBar(): SeekBar {
        return SeekBar(this).apply {
            progressDrawable = resources.getDrawable(R.drawable.seekbar_progress, null)
            thumb = resources.getDrawable(R.drawable.seekbar_thumb, null)
            splitTrack = false
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
    }

    private fun title(): TextView {
        return TextView(this).apply {
            text = "🐱 逆天回复"
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(cSection)
        }
    }

    private fun subTitle(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(cTextLight)
            setPadding(0, dp(2), 0, dp(14))
        }
    }

    private fun sectionTitle(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(cPinkDark)
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun field(label: String, child: View): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val tv = TextView(this).apply {
            text = label
            textSize = 12.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(cTextLight)
            setPadding(dp(4), 0, 0, dp(5))
        }
        wrap.addView(tv)
        wrap.addView(child)
        return wrap
    }

    private fun spacer(h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            )
        }
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ─────────── 诊断（保留原逻辑） ───────────

    /** 诊断：列出所有可能产生悬浮窗的应用 + 已开启的无障碍服务 */
    private fun showDiagnosis() {
        val sb = StringBuilder()
        sb.append("📋 已开启的无障碍服务：\n")
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        if (enabled.isEmpty()) {
            sb.append("（无）\n\n")
        } else {
            enabled.forEach { info ->
                sb.append("• ").append(info.resolveInfo?.loadLabel(packageManager) ?: "未知").append("\n")
            }
            sb.append("\n")
        }

        sb.append("⚠ 有\"悬浮窗权限\"的应用（可能挡道）：\n")
        val overlayApps = mutableListOf<String>()
        val pm = packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        apps.forEach { app ->
            try {
                val hasOverlay = pm.checkPermission(
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    app.packageName
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasOverlay) {
                    val label = app.loadLabel(pm)?.toString() ?: app.packageName
                    overlayApps.add("• $label")
                }
            } catch (_: Exception) {}
        }
        if (overlayApps.isEmpty()) {
            sb.append("（无）")
        } else {
            overlayApps.forEach { sb.append(it).append("\n") }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("无障碍开启诊断")
            .setMessage(sb.toString() + "\n\n操作建议：\n① 先把上面有悬浮窗权限的应用全部关闭（它们的悬浮窗会挡系统确认框）\n② 点\"开启无障碍服务\"直接开，出现确认框立刻点\"确定\"\n③ 成功后再回来开本应用的悬浮窗权限")
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        com.lelecz.reply.service.OcrManager.onActivityResult(requestCode, resultCode, data)
    }
}
