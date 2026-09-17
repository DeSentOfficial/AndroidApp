package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached decrypted Nostr calendar collection (NIP-52 kind 31924,
 * `d = "desent:calendar:<id>"`). Keyed by the calendar uuid; scoped to the
 * owning account via [ownerNpub]. The full membership list + shares live in
 * [payloadJson]; the denormalized [title] / [color] columns are for cheap
 * rendering in the calendar picker.
 */
@Entity(
    tableName = "calendars",
    indices = [Index("ownerNpub")]
)
data class CalendarEntity(
    @PrimaryKey
    val id: String,
    val ownerNpub: String,
    val dTag: String,
    val title: String,
    val color: String?,
    val payloadJson: String,
    val updatedAt: Long,
    val createdAt: Long
)
