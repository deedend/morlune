// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlin.system.exitProcess

private var failures = 0
private var passes = 0

private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) { passes++; println("  ok   $name") } else { failures++; println("  FAIL $name ${detail()}") }
}

private fun <T> expectThrows(name: String, contains: String, block: () -> T) {
    try { block(); check(name, false) { "(nothing thrown)" } }
    catch (e: Exception) { check(name, e.message?.contains(contains) == true) { "(got: ${e.message})" } }
}

private class FakeFetcher(private val routes: Map<String, HttpResult>) : HttpFetcher {
    val requested = mutableListOf<String>()
    val sentHeaders = mutableListOf<Map<String, String>>()
    override fun get(url: String, headers: Map<String, String>): HttpResult {
        requested += url
        sentHeaders += headers
        return routes.entries.firstOrNull { url.startsWith(it.key) }?.value ?: HttpResult(404, "")
    }
}

private val providersDir = File(System.getProperty("providers.dir") ?: "app/src/main/assets/providers")
private val examplesDir = File(System.getProperty("examples.dir") ?: "docs/examples")
private fun providerJson(path: String) = if (path.startsWith("examples/")) File(examplesDir, path.removePrefix("examples/")).readText() else File(providersDir, path).readText()

// 2026-10-12 12:00 in Melbourne (AEDT, UTC+11)
private val fixedNow = { Instant.parse("2026-10-12T01:00:00Z") }
private val melbourne = Location(-37.8136, 144.9631, name = "Melbourne", zoneId = "Australia/Melbourne")

private const val BOM_OBS = """{"metadata":{"response_timestamp":"2026-10-12T01:00:00Z"},
 "data":{"temp":14.2,"temp_feels_like":12.8,"rain_since_9am":1.6,"humidity":71,"wind":{"speed_kilometre":19}}}"""

// dates are local midnight expressed in UTC: Oct 12 local = Oct 11 13:00Z while daylight saving is on
private const val BOM_DAILY = """{"metadata":{},"data":[
 {"date":"2026-10-11T13:00:00Z","temp_max":19,"temp_min":null,"icon_descriptor":"shower","rain":{"amount":{"min":1,"max":4},"chance":70},"now":{"is_night":false}},
 {"date":"2026-10-12T13:00:00Z","temp_max":22,"temp_min":10,"icon_descriptor":"mostly_sunny","rain":{"amount":{"min":0,"max":null},"chance":10}},
 {"date":"2026-10-13T13:00:00Z","temp_max":27,"temp_min":12,"icon_descriptor":"storm","rain":{"amount":{"min":2,"max":9},"chance":80}},
 {"date":"2026-10-14T13:00:00Z","temp_max":18,"temp_min":9,"icon_descriptor":"something_new","rain":{"chance":30}}]}"""

private const val OPEN_METEO = """{"current":{"temperature_2m":13.5,"apparent_temperature":11.9,"weather_code":61,"is_day":0},
 "daily":{"time":["2026-10-12","2026-10-13","2026-10-14"],
  "weather_code":[61,2,95],"temperature_2m_max":[18.0,21.5,24.0],"temperature_2m_min":[9.0,8.5,11.0],
  "precipitation_sum":[5.2,0.0,12.4],"precipitation_probability_max":[85,10,90]}}"""


private const val HTML_PAGE = """<html><body>
<div id="now"><span class="temp">Now 18.4°C</span><span class="desc">Partly cloudy</span></div>
<ul class="forecast">
 <li class="day"><span class="icon" title="Showers"></span><span class="hi">High 21°</span><span class="lo">Low 9°</span><span class="rain-chance">40%</span></li>
 <li class="day"><span class="icon" title="Thunderstorms likely"></span><span class="hi">High 25°</span><span class="lo">Low -1°</span><span class="rain-chance">80%</span></li>
 <li class="day"><span class="icon" title="Mostly sunny"></span><span class="hi">High 19°</span><span class="lo">Low 7°</span><span class="rain-chance">5%</span></li>
</ul></body></html>"""

