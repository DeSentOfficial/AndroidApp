package xyz.desent.data.mapper

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.CalendarEventPayload
import xyz.desent.domain.model.CalendarInstant
import xyz.desent.domain.model.Recurrence
import xyz.desent.domain.model.RecurFreq

class CalendarMapperTest {

    private val mapper = CalendarMapper()

    @Test
    fun timeBasedEvent_roundTripsThroughEntity() {
        val payload = CalendarEventPayload(
            title = "Dentist appointment",
            start = CalendarInstant.Timestamp(1738300000L),
            end = CalendarInstant.Timestamp(1738303000L),
            startTzid = "America/Los_Angeles",
            location = "120 Market St",
            links = listOf("https://meet.example.com/abc"),
            hashtags = listOf("health"),
            updatedAt = 1738200000L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev1", ownerNpub = "npub1abc",
            kind = 31923, dTag = "desent:event:ev1", createdAt = 100L
        )
        val entity = mapper.domainToEntity(domain)
        val restored = mapper.entityToDomain(entity)
        assertEquals(domain, restored)
    }

    @Test
    fun geohash_roundTripsThroughEntityAndWireJson() {
        val payload = CalendarEventPayload(
            title = "Dentist appointment",
            start = CalendarInstant.Timestamp(1738300000L),
            end = CalendarInstant.Timestamp(1738303000L),
            location = "120 Market St, San Francisco",
            geohash = "9q8yyk8",
            updatedAt = 1738200000L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev1g", ownerNpub = "npub1abc",
            kind = 31923, dTag = "desent:event:ev1g", createdAt = 100L
        )
        assertEquals("9q8yyk8", domain.geohash)

        // The encrypted-payload JSON (what NIP-44 wraps) carries the geohash…
        val json = mapper.payloadToJson(mapper.domainToPayload(domain))
        assertTrue(json.contains("\"geohash\":\"9q8yyk8\""))
        // …and it survives the local Room cache round-trip.
        val restored = mapper.entityToDomain(mapper.domainToEntity(domain))
        assertEquals("9q8yyk8", restored.geohash)
        assertEquals(domain, restored)
    }

    @Test
    fun dateBasedEvent_roundTripsThroughEntity() {
        val payload = CalendarEventPayload(
            title = "Conference",
            start = CalendarInstant.Date("2024-06-10"),
            end = CalendarInstant.Date("2024-06-13"),
            updatedAt = 1738200000L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev2", ownerNpub = "npub1abc",
            kind = 31922, dTag = "desent:event:ev2", createdAt = 200L
        )
        // Date-based: allDay, UTC-midnight index of the floating start date.
        assertTrue(domain.allDay)
        assertEquals("2024-06-10", domain.startDateIso)
        assertEquals("2024-06-13", domain.endDateIso)
        // The wire end ISO is EXCLUSIVE (CALENDAR_PROTOCOL.md §Date-based
        // event): endSec is midnight UTC of the end date itself, so the
        // exclusive-day span covers exactly Jun 10–12.
        assertEquals(
            java.time.LocalDate.parse("2024-06-10")
                .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond(),
            domain.startSec
        )
        assertEquals(
            java.time.LocalDate.parse("2024-06-13")
                .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond(),
            domain.endSec
        )

        val entity = mapper.domainToEntity(domain)
        val restored = mapper.entityToDomain(entity)
        assertEquals(domain, restored)
    }

    @Test
    fun dateBasedEvent_daySpanFollowsFloatingIsoDatesInAnyZone() {
        // The day span must come from the floating ISO dates (epoch-day
        // arithmetic on the UTC index), never a zone conversion — otherwise
        // the event paints a day early west of UTC (the pinned bug).
        val payload = CalendarEventPayload(
            title = "Conference",
            start = CalendarInstant.Date("2024-06-10"),
            end = CalendarInstant.Date("2024-06-13"),
            updatedAt = 1738200000L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev2b", ownerNpub = "npub1abc",
            kind = 31922, dTag = "desent:event:ev2b", createdAt = 200L
        )
        for (zone in listOf("UTC", "America/New_York", "Europe/Berlin", "Pacific/Auckland")) {
            val (startDay, endDay) = domain.daySpanIn(java.time.ZoneId.of(zone))
            assertEquals(java.time.LocalDate.parse("2024-06-10").toEpochDay(), startDay)
            assertEquals(java.time.LocalDate.parse("2024-06-13").toEpochDay(), endDay)
        }
    }

    @Test
    fun dateBasedEvent_malformedEndAtOrBeforeStartCoercesToOneDay() {
        val payload = CalendarEventPayload(
            title = "Odd",
            start = CalendarInstant.Date("2024-06-10"),
            end = CalendarInstant.Date("2024-06-10"), // exclusive end == start covers nothing
            updatedAt = 1738200000L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev2c", ownerNpub = "npub1abc",
            kind = 31922, dTag = "desent:event:ev2c", createdAt = 200L
        )
        assertEquals(domain.startSec + 86_400L, domain.endSec)
    }

    @Test
    fun timeBasedPayload_serializesStartAsNumber() {
        val payload = CalendarEventPayload(
            title = "t",
            start = CalendarInstant.Timestamp(1738300000L),
            end = CalendarInstant.Timestamp(1738303000L),
            updatedAt = 5L
        )
        val json = mapper.payloadToJson(payload)
        assertTrue("start must be a bare number for time-based", json.contains("\"start\":1738300000"))
        assertTrue("end must be a bare number for time-based", json.contains("\"end\":1738303000"))
        assertTrue("snake_case updated_at", json.contains("\"updated_at\":5"))
    }

