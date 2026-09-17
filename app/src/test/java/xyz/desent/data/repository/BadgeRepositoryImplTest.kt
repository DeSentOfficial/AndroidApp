package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.BadgeDao
import xyz.desent.data.local.database.dao.RelayDao
import xyz.desent.data.local.database.entity.BadgeAwardEntity
import xyz.desent.data.local.database.entity.BadgeDefinitionEntity
import xyz.desent.data.local.database.entity.BadgePinEntity
import xyz.desent.data.nip11.Nip11MetadataService
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.BadgeAward
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.data.relay.PublishResult

class BadgeRepositoryImplTest {

    private val privHex = "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    private val identity = Identity.create(privHex)
    private val ownerHex = identity.publicKey.toHexString()
    private val ownerNpub = Bech32Utils.hexToNpub(ownerHex)
    private val relayNpub = RelayConfig.RELAY_PUBKEY_HEX

    private lateinit var badgeDao: BadgeDao
    private lateinit var relayRepository: RelayRepository
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var nip11MetadataService: Nip11MetadataService
    private lateinit var relayDao: RelayDao
    private lateinit var repo: BadgeRepositoryImpl

    /** Replay=1 so the verdict emitted during publishEventToRelay is seen by the awaiting collector. */
    private val publishResults = MutableSharedFlow<PublishResult>(replay = 1)

    @Before
    fun setUp() {
        badgeDao = mockk(relaxed = true)
        relayRepository = mockk(relaxed = true)
        secureKeyManager = mockk()
        nip11MetadataService = mockk()
        relayDao = mockk()
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
        coEvery { relayRepository.publishResults } returns publishResults
        coEvery { relayDao.getRelayByUrl(any()) } returns null
        coEvery { nip11MetadataService.fetchMetadata(any()) } returns null
        repo = BadgeRepositoryImpl(badgeDao, relayRepository, secureKeyManager, nip11MetadataService, relayDao)
    }

    // ---------------- relay npub resolution ----------------

    @Test
    fun relayNpubHex_prefersCachedNip11Column() = runBlocking {
        coEvery { relayDao.getRelayByUrl(RelayConfig.EMAIL_RELAY_URL) } returns mockk {
            every { nip11Pubkey } returns "aa11"
        }

        assertEquals("aa11", repo.relayNpubHex())
        coVerify(exactly = 0) { nip11MetadataService.fetchMetadata(any()) }
    }
    @Test
    fun relayNpubHex_fallsBackToConstantWhenOffline() = runBlocking {
        assertEquals(RelayConfig.RELAY_PUBKEY_HEX, repo.relayNpubHex())
    }

    // ---------------- inbound kind 30009 ----------------

    @Test
    fun onInboundDefinition_storesIconModeDefinition() = runBlocking {
        val event = signedEvent(
            kind = NostrKinds.BADGE_DEFINITION,
            content = "Paid Supporter",
            tags = listOf(
                "d" to listOf("paid-supporter"),
                "name" to listOf("Paid Supporter"),
                "icon" to listOf("workspace_premium"),
                "color" to listOf("#ffb300")
            )
        )

        repo.onInboundBadgeEvent(event)

        val stored = slot<BadgeDefinitionEntity>()
        coVerify { badgeDao.upsertDefinition(capture(stored)) }
        assertEquals("paid-supporter", stored.captured.slug)
        assertEquals("workspace_premium", stored.captured.iconName)
        assertEquals("#ffb300", stored.captured.color)
        assertEquals(event.id!!, stored.captured.eventId)
    }

    @Test
    fun onInboundDefinition_tombstoneRetiresBadge() = runBlocking {
        coEvery { badgeDao.getDefinition("paid-supporter") } returns mockk()

        repo.onInboundBadgeEvent(
            signedEvent(
                kind = NostrKinds.BADGE_DEFINITION,
                content = "",
                tags = listOf("d" to listOf("paid-supporter"))
            )
        )

        coVerify { badgeDao.deleteDefinition("paid-supporter") }
        coVerify(exactly = 0) { badgeDao.upsertDefinition(any()) }
    }

