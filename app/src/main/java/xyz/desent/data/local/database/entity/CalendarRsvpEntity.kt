package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Cached RSVP response to a calendar event, received by the organizer via a
 * `["bridge","calendar"] / type=rsvp` gift wrap (refs/CALENDAR_PROTOCOL.md
 * §RSVP). Keyed by (owner, event, sender) so an invitee's latest status
 * replaces their prior one.
 */
@Entity(
    tableName = "calendar_rsvps",
    primaryKeys = ["ownerNpub", "eventD", "senderPubkey"],
    indices = [Index("ownerNpub"), Index("eventD")]
)
data class CalendarRsvpEntity(
    val ownerNpub: String,
    val eventD: String,
    val senderPubkey: String,
    val status: String,
    val freebusy: String?,
    val note: String?,
    val updatedAt: Long
)
