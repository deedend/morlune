// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** Runs a [ProviderDefinition]: fetches the requests, extracts the fields, normalises everything into [WeatherData]. */
class DeclarativeProvider(
    val definition: ProviderDefinition,
    private val fetcher: HttpFetcher,
    private val htmlParser: HtmlParser? = null,
    private val now: () -> Instant = Instant::now,
) : WeatherProvider {

    override val id: String get() = definition.id
    override val name: String get() = definition.name

    private sealed class Doc {
        class JsonDoc(val root: Any?) : Doc()
        class HtmlDoc(val root: HtmlNode) : Doc()
    }

    private class Ctx(val docs: Map<String, Doc>, val zone: ZoneId, val today: LocalDate, val warnings: MutableSet<String>)

    override fun fetch(location: Location): WeatherData {
        val instant = now()
        val zone = try { ZoneId.of(location.zoneId) } catch (e: Exception) { throw ProviderException("unknown time zone '${location.zoneId}'") }
        val today = instant.atZone(zone).toLocalDate()
        val warnings = LinkedHashSet<String>()

        val docs = HashMap<String, Doc>()
        for (req in definition.requests.values) {
            try { docs[req.id] = load(req, location, today) } catch (e: ProviderException) { warnings += "request '${req.id}': ${e.message}" }
        }
        val ctx = Ctx(docs, zone, today, warnings)
        val current = buildCurrent(ctx)
        val daily = buildDaily(ctx)
        if (current == null && daily.isEmpty()) {
            throw ProviderException("${definition.id}: no usable data. ${warnings.joinToString("; ")}".trim())
        }
        val webUrl = definition.webUrl?.let {
            try { Template.render(it, location, today) } catch (e: IllegalArgumentException) { warnings += "webUrl: ${e.message}"; null }
        }
        return WeatherData(definition.id, instant.toEpochMilli(), current, daily, webUrl, definition.attribution, warnings.toList())
    }

    // ---- fetching -------------------------------------------------------------------------------------------

    private fun load(req: RequestSpec, loc: Location, today: LocalDate): Doc {
        val url: String
        val headers: Map<String, String>
        try {
            url = Template.render(req.url, loc, today)
            headers = req.headers.mapValues { Template.render(it.value, loc, today) }
        } catch (e: IllegalArgumentException) {
            throw ProviderException(e.message ?: "bad template", e)
        }
        if (!url.startsWith("https://")) throw ProviderException("refusing non-https URL")
        val res = try { fetcher.get(url, headers) } catch (e: IOException) { throw ProviderException("network error: ${e.message}", e) }
        if (res.status !in 200..299) throw ProviderException("HTTP ${res.status}")
        return when (req.type) {
            ContentType.JSON -> try { Doc.JsonDoc(Json.parse(res.body)) } catch (e: JsonParseException) { throw ProviderException(e.message ?: "invalid JSON", e) }
            ContentType.HTML -> {
                val parser = htmlParser ?: throw ProviderException("this provider scrapes HTML but no HTML parser was supplied")
                Doc.HtmlDoc(parser.parse(res.body))
            }
        }
    }

    // ---- current --------------------------------------------------------------------------------------------

    private fun buildCurrent(ctx: Ctx): CurrentWeather? {
        if (definition.current.isEmpty()) return null
        fun f(name: String, kind: FieldKind): Any? {
            val spec = definition.current[name] ?: return null
            val doc = ctx.docs[spec.request] ?: return null
            val base: Any? = when (doc) { is Doc.JsonDoc -> doc.root; is Doc.HtmlDoc -> doc.root }
            return safe(ctx, "current.$name") { convert(ctx, "current.$name", kind, spec, raw(doc, base, spec), 0) }
        }
        val temp = f("temperature", FieldKind.TEMPERATURE) as Double?
        val feels = f("feelsLike", FieldKind.TEMPERATURE) as Double?
        val rain = f("rain", FieldKind.RAIN) as Double?
        val cond = f("condition", FieldKind.CONDITION) as Condition?
        val isDay = f("isDay", FieldKind.BOOLEAN) as Boolean?
        if (temp == null && feels == null && rain == null && cond == null) return null
        return CurrentWeather(temp, feels, rain, cond ?: Condition.UNKNOWN, isDay)
    }

    // ---- daily ----------------------------------------------------------------------------------------------

    private fun buildDaily(ctx: Ctx): List<DailyForecast> {
        val spec = definition.daily ?: return emptyList()
        val doc = ctx.docs[spec.request] ?: return emptyList()

        val count: Int
        val rawAt: (FieldSpec, Int) -> Any?
        when (spec.layout) {
            Layout.ROWS -> {
                val rows: List<Any?> = when (doc) {
                    is Doc.JsonDoc -> {
                        val sel = spec.itemsPath!!.select(doc.root)
                        if (sel.size == 1 && sel[0] is List<*>) sel[0] as List<*> else sel
                    }
                    is Doc.HtmlDoc -> safe(ctx, "daily.items") { doc.root.select(spec.itemsSelector!!) } ?: emptyList()
                }
                count = rows.size
                rawAt = { f, i -> raw(doc, rows[i], f) }
            }
            Layout.COLUMNS -> {
                val json = doc as Doc.JsonDoc
                val columns = spec.fields.mapValues { (_, f) -> f.path?.first(json.root) as? List<*> }
                count = columns.values.filterNotNull().maxOfOrNull { it.size } ?: 0
                rawAt = { f, i -> columns.entries.first { spec.fields[it.key] === f }.value?.getOrNull(i) }
            }
        }

        val out = ArrayList<DailyForecast>()
        val seen = HashSet<LocalDate>()
        for (i in 0 until minOf(count, spec.limit)) {
            fun f(name: String, kind: FieldKind): Any? {
                val fs = spec.fields[name] ?: return null
                return safe(ctx, "daily[$i].$name") { convert(ctx, "daily[$i].$name", kind, fs, rawAt(fs, i), i) }
            }
            val date = f("date", FieldKind.DATE) as LocalDate? ?: continue
            if (date < ctx.today || !seen.add(date)) continue
            out += DailyForecast(
                date, f("min", FieldKind.TEMPERATURE) as Double?, f("max", FieldKind.TEMPERATURE) as Double?,
                (f("condition", FieldKind.CONDITION) as Condition?) ?: Condition.UNKNOWN,
                f("rain", FieldKind.RAIN) as Double?, f("rainChance", FieldKind.PERCENT) as Int?,
            )
        }
        return out.sortedBy { it.date }
    }

    // ---- extraction & conversion ---------------------------------------------------------------------------

    private fun raw(doc: Doc, base: Any?, spec: FieldSpec): Any? = when (doc) {
        is Doc.JsonDoc -> spec.path?.first(base)
        is Doc.HtmlDoc -> {
            val node = base as HtmlNode
            val sel = spec.selector
            val target = if (sel == null) node else node.select(sel).firstOrNull()
            val attr = spec.attr
            target?.let { if (attr == null || attr == "text") it.text() else it.attr(attr) }?.ifEmpty { null }
        }
    }

    private inline fun <T> safe(ctx: Ctx, label: String, block: () -> T): T? =
        try { block() } catch (e: ProviderException) { ctx.warnings += "$label: ${e.message}"; null }
        catch (e: RuntimeException) { ctx.warnings += "$label: ${e.message ?: e.javaClass.simpleName}"; null }

    private fun convert(ctx: Ctx, label: String, kind: FieldKind, spec: FieldSpec, raw: Any?, index: Int): Any? = when (kind) {
        FieldKind.DATE -> toDate(spec, raw, ctx, index)
        FieldKind.CONDITION -> toCondition(spec, raw, ctx, label)
        FieldKind.BOOLEAN -> toBoolean(spec, raw)
        FieldKind.TEMPERATURE -> toNumber(spec, raw)?.let { v ->
            when (spec.unit) { "f" -> (v - 32.0) * 5.0 / 9.0; "k" -> v - 273.15; else -> v }
        }
        FieldKind.RAIN -> toNumber(spec, raw)?.let { v ->
            when (spec.unit) { "cm" -> v * 10.0; "in" -> v * 25.4; else -> v }
        }
        FieldKind.PERCENT -> toNumber(spec, raw)?.let { Math.round(it).toInt().coerceIn(0, 100) }
    }

    private val numberRx = Regex("-?\\d+(?:[.,]\\d+)?")

    private fun applyRegex(spec: FieldSpec, raw: Any?): Any? {
        val rx = spec.regex ?: return raw
        if (raw == null) return null
        val m = rx.find(stringify(raw)) ?: return null
        return if (m.groupValues.size > 1) m.groupValues[1] else m.value
    }

    private fun toNumber(spec: FieldSpec, raw0: Any?): Double? {
        val raw = applyRegex(spec, raw0)
        val d: Double = when (raw) {
            null -> return null
            is Number -> raw.toDouble()
            is Boolean -> if (raw) 1.0 else 0.0
            else -> numberRx.find(raw.toString())?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        }
        return d * spec.scale + spec.offset
    }

    private fun toBoolean(spec: FieldSpec, raw0: Any?): Boolean? {
        val raw = applyRegex(spec, raw0)
        val b: Boolean = when (raw) {
            null -> return null
            is Boolean -> raw
            is Number -> raw.toDouble() != 0.0
            else -> when (raw.toString().trim().lowercase()) {
                "true", "yes", "day", "1" -> true
                "false", "no", "night", "0" -> false
                else -> return null
            }
        }
        return if (spec.negate) !b else b
    }

    private fun toCondition(spec: FieldSpec, raw0: Any?, ctx: Ctx, label: String): Condition? {
        val raw = applyRegex(spec, raw0) ?: return null
        val text = stringify(raw)
        val map = spec.map?.let { definition.conditionMaps[it] }
        if (map == null) {
            return Condition.values().firstOrNull { it.name == text.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_") }
                ?: Condition.UNKNOWN.also { ctx.warnings += "$label: unmapped condition value '$text'" }
        }
        return map.resolve(text) ?: Condition.UNKNOWN.also { ctx.warnings += "$label: unmapped condition value '$text' (map '${spec.map}')" }
    }

    private fun toDate(spec: FieldSpec, raw0: Any?, ctx: Ctx, index: Int): LocalDate? {
        val fmt = spec.dateFormat
        if (fmt == "sequential") return ctx.today.plusDays(index.toLong())
        val raw = applyRegex(spec, raw0) ?: return null
        val text = stringify(raw).trim()
        return when (fmt) {
            null, "iso-date" -> LocalDate.parse(text.take(10))
            "instant-local" -> OffsetDateTime.parse(text).atZoneSameInstant(ctx.zone).toLocalDate()
            "epoch-s" -> Instant.ofEpochSecond(text.toDouble().toLong()).atZone(ctx.zone).toLocalDate()
            "epoch-ms" -> Instant.ofEpochMilli(text.toDouble().toLong()).atZone(ctx.zone).toLocalDate()
            else -> {
                val b = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(fmt)
                if (fmt.none { it == 'y' || it == 'u' }) b.parseDefaulting(ChronoField.YEAR, ctx.today.year.toLong())
                LocalDate.parse(text, b.toFormatter(Locale.ENGLISH))
            }
        }
    }

    private fun stringify(v: Any): String =
        if (v is Double && v % 1.0 == 0.0 && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

/** Keeps providers by id. Declarative ones come from JSON text; hand-written Kotlin providers can be added directly. */
class ProviderRegistry(private val fetcher: HttpFetcher, private val htmlParser: HtmlParser? = JsoupHtmlParser()) {
    private val providers = LinkedHashMap<String, WeatherProvider>()

    fun add(provider: WeatherProvider) { providers[provider.id] = provider }

    fun addFromJson(text: String): WeatherProvider =
        DeclarativeProvider(ProviderLoader.parse(text), fetcher, htmlParser).also { add(it) }

    fun get(id: String): WeatherProvider? = providers[id]

    /** Tries [primaryId]; if that fails, [fallbackId] (when set). The failure reason is kept as a warning on the result. */
    fun fetchWithFallback(primaryId: String, fallbackId: String?, location: Location): WeatherData {
        val primary = providers[primaryId]
        val reason: String
        if (primary == null) {
            reason = "provider '$primaryId' is not installed"
        } else {
            try { return primary.fetch(location) } catch (e: ProviderException) { reason = e.message ?: "failed" }
        }
        val fallback = fallbackId?.takeIf { it != primaryId }?.let { providers[it] } ?: throw ProviderException(reason)
        val data = fallback.fetch(location)
        return data.copy(warnings = listOf("'$primaryId' failed ($reason); used '${fallback.id}'") + data.warnings)
    }
    fun all(): List<WeatherProvider> = providers.values.toList()
}