    @Test
    fun onInboundDefinition_skipsStaleEcho() = runBlocking {
        coEvery { badgeDao.getDefinition("paid-supporter") } returns mockk {
            every { updatedAt } returns Long.MAX_VALUE
        }

        repo.onInboundBadgeEvent(
            signedEvent(
                kind = NostrKinds.BADGE_DEFINITION,
                content = "Paid Supporter",
                createdAt = 1000L,
                tags = listOf("d" to listOf("paid-supporter"))
            )
        )

        coVerify(exactly = 0) { badgeDao.upsertDefinition(any()) }
    }

    // ---------------- inbound kind 8 ----------------

    @Test
    fun onInboundAward_insertsAwardWithAwardeeNpub() = runBlocking {
        repo.onInboundBadgeEvent(
            signedEvent(
                kind = NostrKinds.BADGE_AWARD,
                content = "",
                tags = listOf(
                    "p" to listOf(ownerHex),
                    "a" to listOf("30009:$relayNpub:early-adopter")
                )
            )
        )

        val stored = slot<BadgeAwardEntity>()
        coVerify { badgeDao.insertAward(capture(stored)) }
        assertEquals(ownerNpub, stored.captured.awardeeNpub)
        assertEquals("early-adopter", stored.captured.slug)
        assertEquals("30009:$relayNpub:early-adopter", stored.captured.definitionAddress)
    }

    // ---------------- inbound kind 30008 ----------------

    @Test
    fun onInboundPinList_replacesPinsInOrder() = runBlocking {
        val award1 = awardEntity(eventId = "award1", slug = "early-adopter")
        val award2 = awardEntity(eventId = "award2", slug = "verified")
        coEvery { badgeDao.getAwardsByIds(listOf("award1", "award2")) } returns listOf(award1, award2)

        repo.onInboundBadgeEvent(
            signedEvent(
                kind = NostrKinds.PROFILE_BADGES,
                content = "badges",
                tags = listOf(
                    "d" to listOf("profile_badges"),
                    "a" to listOf("30009:$relayNpub:early-adopter"),
                    "e" to listOf("award1"),
                    "a" to listOf("30009:$relayNpub:verified"),
                    "e" to listOf("award2")
                )
            )
        )

        val pins = slot<List<BadgePinEntity>>()
        coVerify { badgeDao.replacePins(ownerNpub, capture(pins)) }
        assertEquals(listOf("award1", "award2"), pins.captured.map { it.awardEventId })
        assertEquals(listOf(0, 1), pins.captured.map { it.position })
    }

    @Test
    fun onInboundPinList_dropsStalePairsForRevokedAwards() = runBlocking {
        // Only award1 still resolves; award2 was revoked after publish.
        coEvery { badgeDao.getAwardsByIds(any()) } returns listOf(awardEntity(eventId = "award1", slug = "early-adopter"))

        repo.onInboundBadgeEvent(
            signedEvent(
                kind = NostrKinds.PROFILE_BADGES,
                content = "badges",
                tags = listOf(
                    "d" to listOf("profile_badges"),
                    "a" to listOf("30009:$relayNpub:early-adopter"),
                    "e" to listOf("award1"),
                    "a" to listOf("30009:$relayNpub:verified"),
                    "e" to listOf("award2")
                )
            )
        )

        val pins = slot<List<BadgePinEntity>>()
        coVerify { badgeDao.replacePins(ownerNpub, capture(pins)) }
        assertEquals(listOf("award1"), pins.captured.map { it.awardEventId })
    }

    @Test
    fun onInboundPinList_emptyContentUnpinsEverything() = runBlocking {
        repo.onInboundBadgeEvent(
            signedEvent(
                kind = NostrKinds.PROFILE_BADGES,
                content = "",
                tags = listOf("d" to listOf("profile_badges"))
            )
        )

        coVerify { badgeDao.replacePins(ownerNpub, emptyList()) }
    }

    // ---------------- inbound kind 5 (revocation) ----------------

    @Test
    fun onInboundDeletion_revokesAwardAndDependentPins() = runBlocking {
        coEvery { badgeDao.getAward("award1") } returns awardEntity(eventId = "award1", slug = "early-adopter")

        repo.onInboundDeletionEvent(
            signedEvent(
                kind = NostrKinds.DELETION,
                content = "",
                tags = listOf("e" to listOf("award1"))
            )
        )

        coVerify { badgeDao.deleteAward("award1") }
        coVerify { badgeDao.deletePinsByAward("award1") }
    }

