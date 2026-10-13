// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** A calendar event as read from the system calendar. [color] is the system display colour (event colour, else calendar colour). */
data class CalEvent(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String? = null,
)

data class DayEvents(val date: LocalDate, val events: List<CalEvent>)

sealed class AgendaRow {
    data class Header(val date: LocalDate) : AgendaRow()
    data class Item(val event: CalEvent, val date: LocalDate) : AgendaRow()
    data class More(val hidden: Int) : AgendaRow()
}

enum class WeekStart { MONDAY, SUNDAY, TODAY }

object CalendarLogic {

    fun firstDay(today: LocalDate, start: WeekStart): LocalDate = when (start) {
        WeekStart.MONDAY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        WeekStart.SUNDAY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        WeekStart.TODAY -> today
    }

    /** [from, to) in epoch millis covering [days] local days starting at [firstDay]. */
    fun queryWindow(firstDay: LocalDate, days: Int, zone: ZoneId): Pair<Long, Long> =
        firstDay.atStartOfDay(zone).toInstant().toEpochMilli() to
            firstDay.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

    /** First and last local date an event touches. All-day events are stored in UTC and must not be shifted by the zone. */
    fun span(e: CalEvent, zone: ZoneId): Pair<LocalDate, LocalDate> {
        if (e.allDay) {
            val first = Instant.ofEpochMilli(e.startMs).atZone(ZoneOffset.UTC).toLocalDate()
            val endExclusive = Instant.ofEpochMilli(e.endMs).atZone(ZoneOffset.UTC).toLocalDate()
            val last = if (endExclusive.isAfter(first)) endExclusive.minusDays(1) else first
            return first to last
        }
        val first = Instant.ofEpochMilli(e.startMs).atZone(zone).toLocalDate()
        val last = if (e.endMs > e.startMs) Instant.ofEpochMilli(e.endMs - 1).atZone(zone).toLocalDate() else first
        return first to if (last.isBefore(first)) first else last
    }

    /** Always returns exactly [days] entries (empty days included). Multi-day events appear on every day they cover. */
    fun byDay(events: List<CalEvent>, firstDay: LocalDate, days: Int, zone: ZoneId): List<DayEvents> =
        (0 until days).map { i ->
            val day = firstDay.plusDays(i.toLong())
            val onDay = events.filter { e -> span(e, zone).let { (a, b) -> !day.isBefore(a) && !day.isAfter(b) } }
                .sortedWith(compareBy<CalEvent>({ !it.allDay }, { it.startMs }, { it.title }))
            DayEvents(day, onDay)
        }

    /** Agenda: day header + its events, only for days that have events, at most [maxRows] rows (the last one may be "+N more"). */
    fun agenda(days: List<DayEvents>, maxRows: Int): List<AgendaRow> {
        require(maxRows >= 2) { "maxRows must be at least 2" }
        val flat = ArrayList<AgendaRow>()
        var total = 0
        for (d in days) {
            if (d.events.isEmpty()) continue
            flat += AgendaRow.Header(d.date)
            d.events.forEach { flat += AgendaRow.Item(it, d.date); total++ }
        }
        if (flat.size <= maxRows) return flat
        val kept = ArrayList(flat.subList(0, maxRows - 1))
        if (kept.last() is AgendaRow.Header) kept.removeAt(kept.size - 1)
        val shown = kept.count { it is AgendaRow.Item }
        kept += AgendaRow.More(total - shown)
        return kept
    }

    /** Events to draw in one week column plus how many did not fit. */
    fun chips(day: DayEvents, max: Int): Pair<List<CalEvent>, Int> =
        if (day.events.size <= max) day.events to 0
        else day.events.take(max - 1).let { it to day.events.size - it.size }
}
