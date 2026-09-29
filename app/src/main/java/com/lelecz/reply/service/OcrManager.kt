package com.lelecz.reply.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * OCR 兜底：MediaProjection 截屏 + Tesseract 本地中文识别。
 * - 授权一次（设置页按钮触发系统弹窗），进程存活期内可复用 token
 * - 小尺寸截图（50%）降低老机内存压力，防 OOM
 * - 全流程捕获 Throwable（含 OOM），任何失败都只返回 null，绝不闪退
 */
object OcrManager {

    private var resultCode: Int = 0
    private var data: Intent? = null

    /** OCR 节流：10 秒内最多执行一次 */
    private var lastOcrTime: Long = 0

    fun isAuthorized(): Boolean = data != null

    fun resetAuth() {
        data = null
    }

    /** 在 SettingsActivity 调用，弹出系统授权（先检测系统是否支持，不支持不弹窗不闪退） */
    fun requestAuth(activity: Activity) {
        try {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mgr.createScreenCaptureIntent()
            // 系统没有屏幕捕获组件（部分定制 ROM 阉割）→ 明确提示，不闪退
            val resolved = activity.packageManager.resolveActivity(intent, 0)
            if (resolved == null) {
                android.widget.Toast.makeText(
                    activity,
                    "此系统不支持屏幕捕获（ROM 阉割），OCR 不可用，但不影响主功能",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            activity.startActivityForResult(intent, 9001)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                activity,
                "屏幕捕获打开失败：${t.message?.take(30) ?: "未知原因"}（不影响主功能）",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 9001 && resultCode == Activity.RESULT_OK && data != null) {
            this.resultCode = resultCode
            this.data = data
        }
    }

    /**
     * 截屏并 OCR。全程防崩：任何异常/内存不足都返回 null。
     */
    suspend fun captureAndOcr(context: Context): List<String>? = withContext(Dispatchers.IO) {
        // 节流：10 秒内只允许一次
        val now = System.currentTimeMillis()
        if (now - lastOcrTime < 10_000) return@withContext null
        lastOcrTime = now

        val data = data ?: return@withContext null
        var projection: MediaProjection? = null
        var vd: VirtualDisplay? = null

        try {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val fullWidth = metrics.widthPixels
            val fullHeight = metrics.heightPixels
            if (fullWidth <= 0 || fullHeight <= 0) return@withContext null
            val dpi = metrics.densityDpi

            // 小尺寸截图：宽度最多 540px，高度按比例（只截聊天区约 72%）
            val scale = 540f / fullWidth
            val vdWidth = 540
            val vdHeight = ((fullHeight * 0.72f) * scale).toInt().coerceAtLeast(1)
            val vdDpi = (dpi * scale).toInt()

            val imageReader = ImageReader.newInstance(vdWidth, vdHeight, PixelFormat.RGBA_8888, 1)
            vd = projection.createVirtualDisplay(
                "reply_ocr", vdWidth, vdHeight, vdDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, Handler(Looper.getMainLooper())
            )

            // 等待第一帧（最多 5 秒）
            var bitmap: Bitmap? = null
            var waited = 0
            while (bitmap == null && waited < 60) {
                Thread.sleep(80)
                waited++
                try {
                    val image = imageReader.acquireLatestImage() ?: continue
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride

                    // 精确按 rowStride 复制，不引入额外宽度
                    val bmp = Bitmap.createBitmap(
                        rowStride / pixelStride, vdHeight, Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    // 裁掉行尾 padding 到实际宽度
                    if (bmp.width != vdWidth) {
                        bitmap = Bitmap.createBitmap(bmp, 0, 0, vdWidth, vdHeight)
                        bmp.recycle()
                    } else {
                        bitmap = bmp
                    }
                    image.close()
                } catch (t: Throwable) {
                    // 单帧失败继续等下一帧，不崩
                }
            }

            bitmap ?: return@withContext null
            return@withContext ocrBitmap(context, bitmap)
        } catch (t: Throwable) {
            // 包括 OutOfMemoryError 在内的所有异常，一律不闪退
            return@withContext null
        } finally {
            try { vd?.release() } catch (_: Throwable) {}
            try { projection?.stop() } catch (_: Throwable) {}
        }
    }

    /** Tesseract 本地 OCR（chi_sim） */
    private fun ocrBitmap(context: Context, bitmap: Bitmap): List<String>? {
        try {
            val dataPath = prepareTessdata(context) ?: return null
            val api = TessBaseAPI()
            if (!api.init(dataPath, "chi_sim")) {
                api.end()
                return null
            }
            try {
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                api.setImage(bitmap)
                val raw = api.utF8Text ?: ""
                return raw.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.length >= 2 }
            } finally {
                api.end()
            }
        } catch (_: Throwable) {
            return null
        } finally {
            try { bitmap.recycle() } catch (_: Throwable) {}
        }
    }

    /** 把 assets/tessdata/chi_sim.traineddata 复制到 filesDir/tessdata/ */
    private fun prepareTessdata(context: Context): String? {
        val tessDir = File(context.filesDir, "tessdata")
        val target = File(tessDir, "chi_sim.traineddata")
        if (!target.exists()) {
            if (!tessDir.exists() && !tessDir.mkdirs()) return null
            try {
                context.assets.open("tessdata/chi_sim.traineddata").use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Throwable) {
                return null
            }
        }
        return context.filesDir.absolutePath
    }
}
