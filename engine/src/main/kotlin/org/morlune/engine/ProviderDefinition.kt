// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.time.format.DateTimeFormatter

enum class ContentType { JSON, HTML }
enum class Layout { ROWS, COLUMNS }
enum class FieldKind { TEMPERATURE, RAIN, PERCENT, CONDITION, BOOLEAN, DATE }

class RequestSpec(val id: String, val url: String, val type: ContentType, val headers: Map<String, String>)

class FieldSpec(
    val request: String?,
    val path: JsonPath?,
    val selector: String?,
    val attr: String?,
    val regex: Regex?,
    val map: String?,
    val unit: String?,
    val scale: Double,
    val offset: Double,
    val negate: Boolean,
    val dateFormat: String?,
)

class DailySpec(
    val request: String,
    val layout: Layout,
    val itemsPath: JsonPath?,
    val itemsSelector: String?,
    val limit: Int,
    val fields: Map<String, FieldSpec>,
)

/** Matches provider values (codes, descriptors, page text) onto [Condition]. First matching entry wins. */
class ConditionMap(private val entries: List<Pair<Condition, List<(String) -> Boolean>>>) {
    fun resolve(value: String): Condition? {
        val v = value.trim()
        for ((cond, matchers) in entries) if (matchers.any { it(v) }) return cond
        return null
    }

    companion object {
        private val range = Regex("^(-?\\d+)-(-?\\d+)$")

        fun matcher(pattern: String): (String) -> Boolean {
            val p = pattern.trim()
            if (p.startsWith("~")) {
                val sub = p.substring(1).trim()
                return { v -> v.contains(sub, ignoreCase = true) }
            }
            val m = range.matchEntire(p)
            if (m != null) {
                val lo = m.groupValues[1].toDouble()
                val hi = m.groupValues[2].toDouble()
                return { v -> v.toDoubleOrNull()?.let { it in lo..hi } ?: false }
            }
            return { v -> v.equals(p, ignoreCase = true) }
        }
    }
}

class ProviderDefinition(
    val id: String,
    val name: String,
    val description: String?,
    val attribution: String?,
    val webUrl: String?,
    val requests: Map<String, RequestSpec>,
    val current: Map<String, FieldSpec>,
    val daily: DailySpec?,
    val conditionMaps: Map<String, ConditionMap>,
)

/** Parses and validates a provider JSON file. Errors name the exact place, because users will write these by hand. */
object ProviderLoader {
    private val currentKinds = mapOf(
        "temperature" to FieldKind.TEMPERATURE, "feelsLike" to FieldKind.TEMPERATURE,
        "rain" to FieldKind.RAIN, "condition" to FieldKind.CONDITION, "isDay" to FieldKind.BOOLEAN,
    )
    private val dailyKinds = mapOf(
        "date" to FieldKind.DATE, "min" to FieldKind.TEMPERATURE, "max" to FieldKind.TEMPERATURE,
        "condition" to FieldKind.CONDITION, "rain" to FieldKind.RAIN, "rainChance" to FieldKind.PERCENT,
    )
    private val fieldKeys = setOf("request", "path", "selector", "attr", "regex", "map", "unit", "scale", "offset", "negate", "dateFormat")
    private val knownDateFormats = setOf("iso-date", "instant-local", "epoch-s", "epoch-ms", "sequential")
    private val idRegex = Regex("^[a-z0-9][a-z0-9-]*$")

