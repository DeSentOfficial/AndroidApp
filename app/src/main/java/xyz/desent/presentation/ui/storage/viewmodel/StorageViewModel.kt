package xyz.desent.presentation.ui.storage.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.storage.model.StorageError
import xyz.desent.domain.model.StorageBreakdown
import xyz.desent.domain.usecase.StorageUseCase

data class StorageUiState(
    val breakdown: StorageBreakdown? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    /** True when [breakdown] is being shown despite a failed refresh (stale data). */
    val isStale: Boolean = false
)

class StorageViewModel(
    private val storageUseCase: StorageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = _uiState.value.breakdown == null,
                isRefreshing = _uiState.value.breakdown != null,
                error = null
            )

            val result = storageUseCase.getStorageBreakdown()
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    breakdown = result.getOrNull(),
                    isLoading = false,
                    isRefreshing = false,
                    isStale = false,
                    error = null
                )
            } else {
                // Keep the last good breakdown (if any) so the user isn't left
                // staring at an empty screen on a transient failure; flag it stale.
                val hasCache = _uiState.value.breakdown != null
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isStale = hasCache,
                    error = if (hasCache) null else result.exceptionOrNull()?.friendlyMessage()
                )
            }
        }
    }

    fun refresh() = load()

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is StorageError.Unauthorized ->
            "Couldn't verify your signature. Tap to retry. (Check system time.)"
        is StorageError.Forbidden ->
            "This npub isn't registered on the relay. Use the onboarding flow first."
        is StorageError.RateLimited ->
            "Too many requests" + retryAfterSeconds?.let { ". Wait ${it}s." }.orEmpty()
        is StorageError.Server -> "Couldn't load storage (HTTP $code). Tap to retry."
        else -> message ?: "Couldn't load storage. Tap to retry."
    }
}
