package xyz.desent.presentation.ui.email.viewmodel

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
import xyz.desent.domain.model.EmailOutboxEntry
import xyz.desent.domain.usecase.EmailUseCase

data class EmailOutboxUiState(
    val entries: List<EmailOutboxEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val toast: String? = null
)

/**
 * The Outbox ledger: every outbound send and its delivery state. Opening the
 * screen reconciles first, so stale PENDING entries show as timed out rather
 * than "Sending…" forever.
 */
class EmailOutboxViewModel(
    private val emailUseCase: EmailUseCase,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailOutboxUiState())
    val uiState: StateFlow<EmailOutboxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
            emailUseCase.reconcileOutbox(npub)
            emailUseCase.observeOutbox(npub)
                .onEach { entries ->
                    _uiState.value = _uiState.value.copy(entries = entries, isLoading = false)
                }
                .launchIn(viewModelScope)
        }
    }

    fun retry(messageId: String) {
        viewModelScope.launch {
            emailUseCase.retrySend(messageId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(toast = "Sending again…")
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = "Retry failed: ${e.message}")
                }
            )
        }
    }

    fun delete(messageId: String) {
        viewModelScope.launch { emailUseCase.deleteOutboxEntry(messageId) }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}
