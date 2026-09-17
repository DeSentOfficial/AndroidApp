package xyz.desent.data.mapper

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.data.local.database.entity.CalendarEntity
import xyz.desent.data.local.database.entity.CalendarEventEntity
import xyz.desent.domain.model.CalendarEventPayload
import xyz.desent.domain.model.CalendarInstant
import xyz.desent.domain.model.CalendarListPayload
import xyz.desent.domain.model.CalendarParticipant
import xyz.desent.domain.model.CalendarShareEntry
import xyz.desent.domain.model.NostrCalendar
import xyz.desent.domain.model.NostrCalendarEvent

/**
 * Maps between the three representations of an encrypted calendar event:
 *  - [CalendarEventPayload]  — the wire JSON inside the NIP-44 ciphertext
 *  - [NostrCalendarEvent]    — the domain model (normalized unix-second instants)
 *  - [CalendarEventEntity]   — the local Room cache row
 *
 * Time vs date semantics (kind 31923 vs 31922) are preserved via
 * [CalendarInstant]: a number on the wire for time-based events, an ISO
 * `YYYY-MM-DD` string for date-based (all-day) events. The domain's
 * [NostrCalendarEvent.startSec]/[endSec] are normalized to unix seconds so the
 * local cache can serve time-range queries (the relay cannot — it sees only
 * ciphertext). For date-based events those seconds are UTC-midnight
 * **indexes** of the floating ISO dates (the wire `end` being exclusive);
 * they are never rendered through a timezone — see
 * [NostrCalendarEvent.daySpanIn].
 */
class CalendarMapper {

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // Wire payload ↔ domain
    // ------------------------------------------------------------------

    fun payloadToDomain(
        payload: CalendarEventPayload,
        id: String,
        ownerNpub: String,
        kind: Int,
        dTag: String,
        createdAt: Long
    ): NostrCalendarEvent {
        val startSec = payload.start.toStartSec()
        val allDay = payload.start is CalendarInstant.Date
        val startDateIso = (payload.start as? CalendarInstant.Date)?.iso

        val endSec: Long? = when {
            // Wire end ISO is exclusive; a missing end covers only the start
            // day. Coerce malformed ends (<= start) to a one-day span.
            allDay -> (payload.end?.toEndSec())
                ?.coerceAtLeast(startSec + NostrCalendarEvent.SECONDS_PER_DAY)
                ?: (startSec + NostrCalendarEvent.SECONDS_PER_DAY)
            payload.end != null -> payload.end.toEndSec()
            else -> null                            // instantaneous time event
        }
        val endDateIso = (payload.end as? CalendarInstant.Date)?.iso

        return NostrCalendarEvent(
            id = id,
            ownerNpub = ownerNpub,
            kind = kind,
            dTag = dTag,
            title = payload.title,
            startSec = startSec,
            endSec = endSec,
            allDay = allDay,
            startDateIso = startDateIso,
            endDateIso = endDateIso,
            startTzid = payload.startTzid,
            endTzid = payload.endTzid,
            summary = payload.summary,
            description = payload.description,
            location = payload.location,
            geohash = payload.geohash,
            image = payload.image,
            participants = payload.participants,
            links = payload.links,
            hashtags = payload.hashtags,
            calendarD = payload.calendarD,
            attachments = payload.attachments,
            shares = payload.shares,
            recurrence = payload.recur,
            updatedAt = payload.updatedAt,
            createdAt = createdAt
        )
    }

    fun domainToPayload(event: NostrCalendarEvent): CalendarEventPayload {
        val start: CalendarInstant = if (event.allDay) {
            CalendarInstant.Date(requireNotNull(event.startDateIso) { "startDateIso required for all-day event" })
        } else {
            CalendarInstant.Timestamp(event.startSec)
        }
        val end: CalendarInstant? = when {
            event.allDay -> event.endDateIso?.let { CalendarInstant.Date(it) }
            event.endSec != null -> CalendarInstant.Timestamp(event.endSec)
            else -> null
        }
        return CalendarEventPayload(
            title = event.title,
            start = start,
            end = end,
            startTzid = event.startTzid,
            endTzid = event.endTzid,
            summary = event.summary,
            description = event.description,
            location = event.location,
            geohash = event.geohash,
            image = event.image,
            participants = event.participants,
            links = event.links,
            hashtags = event.hashtags,
            calendarD = event.calendarD,
            updatedAt = event.updatedAt,
            attachments = event.attachments,
            shares = event.shares,
            recur = event.recurrence
        )
    }

