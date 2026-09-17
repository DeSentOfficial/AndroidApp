package xyz.desent.domain.usecase

import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.Recurrence
import xyz.desent.domain.model.RecurFreq
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Expands recurring calendar events into virtual occurrences at read time
 * (refs/CALENDAR_PROTOCOL.md defers recurrence to client-side expansion).
 *
 * A recurring series is one stored kind-31922/31923 event; this object derives
 * the occurrences that overlap a query range as read-only copies sharing the
 * event's [NostrCalendarEvent.id], shifted to each occurrence's start/end.
 * Nothing is ever materialized on the wire or in the Room cache.
 *
 * Supported rule subset (RFC 5545 semantics, pure calendar-day arithmetic on
 * the UTC-midnight index — for all-day events the index equals the floating
 * ISO dates' epoch days, so occurrences land on the same day everywhere):
 *  - DAILY / WEEKLY / MONTHLY / YEARLY with INTERVAL
 *  - BYDAY: plain codes for WEEKLY (and "every such weekday" for
 *    MONTHLY/YEARLY), ordinal codes (`1MO`, `-1FR`) for MONTHLY/YEARLY
 *  - UNTIL (inclusive ISO date) or COUNT (from the anchor, inclusive)
 *  - Feb 29 YEARLY anchors clamp to Feb 28 in common years (product choice;
 *    RFC 5545 would skip). MONTHLY anchors on days a month lacks are skipped.
 *
 * Iteration is bounded by [MAX_OCCURRENCES_PER_EVENT] per event per query.
 */
object RecurringEventExpander {

    /** Safety cap so a pathological rule can never spin a query. */
    const val MAX_OCCURRENCES_PER_EVENT = 400

    private const val SECONDS_PER_DAY = 86_400L

    /**
     * Expand every recurring event in [events] overlapping
     * `[rangeStartSec, rangeEndSec)`, sorted by start. Non-recurring entries
     * are ignored (callers serve those from the SQL range query).
     */
    fun expandAll(
        events: List<NostrCalendarEvent>,
        rangeStartSec: Long,
        rangeEndSec: Long
    ): List<NostrCalendarEvent> = events
        .filter { it.recurrence != null }
        .flatMap { expand(it, rangeStartSec, rangeEndSec) }
        .sortedBy { it.startSec }

    /** Occurrences of one recurring event overlapping the range, chronological. */
    fun expand(event: NostrCalendarEvent, rangeStartSec: Long, rangeEndSec: Long): List<NostrCalendarEvent> {
        val rule = event.recurrence ?: return emptyList()
        if (rangeStartSec >= rangeEndSec) return emptyList()

        val anchor = LocalDate.ofEpochDay(Math.floorDiv(event.startSec, SECONDS_PER_DAY))
        val timeOfDaySec = Math.floorMod(event.startSec, SECONDS_PER_DAY)
        val durationSec = (event.endSec ?: event.startSec) - event.startSec

        val interval = rule.interval.coerceAtLeast(1)
        val untilDate = rule.untilIso?.let { parseIsoDate(it) }
        val count: Int? = rule.count?.takeIf { it > 0 }?.coerceAtMost(MAX_OCCURRENCES_PER_EVENT)

        // Candidate dates may start before the range when the event is
        // multi-day (an occurrence overlaps while starting earlier). One extra
        // day of slack beyond duration + time-of-day covers the boundary.
        val from: LocalDate
        val toInclusive: LocalDate?
        if (count != null) {
            // COUNT enumerates from the anchor regardless of the query range;
            // iteration is bounded by the count itself.
            from = anchor
            toInclusive = null
        } else {
            val earliestStartSec = rangeStartSec - durationSec - timeOfDaySec
            from = maxOf(anchor, LocalDate.ofEpochDay(Math.floorDiv(earliestStartSec, SECONDS_PER_DAY) - 1))
            val lastStartSec = rangeEndSec - 1 - timeOfDaySec
            val lastDate = LocalDate.ofEpochDay(Math.floorDiv(lastStartSec, SECONDS_PER_DAY))
            toInclusive = minOf(lastDate, untilDate ?: lastDate)
        }

        val candidateDates = candidates(rule, anchor, interval, from, toInclusive, count)
        return candidateDates
            .asSequence()
            .map { date -> occurrence(event, date, timeOfDaySec, durationSec) }
            .filter { occ -> overlaps(occ.startSec, occ.endSec, rangeStartSec, rangeEndSec) }
            .take(MAX_OCCURRENCES_PER_EVENT)
            .toList()
    }

    /** Mirrors CalendarEventDao.eventsInRange's overlap predicate. */
    private fun overlaps(startSec: Long, endSec: Long?, rangeStart: Long, rangeEnd: Long): Boolean =
        startSec < rangeEnd && (endSec ?: startSec) > rangeStart

    /** Build one occurrence copy, shifted by whole days from the anchor. */
    private fun occurrence(
        event: NostrCalendarEvent,
        date: LocalDate,
        timeOfDaySec: Long,
        durationSec: Long
    ): NostrCalendarEvent {
        val startSec = date.toEpochDay() * SECONDS_PER_DAY + timeOfDaySec
        val dayShift = (startSec - event.startSec) / SECONDS_PER_DAY
        return event.copy(
            startSec = startSec,
            endSec = if (event.endSec != null) startSec + durationSec else null,
            startDateIso = event.startDateIso?.let { parseIsoDate(it).plusDays(dayShift).toString() },
            endDateIso = event.endDateIso?.let { parseIsoDate(it).plusDays(dayShift).toString() }
        )
    }

    // ------------------------------------------------------------------
    // Candidate-date generation per frequency (ascending, deduped)
    // ------------------------------------------------------------------

    private fun candidates(
        rule: Recurrence,
        anchor: LocalDate,
        interval: Int,
        from: LocalDate,
        toInclusive: LocalDate?,
        limit: Int?
    ): List<LocalDate> {
        val fromClamped = maxOf(anchor, from)
        val result = mutableListOf<LocalDate>()

        fun done() = (limit != null && result.size >= limit)

        when (rule.freq) {
            RecurFreq.DAILY -> {
                val k0 = firstPeriodIndex(ChronoUnit.DAYS.between(anchor, fromClamped), interval)
                var k = k0
                while (!done()) {
                    val d = anchor.plusDays(k * interval)
                    if (toInclusive != null && d > toInclusive) break
                    if (d >= fromClamped) result.add(d)
                    k++
                }
            }

            RecurFreq.WEEKLY -> {
                // Monday-based weeks, matching RFC 5545's default WKST.
                val anchorWeek = anchor.with(DayOfWeek.MONDAY)
                val fromWeek = fromClamped.with(DayOfWeek.MONDAY)
                val k0 = firstPeriodIndex(ChronoUnit.WEEKS.between(anchorWeek, fromWeek), interval)
                val days = weekDays(rule, anchor)
                var k = k0
                while (!done()) {
                    val weekStart = anchorWeek.plusWeeks(k * interval)
                    if (toInclusive != null && weekStart > toInclusive) break
                    days.forEach { dow ->
                        val d = weekStart.plus((dow.value - 1).toLong(), ChronoUnit.DAYS)
                        if (d >= fromClamped && (toInclusive == null || d <= toInclusive)) result.add(d)
                    }
                    k++
                }
            }

            RecurFreq.MONTHLY -> {
                val anchorMonth = anchor.withDayOfMonth(1)
                val fromMonth = fromClamped.withDayOfMonth(1)
                val k0 = firstPeriodIndex(ChronoUnit.MONTHS.between(anchorMonth, fromMonth), interval)
                var k = k0
                while (!done()) {
                    val month = anchor.withDayOfMonth(1).plusMonths(k * interval)
                    if (toInclusive != null && month > toInclusive.withDayOfMonth(1)) break
                    monthDates(rule, month, anchor.dayOfMonth)
                        .filter { d -> d >= fromClamped && (toInclusive == null || d <= toInclusive) }
                        .forEach { if (!done()) result.add(it) }
                    k++
                }
            }

            RecurFreq.YEARLY -> {
                val k0 = firstPeriodIndex((fromClamped.year - anchor.year).toLong(), interval)
                var k = k0
                while (!done()) {
                    val year = (anchor.year + k * interval).toInt()
                    if (toInclusive != null && year > toInclusive.year) break
                    yearDates(rule, anchor, year)
                        .filter { d -> d >= fromClamped && (toInclusive == null || d <= toInclusive) }
                        .forEach { if (!done()) result.add(it) }
                    k++
                }
            }
        }
        return result
    }

    /** Smallest period index k >= 0 whose period covers [delta] units. */
    private fun firstPeriodIndex(delta: Long, interval: Int): Long =
        Math.floorDiv(delta.coerceAtLeast(0), interval.toLong())

    /** WEEKLY: the weekdays of each period-week — BYDAY codes or the anchor's. */
    private fun weekDays(rule: Recurrence, anchor: LocalDate): List<DayOfWeek> {
        val codes = rule.byDay.mapNotNull { parseByDay(it).second }.distinct()
        return if (codes.isEmpty()) listOf(anchor.dayOfWeek) else codes.sortedBy { it.value }
    }

    /**
     * MONTHLY dates of one period-month: the anchor day-of-month (months that
     * lack the day contribute nothing, per RFC 5545), or the BYDAY matches —
     * ordinal entries select one specific weekday, plain entries every such
     * weekday of the month.
     */
    private fun monthDates(rule: Recurrence, month: LocalDate, anchorDayOfMonth: Int): List<LocalDate> {
        if (rule.byDay.isEmpty()) {
            return if (anchorDayOfMonth <= month.lengthOfMonth()) {
                listOf(month.withDayOfMonth(anchorDayOfMonth))
            } else {
                emptyList()
            }
        }
        return rule.byDay.flatMap { code ->
            val (ordinal, dow) = parseByDay(code)
            when (ordinal) {
                null -> allWeekdaysIn(month, dow ?: return emptyList())
                else -> listOfNotNull(nthWeekdayOfMonth(month, dow ?: return emptyList(), ordinal))
            }
        }.distinct().sorted()
    }

    /**
     * YEARLY dates of one period-year: the anchor month-day — with a Feb 29
     * anchor clamped to Feb 28 in common years — or the BYDAY matches within
     * the anchor month.
     */
    private fun yearDates(rule: Recurrence, anchor: LocalDate, year: Int): List<LocalDate> {
        if (rule.byDay.isEmpty()) {
            val day = if (anchor.monthValue == 2 && anchor.dayOfMonth == 29 && !LocalDate.of(year, 1, 1).isLeapYear) {
                28
            } else {
                anchor.dayOfMonth
            }
            return listOf(LocalDate.of(year, anchor.monthValue, day))
        }
        val month = LocalDate.of(year, anchor.monthValue, 1)
        return rule.byDay.flatMap { code ->
            val (ordinal, dow) = parseByDay(code)
            when (ordinal) {
                null -> allWeekdaysIn(month, dow ?: return emptyList())
                else -> listOfNotNull(nthWeekdayOfMonth(month, dow ?: return emptyList(), ordinal))
            }
        }.distinct().sorted()
    }

    /** Parse a BYDAY entry into (ordinal, weekday); null ordinal = plain code. */
    private fun parseByDay(code: String): Pair<Int?, DayOfWeek?> {
        val dow = code.takeLast(2).let { c ->
            DayOfWeek.values().firstOrNull { it.name.startsWith(c, ignoreCase = true) }
        } ?: return null to null
        return code.dropLast(2).toIntOrNull() to dow
    }

    private fun allWeekdaysIn(month: LocalDate, dow: DayOfWeek): List<LocalDate> {
        val first = month.withDayOfMonth(1)
        val firstMatch = first.plus(((dow.value - first.dayOfWeek.value + 7) % 7).toLong(), ChronoUnit.DAYS)
        return generateSequence(firstMatch) { it.plusWeeks(1) }
            .takeWhile { it.month == month.month }
            .toList()
    }

    private fun nthWeekdayOfMonth(month: LocalDate, dow: DayOfWeek, ordinal: Int): LocalDate? {
        val all = allWeekdaysIn(month, dow)
        return when {
            ordinal > 0 -> all.getOrNull(ordinal - 1)
            ordinal < 0 -> all.getOrNull(all.size + ordinal)
            else -> null
        }
    }

    private fun parseIsoDate(iso: String): LocalDate =
        runCatching { LocalDate.parse(iso) }.getOrElse { LocalDate.parse(iso.substringBefore('T')) }
}
