package com.qqbq.export

import android.content.Context

object Prefs {

    private const val FILE = "qqbq"
    private const val KEY_ROOTS = "roots"
    private const val KEY_STATIC = "static"

    /** QQ 表情缓存最可能在的地方。老的 .emotionsm 是「原创表情包」缓存目录。 */
    val DEFAULT_ROOTS = listOf(
        // 表情缓存
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/.emotionsm",
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/emotionsm",
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/.emotion",
        // 聊天图片（转发/发送过的动图会落在这下面）
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic",
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/photo",
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/thumb",
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/QQ_Images",
        "/sdcard/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
        // 整个 QQ 外置目录（兜底，最全）
        "/sdcard/Android/data/com.tencent.mobileqq",
        // 旧版 QQ / TIM
        "/sdcard/tencent/MobileQQ/.emotionsm",
        "/sdcard/Tencent/MobileQQ/.emotionsm",
        "/sdcard/Android/data/com.tencent.tim/Tencent/TIM/.emotionsm"
    )

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun roots(c: Context): List<String> {
        val raw = sp(c).getString(KEY_ROOTS, null) ?: return DEFAULT_ROOTS
        val list = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) DEFAULT_ROOTS else list
    }

    fun setRoots(c: Context, roots: List<String>) {
        sp(c).edit().putString(KEY_ROOTS, roots.joinToString("\n")).apply()
    }

    fun wantStatic(c: Context): Boolean = sp(c).getBoolean(KEY_STATIC, false)

    fun wantDedupe(c: Context): Boolean = sp(c).getBoolean("dedupe", true)

    fun setWantDedupe(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("dedupe", value).apply()
    }

    fun setWantStatic(c: Context, value: Boolean) {
        sp(c).edit().putBoolean(KEY_STATIC, value).apply()
    }
}
