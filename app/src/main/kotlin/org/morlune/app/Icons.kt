// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.content.Context
import org.morlune.engine.Backgrounds
import org.morlune.engine.Condition

/** Resolves drawables by name: wi_<pack>_<icon> for icons, bg_<key> for the weather pictures. */
object Icons {
    fun iconName(c: Condition, isDay: Boolean): String = when (c) {
        Condition.CLEAR -> if (isDay) "clear_day" else "clear_night"
        Condition.PARTLY_CLOUDY -> if (isDay) "partly_day" else "partly_night"
        Condition.CLOUDY, Condition.UNKNOWN -> "cloudy"
        Condition.FOG, Condition.HAZE, Condition.DUST -> "fog"
        Condition.DRIZZLE, Condition.RAIN, Condition.HEAVY_RAIN -> "rain"
        Condition.SHOWERS -> if (isDay) "showers" else "rain"
        Condition.THUNDERSTORM, Condition.HAIL -> "storm"
        Condition.SNOW, Condition.FROST -> "snow"
        Condition.WIND -> "wind"
    }

    fun iconRes(ctx: Context, pack: String, c: Condition, isDay: Boolean): Int {
        val name = iconName(c, isDay)
        return drawable(ctx, "wi_${pack}_$name").takeIf { it != 0 } ?: drawable(ctx, "wi_simple_$name")
    }

    fun backgroundRes(ctx: Context, c: Condition, isDay: Boolean?): Int =
        drawable(ctx, "bg_" + Backgrounds.key(c, isDay))

    private fun drawable(ctx: Context, name: String): Int = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
}
