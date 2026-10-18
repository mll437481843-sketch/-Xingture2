package com.qqbq.export

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 这个类由 Shizuku 在【shell 进程】里用反射创建，uid 是 2000（root 模式下是 0）。
 * 也就是说：这里跑的代码，权限等同 adb shell，能打开 /sdcard/Android/data/<别的应用>/ 下的文件。
 *
 * 注意：构造函数会被 Shizuku 通过反射调用，不要在里面做耗时或依赖 UI 的事。
 */
class UserService : ShellStub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun whoami(): String {
        return "uid=" + android.os.Process.myUid()
    }

    override fun exec(cmd: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (text.length > 400000) text.substring(0, 400000) + "\n[输出过长已截断]" else text
        } catch (t: Throwable) {
            "ERR " + t.javaClass.simpleName + ": " + (t.message ?: "")
        }
    }

    override fun openRead(path: String): ParcelFileDescriptor? {
        return try {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            null
        }
    }
}
