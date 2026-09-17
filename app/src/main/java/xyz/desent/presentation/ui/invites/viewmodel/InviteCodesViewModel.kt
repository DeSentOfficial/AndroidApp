package xyz.desent.presentation.ui.invites.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.domain.model.InviteCode
import xyz.desent.domain.usecase.RegistrationUseCase

data class InviteCodesUiState(
    val codes: List<InviteCode> = emptyList(),
    val cap: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** 403 pubkey_not_registered — the key has no address yet. */
    val notRegistered: Boolean = false,
    /** 403 account_disabled — suspended. */
    val accountDisabled: Boolean = false
)

/**
 * "Your invite codes" screen. Data is re-fetched on every screen entry (the
 * ViewModel is nav-entry scoped) — admins can regenerate unused codes, so no
 * client-side caching (refs/FromServer/ANDROID_REFERRALS.md §5).
 */
class InviteCodesViewModel(
    private val registrationUseCase: RegistrationUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteCodesUiState())
    val uiState: StateFlow<InviteCodesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                notRegistered = false,
                accountDisabled = false
            )

            val result = registrationUseCase.getInviteCodes()
            val invites = result.getOrNull()
            if (invites != null) {
                _uiState.value = _uiState.value.copy(
                    codes = invites.codes,
                    cap = invites.cap,
                    isLoading = false
                )
            } else {
                val e = result.exceptionOrNull()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notRegistered = e is RegistrationError.PubkeyNotRegistered ||
                        e is RegistrationError.NotRegistered,
                    accountDisabled = e is RegistrationError.AccountDisabled,
                    error = e?.friendlyMessage()
                )
            }
        }
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is RegistrationError.Unauthorized -> "Authentication failed. Please sign in again."
        is RegistrationError.AccountDisabled -> "Your account has been suspended"
        is RegistrationError.PubkeyNotRegistered,
        is RegistrationError.NotRegistered -> "You haven't registered a DeSent address yet"
        is RegistrationError.RateLimited -> "Too many requests. Try again later."
        else -> message ?: "Failed to load invite codes"
    }
}
