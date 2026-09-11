package com.lelecz.reply.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lelecz.reply.R

/**
 * 悬浮窗服务：显示"对方消息 + AI 建议回复"气泡。
 * - 拖动移动位置（只响应标题栏手柄区域，不拦截按钮点击）
 * - 点击建议 → 回调 NotifyAccessibilityService 自动发送（或复制）
 * - 刷新按钮 → 重新调 AI
 * - 检测到系统设置/权限弹窗时自动隐藏（避免遮挡系统弹窗）
 */
class ReplyFloatService : Service() {

    private lateinit var wm: WindowManager
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var autoHideJob: Runnable? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        const val ACTION_SHOW = "com.lelecz.reply.SHOW"
        const val ACTION_HIDE = "com.lelecz.reply.HIDE"
        const val EXTRA_LAST_MSG = "extra_last_msg"
        const val EXTRA_REPLIES = "extra_replies"

        private var instance: ReplyFloatService? = null

        fun isRunning(): Boolean = instance != null

        /** 外部（无障碍服务）调用：显示气泡 */
        fun show(context: Context, lastMsg: String, replies: List<String>) {
            val s = instance
            if (s != null) {
                s.updateContent(lastMsg, replies)
                return
            }
            val intent = Intent(context, ReplyFloatService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_LAST_MSG, lastMsg)
                .putStringArrayListExtra(EXTRA_REPLIES, ArrayList(replies))
            ContextCompat.startForegroundService(context, intent)
        }

        fun hide(context: Context) {
            context.startService(Intent(context, ReplyFloatService::class.java).setAction(ACTION_HIDE))
        }

        /** 前台是系统设置/权限弹窗时自动收起气泡，避免遮挡 */
        fun autoHideIfSystemUi(pkg: String?) {
            if (pkg == null) return
            val isSystemUi = pkg == "com.android.settings" ||
                pkg == "com.android.systemui" ||
                pkg.contains("permission", true) ||
                pkg.contains("packageinstaller", true)
            if (isSystemUi) {
                instance?.hideBubble()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_SHOW -> {
                val lastMsg = intent.getStringExtra(EXTRA_LAST_MSG) ?: ""
                val replies = intent.getStringArrayListExtra(EXTRA_REPLIES) ?: emptyList()
                showBubble(lastMsg, replies)
            }
            ACTION_HIDE -> hideBubble()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val ch = android.app.NotificationChannel(
            "float", "悬浮窗", android.app.NotificationManager.IMPORTANCE_MIN
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(ch)
        val notification = android.app.Notification.Builder(this, "float")
            .setContentTitle("逆天回复运行中")
            .setContentText("收到群消息时弹出建议")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun showBubble(lastMsg: String, replies: List<String>) {
        hideBubble()

        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_float_bubble, null)
        val msgView = view.findViewById<TextView>(R.id.float_last_msg)
        val replyArea = view.findViewById<LinearLayout>(R.id.float_replies)
        val header = view.findViewById<View>(R.id.float_header)

        msgView.text = lastMsg.ifBlank { "（未获取到消息）" }
        replyArea.removeAllViews()

        if (replies.isEmpty()) {
            replyArea.addView(makeReplyItem("⚠️ AI 生成失败，请检查 API Key / 网络", clickable = false))
        } else {
            replies.forEach { r ->
                replyArea.addView(makeReplyItem(r, clickable = true))
            }
        }

        view.findViewById<TextView>(R.id.float_close).setOnClickListener {
            hideBubble()
        }

        val refreshBtn = view.findViewById<TextView>(R.id.float_refresh)
        refreshBtn.setOnClickListener {
            if (NotifyAccessibilityService.hasInstance()) {
                Toast.makeText(this, "🔄 重新生成中…", Toast.LENGTH_SHORT).show()
                NotifyAccessibilityService.refreshReplies()
            } else {
                Toast.makeText(this, "无障碍服务未开启，刷新无效", Toast.LENGTH_SHORT).show()
            }
        }

        // 拖动：只挂在标题栏手柄上，不拦截建议项/按钮点击
        var initialX = 0f; var initialY = 0f; var touchX = 0f; var touchY = 0f
        var dragging = false
        header.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x?.toFloat() ?: 0f
                    initialY = layoutParams?.y?.toFloat() ?: 0f
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) dragging = true
                    if (dragging) {
                        layoutParams?.apply {
                            x = (initialX + dx).toInt()
                            y = (initialY + dy).toInt()
                        }
                        try { wm.updateViewLayout(v, layoutParams) } catch (_: Exception) {}
                    }
                    true
                }
                else -> true
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 160
        }

        floatView = view
        try {
            wm.addView(view, layoutParams)
        } catch (_: Exception) {}

        // 90 秒无人点击自动收起（防止残留悬浮窗遮挡系统权限弹窗/其他界面）
        autoHideJob?.let { mainHandler.removeCallbacks(it) }
        val job = Runnable { hideBubble() }
        autoHideJob = job
        mainHandler.postDelayed(job, 90_000)
    }

    private fun makeReplyItem(text: String, clickable: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFF1F2937.toInt())
            setPadding(28, 18, 28, 18)
            setBackgroundResource(R.drawable.bg_reply_item)
            if (clickable) {
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    NotifyAccessibilityService.sendReply(text)
                }
            }
        }
    }

    private fun updateContent(lastMsg: String, replies: List<String>) {
        showBubble(lastMsg, replies)
    }

    private fun hideBubble() {
        autoHideJob?.let { mainHandler.removeCallbacks(it) }
        autoHideJob = null
        floatView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        floatView = null
    }

    override fun onDestroy() {
        hideBubble()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
