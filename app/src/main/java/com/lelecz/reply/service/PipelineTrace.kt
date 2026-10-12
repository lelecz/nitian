package com.lelecz.reply.service

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 管线追踪：记录「事件 → 悬浮窗」一次完整链路各环节耗时。
 * - 实时：服务在关键环节调 ReplyFloatService.showStatus 显示状态行
 * - 诊断：设置页调 formatReport() 展示最近几次的每步耗时
 * 开销：每环节一次 SystemClock 取值（亚微秒级），不影响无障碍读屏。
 */
object PipelineTrace {

    data class Step(val name: String, val ms: Long)

    /** 一次完整链路记录 */
    data class Run(
        val time: String,        // 触发时刻 HH:mm:ss
        val steps: List<Step>,   // 各环节（按发生顺序）
        val total: Long,         // 总耗时 ms
        val note: String         // 补充说明（OCR 兜底 / 跳过原因 / 失败原因）
    )

    private const val MAX_RUNS = 5
    private val runs = ArrayDeque<Run>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile
    private var current: Handle? = null

    /**
     * 一次管线的记录句柄。
     * 被新事件覆盖（superseded）后自动失效：后续 mark/note/finish 静默忽略，
     * 因此旧消息的 AI 请求晚归时不会污染新一次记录。
     */
    class Handle {
        @Volatile
        internal var dead = false
        private val t0 = SystemClock.elapsedRealtime()
        private var last = t0
        private val steps = mutableListOf<Step>()
        private var note = ""

        fun isLive(): Boolean = !dead

        /** 记录一个环节：耗时 = 距上一次 mark（或开始）的毫秒数 */
        fun mark(name: String) {
            if (dead) return
            val now = SystemClock.elapsedRealtime()
            steps.add(Step(name, now - last))
            last = now
        }

        fun note(text: String) {
            if (!dead) note = text
        }

        fun finish() {
            if (dead) return
            dead = true
            if (current === this) current = null
            val total = SystemClock.elapsedRealtime() - t0
            synchronized(runs) {
                runs.addFirst(Run(fmt.format(Date()), steps.toList(), total, note))
                while (runs.size > MAX_RUNS) runs.removeLast()
            }
        }
    }

    /**
     * 开始一次记录。
     * @param trace false = 本次不记录（如非聊天界面的窗口切换），返回的句柄所有操作为空操作
     */
    fun begin(trace: Boolean = true): Handle {
        if (!trace) return Handle().apply { dead = true }
        current?.dead = true
        val h = Handle()
        current = h
        return h
    }

    /** 最近的记录（新 → 旧） */
    fun recent(): List<Run> = synchronized(runs) { runs.toList() }

    /** 诊断弹窗文本 */
    fun formatReport(): String {
        val list = recent()
        if (list.isEmpty()) {
            return "还没有记录。\n\n等群消息触发一次回复后，再回来看每一步的耗时，就知道慢在哪。"
        }
        val sb = StringBuilder("最近 ${list.size} 次链路（新 → 旧）")
        list.forEachIndexed { i, r ->
            sb.append("\n\n#").append(list.size - i).append("  ").append(r.time)
                .append("  · 总 ").append(fmtMs(r.total))
            val slowest = r.steps.maxByOrNull { it.ms }
            r.steps.forEach { s ->
                sb.append("\n  · ").append(s.name).append("：").append(fmtMs(s.ms))
                if (s === slowest && s.ms > 0) sb.append("  ◀ 最慢")
            }
            if (r.note.isNotBlank()) sb.append("\n  ↳ ").append(r.note)
        }
        return sb.toString()
    }

    private fun fmtMs(ms: Long): String =
        if (ms >= 1000) String.format(Locale.getDefault(), "%.2fs", ms / 1000.0) else "${ms}ms"
}