    fun parse(text: String): ProviderDefinition {
        val root = try { Json.parse(text) } catch (e: JsonParseException) { throw ProviderException("provider file is not valid JSON: ${e.message}", e) }
        val top = obj(root, "provider")
        checkKeys(top, setOf("schema", "id", "name", "description", "attribution", "webUrl", "requests", "current", "daily", "conditionMaps"), "provider")

        val schema = (top["schema"] as? Double)?.toInt() ?: fail("provider", "missing 'schema' (use 1)")
        if (schema != 1) fail("provider.schema", "unsupported schema $schema (this app understands 1)")
        val id = str(top, "id", "provider", required = true)!!
        if (!idRegex.matches(id)) fail("provider.id", "must be lowercase letters, digits and dashes, e.g. 'bom-au'")
        val name = str(top, "name", "provider", required = true)!!

        val requests = LinkedHashMap<String, RequestSpec>()
        val reqList = top["requests"] as? List<*> ?: fail("provider.requests", "expected a list of requests")
        if (reqList.isEmpty()) fail("provider.requests", "needs at least one request")
        reqList.forEachIndexed { idx, r ->
            val p = "requests[$idx]"
            val o = obj(r, p)
            checkKeys(o, setOf("id", "url", "type", "headers"), p)
            val rid = str(o, "id", p, required = true)!!
            if (rid in requests) fail(p, "duplicate request id '$rid'")
            val url = str(o, "url", p, required = true)!!
            if (!url.startsWith("https://")) fail("$p.url", "must start with https://")
            val type = when (str(o, "type", p, required = true)!!.lowercase()) {
                "json" -> ContentType.JSON
                "html" -> ContentType.HTML
                else -> fail("$p.type", "must be 'json' or 'html'")
            }
            val headers = (o["headers"]?.let { obj(it, "$p.headers") } ?: emptyMap()).mapValues { (k, v) ->
                v as? String ?: fail("$p.headers.$k", "header values must be strings")
            }
            requests[rid] = RequestSpec(rid, url, type, headers)
        }

        val maps = LinkedHashMap<String, ConditionMap>()
        top["conditionMaps"]?.let { cm ->
            obj(cm, "conditionMaps").forEach { (mapName, v) -> maps[mapName] = parseConditionMap(v, "conditionMaps.$mapName") }
        }

        val current = LinkedHashMap<String, FieldSpec>()
        top["current"]?.let { c ->
            obj(c, "current").forEach { (fname, v) ->
                val kind = currentKinds[fname] ?: fail("current.$fname", "unknown field; allowed: ${currentKinds.keys.joinToString()}")
                current[fname] = parseField(v, "current.$fname", kind, requests, maps, requestRequired = true, selectorOptional = false)
            }
        }

        val daily = top["daily"]?.let { parseDaily(it, requests, maps) }

        top["webUrl"]?.let { if (it !is String) fail("provider.webUrl", "must be a string") }
        return ProviderDefinition(id, name, str(top, "description", "provider"), str(top, "attribution", "provider"),
            top["webUrl"] as String?, requests, current, daily, maps)
    }

    private fun parseDaily(v: Any?, requests: Map<String, RequestSpec>, maps: Map<String, ConditionMap>): DailySpec {
        val o = obj(v, "daily")
        checkKeys(o, setOf("request", "layout", "items", "limit", "fields"), "daily")
        val reqId = str(o, "request", "daily", required = false) ?: requests.keys.singleOrNull()
            ?: fail("daily.request", "required when there are several requests")
        val req = requests[reqId] ?: fail("daily.request", "unknown request '$reqId'")
        val layout = when (str(o, "layout", "daily", required = false)?.lowercase() ?: "rows") {
            "rows" -> Layout.ROWS
            "columns" -> Layout.COLUMNS
            else -> fail("daily.layout", "must be 'rows' or 'columns'")
        }
        if (layout == Layout.COLUMNS && req.type != ContentType.JSON) fail("daily.layout", "'columns' only works with JSON requests")
        val items = str(o, "items", "daily", required = layout == Layout.ROWS)
        val itemsPath = if (items != null && req.type == ContentType.JSON) compilePath(items, "daily.items") else null
        val limit = (o["limit"] as? Double)?.toInt() ?: 7
        if (limit !in 1..16) fail("daily.limit", "must be between 1 and 16")
        val fields = LinkedHashMap<String, FieldSpec>()
        obj(o["fields"], "daily.fields").forEach { (fname, fv) ->
            val kind = dailyKinds[fname] ?: fail("daily.fields.$fname", "unknown field; allowed: ${dailyKinds.keys.joinToString()}")
            fields[fname] = parseField(fv, "daily.fields.$fname", kind, mapOf(reqId to req), maps,
                requestRequired = false, selectorOptional = layout == Layout.ROWS, forcedRequest = reqId)
        }
        if ("date" !in fields) fail("daily.fields", "needs a 'date' field")
        return DailySpec(reqId, layout, itemsPath, if (req.type == ContentType.HTML) items else null, limit, fields)
    }

