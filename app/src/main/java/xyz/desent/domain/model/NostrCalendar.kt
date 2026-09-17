package xyz.desent.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Encrypted Nostr calendar event (NIP-52 kinds 31922 / 31923), per
 * refs/CALENDAR_PROTOCOL.md.
 *
 *  - kind 31923: time-based event; `start`/`end` are unix seconds (true
 *    instants — render them in the viewer's timezone).
 *  - kind 31922: date-based (all-day / multi-day) event; `start`/`end` are
 *    ISO 8601 dates (`YYYY-MM-DD`) — **floating local dates** that denote
 *    calendar days, not instants. They MUST be anchored and rendered in the
 *    viewer's local timezone (CALENDAR_PROTOCOL.md §Date-based event, pinned
 *    2026-08-29): never convert them through UTC, which draws the event one
 *    day early everywhere west of UTC.
 *
 * The outer Nostr event carries only `["d", "desent:event:<id>"]`; every other
 * field lives inside the NIP-44-self-encrypted `.content`, serialized as
 * [CalendarEventPayload]. [startSec]/[endSec] are normalized to unix seconds
 * so the local Room cache can serve time-range queries (the relay cannot —
 * content is opaque ciphertext). For date-based events they are UTC-midnight
 * **indexes**: `startSec` = midnight UTC of the floating start date and
 * `endSec` = midnight UTC of the wire's exclusive end ISO, so pure
 * epoch-day arithmetic ([SECONDS_PER_DAY]) recovers the floating days
 * exactly. They are range-query indexes only — renderers derive day keys via
 * [daySpanIn] (or the ISO strings), never a timezone conversion of these
 * seconds.
 *
 * Attachments reuse the same client-side AES-256-GCM Blossom flow as notes;
 * the per-attachment AES keys live inside this encrypted payload.
 */
data class NostrCalendarEvent(
    /** The uuid portion of the `d` tag (`desent:event:<id>`). Stable across edits. */
    val id: String,
    /** The owner's npub (multi-account scoping). */
    val ownerNpub: String,
    /** 31923 (time-based) or 31922 (date-based / all-day). */
    val kind: Int,
    /** The full `d` tag value (`desent:event:<id>`). */
    val dTag: String,
    val title: String,
    /**
     * Normalized inclusive start, unix seconds. Time-based: the instant
     * itself. Date-based: midnight UTC of the floating start date — a
     * range-query index only; render via [daySpanIn] or [startDateIso].
     */
    val startSec: Long,
    /**
     * Normalized exclusive end, unix seconds; null = instantaneous /
     * single-day. Date-based: midnight UTC of the wire's exclusive end ISO
     * (the wire end date itself is NOT covered) — a range-query index only.
     */
    val endSec: Long?,
    /** True for kind 31922 (all-day / multi-day). Derived from [kind]. */
    val allDay: Boolean,
    /** Original ISO date for date-based events (`YYYY-MM-DD`), else null. */
    val startDateIso: String?,
    val endDateIso: String?,
    val startTzid: String?,
    val endTzid: String?,
    val summary: String?,
    val description: String?,
    val location: String?,
    val geohash: String?,
    val image: String?,
    val participants: List<CalendarParticipant>,
    val links: List<String>,
    val hashtags: List<String>,
    /** `d` identifier of the 31924 calendar this event belongs to, if any. */
    val calendarD: String?,
    val attachments: List<AttachmentMeta>,
    /** Client-local share ledger (inside the encrypted blob). Populated in Phase 3. */
    val shares: List<CalendarShareEntry>,
    /**
     * Recurrence rule, expanded client-side at read time (see
     * [xyz.desent.domain.usecase.RecurringEventExpander]). A recurring series
     * is one stored event — occurrences are never materialized on the wire or
     * in the local cache. Null = one-off event.
     */
    val recurrence: Recurrence? = null,
    val updatedAt: Long,
    val createdAt: Long
) {
    val isTimeBased: Boolean get() = kind == KIND_TIME
    val isDateBased: Boolean get() = kind == KIND_DATE

    /**
     * The event's day span `[startDay, endDay)` in epoch days — the one true
     * way to derive which calendar days an event paints. See [daySpan] for
     * the semantics per kind.
     */
    fun daySpanIn(zone: ZoneId): Pair<Long, Long> = daySpan(startSec, endSec, allDay, zone)

    companion object {
        const val KIND_TIME = 31923
        const val KIND_DATE = 31922
        const val KIND_CALENDAR_LIST = 31924
        const val KIND_RSVP = 31925
        const val D_PREFIX = "desent:event:"

        /** Seconds per day — the granularity of the date-based UTC index. */
        const val SECONDS_PER_DAY = 86_400L

        /**
         * The event's day span `[startDay, endDay)` in epoch days.
         *
         * All-day (31922) events carry floating ISO dates whose UTC-midnight
         * indexes equal the dates' epoch days, so the span is pure epoch-day
         * arithmetic — never a zone conversion, which would shift the event a
         * day for any non-UTC viewer. A null [endSec] covers exactly the
         * start day.
         *
         * Time-based (31923) events are true instants and resolve their days
         * in [zone]: a null [endSec] (or one equal to the start) paints only
         * the start day, and an exclusive end at exact midnight paints no
         * extra day.
         */
        fun daySpan(startSec: Long, endSec: Long?, allDay: Boolean, zone: ZoneId): Pair<Long, Long> {
            if (allDay) {
                val startDay = Math.floorDiv(startSec, SECONDS_PER_DAY)
                val endDay = Math.floorDiv(endSec ?: (startSec + SECONDS_PER_DAY), SECONDS_PER_DAY)
                return startDay to maxOf(endDay, startDay + 1)
            }
            val startDay = Instant.ofEpochSecond(startSec).atZone(zone).toLocalDate().toEpochDay()
            val endDay = endSec
                ?.let { Instant.ofEpochSecond(it - 1).atZone(zone).toLocalDate().toEpochDay() + 1 }
                ?: (startDay + 1)
            return startDay to endDay
        }
    }
}

