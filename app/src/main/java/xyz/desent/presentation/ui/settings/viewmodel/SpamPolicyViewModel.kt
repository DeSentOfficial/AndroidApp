package xyz.desent.presentation.ui.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamListManifest
import xyz.desent.domain.repository.PrivateStorageRepository
import xyz.desent.domain.repository.SpamFilterRepository

/** Blend presets offered to the user (the weights are normalised by the classifier). */
enum class BlendPreset(val heuristicWeight: Double, val bayesianWeight: Double) {
    BALANCED(0.6, 0.4),
    HEURISTICS(0.8, 0.2),
    BAYESIAN(0.3, 0.7)
}

data class SpamPolicyUiState(
    val config: SpamFilterConfig = SpamFilterConfig(),
    val manifest: SpamListManifest = SpamListManifest.EMPTY,
    val tokenCount: Int = 0,
    /** Epoch seconds of the newest config event seen (cross-device LWW watermark). */
    val lastDeviceSyncAt: Long = 0L,
    /** Epoch millis of the last blocklist (publisher list) fetch. */
    val lastBlocklistSyncAt: Long = 0L,
    val syncing: Boolean = false,
    val refreshingBlocklist: Boolean = false,
    val toast: String? = null
)

/**
 * Backs the Spam policy screen. Reads/writes [SpamFilterConfig] through
 * [SpamFilterRepository]; every edit flows to the NIP-78 `desent:spam-settings`
 * namespace automatically via [xyz.desent.data.spam.SpamSettingsSyncCoordinator]
 * (debounced, echo-suppressed), so this ViewModel never publishes directly on
 * field edits — only the explicit "Sync now" action force-publishes.
 */
class SpamPolicyViewModel(
    private val spamFilterRepository: SpamFilterRepository,
    private val privateStorageRepository: PrivateStorageRepository,
    private val preferencesManager: PreferencesManager,
    private val mailFolderRepository: xyz.desent.domain.repository.MailFolderRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpamPolicyUiState())
    val uiState: StateFlow<SpamPolicyUiState> = _uiState.asStateFlow()

    init {
        spamFilterRepository.observeConfig()
            .onEach { c -> _uiState.value = _uiState.value.copy(config = c) }
            .launchIn(viewModelScope)
        spamFilterRepository.observeManifest()
            .onEach { m -> _uiState.value = _uiState.value.copy(manifest = m) }
            .launchIn(viewModelScope)
        spamFilterRepository.observeLastSyncAt()
            .onEach { t -> _uiState.value = _uiState.value.copy(lastBlocklistSyncAt = t) }
            .launchIn(viewModelScope)
        preferencesManager.spamSettingsLastSeenAt
            .onEach { t -> _uiState.value = _uiState.value.copy(lastDeviceSyncAt = t) }
            .launchIn(viewModelScope)

        // Token count is owner-scoped; resolve the active npub first.
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull()
            if (!npub.isNullOrBlank()) {
                spamFilterRepository.observeTokenCount(npub)
                    .onEach { n -> _uiState.value = _uiState.value.copy(tokenCount = n) }
                    .launchIn(viewModelScope)
            }
        }
    }

    /** Apply a single-field change to the config (debounced sync is automatic). */
    fun update(transform: (SpamFilterConfig) -> SpamFilterConfig) {
        viewModelScope.launch {
            spamFilterRepository.setConfig(transform(_uiState.value.config))
        }
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setLayerHeuristics(enabled: Boolean) = update { it.copy(layerHeuristicsEnabled = enabled) }
    fun setLayerBlocklist(enabled: Boolean) = update { it.copy(layerBlocklistEnabled = enabled) }
    fun setLayerBayesian(enabled: Boolean) = update { it.copy(layerBayesianEnabled = enabled) }

    /** @param sensitivity 0..10 where higher = catches MORE spam (lower threshold). */
    fun setSensitivity(sensitivity: Float) {
        val threshold = (10.0 - sensitivity.toDouble()).coerceIn(0.0, 10.0)
        update { it.copy(threshold = threshold) }
    }

    fun applyBlendPreset(preset: BlendPreset) = update {
        it.copy(heuristicWeight = preset.heuristicWeight, bayesianWeight = preset.bayesianWeight)
    }

    /** Force-publish config + token snapshot (and drain the mail-state overlay) to the relay now. */
    fun syncNow() {
        if (_uiState.value.syncing) return
        _uiState.value = _uiState.value.copy(syncing = true)
        viewModelScope.launch {
            val cfg = _uiState.value.config
            val configResult = runCatching { privateStorageRepository.saveSpamSettings(cfg) }
            val tokenResult = runCatching { privateStorageRepository.snapshotAndPublishTokens(force = true) }
            val mailResult = mailFolderRepository?.let { runCatching { it.flush(force = true) } }
            val ok = configResult.isSuccess && tokenResult.isSuccess && mailResult?.isSuccess ?: true
            _uiState.value = _uiState.value.copy(
                syncing = false,
                toast = if (ok) "Synced to your devices" else "Sync failed"
            )
        }
    }

    /** Re-fetch the publisher's blocklist. */
    fun refreshBlocklist() {
        if (_uiState.value.refreshingBlocklist) return
        _uiState.value = _uiState.value.copy(refreshingBlocklist = true)
        viewModelScope.launch {
            val changed = runCatching { spamFilterRepository.syncManifest() }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                refreshingBlocklist = false,
                toast = if (changed) "Blocklist updated" else "Blocklist already up to date"
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}