    @Test
    fun onInboundDeletion_retiresDefinitionByEventId() = runBlocking {
        coEvery { badgeDao.getAward(any()) } returns null

        repo.onInboundDeletionEvent(
            signedEvent(
                kind = NostrKinds.DELETION,
                content = "",
                tags = listOf("e" to listOf("definition-event-id"))
            )
        )

        coVerify { badgeDao.deleteDefinitionByEventId("definition-event-id") }
    }

    // ---------------- publishPinList (kind 30008) ----------------

    private fun domainAward(eventId: String, slug: String) = BadgeAward(
        eventId = eventId,
        awardeeNpub = ownerNpub,
        slug = slug,
        definitionAddress = "30009:$relayNpub:$slug",
        awardedAt = 1000L
    )

    private fun stubOwnedAwards(vararg awards: BadgeAward) {
        coEvery { badgeDao.getAwardsByIds(awards.map { it.eventId }) } returns awards.map {
            awardEntity(it.eventId, it.slug)
        }
        coEvery { badgeDao.getDefinition(any()) } returns mockk()
    }

    @Test
    fun publishPinList_publishesSignedEventToDesentRelayOnly() = runBlocking {
        stubOwnedAwards(domainAward("award1", "early-adopter"))
        coEvery { relayRepository.publishEventToRelay(any(), RelayConfig.EMAIL_RELAY_URL) } coAnswers {
            val event = firstArg<GenericEvent>()
            publishResults.emit(PublishResult(event.id!!, true, "", RelayConfig.EMAIL_RELAY_URL))
        }

        val rejection = repo.publishPinList(ownerNpub, listOf(domainAward("award1", "early-adopter")))

        assertEquals(null, rejection)
        val event = slot<GenericEvent>()
        // Exactly one publish in total — the kind 30008 goes to the DeSent
        // relay only, never fanned out to the user's write relays.
        coVerify(exactly = 1) { relayRepository.publishEventToRelay(capture(event), RelayConfig.EMAIL_RELAY_URL) }
        assertEquals(ownerHex, event.captured.pubKey.toHexString())
        assertEquals("badges", event.captured.content)
        assertEquals(NostrKinds.PROFILE_BADGES, event.captured.kind)
        assertNotNull(event.captured.signature)

        // Optimistic local pin replace happened.
        val pins = slot<List<BadgePinEntity>>()
        coVerify { badgeDao.replacePins(ownerNpub, capture(pins)) }
        assertEquals(listOf("award1"), pins.captured.map { it.awardEventId })
    }

    @Test
    fun publishPinList_emptyListPublishesTombstone() = runBlocking {
        coEvery { relayRepository.publishEventToRelay(any(), RelayConfig.EMAIL_RELAY_URL) } coAnswers {
            val event = firstArg<GenericEvent>()
            publishResults.emit(PublishResult(event.id!!, true, "", RelayConfig.EMAIL_RELAY_URL))
        }

        val rejection = repo.publishPinList(ownerNpub, emptyList())

        assertEquals(null, rejection)
        val event = slot<GenericEvent>()
        coVerify { relayRepository.publishEventToRelay(capture(event), RelayConfig.EMAIL_RELAY_URL) }
        assertEquals("", event.captured.content)
    }

    @Test
    fun publishPinList_surfacesRelayRejectionAndRestoresPins() = runBlocking {
        stubOwnedAwards(domainAward("award1", "early-adopter"))
        coEvery { badgeDao.getPinsFor(ownerNpub) } returns listOf(
            BadgePinEntity(ownerNpub, "old-award", "old-slug", 0)
        )
        coEvery { relayRepository.publishEventToRelay(any(), RelayConfig.EMAIL_RELAY_URL) } coAnswers {
            val event = firstArg<GenericEvent>()
            publishResults.emit(PublishResult(event.id!!, false, "invalid: award revoked", RelayConfig.EMAIL_RELAY_URL))
        }

        val rejection = repo.publishPinList(ownerNpub, listOf(domainAward("award1", "early-adopter")))

        assertEquals("invalid: award revoked", rejection)
        // Restored snapshot after the rejection (second replacePins call).
        val pinLists = mutableListOf<List<BadgePinEntity>>()
        coVerify(atLeast = 2) { badgeDao.replacePins(ownerNpub, capture(pinLists)) }
        assertEquals(listOf("old-award"), pinLists.last().map { it.awardEventId })
    }