    private fun parseField(
        v: Any?, path: String, kind: FieldKind,
        requests: Map<String, RequestSpec>, maps: Map<String, ConditionMap>,
        requestRequired: Boolean, selectorOptional: Boolean, forcedRequest: String? = null,
    ): FieldSpec {
        // Shorthand: a plain string is a JSON path (starts with $) or a CSS selector.
        val o: Map<String, Any?> = if (v is String) {
            if (v.trim().startsWith("$")) mapOf("path" to v) else mapOf("selector" to v)
        } else obj(v, path)
        checkKeys(o, fieldKeys, path)

        val reqId = forcedRequest ?: str(o, "request", path, required = false) ?: requests.keys.singleOrNull()
            ?: fail("$path.request", "required when there are several requests")
        val req = requests[reqId] ?: fail("$path.request", "unknown request '$reqId'")

        val dateFormat = str(o, "dateFormat", path, required = false)
        if (dateFormat != null) {
            if (kind != FieldKind.DATE) fail("$path.dateFormat", "only valid on date fields")
            if (dateFormat !in knownDateFormats) {
                try { DateTimeFormatter.ofPattern(dateFormat) } catch (e: IllegalArgumentException) {
                    fail("$path.dateFormat", "not a known format (${knownDateFormats.joinToString()}) nor a valid pattern: ${e.message}")
                }
            }
        }
        val sequential = dateFormat == "sequential"

        val pathStr = str(o, "path", path, required = false)
        val selector = str(o, "selector", path, required = false)
        var jsonPath: JsonPath? = null
        if (!sequential) {
            when (req.type) {
                ContentType.JSON -> {
                    if (selector != null) fail("$path.selector", "request '$reqId' is JSON; use 'path'")
                    jsonPath = compilePath(pathStr ?: fail(path, "needs a 'path'"), "$path.path")
                }
                ContentType.HTML -> {
                    if (pathStr != null) fail("$path.path", "request '$reqId' is HTML; use 'selector'")
                    if (selector == null && !selectorOptional) fail(path, "needs a 'selector'")
                }
            }
        }

        val unit = str(o, "unit", path, required = false)?.lowercase()
        val allowedUnits = when (kind) {
            FieldKind.TEMPERATURE -> setOf("c", "f", "k")
            FieldKind.RAIN -> setOf("mm", "cm", "in")
            else -> emptySet()
        }
        if (unit != null && unit !in allowedUnits) fail("$path.unit", "not valid here; allowed: ${allowedUnits.joinToString().ifEmpty { "none" }}")

        val mapName = str(o, "map", path, required = false)
        if (mapName != null) {
            if (kind != FieldKind.CONDITION) fail("$path.map", "only valid on condition fields")
            if (mapName !in maps) fail("$path.map", "unknown condition map '$mapName' (defined: ${maps.keys.joinToString().ifEmpty { "none" }})")
        }
        val regex = str(o, "regex", path, required = false)?.let {
            try { Regex(it) } catch (e: Exception) { fail("$path.regex", "invalid regular expression: ${e.message}") }
        }
        return FieldSpec(
            request = reqId, path = jsonPath, selector = selector, attr = str(o, "attr", path, required = false),
            regex = regex, map = mapName, unit = unit,
            scale = (o["scale"] as? Double) ?: 1.0, offset = (o["offset"] as? Double) ?: 0.0,
            negate = (o["negate"] as? Boolean) ?: false, dateFormat = dateFormat,
        )
    }

    private fun parseConditionMap(v: Any?, path: String): ConditionMap {
        val o = obj(v, path)
        val entries = o.map { (condName, patterns) ->
            val cond = Condition.values().firstOrNull { it.name == condName }
                ?: fail("$path.$condName", "not a condition; use one of ${Condition.values().joinToString { it.name }}")
            val list = when (patterns) {
                is String -> listOf(patterns)
                is List<*> -> patterns.map { (it as? String) ?: (it as? Double)?.let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() } ?: fail("$path.$condName", "patterns must be strings") }
                else -> fail("$path.$condName", "expected a string or a list of strings")
            }
            cond to list.map { ConditionMap.matcher(it) }
        }
        return ConditionMap(entries)
    }

    private fun compilePath(expr: String, at: String): JsonPath =
        try { JsonPath.compile(expr) } catch (e: IllegalArgumentException) { fail(at, e.message ?: "invalid path") }

    private fun fail(at: String, msg: String): Nothing = throw ProviderException("$at: $msg")

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?, at: String): Map<String, Any?> =
        if (v is Map<*, *>) v as Map<String, Any?> else fail(at, "expected an object")

    private fun str(m: Map<String, Any?>, key: String, at: String, required: Boolean = false): String? {
        val v = m[key]
        if (v == null) { if (required) fail(at, "missing '$key'"); return null }
        return v as? String ?: fail("$at.$key", "must be a string")
    }

    private fun checkKeys(m: Map<String, Any?>, allowed: Set<String>, at: String) {
        val bad = m.keys.firstOrNull { it !in allowed && !it.startsWith("_") && !it.startsWith("$") }
        if (bad != null) fail(at, "unknown key '$bad' (allowed: ${allowed.joinToString()})")
    }
}
