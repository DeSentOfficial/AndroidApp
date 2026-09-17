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
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.SecurityConfigStore
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.model.SecurityConfig

class SecurityConfigRepositoryImplTest {

    private val identity = Identity.create(
        "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    )
    private val ownerNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

    private lateinit var store: SecurityConfigStore
    private lateinit var nostrRepo: NostrRepository
    private lateinit var repo: SecurityConfigRepositoryImpl

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        nostrRepo = mockk(relaxed = true)
        repo = SecurityConfigRepositoryImpl(store, nostrRepo)
    }

    @Test
    fun setAlertMode_publishesPartialPayloadOnly() = runBlocking {
        val contentSlot = slot<String>()
        coEvery { nostrRepo.publishUserSettings(capture(contentSlot)) } returns Result.success(Unit)

        val result = repo.setAlertMode(ownerNpub, SecurityAlertMode.ALWAYS)

        assertTrue(result.isSuccess)
        // Partial payload: the security field ONLY — auto-purge fields must
        // never appear (USER_SETTINGS_PROTOCOL.md § Partial updates; the
        // server schema is closed and rejects unknown/extra expectations).
        assertEquals("""{"security_alerts":"always"}""", contentSlot.captured)
    }

    @Test
    fun setAlertMode_mirrorsLocallyOnSuccess() = runBlocking {
        coEvery { nostrRepo.publishUserSettings(any()) } returns Result.success(Unit)

        repo.setAlertMode(ownerNpub, SecurityAlertMode.OFF)

        val configSlot = slot<SecurityConfig>()
        coVerify { store.save(ownerNpub, capture(configSlot), any()) }
        assertEquals(SecurityAlertMode.OFF, configSlot.captured.alertMode)
    }

    @Test
    fun setAlertMode_failsWithoutLocalMirror_whenPublishFails() = runBlocking {
        coEvery { nostrRepo.publishUserSettings(any()) } returns Result.failure(Exception("relay down"))

        val result = repo.setAlertMode(ownerNpub, SecurityAlertMode.OFF)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { store.save(any(), any(), any()) }
    }

    @Test
    fun refresh_subscribesToOwnUserSettings() = runBlocking {
        val result = repo.refresh()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { nostrRepo.subscribeToOwnUserSettings() }
    }
}
