package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import nostr.id.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.CalendarDao
import xyz.desent.data.local.database.dao.CalendarEventDao
import xyz.desent.data.local.database.dao.CalendarRsvpDao
import xyz.desent.data.mapper.CalendarMapper
import xyz.desent.domain.model.CalendarEventPayload
import xyz.desent.domain.model.CalendarInstant
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.repository.RelayRepository

class CalendarRepositoryImplTest {

    private val privHex = "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    private val priv = Nip44Encryption.hexToBytes(privHex)
    private val identity = Identity.create(privHex)
    private val ownerNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

    private lateinit var eventDao: CalendarEventDao
    private lateinit var calendarDao: CalendarDao
    private lateinit var rsvpDao: CalendarRsvpDao
    private lateinit var nostrRepo: NostrRepository
    private lateinit var giftWrap: GiftWrapEncryptionService
    private lateinit var relayRepo: RelayRepository
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var repo: CalendarRepositoryImpl

    @Before
    fun setUp() {
        eventDao = mockk(relaxed = true)
        calendarDao = mockk(relaxed = true)
        rsvpDao = mockk(relaxed = true)
        nostrRepo = mockk(relaxed = true)
        giftWrap = mockk(relaxed = true)
        relayRepo = mockk(relaxed = true)
        secureKeyManager = mockk()
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
        // A real signed event standing in for the ephemeral wrap wrapGift() builds.
        val wrapEvent = nostr.event.impl.GenericEvent.builder()
            .pubKey(identity.publicKey)
            .kind(xyz.desent.data.nostr.NostrKinds.GIFT_WRAP)
            .createdAt(1_000L)
            .content("wrapcontent")
            .tags(emptyList())
            .build()
        identity.sign(wrapEvent)
        coEvery { giftWrap.wrapGift(any(), any(), any(), any(), any(), any()) } returns Result.success(wrapEvent)
        repo = CalendarRepositoryImpl(eventDao, calendarDao, rsvpDao, CalendarMapper(), secureKeyManager, nostrRepo, giftWrap, relayRepo)
    }

    private fun timeEvent(
        id: String = "ev1",
        title: String = "Dentist",
        startSec: Long = 1738300000L,
        endSec: Long? = 1738303000L
    ): NostrCalendarEvent = NostrCalendarEvent(
        id = id, ownerNpub = ownerNpub, kind = NostrCalendarEvent.KIND_TIME,
        dTag = NostrCalendarEvent.D_PREFIX + id, title = title,
        startSec = startSec, endSec = endSec, allDay = false,
        startDateIso = null, endDateIso = null, startTzid = null, endTzid = null,
        summary = null, description = null, location = null, geohash = null, image = null,
        participants = emptyList(), links = emptyList(), hashtags = emptyList(),
        calendarD = null, attachments = emptyList(), shares = emptyList(),
        updatedAt = 1738200000L, createdAt = 100L
    )

    @Test
    fun saveEvent_publishesEncryptedKind31923_andCachesLocally() = runBlocking {
        val event = timeEvent()
        val cipherSlot = slot<String>()
        coEvery { nostrRepo.publishCalendarEvent(any(), any(), capture(cipherSlot)) } returns Result.success(Unit)

        val result = repo.saveEvent(event)
        assertTrue(result.isSuccess)

        coVerify { nostrRepo.publishCalendarEvent(31923, "desent:event:ev1", cipherSlot.captured) }
        coVerify { eventDao.upsertEvent(any()) }

        val decrypted = PrivateStorageCrypto.decryptFromSelf(cipherSlot.captured, priv)
        val payload = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(CalendarEventPayload.serializer(), decrypted)
        assertEquals("Dentist", payload.title)
        assertEquals(CalendarInstant.Timestamp(1738300000L), payload.start)
    }

    @Test
    fun saveDateBasedEvent_publishesKind31922() = runBlocking {
        val event = timeEvent(id = "ev2", title = "PTO").copy(
            kind = NostrCalendarEvent.KIND_DATE, allDay = true, startDateIso = "2024-01-15", endDateIso = null
        )
        coEvery { nostrRepo.publishCalendarEvent(any(), any(), any()) } returns Result.success(Unit)

        assertTrue(repo.saveEvent(event).isSuccess)
        coVerify { nostrRepo.publishCalendarEvent(31922, "desent:event:ev2", any()) }
    }

