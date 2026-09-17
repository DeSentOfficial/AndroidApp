package xyz.desent.data.contacts

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.ContactProfileLinkDao
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.database.entity.ContactProfileLinkEntity
import xyz.desent.data.local.database.entity.UserEntity
import xyz.desent.data.nip05.Nip05VerificationService
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.domain.repository.RelayRepository
import java.util.concurrent.atomic.AtomicInteger

class ContactProfileResolverImplTest {

    private val hexA = "ab".repeat(32)
    private val hexB = "cd".repeat(32)
    private val npubA = Bech32Utils.hexToNpub(hexA)
    private val npubB = Bech32Utils.hexToNpub(hexB)

    private val desent = RelayConfig.EMAIL_RELAY_URL
    private val yadha = "wss://relay.yadha.net"

    private lateinit var relayRepository: RelayRepository
    private lateinit var eventProcessor: NostrEventProcessor
    private lateinit var userDao: UserDao
    private lateinit var nip05Service: Nip05VerificationService
    private lateinit var linkDao: ContactProfileLinkDao

    @Before
    fun setUp() {
        relayRepository = mockk(relaxed = true)
        eventProcessor = mockk()
        userDao = mockk(relaxed = true)
        nip05Service = mockk()
        linkDao = mockk(relaxed = true)
        coEvery { linkDao.getByIdentifier(any()) } returns null
    }

    private fun resolver() = ContactProfileResolverImpl(
        relayRepository = relayRepository,
        eventProcessor = eventProcessor,
        userDao = userDao,
        nip05Service = nip05Service,
        contactProfileLinkDao = linkDao,
        lookupTimeoutMs = 150L,
        connectSettleMs = 10L
    )

    private fun userEntity(
        npub: String,
        picture: String? = "https://img.example/p.png",
        lastUpdated: Long = System.currentTimeMillis()
    ) = UserEntity(
        npub = npub,
        name = "Alice",
        displayName = null,
        about = null,
        picture = picture,
        banner = null,
        website = null,
        lud06 = null,
        lud16 = null,
        nip05 = null,
        nip05Verified = false,
        createdAt = 1L,
        lastUpdated = lastUpdated
    )

    /** A SharedFlow that immediately buffers [values] for any subscriber. */
    private fun arrivalsOf(vararg values: String): MutableSharedFlow<String> =
        MutableSharedFlow<String>(replay = values.size, extraBufferCapacity = values.size).apply {
            values.forEach { tryEmit(it) }
        }

    // ==================== Room-first cache ====================

    @Test
    fun `fresh Room row is served with zero network traffic`() = runBlocking {
        coEvery { userDao.getUserByNpub(npubA) } returns userEntity(npubA)
        every { eventProcessor.metadataArrivals } returns MutableSharedFlow()

        val profile = resolver().resolve(hexA)

        assertNotNull(profile)
        assertEquals("https://img.example/p.png", profile?.picture)
        coVerify(exactly = 0) { relayRepository.subscribeToEventsOnRelay(any(), any(), any()) }
        coVerify(exactly = 0) { relayRepository.connectToRelay(any()) }
        coVerify(exactly = 0) { relayRepository.disconnectFromRelay(any()) }
    }

    @Test
    fun `stale Room row is served instantly and refreshed in background`() = runBlocking {
        val stale = userEntity(npubA, picture = "https://img.example/old.png", lastUpdated = System.currentTimeMillis() - 25 * 3600_000L)
        coEvery { userDao.getUserByNpub(npubA) } returns stale
        every { eventProcessor.metadataArrivals } returns arrivalsOf(npubA)

        val profile = resolver().resolve(hexA)

        // The stale snapshot is returned synchronously…
        assertEquals("https://img.example/old.png", profile?.picture)
        // …while the background refresh hits the pooled relay and marks the row fresh.
        coVerify(timeout = 2_000L) { relayRepository.subscribeToEventsOnRelay(any(), any(), desent) }
        coVerify(timeout = 2_000L) { userDao.touchLastUpdated(npubA, any()) }
        // The service relay is pooled — never torn down.
        coVerify(exactly = 0) { relayRepository.disconnectFromRelay(desent) }
    }

