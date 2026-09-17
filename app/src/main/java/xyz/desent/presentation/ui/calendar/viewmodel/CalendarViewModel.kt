package xyz.desent.presentation.ui.calendar.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.model.NostrCalendarEvent
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.usecase.CalendarUseCase
import xyz.desent.domain.usecase.PrivateStorageUseCase
import xyz.desent.presentation.ui.calendar.CalendarAgendaBuilder
import xyz.desent.presentation.ui.calendar.CalendarListItem

data class CalendarUiState(
    val items: List<CalendarListItem> = emptyList(),
    /**
     * The events actually rendered by the agenda (recurring series already
     * expanded into occurrences) — the month grid derives its day dots from
     * this so grid and list can never disagree.
     */
    val events: List<NostrCalendarEvent> = emptyList(),
    val isLoading: Boolean = true,
    val toast: String? = null,
    /** Contact detail opened from an anniversary chip (any-tab requirement). */
    val detailContact: PrivateContact? = null,
    val detailProfile: ContactProfile? = null,
    val isResolvingProfile: Boolean = false
)

class CalendarViewModel(
    private val useCase: CalendarUseCase,
    private val contactsUseCase: PrivateStorageUseCase,
    private val profileResolver: ContactProfileResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    private var contacts: List<PrivateContact> = emptyList()
    private var storedEvents: List<NostrCalendarEvent> = emptyList()

    init {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub()
            if (npub == null) {
                _uiState.value = CalendarUiState(isLoading = false)
                return@launch
            }
            // Refresh the relay subscription on entry so edits from other
            // devices land quickly. The Room flow below drives the UI.
            useCase.subscribeToOwnCalendar()
            launch {
                contactsUseCase.observeContacts(npub).collect { list ->
                    contacts = list
                    rebuild()
                }
            }
            useCase.observeEvents(npub).collect { list ->
                storedEvents = list
                rebuild()
            }
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            val result = useCase.deleteEvent(npub, id)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "Event deleted" else "Delete failed"
            )
        }
    }

    fun refresh() {
        viewModelScope.launch { useCase.subscribeToOwnCalendar() }
    }

    /** Anniversary chip tap → contact detail sheet with live profile. */
    fun openContact(contact: PrivateContact) {
        _uiState.value = _uiState.value.copy(
            detailContact = contact,
            detailProfile = null,
            isResolvingProfile = contact.pubkey != null
        )
        contact.pubkey?.let { hex ->
            viewModelScope.launch {
                val profile = runCatching { profileResolver.resolve(hex) }.getOrNull()
                _uiState.value = _uiState.value.copy(detailProfile = profile, isResolvingProfile = false)
            }
        }
    }

    fun dismissContact() {
        _uiState.value = _uiState.value.copy(detailContact = null, detailProfile = null, isResolvingProfile = false)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    /** Re-flatten the agenda from the cached events and contacts. */
    private fun rebuild() {
        val items = CalendarAgendaBuilder.build(storedEvents, contacts)
        _uiState.value = _uiState.value.copy(
            items = items,
            events = items.mapNotNull { (it as? CalendarListItem.EventItem)?.event },
            isLoading = false
        )
    }
}
