package xyz.desent.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.data.RelayConfig
import xyz.desent.domain.model.BackupContents
import xyz.desent.domain.model.BackupRelay
import xyz.desent.domain.model.Relay
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.AuthRepository
import xyz.desent.domain.repository.KeyBackupRepository
import xyz.desent.domain.repository.RelayRepository

/**
 * Backup restore must not reintroduce third-party relays into the pool — the
 * app is a consumer of the Nostr network, never a poster (see AGENTS.md).
 */
class ImportBackupUseCaseTest {

    private val keyBackupRepository = mockk<KeyBackupRepository>()
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val relayRepository = mockk<RelayRepository>(relaxed = true)

    private val useCase = ImportBackupUseCase(
        keyBackupRepository, accountRepository, authRepository, relayRepository
    )

    private fun stubContents(relays: List<BackupRelay>) {
        coEvery { keyBackupRepository.decrypt(any(), any()) } returns Result.success(
            BackupContents(createdAt = 1L, accounts = emptyList(), relays = relays)
        )
    }

    @Test
    fun `only the DeSent relay survives a restore`() = runBlocking {
        stubContents(
            listOf(
                BackupRelay("wss://relay.damus.io", isWrite = true),
                BackupRelay("wss://nos.lol"),
                BackupRelay("wss://desent.xyz", isWrite = true)
            )
        )

        val result = useCase.execute("blob".toByteArray(), "passphrase", activateFirst = false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            relayRepository.saveRelays(
                listOf(Relay(url = RelayConfig.EMAIL_RELAY_URL, isWrite = true, isPersistent = true))
            )
        }
    }

    @Test
    fun `legacy DeSent hosts are normalized to the apex relay`() = runBlocking {
        stubContents(listOf(BackupRelay("wss://email.desent.xyz")))

        val result = useCase.execute("blob".toByteArray(), "passphrase", activateFirst = false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            relayRepository.saveRelays(
                listOf(Relay(url = RelayConfig.EMAIL_RELAY_URL, isPersistent = true))
            )
        }
    }

    @Test
    fun `backups containing only third-party relays save nothing`() = runBlocking {
        stubContents(listOf(BackupRelay("wss://relay.primal.net", isWrite = true)))

        val result = useCase.execute("blob".toByteArray(), "passphrase", activateFirst = false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { relayRepository.saveRelays(any()) }
    }
}
