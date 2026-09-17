package xyz.desent.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.entity.CalendarEventEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class CalendarWidgetBuildersTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    @Before
    fun setUp() {
        // The widget formatters are default-locale aware (correct for
        // production); pin the test JVM so the English assertions hold.
        Locale.setDefault(Locale.ENGLISH)
    }

    private fun event(
        id: String,
        startSec: Long,
        endSec: Long?,
        title: String = "Event $id",
        kind: Int = 31923
    ) = CalendarEventEntity(
        id = id,
        ownerNpub = "npub_owner",
        kind = kind,
        dTag = "desent:event:$id",
        title = title,
        startSec = startSec,
        endSec = endSec,
        calendarD = null,
        payloadJson = "{}",
        updatedAt = 0,
        createdAt = 0
    )

    private fun sec(date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        date.atStartOfDay(zone).toEpochSecond() + hour * 3600 + minute * 60

    // ------------------------------------------------------------------
    // Month grid
    // ------------------------------------------------------------------

    @Test
    fun `month grid has 42 cells starting on first day of week`() {
        // August 2026 starts on a Saturday; with a Sunday first day of week
        // the grid must lead with July 26 (Sunday).
        val anchor = LocalDate.of(2026, 8, 15)

        val cells = buildMonthCells(anchor, today = anchor, DayOfWeek.SUNDAY, emptyMap())

        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2026, 7, 26).toEpochDay(), cells.first().epochDay)
        assertEquals(LocalDate.of(2026, 9, 5).toEpochDay(), cells.last().epochDay)
    }

    @Test
    fun `month grid marks out-of-month and today cells`() {
        val anchor = LocalDate.of(2026, 8, 1)

        val cells = buildMonthCells(anchor, today = LocalDate.of(2026, 8, 3), DayOfWeek.SUNDAY, emptyMap())

        val todayCell = cells.single { it.isToday }
        assertEquals(3, todayCell.dayOfMonth)
        assertTrue(todayCell.inMonth)
        // Leading (July) and trailing (September) cells are out of month.
        assertTrue(cells.first().epochDay < anchor.toEpochDay() && !cells.first().inMonth)
        assertTrue(!cells.last().inMonth)
    }

    @Test
    fun `multi-day event counts toward every overlapped day`() {
        // Two-day event on Aug 20–21 (start Aug 20 10:00, end Aug 22 00:00).
        val aug20 = LocalDate.of(2026, 8, 20)
        val events = listOf(
            event("multi", sec(aug20, 10), sec(aug20.plusDays(2)))
        )

        val counts = buildEventDayCounts(events, aug20, days = 7, zone = zone)

        assertEquals(1, counts[aug20.toEpochDay()])
        assertEquals(1, counts[aug20.plusDays(1).toEpochDay()])
        assertEquals(null, counts[aug20.plusDays(2).toEpochDay()])
    }

    @Test
    fun `events outside the range are ignored`() {
        val aug20 = LocalDate.of(2026, 8, 20)
        val events = listOf(event("before", sec(aug20.minusDays(1)), null))

        val counts = buildEventDayCounts(events, aug20, days = 3, zone = zone)

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `span-based and entity-based day counts agree`() {
        val aug20 = LocalDate.of(2026, 8, 20)
        val events = listOf(
            event("multi", sec(aug20, 10), sec(aug20.plusDays(2))),
            event("point", sec(aug20.plusDays(1), 9), null)
        )

        val fromEntities = buildEventDayCounts(events, aug20, days = 7, zone = zone)
        val fromSpans = buildSpanDayCounts(
            spans = events.map { it.daySpan(zone) },
            rangeStart = aug20,
            days = 7
        )

        assertEquals(fromEntities, fromSpans)
    }

    @Test
    fun `span-based counts clip spans to the range`() {
        // Span starts two days before the window and ends at its exclusive
        // boundary — clipped to exactly Aug 20–21 inside the window.
        val aug20 = LocalDate.of(2026, 8, 20)
        val spans = listOf((aug20.toEpochDay() - 2) to (aug20.toEpochDay() + 2))

        val counts = buildSpanDayCounts(spans, aug20, days = 3)

        assertEquals(setOf(aug20.toEpochDay(), aug20.plusDays(1).toEpochDay()), counts.keys)
    }

    @Test
    fun `empty spans count for no days`() {
        // Instantaneous span (start == end) stays dot-less, preserving the
        // widget's historical behavior.
        val aug20 = LocalDate.of(2026, 8, 20)
        val spans = listOf(aug20.toEpochDay() to aug20.toEpochDay())

        val counts = buildSpanDayCounts(spans, aug20, days = 3)

        assertTrue(counts.isEmpty())
    }

    // ------------------------------------------------------------------
    // All-day (31922) floating dates — the pinned timezone fix
    // ------------------------------------------------------------------

    @Test
    fun `all-day entity dots on its ISO day west of UTC`() {
        // Aug 20 2026 floating date → UTC-midnight index. In New York the
        // old code zone-converted the index (Aug 19 20:00 local) and dotted
        // Aug 19; the day must come from the floating date itself.
        val aug20 = LocalDate.of(2026, 8, 20)
        val events = listOf(event("allday", sec(aug20), sec(aug20.plusDays(1)), kind = 31922))

        val counts = buildEventDayCounts(events, aug20, days = 3, zone = ZoneId.of("America/New_York"))

        assertEquals(1, counts[aug20.toEpochDay()])
        assertEquals(null, counts[aug20.minusDays(1).toEpochDay()])
        assertEquals(null, counts[aug20.plusDays(1).toEpochDay()])
    }

    @Test
    fun `all-day multi-day entity ends at the exclusive wire end`() {
        // Wire (exclusive) end Aug 22 → covers Aug 20–21 only, in any zone.
        val aug20 = LocalDate.of(2026, 8, 20)
        val events = listOf(event("trip", sec(aug20), sec(aug20.plusDays(2)), kind = 31922))

        for (zone in listOf(ZoneId.of("UTC"), ZoneId.of("America/New_York"), ZoneId.of("Europe/Berlin"))) {
            val counts = buildEventDayCounts(events, aug20, days = 5, zone = zone)
            assertEquals("zone $zone", setOf(aug20.toEpochDay(), aug20.plusDays(1).toEpochDay()), counts.keys)
        }
    }

    @Test
    fun `timed entity dots in the viewer zone`() {
        // Aug 20 01:00Z is Aug 19 21:00 in New York — a timed instant must
        // dot its LOCAL day (unlike all-day floating dates).
        val aug20 = LocalDate.of(2026, 8, 20)
        val events = listOf(event("call", sec(aug20, 1), sec(aug20, 2)))

        val utcCounts = buildEventDayCounts(events, aug20.minusDays(1), days = 3, zone = ZoneId.of("UTC"))
        val nyCounts = buildEventDayCounts(events, aug20.minusDays(1), days = 3, zone = ZoneId.of("America/New_York"))

        assertEquals(setOf(aug20.toEpochDay()), utcCounts.keys)
        assertEquals(setOf(aug20.minusDays(1).toEpochDay()), nyCounts.keys)
    }

    // ------------------------------------------------------------------
    // Day / week agenda
    // ------------------------------------------------------------------

    @Test
    fun `day view lists only that day's events sorted by start`() {
        val day = LocalDate.of(2026, 8, 19)
        val events = listOf(
            event("late", sec(day, 14), sec(day, 15)),
            event("early", sec(day, 9), sec(day, 10)),
            event("tomorrow", sec(day.plusDays(1), 9), null),
            event("allday", sec(day), sec(day.plusDays(1)), kind = 31922)
        )

        val items = buildDayAgendaItems(events, day, zone)

        assertEquals(listOf("allday", "early", "late"), items.map { (it as CalendarAgendaItem.Event).event.id })
        assertEquals("All day", (items[0] as CalendarAgendaItem.Event).event.timeLabel)
    }

    @Test
    fun `day view with no events yields the empty row`() {
        val items = buildDayAgendaItems(emptyList(), LocalDate.of(2026, 8, 19), zone)

        assertEquals(listOf<CalendarAgendaItem>(CalendarAgendaItem.Empty), items)
    }

    @Test
    fun `day view lists all-day event on its ISO day west of UTC and not the next`() {
        val day = LocalDate.of(2026, 8, 19)
        val ny = ZoneId.of("America/New_York")
        val events = listOf(event("allday", sec(day), sec(day.plusDays(1)), kind = 31922))

        assertEquals(
            listOf("allday"),
            buildDayAgendaItems(events, day, ny).map { (it as CalendarAgendaItem.Event).event.id }
        )
        assertEquals(
            listOf<CalendarAgendaItem>(CalendarAgendaItem.Empty),
            buildDayAgendaItems(events, day.plusDays(1), ny)
        )
    }

    @Test
    fun `week view interleaves day headers with events and skips empty days`() {
        // Week of Aug 17–23, 2026 (Mon–Sun). Events on Tue 18 and Sat 22.
        val tue = LocalDate.of(2026, 8, 18)
        val sat = LocalDate.of(2026, 8, 22)
        val events = listOf(
            event("b", sec(tue, 10), sec(tue, 11)),
            event("a", sec(tue, 8), null),
            event("c", sec(sat, 9), sec(sat, 10))
        )

        val items = buildWeekAgendaItems(events, tue, DayOfWeek.MONDAY, zone)

        assertEquals(
            listOf(
                CalendarAgendaItem.DayHeader(tue.toEpochDay(), tue.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))),
                CalendarAgendaItem.Event(events[1].let { CalendarWidgetEventItem("a", "Event a", "8:00 AM", false) }),
                CalendarAgendaItem.Event(CalendarWidgetEventItem("b", "Event b", "10:00 AM – 11:00 AM", false)),
                CalendarAgendaItem.DayHeader(sat.toEpochDay(), sat.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))),
                CalendarAgendaItem.Event(CalendarWidgetEventItem("c", "Event c", "9:00 AM – 10:00 AM", false))
            ),
            items
        )
    }

    @Test
    fun `week view with no events yields the empty row`() {
        val items = buildWeekAgendaItems(emptyList(), LocalDate.of(2026, 8, 19), DayOfWeek.MONDAY, zone)

        assertEquals(1, items.size)
        assertEquals(CalendarAgendaItem.Empty, items.single())
    }

    @Test
    fun `multi-day event appears on each day of a week view`() {
        // Wed Aug 19 10:00 → Thu Aug 20 11:00 spans both days.
        val wed = LocalDate.of(2026, 8, 19)
        val events = listOf(event("span", sec(wed, 10), sec(wed.plusDays(1), 11)))

        val items = buildWeekAgendaItems(events, wed, DayOfWeek.MONDAY, zone)

        val headers = items.filterIsInstance<CalendarAgendaItem.DayHeader>()
        assertEquals(listOf(wed.toEpochDay(), wed.plusDays(1).toEpochDay()), headers.map { it.epochDay })
    }

    @Test
    fun `blank event title renders as untitled`() {
        val day = LocalDate.of(2026, 8, 19)
        val items = buildDayAgendaItems(listOf(event("x", sec(day, 9), null, title = "")), day, zone)

        assertEquals("(untitled)", (items.single() as CalendarAgendaItem.Event).event.title)
    }

    // ------------------------------------------------------------------
    // Week / title formatting
    // ------------------------------------------------------------------

    @Test
    fun `weekStart respects first day of week`() {
        // Aug 19 2026 is a Wednesday.
        val wed = LocalDate.of(2026, 8, 19)

        assertEquals(LocalDate.of(2026, 8, 17), weekStart(wed, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 8, 16), weekStart(wed, DayOfWeek.SUNDAY))
        assertEquals(wed, weekStart(wed, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun `titles format per view`() {
        val wed = LocalDate.of(2026, 8, 19)

        assertEquals("August 2026", formatCalendarWidgetTitle(CalendarWidgetView.MONTH, wed))
    }

    @Test
    fun `day title uses EEE comma MMM d pattern`() {
        val wed = LocalDate.of(2026, 8, 19)
        val title = formatCalendarWidgetTitle(CalendarWidgetView.DAY, wed)

        assertEquals("Wed, Aug 19", title)
    }

    @Test
    fun `week title collapses same-month range`() {
        // Aug 17–23 2026 (Mon–Sun), same month.
        val mon = LocalDate.of(2026, 8, 17)
        val title = formatCalendarWidgetTitle(CalendarWidgetView.WEEK, mon)

        assertEquals("Aug 17 – 23", title)

        // Jul 27–Aug 2 crosses a month boundary.
        val boundary = LocalDate.of(2026, 7, 27)
        assertEquals("Jul 27 – Aug 2", formatCalendarWidgetTitle(CalendarWidgetView.WEEK, boundary))
    }
}
