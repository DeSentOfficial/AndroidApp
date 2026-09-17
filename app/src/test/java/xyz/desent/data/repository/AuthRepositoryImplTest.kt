package xyz.desent.data.repository

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.Nip49
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.registration.NostrLinkClient
import xyz.desent.domain.model.AccountCreationRequest
import xyz.desent.domain.model.CustodialAccountCreationRequest
import xyz.desent.domain.model.CustodialSignupResult
import xyz.desent.domain.model.RegisteredAccount
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.CustodialAccountRepository
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.domain.repository.UserRepository
import xyz.desent.domain.usecase.MediaUploadUseCase
import xyz.desent.domain.usecase.RegistrationUseCase
import xyz.desent.domain.usecase.SwitchAccountUseCase

/**
 * Session activation in [AuthRepositoryImpl].
 *
 * Every path that makes an account active in-process — key-import login and
 * both account-creation flows — must bring the runtime session online
 * (persistent relays, event processor, in-memory identity, gift-wrap
 * subscription). Creation used to skip this, leaving a freshly created
 * account dark until an account switch or a cold start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest {

    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var relayRepository: RelayRepository
    private lateinit var userRepository: UserRepository
    private lateinit var nostrRepository: NostrRepository
    private lateinit var eventProcessor: NostrEventProcessor
    private lateinit var mediaUploadUseCase: MediaUploadUseCase
    private lateinit var registrationUseCase: RegistrationUseCase
    private lateinit var custodialAccountRepository: CustodialAccountRepository
    private lateinit var nostrLinkClient: NostrLinkClient
    private lateinit var accountRepository: AccountRepository
    private lateinit var accountDao: AccountDao
    private lateinit var switchAccountUseCase: SwitchAccountUseCase
    private lateinit var context: Context

    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setUp() {
        secureKeyManager = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        relayRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        nostrRepository = mockk(relaxed = true)
        eventProcessor = mockk(relaxed = true)
        mediaUploadUseCase = mockk(relaxed = true)
        registrationUseCase = mockk(relaxed = true)
        custodialAccountRepository = mockk(relaxed = true)
        nostrLinkClient = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        accountDao = mockk(relaxed = true)
        switchAccountUseCase = mockk(relaxed = true)
        context = mockk(relaxed = true)

        // Result<T> is a value class, so relaxed mocks can't synthesize it —
        // stub every Result-returning collaborator on the exercised paths.
        coEvery { secureKeyManager.generateKeyPair() } returns Result.success(NSEC to NPUB)
        coEvery { secureKeyManager.validateNSEC(NSEC) } returns Result.success(NPUB)
        coEvery { accountRepository.addAccount(any(), any(), any()) } returns Result.success(Unit)
        coEvery { accountRepository.removeAccount(any()) } returns Result.success(Unit)
        coEvery { preferencesManager.getActiveNpub() } returns null
        coEvery { userRepository.getUserByNpub(any()) } returns null
        coEvery { relayRepository.waitForRelayReady(RELAY, any()) } returns true
        coEvery { nostrRepository.restoreIdentity() } returns Result.success(NPUB)
        coEvery {
            nostrRepository.publishUserProfile(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Result.success(Unit)
        coEvery {
            registrationUseCase.register(any(), any(), any(), any(), any(), any())
        } returns Result.success(
            RegisteredAccount(
                local = "alice",
                displayName = "Alice",
                picture = null,
                about = null,
                tier = null,
                nip05 = "alice@desent.xyz"
            )
        )
        coEvery {
            custodialAccountRepository.signup(any(), any(), any(), any(), any())
        } returns Result.success(
            CustodialSignupResult(
                username = "amberfalcon",
                nip05 = "amberfalcon@desent.xyz",
                npub = NPUB
            )
        )

        repository = AuthRepositoryImpl(
            secureKeyManager = secureKeyManager,
            preferencesManager = preferencesManager,
            relayRepository = relayRepository,
            userRepository = userRepository,
            nostrRepository = nostrRepository,
            eventProcessor = eventProcessor,
            mediaUploadUseCase = mediaUploadUseCase,
            registrationUseCase = registrationUseCase,
            custodialAccountRepository = custodialAccountRepository,
            nostrLinkClient = nostrLinkClient,
            accountRepository = accountRepository,
            accountDao = accountDao,
            switchAccountUseCase = switchAccountUseCase
        )
    }

    // ---- account creation --------------------------------------------------

    @Test
    fun createAccount_bringsSessionOnline() = runTest {
        val result = repository.createAccount(
            AccountCreationRequest(
                local = "alice",
                displayName = "Alice",
                about = null,
                pictureUri = null
            ),
            context
        )

        assertTrue(result.isSuccess)
        assertSessionActivated()
    }

    @Test
    fun createAccount_restoresIdentityBeforePublishingProfile() = runTest {
        repository.createAccount(
            AccountCreationRequest(
                local = "alice",
                displayName = "Alice",
                about = null,
                pictureUri = null
            ),
            context
        )

        // publishUserProfile refuses with "Not logged in" while the in-memory
        // identity is unset, so the identity must be restored first.
        coVerifyOrder {
            nostrRepository.restoreIdentity()
            nostrRepository.publishUserProfile(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun createAccount_registrationFailure_rollsBackWithoutActivatingSession() = runTest {
        coEvery {
            registrationUseCase.register(any(), any(), any(), any(), any(), any())
        } returns Result.failure(Exception("address taken"))

        val result = repository.createAccount(
            AccountCreationRequest(
                local = "alice",
                displayName = "Alice",
                about = null,
                pictureUri = null
            ),
            context
        )

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { accountRepository.removeAccount(NPUB) }
        coVerify(exactly = 0) { nostrRepository.restoreIdentity() }
        coVerify(exactly = 0) { nostrRepository.subscribeToGiftWraps() }
        coVerify(exactly = 0) { relayRepository.connectToPersistentRelays() }
        coVerify(exactly = 0) { relayRepository.disconnectFromAllRelays() }
    }

    // ---- custodial creation ------------------------------------------------

    @Test
    fun createCustodialAccount_bringsSessionOnline() = runTest {
        val result = repository.createCustodialAccount(
            CustodialAccountCreationRequest(
                username = "amberfalcon",
                password = "correct horse battery staple",
                displayName = "Amber"
            ),
            context
        )

        assertTrue(result.isSuccess)
        assertSessionActivated()
        coVerifyOrder {
            nostrRepository.restoreIdentity()
            nostrRepository.publishUserProfile(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun createCustodialAccount_signupFailure_rollsBackWithoutActivatingSession() = runTest {
        coEvery {
            custodialAccountRepository.signup(any(), any(), any(), any(), any())
        } returns Result.failure(Exception("username taken"))

        val result = repository.createCustodialAccount(
            CustodialAccountCreationRequest(
                username = "amberfalcon",
                password = "correct horse battery staple"
            ),
            context
        )

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { accountRepository.removeAccount(NPUB) }
        coVerify(exactly = 0) { nostrRepository.restoreIdentity() }
        coVerify(exactly = 0) { relayRepository.connectToPersistentRelays() }
        coVerify(exactly = 0) { relayRepository.disconnectFromAllRelays() }
    }

    // ---- pre-account vanity checkout (CUSTODIAL_ACCOUNTS.md §4.1) -----------

    @Test
    fun prepareSignupKey_generatesWithoutStoringOrActivating() = runTest {
        coEvery { secureKeyManager.generateKeyPair() } returns
            Result.success(PREPARED_NSEC to PREPARED_NPUB)

        val result = repository.prepareSignupKey()

        assertTrue(result.isSuccess)
        assertEquals(PREPARED_NPUB, result.getOrThrow().npub)
        // Memory-only: no account row, no active slot, no legacy mirror.
        coVerify(exactly = 0) { accountRepository.addAccount(any(), any(), any()) }
        coVerify(exactly = 0) { preferencesManager.saveNpubKey(any()) }
        coVerify(exactly = 0) { accountDao.setLastActiveAt(any(), any()) }
    }

    @Test
    fun createAccount_withExistingNsec_reusesKeyWithoutGenerating() = runTest {
        coEvery { secureKeyManager.validateNSEC(PREPARED_NSEC) } returns
            Result.success(PREPARED_NPUB)

        val result = repository.createAccount(
            AccountCreationRequest(
                local = "alice",
                displayName = "Alice",
                about = null,
                pictureUri = null
            ),
            context,
            existingNsec = PREPARED_NSEC
        )

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { secureKeyManager.generateKeyPair() }
        coVerify(exactly = 1) { secureKeyManager.validateNSEC(PREPARED_NSEC) }
        // The stored account IS the key that paid the vanity approval.
        coVerify(exactly = 1) { accountRepository.addAccount(PREPARED_NPUB, PREPARED_NSEC, any()) }
    }

    @Test
    fun createCustodialAccount_withExistingNsec_reusesKeyWithoutGenerating() = runTest {
        coEvery { secureKeyManager.validateNSEC(PREPARED_NSEC) } returns
            Result.success(PREPARED_NPUB)

        val result = repository.createCustodialAccount(
            CustodialAccountCreationRequest(
                username = "amberfalcon",
                password = "correct horse battery staple"
            ),
            context,
            existingNsec = PREPARED_NSEC
        )

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { secureKeyManager.generateKeyPair() }
        // The blob is built from the REUSED key, not a fresh one.
        coVerify(exactly = 1) {
            custodialAccountRepository.signup(any(), any(), PREPARED_NSEC, any(), any())
        }
    }

    // ---- key-import login (unchanged behaviour, guards the refactor) --------

    @Test
    fun login_bringsSessionOnline() = runTest {
        val result = repository.login(NSEC, rememberMe = true, enableBiometrics = false)

        assertTrue(result.isSuccess)
        assertSessionActivated()
    }

    // ---- ncryptsec (NIP-49) login -------------------------------------------

    @Test
    fun loginWithNcryptsec_success_provisionsDecryptedKeyThroughLoginPipeline() = runTest {
        val rawKey = ByteArray(32) { it.toByte() }
        // log_n = 10 keeps the scrypt derivation test-fast; the wire format
        // (and the log_n field itself) is what matters here.
        val ncryptsec = Nip49.encrypt(rawKey, "correct horse", logN = 10)
        val nsecSlot = slot<String>()
        coEvery { secureKeyManager.validateNSEC(capture(nsecSlot)) } returns Result.success(NPUB)

        val result = repository.loginWithNcryptsec(ncryptsec, "correct horse", true, false)

        assertTrue(result.isSuccess)
        assertEquals(NPUB, result.getOrThrow())
        // The key handed down to the ordinary pipeline decodes back to
        // exactly the secret that was encrypted.
        assertEquals(Nip44Encryption.bytesToHex(rawKey), Bech32Utils.nsecToHex(nsecSlot.captured))
        assertSessionActivated()
    }

    @Test
    fun loginWithNcryptsec_wrongPassword_failsTyped_withoutProvisioning() = runTest {
        val ncryptsec = Nip49.encrypt(ByteArray(32) { it.toByte() }, "right password", logN = 10)

        val result = repository.loginWithNcryptsec(ncryptsec, "wrong password", true, false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is Nip49.WrongPasswordException)
        coVerify(exactly = 0) { secureKeyManager.validateNSEC(any()) }
        coVerify(exactly = 0) { accountRepository.addAccount(any(), any(), any()) }
    }

    @Test
    fun loginWithNcryptsec_malformedInput_failsTyped() = runTest {
        val result = repository.loginWithNcryptsec("ncryptsec1garbage", "pw", true, false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is Nip49.MalformedNcryptsecException)
    }

    /**
     * The session-activation contract every success path must honour. The
     * relay socket is torn down before reconnecting: NIP-42 AUTH is one-shot
     * per connection and the pre-login socket's challenge went unanswered, so
     * reusing it would leave every identity-scoped REQ buffered forever.
     */
    private fun assertSessionActivated() {
        coVerify(exactly = 1) { relayRepository.disconnectFromAllRelays() }
        coVerify(exactly = 1) { relayRepository.connectToPersistentRelays() }
        coVerify(exactly = 1) { relayRepository.waitForRelayReady(RELAY, any()) }
        verify(exactly = 1) { eventProcessor.startProcessing() }
        coVerify(exactly = 1) { nostrRepository.restoreIdentity() }
        coVerify(exactly = 1) { nostrRepository.subscribeToGiftWraps() }
        coVerifyOrder {
            relayRepository.disconnectFromAllRelays()
            relayRepository.connectToPersistentRelays()
            relayRepository.waitForRelayReady(RELAY, any())
            nostrRepository.restoreIdentity()
            nostrRepository.subscribeToGiftWraps()
        }
    }

    private companion object {
        const val NSEC = "nsec1testkey"
        const val NPUB = "npub1testaccount0000000000000000000000000000000000000000000000000"
        const val RELAY = "wss://desent.xyz"

        /** Real bech32 nsec over a fixed scalar — prepareSignupKey decodes it. */
        val PREPARED_NSEC = Bech32Utils.hexToNsec("aa".repeat(32))
        const val PREPARED_NPUB = "npub1preparedkey00000000000000000000000000000000000000000000"
    }
}
