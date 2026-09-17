package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.directory.RelayDirectoryClient
import xyz.desent.data.fanout.FanoutClient
import xyz.desent.data.local.preferences.SecurityConfigStore
import xyz.desent.data.nip11.Nip11MetadataService
import xyz.desent.domain.model.BackupFetchResult
import xyz.desent.domain.model.BackupFetchStatus
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.RelayListMarker
import xyz.desent.domain.model.SecurityConfig

/**
 * [FanoutRepositoryImpl] orchestration: canonicalization on publish and the
 * backup-fetch URL filter (skip desent.xyz / non-wss; dedupe).
 */
class FanoutRepositoryImplTest {

    private lateinit var nostrRepository: NostrRepository
    private lateinit var fanoutClient: FanoutClient
    private lateinit var relayDirectoryClient: RelayDirectoryClient
    private lateinit var nip11MetadataService: Nip11MetadataService
    private lateinit var securityConfigStore: SecurityConfigStore
    private lateinit var repository: FanoutRepositoryImpl

    @Before
    fun setUp() {
        nostrRepository = mockk(relaxed = true)
        fanoutClient = mockk()
        relayDirectoryClient = mockk()
        nip11MetadataService = mockk()
        securityConfigStore = mockk()
        repository = FanoutRepositoryImpl(
            nostrRepository = nostrRepository,
            fanoutClient = fanoutClient,
            relayDirectoryClient = relayDirectoryClient,
            nip11MetadataService = nip11MetadataService,
            securityConfigStore = securityConfigStore
        )
    }

    @Test
    fun `publishRelayList canonicalizes urls and dedupes`() = runBlocking {
        coEvery { nostrRepository.publishRelayList(any()) } returns Result.success(Unit)

        val result = repository.publishRelayList(
            listOf(
                FanoutRelayEntry(url = "wss://relay.example.com/"),
                FanoutRelayEntry(url = "relay.example.com"),           // dup after normalize
                FanoutRelayEntry(url = "wss://other.example.com", marker = RelayListMarker.READ)
            )
        )

        assertTrue(result.isSuccess)
        val published = slot<List<FanoutRelayEntry>>()
        coVerify { nostrRepository.publishRelayList(capture(published)) }
        assertEquals(
            listOf(
                FanoutRelayEntry("wss://relay.example.com", RelayListMarker.READ_WRITE),
                FanoutRelayEntry("wss://other.example.com", RelayListMarker.READ)
            ),
            published.captured
        )
    }

    @Test
    fun `fetchFromBackupRelays skips desent non-wss and duplicates`() = runBlocking {
        coEvery { nostrRepository.fetchGiftWrapsFromBackupRelays(any()) } returns emptyList()

        repository.fetchFromBackupRelays(
            listOf(
                FanoutRelayEntry("wss://desent.xyz"),                  // home relay — live-synced
                FanoutRelayEntry("wss://relay.example.com"),
                FanoutRelayEntry("wss://relay.example.com/"),           // dup after canonicalize
                FanoutRelayEntry("wss://other.example.com", RelayListMarker.READ)
            )
        ).getOrThrow()

        val urls = slot<List<String>>()
        coVerify { nostrRepository.fetchGiftWrapsFromBackupRelays(capture(urls)) }
        assertEquals(
            listOf("wss://relay.example.com", "wss://other.example.com"),
            urls.captured
        )
    }

    @Test
    fun `fetchFromBackupRelays returns the per-relay report`() = runBlocking {
        val report = listOf(
            BackupFetchResult("wss://relay.example.com", BackupFetchStatus.DONE),
            BackupFetchResult("wss://auth.example.com", BackupFetchStatus.AUTH_REQUIRED, "auth-required: please AUTH")
        )
        coEvery { nostrRepository.fetchGiftWrapsFromBackupRelays(any()) } returns report

        val result = repository.fetchFromBackupRelays(
            listOf(FanoutRelayEntry("wss://relay.example.com"), FanoutRelayEntry("wss://auth.example.com"))
        ).getOrThrow()

        assertEquals(report, result)
    }

    @Test
    fun `getHealth maps the wire response to the domain model`() = runBlocking {
        coEvery { fanoutClient.getStatus() } returns Result.success(
            xyz.desent.data.fanout.model.FanoutStatusResponse(
                relays = listOf(
                    xyz.desent.data.fanout.model.FanoutRelayStatusDto(
                        url = "wss://relay.example.com", pending = 1, done = 3, dead = 0
                    )
                ),
                totalPending = 1,
                totalDead = 0
            )
        )

        val health = repository.getHealth().getOrThrow()

        assertEquals(1, health.relays.size)
        assertEquals(1, health.relays[0].pending)
        assertEquals(1, health.totalPending)
    }

    @Test
    fun `fetchRelayInfo prefers the directory detail`() = runBlocking {
        coEvery { relayDirectoryClient.getRelayDetail("wss://relay.example.com") } returns
            Result.success(
                xyz.desent.data.directory.model.DirectoryRelayDto(
                    url = "wss://relay.example.com",
                    name = "Example Relay",
                    icon = "https://relay.example.com/icon.png",
                    rttOpenMs = 424,
                    rttReadMs = 143,
                    uptime7d = 0.99,
                    supportedNips = listOf(1, 9, 17, 59)
                )
            )

        val info = repository.fetchRelayInfo("wss://relay.example.com").getOrThrow()!!

        assertEquals("Example Relay", info.name)
        assertEquals("https://relay.example.com/icon.png", info.iconUrl)
        assertEquals(424L, info.rttOpenMs)
        assertEquals(setOf(1, 9, 17, 59), info.supportedNips)
        assertEquals(xyz.desent.domain.model.FanoutNipSupportStatus.SUPPORTED, info.nipSupport)
        coVerify(exactly = 0) { nip11MetadataService.fetchMetadata(any()) }
    }

    @Test
    fun `fetchRelayInfo falls back to the relay's own NIP-11 doc`() = runBlocking {
        coEvery { relayDirectoryClient.getRelayDetail("wss://other.example.com") } returns
            Result.success(null) // not in the directory
        coEvery { nip11MetadataService.fetchMetadata("wss://other.example.com") } returns
            xyz.desent.domain.model.Nip11Metadata(
                name = "Other Relay",
                supportedNips = listOf(1, 9)
            )

        val info = repository.fetchRelayInfo("wss://other.example.com").getOrThrow()!!

        assertEquals("Other Relay", info.name)
        assertNull(info.rttOpenMs) // NIP-11 carries no probe data
        assertEquals(setOf(1, 9), info.supportedNips)
        assertEquals(
            xyz.desent.domain.model.FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED,
            info.nipSupport
        )
    }

    @Test
    fun `fetchRelayInfo is null when both sources come up empty`() = runBlocking {
        coEvery { relayDirectoryClient.getRelayDetail("wss://ghost.example.com") } returns
            Result.success(null)
        coEvery { nip11MetadataService.fetchMetadata("wss://ghost.example.com") } returns null

        assertNull(repository.fetchRelayInfo("wss://ghost.example.com").getOrThrow())
    }

    @Test
    fun `syncBackupIfEnabled no-ops when the opt-in is off`() = runBlocking {
        coEvery { nostrRepository.getCurrentUserNpub() } returns "npub1abc"
        coEvery { securityConfigStore.get("npub1abc") } returns SecurityConfig(dmFanout = false)

        repository.syncBackupIfEnabled()
        // Fire-and-forget launches on the repo's own scope; give it a beat.
        Thread.sleep(100)

        coVerify(exactly = 0) { nostrRepository.fetchOwnRelayList() }
    }
}
