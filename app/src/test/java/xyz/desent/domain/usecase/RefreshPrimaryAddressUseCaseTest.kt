package xyz.desent.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import nostr.id.Identity
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.database.entity.AccountEntity
import xyz.desent.domain.model.RegisteredAccount
import xyz.desent.domain.repository.RegistrationRepository

class RefreshPrimaryAddressUseCaseTest {

    private lateinit var accountDao: AccountDao
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var registrationRepository: RegistrationRepository
    private lateinit var useCase: RefreshPrimaryAddressUseCase

    private val identity = mockk<Identity>()

    @Before
    fun setUp() {
        accountDao = mockk(relaxed = true)
        secureKeyManager = mockk()
        registrationRepository = mockk()
        useCase = RefreshPrimaryAddressUseCase(accountDao, secureKeyManager, registrationRepository)
        coEvery { secureKeyManager.getIdentityForAccount(any()) } returns Result.success(identity)
    }

    private fun registered(
        emailAddress: String? = null,
        nip05: String? = null,
        local: String? = null
    ): Result<RegisteredAccount?> = Result.success(
        RegisteredAccount(
            local = local,
            displayName = null,
            picture = null,
            about = null,
            tier = null,
            nip05 = nip05,
            emailAddress = emailAddress
        )
    )

    @Test
    fun `email_address wins over nip05 and local`() = runTest {
        coEvery { registrationRepository.getAccount(any()) } returns registered(
            emailAddress = "ada@desent.xyz",
            nip05 = "ada@desent.xyz",
            local = "ada"
        )

        useCase.refreshOne("npub1a")

        coVerify { accountDao.setPrimaryAddress("npub1a", "ada@desent.xyz") }
    }

    @Test
    fun `falls back to desent nip05 when email_address is absent`() = runTest {
        coEvery { registrationRepository.getAccount(any()) } returns registered(
            nip05 = "bob@desent.xyz",
            local = "bob"
        )

        useCase.refreshOne("npub1b")

        coVerify { accountDao.setPrimaryAddress("npub1b", "bob@desent.xyz") }
    }

    @Test
    fun `external nip05 is ignored and local is used`() = runTest {
        coEvery { registrationRepository.getAccount(any()) } returns registered(
            nip05 = "carol@example.com",
            local = "carol"
        )

        useCase.refreshOne("npub1c")

        coVerify { accountDao.setPrimaryAddress("npub1c", "carol@desent.xyz") }
    }

    @Test
    fun `unregistered account keeps the cached value`() = runTest {
        coEvery { registrationRepository.getAccount(any()) } returns Result.success(null)

        useCase.refreshOne("npub1d")

        coVerify(exactly = 0) { accountDao.setPrimaryAddress(any(), any()) }
    }

    @Test
    fun `api failure keeps the cached value and does not throw`() = runTest {
        coEvery { registrationRepository.getAccount(any()) } returns Result.failure(Exception("offline"))

        useCase.refreshOne("npub1e")

        coVerify(exactly = 0) { accountDao.setPrimaryAddress(any(), any()) }
    }

    @Test
    fun `unavailable key skips the api call`() = runTest {
        coEvery { secureKeyManager.getIdentityForAccount("npub1locked") } returns
            Result.failure(Exception("biometric-locked"))

        useCase.refreshOne("npub1locked")

        coVerify(exactly = 0) { registrationRepository.getAccount(any()) }
        coVerify(exactly = 0) { accountDao.setPrimaryAddress(any(), any()) }
    }

    @Test
    fun `no derivable address writes nothing`() = runTest {
        coEvery { registrationRepository.getAccount(any()) } returns registered(
            nip05 = "dave@example.com",
            local = null
        )

        useCase.refreshOne("npub1f")

        coVerify(exactly = 0) { accountDao.setPrimaryAddress(any(), any()) }
    }

    @Test
    fun `refreshAll covers every stored account`() = runTest {
        coEvery { accountDao.getAccounts() } returns listOf(
            AccountEntity("npub1a", null, null, null),
            AccountEntity("npub1b", null, null, null)
        )
        coEvery { registrationRepository.getAccount(any()) } returns registered(emailAddress = "x@desent.xyz")

        useCase.refreshAll()

        coVerify(atLeast = 1) { accountDao.setPrimaryAddress("npub1a", "x@desent.xyz") }
        coVerify(atLeast = 1) { accountDao.setPrimaryAddress("npub1b", "x@desent.xyz") }
    }

    @Test
    fun `refreshAll survives an account list read failure`() = runTest {
        coEvery { accountDao.getAccounts() } throws Exception("db closed")

        useCase.refreshAll() // must not throw

        coVerify(exactly = 0) { registrationRepository.getAccount(any()) }
    }
}
