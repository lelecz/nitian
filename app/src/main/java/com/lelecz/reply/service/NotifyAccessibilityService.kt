package com.lelecz.reply.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lelecz.reply.ai.AiClient
import com.lelecz.reply.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无障碍服务（无 root / Shizuku）：
 * 1. 直接读取屏幕（控件树提取聊天上下文），不依赖通知
 * 2. 控件树读不到时，自动切 MediaProjection 截图 + OCR 兜底
 * 3. 调 AI 生成建议 → 弹悬浮窗
 * 4. 点建议 → 自动填入输入框并发送
 */
class NotifyAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: SettingsStore
    private lateinit var aiClient: AiClient

    /** CPU 唤醒锁：防止切应用/息屏时系统进入 Doze 导致无障碍被挂起 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 前台服务通知 ID（与悬浮窗服务不同，避免冲突） */
    private val FG_NOTIFICATION_ID = 1001

    /** 防抖：内容哈希，相同则不重复触发 */
    private var lastContentHash: String = ""
    private var lastTriggerTime: Long = 0
    private var aiJob: Job? = null
    /** 延迟读屏任务（与 AI 请求分开，避免互相 cancel） */
    private var readJob: Job? = null

    /** 当前上下文（供刷新按钮重生成用） */
    private var currentContext: List<String> = emptyList()
    private var currentLatest: String = ""

    /** 我们自己发送的最后一条（用于过滤，避免自己触发自己） */
    private var lastSentByUs: String = ""

    private var lastEditNode: AccessibilityNodeInfo? = null
    private var lastSendNode: AccessibilityNodeInfo? = null

    companion object {
        private var instance: NotifyAccessibilityService? = null

        fun hasInstance(): Boolean = instance != null

        fun sendReply(text: String) {
            instance?.doSend(text)
        }

        /** 悬浮窗"刷新"按钮：用当前上下文重新生成 */
        fun refreshReplies() {
            instance?.regenerate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        aiClient = AiClient(settings)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 升级为前台服务：大幅降低切应用/后台时被系统杀掉的概率
        startForegroundCompat()
        // 持有 PARTIAL_WAKE_LOCK：防止 Doze 模式下 CPU 休眠导致无障碍挂起
        acquireWakeLock()
    }

    /** 启动前台服务（常驻通知栏，提高进程优先级） */
    private fun startForegroundCompat() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel(
                "a11y_fg", "无障碍保活",
                android.app.NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
            val notification = android.app.Notification.Builder(this, "a11y_fg")
                .setContentTitle("逆天回复·无障碍运行中")
                .setContentText("后台保活中，切应用不掉线")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(android.app.Notification.PRIORITY_MIN)
                .build()
            startForeground(FG_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // 某些 ROM 可能限制无障碍服务调 startForeground，失败则静默降级
        }
    }

    /** 持有 CPU 唤醒锁（PARTIAL_WAKE_LOCK：仅保持 CPU 运行，屏幕可关） */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ReReply:A11yWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire() // 常持，服务存活期间不释放
            }
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!settings.serviceEnabled) return
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                clearSendTargets()
                // 系统设置/权限弹窗出现时自动收起悬浮窗（避免遮挡系统弹窗）
                ReplyFloatService.autoHideIfSystemUi(event.packageName?.toString())
                // 窗口切换：延迟 600ms 等界面稳定后读屏
                scheduleRead(600)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                val isChat = pkg.contains("mobileqq", true) ||
                    pkg.contains("weixin", true) || pkg.contains("wechat", true)
                if (isChat) {
                    // 聊天界面内容变化（新消息/滚动）：节流读取
                    scheduleRead(800)
                }
            }
        }
    }

    private fun scheduleRead(delayMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 1000) return  // 节流 1s
        readJob?.cancel()
        readJob = scope.launch {
            delay(delayMs)
            readScreenAndTrigger()
        }
    }

    /** 读屏 → 提取上下文 → 触发 AI */
    private fun readScreenAndTrigger() {
        val root = rootInActiveWindow ?: return
        val snapshot = try {
            ScreenReader.readChat(root)
        } catch (_: Exception) {
            null
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }

        if (snapshot != null) {
            handleSnapshot(snapshot)
            return
        }

        // 控件树读不到 → OCR 兜底
        if (OcrManager.isAuthorized()) {
            scope.launch {
                try {
                    val lines = OcrManager.captureAndOcr(this@NotifyAccessibilityService)
                    if (!lines.isNullOrEmpty()) {
                        val latest = lines.last()
                        if (latest.isNotBlank()) {
                            handleSnapshot(
                                ScreenReader.ChatSnapshot("ocr", lines.takeLast(10), latest)
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // OCR 全流程已内部防崩，这里再兜一层，绝不闪退
                }
            }
        }
    }

    private fun handleSnapshot(snap: ScreenReader.ChatSnapshot) {
        val latest = snap.latest
        if (latest.isBlank() || latest.length > 200) return

        // 自己发送的过滤
        if (latest == lastSentByUs) return

        // 去重：只比最新一条消息的内容（新消息来了最新一条肯定变）
        val hashKey = latest.hashCode().toString()
        if (hashKey == lastContentHash) return
        lastContentHash = hashKey
        lastTriggerTime = System.currentTimeMillis()

        currentContext = snap.lines
        currentLatest = latest
        settings.lastChatPackage = snap.packageName

        aiJob?.cancel()
        aiJob = scope.launch {
            try {
                val replies = aiClient.generateReplies(currentContext, currentLatest)
                mainHandler.post {
                    if (replies.isNotEmpty()) {
                        ReplyFloatService.show(this@NotifyAccessibilityService, currentLatest, replies)
                    } else {
                        ReplyFloatService.show(
                            this@NotifyAccessibilityService,
                            currentLatest,
                            listOf("⚠️ AI 没返回内容，试试刷新")
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch // 正常取消（新消息覆盖），静默不显示
                mainHandler.post {
                    ReplyFloatService.show(
                        this@NotifyAccessibilityService,
                        currentLatest,
                        listOf("⚠️ ${e.message?.take(50) ?: "AI 调用失败"} · 点刷新重试")
                    )
                }
            }
        }
    }

    /** 刷新：用当前上下文重新生成 */
    private fun regenerate() {
        if (currentLatest.isBlank()) {
            ReplyFloatService.show(this, "还没有上下文", listOf("先等群里来消息再刷新"))
            return
        }
        aiJob?.cancel()
        aiJob = scope.launch {
            try {
                val replies = aiClient.generateReplies(currentContext, currentLatest)
                mainHandler.post {
                    if (replies.isNotEmpty()) {
                        ReplyFloatService.show(this@NotifyAccessibilityService, currentLatest, replies)
                    } else {
                        ReplyFloatService.show(
                            this@NotifyAccessibilityService,
                            currentLatest,
                            listOf("⚠️ 刷新失败，模型没返回内容")
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch // 正常取消，静默不显示
                mainHandler.post {
                    ReplyFloatService.show(
                        this@NotifyAccessibilityService,
                        currentLatest,
                        listOf("⚠️ ${e.message?.take(50) ?: "AI 调用失败"}")
                    )
                }
            }
        }
    }

    // ─────────────────────── 自动发送 ───────────────────────

    private fun clearSendTargets() {
        lastEditNode?.recycle()
        lastSendNode?.recycle()
        lastEditNode = null
        lastSendNode = null
    }

    private fun refreshSendTargets() {
        clearSendTargets()
        val root = rootInActiveWindow ?: return
        try {
            lastEditNode = findNode(root) { n ->
                n.className?.toString()?.contains("EditText") == true
            }
            lastSendNode = findNode(root) { n ->
                (n.text?.toString()?.contains("发送") == true) ||
                    (n.contentDescription?.toString()?.contains("发送") == true)
            }
        } catch (_: Exception) {}
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun doSend(text: String) {
        lastSentByUs = text
        if (!settings.autoSend) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("reply", text))
            mainHandler.post {
                android.widget.Toast.makeText(this, "已复制，去粘贴发送吧", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        mainHandler.post { tryAutoSend(text) }
    }

    private fun tryAutoSend(text: String) {
        if (lastEditNode == null) refreshSendTargets()

        val edit = lastEditNode ?: return
        val rect = Rect()
        edit.getBoundsInScreen(rect)
        if (rect.isEmpty) return

        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        mainHandler.postDelayed({
            val send = lastSendNode
            if (send != null) {
                val sRect = Rect()
                send.getBoundsInScreen(sRect)
                if (!sRect.isEmpty) {
                    clickAt(sRect.centerX(), sRect.centerY())
                    return@postDelayed
                }
            }
            // 找不到发送按钮：点输入框上方回车兜底
            mainHandler.postDelayed({
                clickAt(rect.centerX(), rect.centerY() - 40)
            }, 300)
        }, 350)
    }

    private fun clickAt(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        aiJob?.cancel()
        readJob?.cancel()
        scope.cancel()
        clearSendTargets()
        // 释放唤醒锁
        try { wakeLock?.release() } catch (_: Exception) { wakeLock = null }
        // 停止前台
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}

        // 被系统/ROM 关闭时，立即检查并通知用户一键恢复
        val wasEnabled = settings.serviceEnabled
        val appCtx = applicationContext
        super.onDestroy()
        if (wasEnabled) {
            // 更激进的恢复检测：500ms 即检查（切应用导致的关闭非常快）
            val delays = listOf(500L, 2000L, 5000L, 10000L)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            delays.forEach { delayMs ->
                handler.postDelayed({
                    try { AccessibilityGuard.notifyIfClosed(appCtx) } catch (_: Exception) {}
                }, delayMs)
            }
            // 同时尝试拉起悬浮窗前台服务（提高进程优先级，有助于自动恢复）
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    appCtx,
                    android.content.Intent(appCtx, ReplyFloatService::class.java)
                        .setAction(ReplyFloatService.ACTION_HIDE)
                )
            } catch (_: Exception) {}
        }
    }
}
