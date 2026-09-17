package xyz.desent.presentation.ui.notes.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.data.attachment.NoteAttachmentOpener
import xyz.desent.data.local.preferences.NoteDraftStore
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.util.UUID

/** Display mode for the note detail screen. */
enum class NoteViewMode { VIEW, EDIT }

data class NoteEditorUiState(
    val noteId: String? = null,
    val title: String = "",
    val body: String = "",
    val folder: String = "",
    val attachments: List<AttachmentMeta> = emptyList(),
    /** Last persisted edit time (epoch seconds); 0 while the note has never been saved. */
    val updatedAt: Long = 0L,
    val isSaving: Boolean = false,
    val isUploading: Boolean = false,
    /** sha256 of the attachment currently being downloaded/decrypted, if any. */
    val openingSha: String? = null,
    /** Whether the note is being viewed (rendered) or edited. Existing notes open in VIEW. */
    val mode: NoteViewMode = NoteViewMode.EDIT,
    val saved: Boolean = false,
    val toast: String? = null,
    /**
     * Inline `![alt](attachment:<sha>)` image rendering: sha256 → locally
     * decrypted content URI (PRIVATE_STORAGE_PROTOCOL.md §Note body). Bodies
     * are rewritten to these URIs before markdown rendering; non-image
     * attachments stay as the chips/open flow below the body.
     */
    val inlineImages: Map<String, String> = emptyMap()
)

/** Fired when a decrypted attachment is ready to hand to an ACTION_VIEW intent. */
data class AttachmentOpenEvent(val uri: Uri, val mimeType: String)

/** Serializable shape persisted to the on-disk draft store. */
@Serializable
private data class NoteDraft(
    val title: String = "",
    val body: String = "",
    val folder: String = "",
    val mode: String = "EDIT",
    val attachments: List<AttachmentMeta> = emptyList()
)

