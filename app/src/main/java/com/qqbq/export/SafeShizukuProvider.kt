package com.qqbq.export

import android.content.Context
import android.content.pm.ProviderInfo
import rikka.shizuku.ShizukuProvider

/**
 * 关掉 Shizuku API 的「Sui 自动初始化」。
 *
 * 原因：ShizukuProvider.onCreate() 里会调 Sui.init()，而 Sui 需要反射拿
 * android.os.ServiceManager，在较新的 Android 上这个反射可能拿不到（getService == null），
 * 于是 Sui.requestBinder() 里 getService.invoke(...) 抛 NullPointerException——
 * 它只 catch 了 IllegalAccessException / InvocationTargetException，NPE 会一路抛穿
 * ContentProvider.onCreate，进程在 Application.onCreate 之前就被系统杀掉，
 * 表现就是「一打开就闪退」。
 *
 * attachInfo 比 onCreate 更早执行，所以在这里把开关关掉最稳。
 * 关掉只影响 Sui（另一种免 root 方案），不影响正常的 Shizuku 连接。
 */
class SafeShizukuProvider : ShizukuProvider() {

    override fun attachInfo(context: Context, info: ProviderInfo) {
        try {
            ShizukuProvider.disableAutomaticSuiInitialization()
        } catch (ignored: Throwable) {
        }
        super.attachInfo(context, info)
    }
}
