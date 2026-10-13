// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.morlune.engine.Location
import org.morlune.engine.RefreshInterval
import org.morlune.engine.WeekStart
import java.time.DateTimeException
import java.time.ZoneId

/**
 * Two kinds of settings:
 *  - global ("global" prefs file): weather provider, location, refresh interval. One fetch serves every widget.
 *  - per widget ("widget_<id>" prefs file): how that widget looks and what its taps open.
 * The defaults below must match the android:defaultValue entries in res/xml/prefs_*.xml.
 */
object Prefs {
    const val GLOBAL = "global"
    fun widgetName(id: Int) = "widget_$id"
    fun global(ctx: Context): SharedPreferences = ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE)
    fun widget(ctx: Context, id: Int): SharedPreferences = ctx.getSharedPreferences(widgetName(id), Context.MODE_PRIVATE)
}

class GlobalSettings(private val p: SharedPreferences) {
    val providerId: String get() = p.getString("provider_id", "open-meteo").orEmpty().ifBlank { "open-meteo" }
    val fallbackToOpenMeteo: Boolean get() = p.getBoolean("fallback_open_meteo", true)
    val refresh: RefreshInterval get() = RefreshInterval.fromMinutes(p.getString("refresh_minutes", "120")?.toIntOrNull())

    fun zone(): ZoneId = try {
        p.getString("loc_zone", "").orEmpty().trim().takeIf { it.isNotEmpty() }?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
    } catch (e: DateTimeException) {
        ZoneId.systemDefault()
    }

    /** null until the user has entered valid coordinates. */
    fun location(): Location? {
        val lat = p.getString("loc_lat", "").orEmpty().trim().replace(',', '.').toDoubleOrNull() ?: return null
        val lon = p.getString("loc_lon", "").orEmpty().trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Location(
            lat = lat, lon = lon,
            name = p.getString("loc_name", "").orEmpty().trim().ifEmpty {
                if (p.getString("loc_suburb_coordinates", "") == "$lat,$lon")
                    p.getString("loc_suburb", "").orEmpty().trim()
                else ""
            }.ifEmpty { null },
            zoneId = zone().id,
            extra = parseExtra(p.getString("extra_values", "").orEmpty()),
        )
    }

    companion object {
        fun of(ctx: Context) = GlobalSettings(Prefs.global(ctx))

        /** "key=value" per line; blank lines and lines without '=' are ignored. */
        fun parseExtra(text: String): Map<String, String> =
            text.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.filter { it.first.isNotEmpty() }.toMap()
    }
}

class WidgetSettings(private val p: SharedPreferences) {
    val clockStyle: String get() = p.getString("clock_style", "system").orEmpty()
    val clockSizeSp: Int get() = p.getInt("clock_size", 64)
    val datePattern: String get() = p.getString("date_pattern", "EEEE d MMMM").orEmpty().ifBlank { "EEEE d MMMM" }
    val showLocation: Boolean get() = p.getBoolean("show_location", true)
    val clockApp: String get() = p.getString("clock_app", "").orEmpty()

    val imageEnabled: Boolean get() = p.getBoolean("image_enabled", true)
    val ownPictures: Boolean get() = p.getBoolean("own_pictures", false)
    val imageOpacityPct: Int get() = p.getInt("image_opacity", 70)
    val iconPack: String get() = p.getString("icon_pack", "simple").orEmpty().ifBlank { "simple" }
    val iconColor: Int get() = p.getInt("icon_color", Color.WHITE)
    val forecastDays: Int get() = p.getInt("forecast_days", 5)
    val weatherApp: String get() = p.getString("weather_app", "").orEmpty()
    /** App for the forecast icons; blank means the same as [weatherApp]. */
    val forecastApp: String get() = p.getString("forecast_app", "").orEmpty()
    val weatherUrl: String get() = p.getString("weather_url", "").orEmpty().trim()

    val calendarMode: String get() = p.getString("calendar_mode", "agenda").orEmpty()
    val calendarRows: Int get() = p.getInt("calendar_rows", 6)
    val weekStart: WeekStart get() = runCatching { WeekStart.valueOf(p.getString("week_start", "MONDAY").orEmpty()) }.getOrDefault(WeekStart.MONDAY)
    val weekChips: Int get() = p.getInt("week_chips", 3)
    val weekDays: Int get() = p.getInt("week_days", 7).coerceIn(3, 7)
    val calendarApp: String get() = p.getString("calendar_app", "").orEmpty()

    val textColor: Int get() = p.getInt("text_color", Color.WHITE)
    val bgColor: Int get() = p.getInt("bg_color", 0xFF1B2733.toInt())
    val bgOpacityPct: Int get() = p.getInt("bg_opacity", 60)

    companion object {
        fun of(ctx: Context, widgetId: Int) = WidgetSettings(Prefs.widget(ctx, widgetId))
    }
}
