package xyz.desent.presentation.ui.calendar.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.attachment.CalendarAttachmentOpener
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.Recurrence
import xyz.desent.domain.model.RecurFreq
import xyz.desent.domain.repository.LocationResolver
import xyz.desent.domain.repository.ResolvedLocation
import xyz.desent.domain.usecase.CalendarUseCase
import xyz.desent.domain.util.GeohashUtils
import xyz.desent.domain.util.GeohashUtils.GeoPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarEventEditorViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var useCase: CalendarUseCase
    private lateinit var opener: CalendarAttachmentOpener
    private lateinit var resolver: LocationResolver

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        useCase = mockk(relaxed = true)
        opener = mockk(relaxed = true)
        resolver = mockk()
        coEvery { useCase.activeOwnerNpub() } returns "npub1owner"
        coEvery { useCase.observeCalendars(any()) } returns flowOf(emptyList())
        coEvery { useCase.getEvent(any(), any()) } returns null
        coEvery { useCase.saveEvent(any()) } returns Result.success(Unit)
        coEvery { resolver.resolve(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm(
        eventId: String? = null,
        /** Editor zone under test; production injects the device zone. */
        zone: ZoneId = ZoneOffset.UTC,
        locationResolver: LocationResolver = resolver,
        prefill: CalendarEventPrefill? = null
    ): CalendarEventEditorViewModel =
        CalendarEventEditorViewModel(useCase, opener, locationResolver, eventId, prefill, zone)

    /** Local-midnight epoch millis of [date] in [zone] — what the pickers emit. */
    private fun localMs(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun utcMs(date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + hour * 3_600_000L + minute * 60_000L

    private fun defaultEvent(id: String? = null): NostrCalendarEvent = NostrCalendarEvent(
        id = id ?: "ev1", ownerNpub = "npub1owner",
        kind = NostrCalendarEvent.KIND_DATE, dTag = "desent:event:${id ?: "ev1"}",
        title = "T", startSec = utcMs(LocalDate.of(2026, 1, 7)) / 1000,
        endSec = utcMs(LocalDate.of(2026, 1, 8)) / 1000, allDay = true,
        startDateIso = "2026-01-07", endDateIso = null,
        startTzid = null, endTzid = null, summary = null, description = null,
        location = null, geohash = null, image = null,
        participants = emptyList(), links = emptyList(), hashtags = emptyList(),
        calendarD = null, attachments = emptyList(), shares = emptyList(),
        recurrence = null, updatedAt = 0, createdAt = 0
    )

    @Test
    fun `new event saves as one-off by default`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateTitle("Standing meeting")
        vm.updateStart(utcMs(LocalDate.of(2026, 3, 2), 9))
        vm.updateEnd(utcMs(LocalDate.of(2026, 3, 2), 10))
        vm.save()

        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertNull(saved.captured.recurrence)
        assertFalse(vm.uiState.value.hidesAnchorYear)
    }

    @Test
    fun `yearly never-ending rule sets omitYear and hides year`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateTitle("Birthday")
        vm.updateAllDay(true)
        vm.updateStart(utcMs(LocalDate.of(1990, 3, 15)))
        vm.updateEnd(null)
        vm.updateRecurFreq(RecurFreq.YEARLY)

        assertTrue(vm.uiState.value.hidesAnchorYear)

        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        val rule = saved.captured.recurrence
        assertNotNull(rule)
        assertEquals(RecurFreq.YEARLY, rule!!.freq)
        assertTrue(rule.omitYear)
        assertNull(rule.count)
        assertNull(rule.untilIso)
    }

    @Test
    fun `weekly rule with selected days builds byDay codes`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateTitle("Gym")
        vm.updateRecurFreq(RecurFreq.WEEKLY)
        vm.toggleRecurWeeklyDay(DayOfWeek.MONDAY)
        vm.toggleRecurWeeklyDay(DayOfWeek.WEDNESDAY)
        vm.toggleRecurWeeklyDay(DayOfWeek.FRIDAY)
        vm.setRecurEndType(RecurEndType.COUNT)
        vm.updateRecurCount(-4)
        vm.updateRecurCount(1)
        vm.save()

        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        val rule = saved.captured.recurrence!!
        assertEquals(listOf("MO", "WE", "FR"), rule.byDay)
        assertEquals(5, rule.count)
        assertFalse(rule.omitYear)
    }

    @Test
    fun `monthly nth-weekday rule builds ordinal byDay code`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateTitle("Dinner club")
        vm.updateRecurFreq(RecurFreq.MONTHLY)
        vm.setUseNthWeekday(true)
        vm.setNthWeekday(DayOfWeek.TUESDAY)
        vm.setNthOrdinal(2)

        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals(listOf("2TU"), saved.captured.recurrence!!.byDay)
    }

    @Test
    fun `until end type serializes the picked local date iso`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateTitle("Renewal")
        vm.updateStart(utcMs(LocalDate.of(2026, 6, 1)))
        vm.updateEnd(utcMs(LocalDate.of(2026, 6, 1), 1))
        vm.updateRecurFreq(RecurFreq.DAILY)
        vm.setRecurEndType(RecurEndType.UNTIL)
        vm.setRecurUntil(utcMs(LocalDate.of(2026, 6, 30)))

        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals("2026-06-30", saved.captured.recurrence!!.untilIso)
        assertNull(saved.captured.recurrence!!.count)
    }

    @Test
    fun `until iso keeps the picked day east of UTC`() = runTest(mainDispatcher) {
        // Berlin local midnight of Jun 30 is Jun 29 22:00Z — a UTC
        // conversion wrote "2026-06-29" on the wire.
        val berlin = ZoneId.of("Europe/Berlin")
        val vm = makeVm(zone = berlin)
        vm.updateTitle("Renewal")
        vm.updateStart(localMs(LocalDate.of(2026, 6, 1), berlin))
        vm.updateEnd(localMs(LocalDate.of(2026, 6, 1), berlin) + 3_600_000L)
        vm.updateRecurFreq(RecurFreq.DAILY)
        vm.setRecurEndType(RecurEndType.UNTIL)
        vm.setRecurUntil(localMs(LocalDate.of(2026, 6, 30), berlin))

        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals("2026-06-30", saved.captured.recurrence!!.untilIso)
    }

    @Test
    fun `until before start is rejected`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateTitle("X")
        vm.updateStart(utcMs(LocalDate.of(2026, 3, 2)))
        vm.updateRecurFreq(RecurFreq.DAILY)
        vm.setRecurEndType(RecurEndType.UNTIL)
        vm.setRecurUntil(utcMs(LocalDate.of(2026, 3, 1)))

        vm.save()

        assertEquals("Recurrence must end on or after the start date", vm.uiState.value.toast)
        coVerify(exactly = 0) { useCase.saveEvent(any()) }
    }

    @Test
    fun `editing a recurring event hydrates the recurrence state`() = runTest(mainDispatcher) {
        val stored = defaultEvent("ev9").copy(
            recurrence = Recurrence(
                freq = RecurFreq.WEEKLY,
                interval = 2,
                byDay = listOf("TU", "TH"),
                count = 8
            )
        )
        coEvery { useCase.getEvent("npub1owner", "ev9") } returns stored

        val vm = makeVm(eventId = "ev9")
        val state = vm.uiState.value
        assertEquals(RecurFreq.WEEKLY, state.recurFreq)
        assertEquals(2, state.recurInterval)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), state.recurWeeklyDays)
        assertEquals(RecurEndType.COUNT, state.recurEndType)
        assertEquals(8, state.recurCount)
    }

    @Test
    fun `yearly omitYear rule round-trips through the editor`() = runTest(mainDispatcher) {
        val stored = defaultEvent("ev10").copy(
            recurrence = Recurrence(freq = RecurFreq.YEARLY, omitYear = true)
        )
        coEvery { useCase.getEvent("npub1owner", "ev10") } returns stored

        val vm = makeVm(eventId = "ev10")
        assertTrue(vm.uiState.value.hidesAnchorYear)

        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals(Recurrence(freq = RecurFreq.YEARLY, omitYear = true), saved.captured.recurrence)
    }

    @Test
    fun `monthly ordinal rule hydrates nth weekday state`() = runTest(mainDispatcher) {
        val stored = defaultEvent("ev11").copy(
            recurrence = Recurrence(freq = RecurFreq.MONTHLY, byDay = listOf("-1FR"))
        )
        coEvery { useCase.getEvent("npub1owner", "ev11") } returns stored

        val vm = makeVm(eventId = "ev11")
        val state = vm.uiState.value
        assertEquals(RecurFreq.MONTHLY, state.recurFreq)
        assertTrue(state.useNthWeekday)
        assertEquals(DayOfWeek.FRIDAY, state.nthWeekday)
        assertEquals(-1, state.nthOrdinal)
    }

    @Test
    fun `default until is seeded one year past start`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateStart(utcMs(LocalDate.of(2026, 2, 1)))
        vm.updateRecurFreq(RecurFreq.MONTHLY)
        vm.setRecurEndType(RecurEndType.UNTIL)

        val until = vm.uiState.value.recurUntilMs
        assertNotNull(until)
        assertEquals(
            LocalDate.of(2027, 2, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            until
        )
    }

    // ------------------------------------------------------------------
    // All-day (31922) floating dates — the pinned timezone fix
    // ------------------------------------------------------------------

    @Test
    fun `all-day pick east of UTC publishes the picked ISO date`() = runTest(mainDispatcher) {
        // Berlin local midnight of Aug 29 is Aug 28 22:00Z — the old UTC
        // floor-division stored and published Aug 28 on the wire.
        val berlin = ZoneId.of("Europe/Berlin")
        val vm = makeVm(zone = berlin)
        vm.updateTitle("Launch")
        vm.updateAllDay(true)
        vm.updateStart(localMs(LocalDate.of(2026, 8, 29), berlin))
        vm.updateEnd(null)
        vm.save()

        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals("2026-08-29", saved.captured.startDateIso)
        assertNull(saved.captured.endDateIso) // single day → wire omits the end
        assertEquals(utcMs(LocalDate.of(2026, 8, 29)) / 1000, saved.captured.startSec)
        assertEquals(utcMs(LocalDate.of(2026, 8, 30)) / 1000, saved.captured.endSec)
    }

    @Test
    fun `multi-day all-day pick publishes an exclusive wire end`() = runTest(mainDispatcher) {
        val berlin = ZoneId.of("Europe/Berlin")
        val vm = makeVm(zone = berlin)
        vm.updateTitle("Trip")
        vm.updateAllDay(true)
        vm.updateStart(localMs(LocalDate.of(2026, 8, 29), berlin))
        vm.updateEnd(localMs(LocalDate.of(2026, 8, 31), berlin)) // last covered day
        vm.save()

        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals("2026-08-29", saved.captured.startDateIso)
        assertEquals("2026-09-01", saved.captured.endDateIso) // exclusive (protocol)
        assertEquals(utcMs(LocalDate.of(2026, 9, 1)) / 1000, saved.captured.endSec)
    }

    @Test
    fun `editing all-day event west of UTC shows the ISO day and round-trips it`() = runTest(mainDispatcher) {
        val ny = ZoneId.of("America/New_York")
        coEvery { useCase.getEvent("npub1owner", "evNy") } returns defaultEvent("evNy").copy(
            startDateIso = "2026-08-29",
            startSec = utcMs(LocalDate.of(2026, 8, 29)) / 1000,
            endSec = utcMs(LocalDate.of(2026, 8, 30)) / 1000
        )

        val vm = makeVm(eventId = "evNy", zone = ny)
        // UTC midnight of Aug 29 is Aug 28 20:00 in New York — the editor
        // must hydrate local midnight of the floating date, not the instant.
        assertEquals(localMs(LocalDate.of(2026, 8, 29), ny), vm.uiState.value.startMs)
        assertNull(vm.uiState.value.endMs) // single-day → no End pick

        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals("2026-08-29", saved.captured.startDateIso)
        assertNull(saved.captured.endDateIso)
        assertEquals(utcMs(LocalDate.of(2026, 8, 29)) / 1000, saved.captured.startSec)
    }

    @Test
    fun `editing multi-day all-day event hydrates the last covered day and round-trips`() = runTest(mainDispatcher) {
        val ny = ZoneId.of("America/New_York")
        coEvery { useCase.getEvent("npub1owner", "evTrip") } returns defaultEvent("evTrip").copy(
            startDateIso = "2026-08-28",
            endDateIso = "2026-08-31", // wire end (exclusive)
            startSec = utcMs(LocalDate.of(2026, 8, 28)) / 1000,
            endSec = utcMs(LocalDate.of(2026, 8, 31)) / 1000
        )

        val vm = makeVm(eventId = "evTrip", zone = ny)
        assertEquals(localMs(LocalDate.of(2026, 8, 28), ny), vm.uiState.value.startMs)
        assertEquals(localMs(LocalDate.of(2026, 8, 30), ny), vm.uiState.value.endMs) // last covered day

        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals("2026-08-28", saved.captured.startDateIso)
        assertEquals("2026-08-31", saved.captured.endDateIso)
    }

    // ------------------------------------------------------------------
    // Location resolution (geohash)
    // ------------------------------------------------------------------

    @Test
    fun `editing an event hydrates its geohash`() = runTest(mainDispatcher) {
        coEvery { useCase.getEvent("npub1owner", "evGeo") } returns defaultEvent("evGeo").copy(
            location = "120 Market St, San Francisco",
            geohash = "9q8yyk8"
        )

        val vm = makeVm(eventId = "evGeo")
        assertEquals("120 Market St, San Francisco", vm.uiState.value.location)
        assertEquals("9q8yyk8", vm.uiState.value.geohash)
    }

    @Test
    fun `hydrated geohash survives an untouched save`() = runTest(mainDispatcher) {
        coEvery { useCase.getEvent("npub1owner", "evGeo") } returns defaultEvent("evGeo").copy(
            location = "120 Market St, San Francisco",
            geohash = "9q8yyk8"
        )

        val vm = makeVm(eventId = "evGeo")
        vm.save()
        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals("9q8yyk8", saved.captured.geohash)
    }

    @Test
    fun `editing the location text clears the stale geohash`() = runTest(mainDispatcher) {
        coEvery { useCase.getEvent("npub1owner", "evGeo") } returns defaultEvent("evGeo").copy(
            location = "120 Market St, San Francisco",
            geohash = "9q8yyk8"
        )

        val vm = makeVm(eventId = "evGeo")
        vm.updateLocation("Somewhere else")
        assertNull(vm.uiState.value.geohash)
    }

    @Test
    fun `resolver candidates populate and selection overwrites text with the geohash`() =
        runTest(mainDispatcher) {
            coEvery { resolver.resolve("Market St") } returns Result.success(
                listOf(
                    ResolvedLocation("120 Market St, San Francisco", GeoPoint(37.7749, -122.4194)),
                    ResolvedLocation("Market St, Seattle", GeoPoint(47.6205, -122.3493))
                )
            )
            val vm = makeVm()
            vm.updateLocation("Market St")
            vm.resolveLocation()

            assertEquals(2, vm.uiState.value.resolveCandidates.size)
            assertFalse(vm.uiState.value.isResolving)
            assertNull(vm.uiState.value.toast)

            vm.selectCandidate(vm.uiState.value.resolveCandidates.first())
            assertEquals("120 Market St, San Francisco", vm.uiState.value.location)
            val geohash = vm.uiState.value.geohash
            assertNotNull(geohash)
            assertEquals(7, geohash!!.length)
            val center = GeohashUtils.decode(geohash)!!
            assertEquals(37.7749, center.latitude, 0.002)
            assertEquals(-122.4194, center.longitude, 0.002)
            assertTrue(vm.uiState.value.resolveCandidates.isEmpty())

            vm.clearResolvedLocation()
            assertNull(vm.uiState.value.geohash)
            assertEquals("120 Market St, San Francisco", vm.uiState.value.location)
        }

    @Test
    fun `selecting a candidate persists the geohash on save`() = runTest(mainDispatcher) {
        coEvery { resolver.resolve(any()) } returns Result.success(
            listOf(ResolvedLocation("120 Market St, San Francisco", GeoPoint(37.7749, -122.4194)))
        )
        val vm = makeVm()
        vm.updateTitle("Dentist")
        vm.updateLocation("120 Market St")
        vm.resolveLocation()
        vm.selectCandidate(vm.uiState.value.resolveCandidates.first())
        vm.save()

        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals(vm.uiState.value.geohash, saved.captured.geohash)
        assertEquals("120 Market St, San Francisco", saved.captured.location)
    }

    @Test
    fun `pasted coordinates resolve offline without the geocoder`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateLocation("57.64911, 10.40744")
        vm.resolveLocation()

        coVerify(exactly = 0) { resolver.resolve(any()) }
        val candidates = vm.uiState.value.resolveCandidates
        assertEquals(1, candidates.size)

        vm.selectCandidate(candidates.first())
        assertEquals(GeohashUtils.encode(57.64911, 10.40744), vm.uiState.value.geohash)
    }

    @Test
    fun `blank location resolve asks for input`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.resolveLocation()
        assertEquals("Enter a location to resolve", vm.uiState.value.toast)
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `no-match and failed lookups surface feedback`() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.updateLocation("Nowhere Street 999")
        vm.resolveLocation()
        assertTrue(vm.uiState.value.toast!!.startsWith("No matches"))

        coEvery { resolver.resolve(any()) } returns Result.failure(IllegalStateException("offline"))
        vm.updateLocation("Market St") // clears candidates + toast stays until replaced
        vm.resolveLocation()
        assertEquals("Location lookup failed: offline", vm.uiState.value.toast)
        assertFalse(vm.uiState.value.isResolving)
        assertTrue(vm.uiState.value.resolveCandidates.isEmpty())
    }

    // ---------------- Agent-proposal prefill (ANDROID_AI_AGENTS.md §6) ----------------

    @Test
    fun `agent prefill with start and end opens time-based editor`() = runTest(mainDispatcher) {
        val startSec = utcMs(LocalDate.of(2026, 10, 1), 14) / 1000
        val endSec = utcMs(LocalDate.of(2026, 10, 1), 15) / 1000
        val vm = makeVm(
            prefill = CalendarEventPrefill(
                title = "Call with Alice",
                epochDay = LocalDate.of(2026, 10, 1).toEpochDay(),
                description = "Intro call",
                startSec = startSec,
                endSec = endSec,
                location = "Jitsi"
            )
        )
        vm.save()

        val state = vm.uiState.value
        assertEquals("Call with Alice", state.title)
        assertFalse(state.allDay)
        assertEquals(startSec * 1000L, state.startMs)
        assertEquals(endSec * 1000L, state.endMs)
        assertEquals("Jitsi", state.location)

        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals(NostrCalendarEvent.KIND_TIME, saved.captured.kind)
    }

    @Test
    fun `agent prefill without end stays all-day`() = runTest(mainDispatcher) {
        val day = LocalDate.of(2026, 10, 1)
        val vm = makeVm(
            prefill = CalendarEventPrefill(
                title = "Reminder",
                epochDay = day.toEpochDay(),
                description = "",
                startSec = 0L,
                endSec = 0L,
                location = ""
            )
        )
        vm.save()

        val state = vm.uiState.value
        assertTrue(state.allDay)
        assertEquals(localMs(day, ZoneOffset.UTC), state.startMs)
        assertNull(state.endMs)

        val saved = slot<NostrCalendarEvent>()
        coVerify { useCase.saveEvent(capture(saved)) }
        assertEquals(NostrCalendarEvent.KIND_DATE, saved.captured.kind)
    }
}
