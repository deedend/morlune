// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

/**
 * Works out which weather a picture is for from its folder and file name, so users can drop their own photos in a
 * folder ("rain/heavy_night_3.jpg", "Clear sky - morning.jpg") without any configuration.
 * Groups are the same ones [Backgrounds.key] produces.
 */
object PictureNames {
    data class Tag(val group: String, val period: String)   // period: "day", "night" or "any"

    /** [path] is the folder names plus the file name, for example "Rain/storm_night_2.jpg". Null if no weather word is found. */
    fun classify(path: String): Tag? {
        val words = words(path)
        fun has(vararg starts: String) = words.any { w -> starts.any { w.startsWith(it) } }
        val group = when {
            has("snow", "sleet", "blizzard", "flurr") -> "snow"
            has("heavy", "downpour", "torrential") && has("rain", "shower", "downpour", "torrential") -> "heavy_rain"
            has("thunder", "storm", "lightning") -> "storm"
            has("rain", "shower", "drizzle") -> "rain"
            has("fog", "mist", "haze", "hazy", "smog") -> "fog"
            has("partly", "scattered", "broken") -> "partly_cloudy"
            has("cloud", "overcast") -> "cloudy"
            has("clear", "sunny", "sunshine", "bluesky") || "sun" in words -> "clear"
            else -> return null
        }
        val period = when {
            has("night", "evening", "dusk", "moon", "star") -> "night"
            has("day", "morning", "noon", "midday") -> "day"
            else -> "any"
        }
        return Tag(group, period)
    }

    /** Lower-case letter runs: "Heavy_Rain-2.JPG" gives [heavy, rain, jpg]. No regex on purpose (Android's engine is pickier). */
    private fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in s.lowercase()) {
            if (ch in 'a'..'z') cur.append(ch) else if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Which stored picture to show: exact day/night match first, then "any", then the other period. */
    fun <T> choose(candidates: List<Pair<String, T>>, wantPeriod: String, seed: Long): T? {
        if (candidates.isEmpty()) return null
        val exact = candidates.filter { it.first == wantPeriod }
        val any = candidates.filter { it.first == "any" }
        val pool = exact.ifEmpty { any }.ifEmpty { candidates }
        return pool[Math.floorMod(seed, pool.size.toLong()).toInt()].second
    }
}