    @Test
    fun `placeholder-only Room row does not satisfy the cache and hits relays`() = runBlocking {
        // Row without any real profile field (login placeholder) must not be served.
        coEvery { userDao.getUserByNpub(npubA) } returnsMany listOf(
            userEntity(npubA, picture = null, lastUpdated = System.currentTimeMillis()),
            userEntity(npubA) // read-back after relay arrival
        )
        every { eventProcessor.metadataArrivals } returns arrivalsOf(npubA)

        val profile = resolver().resolve(hexA)

        assertEquals("https://img.example/p.png", profile?.picture)
        coVerify(exactly = 1) { relayRepository.subscribeToEventsOnRelay(any(), any(), desent) }
        coVerify(exactly = 0) { relayRepository.connectToRelay(any()) }
    }

    // ==================== Relay fan-out + teardown ====================

    @Test
    fun `missing row falls through a silent relay to the next one`() = runBlocking {
        // Cache-check read null; read-back after the yadha arrival returns the row.
        coEvery { userDao.getUserByNpub(npubA) } returnsMany listOf(null, userEntity(npubA))
        val arrivals = MutableSharedFlow<String>()
        every { eventProcessor.metadataArrivals } returns arrivals

        val yadhaSubscribed = CompletableDeferred<Unit>()
        coEvery { relayRepository.subscribeToEventsOnRelay(any(), any(), yadha) } coAnswers {
            yadhaSubscribed.complete(Unit)
        }

        val resolved = coroutineScope {
            val job = async { resolver().resolve(hexA) }
            // desent accepts the REQ but never yields the npub (silent → timeout).
            yadhaSubscribed.await()
            arrivals.emit(npubA)
            job.await()
        }

        assertEquals("https://img.example/p.png", resolved?.picture)
        // First relay queried (and its subscription closed)…
        coVerify(exactly = 1) { relayRepository.subscribeToEventsOnRelay(any(), any(), desent) }
        // …second relay connected on demand and torn down after the lookup.
        coVerify(exactly = 1) { relayRepository.connectToRelay(yadha) }
        coVerify(exactly = 1) { relayRepository.disconnectFromRelay(yadha) }
        coVerify(exactly = 0) { relayRepository.disconnectFromRelay(desent) }
    }

    @Test
    fun `concurrent lookups share one temp connection and tear it down at idle`() = runBlocking {
        // The pooled relay is unreachable so both lookups gate on the temp relay.
        coEvery { relayRepository.subscribeToEventsOnRelay(any(), any(), desent) } throws Exception("Not connected")
        every { eventProcessor.metadataArrivals } returns arrivalsOf(npubA, npubB)
        coEvery { userDao.getUserByNpub(npubA) } returnsMany listOf(null, userEntity(npubA))
        coEvery { userDao.getUserByNpub(npubB) } returnsMany listOf(null, userEntity(npubB))

        val firstSubscribeSeen = CompletableDeferred<Unit>()
        val subscribeCalls = AtomicInteger(0)
        coEvery { relayRepository.subscribeToEventsOnRelay(any(), any(), yadha) } coAnswers {
            if (subscribeCalls.incrementAndGet() == 1) {
                firstSubscribeSeen.complete(Unit)
                // Hold the first subscriber until the second one is in —
                // guarantees both lookups are inside withTempRelay together.
                while (subscribeCalls.get() < 2) kotlinx.coroutines.delay(5)
            }
        }

        coroutineScope {
            // ONE resolver — the ref-count map and semaphore must be shared
            // for the connection sharing to apply.
            val shared = resolver()
            val first = async { shared.resolve(hexA) }
            firstSubscribeSeen.await() // first lookup holds the temp connection
            val second = async { shared.resolve(hexB) }
            assertNotNull(first.await())
            assertNotNull(second.await())
        }

        // One socket shared by both lookups, disconnected once at idle.
        coVerify(exactly = 1) { relayRepository.connectToRelay(yadha) }
        coVerify(exactly = 1) { relayRepository.disconnectFromRelay(yadha) }
    }

