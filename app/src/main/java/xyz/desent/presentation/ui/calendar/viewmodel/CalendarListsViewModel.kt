package xyz.desent.presentation.ui.calendar.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.domain.model.NostrCalendar
import xyz.desent.domain.usecase.CalendarUseCase
import java.util.UUID

data class CalendarListsUiState(
    val calendars: List<NostrCalendar> = emptyList(),
    val isLoading: Boolean = true,
    val toast: String? = null
)

class CalendarListsViewModel(
    private val useCase: CalendarUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarListsUiState())
    val uiState: StateFlow<CalendarListsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub()
            if (npub == null) {
                _uiState.value = CalendarListsUiState(isLoading = false)
                return@launch
            }
            useCase.observeCalendars(npub).collect { calendars ->
                _uiState.value = CalendarListsUiState(calendars = calendars, isLoading = false)
            }
        }
    }

    fun createCalendar(title: String, color: String?) {
        if (title.isBlank()) {
            _uiState.value = _uiState.value.copy(toast = "Name is required")
            return
        }
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: run {
                _uiState.value = _uiState.value.copy(toast = "No active account")
                return@launch
            }
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis() / 1000
            useCase.saveCalendar(
                NostrCalendar(
                    id = id,
                    ownerNpub = npub,
                    dTag = NostrCalendar.D_PREFIX + id,
                    title = title.trim(),
                    description = null,
                    color = color,
                    eventDs = emptyList(),
                    shares = emptyList(),
                    updatedAt = now,
                    createdAt = now
                )
            )
            _uiState.value = _uiState.value.copy(toast = "Calendar created")
        }
    }

    fun deleteCalendar(id: String) {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            val result = useCase.deleteCalendar(npub, id)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "Calendar deleted" else "Delete failed"
            )
        }
    }

    fun clearToast() { _uiState.value = _uiState.value.copy(toast = null) }

    companion object {
        /** Preset palette offered when creating a calendar. */
        val COLORS = listOf(
            "#3b82f6", "#ef4444", "#22c55e", "#eab308",
            "#a855f7", "#f97316", "#06b6d4", "#64748b"
        )
    }
}
