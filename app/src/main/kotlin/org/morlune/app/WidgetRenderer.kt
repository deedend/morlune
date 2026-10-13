// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.morlune.engine.AgendaRow
import org.morlune.engine.CalEvent
import org.morlune.engine.CalendarLogic
import org.morlune.engine.Condition
import org.morlune.engine.Fmt
import org.morlune.engine.WeatherData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Builds the RemoteViews for one widget from settings, cached weather and the system calendar. */
object WidgetRenderer {
    private const val AGENDA_WINDOW_DAYS = 14

    fun render(ctx: Context, widgetId: Int): RemoteViews {
        val s = WidgetSettings.of(ctx, widgetId)
        val g = GlobalSettings.of(ctx)
        val zone = g.zone()
        val now = ZonedDateTime.now(zone)
        val data = WeatherCache.load(ctx)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_main)

        background(ctx, rv, s, data, now)
        clock(rv, s, g)
        weather(ctx, rv, s, g, data, now.toLocalDate())
        forecast(ctx, rv, s, data, now.toLocalDate())
        calendar(ctx, rv, s, zone, now.toLocalDate())

        val url = s.weatherUrl.ifBlank { data?.webUrl.orEmpty() }.ifBlank { null }
        rv.setOnClickPendingIntent(R.id.clock_area, Targets.clock(ctx, widgetId, s.clockApp))
        rv.setOnClickPendingIntent(R.id.weather_area, Targets.weather(ctx, widgetId, url))
        rv.setOnClickPendingIntent(R.id.forecast, Targets.weather(ctx, widgetId, url))
        rv.setOnClickPendingIntent(R.id.calendar_area, Targets.calendar(ctx, widgetId, s.calendarApp, now.toInstant().toEpochMilli()))
        return rv
    }

    private fun alpha255(pct: Int) = (pct.coerceIn(0, 100) * 255 + 50) / 100

    private fun background(ctx: Context, rv: RemoteViews, s: WidgetSettings, data: WeatherData?, now: ZonedDateTime) {
        rv.setInt(R.id.panel, "setColorFilter", s.bgColor or (0xFF shl 24))
        rv.setInt(R.id.panel, "setImageAlpha", alpha255(s.bgOpacityPct))
        val res = if (s.imageEnabled && data != null)
            Icons.backgroundRes(ctx, data.backgroundCondition, Fmt.isDay(data.current, now.hour)) else 0
        if (res != 0) {
            rv.setViewVisibility(R.id.weather_bg, View.VISIBLE)
            rv.setImageViewResource(R.id.weather_bg, res)
            rv.setInt(R.id.weather_bg, "setImageAlpha", alpha255(s.imageOpacityPct))
        } else {
            rv.setViewVisibility(R.id.weather_bg, View.GONE)
        }
    }

    private fun clock(rv: RemoteViews, s: WidgetSettings, g: GlobalSettings) {
        val (f12, f24) = when (s.clockStyle) {
            "24h" -> "HH:mm" to "HH:mm"
            "12h" -> "h:mm" to "h:mm"
            "12h_ampm" -> "h:mm a" to "h:mm a"
            else -> "h:mm" to "HH:mm"
        }
        // TextClock ticks by itself, so the app is not woken up every minute.
        rv.setCharSequence(R.id.clock, "setFormat12Hour", f12)
        rv.setCharSequence(R.id.clock, "setFormat24Hour", f24)
        rv.setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_SP, s.clockSizeSp.toFloat())
        rv.setCharSequence(R.id.date, "setFormat12Hour", s.datePattern)
        rv.setCharSequence(R.id.date, "setFormat24Hour", s.datePattern)
        listOf(R.id.clock, R.id.date, R.id.location).forEach { rv.setTextColor(it, s.textColor) }

        val name = g.location()?.name
        if (s.showLocation && name != null) {
            rv.setViewVisibility(R.id.location, View.VISIBLE)
            rv.setTextViewText(R.id.location, name)
        } else {
            rv.setViewVisibility(R.id.location, View.GONE)
        }
    }

    private fun weather(ctx: Context, rv: RemoteViews, s: WidgetSettings, g: GlobalSettings, data: WeatherData?, today: LocalDate) {
        rv.setTextViewTextSize(R.id.temp, TypedValue.COMPLEX_UNIT_SP, s.clockSizeSp * 0.55f)
        listOf(R.id.temp, R.id.minmax, R.id.rain).forEach { rv.setTextColor(it, s.textColor) }
        if (data == null) {
            rv.setTextViewText(R.id.temp, "–")
            rv.setTextViewText(R.id.minmax, ctx.getString(if (g.location() == null) R.string.no_weather_yet else R.string.updating))
            rv.setViewVisibility(R.id.rain, View.GONE)
            return
        }
        val todayForecast = data.daily.firstOrNull { it.date == today }
        rv.setTextViewText(R.id.temp, Fmt.temp(data.current?.temperatureC ?: todayForecast?.let { avg(it.minC, it.maxC) }))
        rv.setTextViewText(R.id.minmax, if (todayForecast != null) Fmt.range(todayForecast.minC, todayForecast.maxC) else "")
        val rain = Fmt.rain(data.current?.rainMm ?: todayForecast?.rainMm)
        if (rain != null) {
            rv.setViewVisibility(R.id.rain, View.VISIBLE)
            rv.setTextViewText(R.id.rain, ctx.getString(R.string.rain_fmt, rain))
        } else {
            rv.setViewVisibility(R.id.rain, View.GONE)
        }
    }

    private fun avg(a: Double?, b: Double?): Double? = if (a != null && b != null) (a + b) / 2 else a ?: b

    private fun forecast(ctx: Context, rv: RemoteViews, s: WidgetSettings, data: WeatherData?, today: LocalDate) {
        rv.removeAllViews(R.id.forecast)
        val days = data?.daily?.filter { it.date.isAfter(today) }?.take(s.forecastDays).orEmpty()
        if (days.isEmpty()) { rv.setViewVisibility(R.id.forecast, View.GONE); return }
        rv.setViewVisibility(R.id.forecast, View.VISIBLE)
        for (d in days) {
            val cell = RemoteViews(ctx.packageName, R.layout.forecast_cell)
            cell.setImageViewResource(R.id.fc_icon, Icons.iconRes(ctx, s.iconPack, d.condition, true))
            cell.setInt(R.id.fc_icon, "setColorFilter", s.iconColor)
            cell.setTextViewText(R.id.fc_temps, Fmt.range(d.minC, d.maxC))
            cell.setTextColor(R.id.fc_temps, s.textColor)
            rv.addView(R.id.forecast, cell)
        }
    }

    // ---- calendar ---------------------------------------------------------------------------------------------

    private fun calendar(ctx: Context, rv: RemoteViews, s: WidgetSettings, zone: ZoneId, today: LocalDate) {
        rv.removeAllViews(R.id.cal_content)
        rv.setTextColor(R.id.cal_message, s.textColor)
        if (!CalendarRepository.hasPermission(ctx)) return message(ctx, rv, R.string.cal_permission_needed)

        if (s.calendarMode == "week") {
            val first = CalendarLogic.firstDay(today, s.weekStart)
            val (from, to) = CalendarLogic.queryWindow(first, 7, zone)
            val days = CalendarLogic.byDay(CalendarRepository.query(ctx, from, to), first, 7, zone)
            val row = RemoteViews(ctx.packageName, R.layout.week_row)
            val dayFmt = DateTimeFormatter.ofPattern("EEEEE d", Locale.getDefault())
            for (day in days) {
                val col = RemoteViews(ctx.packageName, R.layout.week_day)
                val label = SpannableString(day.date.format(dayFmt))
                if (day.date == today) label.setSpan(UnderlineSpan(), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                col.setTextViewText(R.id.wk_header, label)
                col.setTextColor(R.id.wk_header, s.textColor)
                val (shown, hidden) = CalendarLogic.chips(day, s.weekChips.coerceAtLeast(1))
                for (e in shown) col.addView(R.id.wk_events, chip(ctx, e))
                if (hidden > 0) col.addView(R.id.wk_events, moreRow(ctx, s, ctx.getString(R.string.cal_more, hidden), center = true))
                row.addView(R.id.week_row, col)
            }
            rv.setViewVisibility(R.id.cal_message, View.GONE)
            rv.addView(R.id.cal_content, row)
            return
        }

        val (from, to) = CalendarLogic.queryWindow(today, AGENDA_WINDOW_DAYS, zone)
        val days = CalendarLogic.byDay(CalendarRepository.query(ctx, from, to), today, AGENDA_WINDOW_DAYS, zone)
        val rows = CalendarLogic.agenda(days, s.calendarRows.coerceAtLeast(2))
        if (rows.isEmpty()) return message(ctx, rv, R.string.cal_nothing)
        rv.setViewVisibility(R.id.cal_message, View.GONE)
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        for (r in rows) {
            when (r) {
                is AgendaRow.Header -> {
                    val h = RemoteViews(ctx.packageName, R.layout.agenda_header)
                    val text = when (r.date) {
                        today -> ctx.getString(R.string.day_today)
                        today.plusDays(1) -> ctx.getString(R.string.day_tomorrow)
                        else -> r.date.format(dayFmt)
                    }
                    h.setTextViewText(R.id.ag_header, text)
                    h.setTextColor(R.id.ag_header, s.textColor)
                    rv.addView(R.id.cal_content, h)
                }
                is AgendaRow.Item -> {
                    val item = RemoteViews(ctx.packageName, R.layout.agenda_item)
                    item.setInt(R.id.ag_dot, "setColorFilter", r.event.color)
                    item.setTextViewText(R.id.ag_time, timeLabel(ctx, r.event, r.date, zone))
                    item.setTextViewText(R.id.ag_title, r.event.title)
                    item.setTextColor(R.id.ag_time, s.textColor)
                    item.setTextColor(R.id.ag_title, s.textColor)
                    rv.addView(R.id.cal_content, item)
                }
                is AgendaRow.More -> rv.addView(R.id.cal_content, moreRow(ctx, s, ctx.getString(R.string.cal_more, r.hidden), center = false))
            }
        }
    }

    private fun message(ctx: Context, rv: RemoteViews, res: Int) {
        rv.setViewVisibility(R.id.cal_message, View.VISIBLE)
        rv.setTextViewText(R.id.cal_message, ctx.getString(res))
    }

    private fun moreRow(ctx: Context, s: WidgetSettings, text: String, center: Boolean): RemoteViews {
        val m = RemoteViews(ctx.packageName, R.layout.agenda_more)
        m.setTextViewText(R.id.ag_more, text)
        m.setTextColor(R.id.ag_more, s.textColor)
        if (center) m.setInt(R.id.ag_more, "setGravity", android.view.Gravity.CENTER_HORIZONTAL)
        return m
    }

    private fun chip(ctx: Context, e: CalEvent): RemoteViews {
        val c = RemoteViews(ctx.packageName, R.layout.week_chip)
        c.setInt(R.id.chip_bg, "setColorFilter", e.color)
        c.setTextViewText(R.id.chip_text, e.title)
        c.setTextColor(R.id.chip_text, onColor(e.color))
        return c
    }

    /** Black or white text, whichever reads better on the event colour. */
    internal fun onColor(bg: Int): Int {
        val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0
        return if (lum > 0.6) Color.BLACK else Color.WHITE
    }

    private fun timeLabel(ctx: Context, e: CalEvent, day: LocalDate, zone: ZoneId): String {
        if (e.allDay) return ctx.getString(R.string.cal_all_day)
        val fmt = DateFormat.getTimeFormat(ctx)
        val startDay = Instant.ofEpochMilli(e.startMs).atZone(zone).toLocalDate()
        return if (startDay.isBefore(day)) "→ " + fmt.format(Date(e.endMs)) else fmt.format(Date(e.startMs))
    }
}
