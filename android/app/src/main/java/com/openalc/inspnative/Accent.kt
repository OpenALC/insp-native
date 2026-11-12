package com.openalc.inspnative

import android.content.Context
import android.graphics.Color

object Accent {
    fun color(context: Context): Int = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .getInt("accent", 0xff54d81b.toInt())

    fun save(context: Context, color: Int) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("accent", color).apply()
    }

    fun mix(color: Int, background: Int, ratio: Float): Int = Color.rgb(
        (Color.red(color) * ratio + Color.red(background) * (1 - ratio)).toInt(),
        (Color.green(color) * ratio + Color.green(background) * (1 - ratio)).toInt(),
        (Color.blue(color) * ratio + Color.blue(background) * (1 - ratio)).toInt(),
    )
}
