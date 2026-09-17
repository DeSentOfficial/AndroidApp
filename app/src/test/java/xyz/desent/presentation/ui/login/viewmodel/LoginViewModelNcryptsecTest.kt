package xyz.desent.presentation.ui.login.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Nip49
import xyz.desent.domain.repository.CustodialAccountRepository
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.RefreshPrimaryAddressUseCase
import xyz.desent.domain.usecase.RegistrationUseCase

/**
 * ncryptsec (NIP-49) two-phase key login in [LoginViewModel]: Continue on an
 * `ncryptsec1…` paste (or QR scan) switches to a password phase — never the
 * linked-key probe, which needs a plaintext signing key — and Decrypt & Sign
 * In provisions through the ordinary import path. The key password lives in
 * memory only and is cleared as soon as it is no longer needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelNcryptsecTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var authUseCase: AuthUseCase
    private lateinit var registrationUseCase: RegistrationUseCase
    private lateinit var custodialAccountRepository: CustodialAccountRepository
    private lateinit var refreshPrimaryAddressUseCase: RefreshPrimaryAddressUseCase

    // log_n = 10 keeps the scrypt derivation test-fast; the ViewModel only
    // needs a structurally valid string (its pre-check parses without the KDF).
    private val ncryptsec = Nip49.encrypt(ByteArray(32) { it.toByte() }, "key password", logN = 10)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        authUseCase = mockk()
        registrationUseCase = mockk()
        custodialAccountRepository = mockk()
        refreshPrimaryAddressUseCase = mockk(relaxed = true)

        // getRegistrationMode returns an inline value class, which relaxed
        // mocks cannot synthesize — and the ViewModel polls it on entry.
        coEvery { registrationUseCase.getRegistrationMode() } returns
            Result.success(xyz.desent.domain.model.RegistrationMode.OPEN)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm() = LoginViewModel(
        authUseCase,
        registrationUseCase,
        custodialAccountRepository,
        refreshPrimaryAddressUseCase,
        mockk(relaxed = true),
        mockk(relaxed = true)
    )

    private fun kotlinx.coroutines.test.TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    // ---- phase switch ------------------------------------------------------

    @Test
    fun continueWithNcryptsec_switchesToPasswordPhase_withoutProbingOrImporting() =
        runTest(mainDispatcher) {
            val vm = makeVm()
            vm.onNsecChange("  $ncryptsec  ") // pastes carry stray whitespace

            vm.onLogin()

            assertTrue(vm.uiState.value.ncryptsecPrompt)
            assertFalse(vm.uiState.value.isLoading)
            coVerify(exactly = 0) { authUseCase.probeLinkedNostrAccount(any()) }
            coVerify(exactly = 0) { authUseCase.login(any(), any(), any()) }
        }

    @Test
    fun qrScannedNcryptsec_continuesIntoPasswordPhase() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.onQrCodeScanned(ncryptsec)
        vm.onLogin()

        assertTrue(vm.uiState.value.ncryptsecPrompt)
    }

    @Test
    fun continueWithMalformedNcryptsec_showsError_withoutPhaseSwitch() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.onNsecChange("ncryptsec1garbage")

        vm.onLogin()

        assertFalse(vm.uiState.value.ncryptsecPrompt)
        assertEquals("This doesn't look like a valid ncryptsec key", vm.uiState.value.error)
        coVerify(exactly = 0) { authUseCase.loginWithNcryptsec(any(), any(), any(), any()) }
    }

    // ---- decrypt & sign in -------------------------------------------------

    @Test
    fun ncryptsecLogin_success_setsLoginSuccess_andClearsSecrets() = runTest(mainDispatcher) {
        coEvery { authUseCase.loginWithNcryptsec(ncryptsec, "key password", false, false) } returns
            Result.success("npub1abc")
        val vm = makeVm()

        vm.onNsecChange(ncryptsec)
        vm.onLogin()
        vm.onNcryptsecPasswordChange("key password")
        vm.onNcryptsecLogin()
        settle()

        assertTrue(vm.uiState.value.isLoginSuccess)
        assertFalse(vm.uiState.value.ncryptsecPrompt)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("", vm.ncryptsecPassword)
        assertEquals("", vm.nsecInput)
        coVerify(exactly = 1) { authUseCase.loginWithNcryptsec(ncryptsec, "key password", false, false) }
        coVerify(exactly = 1) { refreshPrimaryAddressUseCase.refreshOne("npub1abc") }
    }

    @Test
    fun ncryptsecLogin_wrongPassword_staysInPhaseWithTypedMessage() = runTest(mainDispatcher) {
        coEvery { authUseCase.loginWithNcryptsec(any(), any(), any(), any()) } returns
            Result.failure(Nip49.WrongPasswordException())
        val vm = makeVm()
        vm.onNsecChange(ncryptsec)
        vm.onLogin()
        vm.onNcryptsecPasswordChange("typo")

        vm.onNcryptsecLogin()
        settle()

        // The user likely has a typo: keep the phase (and entry) for a retry.
        assertTrue(vm.uiState.value.ncryptsecPrompt)
        assertFalse(vm.uiState.value.isLoginSuccess)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(
            "Incorrect password — this is the password the key was encrypted with",
            vm.uiState.value.error
        )
    }

    @Test
    fun ncryptsecLogin_malformedOnDecrypt_resetsToKeyEntry() = runTest(mainDispatcher) {
        coEvery { authUseCase.loginWithNcryptsec(any(), any(), any(), any()) } returns
            Result.failure(Nip49.MalformedNcryptsecException("corrupted"))
        val vm = makeVm()
        vm.onNsecChange(ncryptsec)
        vm.onLogin()
        vm.onNcryptsecPasswordChange("key password")

        vm.onNcryptsecLogin()
        settle()

        // Structural problems mean the paste itself is bad: back to the key
        // field rather than a password dead end.
        assertFalse(vm.uiState.value.ncryptsecPrompt)
        assertEquals("", vm.ncryptsecPassword)
        assertFalse(vm.uiState.value.isLoginSuccess)
    }

    @Test
    fun ncryptsecLogin_blankPassword_showsError_withoutCallingRepository() =
        runTest(mainDispatcher) {
            val vm = makeVm()
            vm.onNsecChange(ncryptsec)
            vm.onLogin()

            vm.onNcryptsecLogin()

            assertEquals(
                "Please enter the password this key was encrypted with",
                vm.uiState.value.error
            )
            coVerify(exactly = 0) { authUseCase.loginWithNcryptsec(any(), any(), any(), any()) }
        }

    @Test
    fun onNcryptsecLogin_withoutPhase_isNoop() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.onNcryptsecPasswordChange("pw")

        vm.onNcryptsecLogin()
        settle()

        assertFalse(vm.uiState.value.isLoginSuccess)
        coVerify(exactly = 0) { authUseCase.loginWithNcryptsec(any(), any(), any(), any()) }
    }

    // ---- cancel ------------------------------------------------------------

    @Test
    fun cancelNcryptsecLogin_returnsToKeyEntry_keepingThePaste() = runTest(mainDispatcher) {
        val vm = makeVm()
        vm.onNsecChange(ncryptsec)
        vm.onLogin()
        vm.onNcryptsecPasswordChange("typed")

        vm.cancelNcryptsecLogin()

        assertFalse(vm.uiState.value.ncryptsecPrompt)
        assertEquals("", vm.ncryptsecPassword)
        // The pasted key stays so "Use a different key" doesn't force a re-paste.
        assertEquals(ncryptsec, vm.nsecInput)
    }
}