    @Test
    fun publishPinList_failsWhenNotLoggedIn() = runBlocking {
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.failure(Exception("no key"))

        assertEquals("Not logged in", repo.publishPinList(ownerNpub, emptyList()))
        coVerify(exactly = 0) { relayRepository.publishEventToRelay(any(), any()) }
    }

    @Test
    fun publishPinList_rejectsMoreThanTwentyPairs() = runBlocking {
        val many = (1..21).map { domainAward("a$it", "s$it") }

        assertEquals(
            "invalid: at most 20 badges may be pinned",
            repo.publishPinList(ownerNpub, many)
        )
        coVerify(exactly = 0) { relayRepository.publishEventToRelay(any(), any()) }
    }

    @Test
    fun publishPinList_rejectsUnknownOrForeignAward() = runBlocking {
        coEvery { badgeDao.getAwardsByIds(any()) } returns emptyList()

        assertEquals(
            "invalid: pin list references an unknown or revoked award",
            repo.publishPinList(ownerNpub, listOf(domainAward("ghost", "early-adopter")))
        )
        coVerify(exactly = 0) { relayRepository.publishEventToRelay(any(), any()) }
    }

    @Test
    fun publishPinList_rejectsRetiredBadge() = runBlocking {
        val award = domainAward("award1", "retired-badge")
        coEvery { badgeDao.getAwardsByIds(listOf("award1")) } returns listOf(awardEntity("award1", "retired-badge"))
        coEvery { badgeDao.getDefinition("retired-badge") } returns null

        assertEquals(
            "invalid: badge 'retired-badge' is no longer available",
            repo.publishPinList(ownerNpub, listOf(award))
        )
        coVerify(exactly = 0) { relayRepository.publishEventToRelay(any(), any()) }
    }

    // ---------------- sync ----------------

    @Test
    fun requestBadgeSync_subscribesDefinitionsAndUserBadgesOncePerNpub() = runBlocking {
        coEvery { relayRepository.waitForRelayReady(any(), any()) } returns true

        repo.requestBadgeSync(ownerNpub)
        repo.requestBadgeSync(ownerNpub) // throttled

        coVerify(exactly = 1) {
            relayRepository.subscribeToEventsOnRelay(
                any(), "badges-defs", RelayConfig.EMAIL_RELAY_URL, persistent = true
            )
        }
        val filters = slot<List<Map<String, Any>>>()
        coVerify(exactly = 1) {
            relayRepository.subscribeToEventsOnRelay(
                capture(filters), "badges-${ownerHex.take(8)}", RelayConfig.EMAIL_RELAY_URL, persistent = true
            )
        }
        // Two filters: awards by #p, pin list by authors.
        assertEquals(2, filters.captured.size)
    }

    @Test
    fun resyncUserBadges_bypassesThrottle() = runBlocking {
        coEvery { relayRepository.waitForRelayReady(any(), any()) } returns true

        repo.requestBadgeSync(ownerNpub)
        repo.resyncUserBadges(ownerNpub)

        coVerify(exactly = 2) {
            relayRepository.subscribeToEventsOnRelay(
                any(), "badges-${ownerHex.take(8)}", RelayConfig.EMAIL_RELAY_URL, persistent = true
            )
        }
    }

    // ---------------- helpers ----------------

    private fun awardEntity(eventId: String, slug: String) = BadgeAwardEntity(
        eventId = eventId,
        awardeeNpub = ownerNpub,
        slug = slug,
        definitionAddress = "30009:$relayNpub:$slug",
        awardedAt = 1000L
    )

    private fun signedEvent(
        kind: Int,
        content: String,
        tags: List<Pair<String, List<String>>> = emptyList(),
        createdAt: Long = 1500L
    ): GenericEvent {
        val event = GenericEvent.builder()
            .pubKey(identity.publicKey)
            .kind(kind)
            .content(content)
            .createdAt(createdAt)
            .tags(tags.map { GenericTag(it.first, it.second) as nostr.event.BaseTag })
            .build()
        identity.sign(event)
        return event
    }
}
