package xyz.desent.presentation.ui.security.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import xyz.desent.data.local.database.dao.SecurityAlertDao
import xyz.desent.data.local.database.entity.SecurityAlertEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.usecase.SecurityConfigUseCase

data class SecurityAlertsUiState(
    val alerts: List<SecurityAlertEntity> = emptyList(),
    val unseenCount: Int = 0,
    /** The kind-30079 `security_alerts` mode (off / new_device / always). */
    val alertMode: SecurityAlertMode = SecurityAlertMode.DEFAULT,
    val hasCachedMode: Boolean = false,
    val isPublishing: Boolean = false,
    /**
     * False while viewing a NON-active account: kind-30079 publishes sign with
     * the active identity, so the mode chips must not offer changes until the
     * user switches to the account.
     */
    val modeEditable: Boolean = true,
    /** Non-null while the detail pane is open. */
    val selectedEventId: String? = null,
    val toast: String? = null
)

/**
 * Backs the Security alerts screen (list + detail) and the in-screen mode
 * chips. Alert rows come from the dedicated `security_alerts` Room table;
 * the mode publishes as a partial kind-30079 payload via
 * [SecurityConfigUseCase] (its own configuration — never the 35050 mailbox
 * config or the 30078 spam-settings namespace).
 *
 * [ownerNpubOverride] scopes the screen to one specific account (arriving
 * from the account switcher's per-account section); null = the active
 * account. The kind-30079 publish path signs with the ACTIVE identity, so
 * the mode chips are read-only while another account is displayed.
 */
class SecurityAlertsViewModel(
    private val securityAlertDao: SecurityAlertDao,
    private val securityConfigUseCase: SecurityConfigUseCase,
    private val preferencesManager: PreferencesManager,
    initialSelectedEventId: String? = null,
    ownerNpubOverride: String? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityAlertsUiState(selectedEventId = initialSelectedEventId))
    val uiState: StateFlow<SecurityAlertsUiState> = _uiState.asStateFlow()

    /** Resolved scope once known; null only before init completes. */
    private var ownerNpub: String? = null

    init {
        viewModelScope.launch {
            // Local retention mirror of the relay's fixed 30-day NIP-40
            // expiration on alert wraps (ANDROID_SECURITY_ALERTS.md §3).
            val cutoff = System.currentTimeMillis() - ALERT_RETENTION_MS
            runCatching { securityAlertDao.purgeOlderThan(cutoff) }

            val activeNpub = preferencesManager.npubKey.firstOrNull()
            val npub = ownerNpubOverride ?: activeNpub ?: return@launch
            ownerNpub = npub
            _uiState.value = _uiState.value.copy(modeEditable = npub == activeNpub)

            securityAlertDao.observeForOwner(npub)
                .onEach { alerts ->
                    _uiState.value = _uiState.value.copy(alerts = alerts)
                    // A selected row may have been purged; drop the selection.
                    val selected = _uiState.value.selectedEventId
                    if (selected != null && alerts.none { it.eventId == selected }) {
                        _uiState.value = _uiState.value.copy(selectedEventId = null)
                    }
                }
                .launchIn(viewModelScope)

            securityAlertDao.observeUnseenCount(npub)
                .onEach { n -> _uiState.value = _uiState.value.copy(unseenCount = n) }
                .launchIn(viewModelScope)

            securityConfigUseCase.observe(npub)
                .onEach { config ->
                    _uiState.value = _uiState.value.copy(
                        alertMode = config?.alertMode ?: SecurityAlertMode.DEFAULT,
                        hasCachedMode = config != null
                    )
                }
                .launchIn(viewModelScope)

            // Hydrate the mode from the relay (owner-scoped REQ; LWW on echo).
            // The subscription follows the ACTIVE identity, so skip it while
            // another account's alerts are being viewed.
            if (npub == activeNpub) {
                runCatching { securityConfigUseCase.refresh() }
            }
        }
    }

    /** Open an alert (marks it seen) or close the detail pane (null). */
    fun selectAlert(eventId: String?) {
        _uiState.value = _uiState.value.copy(selectedEventId = eventId)
        if (eventId != null) {
            viewModelScope.launch { runCatching { securityAlertDao.markSeen(eventId) } }
        }
    }

    fun markAllSeen() {
        viewModelScope.launch {
            val npub = ownerNpub ?: preferencesManager.npubKey.firstOrNull() ?: return@launch
            runCatching { securityAlertDao.markAllSeen(npub) }
            _uiState.value = _uiState.value.copy(toast = "All alerts marked read")
        }
    }

    /** Publish the new alert mode (partial kind-30079 payload, quota-exempt). */
    fun setAlertMode(mode: SecurityAlertMode) {
        if (_uiState.value.isPublishing) return
        if (mode == _uiState.value.alertMode) return
        if (!_uiState.value.modeEditable) {
            // Publishing signs with the active identity — refuse for a
            // non-active account instead of writing the wrong key's settings.
            _uiState.value = _uiState.value.copy(
                toast = "Switch to this account to change its alert settings"
            )
            return
        }
        _uiState.value = _uiState.value.copy(isPublishing = true)
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull()
            val result = if (npub != null) {
                securityConfigUseCase.setAlertMode(npub, mode)
            } else {
                kotlin.Result.failure(IllegalStateException("Not logged in"))
            }
            _uiState.value = _uiState.value.copy(
                isPublishing = false,
                toast = if (result.isSuccess) "Alert preference updated" else "Couldn't update — will retry on next sync"
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    companion object {
        /** Fixed 30-day retention, mirroring the relay's NIP-40 expiration. */
        private const val ALERT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
