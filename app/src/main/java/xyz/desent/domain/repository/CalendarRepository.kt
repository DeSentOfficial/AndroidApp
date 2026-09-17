package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.NostrCalendar
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.NostrCalendarRsvp
import xyz.desent.domain.model.RsvpStatus

/**
 * Encrypted personal Nostr calendar (NIP-52 kinds 31922/31923, with 31924/31925
 * arriving in later phases). Events are NIP-44 self-encrypted; the relay only
 * ever sees the `d` tag + opaque ciphertext. See refs/CALENDAR_PROTOCOL.md.
 *
 * The local Room cache holds the decrypted events so time-range queries can be
 * served in-app (the relay cannot see `start`/`end` — they're inside the
 * ciphertext).
 */
interface CalendarRepository {

    fun observeEvents(ownerNpub: String): Flow<List<NostrCalendarEvent>>

    fun observeEvent(ownerNpub: String, id: String): Flow<NostrCalendarEvent?>

    suspend fun getEvent(ownerNpub: String, id: String): NostrCalendarEvent?

    /** Events whose `[startSec, endSec)` overlaps `[rangeStart, rangeEnd)`. */
    suspend fun eventsInRange(ownerNpub: String, rangeStart: Long, rangeEnd: Long): List<NostrCalendarEvent>

    /** Create or update (same `d` → replaceable upsert). Empties content = tombstone. */
    suspend fun saveEvent(event: NostrCalendarEvent): Result<Unit>

    suspend fun deleteEvent(ownerNpub: String, id: String): Result<Unit>

    // ---- Calendars (kind 31924) ----

    fun observeCalendars(ownerNpub: String): Flow<List<NostrCalendar>>

    suspend fun getCalendar(ownerNpub: String, id: String): NostrCalendar?

    suspend fun saveCalendar(calendar: NostrCalendar): Result<Unit>

    suspend fun deleteCalendar(ownerNpub: String, id: String): Result<Unit>

    /** Gift-wrap [event]'s plaintext and push it to [recipientNpub] (NIP-17 share). */
    suspend fun shareEvent(event: NostrCalendarEvent, recipientNpub: String, role: String): Result<Unit>

    /** Re-push the current version of [event] to every recorded share recipient. */
    suspend fun pushEventUpdateToShares(event: NostrCalendarEvent): Result<Unit>

    /** Publish our own 31925 RSVP for [event] and notify its organizer via gift wrap. */
    suspend fun sendRsvp(event: NostrCalendarEvent, status: RsvpStatus, freebusy: String?, note: String?): Result<Unit>

    /** RSVPs the active user (as organizer) has received for [eventD]. */
    fun observeRsvps(ownerNpub: String, eventD: String): Flow<List<NostrCalendarRsvp>>

    /** Open / refresh the relay subscription for the active user's own calendar kinds. */
    suspend fun subscribeToOwnCalendar(): Result<Unit>
}
