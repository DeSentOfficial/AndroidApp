package xyz.desent.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import xyz.desent.crypto.AesGcm
import xyz.desent.data.attachments.NoteAttachmentClient
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.model.NostrCalendar
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.NostrCalendarRsvp
import xyz.desent.domain.model.RsvpStatus
import xyz.desent.domain.repository.CalendarRepository

/**
 * Use case for the encrypted Nostr calendar. Wraps the repository and the
 * client-side-encrypted attachment flow (shared with notes) so the UI layer
 * stays thin. The active account's npub is resolved via [PreferencesManager].
 */
class CalendarUseCase(
    private val repository: CalendarRepository,
    private val preferencesManager: PreferencesManager,
    private val attachmentClient: NoteAttachmentClient
) {

    suspend fun activeOwnerNpub(): String? =
        preferencesManager.npubKey.firstOrNull()

    fun observeEvents(ownerNpub: String): Flow<List<NostrCalendarEvent>> =
        repository.observeEvents(ownerNpub)

    fun observeEvent(ownerNpub: String, id: String): Flow<NostrCalendarEvent?> =
        repository.observeEvent(ownerNpub, id)

    suspend fun getEvent(ownerNpub: String, id: String): NostrCalendarEvent? =
        repository.getEvent(ownerNpub, id)

    suspend fun eventsInRange(ownerNpub: String, rangeStart: Long, rangeEnd: Long): List<NostrCalendarEvent> =
        repository.eventsInRange(ownerNpub, rangeStart, rangeEnd)

    suspend fun saveEvent(event: NostrCalendarEvent): Result<Unit> =
        repository.saveEvent(event)

    suspend fun deleteEvent(ownerNpub: String, id: String): Result<Unit> =
        repository.deleteEvent(ownerNpub, id)

    // ---- Calendars (kind 31924) ----

    fun observeCalendars(ownerNpub: String): Flow<List<NostrCalendar>> =
        repository.observeCalendars(ownerNpub)

    suspend fun getCalendar(ownerNpub: String, id: String): NostrCalendar? =
        repository.getCalendar(ownerNpub, id)

    suspend fun saveCalendar(calendar: NostrCalendar): Result<Unit> =
        repository.saveCalendar(calendar)

    suspend fun deleteCalendar(ownerNpub: String, id: String): Result<Unit> =
        repository.deleteCalendar(ownerNpub, id)

    suspend fun shareEvent(event: NostrCalendarEvent, recipientNpub: String, role: String): Result<Unit> =
        repository.shareEvent(event, recipientNpub, role)

    suspend fun pushEventUpdateToShares(event: NostrCalendarEvent): Result<Unit> =
        repository.pushEventUpdateToShares(event)

    suspend fun sendRsvp(event: NostrCalendarEvent, status: RsvpStatus, freebusy: String?, note: String?): Result<Unit> =
        repository.sendRsvp(event, status, freebusy, note)

    fun observeRsvps(ownerNpub: String, eventD: String): Flow<List<NostrCalendarRsvp>> =
        repository.observeRsvps(ownerNpub, eventD)

    /** Add [eventD] (`desent:event:<uuid>`) to a calendar's membership, re-publishing the 31924. */
    suspend fun addEventToCalendar(ownerNpub: String, calendarId: String, eventD: String): Result<Unit> {
        val calendar = repository.getCalendar(ownerNpub, calendarId)
            ?: return Result.failure(Exception("Calendar not found"))
        if (eventD in calendar.eventDs) return Result.success(Unit)
        val updated = calendar.copy(
            eventDs = calendar.eventDs + eventD,
            updatedAt = System.currentTimeMillis() / 1000
        )
        return repository.saveCalendar(updated)
    }

    /** Remove [eventD] from a calendar's membership, re-publishing the 31924. */
    suspend fun removeEventFromCalendar(ownerNpub: String, calendarId: String, eventD: String): Result<Unit> {
        val calendar = repository.getCalendar(ownerNpub, calendarId)
            ?: return Result.failure(Exception("Calendar not found"))
        val updated = calendar.copy(
            eventDs = calendar.eventDs.filterNot { it == eventD },
            updatedAt = System.currentTimeMillis() / 1000
        )
        return repository.saveCalendar(updated)
    }

    suspend fun subscribeToOwnCalendar(): Result<Unit> =
        repository.subscribeToOwnCalendar()

    /**
     * Client-side-encrypt [data] and upload it via the attachment ciphertext
     * endpoint. Returns the [AttachmentMeta] (including the AES key + nonce) to
     * embed inside the NIP-44-encrypted event payload.
     */
    suspend fun uploadAttachment(data: ByteArray, mimeType: String, fileName: String): Result<AttachmentMeta> {
        val encrypted = AesGcm.encrypt(data)
        return attachmentClient.uploadCiphertext(encrypted.wireBytes, mimeType).map { resp ->
            AttachmentMeta(
                sha256 = resp.sha256,
                keyHex = encrypted.keyHex,
                nonceHex = encrypted.nonceHex,
                mimeType = mimeType,
                filename = fileName,
                size = data.size.toLong()
            )
        }
    }

    /** Download an attachment's ciphertext and AES-GCM decrypt it to plaintext. */
    suspend fun downloadAttachment(meta: AttachmentMeta): Result<ByteArray> =
        attachmentClient.downloadCiphertext(meta.sha256).map { wire ->
            AesGcm.decrypt(wire, meta.keyHex, meta.nonceHex)
        }
}