/**
 * A named calendar collection (NIP-52 kind 31924), `d = "desent:calendar:<id>"`.
 * Carries an ordered list of member event `d` identifiers plus its own share
 * ledger (calendar-level shares cascade to all member events on push).
 */
data class NostrCalendar(
    val id: String,
    val ownerNpub: String,
    val dTag: String,
    val title: String,
    val description: String?,
    val color: String?,
    val eventDs: List<String>,
    val shares: List<CalendarShareEntry>,
    val updatedAt: Long,
    val createdAt: Long
) {
    companion object {
        const val D_PREFIX = "desent:calendar:"
    }
}

/** Wire shape inside the 31924 ciphertext (refs/CALENDAR_PROTOCOL.md). */
@Serializable
data class CalendarListPayload(
    val title: String,
    val description: String? = null,
    val color: String? = null,
    @SerialName("event_ds") val eventDs: List<String> = emptyList(),
    val shares: List<CalendarShareEntry> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long
)

/**
 * A received RSVP response cached locally so the organizer can render invitee
 * status. The durable on-wire form is a kind-31925 event
 * (`d = "desent:rsvp:<event-uuid>"`) authored by the invitee; the organizer
 * merely caches the gift-wrapped notification here.
 */
data class NostrCalendarRsvp(
    val ownerNpub: String,
    val eventD: String,
    val senderPubkeyHex: String,
    val status: RsvpStatus,
    val freebusy: String?,
    val note: String?,
    val updatedAt: Long
)

enum class RsvpStatus(val wire: String) {
    ACCEPTED("accepted"),
    DECLINED("declined"),
    TENTATIVE("tentative");

    companion object {
        fun fromWire(s: String?): RsvpStatus? = values().firstOrNull { it.wire == s }
    }
}

/** Wire shape inside the 31925 ciphertext + RSVP gift-wrap rumor (refs/CALENDAR_PROTOCOL.md §RSVP). */
@Serializable
data class RsvpPlaintext(
    @SerialName("event_d") val eventD: String,
    @SerialName("event_pubkey") val eventPubkey: String,
    @SerialName("event_kind") val eventKind: Int,
    val status: String,
    val freebusy: String? = null,
    val note: String? = null,
    @SerialName("updated_at") val updatedAt: Long
)

/** Wire shape inside the 31922/31923 ciphertext (refs/CALENDAR_PROTOCOL.md). */
@Serializable
data class CalendarEventPayload(
    val title: String,
    @Serializable(with = CalendarInstantSerializer::class) val start: CalendarInstant,
    @Serializable(with = CalendarInstantSerializer::class) val end: CalendarInstant? = null,
    @SerialName("start_tzid") val startTzid: String? = null,
    @SerialName("end_tzid") val endTzid: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val geohash: String? = null,
    val image: String? = null,
    val participants: List<CalendarParticipant> = emptyList(),
    val links: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    @SerialName("calendar_d") val calendarD: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
    val attachments: List<AttachmentMeta> = emptyList(),
    val shares: List<CalendarShareEntry> = emptyList(),
    /** Recurrence rule (RFC 5545 RRULE subset); null = one-off event. */
    val recur: Recurrence? = null
)

/** RFC 5545-style frequency of a [Recurrence] rule. */
enum class RecurFreq(val wire: String) {
    DAILY("DAILY"),
    WEEKLY("WEEKLY"),
    MONTHLY("MONTHLY"),
    YEARLY("YEARLY");

    companion object {
        fun fromWire(s: String?): RecurFreq? =
            s?.let { v -> values().firstOrNull { it.wire.equals(v, ignoreCase = true) } }
    }
}

