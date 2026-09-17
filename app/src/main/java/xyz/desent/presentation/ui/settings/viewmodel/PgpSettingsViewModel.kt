package xyz.desent.presentation.ui.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.data.pgp.PgpFeatureGate
import xyz.desent.domain.model.PgpKeyInfo
import xyz.desent.domain.model.PgpKeyState
import xyz.desent.domain.repository.PgpKeyRepository
import xyz.desent.domain.repository.RegistrationRepository
import xyz.desent.domain.repository.SecurityConfigRepository
import xyz.desent.data.local.preferences.PreferencesManager

/** WKD publish state as the relay's key registry sees it. */
sealed class PgpPublishedState {
    data object Unknown : PgpPublishedState()
    data object Checking : PgpPublishedState()
    data object Published : PgpPublishedState()
    data object NotPublished : PgpPublishedState()
    data class Error(val message: String) : PgpPublishedState()
}

data class PgpSettingsUiState(
    val featureEnabled: Boolean = false,
    val keyInfo: PgpKeyInfo? = null,
    val published: PgpPublishedState = PgpPublishedState.Unknown,
    val autoEncrypt: Boolean = false,
    val isWorking: Boolean = false,
    val toast: String? = null,
    val error: String? = null
)

/**
 * PGP key management (ANDROID_PGP.md §2): one key per account — generate
 * (curve25519 passphraseless) or import (passphrase unlocked once, re-armed
 * passphraseless), published to the relay registry for WKD discovery, private
 * half kept in kind-30078 + the device mirror. Removal = 30078 tombstone AND
 * `DELETE /api/pgp/key`.
 */
class PgpSettingsViewModel(
    private val pgpKeyRepository: PgpKeyRepository,
    private val pgpFeatureGate: PgpFeatureGate,
    private val securityConfigRepository: SecurityConfigRepository,
    private val preferencesManager: PreferencesManager,
    private val registrationRepository: RegistrationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PgpSettingsUiState())
    val uiState: StateFlow<PgpSettingsUiState> = _uiState.asStateFlow()

    init {
        pgpFeatureGate.ensureLoaded()
        viewModelScope.launch {
            pgpFeatureGate.enabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(featureEnabled = enabled)
            }
        }
        viewModelScope.launch {
            pgpKeyRepository.keyState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    keyInfo = (state as? PgpKeyState.Available)?.info
                )
            }
        }
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            securityConfigRepository.observe(npub).collect { config ->
                _uiState.value = _uiState.value.copy(autoEncrypt = config?.pgpAutoEncrypt ?: false)
            }
        }
        refreshPublishedState()
    }

    fun refresh() {
        pgpFeatureGate.refresh()
        refreshPublishedState()
    }

    private fun refreshPublishedState() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(published = PgpPublishedState.Checking)
            val result = pgpKeyRepository.fetchPublishedKey()
            result.fold(
                onSuccess = { info ->
                    _uiState.value = _uiState.value.copy(
                        published = if (info != null) PgpPublishedState.Published else PgpPublishedState.NotPublished
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        published = PgpPublishedState.Error(e.message ?: "Lookup failed")
                    )
                }
            )
        }
    }

    /** The user's primary @desent.xyz address (relay rejects anything else). */
    private suspend fun primaryAddress(): String? {
        val remote = registrationRepository.getAccount().getOrNull() ?: return null
        return remote.nip05?.takeIf { it.endsWith("@desent.xyz", ignoreCase = true) }
            ?: remote.local?.takeIf { it.isNotBlank() }?.let { "$it@desent.xyz" }
    }

    fun generateKey() {
        if (_uiState.value.isWorking) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, error = null, toast = null)
            val account = registrationRepository.getAccount().getOrNull()
            val address = account?.nip05?.takeIf { it.endsWith("@desent.xyz", ignoreCase = true) }
                ?: account?.local?.takeIf { it.isNotBlank() }?.let { "$it@desent.xyz" }
            if (address == null) {
                _uiState.value = _uiState.value.copy(
                    isWorking = false,
                    error = "No @desent.xyz address on this account"
                )
                return@launch
            }
            val displayName = account?.displayName?.takeIf { it.isNotBlank() }
                ?: address.substringBefore('@')
            pgpKeyRepository.generateKey(displayName, address).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isWorking = false,
                        toast = "PGP key generated and published"
                    )
                    refreshPublishedState()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isWorking = false,
                        error = "Key generation failed: ${e.message}"
                    )
                }
            )
        }
    }

    fun importKey(secretArmored: String, passphrase: String?) {
        if (_uiState.value.isWorking) return
        if (secretArmored.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Paste an armored private key block")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, error = null, toast = null)
            pgpKeyRepository.importKey(secretArmored.trim(), passphrase).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isWorking = false,
                        toast = "PGP key imported and published"
                    )
                    refreshPublishedState()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isWorking = false,
                        error = "Import failed: ${e.message}"
                    )
                }
            )
        }
    }

    fun removeKey() {
        if (_uiState.value.isWorking) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, error = null, toast = null)
            pgpKeyRepository.removeKey().fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isWorking = false,
                        toast = "PGP key removed"
                    )
                    refreshPublishedState()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isWorking = false,
                        error = "Removal failed: ${e.message}"
                    )
                }
            )
        }
    }

    /** `pgp_auto_encrypt` on kind 30079 — the event content is the sync. */
    fun setAutoEncrypt(enabled: Boolean) {
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            _uiState.value = _uiState.value.copy(autoEncrypt = enabled)
            securityConfigRepository.setPgpAutoEncrypt(npub, enabled).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        toast = "Publishing kind 30079 to desent.xyz…"
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        autoEncrypt = !enabled,
                        error = "Save failed: ${e.message}"
                    )
                }
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