class NoteEditorViewModel(
    private val useCase: PrivateStorageUseCase,
    private val opener: NoteAttachmentOpener,
    private val noteId: String?, // null = new note
    private val savedState: SavedStateHandle,
    private val draftStore: NoteDraftStore
) : ViewModel() {

    private val draftKey: String
        get() = _uiState.value.noteId ?: noteId ?: "new"

    private val _uiState = MutableStateFlow(
        NoteEditorUiState(
            noteId = noteId,
            title = savedState.get<String>(KEY_TITLE) ?: "",
            body = savedState.get<String>(KEY_BODY) ?: "",
            folder = savedState.get<String>(KEY_FOLDER) ?: "",
            attachments = savedState.get<String>(KEY_ATTACHMENTS)
                ?.let { runCatching { json.decodeFromString<List<AttachmentMeta>>(it) }.getOrNull() }
                ?: emptyList(),
            mode = savedState.get<String>(KEY_MODE)?.toModeOrNull()
                ?: if (noteId != null) NoteViewMode.VIEW else NoteViewMode.EDIT
        )
    )
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

    private val _openEvents = MutableSharedFlow<AttachmentOpenEvent>(extraBufferCapacity = 4)
    val openEvents: SharedFlow<AttachmentOpenEvent> = _openEvents.asSharedFlow()

    /** Attachment shas removed during this edit session — orphan-cleanup candidates on save. */
    private val removedShas = mutableSetOf<String>()

    init {
        // Restore in this order: (1) SavedStateHandle (already applied above on construction),
        // (2) on-disk draft, (3) the stored note body for an existing note.
        viewModelScope.launch {
            val hasSavedDraft = savedState.get<String>(KEY_TITLE) != null ||
                savedState.get<String>(KEY_BODY) != null
            when {
                hasSavedDraft -> {
                    if (noteId != null) savedState[KEY_LOADED] = true
                }
                else -> {
                    val diskDraft = draftStore.getDraft(draftKey)?.let {
                        runCatching { json.decodeFromString<NoteDraft>(it) }.getOrNull()
                    }
                    if (diskDraft != null) {
                        applyDraft(diskDraft)
                    } else if (noteId != null && savedState.get<String>(KEY_LOADED) == null) {
                        loadExistingNote()
                    }
                }
            }
        }
    }

    private suspend fun loadExistingNote() {
        val npub = useCase.activeOwnerNpub() ?: return
        val id = noteId ?: return
        val note = useCase.observeNote(npub, id).firstOrNull() ?: return
        _uiState.value = _uiState.value.copy(
            title = note.title,
            body = note.body,
            folder = note.folder,
            attachments = note.attachments,
            updatedAt = note.updatedAt
        )
        persistToSavedState()
        savedState[KEY_LOADED] = true
        prepareInlineImages()
    }

    private fun applyDraft(d: NoteDraft) {
        _uiState.value = _uiState.value.copy(
            title = d.title,
            body = d.body,
            folder = d.folder,
            attachments = d.attachments,
            mode = d.mode.toModeOrNull() ?: _uiState.value.mode
        )
        persistToSavedState()
    }

    private fun persistToSavedState() {
        val s = _uiState.value
        savedState[KEY_TITLE] = s.title
        savedState[KEY_BODY] = s.body
        savedState[KEY_FOLDER] = s.folder
        savedState[KEY_MODE] = s.mode.name
        savedState[KEY_ATTACHMENTS] = json.encodeToString(
            ListSerializer(AttachmentMeta.serializer()),
            s.attachments
        )
    }

    /** Flush the current editor contents to the on-disk draft store. Call on ON_STOP. */
    fun persistDraft() {
        val s = _uiState.value
        viewModelScope.launch {
            val draft = NoteDraft(s.title, s.body, s.folder, s.mode.name, s.attachments)
            draftStore.saveDraft(draftKey, json.encodeToString(draft))
        }
    }

    fun onTitleChange(value: String) {
        _uiState.value = _uiState.value.copy(title = value)
        savedState[KEY_TITLE] = value
    }

    fun onBodyChange(value: String) {
        _uiState.value = _uiState.value.copy(body = value)
        savedState[KEY_BODY] = value
    }

    fun onFolderChange(value: String) {
        _uiState.value = _uiState.value.copy(folder = value)
        savedState[KEY_FOLDER] = value
    }

    /** Switch from the rendered view into the editor. */
    fun enterEdit() {
        _uiState.value = _uiState.value.copy(mode = NoteViewMode.EDIT)
        savedState[KEY_MODE] = NoteViewMode.EDIT.name
    }

    /** Leave the editor and return to the rendered view (existing notes only). */
    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(mode = NoteViewMode.VIEW)
        savedState[KEY_MODE] = NoteViewMode.VIEW.name
        prepareInlineImages()
    }

    fun addAttachment(data: ByteArray, mimeType: String, fileName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true)
            val result = useCase.uploadAttachment(data, mimeType, fileName)
            _uiState.value = _uiState.value.copy(
                isUploading = false,
                attachments = if (result.isSuccess) {
                    _uiState.value.attachments + result.getOrThrow()
                } else {
                    _uiState.value.attachments
                },
                toast = result.fold(
                    onSuccess = { "Attachment added" },
                    onFailure = { "Upload failed: ${it.message}" }
                )
            )
            if (result.isSuccess) {
                savedState[KEY_ATTACHMENTS] = json.encodeToString(
                    ListSerializer(AttachmentMeta.serializer()),
                    _uiState.value.attachments
                )
            }
        }
    }

    fun removeAttachment(sha256: String) {
        removedShas += sha256
        _uiState.value = _uiState.value.copy(
            attachments = _uiState.value.attachments.filterNot { it.sha256 == sha256 }
        )
        savedState[KEY_ATTACHMENTS] = json.encodeToString(
            ListSerializer(AttachmentMeta.serializer()),
            _uiState.value.attachments
        )
    }

    /**
     * Resolve inline `![alt](attachment:<sha>)` image references: download +
     * decrypt each referenced image attachment to a local FileProvider URI so
     * the markdown renderer can show it. Non-image refs are left untouched
     * (they stay covered by the open/download chips below the body).
     */
    fun prepareInlineImages() {
        val s = _uiState.value
        if (s.mode != NoteViewMode.VIEW) return
        val referenced = ATTACHMENT_REF_REGEX.findAll(s.body)
            .map { it.groupValues[1].lowercase() }
            .toSet()
        val pending = s.attachments.filter { meta ->
            meta.sha256.lowercase() in referenced &&
                meta.mimeType.startsWith("image/", ignoreCase = true) &&
                meta.sha256.lowercase() !in s.inlineImages
        }
        pending.forEach { meta ->
            viewModelScope.launch {
                opener.open(meta).onSuccess { target ->
                    _uiState.value = _uiState.value.copy(
                        inlineImages = _uiState.value.inlineImages +
                            (meta.sha256.lowercase() to target.uri.toString())
                    )
                } // failures: renderer falls back to the alt text silently
            }
        }
    }

    /** Download + decrypt + expose via FileProvider; emits to [openEvents]. */
    fun openAttachment(meta: AttachmentMeta) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(openingSha = meta.sha256)
            val result = opener.open(meta)
            _uiState.value = _uiState.value.copy(openingSha = null)
            result.fold(
                onSuccess = { _openEvents.emit(AttachmentOpenEvent(it.uri, it.mimeType)) },
                onFailure = {
                    _uiState.value = _uiState.value.copy(toast = "Couldn't open: ${it.message}")
                }
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.title.isBlank() && state.body.isBlank()) {
            _uiState.value = state.copy(toast = "Add a title or body first")
            return
        }
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: run {
                _uiState.value = state.copy(toast = "No active account")
                return@launch
            }
            _uiState.value = state.copy(isSaving = true)
            val id = state.noteId ?: noteId ?: UUID.randomUUID().toString()
            val note = PrivateNote(
                id = id,
                title = state.title.trim(),
                body = state.body,
                updatedAt = System.currentTimeMillis() / 1000,
                folder = state.folder.trim(),
                attachments = state.attachments,
                ownerNpub = npub,
                dTag = "desent:note:$id"
            )
            val result = useCase.saveNote(note)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                noteId = id,
                mode = if (result.isSuccess) NoteViewMode.VIEW else _uiState.value.mode,
                saved = false,
                updatedAt = if (result.isSuccess) note.updatedAt else _uiState.value.updatedAt,
                toast = if (result.isSuccess) "Saved" else "Save failed: ${result.exceptionOrNull()?.message}"
            )
            if (result.isSuccess) {
                savedState[KEY_MODE] = NoteViewMode.VIEW.name
                // The note is persisted to the relay; clear any disk drafts for it
                // (and for the "new" bucket if this was a freshly created note).
                draftStore.clearDraft(id)
                if (noteId == null) draftStore.clearDraft("new")
                // Best-effort cleanup of blobs dropped from this note during
                // the edit session (skipped when other notes still reference
                // them or the removal is reverted by a later re-add).
                val dropped = removedShas.toSet() -
                    state.attachments.map { it.sha256 }.toSet()
                dropped.forEach { sha ->
                    useCase.deleteAttachmentIfOrphaned(npub, sha)
                }
                removedShas.clear()
                prepareInlineImages()
            }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun String.toModeOrNull(): NoteViewMode? =
        when (this) {
            NoteViewMode.VIEW.name -> NoteViewMode.VIEW
            NoteViewMode.EDIT.name -> NoteViewMode.EDIT
            else -> null
        }

    companion object {
        private const val KEY_TITLE = "draft:title"
        private const val KEY_BODY = "draft:body"
        private const val KEY_FOLDER = "draft:folder"
        private const val KEY_MODE = "draft:mode"
        private const val KEY_ATTACHMENTS = "draft:attachments"
        private const val KEY_LOADED = "draft:loaded"

        /** Inline attachment refs in note bodies: `![…](attachment:<sha256>)`. */
        val ATTACHMENT_REF_REGEX = Regex("""!\[[^\]]*\]\(attachment:([0-9a-fA-F]{64})\)""")

        private val json = Json { ignoreUnknownKeys = true }
    }
}
