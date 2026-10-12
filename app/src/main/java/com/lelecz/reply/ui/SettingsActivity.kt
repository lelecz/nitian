package com.lelecz.reply.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lelecz.reply.R
import com.lelecz.reply.ai.Persona
import com.lelecz.reply.data.SettingsStore
import com.lelecz.reply.data.WallpaperStore
import com.lelecz.reply.service.ReplyFloatService

/**
 * 设置页：回复风格 / 人设 / 监听 / 权限与工具 / 数据备份 / 外观（壁纸）。
 * 总服务开关与 API 配置已拆到主页与 API 页。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var stats: com.lelecz.reply.data.StatsManager
    private lateinit var backup: com.lelecz.reply.data.BackupManager

    /** SAF 请求码 */
    private val REQUEST_EXPORT = 2001
    private val REQUEST_IMPORT = 2002
    private val REQUEST_PICK_WALLPAPER = 2003
    /** 导出时缓存的 JSON 文本 */
    private var pendingBackupJson: String? = null
    private var lastOcrClickTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        stats = com.lelecz.reply.data.StatsManager(this)
        backup = com.lelecz.reply.data.BackupManager(this, settings, stats)
        val container = createPage()
        buildUi(container)
        playEnter(container)
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
        // 刷新无障碍守护状态
        findViewById<Button>(R.id.guardBtn)?.let {
            it.text = if (com.lelecz.reply.service.AccessibilityGuard.isEnabled(this))
                "无障碍守护 · 运行中 ✓"
            else
                "无障碍守护 · 已关闭（点我开启）"
        }
        // 刷新电池优化按钮状态
        findViewById<Button>(R.id.batteryBtn)?.let {
            it.text = if (com.lelecz.reply.service.AccessibilityGuard.isIgnoringBatteryOptimizations(this))
                "电池优化 · 已忽略（防清理 ✓）"
            else
                "电池优化（防清理必开）"
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

    private fun buildUi(container: LinearLayout) {
        container.addView(pageHeader("应用设置"))

        // ── 卡片1：回复风格 ──
        val card2 = card()
        card2.addView(sectionTitle("回复风格"))

        val crazyBar = sliderRow(card2, "抽象逆天程度", 4, settings.craziness - 1) { "${it + 1} / 5" }

        // ── 18R 状态行：解锁状态 × 当前档位，露骨是否生效一眼可见 ──
        fun r18Text(lvl: Int): String = when {
            !settings.contentUnlocked -> "未解锁"
            lvl < 10 -> "已解锁 · 未生效"
            else -> "已解锁 · 露骨生效中"
        }
        val r18Value = TextView(this).apply {
            text = r18Text(settings.flirty)
            textSize = 14f
            setTextColor(cPinkDark)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        val r18Row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@SettingsActivity).apply {
                text = "18R状态"
                textSize = 14f
                setTextColor(cText)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(r18Value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val flirtyBar = sliderRow(card2, "软情趣程度", 9, settings.flirty - 1) { p ->
            r18Value.text = r18Text(p + 1)
            "${p + 1} / 10"
        }
        card2.addView(r18Row) // 拖动软情趣滑条时状态行实时刷新
        val toxicBar = sliderRow(card2, "毒舌程度", 9, settings.toxic - 1) { "${it + 1} / 10" }

        // ── 人设包选择 ──
        card2.addView(sectionTitle("人设包"))
        val personaDesc = TextView(this).apply {
            text = "当前：${Persona.fromId(settings.persona).label} — ${Persona.fromId(settings.persona).desc}"
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
            val btn = qBtn("${p.label}", primary = (p.id == settings.persona)).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    settings.persona = p.id
                    personaDesc.text = "当前：${p.label} — ${p.desc}"
                    personaBtnMap.forEach { (pp, b) ->
                        b.background = if (pp.id == p.id)
                            resources.getDrawable(R.drawable.bg_q_btn_pink, null)
                        else
                            resources.getDrawable(R.drawable.bg_q_btn_soft, null)
                        b.setTextColor(if (pp.id == p.id) 0xFFFFFFFF.toInt() else cPinkDark)
                    }
                    Toast.makeText(this@SettingsActivity, "已切换：${p.label}", Toast.LENGTH_SHORT).show()
                }
            }
            personaBtnMap[p] = btn
            personaBtns.addView(btn)
        }
        personaRow.addView(personaBtns)
        card2.addView(personaRow, lp())
        container.addView(card2)

        // ── 卡片2：监听设置 ──
        val card3 = card()
        card3.addView(sectionTitle("监听设置"))
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
        container.addView(card3)

        // ── 卡片3：权限与工具 ──
        val card4 = card()
        card4.addView(sectionTitle("权限与工具"))

        // 无障碍守护：状态 + 一键开启 + 防清理引导
        val guardBtn = qBtn("无障碍守护 · 检查中…", false, blue = true)
        guardBtn.id = R.id.guardBtn
        guardBtn.setOnClickListener {
            val enabled = com.lelecz.reply.service.AccessibilityGuard.isEnabled(this)
            if (enabled) {
                // 已开启：弹出防清理引导（vivo/小米等 ROM 会后台清理）
                val hasBatteryOpt = com.lelecz.reply.service.AccessibilityGuard.isIgnoringBatteryOptimizations(this)
                val batteryStatus = if (hasBatteryOpt) "✓ 已忽略电池优化" else "✗ 未忽略电池优化（建议开启）"
                Glass.builder(this)
                    .setTitle("无障碍守护 · 运行中")
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
                    .showGlass()
            } else {
                // 被关了：一键跳转无障碍设置
                Toast.makeText(this, "正在打开无障碍设置，找到「逆天回复」打开开关", Toast.LENGTH_LONG).show()
                ReplyFloatService.hide(this)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        card4.addView(guardBtn, lp())

        val permissionBtn = qBtn("开启无障碍服务", false, blue = true)
        permissionBtn.setOnClickListener {
            // 先收起悬浮窗，避免遮挡系统权限确认框（否则系统提示"无法验证回应"）
            ReplyFloatService.hide(this)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        card4.addView(permissionBtn, lp())

        val diagBtn = qBtn("诊断：为什么开不了无障碍", false)
        diagBtn.setOnClickListener {
            ReplyFloatService.hide(this)
            showDiagnosis()
        }
        card4.addView(diagBtn, lp())

        val overlayBtn = qBtn("开启悬浮窗权限", false, blue = true)
        overlayBtn.setOnClickListener {
            ReplyFloatService.hide(this)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        card4.addView(overlayBtn, lp())

        val batteryBtn = qBtn("电池优化（防清理必开）", false)
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

        val ocrBtn = qBtn("屏幕截图（可选）· 未授权（不影响主功能）", false)
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

        val testFloatBtn = qBtn("测试悬浮窗（弹个示例）", false)
        testFloatBtn.setOnClickListener {
            try {
                ReplyFloatService.show(this, "群友: 今天好无聊啊", listOf("那我给你表演个才艺，用脚趾头比耶", "无聊就来找我，我专业陪聊二十年", "你无聊？那你猜猜我现在在干嘛，在数蚂蚁"))
            } catch (e: Exception) {
                Toast.makeText(this, "悬浮窗启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        card4.addView(testFloatBtn, lp())

        val crashBtn = qBtn("查看崩溃日志（闪退时点这个）", false)
        crashBtn.setOnClickListener {
            val log = com.lelecz.reply.App.readCrashLog(this)
            Glass.builder(this)
                .setTitle("崩溃日志（复制发给开发者）")
                .setMessage(log)
                .setPositiveButton("复制", { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                })
                .setNegativeButton("关闭", null)
                .showGlass()
        }
        card4.addView(crashBtn, lp())
        val traceBtn = qBtn("管线诊断（看回复卡在哪一步）", false)
        traceBtn.setOnClickListener { showTraceDialog() }
        card4.addView(traceBtn, lp())

        container.addView(card4)

        // ── 内容分级（18+ 管理员密码解锁） ──
        val cardC = card()
        cardC.addView(sectionTitle("内容分级"))
        val levelStatus = TextView(this).apply {
            text = if (settings.contentUnlocked)
                "18+ 已解锁（管理员密码）· 软情趣拉满 10 级即切换露骨"
            else
                "18+ 成人向内容 · 已锁定，解锁需管理员密码"
            textSize = 12f
            setTextColor(cTextLight)
            setPadding(0, 0, 0, dp(8))
        }
        cardC.addView(levelStatus)
        val unlockBtn = qBtn(
            if (settings.contentUnlocked) "撤销 18+ 解锁" else "输入管理员密码解锁 18+",
            false
        )
        unlockBtn.setOnClickListener { showUnlockDialog() }
        cardC.addView(unlockBtn, lp())
        container.addView(cardC)

        // ── 卡片4：数据与备份 ──
        val card5 = card()
        card5.addView(sectionTitle("数据与备份"))

        val statsBtn = qBtn("查看用量统计", false)
        statsBtn.setOnClickListener { showStatsDialog() }
        card5.addView(statsBtn, lp())

        val exportBtn = qBtn("导出设置（备份到文件）", false, blue = true)
        exportBtn.setOnClickListener { startExport() }
        card5.addView(exportBtn, lp())

        val importBtn = qBtn("导入设置（从备份恢复）", false, blue = true)
        importBtn.setOnClickListener { startImport() }
        card5.addView(importBtn, lp())

        val clearStatsBtn = qBtn("清空用量统计", false)
        clearStatsBtn.setOnClickListener {
            Glass.builder(this)
                .setTitle("清空统计")
                .setMessage("确定清空所有用量统计数据吗？（不影响你的设置）")
                .setPositiveButton("清空") { _, _ ->
                    stats.clear()
                    Toast.makeText(this, "统计已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .showGlass()
        }
        card5.addView(clearStatsBtn, lp())
        container.addView(card5)

        // ── 卡片5：外观（自定义壁纸） ──
        val card6 = card()
        card6.addView(sectionTitle("外观"))
        val wallpaperStatus = TextView(this).apply {
            text = if (WallpaperStore.exists(this@SettingsActivity)) "当前：已设置自定义壁纸" else "当前：默认渐变背景"
            textSize = 12f
            setTextColor(cTextLight)
            setPadding(0, 0, 0, dp(8))
        }
        card6.addView(wallpaperStatus)

        val wallpaperPickBtn = qBtn("选择壁纸", false, blue = true)
        wallpaperPickBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_PICK_WALLPAPER)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        card6.addView(wallpaperPickBtn, lp())

        val wallpaperClearBtn = qBtn("清除壁纸", false)
        wallpaperClearBtn.setOnClickListener {
            WallpaperStore.clear(this)
            Toast.makeText(this, "壁纸已清除", Toast.LENGTH_SHORT).show()
            recreate()
        }
        card6.addView(wallpaperClearBtn, lp())

        val wallpaperHint = TextView(this).apply {
            text = "壁纸仅保存在本机应用目录，不会上传；三个页面共用。"
            textSize = 12f
            setTextColor(cTextLight)
            setPadding(dp(4), dp(6), 0, 0)
        }
        card6.addView(wallpaperHint)
        container.addView(card6)

        // 保存按钮：风格 + 监听（API 三要素在 API 页保存，总开关在主页即时生效）
        val saveBtn = qBtn("保存设置（风格 / 监听）", true)
        saveBtn.setOnClickListener {
            settings.groupFilter = groupInput.text.toString()
            settings.autoSend = autoSendSwitch.isChecked
            settings.craziness = crazyBar.progress + 1
            settings.flirty = flirtyBar.progress + 1
            settings.toxic = toxicBar.progress + 1
            Toast.makeText(this, "已保存，喵~", Toast.LENGTH_SHORT).show()
        }
        container.addView(saveBtn, lp())

        val hint = TextView(this).apply {
            text = "使用小贴士\n1. 去「API 设置」页选服务商预设、填 Key、点「拉取模型」\n2. 总开关在主页，改动立即生效\n3. 先开无障碍 → 成功后再开悬浮窗权限\n4. 打开 QQ 聊天界面，新消息→读屏→悬浮窗弹建议→点一下自动发送\n\n注意：开无障碍被提示\"应用遮挡\"时：点\"诊断\"找出挡道的悬浮窗关掉再开。\n\n无需 root / Shizuku。"
            textSize = 12.5f
            setTextColor(cTextLight)
            setPadding(dp(6), dp(14), dp(6), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        container.addView(hint)
    }

    // ─────────── 用量统计 ───────────

    /** 把 n 向上取到漂亮的量程（5 / 10 / 20 / 50 × 10^k），给仪表盘做满刻度 */
    private fun niceGaugeMax(n: Int): Float {
        if (n <= 0) return 1000f
        val v = n.toFloat()
        val pow = Math.pow(10.0, Math.floor(Math.log10(v.toDouble()))).toFloat()
        val normalized = v / pow   // 1~9.99
        val head = when {
            normalized <= 1f -> 1f
            normalized <= 2f -> 2f
            normalized <= 5f -> 5f
            else -> 10f
        }
        return head * pow * 2f  // 量程留 2 倍余量，数据在 50% 左右不顶红线
    }

    private fun showStatsDialog() {
        val totalTokens = stats.totalTokensConsumed()
        val totalReplies = stats.totalReplies()
        val todayReplies = stats.todayReplies()

        // 根布局
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }

        // ── 仪表盘：token 用量 ──
        val gauge = GaugeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(200)
            )
            this.value = totalTokens.toFloat()
            this.maxValue = niceGaugeMax(totalTokens)
            this.centerText = String.format("%,d", totalTokens)
            this.subText = "累计 token"
        }
        root.addView(gauge)

        // ── 今日 / 累计 两小卡 ──
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val statCard = { label: String, value: Int ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = String.format("%,d", value); textSize = 22f
                    setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF222222.toInt())
                })
                addView(TextView(context).apply {
                    text = label; textSize = 12f; setTextColor(0xFF888888.toInt())
                })
            }
        }
        row.addView(statCard("今日回复", todayReplies))
        row.addView(statCard("累计回复", totalReplies))
        root.addView(row)

        // ── 常用人设 top 5 ──
        val personaStats = stats.allPersonaStats().take(5)
        if (personaStats.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "\n常用人设"; textSize = 13f
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF555555.toInt())
            })
            personaStats.forEach { (id, count) ->
                val p = Persona.fromId(id)
                root.addView(TextView(this).apply {
                    text = "· ${p.label}  ·  $count 次"; textSize = 13f
                    setTextColor(0xFF444444.toInt())
                })
            }
        }

        Glass.builder(this)
            .setTitle("用量统计")
            .setView(root)
            .setPositiveButton("关闭", null)
            .showGlass()
    }

    // ─────────── 管线诊断 ───────────

    /** 展示最近几次「事件 → 悬浮窗」链路的每步耗时，标出最慢一步 */
    private fun showTraceDialog() {
        val tv = TextView(this).apply {
            text = com.lelecz.reply.service.PipelineTrace.formatReport()
            textSize = 12f
            setTextColor(0xFF444444.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        Glass.builder(this)
            .setTitle("管线诊断")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .showGlass()
    }

    // ─────────── 内容分级（管理员密码） ───────────

    private fun showUnlockDialog() {
        // 已解锁 → 走撤销确认
        if (settings.contentUnlocked) {
            Glass.builder(this)
                .setTitle("撤销 18+ 解锁")
                .setMessage("将恢复默认内容尺度，随时可再次解锁。")
                .setPositiveButton("撤销") { _, _ ->
                    settings.contentUnlocked = false
                    Toast.makeText(this, "已恢复默认内容尺度", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton("取消", null)
                .showGlass()
            return
        }

        if (com.lelecz.reply.data.AdminGate.isLocked()) {
            Toast.makeText(
                this,
                "输错次数过多，请 ${com.lelecz.reply.data.AdminGate.remainingLockSec()} 秒后再试",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val input = EditText(this).apply {
            hint = "管理员密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFF444444.toInt())
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        Glass.builder(this)
            .setTitle("解锁 18+ 内容")
            .setMessage("解锁后回复风格将放开成人向尺度（18+），仅限成年人使用；可随时撤销。")
            .setView(wrap)
            .setPositiveButton("解锁") { _, _ ->
                if (com.lelecz.reply.data.AdminGate.check(input.text.toString())) {
                    settings.contentUnlocked = true
                    Toast.makeText(this, "18+ 内容已解锁", Toast.LENGTH_SHORT).show()
                    recreate()
                } else if (com.lelecz.reply.data.AdminGate.isLocked()) {
                    Toast.makeText(
                        this,
                        "连续输错，已锁定 ${com.lelecz.reply.data.AdminGate.remainingLockSec()} 秒",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "密码不对", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .showGlass()
    }

    // ─────────── 设置导出 / 导入 ───────────

    private fun startExport() {
        val json = backup.buildBackupJson(includeStats = true)
        pendingBackupJson = json
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, com.lelecz.reply.data.BackupManager.DEFAULT_FILENAME)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_IMPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 把导出 JSON 写入用户选择的 uri */
    private fun writeBackupToUri(uri: android.net.Uri) {
        val json = pendingBackupJson ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "设置已导出", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pendingBackupJson = null
        }
    }

    /** 从用户选择的 uri 读取备份并恢复 */
    private fun readBackupFromUri(uri: android.net.Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.use { ins ->
                ins.readBytes().toString(Charsets.UTF_8)
            } ?: throw Exception("读不到文件内容")
            val result = backup.restoreFromJson(json)
            Toast.makeText(this, "$result，请重新打开页面刷新", Toast.LENGTH_LONG).show()
            // 重建页面以反映恢复的设置
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────── 诊断 ───────────

    /** 诊断：列出所有可能产生悬浮窗的应用 + 已开启的无障碍服务 */
    private fun showDiagnosis() {
        val sb = StringBuilder()
        sb.append("已开启的无障碍服务：\n")
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

        sb.append("有\"悬浮窗权限\"的应用（可能挡道）：\n")
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

        Glass.builder(this)
            .setTitle("无障碍开启诊断")
            .setMessage(sb.toString() + "\n\n操作建议：\n① 先把上面有悬浮窗权限的应用全部关闭（它们的悬浮窗会挡系统确认框）\n② 点\"开启无障碍服务\"直接开，出现确认框立刻点\"确定\"\n③ 成功后再回来开本应用的悬浮窗权限")
            .setPositiveButton("知道了", null)
            .showGlass()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_EXPORT -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { writeBackupToUri(it) }
                } else {
                    pendingBackupJson = null
                }
            }
            REQUEST_IMPORT -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { readBackupFromUri(it) }
                }
            }
            REQUEST_PICK_WALLPAPER -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        if (WallpaperStore.saveFrom(this, uri)) {
                            Toast.makeText(this, "壁纸已应用", Toast.LENGTH_SHORT).show()
                            recreate()
                        } else {
                            Toast.makeText(this, "壁纸保存失败，换张图试试", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            else -> com.lelecz.reply.service.OcrManager
                .onActivityResult(this, requestCode, resultCode, data)
        }
    }
}
