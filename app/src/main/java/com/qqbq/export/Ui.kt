package com.qqbq.export

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** 以 Monet 柔和色板为准的形状工厂。 */
object Ui {

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun round(radiusDp: Int, fill: Int, context: Context, strokeDp: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(context, radiusDp).toFloat()
        drawable.setColor(fill)
        if (strokeDp > 0) drawable.setStroke(dp(context, strokeDp), strokeColor)
        return drawable
    }

    /** 普通卡片：很淡的主色面 + 一点点描边。 */
    fun card(context: Context, radiusDp: Int = 24): GradientDrawable {
        return round(radiusDp, Monet.surfaceContainer, context, 1, Monet.outlineVariant)
    }

    /** 状态卡：稍浓一点的主色面（仍是柔和调）。 */
    fun heroCard(context: Context): GradientDrawable {
        return round(24, Monet.softPrimary, context, 1, withAlpha(Monet.primary, 40))
    }

    fun filled(context: Context): GradientDrawable = round(22, Monet.primary, context)

    fun tonal(context: Context): GradientDrawable = round(22, Monet.softSecondary, context)

    fun row(context: Context, isSelected: Boolean): GradientDrawable {
        return if (isSelected) {
            round(20, Monet.softSecondary, context, 1, withAlpha(Monet.primary, 60))
        } else {
            round(20, Color.TRANSPARENT, context)
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