    // ==================== NIP-05 identifier path ====================

    @Test
    fun `identifier resolves via NIP-05 and persists the link`() = runBlocking {
        val identifier = "Alice@Example.com"
        coEvery { nip05Service.fetchNip05Record("alice@example.com") } returns Nip05VerificationService.Nip05Record(
            nip05 = "alice@example.com",
            npub = npubA,
            hexPubkey = hexA,
            relays = emptyList()
        )
        coEvery { userDao.getUserByNpub(npubA) } returns userEntity(npubA)

        val profile = resolver().resolveByIdentifier(identifier)

        assertEquals("https://img.example/p.png", profile?.picture)
        val linkSlot = slot<ContactProfileLinkEntity>()
        coVerify { linkDao.upsert(capture(linkSlot)) }
        assertEquals("alice@example.com", linkSlot.captured.identifier)
        assertEquals(hexA, linkSlot.captured.hexPubkey)
        assertTrue(linkSlot.captured.resolvedAt > 0)
        // The linked pubkey's fresh Room row means zero relay traffic.
        coVerify(exactly = 0) { relayRepository.subscribeToEventsOnRelay(any(), any(), any()) }
    }

    @Test
    fun `fresh persisted link skips the NIP-05 fetch`() = runBlocking {
        coEvery { linkDao.getByIdentifier("bob@example.com") } returns ContactProfileLinkEntity(
            identifier = "bob@example.com",
            hexPubkey = hexA,
            resolvedAt = System.currentTimeMillis()
        )
        coEvery { userDao.getUserByNpub(npubA) } returns userEntity(npubA)

        val profile = resolver().resolveByIdentifier("bob@example.com")

        assertEquals("https://img.example/p.png", profile?.picture)
        coVerify(exactly = 0) { nip05Service.fetchNip05Record(any()) }
        coVerify(exactly = 0) { relayRepository.subscribeToEventsOnRelay(any(), any(), any()) }
    }

    @Test
    fun `stale link serves the linked profile and re-verifies in background`() = runBlocking {
        coEvery { linkDao.getByIdentifier("bob@example.com") } returns ContactProfileLinkEntity(
            identifier = "bob@example.com",
            hexPubkey = hexA,
            resolvedAt = System.currentTimeMillis() - 25 * 3600_000L
        )
        coEvery { userDao.getUserByNpub(npubA) } returns userEntity(npubA)
        coEvery { nip05Service.fetchNip05Record("bob@example.com") } returns Nip05VerificationService.Nip05Record(
            nip05 = "bob@example.com",
            npub = npubA,
            hexPubkey = hexA,
            relays = emptyList()
        )

        val profile = resolver().resolveByIdentifier("bob@example.com")

        // Linked profile served synchronously…
        assertEquals("https://img.example/p.png", profile?.picture)
        // …re-verification happens behind the call.
        coVerify(timeout = 2_000L) { nip05Service.fetchNip05Record("bob@example.com") }
    }

    @Test
    fun `unresolvable identifier is negative-cached`() = runBlocking {
        coEvery { nip05Service.fetchNip05Record("nobody@example.com") } returns null

        val resolver = resolver()
        assertNull(resolver.resolveByIdentifier("nobody@example.com"))
        assertNull(resolver.resolveByIdentifier("nobody@example.com"))

        coVerify(exactly = 1) { nip05Service.fetchNip05Record("nobody@example.com") }
    }

    @Test
    fun `non-hex pubkey and non-identifier inputs return null without work`() = runBlocking {
        val resolver = resolver()
        assertNull(resolver.resolve("not-a-hex"))
        assertNull(resolver.resolveByIdentifier("no-at-sign"))
        coVerify(exactly = 0) { relayRepository.subscribeToEventsOnRelay(any(), any(), any()) }
        coVerify(exactly = 0) { nip05Service.fetchNip05Record(any()) }
    }
}
