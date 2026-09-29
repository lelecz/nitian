package com.lelecz.reply.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍守护：
 * 针对部分 ROM（vivo/OriginOS、小米 MIUI 等）会后台清理/自动关闭无障碍服务的问题，
 * 提供：状态检测、被关后通知引导一键恢复、防清理设置跳转、多重保活。
 *
 * 保活层级：
 * 1. 悬浮窗服务每 15 秒检查一次（vivo 上 10 秒）
 * 2. AlarmManager 定时唤醒检查（保底，即使进程被杀也能拉起）
 * 3. 无障碍服务 onDestroy 时延迟检查
 * 4. 开机自启检查
 */
object AccessibilityGuard {

    private const val NOTIFY_ID = 2026
    private const val ALARM_REQUEST_CODE = 2027
    private var lastNotifiedAt = 0L
    private var lastCloseCount = 0
    private var firstCloseTime = 0L

    /** 无障碍服务当前是否开启（本应用） */
    fun isEnabled(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            enabled.any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }
        } catch (_: Exception) {
            false
        }
    }

    /** 若无障碍被系统关闭，发一条"一键恢复"通知（1 分钟内不重复轰炸） */
    fun notifyIfClosed(ctx: Context) {
        if (isEnabled(ctx)) {
            // 已恢复，重置计数
            if (lastCloseCount > 0) {
                lastCloseCount = 0
                firstCloseTime = 0L
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAt < 60_000) return
        lastNotifiedAt = now

        // 记录关闭频率，频繁关闭则加强提示
        if (firstCloseTime == 0L) firstCloseTime = now
        lastCloseCount++

        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    "guard", "无障碍守护",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
            )
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val frequentClose = lastCloseCount >= 3 && (now - firstCloseTime < 10 * 60_000)
            val title = if (frequentClose)
                "⚠️ 无障碍频繁被关闭，请设置防清理！"
            else
                "🛡️ 无障碍服务被系统关闭了"
            val content = if (frequentClose)
                "10 分钟内已被关 $lastCloseCount 次，点我去设置防清理"
            else
                "点我重新开启（需手动确认一次）"

            val n = android.app.Notification.Builder(ctx, "guard")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (_: Exception) {}
    }

    /** 是否 vivo/OriginOS（iQOO 同属 vivo，也爱吃掉无障碍） */
    fun isVivoRom(): Boolean {
        val m = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        return m.contains("vivo") || m.contains("bbk") || m.contains("iqoo") ||
            brand.contains("vivo") || brand.contains("iqoo")
    }

    /** 是否小米/红米（MIUI/HyperOS 也会自动清理） */
    fun isXiaomiRom(): Boolean {
        val m = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        return m.contains("xiaomi") || m.contains("redmi") || brand.contains("redmi")
    }

    /** 跳转到本应用的"应用信息"页（用户在那里设置 自启动/耗电无限制/锁定任务） */
    fun openAppSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    /** 跳转到电池优化设置，引导用户设为"不优化" */
    fun openBatteryOptimization(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Exception) {
            // 某些 ROM 不支持直接跳转，降级到应用信息页
            openAppSettings(ctx)
        }
    }

    /** 检查是否已忽略电池优化 */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
    }

    /** 针对当前 ROM 的防清理引导文案 */
    fun romGuide(): String {
        return when {
            isVivoRom() -> buildString {
                append("vivo/iQOO（OriginOS）防清理设置（必须全部设置）：\n")
                append("① 电池 → 后台耗电管理 → 找到本应用 → 设为「允许后台高耗电」\n")
                append("② 电池 → 耗电管理 → 关闭「睡眠时自动清理耗电应用」\n")
                append("③ 应用管理 → 本应用 → 权限 → 自启动 → 允许自启动\n")
                append("④ 应用管理 → 本应用 → 权限 → 单项权限设置 → 开启「后台弹出界面」「常驻通知」\n")
                append("⑤ 最近任务界面 → 下拉本应用卡片 → 点击「🔒」锁定\n")
                append("⑥ 电池优化 → 选择「不优化」\n")
                append("\n⚠ iQOO 15T 等新机型清理特别严格，请务必逐项检查！")
            }
            isXiaomiRom() -> buildString {
                append("小米/红米（MIUI/HyperOS）防清理设置：\n")
                append("① 应用设置 → 自启动 → 允许自启动\n")
                append("② 省电策略 → 设为「无限制」\n")
                append("③ 最近任务 → 下拉锁定应用\n")
                append("④ 电池优化 → 选择「不优化」\n")
                append("⑤ 权限管理 → 允许「后台弹出界面」「显示悬浮窗」")
            }
            else -> buildString {
                append("防清理通用设置：\n")
                append("① 允许自启动\n")
                append("② 后台运行 / 省电策略设为「无限制」\n")
                append("③ 最近任务里锁定应用（下拉加锁）\n")
                append("④ 电池优化 → 选择「不优化」")
            }
        }
    }

    /** 获取推荐的守护检查间隔（毫秒），vivo 上更频繁 */
    fun getCheckIntervalMs(): Long {
        return if (isVivoRom()) 10_000L else 15_000L
    }

    // ─────────── AlarmManager 保底检查 ───────────

    /** 启动 AlarmManager 定时检查（即使进程被杀，到点也能拉起检查） */
    fun startAlarmGuard(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, GuardReceiver::class.java)
                .setAction(GuardReceiver.ACTION_CHECK)
            val pi = PendingIntent.getBroadcast(
                ctx, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = getCheckIntervalMs() * 3  // Alarm 间隔长一点，避免过度唤醒
            am.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + interval,
                interval,
                pi
            )
        } catch (_: Exception) {}
    }

    /** 停止 AlarmManager 定时检查 */
    fun stopAlarmGuard(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, GuardReceiver::class.java)
                .setAction(GuardReceiver.ACTION_CHECK)
            val pi = PendingIntent.getBroadcast(
                ctx, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        } catch (_: Exception) {}
    }
}
