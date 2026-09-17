package xyz.desent.presentation.ui.markdown.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.markdown.MarkdownFileIO
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.util.UUID

/** Display mode for the markdown viewer. Files open in VIEW. */
enum class MarkdownViewMode { VIEW, EDIT }

data class MarkdownViewerUiState(
    val fileName: String = "",
    val body: String = "",
    val mode: MarkdownViewMode = MarkdownViewMode.VIEW,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val isSavingAsNote: Boolean = false,
    val toast: String? = null
)

/** One-shot UI events from the viewer. */
sealed class MarkdownViewerEvent {
    /**
     * The document's location is read-only (ACTION_VIEW grants carry no write
     * permission) — ask the user where to save an edited copy via
     * ACTION_CREATE_DOCUMENT.
     */
    object LaunchSaveCopy : MarkdownViewerEvent()
}

/**
 * Opens a markdown document living on the device (opened via the system
 * file association, the Notes in-app picker, or a prior edited copy). The
 * body is held in memory only; nothing is persisted unless the user saves
 * back to the file, saves a copy, or explicitly creates a NIP-78 note —
 * which publishes through the normal app-data relay path.
 */
class MarkdownViewerViewModel(
    private val fileUri: String,
    private val io: MarkdownFileIO,
    private val useCase: PrivateStorageUseCase,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MarkdownViewerUiState(fileName = fileNameFromUri(fileUri))
    )
    val uiState: StateFlow<MarkdownViewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MarkdownViewerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<MarkdownViewerEvent> = _events.asSharedFlow()

    /** Body as last loaded from disk or written back — the clean baseline. */
    private var originalBody: String = ""

    init {
        val savedBody = savedState.get<String>(KEY_BODY)
        val savedMode = savedState.get<String>(KEY_MODE)?.toModeOrNull()
        if (savedBody != null) {
            originalBody = savedBody
            _uiState.value = _uiState.value.copy(
                body = savedBody,
                isLoading = false,
                mode = savedMode ?: MarkdownViewMode.VIEW
            )
        } else {
            load()
        }
    }

    fun retry() {
        _uiState.value = _uiState.value.copy(isLoading = true, loadError = null)
        load()
    }

    fun onBodyChange(value: String) {
        _uiState.value = _uiState.value.copy(body = value, isDirty = value != originalBody)
        savedState[KEY_BODY] = value
    }

    fun enterEdit() {
        _uiState.value = _uiState.value.copy(mode = MarkdownViewMode.EDIT)
        savedState[KEY_MODE] = MarkdownViewMode.EDIT.name
    }

    /** Leave the editor, discarding unsaved edits back to the last baseline. */
    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(
            body = originalBody,
            isDirty = false,
            mode = MarkdownViewMode.VIEW
        )
        savedState[KEY_BODY] = originalBody
        savedState[KEY_MODE] = MarkdownViewMode.VIEW.name
    }

    fun save() {
        if (!_uiState.value.isDirty) {
            _uiState.value = _uiState.value.copy(mode = MarkdownViewMode.VIEW)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val result = io.write(fileUri, _uiState.value.body)
            _uiState.value = _uiState.value.copy(isSaving = false)
            result.fold(
                onSuccess = {
                    originalBody = _uiState.value.body
                    savedState[KEY_BODY] = originalBody
                    _uiState.value = _uiState.value.copy(
                        isDirty = false,
                        mode = MarkdownViewMode.VIEW,
                        toast = "Saved"
                    )
                    savedState[KEY_MODE] = MarkdownViewMode.VIEW.name
                },
                onFailure = { e ->
                    if (e is SecurityException) {
                        _events.tryEmit(MarkdownViewerEvent.LaunchSaveCopy)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toast = "Save failed: ${e.message ?: "unknown error"}"
                        )
                    }
                }
            )
        }
    }

    /** Write the current body to a user-chosen location (edited copy flow). */
    fun saveCopyTo(uriString: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val result = io.write(uriString, _uiState.value.body)
            _uiState.value = _uiState.value.copy(isSaving = false)
            result.fold(
                onSuccess = {
                    originalBody = _uiState.value.body
                    savedState[KEY_BODY] = originalBody
                    _uiState.value = _uiState.value.copy(
                        isDirty = false,
                        mode = MarkdownViewMode.VIEW,
                        toast = "Copy saved"
                    )
                    savedState[KEY_MODE] = MarkdownViewMode.VIEW.name
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        toast = "Save failed: ${e.message ?: "unknown error"}"
                    )
                }
            )
        }
    }

    /** Create an encrypted NIP-78 private note from this document. */
    fun saveAsNote() {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: run {
                _uiState.value = _uiState.value.copy(toast = "No active account")
                return@launch
            }
            _uiState.value = _uiState.value.copy(isSavingAsNote = true)
            val id = UUID.randomUUID().toString()
            val note = PrivateNote(
                id = id,
                title = noteTitle(),
                body = _uiState.value.body,
                updatedAt = System.currentTimeMillis() / 1000,
                folder = "",
                attachments = emptyList(),
                ownerNpub = npub,
                dTag = "desent:note:$id"
            )
            val result = useCase.saveNote(note)
            _uiState.value = _uiState.value.copy(
                isSavingAsNote = false,
                toast = result.fold(
                    onSuccess = { "Saved to notes" },
                    onFailure = { "Save failed: ${it.message ?: "unknown error"}" }
                )
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun load() {
        viewModelScope.launch {
            io.read(fileUri).fold(
                onSuccess = { text ->
                    originalBody = text
                    savedState[KEY_BODY] = text
                    _uiState.value = _uiState.value.copy(
                        body = text,
                        isLoading = false,
                        loadError = null
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadError = e.message ?: "Couldn't open the file"
                    )
                }
            )
        }
    }

    private fun noteTitle(): String {
        val name = _uiState.value.fileName
        val stripped = name.removeSuffix(".md").removeSuffix(".markdown")
            .removeSuffix(".MD").removeSuffix(".Markdown")
        return stripped.ifBlank { "Markdown document" }
    }

    private fun String.toModeOrNull(): MarkdownViewMode? =
        when (this) {
            MarkdownViewMode.VIEW.name -> MarkdownViewMode.VIEW
            MarkdownViewMode.EDIT.name -> MarkdownViewMode.EDIT
            else -> null
        }

    companion object {
        private const val KEY_BODY = "mdfile:body"
        private const val KEY_MODE = "mdfile:mode"

        /**
         * Best-effort display name from a content:// or file:// URI string
         * (handles SAF document IDs like `primary:Download/notes.md`).
         */
        fun fileNameFromUri(uriString: String): String {
            val path = uriString.substringBefore('?').substringBefore('#')
            val name = path.substringAfterLast('/').substringAfterLast(':')
            return name.ifBlank { "document.md" }
        }
    }
}
