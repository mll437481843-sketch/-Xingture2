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


    // ---------- 扫描来源：QQ / 微信 ----------

    const val SOURCE_QQ = 0
    const val SOURCE_WECHAT = 1
    const val SOURCE_BOTH = 2

    /** 微信端：新版本在 Android/data/com.tencent.mm，老版本在 tencent/MicroMsg。 */
    val WECHAT_ROOTS = listOf(
        "/sdcard/Android/data/com.tencent.mm/MicroMsg",
        "/sdcard/tencent/MicroMsg",
        "/sdcard/Tencent/MicroMsg",
        // 微信里"保存到手机"的图片会落到这些目录
        "/sdcard/Pictures/WeiXin",
        "/sdcard/DCIM/WeiXin",
        "/sdcard/Pictures/WeChat",
        "/sdcard/DCIM/WeChat",
        "/sdcard/Android/data/com.tencent.mm"
    )

    const val SOURCE_DOUYIN = 3
    const val SOURCE_XHS = 4
    const val SOURCE_ALL = 5

    /** 抖音：缓存里大量 gif/jpg/webp，表情包和评论图都在 cache 下。 */
    val DOUYIN_ROOTS = listOf(
        "/sdcard/Android/data/com.ss.android.ugc.aweme",
        "/sdcard/Pictures/抖音",
        "/sdcard/DCIM/抖音",
        "/sdcard/Pictures/aweme",
        "/sdcard/DCIM/aweme"
    )

    /** 小红书：同理，图片缓存 + 手动保存目录。 */
    val XHS_ROOTS = listOf(
        "/sdcard/Android/data/com.xingin.xhs",
        "/sdcard/Pictures/小红书",
        "/sdcard/DCIM/小红书",
        "/sdcard/Pictures/xhs",
        "/sdcard/DCIM/xhs"
    )

    fun source(c: Context): Int = sp(c).getInt("source", SOURCE_QQ)

    fun setSource(c: Context, value: Int) {
        sp(c).edit().putInt("source", value).apply()
    }

    fun rootsFor(c: Context, source: Int): List<String> {
        return when (source) {
            SOURCE_WECHAT -> WECHAT_ROOTS
            SOURCE_DOUYIN -> DOUYIN_ROOTS
            SOURCE_XHS -> XHS_ROOTS
            SOURCE_BOTH -> roots(c) + WECHAT_ROOTS
            SOURCE_ALL -> roots(c) + WECHAT_ROOTS + DOUYIN_ROOTS + XHS_ROOTS
            else -> roots(c)
        }
    }

    fun flag(c: Context, key: String): Boolean = sp(c).getBoolean(key, false)

    fun setFlag(c: Context, key: String, value: Boolean) {
        sp(c).edit().putBoolean(key, value).apply()
    }

    /** 已经扫描到过的文件 key 集合，用来区分"本次新增" */
    fun seenKeys(c: Context): Set<String> {
        val stored = sp(c).getStringSet("seen_keys", null) ?: return HashSet()
        return HashSet(stored)
    }

    fun markSeen(c: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = HashSet(seenKeys(c))
        if (current.size > 60000) current.clear()
        current.addAll(keys)
        sp(c).edit().putStringSet("seen_keys", current).apply()
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