    @Test
    fun deleteEvent_publishesEmptyTombstone_andDeletesLocally() = runBlocking {
        coEvery { nostrRepo.publishCalendarEvent(any(), any(), any()) } returns Result.success(Unit)

        assertTrue(repo.deleteEvent(ownerNpub, "ev1").isSuccess)
        coVerify { nostrRepo.publishCalendarEvent(31923, "desent:event:ev1", "") }
        coVerify { eventDao.deleteEvent(ownerNpub, "ev1") }
    }

    @Test
    fun shareEvent_wrapsAndPushesGiftWrap_andRecordsShareLedger() = runBlocking {
        val event = timeEvent()
        coEvery { nostrRepo.publishCalendarEvent(any(), any(), any()) } returns Result.success(Unit)

        val result = repo.shareEvent(event, recipientNpub = ownerNpub, role = "attendee")
        assertTrue(result.isSuccess)

        // Gift-wrap content was produced for the recipient (expiration is the
        // NIP-40 TTL now passed into wrapGift — must match the 6-arg call)...
        coVerify { giftWrap.wrapGift(any(), ownerNpub, any(), 14, any(), any()) }
        // ...and the 1059 was published to the DeSent relay (never anywhere else).
        coVerify { relayRepo.publishEventToRelay(any(), RelayConfig.EMAIL_RELAY_URL) }
        // The share ledger is persisted by re-publishing the event (≥1 publish
        // for the wrap + ≥1 for the ledger re-save).
        coVerify(atLeast = 1) { nostrRepo.publishCalendarEvent(31923, "desent:event:ev1", any()) }
    }

    @Test
    fun subscribeDelegatesToNostrRepository() = runBlocking {
        repo.subscribeToOwnCalendar()
        coVerify { nostrRepo.subscribeToOwnCalendar() }
    }

    @Test
    fun eventsInRange_mergesOneOffsWithExpandedRecurrences() = runBlocking {
        val mapper = CalendarMapper()
        // One-off on Jan 10 2026.
        val oneOff = timeEvent(id = "single", startSec = iso("2026-01-10"), endSec = iso("2026-01-10") + 3600)
        // Yearly birthday anchored 1990 — far outside the range start.
        val birthday = timeEvent(id = "bday", startSec = iso("1990-03-15"), endSec = iso("1990-03-15") + 3600)
            .copy(recurrence = xyz.desent.domain.model.Recurrence(freq = xyz.desent.domain.model.RecurFreq.YEARLY))

        // The SQL range query would only see the one-off (the birthday's
        // anchor is outside the range); recurring rows come from their own query.
        coEvery { eventDao.eventsInRange(ownerNpub, any(), any()) } returns listOf(mapper.domainToEntity(oneOff))
        coEvery { eventDao.recurringEvents(ownerNpub) } returns listOf(mapper.domainToEntity(birthday))

        val result = repo.eventsInRange(ownerNpub, iso("2026-01-01"), iso("2026-04-01"))

        assertEquals(listOf("single", "bday"), result.map { it.id })
        assertEquals(iso("2026-03-15"), result[1].startSec)
    }

    @Test
    fun eventsInRange_recurringAnchorRowIsNotDuplicated() = runBlocking {
        val mapper = CalendarMapper()
        // Anchor Jan 7 2026, daily — the anchor row itself overlaps the range
        // and would come back from BOTH queries.
        val daily = timeEvent(id = "daily", startSec = iso("2026-01-07"), endSec = iso("2026-01-07") + 3600)
            .copy(recurrence = xyz.desent.domain.model.Recurrence(freq = xyz.desent.domain.model.RecurFreq.DAILY))

        coEvery { eventDao.eventsInRange(ownerNpub, any(), any()) } returns listOf(mapper.domainToEntity(daily))
        coEvery { eventDao.recurringEvents(ownerNpub) } returns listOf(mapper.domainToEntity(daily))

        val result = repo.eventsInRange(ownerNpub, iso("2026-01-07"), iso("2026-01-10"))

        // Jan 7, 8, 9 — the anchor occurs exactly once despite both DAO hits.
        assertEquals(listOf<Long>(0, 86400, 172_800), result.map { it.startSec - iso("2026-01-07") })
        assertTrue(result.all { it.id == "daily" })
    }

    private fun iso(date: String): Long =
        java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
}
