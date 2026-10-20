package com.qqbq.export

import android.content.Context
import android.graphics.Color
import android.os.Build

/**
 * 柔和色板（Material You 取色 + 低饱和处理）。
 * 原则：面 = 中性色里混 2%~10% 主色；填充只用淡色，文字用深色 → 清透不刺眼。
 */
object Monet {

    var surface = 0xFFFCFAFF.toInt(); private set
    var surfaceContainer = 0xFFF7F3FC.toInt(); private set
    var onSurface = 0xFF1B1B1F.toInt(); private set
    var onSurfaceVariant = 0xFF6A6674.toInt(); private set
    var hairline = 0xFFEAE5F2.toInt(); private set

    /** 用于文字 / 描边的深主色 */
    var primary = 0xFF6F5FBF.toInt(); private set
    /** 用于按钮填充的淡主色 */
    var primaryFill = 0xFFE7E1FA.toInt(); private set
    /** 淡色填充上的深色文字 */
    var onPrimaryFill = 0xFF3B2F7A.toInt(); private set
    /** 选中态浅底 */
    var softPrimary = 0xFFF0EBFB.toInt(); private set
    var softSecondary = 0xFFF4F0FA.toInt(); private set

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val accent = context.getColor(android.R.color.system_accent1_500)
            val accent2 = context.getColor(android.R.color.system_accent2_500)
            val neutralLight = context.getColor(android.R.color.system_neutral1_50)
            val neutralMid = context.getColor(android.R.color.system_neutral1_100)
            val neutralDeep = context.getColor(android.R.color.system_neutral1_900)

            surface = blend(neutralLight, accent, 0.02f)
            surfaceContainer = blend(neutralMid, accent, 0.06f)
            softPrimary = blend(neutralMid, accent, 0.12f)
            softSecondary = blend(neutralMid, accent2, 0.08f)
            primaryFill = blend(neutralLight, accent, 0.30f)
            onPrimaryFill = blend(accent, neutralDeep, 0.45f)
            primary = blend(accent, neutralDeep, 0.28f)
            onSurface = neutralDeep
            onSurfaceVariant = blend(context.getColor(android.R.color.system_neutral2_700), accent, 0.04f)
            hairline = blend(neutralMid, accent, 0.10f)
        } catch (t: Throwable) {
        }
    }

    private fun blend(base: Int, overlay: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio).toInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio).toInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}
