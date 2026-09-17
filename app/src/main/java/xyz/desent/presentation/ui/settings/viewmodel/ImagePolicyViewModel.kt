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
import xyz.desent.data.spam.RemoteImagePolicy
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.repository.PrivateStorageRepository
import xyz.desent.domain.repository.SpamFilterRepository

data class ImagePolicyUiState(
    val config: SpamFilterConfig = SpamFilterConfig(),
    /** Contacts whose mail auto-loads images (when the toggle is on). */
    val contactCount: Int = 0,
    val toast: String? = null
)

/**
 * Backs the "Safe senders" screen (remote-image policy). Reads/writes the
 * image fields of [SpamFilterConfig]; every edit flows to the NIP-78
 * `desent:spam-settings` namespace automatically via
 * [xyz.desent.data.spam.SpamSettingsSyncCoordinator], exactly like the rest
 * of the spam policy.
 */
class ImagePolicyViewModel(
    private val spamFilterRepository: SpamFilterRepository,
    private val privateStorageRepository: PrivateStorageRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImagePolicyUiState())
    val uiState: StateFlow<ImagePolicyUiState> = _uiState.asStateFlow()

    init {
        spamFilterRepository.observeConfig()
            .onEach { c -> _uiState.value = _uiState.value.copy(config = c) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            privateStorageRepository.observeContacts(npub)
                .onEach { contacts ->
                    _uiState.value = _uiState.value.copy(
                        contactCount = contacts.count { it.allEmails().isNotEmpty() }
                    )
                }
                .launchIn(viewModelScope)
        }
    }

    fun setBlockRemoteImages(enabled: Boolean) = update { it.copy(blockRemoteImages = enabled) }

    fun setImagesAllowedForContacts(enabled: Boolean) =
        update { it.copy(imagesAllowedForContacts = enabled) }

    /** @return false when the entry is invalid or already present. */
    fun addSender(raw: String): Boolean {
        val sender = RemoteImagePolicy.normalizeSender(raw)
        if (!isValidEmail(sender) || sender in _uiState.value.config.imageAllowedSenders) {
            toast("Invalid or duplicate sender")
            return false
        }
        update { it.copy(imageAllowedSenders = (it.imageAllowedSenders + sender).sorted()) }
        return true
    }

    /** @return false when the entry is invalid or already present. */
    fun addDomain(raw: String): Boolean {
        val domain = RemoteImagePolicy.normalizeDomain(raw)
        if (!isValidDomain(domain) || domain in _uiState.value.config.imageAllowedDomains) {
            toast("Invalid or duplicate domain")
            return false
        }
        update { it.copy(imageAllowedDomains = (it.imageAllowedDomains + domain).sorted()) }
        return true
    }

    fun removeSender(sender: String) = update {
        it.copy(imageAllowedSenders = it.imageAllowedSenders - RemoteImagePolicy.normalizeSender(sender))
    }

    fun removeDomain(domain: String) = update {
        it.copy(imageAllowedDomains = it.imageAllowedDomains - RemoteImagePolicy.normalizeDomain(domain))
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun update(transform: (SpamFilterConfig) -> SpamFilterConfig) {
        viewModelScope.launch {
            spamFilterRepository.setConfig(transform(_uiState.value.config))
        }
    }

    private fun toast(message: String) {
        _uiState.value = _uiState.value.copy(toast = message)
    }

    private fun isValidEmail(email: String): Boolean =
        email.contains("@") && !email.startsWith("@") && !email.endsWith("@") &&
            !email.contains(",") && !email.contains(" ") &&
            email.substringAfterLast("@", "").contains(".")

    private fun isValidDomain(domain: String): Boolean =
        domain.isNotEmpty() && !domain.contains("@") && !domain.contains(",") &&
            !domain.contains(" ") && domain.contains(".") && !domain.startsWith(".") &&
            !domain.endsWith(".")
}
