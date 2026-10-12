package com.lelecz.reply.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 汽车转速表风格的圆形仪表盘。
 * value 0f..maxValue 映射到 270° 弧形（左下 → 右下，顶部为最大值）。
 * 右侧 20% 区域高亮为红色警戒区。
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前数值（0..maxValue） */
    var value: Float = 0f
        set(v) { field = v.coerceIn(0f, maxValue); invalidate() }

    /** 满量程值 */
    var maxValue: Float = 100f
        set(v) { field = v.coerceAtLeast(1f); invalidate() }

    /** 中心显示的数字（默认跟随 value，可覆盖） */
    var centerText: String? = null

    /** 底部副文字（如 "今日回复"） */
    var subText: String = ""

    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val paintCenterCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintTextBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val paintTextSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    // 颜色：深色金属质感，警戒区红色
    var colorTrack = 0x22000000
    var colorGreen = 0xFF22C55E.toInt()     // 低区
    var colorYellow = 0xFFF59E0B.toInt()    // 中区
    var colorRed = 0xFFEF4444.toInt()       // 警戒区
    var colorNeedle = 0xFF111111.toInt()
    var colorNeedleCenter = 0xFFEF4444.toInt()
    var colorTextBig = 0xFF222222.toInt()
    var colorTextSmall = 0xFF777777.toInt()
    var colorLabel = 0xFF555555.toInt()

    // 弧度范围：从左下（150°）扫到右下（390°），即 240°，
    // 底部 90° 全部是指针盲区，留给数字排版
    private val startAngle = 150f
    private val sweepAngle = 240f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        // 几何：圆弧占上部，圆心下方 0.85r 以下为“指针盲区”（扫掠范围 135°~405°，
        // 指针最长 0.78r，最低只到 y=0.55r），大数字/标签全部放盲区，永不与指针重叠
        val radius = kotlin.math.min(w * 0.45f, (h - dp(4f)) / 2.43f)
        val cy = radius * 1.2f + dp(4f)

        val stroke = radius * 0.12f
        paintTrack.strokeWidth = stroke
        paintArc.strokeWidth = stroke * 0.85f

        // 外轨道（浅灰）
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paintTrack.color = colorTrack
        canvas.drawArc(rect, startAngle, sweepAngle, false, paintTrack)

        // 按 value 比例分段上色（绿→黄→红）
        val ratio = (value / maxValue).coerceIn(0f, 1f)
        val totalSweep = sweepAngle * ratio
        val greenSweep = sweepAngle * 0.55f          // 0%~55% 绿
        val yellowSweep = sweepAngle * 0.25f         // 55%~80% 黄
        // 80%~100% 红

        // 绿色段
        paintArc.color = colorGreen
        val s1 = startAngle
        val e1 = totalSweep.coerceAtMost(greenSweep)
        if (e1 > 0f) canvas.drawArc(rect, s1, e1, false, paintArc)

        // 黄色段（叠加在绿色段之后）
        val yellowStart = s1 + greenSweep
        val yellowSweepActual = totalSweep.coerceAtMost(greenSweep + yellowSweep) - greenSweep
        if (yellowSweepActual > 0f) {
            paintArc.color = colorYellow
            canvas.drawArc(rect, yellowStart, yellowSweepActual, false, paintArc)
        }

        // 红色段（叠加在黄色段之后）
        val redStart = yellowStart + yellowSweep
        val redSweepActual = totalSweep - greenSweep - yellowSweep
        if (redSweepActual > 0f) {
            paintArc.color = colorRed
            canvas.drawArc(rect, redStart, redSweepActual, false, paintArc)
        }

        // 刻度线（25 个刻度，每 10° 一个，覆盖 240°）
        val tickLen = radius * 0.08f
        for (i in 0..24) {
            val a = Math.toRadians((startAngle + i * 10f).toDouble())
            val isMajor = i % 5 == 0
            val l1 = if (isMajor) radius - stroke * 0.6f else radius - stroke * 0.3f
            val l2 = radius + tickLen
            val x1 = cx + cos(a).toFloat() * l1
            val y1 = cy + sin(a).toFloat() * l1
            val x2 = cx + cos(a).toFloat() * l2
            val y2 = cy + sin(a).toFloat() * l2
            // 末段刻度（红区）标红
            val inRedZone = i >= 20
            paintTick.color = if (inRedZone) colorRed else 0xFF999999.toInt()
            paintTick.strokeWidth = if (isMajor) dp(2.5f) else dp(1.2f)
            canvas.drawLine(x1, y1, x2, y2, paintTick)
        }

        // 指针
        val needleAngleDeg = startAngle + sweepAngle * ratio
        val needleAngle = Math.toRadians(needleAngleDeg.toDouble())
        val needleLen = radius * 0.75f
        val nx = cx + cos(needleAngle).toFloat() * needleLen
        val ny = cy + sin(needleAngle).toFloat() * needleLen
        paintNeedle.strokeWidth = dp(3f)
        paintNeedle.color = colorNeedle
        canvas.drawLine(cx, cy, nx, ny, paintNeedle)

        // 中心圆
        paintCenterCircle.color = colorNeedle
        canvas.drawCircle(cx, cy, dp(7f), paintCenterCircle)
        paintCenterCircle.color = colorNeedleCenter
        canvas.drawCircle(cx, cy, dp(3f), paintCenterCircle)

        // 大数字（圆心正下方，指针盲区内：指针最低只到 cy+0.375r）
        paintTextBig.color = colorTextBig
        paintTextBig.textSize = radius * 0.34f
        paintTextBig.isFakeBoldText = true
        val bigText = centerText ?: String.format("%,d", value.toInt())
        canvas.drawText(bigText, cx, cy + radius * 0.72f, paintTextBig)

        // 底部一行：0 — 副文字 — 量程上限（同一基线，互不重叠）
        paintTextSmall.color = colorTextSmall
        paintTextSmall.textSize = radius * 0.09f
        val bottomY = cy + radius * 1.0f
        canvas.drawText("0", cx - radius * 0.72f, bottomY, paintTextSmall)
        canvas.drawText(
            String.format("%,d", maxValue.toInt()),
            cx + radius * 0.72f, bottomY, paintTextSmall
        )
        if (subText.isNotEmpty()) {
            paintLabel.color = colorLabel
            paintLabel.textSize = radius * 0.11f
            canvas.drawText(subText, cx, bottomY, paintLabel)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
