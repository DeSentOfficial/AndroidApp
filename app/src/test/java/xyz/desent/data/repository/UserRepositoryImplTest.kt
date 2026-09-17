package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.local.database.entity.UserEntity
import xyz.desent.data.mapper.UserMapper
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.domain.repository.RelayRepository

class UserRepositoryImplTest {

    private lateinit var userDao: UserDao
    private lateinit var relayRepository: RelayRepository
    private lateinit var eventProcessor: NostrEventProcessor
    private lateinit var repo: UserRepositoryImpl

    private val pubKeyHex = "bfa42f4e9335b290c107f39db95465f5dc24fcd12f1733c1a617028ca98c9b4c"
    private lateinit var npub: String
    private lateinit var subId: String

    private val eoseEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    private val metadataArrivals = MutableSharedFlow<String>(extraBufferCapacity = 64)

    @Before
    fun setUp() {
        userDao = mockk(relaxed = true)
        relayRepository = mockk(relaxed = true)
        eventProcessor = mockk(relaxed = true)

        npub = Bech32Utils.hexToNpub(pubKeyHex)
        subId = "user_${pubKeyHex.take(8)}"

        every { relayRepository.eoseEvents } returns eoseEvents
        every { eventProcessor.metadataArrivals } returns metadataArrivals
        coEvery { relayRepository.getConnectedRelays() } returns listOf("wss://a", "wss://b")

        repo = UserRepositoryImpl(
            userDao = userDao,
            userMapper = UserMapper(),
            relayRepository = relayRepository,
            nostrEventProcessor = eventProcessor
        )
    }

    @Test
    fun fetchUserFromRelays_failsFastWhenAllRelaysEoseWithoutEvents() = runBlocking {
        // The old behaviour idled to a fixed timeout even though every relay
        // had already answered EOSE ("nothing stored") — this stalled the DM
        // roster by 8s per uncached contact. The EOSE race must resolve in
        // ~relay-RTT, long before the 3s backstop.
        coEvery { relayRepository.subscribeToEvents(any(), any(), any()) } coAnswers {
            eoseEvents.tryEmit(subId to "wss://a")
            eoseEvents.tryEmit(subId to "wss://b")
        }
        coEvery { userDao.getUserByNpub(npub) } returns null

        val start = System.currentTimeMillis()
        val result = repo.fetchUserFromRelays(npub)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result.isFailure)
        assertTrue(
            "EOSE race must not idle to the 3s backstop (took ${elapsed}ms)",
            elapsed < 2500
        )
        coVerify(exactly = 1) { relayRepository.unsubscribeFromEvents(subId) }
    }

    @Test
    fun fetchUserFromRelays_ignoresPartialEoseWhenMetadataArrives() = runBlocking {
        // One relay EOSEs, then a kind-0 lands — the arrival must win over
        // the (incomplete) EOSE count.
        coEvery { relayRepository.subscribeToEvents(any(), any(), any()) } coAnswers {
            eoseEvents.tryEmit(subId to "wss://a")
            metadataArrivals.tryEmit(npub)
        }
        coEvery { userDao.getUserByNpub(npub) } returns userEntity()

        val result = repo.fetchUserFromRelays(npub)

        assertTrue(result.isSuccess)
        assertEquals("alice", result.getOrNull()?.name)
        coVerify(exactly = 1) { relayRepository.unsubscribeFromEvents(subId) }
    }

    @Test
    fun fetchUserFromRelays_failsImmediatelyWhenNoRelaysConnected() = runBlocking {
        coEvery { relayRepository.getConnectedRelays() } returns emptyList()

        val result = repo.fetchUserFromRelays(npub)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { relayRepository.subscribeToEvents(any(), any(), any()) }
    }

    @Test
    fun fetchUserFromRelays_timeoutStillReturnsCachedUser() = runBlocking {
        // Backstop path (relay never answers): the kind-0 may still have been
        // stored this session via another subscription — the processor dedups
        // repeat event ids and wouldn't re-emit, so the DB must be checked.
        coEvery { userDao.getUserByNpub(npub) } returns userEntity()

        val result = repo.fetchUserFromRelays(npub)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { relayRepository.unsubscribeFromEvents(subId) }
    }

    private fun userEntity() = UserEntity(
        npub = npub,
        name = "alice",
        displayName = "Alice",
        about = null,
        picture = null,
        nip05 = null,
        createdAt = 1L
    )
}
