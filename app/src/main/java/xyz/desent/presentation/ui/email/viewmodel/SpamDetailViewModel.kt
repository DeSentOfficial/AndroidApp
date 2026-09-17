package xyz.desent.presentation.ui.email.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.spam.RemoteImagePolicyState
import xyz.desent.data.spam.RemoteImagePolicyStateFactory
import xyz.desent.domain.model.Email
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.EmailRepository
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.TrainSpamUseCase

data class SpamDetailUiState(
    val email: Email? = null,
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val toast: String? = null,
    val error: String? = null,
    val notSpamHandled: Boolean = false,
    val deleted: Boolean = false
)

/**
 * Backs the full-screen Spam detail view (see refs/SPAM_FILTER_REFERENCE.md §"Disposition").
 * Loads a single quarantined message, marks it read on open, and exposes the "Not spam"
 * (trains Bayesian ham + clears the flag) and "Delete forever" (hard delete via the relay)
 * actions.
 */
class SpamDetailViewModel(
    private val emailId: String,
    private val emailRepository: EmailRepository,
    private val emailUseCase: EmailUseCase,
    private val trainSpamUseCase: TrainSpamUseCase,
    imagePolicyFactory: RemoteImagePolicyStateFactory,
    contactProfileResolver: ContactProfileResolver,
    faviconResolver: xyz.desent.data.avatar.FaviconResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpamDetailUiState())
    val uiState: StateFlow<SpamDetailUiState> = _uiState.asStateFlow()

    /** Remote-image blocking state for this quarantined message. */
    val imagePolicy: RemoteImagePolicyState by lazy { imagePolicyFactory.create(viewModelScope) }

    /** Sender avatar for this quarantined message. */
    val senderProfiles = SenderProfileStore(contactProfileResolver, faviconResolver, viewModelScope)

    init {
        loadEmail()
    }

    private fun loadEmail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val email = emailRepository.getEmailById(emailId)
            _uiState.value = _uiState.value.copy(email = email, isLoading = false)
            email?.let {
                emailUseCase.markAsRead(it.id)
                senderProfiles.ensure(it)
            }
        }
    }

    /** "Not spam" — trains the classifier and clears the quarantine flag. */
    fun markNotSpam(email: Email) {
        viewModelScope.launch {
            trainSpamUseCase(email, isSpam = false)
            _uiState.value = _uiState.value.copy(notSpamHandled = true, toast = "Moved to inbox")
        }
    }

    /** "Delete forever" — hard delete via the relay's message-delete endpoint. */
    fun deleteForever() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            val result = emailUseCase.requestDeletion(emailId)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isDeleting = false, deleted = true, toast = "Deleted")
            } else {
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    error = "Failed to delete: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
