package com.qqbq.export

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 只干一件事：拿到 Shizuku 授权以后，把一个跑在 shell 进程里的 UserService 绑起来。
 * 之后的读文件 / 执行命令都通过它，因此不需要 root，也不需要"所有文件访问权限"。
 */
object Shell {

    const val REQUEST_CODE = 41213

    private var appContext: Context? = null

    @Volatile
    private var service: IShell? = null

    @Volatile
    private var latch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder == null) null else ShellStub.asInterface(binder)
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val serviceArgs: Shizuku.UserServiceArgs by lazy {
        val ctx = appContext ?: throw IllegalStateException("Shell.configure() 还没调用")
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("qqbq")
            .debuggable(false)
            .version(1)
    }

    fun configure(context: Context) {
        appContext = context.applicationContext
    }

    fun binderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    fun serviceVersion(): Int {
        return try {
            Shizuku.getVersion()
        } catch (t: Throwable) {
            -1
        }
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            // 由 UI 层提示
        }
    }

    /** 绑定用户服务；失败返回 null（通常意味着没授权 / Shizuku 没跑）。必须在子线程调用。 */
    fun ensureService(timeoutMs: Long = 20000L): IShell? {
        service?.let { return it }
        if (!binderAlive()) return null
        if (!hasPermission()) return null

        val waiter = CountDownLatch(1)
        latch = waiter
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (t: Throwable) {
            latch = null
            return null
        }
        try {
            waiter.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        latch = null
        return service
    }
}
