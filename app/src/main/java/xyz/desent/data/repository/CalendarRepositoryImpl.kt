package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nostr.event.BaseTag
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.calendar.CalendarBridgeTags
import xyz.desent.data.local.database.dao.CalendarDao
import xyz.desent.data.local.database.dao.CalendarEventDao
import xyz.desent.data.local.database.dao.CalendarRsvpDao
import xyz.desent.data.local.database.entity.CalendarRsvpEntity
import xyz.desent.data.mapper.CalendarMapper
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.CalendarParticipant
import xyz.desent.domain.model.CalendarShareEntry
import xyz.desent.domain.model.NostrCalendar
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.NostrCalendarRsvp
import xyz.desent.domain.model.RsvpPlaintext
import xyz.desent.domain.model.RsvpStatus
import xyz.desent.domain.model.UnwrappedContent
import xyz.desent.domain.repository.CalendarRepository
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.domain.usecase.RecurringEventExpander

/**
 * Implements [CalendarRepository] over NIP-52 kinds 31922/31923/31924.
 *
 * Publish path: serialize → NIP-44 self-encrypt → kind 31922/31923/31924 →
 * DeSent relays. Inbound path (seen on the own-pubkey subscription): decrypt →
 * upsert the local cache, or hard-delete on an empty-content tombstone.
 *
 * Sharing is push-based via NIP-17 gift wraps (`["bridge","calendar"]`): the
 * client does the fan-out, the relay never sees plaintext event contents.
 */
