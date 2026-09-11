package com.lelecz.reply.service

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
 * 提供：状态检测、被关后通知引导一键恢复、防清理设置跳转。
 */
object AccessibilityGuard {

    private const val NOTIFY_ID = 2026
    private var lastNotifiedAt = 0L

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
        if (isEnabled(ctx)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAt < 60_000) return
        lastNotifiedAt = now
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
            val n = android.app.Notification.Builder(ctx, "guard")
                .setContentTitle("🛡️ 无障碍服务被系统关闭了")
                .setContentText("点我重新开启（需手动确认一次）")
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
        return m.contains("vivo") || m.contains("bbk") || m.contains("iqoo")
    }

    /** 是否小米/红米（MIUI/HyperOS 也会自动清理） */
    fun isXiaomiRom(): Boolean {
        val m = Build.MANUFACTURER?.lowercase() ?: ""
        return m.contains("xiaomi") || m.contains("redmi")
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

    /** 针对当前 ROM 的防清理引导文案 */
    fun romGuide(): String {
        return when {
            isVivoRom() -> "vivo/iQOO（OriginOS）防清理设置：\n① 后台耗电管理 → 允许后台高耗电 / 设为\"无限制\"\n② 自启动 → 允许自启动\n③ 最近任务里把本应用下拉锁定（加锁）"
            isXiaomiRom() -> "小米/红米 防清理设置：\n① 自启动 → 允许\n② 省电策略 → 无限制\n③ 最近任务下拉锁定应用"
            else -> "防清理通用设置：\n① 允许自启动\n② 后台运行 / 省电策略设为\"无限制\"\n③ 最近任务里锁定应用（下拉加锁）"
        }
    }
}