/**
 * Recurrence rule for a calendar event — an RFC 5545 RRULE subset
 * (FREQ + INTERVAL + BYDAY + UNTIL/COUNT). Rides inside the NIP-44-encrypted
 * [CalendarEventPayload]; occurrences are expanded client-side at read time,
 * so an infinitely recurring series still costs exactly one stored event
 * (refs/CALENDAR_PROTOCOL.md defers recurrence to client-side expansion).
 *
 * The event's own start date is the rule anchor. Expansion is pure
 * calendar-day arithmetic on the UTC-midnight index (which for all-day
 * events equals the floating ISO dates' epoch days), so occurrences land on
 * the same calendar day in every timezone.
 */
@Serializable
data class Recurrence(
    val freq: RecurFreq,
    /** Every `interval` periods (e.g. FREQ=YEARLY;INTERVAL=2 = biennial). */
    val interval: Int = 1,
    /**
     * RFC 5545 BYDAY entries: plain codes `"MO"`..`"SU"`, optionally with an
     * ordinal prefix for MONTHLY/YEARLY rules (`"1MO"` = first Monday,
     * `"-1FR"` = last Friday; plain codes there mean every such weekday of
     * the period). Empty = the anchor's own day.
     */
    val byDay: List<String> = emptyList(),
    /** Inclusive last occurrence date, ISO `YYYY-MM-DD`; null = unbounded. */
    @SerialName("until_iso") val untilIso: String? = null,
    /** Total occurrences from the anchor, inclusive; null = unbounded. */
    val count: Int? = null,
    /**
     * Display hint for never-ending YEARLY rules (birthday-style): hide the
     * anchor year and show only day/month. The full anchor date is still
     * stored — expansion needs it; this is presentation-only.
     */
    @SerialName("omit_year") val omitYear: Boolean = false
)

@Serializable
data class CalendarParticipant(val pubkey: String, val role: String)

@Serializable
data class CalendarShareEntry(
    val pubkey: String,
    val role: String,
    @SerialName("last_pushed_at") val lastPushedAt: Long
)

/**
 * A calendar start/end instant on the wire. The protocol uses a bare unix
 * integer for time-based events (31923) and an ISO `YYYY-MM-DD` string for
 * date-based events (31922); this sealed type preserves that exactly.
 */
sealed class CalendarInstant {
    /** Unix seconds (time-based, kind 31923). */
    data class Timestamp(val seconds: Long) : CalendarInstant()
    /** ISO 8601 date `YYYY-MM-DD` (date-based, kind 31922). */
    data class Date(val iso: String) : CalendarInstant()

    /** Normalized inclusive start, unix seconds (date → midnight UTC index). */
    fun toStartSec(): Long = when (this) {
        is Timestamp -> seconds
        is Date -> parseIsoDate(iso).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }

    /**
     * Normalized exclusive end, unix seconds (null if open-ended). For a
     * [Date] this is midnight UTC of the wire's exclusive ISO end — the
     * protocol's wire `end` date itself is not covered ("end is exclusive
     * and optional; if omitted, the event covers only start"). Callers that
     * need a single-day all-day span derive `start + [SECONDS_PER_DAY]`
     * themselves (see CalendarMapper).
     */
    fun toEndSec(): Long? = when (this) {
        is Timestamp -> seconds
        is Date -> parseIsoDate(iso).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }

    companion object {
        fun parseIsoDate(iso: String): LocalDate =
            runCatching { LocalDate.parse(iso) }.getOrElse {
                // Tolerate trailing time fragments some clients emit.
                LocalDate.parse(iso.substringBefore('T'))
            }
    }
}

/**
 * Serializes [CalendarInstant] as either a JSON number (Timestamp) or a JSON
 * string (Date) to match the protocol's two wire shapes. On decode it accepts
 * either form, and also tolerates a numeric value encoded as a string.
 */
object CalendarInstantSerializer : KSerializer<CalendarInstant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CalendarInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CalendarInstant) {
        when (encoder) {
            is JsonEncoder -> when (value) {
                is CalendarInstant.Timestamp -> encoder.encodeJsonElement(JsonPrimitive(value.seconds))
                is CalendarInstant.Date -> encoder.encodeJsonElement(JsonPrimitive(value.iso))
            }
            else -> when (value) {
                // Non-JSON fallback: always emit as a string token.
                is CalendarInstant.Timestamp -> encoder.encodeString(value.seconds.toString())
                is CalendarInstant.Date -> encoder.encodeString(value.iso)
            }
        }
    }

    override fun deserialize(decoder: Decoder): CalendarInstant {
        return when (decoder) {
            is JsonDecoder -> {
                val e = decoder.decodeJsonElement().jsonPrimitive
                e.content.toLongOrNull()?.let { CalendarInstant.Timestamp(it) }
                    ?: CalendarInstant.Date(e.content)
            }
            else -> {
                val s = decoder.decodeString()
                s.toLongOrNull()?.let { CalendarInstant.Timestamp(it) } ?: CalendarInstant.Date(s)
            }
        }
    }
}
