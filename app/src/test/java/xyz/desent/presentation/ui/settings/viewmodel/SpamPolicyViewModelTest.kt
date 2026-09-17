package xyz.desent.presentation.ui.settings.viewmodel

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamListManifest
import xyz.desent.domain.repository.PrivateStorageRepository
import xyz.desent.domain.repository.SpamFilterRepository

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpamPolicyViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var repo: SpamFilterRepository
    private lateinit var privateRepo: PrivateStorageRepository
    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        repo = mockk(relaxed = true)
        privateRepo = mockk(relaxed = true)
        prefs = mockk(relaxed = true)

        every { repo.observeConfig() } returns MutableStateFlow(SpamFilterConfig())
        every { repo.observeManifest() } returns flowOf(SpamListManifest.EMPTY)
        every { repo.observeLastSyncAt() } returns flowOf(0L)
        every { prefs.spamSettingsLastSeenAt } returns flowOf(0L)
        every { prefs.npubKey } returns flowOf(null) // no active owner → token count skipped
        coEvery { repo.syncManifest() } returns false
        coEvery { privateRepo.saveSpamSettings(any()) } returns Result.success(Unit)
        coEvery { privateRepo.snapshotAndPublishTokens(any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm() = SpamPolicyViewModel(repo, privateRepo, prefs)

    @Test
    fun update_writesTransformedConfig() = runTest(mainDispatcher) {
        val vm = makeVm()
        val cfgSlot = slot<SpamFilterConfig>()
        coEvery { repo.setConfig(capture(cfgSlot)) } just Runs

        vm.update { it.copy(threshold = 9.0, enabled = false) }

        assertEquals(9.0, cfgSlot.captured.threshold, 0.0001)
        assertEquals(false, cfgSlot.captured.enabled)
    }

    @Test
    fun setSensitivity_invertsToThreshold() = runTest(mainDispatcher) {
        val vm = makeVm()
        val cfgSlot = slot<SpamFilterConfig>()
        coEvery { repo.setConfig(capture(cfgSlot)) } just Runs

        // Sensitivity 10 (max) → threshold 0 (catch everything).
        vm.setSensitivity(10f)
        assertEquals(0.0, cfgSlot.captured.threshold, 0.0001)

        // Sensitivity 0 (min) → threshold 10 (catch nothing).
        vm.setSensitivity(0f)
        assertEquals(10.0, cfgSlot.captured.threshold, 0.0001)
    }

    @Test
    fun applyBlendPreset_setsWeights() = runTest(mainDispatcher) {
        val vm = makeVm()
        val cfgSlot = slot<SpamFilterConfig>()
        coEvery { repo.setConfig(capture(cfgSlot)) } just Runs

        vm.applyBlendPreset(BlendPreset.BAYESIAN)

        assertEquals(0.3, cfgSlot.captured.heuristicWeight, 0.0001)
        assertEquals(0.7, cfgSlot.captured.bayesianWeight, 0.0001)
    }

    @Test
    fun syncNow_forcePublishesConfigAndTokens() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.syncNow()

        coVerify { privateRepo.saveSpamSettings(any()) }
        coVerify { privateRepo.snapshotAndPublishTokens(force = true) }
    }

    @Test
    fun refreshBlocklist_callsSyncManifest() = runTest(mainDispatcher) {
        val vm = makeVm()

        vm.refreshBlocklist()

        coVerify { repo.syncManifest() }
    }
}
