package xyz.desent.wear

import xyz.desent.data.wearsync.WearAnniversary
import xyz.desent.data.wearsync.WearCalendar
import xyz.desent.data.wearsync.WearCalendarEvent
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.usecase.RecurringEventExpander
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure mapping of decrypted domain calendar events + private contacts to the
 * watch payload ([WearCalendar]). The phone is the only party that decrypts
 * or expands recurrence — the watch receives a flat occurrence list over the
 * window "today → +14 days" and renders it.
 *
 * Day-grouping mirrors [xyz.desent.presentation.ui.calendar.CalendarAgendaBuilder]:
 * all-day events key off the floating ISO dates (UTC-midnight epoch-day
 * index, never a zone conversion), time-based events convert their instants
 * in the local zone. Anniversaries reuse the agenda's derivation (every
 * matching MM-DD for years ≥ the stored year, 2/day cap) bounded to the
 * same window.
 *
 * Android-free so the windowing/caps logic is unit-testable
 * (see WearCalendarBuilderTest).
 */
object WearCalendarBuilder {

    /** Forward sync window: today through today + 14 days (15 day headers). */
    const val FUTURE_WINDOW_DAYS = 14L

    const val MAX_EVENTS = 100
    const val MAX_ANNIVERSARIES = 60

    /** Mirrors CalendarAgendaBuilder.MAX_CHIPS_PER_DAY. */
    const val MAX_ANNIVERSARIES_PER_DAY = 2

    const val MAX_TITLE_CHARS = 120
    const val MAX_LOCATION_CHARS = 120
    const val MAX_DESCRIPTION_CHARS = 1200
    const val MAX_NAME_CHARS = 80

    fun build(
        events: List<NostrCalendarEvent>,
        contacts: List<PrivateContact>,
        today: LocalDate,
        zone: ZoneId,
        syncedAt: Long
    ): WearCalendar {
        val windowStartSec = today.atStartOfDay(zone).toEpochSecond()
        val windowEndSec = today.plusDays(FUTURE_WINDOW_DAYS + 1).atStartOfDay(zone).toEpochSecond()

        // Recurring series expanded over the window; one-offs pass through.
        val oneOff = events.filter { it.recurrence == null }
        val occurrences = RecurringEventExpander.expandAll(events, windowStartSec, windowEndSec)

        // Group by start day, matching agenda semantics: an occurrence shows
        // on the day it starts. Keep only starts within [today, today+14].
        val firstDay = today.toEpochDay()
        val lastDay = today.toEpochDay() + FUTURE_WINDOW_DAYS
        val inWindow = (oneOff + occurrences)
            .filter { event ->
                val startDay = event.daySpanIn(zone).first
                startDay in firstDay..lastDay
            }
            .sortedWith(compareBy({ it.startSec }, { it.id }))
            .take(MAX_EVENTS)

        return WearCalendar(
            events = inWindow.map(::toWearEvent),
            anniversaries = buildAnniversaries(contacts, today),
            windowStartSec = windowStartSec,
            windowEndSec = windowEndSec,
            syncedAt = syncedAt
        )
    }

    private fun toWearEvent(event: NostrCalendarEvent): WearCalendarEvent = WearCalendarEvent(
        id = event.id,
        title = cap(event.title, MAX_TITLE_CHARS),
        startSec = event.startSec,
        endSec = event.endSec,
        allDay = event.allDay,
        startDateIso = event.startDateIso?.takeIf { event.allDay },
        endDateIso = event.endDateIso?.takeIf { event.allDay },
        location = cap(event.location.orEmpty(), MAX_LOCATION_CHARS),
        description = cap(event.description.orEmpty(), MAX_DESCRIPTION_CHARS),
        updatedAt = event.updatedAt
    )

    private fun buildAnniversaries(contacts: List<PrivateContact>, today: LocalDate): List<WearAnniversary> {
        val out = mutableListOf<WearAnniversary>()
        var day = today
        val lastDay = today.plusDays(FUTURE_WINDOW_DAYS)
        while (!day.isAfter(lastDay)) {
            var perDay = 0
            contacts.forEach { contact ->
                contact.anniversaries.forEach { anniversary ->
                    val stored = anniversary.parsedDate() ?: return@forEach
                    if (stored.year > day.year) return@forEach
                    if (stored.monthValue == day.monthValue && stored.dayOfMonth == day.dayOfMonth) {
                        if (perDay >= MAX_ANNIVERSARIES_PER_DAY) return@forEach
                        perDay++
                        val label = anniversary.label.ifBlank { "Date" }
                        val ref = contact.pubkey ?: contact.primaryEmail.ifBlank { contact.name }
                        out.add(
                            WearAnniversary(
                                key = "$ref|$label",
                                name = cap(contact.name, MAX_NAME_CHARS),
                                label = label,
                                epochDay = day.toEpochDay()
                            )
                        )
                    }
                }
            }
            if (out.size >= MAX_ANNIVERSARIES) return out.take(MAX_ANNIVERSARIES)
            day = day.plusDays(1)
        }
        return out
    }

    private fun cap(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"
}
