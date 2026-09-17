package xyz.desent.data.spam

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import xyz.desent.data.local.database.dao.PersonalSpamRuleDao
import xyz.desent.data.local.database.entity.PersonalSpamRuleEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.PersonalSpamRules
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.repository.PrivateStorageRepository

/**
 * Watches the local spam-filter config and the personal block/allow rules and
 * publishes them to the encrypted NIP-78 `desent:spam-settings` namespace (via
 * [PrivateStorageRepository.saveSpamSettings]) so they follow the user across
 * devices.
 *
 * Loop suppression: an inbound remote config is written into DataStore / Room by
 * [xyz.desent.data.repository.PrivateStorageRepositoryImpl.onInboundPrivateStorageEvent],
 * which re-triggers this observer. We therefore republish only when the content
 * differs from the last value we published — so a remote change we just applied
 * does not bounce back as a new event (it equals what we now hold), and our own
 * relay echo is dropped upstream by the `created_at` last-seen watermark. Real
 * edits converge in a single round-trip per change.
 *
 * Debounce: settings UI edits (toggling layers, nudging the threshold) and
 * feedback-driven rule writes fire several writes in quick succession; we
 * coalesce them into one relay publish [DEBOUNCE_MS] after the last edit.
 */
class SpamSettingsSyncCoordinator(
    private val preferencesManager: PreferencesManager,
    private val personalSpamRuleDao: PersonalSpamRuleDao,
    private val privateStorageRepository: PrivateStorageRepository,
    private val coroutineScope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    private data class SyncState(val config: SpamFilterConfig, val rules: PersonalSpamRules)

    @Volatile private var lastPublished: SyncState? = null
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (job?.isActive == true) return
        job = combine(
            preferencesManager.spamFilterConfig,
            personalSpamRuleDao.observeAll()
        ) { config, rules ->
            SyncState(config, rules.toDomain())
        }
            .debounce(debounceMs)
            .distinctUntilChanged()
            .onEach { state ->
                val prev = lastPublished
                lastPublished = state
                // First post-debounce emission seeds the baseline so a fresh
                // launch (or the Flow's onStart default) doesn't trigger a
                // spurious publish; only genuine local edits are published.
                if (prev != null && state != prev) {
                    runCatching {
                        privateStorageRepository.saveSpamSettings(state.config, state.rules)
                    }
                        .onFailure {
                            Log.w(TAG, "Config sync publish failed: ${it.message}")
                        }
                        .onSuccess { Log.d(TAG, "Config synced to relay") }
                }
            }
            .launchIn(coroutineScope)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun List<PersonalSpamRuleEntity>.toDomain(): PersonalSpamRules =
        PersonalSpamRules(
            blockedDomains = valuesOfType(PersonalSpamRuleEntity.TYPE_BLOCK_DOMAIN),
            allowedDomains = valuesOfType(PersonalSpamRuleEntity.TYPE_ALLOW_DOMAIN),
            blockedSenders = valuesOfType(PersonalSpamRuleEntity.TYPE_BLOCK_SENDER),
            allowedSenders = valuesOfType(PersonalSpamRuleEntity.TYPE_ALLOW_SENDER)
        )

    private fun List<PersonalSpamRuleEntity>.valuesOfType(type: String): Set<String> =
        filter { it.type == type }.map { it.value }.toSet()

    companion object {
        private const val TAG = "SpamSettingsSync"
        const val DEFAULT_DEBOUNCE_MS = 4000L
    }
}
