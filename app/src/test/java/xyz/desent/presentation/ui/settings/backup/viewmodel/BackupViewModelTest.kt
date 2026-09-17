package xyz.desent.presentation.ui.settings.backup.viewmodel

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.domain.model.Account
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.usecase.ExportBackupUseCase

/**
 * First-load default selection of the backup wizard: select-all from the
 * Settings entry point, single-account preselection from the account
 * switcher's per-account section, and unknown preselection falling back to
 * all.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val alice = account("npub1alice", "Alice")
    private val bob = account("npub1bob", "Bob")

    private lateinit var accountRepository: AccountRepository
    private val accountsFlow = MutableStateFlow<List<Account>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        accountRepository = mockk()
        every { accountRepository.observeAllAccounts() } returns accountsFlow
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm(preselectNpub: String? = null): BackupViewModel {
        accountsFlow.value = emptyList() // Room may emit an empty list first
        val vm = BackupViewModel(
            exportBackupUseCase = mockk(relaxed = true),
            accountRepository = accountRepository,
            context = mockk<Context>(relaxed = true),
            secureKeyManager = mockk(relaxed = true),
            preselectNpub = preselectNpub
        )
        accountsFlow.value = listOf(alice, bob)
        return vm
    }

    @Test
    fun withoutPreselect_selectsAllAccounts() = runTest(mainDispatcher) {
        val vm = makeVm()

        assertEquals(setOf("npub1alice", "npub1bob"), vm.uiState.value.selectedNpubs)
    }

    @Test
    fun withPreselect_selectsOnlyThatAccount() = runTest(mainDispatcher) {
        val vm = makeVm(preselectNpub = "npub1bob")

        assertEquals(setOf("npub1bob"), vm.uiState.value.selectedNpubs)
    }

    @Test
    fun unknownPreselect_fallsBackToAll() = runTest(mainDispatcher) {
        val vm = makeVm(preselectNpub = "npub1gone")

        assertEquals(setOf("npub1alice", "npub1bob"), vm.uiState.value.selectedNpubs)
    }

    @Test
    fun userToggles_surviveLaterEmissions() = runTest(mainDispatcher) {
        val vm = makeVm(preselectNpub = "npub1alice")
        vm.toggleAccount("npub1bob")

        accountsFlow.value = listOf(alice, bob, account("npub1carol", "Carol"))

        // Preserved: still alice+bob (carol not auto-added).
        assertEquals(setOf("npub1alice", "npub1bob"), vm.uiState.value.selectedNpubs)
    }

    private fun account(npub: String, name: String) = Account(
        npub = npub,
        displayName = name,
        picture = null,
        nip05 = null,
        lastActiveAt = 1L,
        addedAt = 1L
    )
}
