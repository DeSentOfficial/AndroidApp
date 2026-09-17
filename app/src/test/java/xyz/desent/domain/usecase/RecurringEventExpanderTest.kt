package xyz.desent.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.Recurrence
import xyz.desent.domain.model.RecurFreq
import java.time.LocalDate
import java.time.ZoneOffset

class RecurringEventExpanderTest {

    private val zone = ZoneOffset.UTC

    private fun sec(date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        date.atStartOfDay(zone).toEpochSecond() + hour * 3600 + minute * 60

    private fun event(
        id: String = "ev1",
        startSec: Long,
        endSec: Long? = null,
        allDay: Boolean = false,
        recurrence: Recurrence?
    ): NostrCalendarEvent = NostrCalendarEvent(
        id = id, ownerNpub = "npub1owner", kind = NostrCalendarEvent.KIND_TIME,
        dTag = NostrCalendarEvent.D_PREFIX + id, title = "Series",
        startSec = startSec, endSec = endSec, allDay = allDay,
        startDateIso = if (allDay) LocalDate.ofEpochDay(startSec / 86400).toString() else null,
        endDateIso = if (allDay && endSec != null) LocalDate.ofEpochDay(endSec / 86400).toString() else null,
        startTzid = null, endTzid = null, summary = null, description = null,
        location = null, geohash = null, image = null,
        participants = emptyList(), links = emptyList(), hashtags = emptyList(),
        calendarD = null, attachments = emptyList(), shares = emptyList(),
        recurrence = recurrence, updatedAt = 0, createdAt = 0
    )

    private fun datesOf(events: List<NostrCalendarEvent>): List<LocalDate> =
        events.map { LocalDate.ofEpochDay(it.startSec / 86400) }

    // ------------------------------------------------------------------
    // YEARLY
    // ------------------------------------------------------------------

    @Test
    fun `yearly birthday anchored decades ago occurs in query year`() {
        val anchor = LocalDate.of(1990, 3, 15)
        val ev = event(
            startSec = sec(anchor), endSec = sec(anchor.plusDays(1)), allDay = true,
            recurrence = Recurrence(freq = RecurFreq.YEARLY, omitYear = true)
        )

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 3, 1)), sec(LocalDate.of(2026, 4, 1)))

        assertEquals(listOf(LocalDate.of(2026, 3, 15)), datesOf(occurrences))
        val occ = occurrences.single()
        // Duration and ISO dates shift with the occurrence; id stays the series id.
        assertEquals(sec(LocalDate.of(2026, 3, 15)), occ.startSec)
        assertEquals(sec(LocalDate.of(2026, 3, 16)), occ.endSec)
        assertEquals("2026-03-15", occ.startDateIso)
        assertEquals("2026-03-16", occ.endDateIso)
        assertEquals("ev1", occ.id)
    }

    @Test
    fun `yearly interval 2 only hits every other year`() {
        val anchor = LocalDate.of(2024, 6, 1)
        val ev = event(startSec = sec(anchor), recurrence = Recurrence(freq = RecurFreq.YEARLY, interval = 2))

        // 2025 is an off year; 2026 is on (2024 + 2k).
        val r1 = RecurringEventExpander.expand(ev, sec(LocalDate.of(2025, 1, 1)), sec(LocalDate.of(2025, 12, 31)))
        assertTrue(r1.isEmpty())

        val r2 = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 1)), sec(LocalDate.of(2026, 12, 31)))
        assertEquals(listOf(LocalDate.of(2026, 6, 1)), datesOf(r2))
    }

    @Test
    fun `feb 29 yearly anchor clamps to feb 28 in common years and stays feb 29 in leap years`() {
        val anchor = LocalDate.of(2024, 2, 29)
        val ev = event(startSec = sec(anchor), recurrence = Recurrence(freq = RecurFreq.YEARLY))

        val common = RecurringEventExpander.expand(ev, sec(LocalDate.of(2025, 2, 1)), sec(LocalDate.of(2025, 3, 1)))
        assertEquals(listOf(LocalDate.of(2025, 2, 28)), datesOf(common))

        val leap = RecurringEventExpander.expand(ev, sec(LocalDate.of(2028, 2, 1)), sec(LocalDate.of(2028, 3, 1)))
        assertEquals(listOf(LocalDate.of(2028, 2, 29)), datesOf(leap))
    }

    // ------------------------------------------------------------------
    // DAILY / WEEKLY
    // ------------------------------------------------------------------

    @Test
    fun `daily interval 2 hits every other day in range`() {
        val anchor = LocalDate.of(2026, 1, 1)
        val ev = event(
            startSec = sec(anchor), endSec = sec(anchor) + 3600,
            recurrence = Recurrence(freq = RecurFreq.DAILY, interval = 2)
        )

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 5)), sec(LocalDate.of(2026, 1, 12)))

        assertEquals(
            listOf(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 11)),
            datesOf(occurrences)
        )
    }

    @Test
    fun `weekly byDay expands to listed weekdays each week`() {
        // Anchor is a Wednesday; rule overrides with Monday + Friday.
        val anchor = LocalDate.of(2026, 1, 7)
        val ev = event(
            startSec = sec(anchor), endSec = sec(anchor) + 3600,
            recurrence = Recurrence(freq = RecurFreq.WEEKLY, byDay = listOf("MO", "FR"))
        )

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 12)), sec(LocalDate.of(2026, 1, 19)))

        assertEquals(listOf(LocalDate.of(2026, 1, 12), LocalDate.of(2026, 1, 16)), datesOf(occurrences))
    }

    @Test
    fun `weekly without byDay follows the anchor weekday`() {
        val anchor = LocalDate.of(2026, 1, 7) // Wednesday
        val ev = event(startSec = sec(anchor), recurrence = Recurrence(freq = RecurFreq.WEEKLY))

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 12)), sec(LocalDate.of(2026, 1, 19)))

        assertEquals(listOf(LocalDate.of(2026, 1, 14)), datesOf(occurrences))
    }

    // ------------------------------------------------------------------
    // MONTHLY
    // ------------------------------------------------------------------

    @Test
    fun `monthly day 31 skips months without that day`() {
        val anchor = LocalDate.of(2026, 1, 31)
        val ev = event(startSec = sec(anchor), recurrence = Recurrence(freq = RecurFreq.MONTHLY))

        val feb = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 2, 1)), sec(LocalDate.of(2026, 3, 1)))
        assertTrue("February has no 31st", feb.isEmpty())

        val mar = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 3, 1)), sec(LocalDate.of(2026, 4, 1)))
        assertEquals(listOf(LocalDate.of(2026, 3, 31)), datesOf(mar))
    }

    @Test
    fun `monthly ordinal byDay selects nth weekday of month`() {
        val ev = event(
            startSec = sec(LocalDate.of(2026, 1, 1)),
            recurrence = Recurrence(freq = RecurFreq.MONTHLY, byDay = listOf("2TU"))
        )

        // Tuesdays of Feb 2026: 3, 10, 17, 24 → second = 10th.
        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 2, 1)), sec(LocalDate.of(2026, 3, 1)))
        assertEquals(listOf(LocalDate.of(2026, 2, 10)), datesOf(occurrences))
    }

    @Test
    fun `monthly negative ordinal selects last weekday of month`() {
        val ev = event(
            startSec = sec(LocalDate.of(2026, 1, 1)),
            recurrence = Recurrence(freq = RecurFreq.MONTHLY, byDay = listOf("-1FR"))
        )

        // Last Friday of Feb 2026 is the 27th.
        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 2, 1)), sec(LocalDate.of(2026, 3, 1)))
        assertEquals(listOf(LocalDate.of(2026, 2, 27)), datesOf(occurrences))
    }

    @Test
    fun `monthly plain byDay selects every such weekday of month`() {
        val ev = event(
            startSec = sec(LocalDate.of(2026, 1, 1)),
            recurrence = Recurrence(freq = RecurFreq.MONTHLY, byDay = listOf("MO"))
        )

        // Mondays of Feb 2026: 2, 9, 16, 23.
        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 2, 1)), sec(LocalDate.of(2026, 3, 1)))
        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 9),
                LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 23)
            ),
            datesOf(occurrences)
        )
    }

    // ------------------------------------------------------------------
    // Bounds: UNTIL / COUNT / range edges
    // ------------------------------------------------------------------

    @Test
    fun `until is inclusive`() {
        val ev = event(
            startSec = sec(LocalDate.of(2026, 6, 1)), endSec = sec(LocalDate.of(2026, 6, 1)) + 3600,
            recurrence = Recurrence(freq = RecurFreq.DAILY, untilIso = "2026-06-03")
        )

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 6, 1)), sec(LocalDate.of(2026, 7, 1)))
        assertEquals(
            listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 3)),
            datesOf(occurrences)
        )
    }

    @Test
    fun `count counts from the anchor regardless of range start`() {
        val anchor = LocalDate.of(2026, 1, 7) // Wednesday
        val ev = event(startSec = sec(anchor), recurrence = Recurrence(freq = RecurFreq.WEEKLY, count = 3))

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 1)), sec(LocalDate.of(2026, 3, 1)))
        assertEquals(
            listOf(LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 14), LocalDate.of(2026, 1, 21)),
            datesOf(occurrences)
        )
    }

    @Test
    fun `range end is exclusive`() {
        val ev = event(
            startSec = sec(LocalDate.of(2026, 1, 1)), endSec = sec(LocalDate.of(2026, 1, 1)) + 3600,
            recurrence = Recurrence(freq = RecurFreq.DAILY)
        )

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 3)), sec(LocalDate.of(2026, 1, 4)))
        assertEquals(listOf(LocalDate.of(2026, 1, 3)), datesOf(occurrences))
    }

    @Test
    fun `multi-day occurrence overlapping range start is found`() {
        // Yearly 3-day event Jul 1–4, anchored in 1990.
        val anchor = LocalDate.of(1990, 7, 1)
        val ev = event(
            startSec = sec(anchor), endSec = sec(anchor.plusDays(3)), allDay = true,
            recurrence = Recurrence(freq = RecurFreq.YEARLY)
        )

        // Range covers Jul 2 2026 only; the 2026 occurrence starts Jul 1 but overlaps.
        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 7, 2)), sec(LocalDate.of(2026, 7, 3)))
        assertEquals(listOf(LocalDate.of(2026, 7, 1)), datesOf(occurrences))
        assertEquals(sec(LocalDate.of(2026, 7, 4)), occurrences.single().endSec)
    }

    @Test
    fun `time-based occurrence keeps time of day and duration`() {
        val ev = event(
            startSec = sec(LocalDate.of(2026, 1, 7), 9, 30),
            endSec = sec(LocalDate.of(2026, 1, 7), 10, 30),
            recurrence = Recurrence(freq = RecurFreq.DAILY)
        )

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 8)), sec(LocalDate.of(2026, 1, 10)))
        assertEquals(2, occurrences.size)
        assertEquals(sec(LocalDate.of(2026, 1, 8), 9, 30), occurrences[0].startSec)
        assertEquals(sec(LocalDate.of(2026, 1, 8), 10, 30), occurrences[0].endSec)
        assertEquals(sec(LocalDate.of(2026, 1, 9), 9, 30), occurrences[1].startSec)
        assertEquals(sec(LocalDate.of(2026, 1, 9), 10, 30), occurrences[1].endSec)
        assertEquals(null, occurrences[0].startDateIso)
    }

    @Test
    fun `occurrences are capped at the safety limit`() {
        val ev = event(startSec = sec(LocalDate.of(2026, 1, 1)), recurrence = Recurrence(freq = RecurFreq.DAILY))

        val occurrences = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 1)), sec(LocalDate.of(2031, 1, 1)))
        assertEquals(RecurringEventExpander.MAX_OCCURRENCES_PER_EVENT, occurrences.size)
    }

    // ------------------------------------------------------------------
    // expandAll + degenerate cases
    // ------------------------------------------------------------------

    @Test
    fun `expandAll sorts interleaved occurrences and skips one-offs`() {
        val daily = event(
            id = "daily",
            startSec = sec(LocalDate.of(2026, 1, 1)), endSec = sec(LocalDate.of(2026, 1, 1)) + 3600,
            recurrence = Recurrence(freq = RecurFreq.DAILY)
        )
        val weekly = event(
            id = "weekly",
            startSec = sec(LocalDate.of(2026, 1, 5)), endSec = sec(LocalDate.of(2026, 1, 5)) + 3600, // Monday
            recurrence = Recurrence(freq = RecurFreq.WEEKLY)
        )
        val oneOff = event(startSec = sec(LocalDate.of(2026, 1, 6)), recurrence = null)

        val result = RecurringEventExpander.expandAll(
            listOf(oneOff, weekly, daily),
            sec(LocalDate.of(2026, 1, 4)), sec(LocalDate.of(2026, 1, 8))
        )

        // Jan 4 (daily), Jan 5 (daily + weekly), Jan 6 (daily), Jan 7 (daily).
        assertEquals(listOf(4, 5, 5, 6, 7), result.map { LocalDate.ofEpochDay(it.startSec / 86400).dayOfMonth })
        assertTrue(result.all { it.recurrence != null })
    }

    @Test
    fun `null recurrence or inverted range yields nothing`() {
        val oneOff = event(startSec = sec(LocalDate.of(2026, 1, 1)), recurrence = null)
        assertTrue(RecurringEventExpander.expand(oneOff, 0, 100).isEmpty())

        val rule = event(startSec = sec(LocalDate.of(2026, 1, 1)), recurrence = Recurrence(freq = RecurFreq.DAILY))
        assertTrue(RecurringEventExpander.expand(rule, 100, 0).isEmpty())
    }

    @Test
    fun `zero-length occurrence on range start boundary is excluded, inside is included`() {
        // Instantaneous event (no end): DAO semantics require startSec strictly inside.
        val ev = event(
            startSec = sec(LocalDate.of(2026, 1, 3), 12),
            endSec = null,
            recurrence = Recurrence(freq = RecurFreq.DAILY)
        )

        val inside = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 3)), sec(LocalDate.of(2026, 1, 4)))
        assertEquals(1, inside.size)

        // Range starting exactly at the occurrence excludes it.
        val atBoundary = RecurringEventExpander.expand(ev, sec(LocalDate.of(2026, 1, 3), 12), sec(LocalDate.of(2026, 1, 4)))
        assertTrue(atBoundary.isEmpty())

        assertNull(inside.single().endSec)
    }
}
