// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/**
 * Placeholders usable in provider URLs/headers:
 * `{lat}` `{lon}` `{lat:N}` `{lon:N}` (N decimals), `{geohash:N}`, `{name}`, `{extra.key}`, `{today}`.
 * Text values are URL-encoded.
 */
object Template {
    private val placeholder = Regex("\\{([^{}]+)}")

    fun render(template: String, loc: Location, today: LocalDate): String =
        placeholder.replace(template) { m -> resolve(m.groupValues[1].trim(), loc, today) }

    private fun resolve(token: String, loc: Location, today: LocalDate): String {
        val key = token.substringBefore(':')
        val arg = if (token.contains(':')) token.substringAfter(':').trim() else null
        return when {
            key == "lat" -> number(loc.lat, arg)
            key == "lon" -> number(loc.lon, arg)
            key == "geohash" -> {
                val p = arg?.toIntOrNull() ?: throw IllegalArgumentException("{geohash} needs a precision, e.g. {geohash:6}")
                Geohash.encode(loc.lat, loc.lon, p)
            }
            key == "name" -> enc(loc.name ?: throw IllegalArgumentException("{name} used but the location has no name"))
            key == "today" -> today.toString()
            key.startsWith("extra.") -> {
                val k = key.removePrefix("extra.")
                enc(loc.extra[k] ?: throw IllegalArgumentException("{$token} used but the location has no extra value '$k'"))
            }
            else -> throw IllegalArgumentException("unknown placeholder {$token}")
        }
    }

    private fun number(v: Double, decimals: String?): String {
        val n = decimals?.toIntOrNull()
        return if (n == null) BigDecimal.valueOf(v).toPlainString() else String.format(Locale.ROOT, "%.${n}f", v)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
