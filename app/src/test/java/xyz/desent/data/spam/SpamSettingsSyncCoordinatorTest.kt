package xyz.desent.data.spam

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import xyz.desent.data.local.database.dao.PersonalSpamRuleDao
import xyz.desent.data.local.database.entity.PersonalSpamRuleEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.repository.PrivateStorageRepository

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpamSettingsSyncCoordinatorTest {

    private fun makeDao(): Pair<PersonalSpamRuleDao, MutableStateFlow<List<PersonalSpamRuleEntity>>> {
        val flow = MutableStateFlow(emptyList<PersonalSpamRuleEntity>())
        val dao = mockk<PersonalSpamRuleDao>(relaxed = true)
        every { dao.observeAll() } returns flow
        return dao to flow
    }

    @Test
    fun seedsBaselineWithoutPublishing_thenPublishesOnChange() = runTest(UnconfinedTestDispatcher()) {
        val configFlow = MutableStateFlow(SpamFilterConfig(threshold = 5.0))
        val prefs = mockk<PreferencesManager>(relaxed = true)
        every { prefs.spamFilterConfig } returns configFlow
        val (dao, _) = makeDao()
        val repo = mockk<PrivateStorageRepository>(relaxed = true)
        coEvery { repo.saveSpamSettings(any(), any()) } returns Result.success(Unit)

        val coordinator = SpamSettingsSyncCoordinator(prefs, dao, repo, backgroundScope, debounceMs = 0)
        coordinator.start()
        // Unconfined dispatcher runs the collector eagerly: the initial value
        // seeds the baseline (no publish).
        coVerify(exactly = 0) { repo.saveSpamSettings(any(), any()) }

        // A genuine local edit → publish fires exactly once.
        configFlow.value = SpamFilterConfig(threshold = 8.0)
        coVerify(exactly = 1) { repo.saveSpamSettings(any(), any()) }

        // Re-setting the same value → no additional publish.
        configFlow.value = SpamFilterConfig(threshold = 8.0)
        coVerify(exactly = 1) { repo.saveSpamSettings(any(), any()) }
    }

    @Test
    fun doesNotRepublishWhenContentUnchanged() = runTest(UnconfinedTestDispatcher()) {
        val same = SpamFilterConfig(threshold = 5.0)
        val configFlow = MutableStateFlow(same)
        val prefs = mockk<PreferencesManager>(relaxed = true)
        every { prefs.spamFilterConfig } returns configFlow
        val (dao, _) = makeDao()
        val repo = mockk<PrivateStorageRepository>(relaxed = true)
        coEvery { repo.saveSpamSettings(any(), any()) } returns Result.success(Unit)

        val coordinator = SpamSettingsSyncCoordinator(prefs, dao, repo, backgroundScope, debounceMs = 0)
        coordinator.start()
        coVerify(exactly = 0) { repo.saveSpamSettings(any(), any()) }

        // Re-emitting the same value (e.g. an inbound echo that wrote identical
        // config) must not trigger a publish.
        configFlow.value = same
        coVerify(exactly = 0) { repo.saveSpamSettings(any(), any()) }
    }

    @Test
    fun publishesWhenPersonalRulesChange() = runTest(UnconfinedTestDispatcher()) {
        val configFlow = MutableStateFlow(SpamFilterConfig(threshold = 5.0))
        val prefs = mockk<PreferencesManager>(relaxed = true)
        every { prefs.spamFilterConfig } returns configFlow
        val (dao, rulesFlow) = makeDao()
        val repo = mockk<PrivateStorageRepository>(relaxed = true)
        coEvery { repo.saveSpamSettings(any(), any()) } returns Result.success(Unit)

        val coordinator = SpamSettingsSyncCoordinator(prefs, dao, repo, backgroundScope, debounceMs = 0)
        coordinator.start()
        coVerify(exactly = 0) { repo.saveSpamSettings(any(), any()) }

        // A "mark as spam" rule landing in Room must publish even though the
        // scalar config is unchanged.
        rulesFlow.value = listOf(
            PersonalSpamRuleEntity(
                ownerNpub = "npub1owner",
                type = PersonalSpamRuleEntity.TYPE_BLOCK_SENDER,
                value = "spam@example.com",
                updatedAt = 1L
            )
        )
        coVerify(exactly = 1) { repo.saveSpamSettings(any(), any()) }
    }
}
