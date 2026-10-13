// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/**
 * Placeholders usable in provider URLs/headers:
 * `{lat}` `{lon}` `{lat:N}` `{lon:N}` (N decimals), `{geohash:N}`, `{name}`, `{tz}`, `{extra.key}`, `{today}`,
 * and `{token.NAME}` for secrets supplied by a [TokenSource]. Text values are URL-encoded; tokens are inserted as they are.
 */
object Template {
    /** Plain scanning, no regex: Android's regex engine rejects some patterns the JVM accepts. */
    fun render(template: String, loc: Location, today: LocalDate, tokens: TokenSource? = null): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            if (open < 0) { out.append(template, i, template.length); break }
            val close = template.indexOf('}', open + 1)
            require(close > open) { "missing '}' in template: $template" }
            out.append(template, i, open)
            out.append(resolve(template.substring(open + 1, close).trim(), loc, today, tokens))
            i = close + 1
        }
        return out.toString()
    }

    private fun resolve(token: String, loc: Location, today: LocalDate, tokens: TokenSource?): String {
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
            key == "tz" -> enc(loc.zoneId)
            key.startsWith("token.") -> {
                val src = tokens ?: throw IllegalArgumentException("{$token} used but this place cannot supply tokens")
                src.token(key.removePrefix("token."))
            }
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
