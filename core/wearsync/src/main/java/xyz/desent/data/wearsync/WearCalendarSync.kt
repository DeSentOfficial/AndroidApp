package xyz.desent.data.wearsync

import kotlinx.serialization.Serializable

/**
 * Shared phone→watch calendar sync contract.
 *
 * Like the inbox, the phone is the only party that ever decrypts: NIP-52
 * calendar payloads (kinds 31922..31925) are NIP-44-decrypted at ingestion
 * and stored plaintext in Room. The watch receives a flat, recurrence-
 * already-expanded occurrence list over a bounded window (today + 14 days)
 * plus contact-derived anniversary labels — no calendar math, keys, or
 * relays on the watch.
 */

/** One expanded calendar occurrence (recurring series share the series id). */
@Serializable
data class WearCalendarEvent(
    val id: String = "",
    val title: String = "",
    /** Unix seconds. For all-day events this is the UTC-midnight index only. */
    val startSec: Long = 0L,
    /** Unix seconds, exclusive; null end = instantaneous event. */
    val endSec: Long? = null,
    val allDay: Boolean = false,
    /** Floating `YYYY-MM-DD` for all-day rendering (no timezone math). */
    val startDateIso: String? = null,
    /** Exclusive floating `YYYY-MM-DD` for multi-day all-day events. */
    val endDateIso: String? = null,
    val location: String = "",
    val description: String = "",
    /** Series/event revision stamp — drives the new-event notification watermark. */
    val updatedAt: Long = 0L
)

/** Contact-derived anniversary label placed on its day (nothing stored on the wire). */
@Serializable
data class WearAnniversary(
    /** Stable key for list rendering (`<contact-ref>|<label>`). */
    val key: String = "",
    val name: String = "",
    val label: String = "",
    val epochDay: Long = 0L
)

/** Calendar snapshot pushed as one DataItem. */
@Serializable
data class WearCalendar(
    /** Occurrences within the window, earliest start first. */
    val events: List<WearCalendarEvent> = emptyList(),
    /** Anniversaries within the window, earliest day first. */
    val anniversaries: List<WearAnniversary> = emptyList(),
    /** Window bounds (unix seconds, [start, end)) so the watch can label "Today". */
    val windowStartSec: Long = 0L,
    val windowEndSec: Long = 0L,
    /** Uniqueness stamp so identical content still triggers a Data Layer change event. */
    val syncedAt: Long = 0L
)

/** GZIP + JSON codec for [WearCalendar]. */
object WearCalendarCodec {
    fun encode(calendar: WearCalendar): ByteArray = WearSyncGzip.encodeGzipped(calendar)

    fun decode(bytes: ByteArray): WearCalendar? = WearSyncGzip.decodeGzipped(bytes)
}
