package com.lelecz.reply.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * OCR 截屏专用前台服务。
 * - Android 14+ (API 34)：截屏前必须存在 mediaProjection 类型 FGS（硬性要求）
 * - Android 10~13 (API 29~33)：FGS 仅为防杀，getMediaProjection 不强制要求；startForeground 只支持两参数
 * - Android 9 及以下：startForeground 两参数，无 mediaProjection 类型概念
 */
class OcrProjectionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                "ocr_proj", "屏幕识别",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
            val notification = Notification.Builder(this, "ocr_proj")
                .setContentTitle("逆天回复·屏幕识别运行中")
                .setContentText("OCR 截图兜底需要保持运行")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build()

            // API 34+：三参数 startForeground 才支持 mediaProjection 类型
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                val mediaType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(1002, notification, mediaType)
            } else {
                // API 29~33（含用户的安卓 10）：两参数 startForeground 合法
                startForeground(1002, notification)
            }
        } catch (t: Throwable) {
            android.util.Log.e("OcrProj", "startForeground 失败：${t.message}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
