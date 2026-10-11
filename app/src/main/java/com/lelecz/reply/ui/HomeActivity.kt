package com.lelecz.reply.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lelecz.reply.R
import com.lelecz.reply.data.SettingsStore
import com.lelecz.reply.service.AccessibilityGuard
import com.lelecz.reply.service.ReplyFloatService

/**
 * 主页：总服务开关 + 权限状态 + 页面导航。
 * 「总开关」改动即写入并立即生效（修复旧版要点保存才生效的问题）。
 */
class HomeActivity : BaseActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var serviceSwitch: Switch
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        val container = createPage()
        buildUi(container)
        playEnter(container)
    }

    private fun buildUi(container: LinearLayout) {
        container.addView(title())
        container.addView(subTitle("●ᴗ● 群聊整活小助手 · v${versionName()}"))

        // ── 主服务卡 ──
        val card1 = card()
        card1.addView(sectionTitle("主服务"))

        serviceSwitch = qSwitch().apply {
            text = "启用服务（读屏 + AI 回复总开关）"
            isChecked = settings.serviceEnabled
        }
        attachServiceListener()
        card1.addView(serviceSwitch.let { field("总开关", it) })

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextLight)
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        card1.addView(statusText)

        val permBtn = qBtn("开启无障碍服务", false, blue = true)
        permBtn.setOnClickListener {
            ReplyFloatService.hide(this)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        card1.addView(permBtn, lp())
        container.addView(card1)
    }

    /** 总开关：改动立即写入生效（关闭时顺手收起悬浮窗） */
    private fun attachServiceListener() {
        serviceSwitch.setOnCheckedChangeListener { _, checked ->
            settings.serviceEnabled = checked
            if (!checked) ReplyFloatService.hide(this)
            Toast.makeText(
                this,
                if (checked) "服务已开启" else "服务已关闭（读屏与回复已停止）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    override fun onResume() {
        super.onResume()
        // 刷新总开关（先解绑监听，避免回写 + 弹 Toast）
        serviceSwitch.setOnCheckedChangeListener(null)
        serviceSwitch.isChecked = settings.serviceEnabled
        attachServiceListener()
        refreshStatus()
    }

    private fun refreshStatus() {
        val a11y = AccessibilityGuard.isEnabled(this)
        val overlay = Settings.canDrawOverlays(this)
        statusText.text = buildString {
            append("无障碍：${if (a11y) "已开启 ✓" else "未开启 ✗"}")
            if (!a11y) append("（点下方按钮去开启）")
            append("\n")
            append("悬浮窗：${if (overlay) "已授权 ✓" else "未授权 ✗（在「应用设置」里开）"}")
        }
    }
}
