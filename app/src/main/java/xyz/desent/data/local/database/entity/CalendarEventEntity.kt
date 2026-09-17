package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached decrypted Nostr calendar event (NIP-52 kind 31922 / 31923),
 * `d = "desent:event:<id>"`. Keyed by the event uuid; scoped to the owning
 * account via [ownerNpub].
 *
 * The full decrypted payload is kept in [payloadJson]; the denormalized
 * [startSec] / [endSec] / [kind] / [title] / [calendarD] columns exist so the
 * relay-blind time-range + grouping queries can run locally (the relay only
 * ever sees ciphertext, so it cannot serve them). See refs/CALENDAR_ANDROID.md
 * §5 ("Local time-range queries").
 */
@Entity(
    tableName = "calendar_events",
    indices = [Index("ownerNpub"), Index("startSec")]
)
data class CalendarEventEntity(
    @PrimaryKey
    val id: String,
    val ownerNpub: String,
    val kind: Int,
    val dTag: String,
    val title: String,
    val startSec: Long,
    /** Exclusive end, unix seconds; null = instantaneous / single-day. */
    val endSec: Long?,
    val calendarD: String?,
    /**
     * Denormalized `Recurrence.freq` wire value for recurring events, so the
     * expansion path can fetch only recurring rows cheaply
     * (`WHERE recurFreq IS NOT NULL`); null = one-off. The full rule lives
     * inside the encrypted [payloadJson].
     */
    val recurFreq: String? = null,
    val payloadJson: String,
    val updatedAt: Long,
    val createdAt: Long
)
