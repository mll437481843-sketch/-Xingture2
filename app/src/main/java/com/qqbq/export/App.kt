package com.qqbq.export

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 只干一件事：把启动过程与崩溃堆栈写到 Download/qqbq-*.txt，方便排查"一打开就退"。
 * 钩子放在【构造器】里，因为 ContentProvider（Shizuku 的）比 Application.onCreate 更早执行。
 */
class App : Application() {

    init {
        installCrashHandler()
    }

    override fun onCreate() {
        super.onCreate()
        note("BOOT", "sdk=" + Build.VERSION.SDK_INT + " pid=" + android.os.Process.myPid() + " 包已启动")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                note("CRASH", "线程=" + thread.name + "\n" + writer.toString())
            } catch (ignored: Throwable) {
            }
            if (previous != null) {
                try {
                    previous.uncaughtException(thread, error)
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    fun note(tag: String, text: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val body = "[" + stamp + "] " + tag + "\n" + text + "\n"
        publish("qqbq-" + tag.lowercase(Locale.US) + "-" + System.currentTimeMillis() + ".txt", body)
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "diagnose.txt").appendText(body)
        } catch (ignored: Throwable) {
        }
    }

    fun publish(name: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }
        } catch (ignored: Throwable) {
        }
    }
}
