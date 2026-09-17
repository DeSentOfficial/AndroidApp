package xyz.desent.data.relay

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import nostr.event.impl.GenericEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.RelayDao
import xyz.desent.data.local.database.entity.RelayEntity
import xyz.desent.data.mapper.RelayMapper
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.repository.RelayRepositoryImpl

/**
 * Pins the "consumer, not poster" relay policy (see AGENTS.md): EVENT frames
 * may only ever target `wss://desent.xyz`, third-party sockets are REQ-only,
 * and the user's DeSent subscriptions never fan out beyond the DeSent relay.
 */
class RelayRepositoryImplTest {

    private val desent = RelayConfig.EMAIL_RELAY_URL
    private val foreign = "wss://relay.damus.io"

    private lateinit var relayDao: RelayDao
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var repository: TestableRelayRepositoryImpl

    @Before
    fun setUp() {
        relayDao = mockk(relaxed = true)
        secureKeyManager = mockk()
        coEvery { relayDao.getPersistentRelays() } returns emptyList()
        repository = TestableRelayRepositoryImpl(
            relayDao = relayDao,
            relayMapper = mockk(relaxed = true),
            eventProcessor = mockk(relaxed = true),
            secureKeyManager = secureKeyManager
        )
    }

    /** Substitutes per-URL mock clients so routing can be verified per relay. */
    private class TestableRelayRepositoryImpl(
        relayDao: RelayDao,
        relayMapper: RelayMapper,
        eventProcessor: NostrEventProcessor,
        secureKeyManager: SecureKeyManager
    ) : RelayRepositoryImpl(relayDao, relayMapper, eventProcessor, secureKeyManager) {

        val clients = mutableMapOf<String, NostrWebSocketClient>()

        override fun createClient(url: String, readOnly: Boolean): NostrWebSocketClient {
            val client = mockk<NostrWebSocketClient>(relaxed = true)
            every { client.readOnly } returns readOnly
            every { client.isConnected } returns true
            every { client.publishResults } returns MutableSharedFlow()
            every { client.eoseEvents } returns MutableSharedFlow()
            every { client.closedEvents } returns MutableSharedFlow()
            clients[url] = client
            return client
        }
    }

    private fun event(): GenericEvent = mockk(relaxed = true)

    private fun relayEntity(url: String): RelayEntity {
        val entity = mockk<RelayEntity>()
        every { entity.url } returns url
        return entity
    }

    @Test
    fun `third-party clients are created read-only, the DeSent client is not`() = runBlocking {
        repository.connectToRelay(desent)
        repository.connectToRelay(foreign)

        assertFalse(repository.clients.getValue(desent).readOnly)
        assertTrue(repository.clients.getValue(foreign).readOnly)
    }

    @Test
    fun `publishEventToRelay throws for a non-DeSent relay even when connected`() = runBlocking {
        repository.connectToRelay(foreign)

        val error = runCatching { repository.publishEventToRelay(event(), foreign) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        verify(exactly = 0) { repository.clients.getValue(foreign).publish(any()) }
    }

    @Test
    fun `publishEventToRelay delegates to the DeSent client`() = runBlocking {
        repository.connectToRelay(desent)
        val event = event()

        repository.publishEventToRelay(event, desent)

        verify(exactly = 1) { repository.clients.getValue(desent).publish(event) }
    }

    @Test
    fun `broadcast subscriptions skip read-only clients`() = runBlocking {
        repository.connectToRelay(desent)
        repository.connectToRelay(foreign)
        val filters = listOf(mapOf("kinds" to listOf(0)))

        repository.subscribeToEvents(filters, "sub", persistent = true)

        verify(exactly = 1) { repository.clients.getValue(desent).subscribe("sub", filters) }
        verify(exactly = 0) { repository.clients.getValue(foreign).subscribe(any(), any()) }
    }

    @Test
    fun `persistent-subscription replay never reaches third-party relays`() = runBlocking {
        repository.connectToRelay(desent)
        repository.connectToRelay(foreign)
        val filters = listOf(mapOf("authors" to listOf("abc".repeat(8))))
        repository.subscribeToEvents(filters, "own_data", persistent = true)

        repository.replayPersistentSubscriptions(desent)
        repository.replayPersistentSubscriptions(foreign)

        // Broadcast delivered it once, the DeSent replay once more; foreign never.
        verify(exactly = 2) { repository.clients.getValue(desent).subscribe("own_data", filters) }
        verify(exactly = 0) { repository.clients.getValue(foreign).subscribe(any(), any()) }
    }

    @Test
    fun `disconnect clears the persistent-subscription registry`() = runBlocking {
        repository.connectToRelay(desent)
        val filters = listOf(mapOf("kinds" to listOf(1059)))
        repository.subscribeToEvents(filters, "giftwrap_abcd1234", persistent = true)

        // Logout / account switch teardown, then a fresh connection: the
        // previous account's REQs must NOT replay to the new client.
        repository.disconnectFromAllRelays()
        repository.connectToRelay(desent)
        repository.replayPersistentSubscriptions(desent)

        verify(exactly = 0) { repository.clients.getValue(desent).subscribe(any(), any()) }
    }

    @Test
    fun `connectToPersistentRelays ignores foreign isPersistent rows`() = runBlocking {
        coEvery { relayDao.getPersistentRelays() } returns listOf(
            relayEntity(desent),
            relayEntity(foreign)
        )

        repository.connectToPersistentRelays()

        assertEquals(listOf(desent), repository.clients.keys.toList())
    }

    @Test
    fun `NIP-42 AUTH is never answered for third-party relays`() = runBlocking {
        val result = repository.signNip42AuthEvent("challenge", foreign)

        assertNull(result)
        coVerify(exactly = 0) { secureKeyManager.getIdentityFromStoredNSEC() }
    }

    @Test
    fun `NIP-42 AUTH proceeds to key lookup for the DeSent relay`() = runBlocking {
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns
            Result.failure(IllegalStateException("no key"))

        val result = repository.signNip42AuthEvent("challenge", desent)

        // Gate passed; null only because no signing key is stored.
        assertNull(result)
        coVerify(exactly = 1) { secureKeyManager.getIdentityFromStoredNSEC() }
    }
}
