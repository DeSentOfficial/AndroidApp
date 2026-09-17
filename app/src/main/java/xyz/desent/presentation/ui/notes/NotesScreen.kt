package xyz.desent.presentation.ui.notes

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.PrivateNote
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DeleteConfirmationDialog
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import xyz.desent.presentation.ui.notes.viewmodel.FolderSummary
import xyz.desent.presentation.ui.notes.viewmodel.NotesListItem
import xyz.desent.presentation.ui.notes.viewmodel.NotesUiState
import xyz.desent.presentation.ui.notes.viewmodel.NotesView
import xyz.desent.presentation.ui.notes.viewmodel.NotesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onNavigateToEditor: (String?) -> Unit,
    viewModel: NotesViewModel,
    onOpenMarkdownFile: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var actionNote by remember { mutableStateOf<PrivateNote?>(null) }
    var deleteNote by remember { mutableStateOf<PrivateNote?>(null) }

    // Open a markdown document from the device (system file picker). Note:
    // SAF cannot filter by extension, so files stored without one of the
    // markdown MIME types won't be selectable here — external "open with"
    // still covers those.
    val openMarkdownPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onOpenMarkdownFile(uri.toString())
    }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val closeSearch: () -> Unit = {
        isSearching = false
        viewModel.onSearchChange("")
    }
    // Picking a view while a search is active replaces the search results.
    val selectView: (NotesView) -> Unit = { view ->
        if (isSearching) closeSearch()
        viewModel.selectView(view)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::onSearchChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search notes") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = closeSearch) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp)
                        )
                    } else {
                        AccountBarTitle()
                    }
                },
                navigationIcon = {
                    DesentLogoMark()
                },
                actions = {
                    if (!isSearching) {
                        IconButton(onClick = {
                            openMarkdownPicker.launch(arrayOf("text/markdown", "text/x-markdown"))
                        }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Open markdown file")
                        }
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search notes")
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToEditor(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New note")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                NotesEmptyState(
                    view = uiState.view,
                    searchQuery = uiState.searchQuery,
                    onCreateNote = { onNavigateToEditor(null) },
                    modifier = Modifier.padding(Spacing.xl)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item(key = "notes-header") {
                    NotesListHeader(uiState = uiState, onSelectView = selectView)
                }
                itemsIndexed(uiState.items, key = { _, item ->
                    when (item) {
                        is NotesListItem.DateHeader -> "date:${item.group.name}"
                        is NotesListItem.NoteItem -> "note:${item.note.id}"
                    }
                }) { index, item ->
                    when (item) {
                        is NotesListItem.DateHeader -> DateHeaderRow(label = item.group.label)
                        is NotesListItem.NoteItem -> {
                            NoteRow(
                                note = item.note,
                                isFavorite = item.isFavorite,
                                onClick = { onNavigateToEditor(item.note.id) },
                                onLongClick = { actionNote = item.note }
                            )
                            // Thin divider between consecutive note rows (not
                            // before a section header, which has its own rule).
                            val next = uiState.items.getOrNull(index + 1)
                            if (next is NotesListItem.NoteItem) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 66.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionNote?.let { note ->
        val isFavorite = uiState.items
            .filterIsInstance<NotesListItem.NoteItem>()
            .any { it.note.id == note.id && it.isFavorite }
        NoteActionSheet(
            note = note,
            isFavorite = isFavorite,
            onDismiss = { actionNote = null },
            onToggleFavorite = { viewModel.toggleFavorite(note) },
            onDelete = { deleteNote = note }
        )
    }

    deleteNote?.let { note ->
        DeleteConfirmationDialog(
            title = "Delete note?",
            message = "Delete \"${note.title.ifBlank { "(untitled)" }}\"? " +
                "This removes it from your encrypted storage on all devices.",
            onConfirm = {
                viewModel.deleteNote(note.id)
                deleteNote = null
            },
            onDismiss = { deleteNote = null }
        )
    }
}

// ---------------------------------------------------------------------
// Header: segmented pills (All / Favorites / Folders dropdown)
// ---------------------------------------------------------------------

@Composable
private fun NotesListHeader(
    uiState: NotesUiState,
    onSelectView: (NotesView) -> Unit
) {
    var foldersExpanded by remember { mutableStateOf(false) }
    val activeFolderPath = (uiState.view as? NotesView.Folder)?.path
    val activeFolder = uiState.folders.firstOrNull { it.path == activeFolderPath }
    val searching = uiState.searchQuery.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SegmentPill(
                    label = "All",
                    selected = !searching && uiState.view == NotesView.All,
                    onClick = { onSelectView(NotesView.All) }
                )
                SegmentPill(
                    label = "Favorites",
                    selected = !searching && uiState.view == NotesView.Favorites,
                    onClick = { onSelectView(NotesView.Favorites) }
                )
                Box {
                    SegmentPill(
                        label = activeFolder?.path ?: "Folders",
                        selected = !searching && activeFolder != null,
                        onClick = { foldersExpanded = true },
                        trailingIcon = Icons.Default.ArrowDropDown
                    )
                    DropdownMenu(
                        expanded = foldersExpanded,
                        onDismissRequest = { foldersExpanded = false },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        FolderMenuRow(
                            label = "All notes",
                            selected = uiState.view == NotesView.All,
                            onClick = {
                                foldersExpanded = false
                                onSelectView(NotesView.All)
                            }
                        )
                        uiState.folders.forEach { folder ->
                            FolderMenuRow(
                                label = folder.name,
                                depth = folder.depth,
                                badge = folder.noteCount.takeIf { it > 0 },
                                selected = activeFolderPath == folder.path,
                                onClick = {
                                    foldersExpanded = false
                                    onSelectView(NotesView.Folder(folder.path))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(17.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            if (trailingIcon != null) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = if (label == "Folders") "Folders menu" else null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun FolderMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int? = null,
    depth: Int = 0
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(14.dp)
            )
            .padding(start = 8.dp + (depth * 16).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Text(
                text = badge.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------
// List rows
// ---------------------------------------------------------------------

@Composable
private fun DateHeaderRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.sm,
                bottom = Spacing.xs
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(Spacing.sm))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: PrivateNote,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val preview = remember(note.body) { notePreview(note.body) }
    val date = remember(note.updatedAt) { formatNoteListDate(note.updatedAt) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = note.title.ifBlank { "(untitled)" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (note.attachments.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "${note.attachments.size} attachment(s)",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.folder.isNotBlank()) {
                    Text(
                        text = note.folder,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Long-press action sheet
// ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteActionSheet(
    note: PrivateNote,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
            Text(
                text = note.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
            SheetAction(
                icon = if (isFavorite) Icons.Default.StarBorder else Icons.Default.Star,
                label = if (isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = {
                    onToggleFavorite()
                    onDismiss()
                }
            )
            SheetAction(
                icon = Icons.Default.Delete,
                label = "Delete note",
                onClick = {
                    onDelete()
                    onDismiss()
                },
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}

// ---------------------------------------------------------------------
// Empty states
// ---------------------------------------------------------------------

@Composable
private fun NotesEmptyState(
    view: NotesView,
    searchQuery: String,
    onCreateNote: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        when {
            searchQuery.isNotBlank() -> {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "No notes match \"${searchQuery.trim()}\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Try a different search term",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            view == NotesView.Favorites -> {
                Icon(
                    imageVector = Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "No favorites yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Long-press a note to pin it here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            view is NotesView.Folder -> {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "Nothing in this folder yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "No notes yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onCreateNote) {
                    Text("Create a note")
                }
            }
        }
    }
}
