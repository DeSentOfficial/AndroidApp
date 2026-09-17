package xyz.desent.data.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCalendarCodecTest {

    private fun sampleCalendar() = WearCalendar(
        events = listOf(
            WearCalendarEvent(
                id = "ev1",
                title = "Standup",
                startSec = 3600L,
                endSec = 5400L,
                location = "Room 2",
                description = "Weekly sync",
                updatedAt = 10L
            ),
            WearCalendarEvent(
                id = "ev2",
                title = "Conference",
                allDay = true,
                startDateIso = "2026-09-01",
                endDateIso = "2026-09-03"
            )
        ),
        anniversaries = listOf(
            WearAnniversary(key = "alice|Birthday", name = "Alice", label = "Birthday", epochDay = 20650L)
        ),
        windowStartSec = 0L,
        windowEndSec = 1_296_000L,
        syncedAt = 42L
    )

    @Test
    fun roundTripPreservesFields() {
        val calendar = sampleCalendar()
        assertEquals(calendar, WearCalendarCodec.decode(WearCalendarCodec.encode(calendar)))
    }

    @Test
    fun payloadIsGzipped() {
        val bytes = WearCalendarCodec.encode(sampleCalendar())
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }

    @Test
    fun rawJsonFallbackDecodes() {
        val decoded = WearCalendarCodec.decode("""{"syncedAt":9}""".encodeToByteArray())
        assertNotNull(decoded)
        assertEquals(9L, decoded?.syncedAt)
        assertEquals(emptyList<WearCalendarEvent>(), decoded?.events)
    }

    @Test
    fun unknownFieldsIgnored() {
        val decoded = WearCalendarCodec.decode(
            """{"events":[],"futureField":1,"syncedAt":2}""".encodeToByteArray()
        )
        assertNotNull(decoded)
        assertEquals(2L, decoded?.syncedAt)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(WearCalendarCodec.decode(byteArrayOf(0x00, 0x01)))
        assertNull(WearCalendarCodec.decode(ByteArray(0)))
    }

    @Test
    fun emptyCalendarRoundTrips() {
        assertEquals(WearCalendar(), WearCalendarCodec.decode(WearCalendarCodec.encode(WearCalendar())))
    }

    @Test
    fun fourteenDayWindowStaysTiny() {
        val dense = WearCalendar(
            events = (1..100).map {
                WearCalendarEvent(
                    id = "e$it",
                    title = "Event number $it with a longer title",
                    startSec = it * 3600L,
                    endSec = it * 3600L + 1800L,
                    location = "Meeting room $it, Building B",
                    description = "Description paragraph for event $it — plain prose.",
                    updatedAt = it.toLong()
                )
            },
            anniversaries = (1..20).map {
                WearAnniversary(key = "c$it|Anniv", name = "Contact $it", label = "Anniversary", epochDay = 20600L + it)
            }
        )
        val encoded = WearCalendarCodec.encode(dense)
        assertTrue("dense 14d window must stay tiny, was ${encoded.size}", encoded.size < 20_000)
    }
}
