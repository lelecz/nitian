package com.lelecz.reply.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import com.lelecz.reply.R

/**
 * 液态玻璃弹窗：
 * - 玻璃底：bg_glass_dialog（半透明渐变 + 1dp 高光描边 + 24dp 圆角）
 * - Android 12+：窗口后方实时模糊（Window#setBackgroundBlurRadius），壁纸/页面内容透出毛玻璃效果
 */
object Glass {

    fun builder(context: Context): AlertDialog.Builder =
        AlertDialog.Builder(context, R.style.GlassDialog)
}

/** show() 并叠加玻璃模糊（API 31+ 才有窗口级模糊，低版本自动降级为半透明玻璃底） */
fun AlertDialog.Builder.showGlass(): AlertDialog {
    val d = show()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            d.window?.setBackgroundBlurRadius(32)
        } catch (_: Throwable) {
        }
    }
    return d
}
