package com.qqbq.export

import android.content.Context
import android.graphics.Color
import android.os.Build

/**
 * Material You 柔和色板。
 *
 * 关键点：**不直接用系统高饱和色**，而是像 M3 那样把主色按很低的比例混进中性面色，
 * 得到"带一点壁纸色调、但整体很淡"的效果；卡片和按钮也只在主色上做轻度着色。
 * Android 12+ 取系统 Monet 动态色，低版本回退到一组柔和的紫。
 */
object Monet {

    // —— 中性面（已混入主色） ——
    var surface = 0xFFFCF8FF.toInt(); private set
    var surfaceContainer = 0xFFF6F0FB.toInt(); private set
    var onSurface = 0xFF1C1B20.toInt(); private set
    var onSurfaceVariant = 0xFF5B5766.toInt(); private set
    var outlineVariant = 0xFFE2DCEA.toInt(); private set

    // —— 主色组 ——
    var primary = 0xFF7A6BC4.toInt(); private set
    var onPrimary = 0xFFFFFFFF.toInt(); private set
    var softPrimary = 0xFFEDE7FB.toInt(); private set       // 头部/状态卡：主色 10%
    var softSecondary = 0xFFF1ECFA.toInt(); private set     // 次按钮/选中行：主色 8%
    var onSoftPrimary = 0xFF2B2350.toInt(); private set

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val accent = context.getColor(android.R.color.system_accent1_500)
            val accent2 = context.getColor(android.R.color.system_accent2_500)
            val neutralLight = context.getColor(android.R.color.system_neutral1_50)
            val neutralMid = context.getColor(android.R.color.system_neutral1_100)

            // 面：中性色里混 3%~10% 主色
            surface = blend(neutralLight, accent, 0.03f)
            surfaceContainer = blend(neutralMid, accent, 0.07f)
            softPrimary = blend(neutralLight, accent, 0.12f)
            softSecondary = blend(neutralMid, accent2, 0.10f)

            // 主按钮用比 accent 更深一点、更不刺眼的色调
            primary = blend(accent, 0xFF000000.toInt(), 0.12f)
            onPrimary = 0xFFFFFFFF.toInt()
            onSoftPrimary = blend(context.getColor(android.R.color.system_neutral1_900), accent, 0.15f)

            onSurface = context.getColor(android.R.color.system_neutral1_900)
            onSurfaceVariant = blend(context.getColor(android.R.color.system_neutral2_700), accent, 0.05f)
            outlineVariant = context.getColor(android.R.color.system_neutral2_200)
        } catch (t: Throwable) {
            // 保持默认柔和紫
        }
    }

    private fun blend(base: Int, overlay: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio).toInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio).toInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio).toInt()
        return Color.rgb(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
    }
}
