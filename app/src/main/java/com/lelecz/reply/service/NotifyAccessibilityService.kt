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
    private lateinit var stats: com.lelecz.reply.data.StatsManager

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
        stats = com.lelecz.reply.data.StatsManager(this)
        aiClient = AiClient(settings, stats)
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
                val pkg = event.packageName?.toString()
                ReplyFloatService.autoHideIfSystemUi(pkg)
                // 窗口切换：延迟 600ms 等界面稳定后读屏（聊天/OCR 场景才记录管线）
                val trace = pkg != null && (ScreenReader.isChatPackage(pkg) || OcrManager.isAuthorized())
                scheduleRead(600, trace)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (ScreenReader.isChatPackage(pkg)) {
                    // 聊天界面内容变化（新消息/滚动）：节流读取
                    scheduleRead(800, true)
                }
            }
        }
    }

    private fun scheduleRead(delayMs: Long, trace: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 1000) return  // 节流 1s
        readJob?.cancel()
        val run = PipelineTrace.begin(trace)
        readJob = scope.launch {
            delay(delayMs)
            run.mark("界面稳定等待")
            if (run.isLive()) ReplyFloatService.showStatus(this@NotifyAccessibilityService, "读取控件树…")
            readScreenAndTrigger(run)
        }
    }

    /** 读屏 → 提取上下文 → 触发 AI（run = 管线记录句柄） */
    private fun readScreenAndTrigger(run: PipelineTrace.Handle) {
        val root = rootInActiveWindow
        if (root == null) {
            run.note("拿不到根节点（窗口未就绪）")
            run.finish()
            return
        }
        run.mark("取根节点")
        if (run.isLive()) ReplyFloatService.showStatus(this, "遍历控件树…")
        val snapshot = try {
            ScreenReader.readChat(root)
        } catch (_: Exception) {
            null
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
        run.mark("控件树遍历")

        if (snapshot != null) {
            handleSnapshot(run, snapshot)
            return
        }

        // 控件树读不到 → OCR 兜底
        if (OcrManager.isAuthorized()) {
            run.note("控件树读不到 → OCR 兜底")
            if (run.isLive()) ReplyFloatService.showStatus(this, "控件树读不到，OCR 兜底中…")
            scope.launch {
                try {
                    val lines = OcrManager.captureAndOcr(this@NotifyAccessibilityService)
                    if (!lines.isNullOrEmpty()) {
                        val latest = lines.last()
                        if (latest.isNotBlank()) {
                            run.mark("OCR 截图识别")
                            handleSnapshot(
                                run,
                                ScreenReader.ChatSnapshot("ocr", lines.takeLast(10), latest)
                            )
                        } else {
                            run.note("OCR 没识别到文字")
                            run.finish()
                        }
                    } else {
                        run.note("OCR 无结果")
                        run.finish()
                    }
                } catch (_: Throwable) {
                    // OCR 全流程已内部防崩，这里再兜一层，绝不闪退
                    run.note("OCR 失败")
                    run.finish()
                }
            }
        } else {
            run.note("没有可读的聊天列表（当前应用不识别，OCR 未授权）")
            run.finish()
        }
    }

    private fun handleSnapshot(run: PipelineTrace.Handle, snap: ScreenReader.ChatSnapshot) {
        val latest = snap.latest
        if (latest.isBlank() || latest.length > 200) {
            run.note("最新消息为空或过长，跳过")
            run.finish()
            return
        }

        // 自己发送的过滤
        if (latest == lastSentByUs) {
            run.note("是我们自己发的消息，跳过")
            run.finish()
            return
        }

        // 去重：只比最新一条消息的内容（新消息来了最新一条肯定变）
        val hashKey = latest.hashCode().toString()
        if (hashKey == lastContentHash) {
            run.note("重复消息（内容没变），防抖")
            run.finish()
            return
        }
        lastContentHash = hashKey
        lastTriggerTime = System.currentTimeMillis()

        currentContext = snap.lines
        currentLatest = latest
        settings.lastChatPackage = snap.packageName

        run.mark("过滤去重")
        if (run.isLive()) ReplyFloatService.showStatus(this, "AI 生成中…")

        aiJob?.cancel()
        aiJob = scope.launch {
            try {
                val replies = aiClient.generateReplies(currentContext, currentLatest)
                run.mark("AI 生成")
                run.finish()
                mainHandler.post {
                    if (replies.isNotEmpty()) {
                        ReplyFloatService.show(this@NotifyAccessibilityService, currentLatest, replies)
                    } else {
                        ReplyFloatService.show(
                            this@NotifyAccessibilityService,
                            currentLatest,
                            listOf("AI 没返回内容，试试刷新")
                        )
                    }
                }
            } catch (e: Exception) {
                run.note("AI 调用失败：${e.message?.take(60) ?: "未知错误"}")
                run.finish()
                if (e is CancellationException) return@launch // 正常取消（新消息覆盖），静默不显示
                mainHandler.post {
                    ReplyFloatService.show(
                        this@NotifyAccessibilityService,
                        currentLatest,
                        listOf("${e.message?.take(50) ?: "AI 调用失败"} · 点刷新重试")
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
        val run = PipelineTrace.begin()
        if (run.isLive()) ReplyFloatService.showStatus(this, "重新生成中…")
        aiJob?.cancel()
        aiJob = scope.launch {
            try {
                val replies = aiClient.generateReplies(currentContext, currentLatest)
                run.mark("AI 生成")
                run.finish()
                mainHandler.post {
                    if (replies.isNotEmpty()) {
                        ReplyFloatService.show(this@NotifyAccessibilityService, currentLatest, replies)
                    } else {
                        ReplyFloatService.show(
                            this@NotifyAccessibilityService,
                            currentLatest,
                            listOf("刷新失败，模型没返回内容")
                        )
                    }
                }
            } catch (e: Exception) {
                run.note("AI 调用失败：${e.message?.take(60) ?: "未知错误"}")
                run.finish()
                if (e is CancellationException) return@launch // 正常取消，静默不显示
                mainHandler.post {
                    ReplyFloatService.show(
                        this@NotifyAccessibilityService,
                        currentLatest,
                        listOf("${e.message?.take(50) ?: "AI 调用失败"}")
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
            // 输入框：找所有 EditText，优先选屏幕底部的（聊天输入框通常在底部）
            val edits = mutableListOf<AccessibilityNodeInfo>()
            findAllNodes(root) { n ->
                n.className?.toString()?.contains("EditText") == true && n.isEditable
            }.let { edits.addAll(it) }
            if (edits.isNotEmpty()) {
                lastEditNode = edits.maxByOrNull { n ->
                    val r = Rect(); n.getBoundsInScreen(r); r.centerY()
                }
            }
            // 发送按钮：匹配"发送"/"Send"，优先找可点击的
            lastSendNode = findNode(root) { n ->
                val text = n.text?.toString() ?: ""
                val desc = n.contentDescription?.toString() ?: ""
                (text.contains("发送", true) || text.contains("send", true) ||
                    desc.contains("发送", true) || desc.contains("send", true)) &&
                    (n.isClickable || n.parent?.isClickable == true)
            }
        } catch (_: Exception) {}
    }

    private fun findAllNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val stack = mutableListOf(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (predicate(node)) result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return result
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
        // 记录一次回复（发送或复制都算）
        stats.recordReply(settings.persona)
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
