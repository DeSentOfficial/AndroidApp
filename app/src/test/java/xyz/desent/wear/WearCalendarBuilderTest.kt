package xyz.desent.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.CalendarParticipant
import xyz.desent.domain.model.ContactAnniversary
import xyz.desent.domain.model.ContactEmailAddress
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.RecurFreq
import xyz.desent.domain.model.Recurrence
import java.time.LocalDate
import java.time.ZoneId

class WearCalendarBuilderTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 8, 29)

    private fun event(
        id: String,
        title: String = "Event $id",
        startSec: Long,
        endSec: Long? = null,
        kind: Int = NostrCalendarEvent.KIND_TIME,
        startDateIso: String? = null,
        endDateIso: String? = null,
        location: String? = null,
        description: String? = null,
        recurrence: Recurrence? = null,
        updatedAt: Long = 1L
    ) = NostrCalendarEvent(
        id = id,
        ownerNpub = "npub1test",
        kind = kind,
        dTag = "desent:event:$id",
        title = title,
        startSec = startSec,
        endSec = endSec,
        allDay = kind == NostrCalendarEvent.KIND_DATE,
        startDateIso = startDateIso,
        endDateIso = endDateIso,
        startTzid = null,
        endTzid = null,
        summary = null,
        description = description,
        location = location,
        geohash = null,
        image = null,
        participants = emptyList<CalendarParticipant>(),
        links = emptyList(),
        hashtags = emptyList(),
        calendarD = null,
        attachments = emptyList(),
        shares = emptyList(),
        recurrence = recurrence,
        updatedAt = updatedAt,
        createdAt = 1L
    )

    private fun contact(
        name: String,
        vararg anniversaries: Pair<String, String> // label to ISO date
    ) = PrivateContact(
        name = name,
        emails = listOf(ContactEmailAddress(label = "home", value = "$name@example.com".lowercase())),
        anniversaries = anniversaries.map { (label, iso) -> ContactAnniversary(label = label, date = iso) }
    )

    private fun sec(day: LocalDate, hour: Int = 10) =
        day.atStartOfDay(zone).toEpochSecond() + hour * 3600

    @Test
    fun mapsTimedEventWithinWindow() {
        val calendar = WearCalendarBuilder.build(
            events = listOf(
                event("a", startSec = sec(today.plusDays(2), 14), endSec = sec(today.plusDays(2), 15),
                    location = "Room 1", description = "Desc", updatedAt = 7L)
            ),
            contacts = emptyList(),
            today = today,
            zone = zone,
            syncedAt = 99L
        )
        val ev = calendar.events.single()
        assertEquals("a", ev.id)
        assertEquals("Event a", ev.title)
        assertEquals(sec(today.plusDays(2), 14), ev.startSec)
        assertEquals(sec(today.plusDays(2), 15), ev.endSec)
        assertEquals(false, ev.allDay)
        assertNull(ev.startDateIso)
        assertEquals("Room 1", ev.location)
        assertEquals(7L, ev.updatedAt)
        assertEquals(99L, calendar.syncedAt)
        assertEquals(today.atStartOfDay(zone).toEpochSecond(), calendar.windowStartSec)
    }

    @Test
    fun allDayEventCarriesIsoDatesNotZoneMath() {
        val day = today.plusDays(3)
        val calendar = WearCalendarBuilder.build(
            events = listOf(
                event(
                    "d1",
                    kind = NostrCalendarEvent.KIND_DATE,
                    startSec = day.toEpochDay() * 86_400,
                    endSec = (day.toEpochDay() + 2) * 86_400,
                    startDateIso = day.toString(),
                    endDateIso = day.plusDays(2).toString()
                )
            ),
            contacts = emptyList(),
            today = today,
            zone = zone,
            syncedAt = 0L
        )
        val ev = calendar.events.single()
        assertTrue(ev.allDay)
        assertEquals(day.toString(), ev.startDateIso)
        assertEquals(day.plusDays(2).toString(), ev.endDateIso)
    }

    @Test
    fun eventsOutsideWindowAreDropped() {
        val calendar = WearCalendarBuilder.build(
            events = listOf(
                event("past", startSec = sec(today.minusDays(2))),
                event("edge-in", startSec = sec(today)),                 // today 10:00 — in
                event("last", startSec = sec(today.plusDays(14))),       // last day — in
                event("far", startSec = sec(today.plusDays(15)))         // day after window — out
            ),
            contacts = emptyList(),
            today = today,
            zone = zone,
            syncedAt = 0L
        )
        assertEquals(listOf("edge-in", "last"), calendar.events.map { it.id })
    }

    @Test
    fun recurringSeriesExpandsToWindowOccurrences() {
        val anchor = today.minusDays(30)
        val series = event(
            "weekly",
            startSec = sec(anchor, 9),
            endSec = sec(anchor, 10),
            recurrence = Recurrence(
                freq = RecurFreq.WEEKLY,
                interval = 1,
                byDay = listOf(anchor.dayOfWeek.name.take(2).uppercase())
            )
        )
        val calendar = WearCalendarBuilder.build(
            events = listOf(series),
            contacts = emptyList(),
            today = today,
            zone = zone,
            syncedAt = 0L
        )
        // ~2-3 weekly occurrences fall in the 15-day window; all share the series id
        assertTrue("expected expanded occurrences, got ${calendar.events.size}", calendar.events.size in 2..3)
        assertTrue(calendar.events.all { it.id == "weekly" })
        assertTrue(calendar.events.all { it.startSec >= today.atStartOfDay(zone).toEpochSecond() })
    }

    @Test
    fun capsEventCountAndLongFields() {
        val events = (1..200).map {
            event("e$it", title = "T".repeat(300), startSec = sec(today.plusDays((it % 14).toLong())),
                location = "L".repeat(300), description = "D".repeat(3000))
        }
        val calendar = WearCalendarBuilder.build(events, emptyList(), today, zone, 0L)
        assertEquals(WearCalendarBuilder.MAX_EVENTS, calendar.events.size)
        assertTrue(calendar.events.all { it.title.length <= WearCalendarBuilder.MAX_TITLE_CHARS })
        assertTrue(calendar.events.all { it.location.length <= WearCalendarBuilder.MAX_LOCATION_CHARS })
        assertTrue(calendar.events.all { it.description.length <= WearCalendarBuilder.MAX_DESCRIPTION_CHARS })
    }

    @Test
    fun anniversariesDerivedWithinWindowWithPerDayCap() {
        val birthdayDay = today.plusDays(5)
        val contacts = listOf(
            contact("Alice", "Birthday" to "${today.year - 30}-${"%02d".format(birthdayDay.monthValue)}-${"%02d".format(birthdayDay.dayOfMonth)}"),
            contact("Bob", "Anniversary" to "${today.year - 2}-${"%02d".format(birthdayDay.monthValue)}-${"%02d".format(birthdayDay.dayOfMonth)}"),
            contact("Carol", "Date" to "${today.year - 1}-${"%02d".format(birthdayDay.monthValue)}-${"%02d".format(birthdayDay.dayOfMonth)}"),
            contact("Future", "Wedding" to "${today.year + 1}-01-01") // stored year in the future — skipped
        )
        val calendar = WearCalendarBuilder.build(emptyList(), contacts, today, zone, 0L)
        assertEquals(2, calendar.anniversaries.size) // per-day cap: Alice + Bob, Carol dropped
        assertEquals(birthdayDay.toEpochDay(), calendar.anniversaries[0].epochDay)
        assertTrue(calendar.anniversaries.all { it.name != "Future" })
    }

    @Test
    fun emptyInputsYieldEmptyPayloadWithWindow() {
        val calendar = WearCalendarBuilder.build(emptyList(), emptyList(), today, zone, 5L)
        assertEquals(0, calendar.events.size)
        assertEquals(0, calendar.anniversaries.size)
        assertEquals(5L, calendar.syncedAt)
        assertTrue(calendar.windowEndSec > calendar.windowStartSec)
    }
}
