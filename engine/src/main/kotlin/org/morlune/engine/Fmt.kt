// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.util.Locale

/** Number formatting for the widget text. Kept here (not in the Android module) so it is unit tested. */
object Fmt {
    fun temp(c: Double?): String = if (c == null || !c.isFinite()) "–" else "${Math.round(c)}°"

    fun range(min: Double?, max: Double?): String =
        if (min == null && max == null) "–" else "${temp(min)}/${temp(max)}"

    /** "0 mm", "1.6 mm", "12 mm"; null if the provider has no rain value. */
    fun rain(mm: Double?): String? = when {
        mm == null || !mm.isFinite() -> null
        mm <= 0.0 -> "0 mm"
        mm < 10.0 -> String.format(Locale.ROOT, "%.1f mm", mm)
        else -> "${Math.round(mm)} mm"
    }

    /** Day/night for the background and icons: the provider's flag if it has one, otherwise a rough guess from the hour. */
    fun isDay(current: CurrentWeather?, localHour: Int): Boolean = current?.isDay ?: (localHour in 6..17)
}

enum class RefreshInterval(val minutes: Int) {
    H1(60), H2(120), H6(360), H12(720), H24(1440);

    companion object {
        val DEFAULT = H2
        fun fromMinutes(m: Int?): RefreshInterval = values().firstOrNull { it.minutes == m } ?: DEFAULT
    }
}
