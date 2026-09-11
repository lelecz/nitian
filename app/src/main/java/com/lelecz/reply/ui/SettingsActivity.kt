package com.lelecz.reply.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
import com.lelecz.reply.data.SettingsStore
import com.lelecz.reply.service.ReplyFloatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lelecz.reply.ai.AiClient

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

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
                "屏幕截图（可选）· 已授权 ✓"
            else
                "屏幕截图（可选）· 未授权（不影响主功能）"
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
            setBackgroundColor(0xFFF5F6FA.toInt())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        // 标题
        container.addView(title("逆天回复"))

        // ── API 设置 ──
        container.addView(sectionTitle("API 设置"))
        val keyInput = EditText(this).apply {
            hint = "商汤 API Key（sk-...）"
            setText(settings.apiKey)
            setSingleLine(true)
        }
        container.addView(field("API Key", keyInput))

        val urlInput = EditText(this).apply {
            hint = "Base URL"
            setText(settings.baseUrl)
            setSingleLine(true)
        }
        container.addView(field("Base URL", urlInput))

        val modelInput = EditText(this).apply {
            hint = "模型名"
            setText(settings.model)
            setSingleLine(true)
        }
        container.addView(field("模型", modelInput))

        // ── 风格 ──
        container.addView(sectionTitle("回复风格"))

        val crazyLabel = TextView(this).apply { text = "抽象逆天程度：${settings.craziness} / 5" }
        container.addView(crazyLabel)
        val crazyBar = SeekBar(this).apply {
            max = 4; progress = settings.craziness - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    crazyLabel.text = "抽象逆天程度：${p + 1} / 5"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(crazyBar)

        val flirtyLabel = TextView(this).apply { text = "软情趣程度：${settings.flirty} / 10" }
        container.addView(flirtyLabel)
        val flirtyBar = SeekBar(this).apply {
            max = 9; progress = settings.flirty - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    flirtyLabel.text = "软情趣程度：${p + 1} / 10"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(flirtyBar)

        // ── 监听 ──
        container.addView(sectionTitle("监听设置"))
        val groupInput = EditText(this).apply {
            hint = "只监听这些群（逗号分隔关键词，留空=全部）"
            setText(settings.groupFilter)
            setSingleLine(true)
        }
        container.addView(field("群过滤", groupInput))

        val autoSendSwitch = Switch(this).apply {
            text = "点悬浮窗直接发送（关=只复制到剪贴板）"
            isChecked = settings.autoSend
        }
        container.addView(field("发送方式", autoSendSwitch))

        val serviceSwitch = Switch(this).apply {
            text = "启用服务"
            isChecked = settings.serviceEnabled
        }
        container.addView(field("总开关", serviceSwitch))

        // ── 按钮 ──
        val saveBtn = Button(this).apply { text = "保存设置" }
        saveBtn.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isNotBlank() && (key.startsWith("http") || !key.startsWith("sk-"))) {
                Toast.makeText(
                    this,
                    "⚠️ API Key 看起来不对：应填 sk- 开头的密钥，不是网址",
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
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        container.addView(saveBtn, lp())

        val testBtn = Button(this).apply { text = "测试 AI 连接" }
        testBtn.setOnClickListener {
            settings.apiKey = keyInput.text.toString()
            settings.baseUrl = urlInput.text.toString()
            settings.model = modelInput.text.toString()
            testBtn.text = "测试中…"
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
                            "连接成功！示例回复：${replies.first()}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "失败：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
                testBtn.text = "测试 AI 连接"
                testBtn.isEnabled = true
            }
        }
        container.addView(testBtn, lp())

        val permissionBtn = Button(this).apply { text = "开启无障碍服务" }
        permissionBtn.setOnClickListener {
            // 先收起悬浮窗，避免遮挡系统权限确认框（否则系统提示"无法验证回应"）
            ReplyFloatService.hide(this)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        container.addView(permissionBtn, lp())

        val diagBtn = Button(this).apply { text = "🔍 诊断：为什么开不了无障碍" }
        diagBtn.setOnClickListener {
            ReplyFloatService.hide(this)
            showDiagnosis()
        }
        container.addView(diagBtn, lp())

        val overlayBtn = Button(this).apply { text = "开启悬浮窗权限" }
        overlayBtn.setOnClickListener {
            ReplyFloatService.hide(this)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        container.addView(overlayBtn, lp())

        val ocrBtn = Button(this).apply {
            id = R.id.ocrBtn
            text = "屏幕截图（可选）· 未授权（不影响主功能）"
            setOnClickListener {
                // 先给用户反馈，避免重复点击
                Toast.makeText(this@SettingsActivity, "正在打开屏幕捕获授权…", Toast.LENGTH_SHORT).show()
                lastOcrClickTime = System.currentTimeMillis()
                // 延迟执行，避免与其他操作竞争导致 ROM 兼容问题
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    com.lelecz.reply.service.OcrManager.requestAuth(this@SettingsActivity)
                }, 300)
            }
        }
        container.addView(ocrBtn, lp())

        val testFloatBtn = Button(this).apply { text = "测试悬浮窗（弹个示例）" }
        testFloatBtn.setOnClickListener {
            try {
                ReplyFloatService.show(this, "群友: 今天好无聊啊", listOf("那我给你表演个才艺，用脚趾头比耶", "无聊就来找我，我专业陪聊二十年", "你无聊？那你猜猜我现在在干嘛，在数蚂蚁"))
            } catch (e: Exception) {
                Toast.makeText(this, "悬浮窗启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(testFloatBtn, lp())

        val crashBtn = Button(this).apply { text = "🐛 查看崩溃日志（闪退时点这个）" }
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
        container.addView(crashBtn, lp())

        val hint = TextView(this).apply {
            text = "使用说明：\n1. 填 API Key（sk- 开头的密钥，不是网址）\n2. 先开无障碍（点按钮自动收气泡防遮挡）→ 成功后再开悬浮窗权限\n3. 打开 QQ 聊天界面，新消息→直接读屏→悬浮窗弹建议→点一下自动发送\n4. 控件树读不到时点上方授权屏幕截图（OCR 兜底）\n\n⚠ 开无障碍被提示\"应用遮挡\"时：点上方\"诊断\"按钮，查出是哪个应用的悬浮窗在挡道，关掉它再开。\n\n无需 root / Shizuku。"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(hint)

        root.addView(container)
        return root
    }

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

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 24f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFF111827.toInt())
        setPadding(0, 0, 0, dp(16))
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFF3B82F6.toInt())
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun field(label: String, child: View): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val tv = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        wrap.addView(tv)
        wrap.addView(child)
        return wrap
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
