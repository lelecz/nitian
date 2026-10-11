package com.lelecz.reply.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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

    /** createPage 是否已执行（onResume 壁纸刷新判断用） */
    private var pageReady = false
    /** 建页时壁纸是否存在 */
    private var wallpaperActive = false

    /**
     * 页面骨架：
     * - 边到边（状态栏透明），壁纸从屏幕最顶端铺到最底端
     * - FrameLayout 底层壁纸 → 内容列（ScrollView + 底部导航栏）
     * 内部完成 setContentView。
     */
    protected fun createPage(): LinearLayout {
        // 边到边：状态栏透明，壁纸可覆盖状态栏区域
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = Color.TRANSPARENT
        }

        val root = FrameLayout(this)
        wallpaperActive = WallpaperStore.exists(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!wallpaperActive) {
                // 无壁纸：默认渐变铺满全屏（含底部导航栏区域）
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFFFFF0F5.toInt(), 0xFFF7F1FF.toInt(), 0xFFEFF6FF.toInt())
                )
            }
        }

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(44))
        }
        scroll.addView(container)
        column.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // 底部导航栏（主页 / API / 设置）
        val nav = buildBottomNav()
        column.addView(
            nav,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        if (wallpaperActive) WallpaperStore.attach(this, root)

        // 系统栏 inset：内容避开状态栏，导航栏避开手势条/三键区
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            container.setPadding(dp(18), dp(22) + bars.top, dp(18), dp(44))
            nav.setPadding(nav.paddingLeft, dp(8), nav.paddingRight, dp(10) + bars.bottom)
            insets
        }
        // 浅色界面：状态栏/导航栏图标用深色
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        root.requestApplyInsets()

        pageReady = true
        return container
    }

    /** 底部导航栏：三个 Tab，当前页高亮，点其他 Tab 切页 */
    private fun buildBottomNav(): LinearLayout {
        val currentTab = when (this) {
            is ApiActivity -> 1
            is SettingsActivity -> 2
            else -> 0
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(0xF2FFFFFF.toInt())
            }
        }
        val tabs = listOf(
            "主页" to HomeActivity::class.java,
            "API" to ApiActivity::class.java,
            "设置" to SettingsActivity::class.java
        )
        tabs.forEachIndexed { i, (label, target) ->
            val selected = i == currentTab
            val tab = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(11), dp(16), dp(11))
                setTextColor(if (selected) cPinkDark else cTextLight)
                if (selected) {
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(0x2AF06E94)
                    }
                }
                setOnClickListener {
                    if (i != currentTab) {
                        startActivity(Intent(this@BaseActivity, target))
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                }
            }
            nav.addView(
                tab,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        return nav
    }

    override fun onResume() {
        super.onResume()
        // 壁纸在别页被更换/清除 → 回到本页自动重建，避免背景停留在旧状态
        if (pageReady && WallpaperStore.exists(this) != wallpaperActive) recreate()
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
