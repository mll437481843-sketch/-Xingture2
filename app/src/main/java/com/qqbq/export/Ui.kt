package com.qqbq.export

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object Ui {

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun round(radiusDp: Int, fill: Int, context: Context, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(context, radiusDp).toFloat()
        d.setColor(fill)
        if (strokeDp > 0) d.setStroke(dp(context, strokeDp), strokeColor)
        return d
    }

    /** 卡片 / 底栏：很淡的面 + 一根发丝描边 */
    fun card(context: Context, radiusDp: Int = 22): GradientDrawable =
        round(radiusDp, Monet.surfaceContainer, context, 1, Monet.hairline)

    /** 主按钮：淡主色填充 + 深色字（清透） */
    fun filled(context: Context): GradientDrawable = round(18, Monet.primaryFill, context)

    fun soft(context: Context): GradientDrawable = round(18, Monet.softPrimary, context)

    /** 胶囊 chip */
    fun chip(context: Context, active: Boolean): GradientDrawable =
        if (active) round(16, Monet.softPrimary, context, 1, Monet.primary)
        else round(16, Color.TRANSPARENT, context, 1, Monet.hairline)

    /** 宫格单元 */
    fun tile(context: Context, active: Boolean): GradientDrawable =
        if (active) round(18, Monet.softPrimary, context, 2, Monet.primary)
        else round(18, Monet.surfaceContainer, context, 1, Monet.hairline)

    /** 列表行 */
    fun row(context: Context, active: Boolean): GradientDrawable =
        if (active) round(16, Monet.softPrimary, context, 2, Monet.primary)
        else round(16, Color.TRANSPARENT, context)

    fun badge(context: Context): GradientDrawable = round(9, Monet.primaryFill, context)

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
