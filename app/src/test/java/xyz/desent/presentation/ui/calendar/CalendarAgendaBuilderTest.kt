package xyz.desent.presentation.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.domain.model.ContactAnniversary
import xyz.desent.domain.model.ContactEmailAddress
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.Recurrence
import xyz.desent.domain.model.RecurFreq
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class CalendarAgendaBuilderTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 8, 26)

    @Before
    fun setUp() {
        // The builder's formatters are default-locale aware (correct for
        // production); pin the test JVM so label assertions hold.
        Locale.setDefault(Locale.ENGLISH)
    }

    private fun event(
        id: String,
        startSec: Long,
        endSec: Long? = null,
        recurrence: Recurrence? = null,
        allDay: Boolean = false,
        startDateIso: String? = null,
        endDateIso: String? = null
    ): NostrCalendarEvent = NostrCalendarEvent(
        id = id,
        ownerNpub = "npub_owner",
        kind = if (allDay) NostrCalendarEvent.KIND_DATE else NostrCalendarEvent.KIND_TIME,
        dTag = "desent:event:$id",
        title = "Event $id",
        startSec = startSec,
        endSec = endSec,
        allDay = allDay,
        startDateIso = startDateIso,
        endDateIso = endDateIso,
        startTzid = null,
        endTzid = null,
        summary = null,
        description = null,
        location = null,
        geohash = null,
        image = null,
        participants = emptyList(),
        links = emptyList(),
        hashtags = emptyList(),
        calendarD = null,
        attachments = emptyList(),
        shares = emptyList(),
        recurrence = recurrence,
        updatedAt = 0,
        createdAt = 0
    )

    private fun sec(date: LocalDate, hour: Int = 0): Long =
        date.atStartOfDay(zone).toEpochSecond() + hour * 3600

    private fun build(
        events: List<NostrCalendarEvent> = emptyList(),
        contacts: List<PrivateContact> = emptyList()
    ): List<CalendarListItem> = CalendarAgendaBuilder.build(events, contacts, today, zone)

    /** Re-group the flattened agenda into day → rows, preserving order. */
    private fun byDay(items: List<CalendarListItem>): Map<Long, List<CalendarListItem>> {
        val out = LinkedHashMap<Long, MutableList<CalendarListItem>>()
        var currentDay = Long.MIN_VALUE
        items.forEach { item ->
            if (item is CalendarListItem.DayHeader) {
                currentDay = item.epochDay
                out.getOrPut(currentDay) { mutableListOf() }
            } else {
                out.getOrPut(currentDay) { mutableListOf() }.add(item)
            }
        }
        return out
    }

    @Test
    fun `one-off events are grouped under day headers sorted by day then start`() {
        val tue = LocalDate.of(2026, 8, 18)
        val wed = LocalDate.of(2026, 8, 19)
        val items = build(
            listOf(
                event("late", sec(tue, 14), sec(tue, 15)),
                event("early", sec(tue, 8), sec(tue, 9)),
                event("next", sec(wed, 10), null)
            )
        )

        val grouped = byDay(items)
        assertEquals(listOf(tue.toEpochDay(), wed.toEpochDay()), grouped.keys.toList())
        assertEquals(listOf("early", "late"), grouped[tue.toEpochDay()]!!.map { (it as CalendarListItem.EventItem).event.id })
    }

    @Test
    fun `weekly series expands to an occurrence per week`() {
        // Anchor Tue Aug 18, weekly → every Tuesday within the window.
        val anchor = LocalDate.of(2026, 8, 18)
        val weekly = event(
            "standup",
            sec(anchor, 9),
            sec(anchor, 10),
            recurrence = Recurrence(freq = RecurFreq.WEEKLY)
        )

        val items = build(listOf(weekly))
        val starts = items.filterIsInstance<CalendarListItem.EventItem>().map { it.event.startSec }

        assertTrue(starts.size > 4)
        // Every occurrence is the stored series, shifted to a Tuesday 9:00.
        starts.forEach { s ->
            val zdt = Instant.ofEpochSecond(s).atZone(zone)
            assertEquals(anchor.dayOfWeek, zdt.dayOfWeek)
            assertEquals(9, zdt.hour)
        }
        assertEquals(setOf("standup"), items.filterIsInstance<CalendarListItem.EventItem>().map { it.event.id }.toSet())
        // Each occurrence sits under its own day header.
        assertEquals(starts.distinct().size, byDay(items).count { (_, rows) -> rows.any { it is CalendarListItem.EventItem } })
    }

    @Test
    fun `occurrences of one series produce distinct list keys`() {
        val anchor = LocalDate.of(2026, 8, 18)
        val weekly = event(
            "standup",
            sec(anchor, 9),
            sec(anchor, 10),
            recurrence = Recurrence(freq = RecurFreq.WEEKLY)
        )

        val keys = build(listOf(weekly)).filterIsInstance<CalendarListItem.EventItem>()
            .map { "event:${it.event.id}:${it.event.startSec}" }

        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `recurring occurrences outside the expansion window are dropped`() {
        // Daily series anchored far before today: only occurrences within
        // [today - 365d, today + 730d] may appear.
        val daily = event(
            "old-daily",
            sec(LocalDate.of(2024, 1, 1), 9),
            sec(LocalDate.of(2024, 1, 1), 10),
            recurrence = Recurrence(freq = RecurFreq.DAILY)
        )

        val starts = build(listOf(daily)).filterIsInstance<CalendarListItem.EventItem>().map { it.event.startSec }

        val windowStart = today.minusDays(CalendarAgendaBuilder.PAST_WINDOW_DAYS).atStartOfDay(zone).toEpochSecond()
        val windowEnd = today.plusDays(CalendarAgendaBuilder.FUTURE_WINDOW_DAYS + 1).atStartOfDay(zone).toEpochSecond()
        assertTrue(starts.isNotEmpty())
        assertTrue(starts.all { it >= windowStart && it < windowEnd })
    }

    @Test
    fun `one-off events outside the window are still listed`() {
        // The agenda has always listed every stored event; the window only
        // bounds recurrence expansion.
        val ancient = LocalDate.of(2020, 1, 1)

        val items = build(listOf(event("ancient", sec(ancient), null)))

        assertEquals(1, items.filterIsInstance<CalendarListItem.EventItem>().size)
        assertEquals(listOf(ancient.toEpochDay()), byDay(items).keys.toList())
    }

    // ------------------------------------------------------------------
    // All-day (31922) floating dates — the pinned timezone fix
    // ------------------------------------------------------------------

    @Test
    fun `all-day event groups under its floating ISO day in every timezone`() {
        // Aug 29 2026 as a floating date → UTC-midnight index. The day key
        // must be the ISO day itself; the old code converted the index
        // through the viewer's zone and grouped it under Aug 28 in
        // America/New_York (the "shows the day before" bug).
        val day = LocalDate.of(2026, 8, 29)
        val startSec = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        for (zone in listOf("UTC", "America/New_York", "Europe/Berlin", "Pacific/Auckland")) {
            val allDay = event(
                "allday", startSec, startSec + 86_400L,
                allDay = true, startDateIso = "2026-08-29"
            )
            val grouped = byDay(CalendarAgendaBuilder.build(listOf(allDay), emptyList(), today, ZoneId.of(zone)))
            assertEquals("zone $zone", listOf(day.toEpochDay()), grouped.keys.toList())
        }
    }

    @Test
    fun `multi-day all-day span is exclusive at the wire end date`() {
        // Start Aug 28, wire (exclusive) end Aug 31 → covers Aug 28–30 only,
        // in every zone.
        val start = LocalDate.of(2026, 8, 28)
        val allDay = event(
            "trip",
            start.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
            start.plusDays(3).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
            allDay = true, startDateIso = "2026-08-28", endDateIso = "2026-08-31"
        )
        for (zone in listOf("UTC", "America/New_York", "Europe/Berlin")) {
            val span = allDay.gridDaySpan(ZoneId.of(zone))
            assertEquals("zone $zone", start.toEpochDay(), span.first)
            assertEquals("zone $zone", start.plusDays(3).toEpochDay(), span.second)
        }
    }

    @Test
    fun `contact anniversary recurs on matching month-day after its stored year`() {
        val contact = PrivateContact(
            name = "Ada",
            emails = listOf(ContactEmailAddress("", "ada@example.com")),
            anniversaries = listOf(ContactAnniversary("Birthday", "1990-08-28"))
        )
        // A far-future event extends the derivation window to its 2-year cap
        // (with no events the horizon is only +90 days — unchanged behavior).
        val horizonExtender = event("far", sec(LocalDate.of(2027, 12, 1)), null)

        val grouped = byDay(build(events = listOf(horizonExtender), contacts = listOf(contact)))

        // 2026-08-28 and 2027-08-28 both recur within the window; nothing
        // before today (chips are forward-looking).
        assertEquals(
            listOf(LocalDate.of(2026, 8, 28).toEpochDay(), LocalDate.of(2027, 8, 28).toEpochDay()),
            grouped.keys.filter { day -> grouped[day]!!.any { it is CalendarListItem.AnniversaryItem } }
        )
        val chip = grouped.values.flatten().filterIsInstance<CalendarListItem.AnniversaryItem>().first()
        assertEquals("Ada", chip.contact.name)
        assertEquals("Birthday", chip.anniversaryLabel)
    }

    @Test
    fun `anniversary chips are capped per day`() {
        val contacts = (1..4).map { n ->
            PrivateContact(
                name = "C$n",
                anniversaries = listOf(ContactAnniversary("D$n", "1990-08-27"))
            )
        }

        val items = build(contacts = contacts)

        assertEquals(CalendarAgendaBuilder.MAX_CHIPS_PER_DAY, items.filterIsInstance<CalendarListItem.AnniversaryItem>().size)
    }
}
