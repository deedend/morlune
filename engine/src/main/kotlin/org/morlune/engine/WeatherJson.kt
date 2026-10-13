// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.time.LocalDate

/** Saves/loads [WeatherData] as JSON so the widget can show the last good data between fetches. */
object WeatherJson {
    fun toJson(w: WeatherData): String {
        val sb = StringBuilder("{\"v\":1")
        sb.append(",\"providerId\":").append(q(w.providerId))
        sb.append(",\"fetchedAt\":").append(w.fetchedAtEpochMs)
        sb.append(",\"webUrl\":").append(qn(w.webUrl))
        sb.append(",\"attribution\":").append(qn(w.attribution))
        sb.append(",\"warnings\":[").append(w.warnings.joinToString(",") { q(it) }).append("]")
        sb.append(",\"current\":")
        val c = w.current
        if (c == null) sb.append("null") else {
            sb.append("{\"temp\":").append(num(c.temperatureC))
            sb.append(",\"feels\":").append(num(c.feelsLikeC))
            sb.append(",\"rain\":").append(num(c.rainMm))
            sb.append(",\"cond\":").append(q(c.condition.name))
            sb.append(",\"isDay\":").append(c.isDay?.toString() ?: "null").append("}")
        }
        sb.append(",\"daily\":[")
        sb.append(w.daily.joinToString(",") { d ->
            "{\"date\":" + q(d.date.toString()) + ",\"min\":" + num(d.minC) + ",\"max\":" + num(d.maxC) +
                ",\"cond\":" + q(d.condition.name) + ",\"rain\":" + num(d.rainMm) + ",\"chance\":" + (d.rainChancePct?.toString() ?: "null") + "}"
        })
        return sb.append("]}").toString()
    }

    fun fromJson(text: String): WeatherData {
        val root = Json.parse(text) as? Map<*, *> ?: throw ProviderException("cache is not an object")
        fun d(m: Map<*, *>, k: String): Double? = (m[k] as? Double)?.takeIf { it.isFinite() }
        fun cond(m: Map<*, *>): Condition = Condition.values().firstOrNull { it.name == m["cond"] } ?: Condition.UNKNOWN
        val current = (root["current"] as? Map<*, *>)?.let {
            CurrentWeather(d(it, "temp"), d(it, "feels"), d(it, "rain"), cond(it), it["isDay"] as? Boolean)
        }
        val daily = (root["daily"] as? List<*>).orEmpty().mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val date = (m["date"] as? String)?.let { LocalDate.parse(it) } ?: return@mapNotNull null
            DailyForecast(date, d(m, "min"), d(m, "max"), cond(m), d(m, "rain"), (m["chance"] as? Double)?.toInt())
        }
        return WeatherData(
            providerId = root["providerId"] as? String ?: "", fetchedAtEpochMs = (root["fetchedAt"] as? Double)?.toLong() ?: 0L,
            current = current, daily = daily, webUrl = root["webUrl"] as? String, attribution = root["attribution"] as? String,
            warnings = (root["warnings"] as? List<*>).orEmpty().filterIsInstance<String>(),
        )
    }

    private fun num(v: Double?) = if (v == null || !v.isFinite()) "null" else v.toString()
    private fun qn(s: String?) = if (s == null) "null" else q(s)
    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }
}
