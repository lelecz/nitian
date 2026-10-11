package com.lelecz.reply.data

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.net.Uri
import android.content.Context
import java.io.File

/**
 * 自定义壁纸：SAF 选图后复制到应用内部存储（不上传、不落公共相册），
 * 三个页面共享同一张背景。
 */
object WallpaperStore {

    private const val FILE_NAME = "wallpaper.img"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun exists(context: Context) = file(context).exists()

    /** 把 SAF 选到的图片复制为内部壁纸，返回是否成功 */
    fun saveFrom(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file(context).outputStream().use { output -> input.copyTo(output) }
        } != null
    } catch (e: Exception) {
        false
    }

    /** 清除壁纸，恢复默认渐变背景 */
    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * 把壁纸插到内容层之下（必须在 setContentView 之后调用）。
     * 结构变为：[壁纸 ImageView, 半透明蒙层, 内容 ScrollView]
     */
    fun attach(activity: Activity, frame: FrameLayout) {
        if (!exists(activity)) return
        try {
            // 采样解码，避免大图 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file(activity).path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            val screenMax = maxOf(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels
            ) * 2
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > screenMax) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                file(activity).path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val iv = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }
            frame.addView(iv, 0, lp)
            // 蒙层：保证粉字标题与卡片在任何壁纸上都可读
            val scrim = View(activity).apply { setBackgroundColor(0x40FFFFFF) }
            frame.addView(scrim, 1, lp)
        } catch (_: Exception) {
        }
    }
}
