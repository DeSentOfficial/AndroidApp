package xyz.desent.presentation.ui.security.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.dao.SecurityAlertDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.model.SecurityConfig
import xyz.desent.domain.usecase.SecurityConfigUseCase

/**
 * Account scoping of the Security alerts screen: an [SecurityAlertsViewModel]
 * created with `ownerNpubOverride` (arriving from the account switcher's
 * per-account section) reads that account's alerts, but the kind-30079
 * publish path signs with the ACTIVE identity — so the mode must be
 * read-only until the user switches accounts.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecurityAlertsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val activeNpub = "npub1active"
    private val otherNpub = "npub1other"

    private lateinit var dao: SecurityAlertDao
    private lateinit var useCase: SecurityConfigUseCase
    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        dao = mockk(relaxed = true)
        useCase = mockk()
        prefs = mockk()

        every { prefs.npubKey } returns flowOf(activeNpub)
        every { dao.observeForOwner(any()) } returns MutableStateFlow(emptyList())
        every { dao.observeUnseenCount(any()) } returns MutableStateFlow(0)
        every { useCase.observe(any()) } returns MutableStateFlow<SecurityConfig?>(null)
        coEvery { useCase.refresh() } returns Result.success(Unit)
        coEvery { useCase.setAlertMode(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm(ownerNpubOverride: String? = null) =
        SecurityAlertsViewModel(dao, useCase, prefs, null, ownerNpubOverride)

    @Test
    fun withoutOverride_observesActiveAccount() = runTest(mainDispatcher) {
        val vm = makeVm()

        coVerify { dao.observeForOwner(activeNpub) }
        assertTrue(vm.uiState.value.modeEditable)
    }

    @Test
    fun withOverride_observesThatAccountOnly() = runTest(mainDispatcher) {
        val vm = makeVm(ownerNpubOverride = otherNpub)

        coVerify(exactly = 1) { dao.observeForOwner(otherNpub) }
        coVerify(exactly = 0) { dao.observeForOwner(activeNpub) }
        assertFalse(vm.uiState.value.modeEditable)
    }

    @Test
    fun withOverride_skipsRelayRefreshForNonActiveAccount() = runTest(mainDispatcher) {
        makeVm(ownerNpubOverride = otherNpub)

        // The 30079 subscription follows the ACTIVE identity — no point
        // refreshing while viewing someone else's alerts.
        coVerify(exactly = 0) { useCase.refresh() }
    }

    @Test
    fun setAlertMode_refusedWhileViewingOtherAccount() = runTest(mainDispatcher) {
        every { useCase.observe(otherNpub) } returns
            MutableStateFlow(SecurityConfig(alertMode = SecurityAlertMode.OFF))
        val vm = makeVm(ownerNpubOverride = otherNpub)

        vm.setAlertMode(SecurityAlertMode.ALWAYS)

        coVerify(exactly = 0) { useCase.setAlertMode(any(), any()) }
        assertFalse(vm.uiState.value.isPublishing)
        assertNotNull(vm.uiState.value.toast)
    }

    @Test
    fun setAlertMode_publishesForActiveAccount() = runTest(mainDispatcher) {
        every { useCase.observe(activeNpub) } returns
            MutableStateFlow(SecurityConfig(alertMode = SecurityAlertMode.OFF))
        val vm = makeVm()

        vm.setAlertMode(SecurityAlertMode.ALWAYS)

        coVerify { useCase.setAlertMode(activeNpub, SecurityAlertMode.ALWAYS) }
        assertEquals("Alert preference updated", vm.uiState.value.toast)
    }

    @Test
    fun markAllSeen_targetsViewedAccount() = runTest(mainDispatcher) {
        val vm = makeVm(ownerNpubOverride = otherNpub)

        vm.markAllSeen()

        coVerify { dao.markAllSeen(otherNpub) }
    }
}
