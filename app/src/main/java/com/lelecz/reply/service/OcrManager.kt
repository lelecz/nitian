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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * OCR 兜底：MediaProjection 截屏 + Tesseract 本地中文识别。
 *
 * 关键设计（均为实测踩坑得出）：
 * - VirtualDisplay + ImageReader 常驻复用：安卓 10 上每次截图后 release 掉唯一的
 *   VirtualDisplay，系统会把整个 MediaProjection 一并停掉，第二次就再也拿不到帧。
 * - MediaProjection 缓存复用：令牌不重复消费，只在 resetAuth 释放。
 * - FGS 启动顺序：安卓 10~13 必须在 getMediaProjection 成功【之后】启动（先启动会因
 *   缺投影权限 startForeground 失败 → 5 秒后 RemoteServiceException 崩溃）；
 *   安卓 14+ 必须在 Activity 前台授权回调里【先于】getMediaProjection 启动。
 * - 投影注册 onStop 回调：系统侧停投（用户点投屏图标/安全策略）能感知并要求重新授权。
 * - 全流程防崩：任何异常只返回 null，绝不闪退。
 */
object OcrManager {

    private var resultCode: Int = 0
    private var data: Intent? = null
    private var projection: MediaProjection? = null
    private var lastOcrTime: Long = 0
    private var projectionServiceStarted: Boolean = false

    /** 常驻复用的镜像通道 */
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var vdWidth: Int = 0
    private var vdHeight: Int = 0

    /** 系统侧停止投影（用户下拉点投屏退出/安全策略） */
    @Volatile
    private var projectionStopped: Boolean = false

    /** 上次跳过/失败原因，供管线诊断显示 */
    @Volatile
    var lastSkip: String? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isAuthorized(): Boolean = data != null

    fun resetAuth(context: Context? = null) {
        data = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        projectionStopped = false
        projectionServiceStarted = false
        releaseDisplay()
        try {
            context?.stopService(Intent(context, OcrProjectionService::class.java))
        } catch (_: Throwable) {}
    }

    private fun releaseDisplay() {
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        vd = null
        reader = null
        vdWidth = 0
        vdHeight = 0
    }

    /**
     * 启动 mediaProjection 前台服务。
     * - Android 10~13：在 getMediaProjection 成功【之后】调用（后台可启动，无前台限制）
     * - Android 14+：必须在 Activity 前台授权回调里【先于】getMediaProjection 调用
     */
    private fun ensureProjectionFgs(context: Context, fromActivity: Boolean = false) {
        if (projectionServiceStarted) return
        // Android 12+ 禁止后台进程启动 FGS；34+ 只允许 Activity 授权回调这条路
        if (Build.VERSION.SDK_INT >= 31 && !fromActivity) {
            android.util.Log.w("OcrManager", "API 31+ 后台启动 FGS 受限，跳过（投影本次仍可用）")
            return
        }
        try {
            val intent = Intent(context, OcrProjectionService::class.java)
            ContextCompat.startForegroundService(context, intent)
            projectionServiceStarted = true
        } catch (t: Throwable) {
            android.util.Log.w("OcrManager", "启动 OCR 前台服务失败：${t.message}")
        }
    }

