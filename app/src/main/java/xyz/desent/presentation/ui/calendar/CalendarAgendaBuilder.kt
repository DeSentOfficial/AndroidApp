package xyz.desent.presentation.ui.calendar

import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.usecase.RecurringEventExpander
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Flattened agenda view: day headers interleaved with event rows. */
sealed class CalendarListItem {
    data class DayHeader(val epochDay: Long, val label: String) : CalendarListItem()

    /**
     * One calendar event row. For a recurring series this is a single
     * occurrence — a read-only copy sharing the series
     * [NostrCalendarEvent.id] but shifted to this occurrence's
     * start/end — so list keys must pair the id with [NostrCalendarEvent.startSec].
     */
    data class EventItem(val event: NostrCalendarEvent) : CalendarListItem()

    /**
     * Contact-derived anniversary chip (ANDROID_CONTACTS.md §7): rendered for
     * every matching MM-DD (years ≥ the stored year), nothing stored on the
     * wire. Capped at [CalendarAgendaBuilder.MAX_CHIPS_PER_DAY] after the
     * day's real events.
     */
    data class AnniversaryItem(val contact: PrivateContact, val anniversaryLabel: String) : CalendarListItem()
}

/**
 * Pure flattening of calendar events + contacts into the agenda list used by
 * the calendar screen (and the month grid's event dots).
 *
 * Recurring series are expanded into virtual occurrences over a bounded
 * window around today ([PAST_WINDOW_DAYS] back, [FUTURE_WINDOW_DAYS] forward)
 * so each occurrence lands on its own day; one-off events pass through
 * unfiltered (the agenda has always listed every stored event). Derived
 * contact anniversaries are computed for the same forward horizon.
 */
object CalendarAgendaBuilder {

    /** How far back recurring occurrences are expanded (past events). */
    const val PAST_WINDOW_DAYS = 365L

    /** Forward expansion horizon; matches the anniversary-derivation cap. */
    const val FUTURE_WINDOW_DAYS = 730L

    /** Cap ~2 anniversary chips per day after real event rows (spec §7). */
    const val MAX_CHIPS_PER_DAY = 2

    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

    fun build(
        events: List<NostrCalendarEvent>,
        contacts: List<PrivateContact>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<CalendarListItem> {
        val windowStartSec = today.minusDays(PAST_WINDOW_DAYS).atStartOfDay(zone).toEpochSecond()
        val windowEndSec = today.plusDays(FUTURE_WINDOW_DAYS + 1).atStartOfDay(zone).toEpochSecond()
        val oneOff = events.filter { it.recurrence == null }
        val occurrences = RecurringEventExpander.expandAll(events, windowStartSec, windowEndSec)
        val all = oneOff + occurrences

        // Group events by start day. All-day events key off the floating ISO
        // dates (via the UTC-midnight index — never a zone conversion, which
        // would draw them a day early west of UTC); time-based events convert
        // their instants in the local zone.
        val eventsByDay = all.groupBy { it.daySpanIn(zone).first }

        // … and derive contact anniversaries for the visible window (today →
        // last event day, or +90 days when there are no events; bounded to 2
        // years). Chips recur every matching MM-DD for years ≥ the stored
        // year; nothing is stored.
        val lastDay = minOf(
            eventsByDay.keys.maxOrNull() ?: today.plusDays(90).toEpochDay(),
            today.plusDays(FUTURE_WINDOW_DAYS).toEpochDay()
        )
        val anniversariesByDay = HashMap<Long, MutableList<Pair<PrivateContact, String>>>()
        var d = today
        while (!d.isAfter(LocalDate.ofEpochDay(lastDay))) {
            contacts.forEach { contact ->
                contact.anniversaries.forEach { anniversary ->
                    val stored = anniversary.parsedDate() ?: return@forEach
                    if (stored.year > d.year) return@forEach
                    if (stored.monthValue == d.monthValue && stored.dayOfMonth == d.dayOfMonth) {
                        anniversariesByDay.getOrPut(d.toEpochDay()) { mutableListOf() }
                            .add(contact to anniversary.label.ifBlank { "Date" })
                    }
                }
            }
            d = d.plusDays(1)
        }

        val out = mutableListOf<CalendarListItem>()
        val allDays = (eventsByDay.keys + anniversariesByDay.keys).sorted()
        allDays.forEach { day ->
            out.add(CalendarListItem.DayHeader(day, LocalDate.ofEpochDay(day).format(dateFormatter)))
            eventsByDay[day]?.sortedBy { it.startSec }?.forEach { event ->
                out.add(CalendarListItem.EventItem(event))
            }
            anniversariesByDay[day]
                ?.take(MAX_CHIPS_PER_DAY)
                ?.forEach { (contact, label) ->
                    out.add(CalendarListItem.AnniversaryItem(contact, label))
                }
        }
        return out
    }
}

/**
 * Day span `[startDay, endDay)` in epoch days for month-grid day counting,
 * via [NostrCalendarEvent.daySpanIn]. Instantaneous time-based events (null
 * end) still paint their start day — matching the agenda, which lists them
 * on their start day (the widget's entity path intentionally leaves them
 * undotted).
 */
internal fun NostrCalendarEvent.gridDaySpan(zone: ZoneId): Pair<Long, Long> =
    daySpanIn(zone)
