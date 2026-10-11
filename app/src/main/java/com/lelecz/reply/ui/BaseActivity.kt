package com.lelecz.reply.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lelecz.reply.R
import com.lelecz.reply.data.WallpaperStore

/**
 * 三个页面（主页 / API 页 / 设置页）共享的骨架与 Q 弹组件。
 * 子类在 onCreate 中调用 [createPage] 拿到内容容器，再 [playEnter] 播放入场动画。
 */
open class BaseActivity : AppCompatActivity() {

    // Q弹配色
    protected val cPink = 0xFFFF8FAB.toInt()
    protected val cPinkDark = 0xFFF06E94.toInt()
    protected val cPurple = 0xFFB8A9F0.toInt()
    protected val cPurpleDark = 0xFF9D8AE8.toInt()
    protected val cBlue = 0xFF8EC9F0.toInt()
    protected val cBlueDark = 0xFF6FB4E8.toInt()
    protected val cText = 0xFF5A4A52.toInt()
    protected val cTextLight = 0xFF9B8A92.toInt()
    protected val cSection = 0xFFE86A92.toInt()

    /**
     * 页面骨架：FrameLayout（底层壁纸）+ ScrollView（默认渐变；有壁纸时透明）+ 内容容器。
     * 内部完成 setContentView。
     */
    protected fun createPage(): LinearLayout {
        val frame = FrameLayout(this)
        val hasWallpaper = WallpaperStore.exists(this)
        val scroll = ScrollView(this).apply {
            if (!hasWallpaper) {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFFFFF0F5.toInt(), 0xFFF7F1FF.toInt(), 0xFFEFF6FF.toInt())
                )
            }
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(44))
        }
        scroll.addView(container)
        frame.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(frame)
        if (hasWallpaper) WallpaperStore.attach(this, frame)
        return container
    }

    /** 页面入场 Q 弹动画 */
    protected fun playEnter(container: LinearLayout) {
        container.alpha = 0f
        container.translationY = dp(30).toFloat()
        container.animate().alpha(1f).translationY(0f).setDuration(400)
            .setInterpolator(OvershootInterpolator(0.6f)).start()
    }

    // ─────────── Q弹组件 ───────────

    /** 白色圆角卡片 */
    protected fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_q_card)
            elevation = dp(2).toFloat()
            val mlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mlp.bottomMargin = dp(14)
            layoutParams = mlp
        }
    }

    /** Q弹主按钮：粉紫渐变 + 按压回弹 */
    protected fun qBtn(text: String, primary: Boolean, blue: Boolean = false): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setAllCaps(false)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = if (blue)
                resources.getDrawable(R.drawable.bg_q_btn_blue, null)
            else if (primary)
                resources.getDrawable(R.drawable.bg_q_btn_pink, null)
            else
                resources.getDrawable(R.drawable.bg_q_btn_soft, null)
            if (!primary && !blue) setTextColor(cPinkDark)
            qbounce()
        }
    }

    /** 按压弹性回弹（Q弹手感核心） */
    protected fun View.qbounce() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(1.6f)).start()
                    false
                }
                else -> false
            }
        }
    }

    /** Q弹输入框 */
    protected fun EditText.qInput() {
        textSize = 15f
        setTextColor(cText)
        setHintTextColor(0xFFC9B3BB.toInt())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = resources.getDrawable(R.drawable.bg_q_input, null)
        maxLines = 1
    }

    /** Q弹开关 */
    protected fun qSwitch(): Switch {
        return Switch(this).apply {
            textSize = 14f
            setTextColor(cText)
            trackDrawable = resources.getDrawable(R.drawable.bg_q_switch_track, null)
            thumbDrawable = resources.getDrawable(R.drawable.bg_q_switch_thumb, null)
        }
    }

    /** Q弹滑块 */
    protected fun qSeekBar(): SeekBar {
        return SeekBar(this).apply {
            progressDrawable = resources.getDrawable(R.drawable.seekbar_progress, null)
            thumb = resources.getDrawable(R.drawable.seekbar_thumb, null)
            splitTrack = false
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
    }

    /**
     * 滑条行：左侧固定标签 + 右对齐数值（拖动时标签不跳动）+ 下方滑条
     * @return 创建的 SeekBar（保存逻辑需要读取 progress）
     */
    protected fun sliderRow(
        parent: LinearLayout,
        label: String,
        maxProgress: Int,
        initialProgress: Int,
        format: (Int) -> String
    ): SeekBar {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nameView = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(cText)
        }
        val valueView = TextView(this).apply {
            text = format(initialProgress)
            textSize = 14f
            setTextColor(cPinkDark)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.END
        }
        row.addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
        val bar = qSeekBar().apply {
            max = maxProgress
            progress = initialProgress
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    valueView.text = format(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        parent.addView(bar, lp())
        return bar
    }

    /** 应用大标题（仅主页使用） */
    protected fun title(): TextView {
        return TextView(this).apply {
            text = "逆天回复"
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(cSection)
        }
    }

    /** 副标题 */
    protected fun subTitle(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(cTextLight)
            setPadding(0, dp(2), 0, dp(14))
        }
    }

    /** 子页面页头（API 设置 / 应用设置） */
    protected fun pageHeader(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(cSection)
            setPadding(0, dp(4), 0, dp(16))
        }
    }

    /** 卡片内分节标题 */
    protected fun sectionTitle(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(cPinkDark)
            setPadding(0, 0, 0, dp(10))
        }
    }

    /** 带小标签的控件行 */
    protected fun field(label: String, child: View): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val tv = TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(cTextLight)
            setPadding(dp(4), 0, 0, dp(5))
        }
        wrap.addView(tv)
        wrap.addView(child)
        return wrap
    }

    protected fun spacer(h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            )
        }
    }

    protected fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