class CalendarRepositoryImpl(
    private val eventDao: CalendarEventDao,
    private val calendarDao: CalendarDao,
    private val rsvpDao: CalendarRsvpDao,
    private val mapper: CalendarMapper,
    private val secureKeyManager: SecureKeyManager,
    private val nostrRepository: NostrRepository,
    private val giftWrapEncryptionService: GiftWrapEncryptionService,
    private val relayRepository: RelayRepository
) : CalendarRepository {

    private companion object {
        const val TAG = "CalendarRepo"
        const val EVENT_D_PREFIX = "desent:event:"
        const val CALENDAR_D_PREFIX = "desent:calendar:"
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private suspend fun selfPriv(): ByteArray? =
        secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.privateKey?.rawData

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    override fun observeEvents(ownerNpub: String): Flow<List<NostrCalendarEvent>> =
        eventDao.observeEvents(ownerNpub).map { rows -> rows.map { mapper.entityToDomain(it) } }

    override fun observeEvent(ownerNpub: String, id: String): Flow<NostrCalendarEvent?> =
        eventDao.observeEvent(ownerNpub, id).map { it?.let { mapper.entityToDomain(it) } }

    override suspend fun getEvent(ownerNpub: String, id: String): NostrCalendarEvent? =
        eventDao.getEvent(ownerNpub, id)?.let { mapper.entityToDomain(it) }

    /**
     * One-off events come straight from the SQL range query. Recurring events
     * (denormalized `recurFreq`) are fetched separately and expanded into
     * virtual occurrences at read time — the stored row is only the anchor.
     */
    override suspend fun eventsInRange(ownerNpub: String, rangeStart: Long, rangeEnd: Long): List<NostrCalendarEvent> {
        val direct = eventDao.eventsInRange(ownerNpub, rangeStart, rangeEnd)
            .filter { it.recurFreq == null }
            .mapNotNull { runCatching { mapper.entityToDomain(it) }.getOrNull() }
        val recurring = eventDao.recurringEvents(ownerNpub)
            .mapNotNull { runCatching { mapper.entityToDomain(it) }.getOrNull() }
        val occurrences = RecurringEventExpander.expandAll(recurring, rangeStart, rangeEnd)
        return (direct + occurrences).sortedBy { it.startSec }
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    override suspend fun saveEvent(event: NostrCalendarEvent): Result<Unit> {
        val priv = selfPriv()
            ?: return Result.failure(Exception("No active identity"))
        return try {
            val payload = mapper.domainToPayload(event)
            val ciphertext = PrivateStorageCrypto.encryptToSelf(
                mapper.payloadToJson(payload), priv
            )
            nostrRepository.publishCalendarEvent(event.kind, event.dTag, ciphertext).getOrThrow()

            // Mirror into the local cache immediately for a snappy UI; the
            // inbound echo from the relay will REPLACE this row (same PK).
            eventDao.upsertEvent(mapper.domainToEntity(event))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveEvent failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteEvent(ownerNpub: String, id: String): Result<Unit> {
        return try {
            // Empty-content tombstone: relay hard-deletes the prior version and
            // does not store the tombstone itself.
            nostrRepository.publishCalendarEvent(
                kind = NostrCalendarEvent.KIND_TIME,
                dTag = EVENT_D_PREFIX + id,
                ciphertext = ""
            ).getOrThrow()
            eventDao.deleteEvent(ownerNpub, id)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteEvent failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun subscribeToOwnCalendar(): Result<Unit> = try {
        nostrRepository.subscribeToOwnCalendar()
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "subscribeToOwnCalendar failed: ${e.message}", e)
        Result.failure(e)
    }

    // ------------------------------------------------------------------
    // Calendars (kind 31924)
    // ------------------------------------------------------------------

    override fun observeCalendars(ownerNpub: String): Flow<List<NostrCalendar>> =
        calendarDao.observeCalendars(ownerNpub).map { rows ->
            rows.map { mapper.calendarEntityToDomain(it) }
        }

    override suspend fun getCalendar(ownerNpub: String, id: String): NostrCalendar? =
        calendarDao.getCalendar(ownerNpub, id)?.let { mapper.calendarEntityToDomain(it) }

    override suspend fun saveCalendar(calendar: NostrCalendar): Result<Unit> {
        val priv = selfPriv()
            ?: return Result.failure(Exception("No active identity"))
        return try {
            val payload = mapper.domainToCalendarPayload(calendar)
            val ciphertext = PrivateStorageCrypto.encryptToSelf(
                mapper.calendarPayloadToJson(payload), priv
            )
            nostrRepository.publishCalendarEvent(
                NostrCalendarEvent.KIND_CALENDAR_LIST, calendar.dTag, ciphertext
            ).getOrThrow()
            calendarDao.upsertCalendar(mapper.domainToCalendarEntity(calendar))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "saveCalendar failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteCalendar(ownerNpub: String, id: String): Result<Unit> {
        return try {
            nostrRepository.publishCalendarEvent(
                kind = NostrCalendarEvent.KIND_CALENDAR_LIST,
                dTag = CALENDAR_D_PREFIX + id,
                ciphertext = ""
            ).getOrThrow()
            calendarDao.deleteCalendar(ownerNpub, id)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteCalendar failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Inbound (called by NostrEventProcessor for kinds 31922-31925)
    // ------------------------------------------------------------------

    /**
     * Decrypt and cache an inbound kind-31922/31923 event or kind-31924
     * calendar authored by the active user. Empty content is a tombstone → the
     * local row is deleted. Kind 31925 (RSVPs) is handled in Phase 3. Defence-
     * in-depth: only processes events whose author is the active identity (the
     * relay already owner-scopes reads).
     */
    suspend fun onInboundCalendarEvent(event: GenericEvent) {
        val authorHex = event.pubKey.toHexString()
        val activeHex = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.publicKey?.toHexString()
        if (activeHex == null || authorHex != activeHex) {
            Log.d(TAG, "onInbound: skipping ${event.kind} not authored by active user (${authorHex.take(8)})")
            return
        }

        val ownerNpub = Bech32Utils.hexToNpub(authorHex)
        val dTag = event.tags.firstOrNull { (it as? GenericTag)?.getCode() == "d" }
            ?.let { (it as? GenericTag)?.getParams()?.firstOrNull() }
            ?: return

        val isCalendar = event.kind == NostrCalendarEvent.KIND_CALENDAR_LIST || dTag.startsWith(CALENDAR_D_PREFIX)
        val id = dTag.removePrefix(if (isCalendar) CALENDAR_D_PREFIX else EVENT_D_PREFIX)

        // Tombstone: relay hard-deleted the prior version; mirror locally.
        if (event.content.isNullOrEmpty()) {
            if (isCalendar) calendarDao.deleteCalendar(ownerNpub, id)
            else eventDao.deleteEvent(ownerNpub, id)
            Log.d(TAG, "onInbound: tombstone deleted ${if (isCalendar) "calendar" else "event"} $id")
            return
        }

        val priv = selfPriv() ?: return
        val plaintext = try {
            PrivateStorageCrypto.decryptFromSelf(event.content, priv)
        } catch (e: Exception) {
            Log.w(TAG, "onInbound: decrypt failed for d=$dTag: ${e.message}")
            return
        }

        if (isCalendar) {
            val payload = try {
                mapper.jsonToCalendarPayload(plaintext)
            } catch (e: Exception) {
                Log.w(TAG, "onInbound: calendar payload parse failed for d=$dTag: ${e.message}")
                return
            }
            val domain = mapper.calendarPayloadToDomain(
                payload, id = id, ownerNpub = ownerNpub, dTag = dTag, createdAt = event.createdAt
            )
            calendarDao.upsertCalendar(mapper.domainToCalendarEntity(domain))
            Log.d(TAG, "onInbound: upserted calendar $id (updatedAt=${payload.updatedAt})")
            return
        }

        if (event.kind != NostrCalendarEvent.KIND_TIME && event.kind != NostrCalendarEvent.KIND_DATE) {
            // 31925 (RSVPs) arrives in Phase 3.
            Log.d(TAG, "onInbound: deferring kind ${event.kind} (not yet supported)")
            return
        }

        val payload = try {
            mapper.jsonToPayload(plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "onInbound: payload parse failed for d=$dTag: ${e.message}")
            return
        }

        val domain = mapper.payloadToDomain(
            payload = payload,
            id = id,
            ownerNpub = ownerNpub,
            kind = event.kind,
            dTag = dTag,
            createdAt = event.createdAt
        )
        eventDao.upsertEvent(mapper.domainToEntity(domain))
        Log.d(TAG, "onInbound: upserted event $id (kind=${event.kind}, updatedAt=${payload.updatedAt})")
    }

    // ------------------------------------------------------------------
    // Sharing (NIP-17 gift-wrap push, ["bridge","calendar"])
    // ------------------------------------------------------------------

    /**
     * Gift-wrap the full plaintext of [event] and push it to [recipientNpub].
     * Records the recipient in the event's encrypted [NostrCalendarEvent.shares]
     * ledger and re-publishes the 31922/31923 so the ledger is durable across
     * the user's devices.
     */
    override suspend fun shareEvent(event: NostrCalendarEvent, recipientNpub: String, role: String): Result<Unit> {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
            ?: return Result.failure(Exception("No active identity"))
        val senderNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())
        val recipientHex = Bech32Utils.npubToHex(recipientNpub)

        return try {
            val payload = mapper.domainToPayload(event)
            val content = mapper.payloadToJson(payload)
            val wrapEvent = giftWrapEncryptionService.wrapGift(
                content = content,
                recipientNpub = recipientNpub,
                senderNpub = senderNpub,
                kind = 14,
                extraTags = listOf(
                    listOf("p", recipientHex),
                    listOf(CalendarBridgeTags.KEY_CALENDAR_KIND, event.kind.toString()),
                    listOf(CalendarBridgeTags.KEY_CALENDAR_D, event.dTag),
                    listOf(CalendarBridgeTags.KEY_SHARE_ROLE, role),
                    listOf("bridge", CalendarBridgeTags.BRIDGE),
                    listOf("type", CalendarBridgeTags.TYPE_SHARE),
                    listOf(CalendarBridgeTags.KEY_SHARES_VERSION, CalendarBridgeTags.SHARES_VERSION)
                ),
                expiration = nowSec() + CalendarBridgeTags.SHARE_WRAP_TTL_SECONDS
            ).getOrThrow()

            publishCalendarGiftWrap(wrapEvent)

            // Update the encrypted share ledger + re-publish the 31922/31923.
            val updated = event.copy(
                shares = (event.shares.filterNot { it.pubkey == recipientHex } +
                    CalendarShareEntry(recipientHex, role, nowSec())).distinct(),
                updatedAt = nowSec()
            )
            saveEvent(updated).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "shareEvent failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Re-push the current version of [event] to every recorded share recipient
     * (and, in Phase 3c, every RSVP sender). Call after an edit so recipients
     * see the update.
     */
    override suspend fun pushEventUpdateToShares(event: NostrCalendarEvent): Result<Unit> {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
            ?: return Result.failure(Exception("No active identity"))
        val senderNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())
        if (event.shares.isEmpty()) return Result.success(Unit)
        return try {
            val payload = mapper.domainToPayload(event)
            val content = mapper.payloadToJson(payload)
            var failures = 0
            event.shares.forEach { share ->
                runCatching {
                    val wrapEvent = giftWrapEncryptionService.wrapGift(
                        content = content,
                        recipientNpub = Bech32Utils.hexToNpub(share.pubkey),
                        senderNpub = senderNpub,
                        kind = 14,
                        extraTags = listOf(
                            listOf("p", share.pubkey),
                            listOf(CalendarBridgeTags.KEY_CALENDAR_KIND, event.kind.toString()),
                            listOf(CalendarBridgeTags.KEY_CALENDAR_D, event.dTag),
                            listOf(CalendarBridgeTags.KEY_SHARE_ROLE, share.role),
                            listOf("bridge", CalendarBridgeTags.BRIDGE),
                            listOf("type", CalendarBridgeTags.TYPE_SHARE),
                            listOf(CalendarBridgeTags.KEY_SHARES_VERSION, CalendarBridgeTags.SHARES_VERSION)
                        ),
                        expiration = nowSec() + CalendarBridgeTags.SHARE_WRAP_TTL_SECONDS
                    ).getOrThrow()
                    publishCalendarGiftWrap(wrapEvent)
                }.onFailure { failures++ }
            }
            if (failures == event.shares.size) Result.failure(Exception("All share pushes failed"))
            else Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "pushEventUpdateToShares failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Publish a pre-built, pre-signed kind-1059 gift wrap [event] (built by
     * [GiftWrapEncryptionService.wrapGift], including its NIP-40 expiration tag).
     */
    private suspend fun publishCalendarGiftWrap(event: GenericEvent) {
        val targetRelays = listOf(RelayConfig.EMAIL_RELAY_URL)
        var ok = false
        targetRelays.forEach { url ->
            runCatching { relayRepository.publishEventToRelay(event, url) }
                .onSuccess { ok = true }
                .onFailure { Log.w(TAG, "gift wrap publish to $url failed: ${it.message}") }
        }
        if (!ok) throw Exception("Failed to publish gift wrap to any relay")
    }

    // ------------------------------------------------------------------
    // Inbound gift-wrap rumor routing (["bridge","calendar"])
    // ------------------------------------------------------------------

    /**
     * Handle an unwrapped kind-14 rumor tagged `["bridge","calendar"]`. For a
     * `type=share` rumor, decrypt the embedded event JSON, re-encrypt it to our
     * own self-conversation key, and publish it under our own pubkey with the
     * same `d` — making it a first-class replaceable calendar entry in our own
     * storage. `type=rsvp` is handled by [onInboundRsvp] (Phase 3c).
     */
    suspend fun onInboundCalendarRumor(unwrapped: UnwrappedContent, receiverNpub: String) {
        val type = unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == "type" }?.getOrNull(1)
        when (type) {
            CalendarBridgeTags.TYPE_SHARE -> onInboundShare(unwrapped, receiverNpub)
            CalendarBridgeTags.TYPE_RSVP -> onInboundRsvp(unwrapped, receiverNpub)
            else -> Log.d(TAG, "onInboundCalendarRumor: unknown type=$type, ignoring")
        }
    }

    private suspend fun onInboundShare(unwrapped: UnwrappedContent, receiverNpub: String) {
        val priv = selfPriv() ?: return
        val kindTag = unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == CalendarBridgeTags.KEY_CALENDAR_KIND }?.getOrNull(1)?.toIntOrNull()
            ?: run { Log.w(TAG, "onInboundShare: missing calendar_kind"); return }
        val dTag = unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == CalendarBridgeTags.KEY_CALENDAR_D }?.getOrNull(1)
            ?: run { Log.w(TAG, "onInboundShare: missing calendar_d"); return }
        val role = unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == CalendarBridgeTags.KEY_SHARE_ROLE }?.getOrNull(1) ?: "attendee"

        val payload = try {
            mapper.jsonToPayload(unwrapped.content)
        } catch (e: Exception) {
            Log.w(TAG, "onInboundShare: payload parse failed: ${e.message}")
            return
        }
        val id = dTag.removePrefix(EVENT_D_PREFIX)
        // Record the share sender as the event's organizer so RSVP addressing
        // (and rendering) can resolve them later.
        val organizerHex = Bech32Utils.npubToHex(unwrapped.senderNpub)
        val withOrganizer = payload.copy(
            participants = (payload.participants.filterNot { it.pubkey == organizerHex } +
                CalendarParticipant(organizerHex, "organizer")).distinct()
        )
        val domain = mapper.payloadToDomain(
            payload = withOrganizer,
            id = id,
            ownerNpub = receiverNpub,
            kind = kindTag,
            dTag = dTag,
            createdAt = nowSec()
        )
        // Re-encrypt to our own self-conversation key and publish under our
        // pubkey with the same `d`. This becomes OUR replaceable copy.
        runCatching {
            val ciphertext = PrivateStorageCrypto.encryptToSelf(mapper.payloadToJson(withOrganizer), priv)
            nostrRepository.publishCalendarEvent(kindTag, dTag, ciphertext).getOrThrow()
            eventDao.upsertEvent(mapper.domainToEntity(domain))
            Log.d(TAG, "onInboundShare: imported shared event $id (role=$role)")
        }.onFailure { Log.w(TAG, "onInboundShare: import failed: ${it.message}") }
    }

    /** Phase 3c hook — RSVP receipt. */
    private suspend fun onInboundRsvp(unwrapped: UnwrappedContent, receiverNpub: String) {
        val eventD = unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == CalendarBridgeTags.KEY_CALENDAR_D }?.getOrNull(1)
            ?: run { Log.w(TAG, "onInboundRsvp: missing calendar_d"); return }
        val payload = try {
            json.decodeFromString(RsvpPlaintext.serializer(), unwrapped.content)
        } catch (e: Exception) {
            Log.w(TAG, "onInboundRsvp: payload parse failed: ${e.message}")
            return
        }
        val senderHex = Bech32Utils.npubToHex(unwrapped.senderNpub)
        val status = RsvpStatus.fromWire(payload.status) ?: run {
            Log.w(TAG, "onInboundRsvp: unknown status ${payload.status}"); return
        }
        rsvpDao.upsertRsvp(
            CalendarRsvpEntity(
                ownerNpub = receiverNpub,
                eventD = eventD,
                senderPubkey = senderHex,
                status = status.wire,
                freebusy = payload.freebusy,
                note = payload.note,
                updatedAt = payload.updatedAt
            )
        )
        Log.d(TAG, "onInboundRsvp: cached RSVP for $eventD from ${senderHex.take(8)} = ${status.wire}")
    }

    // ------------------------------------------------------------------
    // RSVP send + observe
    // ------------------------------------------------------------------

    override fun observeRsvps(ownerNpub: String, eventD: String): Flow<List<NostrCalendarRsvp>> =
        rsvpDao.observeRsvpsForEvent(ownerNpub, eventD).map { rows ->
            rows.map { e ->
                NostrCalendarRsvp(
                    ownerNpub = e.ownerNpub,
                    eventD = e.eventD,
                    senderPubkeyHex = e.senderPubkey,
                    status = RsvpStatus.fromWire(e.status) ?: RsvpStatus.TENTATIVE,
                    freebusy = e.freebusy,
                    note = e.note,
                    updatedAt = e.updatedAt
                )
            }
        }

    override suspend fun sendRsvp(
        event: NostrCalendarEvent,
        status: RsvpStatus,
        freebusy: String?,
        note: String?
    ): Result<Unit> {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
            ?: return Result.failure(Exception("No active identity"))
        val senderNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

        // Resolve the organizer (event author) to address the gift-wrapped
        // notification. For a shared event this is the participant carrying
        // role "organizer"; fall back to the event's owner pubkey.
        val organizerHex = event.participants.firstOrNull { it.role == "organizer" }?.pubkey
            ?: Bech32Utils.npubToHex(event.ownerNpub)
        val organizerNpub = Bech32Utils.hexToNpub(organizerHex)

        return try {
            val rsvp = RsvpPlaintext(
                eventD = event.dTag,
                eventPubkey = organizerHex,
                eventKind = event.kind,
                status = status.wire,
                freebusy = freebusy,
                note = note,
                updatedAt = nowSec()
            )
            // 1. Durable self-encrypted record (kind 31925).
            val ciphertext = PrivateStorageCrypto.encryptToSelf(
                json.encodeToString(RsvpPlaintext.serializer(), rsvp), identity.privateKey.rawData
            )
            nostrRepository.publishCalendarEvent(
                NostrCalendarEvent.KIND_RSVP,
                "desent:rsvp:${event.id}",
                ciphertext
            ).getOrThrow()

            // 2. Gift-wrap the RSVP notification to the organizer.
            val wrapEvent = giftWrapEncryptionService.wrapGift(
                content = json.encodeToString(RsvpPlaintext.serializer(), rsvp),
                recipientNpub = organizerNpub,
                senderNpub = senderNpub,
                kind = 14,
                extraTags = listOf(
                    listOf("p", organizerHex),
                    listOf(CalendarBridgeTags.KEY_CALENDAR_D, event.dTag),
                    listOf("bridge", CalendarBridgeTags.BRIDGE),
                    listOf("type", CalendarBridgeTags.TYPE_RSVP),
                    listOf(CalendarBridgeTags.KEY_SHARES_VERSION, CalendarBridgeTags.SHARES_VERSION)
                ),
                expiration = nowSec() + CalendarBridgeTags.SHARE_WRAP_TTL_SECONDS
            ).getOrThrow()
            publishCalendarGiftWrap(wrapEvent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendRsvp failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