fun main() {
    println("JSON / JSONPath")
    run {
        val j = Json.parse("""{"a":[1,2,{"b":"x\u00e9\n"}],"n":null,"t":true,"e":1.5e2}""") as Map<*, *>
        check("parses nested values", (j["a"] as List<*>).size == 3 && j["e"] == 150.0 && j["t"] == true)
        check("keeps escapes", ((j["a"] as List<*>)[2] as Map<*, *>)["b"] == "xé\n")
        expectThrows("rejects trailing garbage", "trailing") { Json.parse("{} x") }
        expectThrows("rejects unterminated", "unterminated") { Json.parse("[1,2") }
        val data = Json.parse("""{"data":[{"v":1},{"v":2},{"v":3}],"o":{"k":{"v":9}}}""")
        check("wildcard", JsonPath.compile("$.data[*].v").select(data) == listOf(1.0, 2.0, 3.0))
        check("negative index", JsonPath.compile("$.data[-1].v").first(data) == 3.0)
        check("quoted key", JsonPath.compile("$['o']['k'].v").first(data) == 9.0)
        check("recursive descent", JsonPath.compile("$..v").select(data).size == 4)
        check("missing path is null", JsonPath.compile("$.nope.x").first(data) == null)
        expectThrows("bad path", "must start with") { JsonPath.compile("data.x") }
    }

    println("Geohash / templates")
    run {
        check("geohash vector 1", Geohash.encode(57.64911, 10.40744, 11) == "u4pruydqqvj")
        check("geohash vector 2", Geohash.encode(42.6, -5.6, 5) == "ezs42")
        val t = Template.render("a/{geohash:6}/{lat:2},{lon:2}/{name}/{extra.slug}/{today}",
            melbourne.copy(name = "Port Fairy", extra = mapOf("slug" to "a b")), LocalDate.of(2026, 10, 12))
        check("template renders", t.endsWith("/-37.81,144.96/Port%20Fairy/a%20b/2026-10-12"), { t })
        check("template: no placeholders and edges", Template.render("plain", melbourne, LocalDate.now()) == "plain" && Template.render("{today}{today}", melbourne, LocalDate.of(2026, 1, 2)) == "2026-01-022026-01-02")
        expectThrows("unclosed brace", "missing '}'") { Template.render("a{lat", melbourne, LocalDate.now()) }
        expectThrows("unknown placeholder", "unknown placeholder") { Template.render("{wat}", melbourne, LocalDate.now()) }
        expectThrows("missing extra", "no extra value") { Template.render("{extra.zz}", melbourne, LocalDate.now()) }
    }

    println("Open-Meteo (columnar JSON)")
    run {
        val fetcher = FakeFetcher(mapOf("https://api.open-meteo.com/" to HttpResult(200, OPEN_METEO)))
        val p = DeclarativeProvider(ProviderLoader.parse(providerJson("open-meteo.json")), fetcher, now = fixedNow)
        val w = p.fetch(melbourne)
        check("url built from lat/lon", fetcher.requested.single().contains("latitude=-37.8136&longitude=144.9631"), { fetcher.requested.toString() })
        check("current temp", w.current?.temperatureC == 13.5)
        check("current condition WMO 61 -> RAIN", w.current?.condition == Condition.RAIN)
        check("isDay 0 -> false", w.current?.isDay == false)
        check("rain today from daily sum", w.current?.rainMm == 5.2)
        check("3 days", w.daily.size == 3)
        check("day 3 thunderstorm", w.daily[2].condition == Condition.THUNDERSTORM && w.daily[2].maxC == 24.0)
        check("day 2 partly cloudy, 10%", w.daily[1].condition == Condition.PARTLY_CLOUDY && w.daily[1].rainChancePct == 10)
        check("no warnings", w.warnings.isEmpty(), { w.warnings.toString() })
        check("background key", Backgrounds.key(w.backgroundCondition, w.current?.isDay) == "rain_night")
    }

    println("BOM (row JSON, two requests, timezone dates)")
    run {
        val routes = mapOf(
            "https://api.weather.bom.gov.au/v1/locations/r1r0fs/observations" to HttpResult(200, BOM_OBS),
            "https://api.weather.bom.gov.au/v1/locations/r1r0fs/forecasts/daily" to HttpResult(200, BOM_DAILY),
        )
        val f = FakeFetcher(routes)
        val geo = Geohash.encode(melbourne.lat, melbourne.lon, 6)
        check("melbourne geohash is r1r0fs", geo == "r1r0fs", { geo })
        val p = DeclarativeProvider(ProviderLoader.parse(providerJson("bom-au.json")), f, now = fixedNow)
        val w = p.fetch(melbourne)
        check("temp from obs", w.current?.temperatureC == 14.2 && w.current?.feelsLikeC == 12.8)
        check("rain since 9am", w.current?.rainMm == 1.6)
        check("condition from daily[0]", w.current?.condition == Condition.SHOWERS)
        check("is_night=false -> isDay", w.current?.isDay == true)
        check("first day is today (UTC->local)", w.daily.first().date == LocalDate.of(2026, 10, 12), { w.daily.first().date.toString() })
        check("null min stays null", w.daily[0].minC == null && w.daily[0].maxC == 19.0)
        check("null rain max stays null", w.daily[1].rainMm == null)
        check("chance parsed", w.daily[0].rainChancePct == 70)
        check("unknown descriptor -> UNKNOWN + warning", w.daily[3].condition == Condition.UNKNOWN && w.warnings.any { "something_new" in it }, { w.warnings.toString() })
        check("webUrl", w.webUrl == "https://weather.bom.gov.au/location/r1r0fs", { w.webUrl ?: "null" })

        // observations endpoint down: forecast still works, current temp missing, warning explains why
        val partial = FakeFetcher(mapOf(
            "https://api.weather.bom.gov.au/v1/locations/r1r0fs/observations" to HttpResult(503, ""),
            "https://api.weather.bom.gov.au/v1/locations/r1r0fs/forecasts/daily" to HttpResult(200, BOM_DAILY),
        ))
        val w2 = DeclarativeProvider(ProviderLoader.parse(providerJson("bom-au.json")), partial, now = fixedNow).fetch(melbourne)
        check("partial: temp null", w2.current?.temperatureC == null)
        check("partial: forecast present", w2.daily.size == 4, { w2.daily.size.toString() })
        check("partial: warning says HTTP 503", w2.warnings.any { "HTTP 503" in it }, { w2.warnings.toString() })

        // everything down -> exception
        val dead = FakeFetcher(emptyMap())
        expectThrows("total failure throws", "no usable data") { DeclarativeProvider(ProviderLoader.parse(providerJson("bom-au.json")), dead, now = fixedNow).fetch(melbourne) }
        val io = HttpFetcher { _, _ -> throw IOException("offline") }
        expectThrows("network error reported", "offline") { DeclarativeProvider(ProviderLoader.parse(providerJson("bom-au.json")), io, now = fixedNow).fetch(melbourne) }
    }

    println("HTML scraping (Jsoup)")
    run {
        val f = FakeFetcher(mapOf("https://weather.example.org/town/foo" to HttpResult(200, HTML_PAGE)))
        val p = DeclarativeProvider(ProviderLoader.parse(providerJson("examples/html-example.json")), f, JsoupHtmlParser(), fixedNow)
        val w = p.fetch(melbourne.copy(extra = mapOf("slug" to "foo")))
        check("current temp via first-number", w.current?.temperatureC == 18.4, { w.current.toString() })
        check("current condition by text", w.current?.condition == Condition.PARTLY_CLOUDY)
        check("3 rows", w.daily.size == 3)
        check("sequential dates", w.daily.map { it.date } == listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)))
        check("regex max", w.daily[0].maxC == 21.0 && w.daily[1].maxC == 25.0)
        check("negative min", w.daily[1].minC == -1.0)
        check("attr title -> condition", w.daily[0].condition == Condition.SHOWERS && w.daily[1].condition == Condition.THUNDERSTORM && w.daily[2].condition == Condition.PARTLY_CLOUDY)
        check("percent", w.daily[2].rainChancePct == 5)
        expectThrows("html without parser", "no usable data") { DeclarativeProvider(ProviderLoader.parse(providerJson("examples/html-example.json")), f, null, fixedNow).fetch(melbourne.copy(extra = mapOf("slug" to "foo"))) }
    }

    println("Units and validation")
    run {
        val fJson = """{"t":68.0,"k":283.15,"r":0.5}"""
        val def = """{"schema":1,"id":"units","name":"Units","requests":[{"id":"r","url":"https://x.test/a","type":"json"}],
          "current":{"temperature":{"path":"$.t","unit":"f"},"feelsLike":{"path":"$.k","unit":"k"},"rain":{"path":"$.r","unit":"in"}}}"""
        val w = DeclarativeProvider(ProviderLoader.parse(def), FakeFetcher(mapOf("https://x.test/" to HttpResult(200, fJson))), now = fixedNow).fetch(melbourne)
        check("68F = 20C", w.current?.temperatureC == 20.0, { w.current.toString() })
        check("283.15K = 10C", Math.abs((w.current?.feelsLikeC ?: 0.0) - 10.0) < 1e-9)
        check("0.5in = 12.7mm", Math.abs((w.current?.rainMm ?: 0.0) - 12.7) < 1e-9)

        fun bad(name: String, expect: String, json: String) = expectThrows(name, expect) { ProviderLoader.parse(json) }
        val req = """"requests":[{"id":"r","url":"https://x.test/a","type":"json"}]"""
        bad("typo in field name", "current.temprature", """{"schema":1,"id":"a","name":"A",$req,"current":{"temprature":"$.t"}}""")
        bad("http rejected", "https", """{"schema":1,"id":"a","name":"A","requests":[{"id":"r","url":"http://x.test","type":"json"}]}""")
        bad("unknown condition map", "unknown condition map", """{"schema":1,"id":"a","name":"A",$req,"current":{"condition":{"path":"$.c","map":"nope"}}}""")
        bad("selector on JSON request", "use 'path'", """{"schema":1,"id":"a","name":"A",$req,"current":{"temperature":{"selector":".x"}}}""")
        bad("bad regex", "invalid regular expression", """{"schema":1,"id":"a","name":"A",$req,"current":{"temperature":{"path":"$.t","regex":"("}}}""")
        bad("unit on wrong field", "not valid here", """{"schema":1,"id":"a","name":"A",$req,"current":{"rain":{"path":"$.t","unit":"c"}}}""")
        bad("bad condition name", "not a condition", """{"schema":1,"id":"a","name":"A",$req,"conditionMaps":{"m":{"SUNNYISH":["x"]}}}""")
        bad("daily needs date", "needs a 'date'", """{"schema":1,"id":"a","name":"A",$req,"daily":{"items":"$.d[*]","fields":{"max":"$.m"}}}""")
        bad("not json", "not valid JSON", "{oops")
    }


    println("Cache round trip")
    run {
        val f = FakeFetcher(mapOf("https://api.open-meteo.com/" to HttpResult(200, OPEN_METEO)))
        val w = DeclarativeProvider(ProviderLoader.parse(providerJson("open-meteo.json")), f, now = fixedNow).fetch(melbourne)
        val back = WeatherJson.fromJson(WeatherJson.toJson(w.copy(warnings = listOf("quote \" and \\ and \n newline"))))
        check("round trip equal", back == w.copy(warnings = listOf("quote \" and \\ and \n newline")), { back.toString() })
        val nulls = WeatherData("x", 5L, null, listOf(DailyForecast(LocalDate.of(2026, 1, 2), null, 3.5, Condition.SNOW, null, null)), null, null, emptyList())
        check("nulls survive", WeatherJson.fromJson(WeatherJson.toJson(nulls)) == nulls)
    }

    println("Formatting")
    run {
        check("temp rounds", Fmt.temp(14.5) == "15°" && Fmt.temp(14.4) == "14°" && Fmt.temp(-0.2) == "0°" && Fmt.temp(-3.6) == "-4°")
        check("temp null", Fmt.temp(null) == "–")
        check("range", Fmt.range(9.0, 19.4) == "9°/19°" && Fmt.range(null, 19.0) == "–/19°" && Fmt.range(null, null) == "–")
        check("rain", Fmt.rain(null) == null && Fmt.rain(0.0) == "0 mm" && Fmt.rain(1.64) == "1.6 mm" && Fmt.rain(12.4) == "12 mm")
        check("isDay", Fmt.isDay(null, 7) && !Fmt.isDay(null, 20) && !Fmt.isDay(CurrentWeather(1.0, null, null, Condition.CLEAR, false), 12))
        check("refresh intervals", RefreshInterval.values().map { it.minutes } == listOf(60, 120, 360, 720, 1440) && RefreshInterval.fromMinutes(999) == RefreshInterval.H2)
    }

    println("Fallback")
    run {
        val reg = ProviderRegistry(FakeFetcher(mapOf("https://api.open-meteo.com/" to HttpResult(200, OPEN_METEO))), JsoupHtmlParser())
        reg.addFromJson(providerJson("bom-au.json"))
        reg.addFromJson(providerJson("open-meteo.json"))
        val w = reg.fetchWithFallback("bom-au", "open-meteo", melbourne)   // BOM routes give 404 in this fake
        check("fell back to open-meteo", w.providerId == "open-meteo" && w.warnings.any { "bom-au" in it && "used 'open-meteo'" in it }, { w.warnings.toString() })
        expectThrows("no fallback -> error", "no usable data") { reg.fetchWithFallback("bom-au", null, melbourne) }
        expectThrows("missing provider, no fallback", "not installed") { reg.fetchWithFallback("nope", null, melbourne) }
        check("primary ok -> no fallback used", reg.fetchWithFallback("open-meteo", "bom-au", melbourne).warnings.isEmpty())
    }

    println("Calendar logic")
    run {
        val z = java.time.ZoneId.of("Australia/Melbourne")
        fun ms(s: String) = java.time.LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
        fun utcMs(s: String) = java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val mon = LocalDate.of(2026, 10, 12)
        val meeting = CalEvent(1, "Meeting", ms("2026-10-12T09:00:00"), ms("2026-10-12T10:00:00"), false, 0xFF0000)
        val lateNight = CalEvent(2, "Late", ms("2026-10-12T23:00:00"), ms("2026-10-13T01:00:00"), false, 0x00FF00)
        val allDay = CalEvent(3, "Holiday", utcMs("2026-10-13"), utcMs("2026-10-14"), true, 0x0000FF)
        val threeDay = CalEvent(4, "Camp", utcMs("2026-10-14"), utcMs("2026-10-17"), true, 0x123456)
        val endsMidnight = CalEvent(5, "Ends midnight", ms("2026-10-12T20:00:00"), ms("2026-10-13T00:00:00"), false, 1)
        val zeroLen = CalEvent(6, "Zero", ms("2026-10-15T08:00:00"), ms("2026-10-15T08:00:00"), false, 1)
        check("all-day not shifted by zone", CalendarLogic.span(allDay, z) == LocalDate.of(2026, 10, 13) to LocalDate.of(2026, 10, 13), { CalendarLogic.span(allDay, z).toString() })
        check("3-day all-day spans 14..16", CalendarLogic.span(threeDay, z) == LocalDate.of(2026, 10, 14) to LocalDate.of(2026, 10, 16))
        check("event ending at midnight stays on its day", CalendarLogic.span(endsMidnight, z).second == mon)
        check("overnight event covers two days", CalendarLogic.span(lateNight, z) == mon to mon.plusDays(1))
        check("zero length event ok", CalendarLogic.span(zeroLen, z).let { it.first == it.second })
        val days = CalendarLogic.byDay(listOf(meeting, lateNight, allDay, threeDay, endsMidnight, zeroLen), mon, 7, z)
        check("7 days always", days.size == 7 && days[0].date == mon)
        check("day 1 order: timed by start", days[0].events.map { it.id } == listOf(1L, 5L, 2L), { days[0].events.map { it.id }.toString() })
        check("day 2: all-day first then overnight carry", days[1].events.map { it.id } == listOf(3L, 2L), { days[1].events.map { it.id }.toString() })
        check("multi-day shown on each day", listOf(2, 3, 4).all { days[it].events.any { e -> e.id == 4L } })
        check("week start monday", CalendarLogic.firstDay(LocalDate.of(2026, 10, 14), WeekStart.MONDAY) == mon)
        check("week start sunday", CalendarLogic.firstDay(LocalDate.of(2026, 10, 14), WeekStart.SUNDAY) == LocalDate.of(2026, 10, 11))
        check("week start today", CalendarLogic.firstDay(LocalDate.of(2026, 10, 14), WeekStart.TODAY) == LocalDate.of(2026, 10, 14))
        val (from, to) = CalendarLogic.queryWindow(mon, 7, z)
        check("query window 7 local days", to - from == 7L * 24 * 3600 * 1000, { (to - from).toString() })

        val rows = CalendarLogic.agenda(days, 20)
        check("agenda skips empty days", rows.count { it is AgendaRow.Header } == days.count { it.events.isNotEmpty() })
        val cut = CalendarLogic.agenda(days, 5)
        check("agenda cut to 5 rows with More", cut.size == 5 && cut.last() is AgendaRow.More, { cut.toString() })
        val more = cut.last() as AgendaRow.More
        val allItems = rows.count { it is AgendaRow.Item }
        check("More counts hidden items", cut.count { it is AgendaRow.Item } + more.hidden == allItems, { "$cut $allItems" })
        check("no dangling header before More", cut.getOrNull(cut.size - 2) !is AgendaRow.Header, { cut.toString() })
        val (shown, hidden) = CalendarLogic.chips(days[0], 2)
        check("chips: 3 events, max 2 -> 1 shown + 2 hidden", shown.size == 1 && hidden == 2)
        check("chips: fits", CalendarLogic.chips(days[3], 5).second == 0)
    }

    println("Picture names")
    run {
        fun t(path: String) = PictureNames.classify(path)
        check("folder + file", t("Rain/heavy_night_3.jpg") == PictureNames.Tag("heavy_rain", "night"), { t("Rain/heavy_night_3.jpg").toString() })
        check("plain rain", t("rain1.jpg") == PictureNames.Tag("rain", "any"))
        check("sentence style", t("Clear sky - morning.JPG") == PictureNames.Tag("clear", "day"))
        check("partly before cloudy", t("partly-cloudy-day.png")?.group == "partly_cloudy" && t("Cloudy/IMG_1.jpg")?.group == "cloudy")
        check("snow beats storm", t("snow storm.jpg")?.group == "snow" && t("thunderstorm_night.jpg")?.group == "storm")
        check("fog words", t("fog/dawn.jpg")?.group == "fog" && t("hazy.jpg")?.group == "fog")
        check("no weather word", t("IMG_20240101_123456.jpg") == null && t("holiday/beach.jpg") == null)
        check("sunday is not sun", t("sunday.jpg") == null)
        val c = listOf("day" to "a", "night" to "b", "any" to "c", "day" to "d")
        check("choose exact", PictureNames.choose(c, "night", 5L) == "b" && PictureNames.choose(c, "day", 0L) == "a" && PictureNames.choose(c, "day", 1L) == "d")
        check("choose any then other", PictureNames.choose(listOf("any" to "x", "day" to "y"), "night", 9L) == "x" && PictureNames.choose(listOf("day" to "y"), "night", 3L) == "y")
        check("choose empty / negative seed", PictureNames.choose(emptyList<Pair<String, String>>(), "day", 1L) == null && PictureNames.choose(c, "day", -3L) != null)
    }


    println("\n$passes passed, $failures failed")
    if (failures > 0) exitProcess(1)
}