    fun requestAuth(activity: Activity) {
        try {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mgr.createScreenCaptureIntent()
            val resolved = activity.packageManager.resolveActivity(intent, 0)
            if (resolved == null) {
                android.widget.Toast.makeText(
                    activity,
                    "此系统不支持屏幕捕获（ROM 阉割），OCR 不可用，不影响主功能",
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

    /** 从 SettingsActivity 的 onActivityResult 调用 */
    fun onActivityResult(context: Context?, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 9001 && resultCode == Activity.RESULT_OK && data != null) {
            this.resultCode = resultCode
            this.data = data
            // Android 14+：必须先有 mediaProjection FGS 才能调 getMediaProjection，此刻在前台立即启动
            if (Build.VERSION.SDK_INT >= 34 && context != null) {
                ensureProjectionFgs(context, fromActivity = true)
            }
        } else {
            resetAuth(context)
        }
    }

    suspend fun captureAndOcr(context: Context): List<String>? = withContext(Dispatchers.IO) {
        lastSkip = null
        val token = data ?: return@withContext null
        val now = System.currentTimeMillis()
        if (now - lastOcrTime < 10_000) {
            lastSkip = "10 秒内刚截过一次，节流跳过"
            return@withContext null
        }
        lastOcrTime = now

        if (projectionStopped) {
            resetAuth(context.applicationContext)
            lastSkip = "投影已被系统停止，请到设置页重新授权"
            return@withContext null
        }

        try {
            // 1) 投影：只取一次并缓存复用
            val proj = projection ?: try {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mgr.getMediaProjection(resultCode, token)
                projection = p
                projectionStopped = false
                // 监听系统侧停止（下拉退出投屏等），感知后要求重新授权
                try {
                    p.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            projectionStopped = true
                            mainHandler.post { releaseDisplay() }
                        }
                    }, mainHandler)
                } catch (_: Throwable) {}
                // 2) 投影拿到后启动 FGS：系统已授予投影权限，startForeground 才会成功，
                //    同时保活投影防止应用退后台后被系统回收
                if (Build.VERSION.SDK_INT < 34) {
                    ensureProjectionFgs(context, fromActivity = false)
                }
                p
            } catch (se: SecurityException) {
                android.util.Log.e("OcrManager", "getMediaProjection 失败（授权失效）", se)
                resetAuth(context.applicationContext)
                lastSkip = "授权已失效，请到设置页重新授权"
                return@withContext null
            }

            // 3) 计算目标尺寸（只截上半聊天区，宽度压到 540 降低内存压力）
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val fullWidth = metrics.widthPixels
            val fullHeight = metrics.heightPixels
            if (fullWidth <= 0 || fullHeight <= 0) {
                lastSkip = "屏幕尺寸异常"
                return@withContext null
            }
            val scale = 540f / fullWidth
            val wantW = 540
            val wantH = ((fullHeight * 0.72f) * scale).toInt().coerceAtLeast(1)

            // 4) 常驻 VirtualDisplay：首次创建，之后复用（旋转/尺寸变化才重建）
            if (vd == null || reader == null || vdWidth != wantW || vdHeight != wantH) {
                releaseDisplay()
                val r = ImageReader.newInstance(wantW, wantH, PixelFormat.RGBA_8888, 2)
                reader = r
                vdWidth = wantW
                vdHeight = wantH
                vd = proj.createVirtualDisplay(
                    "reply_ocr", wantW, wantH, (metrics.densityDpi * scale).toInt(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.surface, null, mainHandler
                )
                // 给镜像通道一点预热时间
                Thread.sleep(300)
            }

            // 5) 取最新帧（最多等 5 秒）
            val r = reader ?: run { lastSkip = "镜像通道未就绪"; return@withContext null }
            var bitmap: Bitmap? = null
            var waited = 0
            while (bitmap == null && waited < 60) {
                Thread.sleep(80)
                waited++
                try {
                    val image = r.acquireLatestImage() ?: continue
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val bmp = Bitmap.createBitmap(
                        rowStride / pixelStride, vdHeight, Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    bitmap = if (bmp.width != vdWidth) {
                        Bitmap.createBitmap(bmp, 0, 0, vdWidth, vdHeight).also { bmp.recycle() }
                    } else bmp
                    image.close()
                } catch (_: Throwable) {}
            }

            if (bitmap == null) {
                lastSkip = "没等到画面帧（镜像通道未产出画面）"
                return@withContext null
            }
            return@withContext ocrBitmap(context, bitmap!!)
        } catch (t: Throwable) {
            android.util.Log.e("OcrManager", "captureAndOcr 异常", t)
            lastSkip = "截屏异常：${t.message?.take(40) ?: "未知"}"
            return@withContext null
        }
    }

    private fun ocrBitmap(context: Context, bitmap: Bitmap): List<String>? {
        try {
            val dataPath = prepareTessdata(context) ?: run { lastSkip = "OCR 引擎初始化失败"; return null }
            val api = TessBaseAPI()
            if (!api.init(dataPath, "chi_sim")) { api.end(); lastSkip = "OCR 引擎初始化失败"; return null }
            try {
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                api.setImage(bitmap)
                val raw = api.utF8Text ?: ""
                return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() && it.length >= 2 }
            } finally { api.end() }
        } catch (_: Throwable) {
            lastSkip = "OCR 识别异常"
            return null
        } finally {
            try { bitmap.recycle() } catch (_: Throwable) {}
        }
    }

    private fun prepareTessdata(context: Context): String? {
        val tessDir = File(context.filesDir, "tessdata")
        val target = File(tessDir, "chi_sim.traineddata")
        if (!target.exists()) {
            if (!tessDir.exists() && !tessDir.mkdirs()) return null
            try {
                context.assets.open("tessdata/chi_sim.traineddata").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            } catch (_: Throwable) { return null }
        }
        return context.filesDir.absolutePath
    }
}
