package com.lelecz.reply

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    companion object {
        /** 最近一次崩溃摘要（进程内） */
        var lastCrash: String = ""
            private set

        /** 读取崩溃日志全文 */
        fun readCrashLog(context: android.content.Context): String {
            val f = File(context.filesDir, "crash.log")
            return if (f.exists()) f.readText().takeLast(4000) else "（无崩溃记录）"
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 全局崩溃捕获：任何未捕获异常写入本地日志，不弹窗，下次打开可查看
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.append("=== ").append(
                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                ).append(" ===\n")
                sb.append("线程: ").append(thread.name).append("\n")
                sb.append(Log.getStackTraceString(throwable)).append("\n\n")
                val f = File(filesDir, "crash.log")
                FileWriter(f, true).use { it.write(sb.toString()) }
                lastCrash = throwable.toString().take(200)
            } catch (_: Throwable) {}
        }
    }
}
