package com.lelecz.reply.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启（如果用户允许） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 悬浮窗服务开机自启（无障碍需用户手动开启，这里只启动悬浮窗）
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, ReplyFloatService::class.java)
                        .setAction(ReplyFloatService.ACTION_HIDE)
                )
            } catch (_: Exception) {}
        }
    }
}