    @Test
    fun dateBasedPayload_serializesStartAsIsoString() {
        val payload = CalendarEventPayload(
            title = "t",
            start = CalendarInstant.Date("2024-06-10"),
            end = CalendarInstant.Date("2024-06-13"),
            startTzid = null,
            updatedAt = 5L
        )
        val json = mapper.payloadToJson(payload)
        assertTrue("start must be an ISO date string for date-based", json.contains("\"start\":\"2024-06-10\""))
        assertTrue("end must be an ISO date string for date-based", json.contains("\"end\":\"2024-06-13\""))
    }

    @Test
    fun timeBasedInstantaneousEvent_omitsEndOnSerialize() {
        // Domain with endSec == null → payload.end == null → wire omits an end value.
        val payload = CalendarEventPayload(
            title = "ping",
            start = CalendarInstant.Timestamp(1738300000L),
            end = null,
            updatedAt = 5L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev3", ownerNpub = "npub1",
            kind = 31923, dTag = "desent:event:ev3", createdAt = 1L
        )
        assertNull(domain.endSec)
        val json = mapper.payloadToJson(mapper.domainToPayload(domain))
        // No "end" key on the wire for an instantaneous time-based event.
        assertTrue(json.contains("\"start\":1738300000"))
        assertTrue("wire must not carry an end field", !json.contains("\"end\""))
    }

    @Test
    fun singleDayEvent_endSecSpansWholeDay() {
        // A date-based event with only `start` covers the whole day:
        // endSec (exclusive) = next-day midnight, so range queries match all day.
        val payload = CalendarEventPayload(
            title = "birthday",
            start = CalendarInstant.Date("2024-01-15"),
            end = null,
            updatedAt = 5L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev4", ownerNpub = "npub1",
            kind = 31922, dTag = "desent:event:ev4", createdAt = 1L
        )
        assertEquals(86400L, (domain.endSec!! - domain.startSec))
    }

    @Test
    fun decodeToleratesStartAsStringOfSeconds() {
        // Some clients encode a time-based start as a quoted number string.
        val legacy = """{"title":"t","start":"1738300000","updated_at":5}"""
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString(CalendarEventPayload.serializer(), legacy)
        assertEquals(CalendarInstant.Timestamp(1738300000L), payload.start)
    }

    // ------------------------------------------------------------------
    // Recurrence
    // ------------------------------------------------------------------

    @Test
    fun recurrence_roundTripsThroughEntityAndDenormalizesFreq() {
        val payload = CalendarEventPayload(
            title = "Birthday",
            start = CalendarInstant.Date("1990-03-15"),
            end = null,
            recur = Recurrence(
                freq = RecurFreq.YEARLY,
                interval = 1,
                omitYear = true
            ),
            updatedAt = 5L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev5", ownerNpub = "npub1",
            kind = 31922, dTag = "desent:event:ev5", createdAt = 1L
        )
        assertEquals(RecurFreq.YEARLY, domain.recurrence?.freq)
        assertTrue(domain.recurrence?.omitYear == true)

        val entity = mapper.domainToEntity(domain)
        assertEquals("YEARLY", entity.recurFreq)
        val restored = mapper.entityToDomain(entity)
        assertEquals(domain, restored)
    }

    @Test
    fun boundedRecurrence_roundTripsWithUntilAndByDay() {
        val payload = CalendarEventPayload(
            title = "Class",
            start = CalendarInstant.Timestamp(1_768_000_000L),
            end = CalendarInstant.Timestamp(1_768_003_600L),
            recur = Recurrence(
                freq = RecurFreq.WEEKLY,
                interval = 2,
                byDay = listOf("TU", "TH"),
                count = 8
            ),
            updatedAt = 5L
        )
        val domain = mapper.payloadToDomain(
            payload, id = "ev6", ownerNpub = "npub1",
            kind = 31923, dTag = "desent:event:ev6", createdAt = 1L
        )
        val restored = mapper.entityToDomain(mapper.domainToEntity(domain))
        assertEquals(domain, restored)
        assertEquals(listOf("TU", "TH"), restored.recurrence?.byDay)
        assertEquals(8, restored.recurrence?.count)
    }

    @Test
    fun recur_serializesWithSnakeCaseWireKeys() {
        val payload = CalendarEventPayload(
            title = "t",
            start = CalendarInstant.Date("1990-03-15"),
            recur = Recurrence(freq = RecurFreq.YEARLY, untilIso = "2030-03-15", omitYear = false),
            updatedAt = 5L
        )
        val json = mapper.payloadToJson(payload)
        assertTrue(json.contains("\"recur\":{\"freq\":\"YEARLY\""))
        assertTrue(json.contains("\"until_iso\":\"2030-03-15\""))
        // omit_year always serialized; absent-bound fields use their defaults.
    }

    @Test
    fun legacyPayloadWithoutRecur_decodesAsOneOff() {
        val legacy = """{"title":"t","start":"2024-01-15","updated_at":5}"""
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString(CalendarEventPayload.serializer(), legacy)
        assertNull(payload.recur)

        val domain = mapper.payloadToDomain(
            payload, id = "ev7", ownerNpub = "npub1",
            kind = 31922, dTag = "desent:event:ev7", createdAt = 1L
        )
        assertNull(domain.recurrence)
        assertNull(mapper.domainToEntity(domain).recurFreq)
    }

    @Test
    fun unknownFreqOnWire_failsDecode_andIsSkippedByTolerantCallSites() {
        // A foreign freq value (e.g. SECONDLY) fails enum decoding; the range
        // queries wrap entityToDomain in runCatching and skip such rows
        // instead of crashing. Document that contract here.
        val json = """{"title":"t","start":"2024-01-15","updated_at":5,
            "recur":{"freq":"SECONDLY","interval":1}}""".trimIndent()
        val result = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(CalendarEventPayload.serializer(), json)
        }
        assertTrue(result.isFailure)
    }
}