    // ------------------------------------------------------------------
    // Domain ↔ Room entity
    // ------------------------------------------------------------------

    fun domainToEntity(event: NostrCalendarEvent): CalendarEventEntity = CalendarEventEntity(
        id = event.id,
        ownerNpub = event.ownerNpub,
        kind = event.kind,
        dTag = event.dTag,
        title = event.title,
        startSec = event.startSec,
        endSec = event.endSec,
        calendarD = event.calendarD,
        recurFreq = event.recurrence?.freq?.wire,
        payloadJson = json.encodeToString(CalendarEventPayload.serializer(), domainToPayload(event)),
        updatedAt = event.updatedAt,
        createdAt = event.createdAt
    )

    fun entityToDomain(entity: CalendarEventEntity): NostrCalendarEvent {
        val payload = json.decodeFromString(CalendarEventPayload.serializer(), entity.payloadJson)
        return payloadToDomain(
            payload = payload,
            id = entity.id,
            ownerNpub = entity.ownerNpub,
            kind = entity.kind,
            dTag = entity.dTag,
            createdAt = entity.createdAt
        )
    }

    /** Serialize a payload to the ciphertext-ready JSON string. */
    fun payloadToJson(payload: CalendarEventPayload): String =
        json.encodeToString(CalendarEventPayload.serializer(), payload)

    /** Deserialize a decrypted payload JSON string. */
    fun jsonToPayload(plainJson: String): CalendarEventPayload =
        json.decodeFromString(CalendarEventPayload.serializer(), plainJson)

    // ------------------------------------------------------------------
    // Calendar (kind 31924) mappings
    // ------------------------------------------------------------------

    fun calendarPayloadToDomain(
        payload: CalendarListPayload,
        id: String,
        ownerNpub: String,
        dTag: String,
        createdAt: Long
    ): NostrCalendar = NostrCalendar(
        id = id,
        ownerNpub = ownerNpub,
        dTag = dTag,
        title = payload.title,
        description = payload.description,
        color = payload.color,
        eventDs = payload.eventDs,
        shares = payload.shares,
        updatedAt = payload.updatedAt,
        createdAt = createdAt
    )

    fun domainToCalendarPayload(calendar: NostrCalendar): CalendarListPayload = CalendarListPayload(
        title = calendar.title,
        description = calendar.description,
        color = calendar.color,
        eventDs = calendar.eventDs,
        shares = calendar.shares,
        updatedAt = calendar.updatedAt
    )

    fun domainToCalendarEntity(calendar: NostrCalendar): CalendarEntity = CalendarEntity(
        id = calendar.id,
        ownerNpub = calendar.ownerNpub,
        dTag = calendar.dTag,
        title = calendar.title,
        color = calendar.color,
        payloadJson = json.encodeToString(
            CalendarListPayload.serializer(),
            domainToCalendarPayload(calendar)
        ),
        updatedAt = calendar.updatedAt,
        createdAt = calendar.createdAt
    )

    fun calendarEntityToDomain(entity: CalendarEntity): NostrCalendar {
        val payload = json.decodeFromString(CalendarListPayload.serializer(), entity.payloadJson)
        return calendarPayloadToDomain(
            payload,
            id = entity.id,
            ownerNpub = entity.ownerNpub,
            dTag = entity.dTag,
            createdAt = entity.createdAt
        )
    }

    fun calendarPayloadToJson(payload: CalendarListPayload): String =
        json.encodeToString(CalendarListPayload.serializer(), payload)

    fun jsonToCalendarPayload(plainJson: String): CalendarListPayload =
        json.decodeFromString(CalendarListPayload.serializer(), plainJson)
}

/** Convenience for share-ledger construction in Phase 3. */
fun shareEntry(pubkey: String, role: String, lastPushedAt: Long) =
    CalendarShareEntry(pubkey, role, lastPushedAt)

/** Convenience for participant construction. */
fun participant(pubkey: String, role: String) =
    CalendarParticipant(pubkey, role)
