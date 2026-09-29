package com.lelecz.reply.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 守护广播接收器：
 * - 接收 AlarmManager 定时唤醒，检查无障碍服务状态
 * - 接收开机广播
 * - 接收其他系统事件（如网络变化、用户解锁等）触发检查
 */
class GuardReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CHECK = "com.lelecz.reply.GUARD_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK -> {
                // AlarmManager 定时检查
                try {
                    AccessibilityGuard.notifyIfClosed(context.applicationContext)
                } catch (_: Exception) {}
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // 开机自启：启动悬浮窗服务 + 延迟检查无障碍
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                        context,
                        Intent(context, ReplyFloatService::class.java)
                            .setAction(ReplyFloatService.ACTION_HIDE)
                    )
                } catch (_: Exception) {}
                try {
                    val appCtx = context.applicationContext
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        AccessibilityGuard.notifyIfClosed(appCtx)
                        AccessibilityGuard.startAlarmGuard(appCtx)
                    }, 10_000)
                } catch (_: Exception) {}
            }
            Intent.ACTION_USER_PRESENT -> {
                // 用户解锁屏幕时检查一次
                try {
                    AccessibilityGuard.notifyIfClosed(context.applicationContext)
                } catch (_: Exception) {}
            }
        }
    }
}
