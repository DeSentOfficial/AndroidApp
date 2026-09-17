package xyz.desent.presentation.ui.notes.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.data.local.database.dao.FavoriteNoteDao
import xyz.desent.data.local.database.entity.FavoriteNoteEntity
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.usecase.PrivateStorageUseCase
import xyz.desent.presentation.ui.notes.NoteDateGroup
import xyz.desent.presentation.ui.notes.noteDateGroup

/** Which slice of the notes collection the list is currently showing. */
sealed interface NotesView {
    data object All : NotesView
    data object Favorites : NotesView
    data class Folder(val path: String) : NotesView
}

/** One row of the folders dropdown (the folder tree, flattened depth-first). */
data class FolderSummary(
    val path: String,
    val name: String,
    val depth: Int,
    /** Total notes in this folder's subtree. */
    val noteCount: Int
)

data class NotesUiState(
    val view: NotesView = NotesView.All,
    /** Flattened list rows: date section headers interleaved with note rows. */
    val items: List<NotesListItem> = emptyList(),
    /** Full folder tree for the dropdown, independent of the active view. */
    val folders: List<FolderSummary> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val toast: String? = null
)

/** Row model consumed by the notes list. */
sealed class NotesListItem {
    data class DateHeader(val group: NoteDateGroup) : NotesListItem()

    data class NoteItem(
        val note: PrivateNote,
        val isFavorite: Boolean = false
    ) : NotesListItem()
}

class NotesViewModel(
    private val useCase: PrivateStorageUseCase,
    private val favoriteNoteDao: FavoriteNoteDao
) : ViewModel() {

    private val _view = MutableStateFlow<NotesView>(NotesView.All)
    private val _search = MutableStateFlow("")

    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub()
            if (npub == null) {
                _uiState.value = NotesUiState(isLoading = false)
                return@launch
            }
            // Refresh the relay subscription on entry so edits from other
            // devices land quickly. The Room flow below drives the UI.
            useCase.subscribeToOwnPrivateStorage()
            combine(
                useCase.observeNotes(npub),
                favoriteNoteDao.observeNoteIds(npub),
                _view,
                _search
            ) { notes, favIds, view, search ->
                buildState(notes, favIds.toSet(), view, search)
            }.collect { _uiState.value = it }
        }
    }

    fun selectView(view: NotesView) {
        _view.value = view
    }

    /** Search overrides the pill view: any non-blank query lists matches across all folders. */
    fun onSearchChange(query: String) {
        _search.value = query
    }

    /** Pin/unpin a note as a device-local favorite (shown on the Notes widget). */
    fun toggleFavorite(note: PrivateNote) {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            if (favoriteNoteDao.isFavorite(npub, note.id)) {
                favoriteNoteDao.delete(npub, note.id)
                _uiState.value = _uiState.value.copy(toast = "Removed from favorites")
            } else {
                favoriteNoteDao.insert(
                    FavoriteNoteEntity(
                        ownerNpub = npub,
                        noteId = note.id,
                        addedAt = System.currentTimeMillis()
                    )
                )
                _uiState.value = _uiState.value.copy(toast = "Added to favorites")
            }
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            // Capture the attachment shas before the row disappears so their
            // blobs can be cleaned up when no other note references them.
            val attachments = useCase.observeNote(npub, id).firstOrNull()?.attachments.orEmpty()
            val result = useCase.deleteNote(npub, id)
            // Also drop the local favorite pin so it doesn't dangle.
            favoriteNoteDao.delete(npub, id)
            if (result.isSuccess) {
                attachments.forEach { useCase.deleteAttachmentIfOrphaned(npub, it.sha256) }
            }
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "Note deleted" else "Delete failed"
            )
        }
    }

    fun refresh() {
        viewModelScope.launch { useCase.subscribeToOwnPrivateStorage() }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    // ------------------------------------------------------------------
    // List construction
    // ------------------------------------------------------------------

    private fun buildState(
        notes: List<PrivateNote>,
        favIds: Set<String>,
        view: NotesView,
        search: String
    ): NotesUiState {
        val folders = buildFolderSummaries(notes)
        val query = search.trim()
        val listed = when {
            query.isNotEmpty() -> notes.filter { it.matchesQuery(query) }
            view == NotesView.Favorites -> notes.filter { it.id in favIds }
            view is NotesView.Folder -> notes.filter { it.folder == view.path }
            else -> notes
        }.sortedByDescending { it.updatedAt }

        return NotesUiState(
            view = view,
            items = buildListItems(listed, favIds),
            folders = folders,
            searchQuery = search,
            isLoading = false
        )
    }

    private fun PrivateNote.matchesQuery(query: String): Boolean {
        val q = query.lowercase()
        return title.lowercase().contains(q) ||
            body.lowercase().contains(q) ||
            folder.lowercase().contains(q)
    }

    private fun buildListItems(
        notes: List<PrivateNote>,
        favIds: Set<String>
    ): List<NotesListItem> {
        val out = mutableListOf<NotesListItem>()
        var lastGroup: NoteDateGroup? = null
        for (note in notes) {
            val group = noteDateGroup(note.updatedAt)
            if (group != lastGroup) {
                out.add(NotesListItem.DateHeader(group))
                lastGroup = group
            }
            out.add(NotesListItem.NoteItem(note, note.id in favIds))
        }
        return out
    }

    // ------------------------------------------------------------------
    // Folder-tree summaries (dropdown)
    // ------------------------------------------------------------------

    private class FolderNode(val name: String, val path: String) {
        val subfolders = linkedMapOf<String, FolderNode>()
        val notes = mutableListOf<PrivateNote>()
    }

    private fun buildFolderSummaries(notes: List<PrivateNote>): List<FolderSummary> {
        val root = FolderNode("", "")
        for (note in notes) {
            val segments = note.folder.trim('/').split('/').filter { it.isNotBlank() }
            var cur = root
            for ((index, seg) in segments.withIndex()) {
                val childPath = segments.subList(0, index + 1).joinToString("/")
                cur = cur.subfolders.getOrPut(seg) { FolderNode(seg, childPath) }
            }
            cur.notes.add(note)
        }

        val out = mutableListOf<FolderSummary>()
        walk(root, depth = 0, out)
        return out
    }

    private fun walk(node: FolderNode, depth: Int, out: MutableList<FolderSummary>) {
        node.subfolders.values.sortedBy { it.name.lowercase() }.forEach { child ->
            out.add(
                FolderSummary(
                    path = child.path,
                    name = child.name,
                    depth = depth,
                    noteCount = countNotes(child)
                )
            )
            walk(child, depth + 1, out)
        }
    }

    private fun countNotes(node: FolderNode): Int =
        node.notes.size + node.subfolders.values.sumOf { countNotes(it) }
}
