package xyz.desent.presentation.ui.email

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ForwardToInbox
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
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
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.MailFolder
import xyz.desent.domain.model.SpfStatus
import xyz.desent.presentation.ui.email.components.EmailSenderAvatar
import xyz.desent.presentation.ui.email.components.MailFolderRow
import xyz.desent.presentation.ui.email.components.emailPreview
import xyz.desent.presentation.ui.email.viewmodel.AliasFilter
import xyz.desent.presentation.ui.email.viewmodel.EmailFilter
import xyz.desent.presentation.ui.email.viewmodel.EmailInboxUiState
import xyz.desent.presentation.ui.email.viewmodel.EmailInboxViewModel
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DeleteConfirmationDialog
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailInboxScreen(
    onNavigateToThread: (String) -> Unit,
    onNavigateToCompose: () -> Unit,
    onNavigateToForward: () -> Unit,
    onNavigateToAliases: () -> Unit,
    onNavigateToOutbox: () -> Unit,
    onNavigateToSpamDetail: (String) -> Unit,
    onNavigateToSpamPolicy: () -> Unit,
    onNavigateToImagePolicy: () -> Unit,
    viewModel: EmailInboxViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val senderAvatars by viewModel.senderProfiles.avatars.collectAsState()
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var sheetEmail by remember { mutableStateOf<Email?>(null) }

    // Mail-folder UI state: creation, manage (rename/delete), move-to-folder.
    var showCreateFolder by remember { mutableStateOf(false) }
    var manageFolder by remember { mutableStateOf<xyz.desent.domain.model.MailFolder?>(null) }
    var renameFolder by remember { mutableStateOf<xyz.desent.domain.model.MailFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<xyz.desent.domain.model.MailFolder?>(null) }
    var moveEmail by remember { mutableStateOf<Email?>(null) }
    var deleteEmail by remember { mutableStateOf<Email?>(null) }

    // Search + selection-mode UI state.
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var moveSelected by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    val selectionMode = uiState.selectedIds.isNotEmpty()

    val closeSearch = {
        viewModel.onSearchQueryChange("")
        isSearching = false
    }

    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = isSearching) { closeSearch() }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        selectionMode -> Text("${uiState.selectedIds.size} selected")

                        isSearching -> OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::onSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search email") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp)
                        )

                        else -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AccountBarTitle()
                            if (uiState.unreadCount > 0) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = MaterialTheme.colorScheme.error
                                ) {
                                    Text(
                                        text = uiState.unreadCount.toString(),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    when {
                        selectionMode -> IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }

                        isSearching -> IconButton(onClick = closeSearch) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }

                        else -> DesentLogoMark()
                    }
                },
                actions = {
                    when {
                        selectionMode -> {
                            val selected = uiState.threads.filter { it.id in uiState.selectedIds }
                            val anySelectedUnread = selected.any { !it.isRead }
                            val anySelectedSpam = selected.any { it.isSpam }

                            IconButton(onClick = {
                                if (anySelectedUnread) viewModel.markSelectedRead() else viewModel.markSelectedUnread()
                            }) {
                                Icon(
                                    imageVector = if (anySelectedUnread) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
                                    contentDescription = if (anySelectedUnread) "Mark as read" else "Mark as unread"
                                )
                            }
                            IconButton(onClick = { moveSelected = true }) {
                                Icon(Icons.Default.Folder, contentDescription = "Move to folder")
                            }
                            IconButton(onClick = {
                                if (anySelectedSpam) viewModel.markSelectedNotSpam() else viewModel.markSelectedSpam()
                            }) {
                                Icon(
                                    imageVector = if (anySelectedSpam) Icons.Default.MoveToInbox else Icons.Default.Report,
                                    contentDescription = if (anySelectedSpam) "Not spam" else "Mark as spam"
                                )
                            }
                            IconButton(onClick = { confirmDeleteSelected = true }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            IconButton(onClick = { showSelectionMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showSelectionMenu,
                                onDismissRequest = { showSelectionMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    leadingIcon = {
                                        Icon(Icons.Default.SelectAll, contentDescription = null)
                                    },
                                    onClick = {
                                        showSelectionMenu = false
                                        viewModel.selectAllVisible()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear selection") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Deselect, contentDescription = null)
                                    },
                                    onClick = {
                                        showSelectionMenu = false
                                        viewModel.clearSelection()
                                    }
                                )
                                if (uiState.selectedIds.size == 1) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("More actions") },
                                        onClick = {
                                            showSelectionMenu = false
                                            sheetEmail = selected.firstOrNull()
                                        }
                                    )
                                }
                            }
                        }

                        !isSearching -> {
                            IconButton(onClick = { isSearching = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search email")
                            }
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Mark all as read") },
                                    leadingIcon = {
                                        Icon(Icons.Default.MarkEmailRead, contentDescription = null)
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.markAllRead()
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Forward / migrate mail…") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.ForwardToInbox, contentDescription = null)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToForward()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Aliases") },
                                    leadingIcon = {
                                        Icon(Icons.Default.AlternateEmail, contentDescription = null)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToAliases()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Spam filter") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Tune, contentDescription = null)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToSpamPolicy()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Safe senders") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Image, contentDescription = null)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToImagePolicy()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCompose) {
                Icon(Icons.Default.Edit, contentDescription = "Compose email")
            }
        }
    ) { paddingValues ->
        val listState = rememberLazyListState()

        LaunchedEffect(uiState.filter, uiState.selectedFolderId) {
            listState.animateScrollToItem(0)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val showSpamEmptyState = uiState.filter == EmailFilter.SPAM && !uiState.isLoading && uiState.threads.isEmpty()

            when {
                uiState.isLoading -> {
                    InboxHeader(
                        uiState = uiState,
                        onFilterChange = { viewModel.onFilterChange(it) },
                        onFolderSelect = { viewModel.onFolderSelected(it) },
                        onNavigateToOutbox = onNavigateToOutbox,
                        onCreateFolder = { showCreateFolder = true },
                        onManageFolder = { manageFolder = it },
                        onAliasFilterSelect = { viewModel.onAliasFilterSelected(it) },
                        onResetView = { viewModel.resetViewToAll() },
                        onRefreshAliases = { viewModel.refreshAliases() },
                        onNavigateToAliases = onNavigateToAliases
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.threads.isEmpty() -> {
                    InboxHeader(
                        uiState = uiState,
                        onFilterChange = { viewModel.onFilterChange(it) },
                        onFolderSelect = { viewModel.onFolderSelected(it) },
                        onNavigateToOutbox = onNavigateToOutbox,
                        onCreateFolder = { showCreateFolder = true },
                        onManageFolder = { manageFolder = it },
                        onAliasFilterSelect = { viewModel.onAliasFilterSelected(it) },
                        onResetView = { viewModel.resetViewToAll() },
                        onRefreshAliases = { viewModel.refreshAliases() },
                        onNavigateToAliases = onNavigateToAliases
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showSpamEmptyState) {
                                Icon(
                                    imageVector = Icons.Default.Report,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "No quarantined messages",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Messages flagged by the filter appear here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (uiState.searchQuery.isNotBlank()) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "No results",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Nothing matches \"${uiState.searchQuery}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "No threads yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = onNavigateToCompose) {
                                    Text("Compose a new email")
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        item(key = "inbox-header") {
                            InboxHeader(
                                uiState = uiState,
                                onFilterChange = { viewModel.onFilterChange(it) },
                                onFolderSelect = { viewModel.onFolderSelected(it) },
                                onNavigateToOutbox = onNavigateToOutbox,
                                onCreateFolder = { showCreateFolder = true },
                                onManageFolder = { manageFolder = it },
                                onAliasFilterSelect = { viewModel.onAliasFilterSelected(it) },
                                onResetView = { viewModel.resetViewToAll() },
                                onRefreshAliases = { viewModel.refreshAliases() },
                                onNavigateToAliases = onNavigateToAliases
                            )
                        }
                        itemsIndexed(uiState.threads, key = { _, email -> email.id }) { index, email ->
                            EmailListItem(
                                email = email,
                                senderPicture = senderAvatars[email.senderEmail.lowercase()],
                                selectionMode = selectionMode,
                                selected = email.id in uiState.selectedIds,
                                mirrored = uiState.mirrored[email.id] == true,
                                onClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(email)
                                    } else if (email.isSpam) {
                                        onNavigateToSpamDetail(email.id)
                                    } else {
                                        val threadKey = email.threadKey
                                        onNavigateToThread(threadKey)
                                        viewModel.markThreadRead(threadKey)
                                    }
                                },
                                onLongClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(email)
                                    } else {
                                        viewModel.enterSelection(email)
                                    }
                                }
                            )
                            if (index < uiState.threads.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 66.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(text = error)
                }
            }

            sheetEmail?.let { email ->
                if (email.isSpam) {
                    SpamActionSheet(
                        email = email,
                        onDismiss = { sheetEmail = null },
                        onNotSpam = {
                            viewModel.markNotSpam(email)
                            sheetEmail = null
                        },
                        onDeleteForever = {
                            deleteEmail = email
                            sheetEmail = null
                        }
                    )
                } else {
                    EmailActionSheet(
                        email = email,
                        onDismiss = { sheetEmail = null },
                        onRead = {
                            viewModel.toggleRead(email)
                            sheetEmail = null
                        },
                        onReply = {
                            val threadKey = email.threadKey
                            sheetEmail = null
                            onNavigateToThread(threadKey)
                        },
                        onForward = {
                            Toast.makeText(context, "Forward — coming soon", Toast.LENGTH_SHORT).show()
                            sheetEmail = null
                        },
                        onDelete = {
                            deleteEmail = email
                            sheetEmail = null
                        },
                        onMarkAsSpam = {
                            viewModel.markAsSpam(email)
                            sheetEmail = null
                        },
                        onMoveToFolder = {
                            moveEmail = email
                            sheetEmail = null
                        }
                    )
                }
            }

            // ==================== Mail-folder dialogs ====================

            if (showCreateFolder) {
                xyz.desent.presentation.ui.email.components.FolderNameDialog(
                    title = "New folder",
                    confirmLabel = "Create",
                    onDismiss = { showCreateFolder = false },
                    onConfirm = { name ->
                        showCreateFolder = false
                        viewModel.createFolder(name)
                    }
                )
            }

            manageFolder?.let { folder ->
                xyz.desent.presentation.ui.email.components.FolderManageDialog(
                    folder = folder,
                    onDismiss = { manageFolder = null },
                    onRename = { renameFolder = folder },
                    onDelete = { deleteFolder = folder }
                )
            }

            renameFolder?.let { folder ->
                xyz.desent.presentation.ui.email.components.FolderNameDialog(
                    title = "Rename folder",
                    initialValue = folder.name,
                    confirmLabel = "Rename",
                    onDismiss = { renameFolder = null },
                    onConfirm = { name ->
                        renameFolder = null
                        viewModel.renameFolder(folder.id, name)
                    }
                )
            }

            deleteFolder?.let { folder ->
                xyz.desent.presentation.ui.email.components.DeleteFolderConfirmDialog(
                    folderName = folder.name,
                    onDismiss = { deleteFolder = null },
                    onConfirm = {
                        deleteFolder = null
                        viewModel.deleteFolder(folder.id)
                    }
                )
            }

            deleteEmail?.let { email ->
                DeleteConfirmationDialog(
                    title = if (email.isSpam) "Delete forever?" else "Delete message?",
                    message = "Permanently delete \"${email.subject.ifBlank { "(no subject)" }}\"? " +
                        "This can't be undone.",
                    onConfirm = {
                        viewModel.deleteEmail(email.id)
                        deleteEmail = null
                    },
                    onDismiss = { deleteEmail = null },
                    confirmLabel = if (email.isSpam) "Delete forever" else "Delete"
                )
            }

            moveEmail?.let { email ->
                xyz.desent.presentation.ui.email.components.MoveToFolderSheet(
                    folders = uiState.folders.map { it.folder },
                    assignedFolderId = null,
                    onDismiss = { moveEmail = null },
                    onMove = { folderId ->
                        moveEmail = null
                        viewModel.moveToFolder(email, folderId)
                    },
                    onCreateFolder = {
                        moveEmail = null
                        showCreateFolder = true
                    }
                )
            }

            if (confirmDeleteSelected) {
                DeleteConfirmationDialog(
                    title = "Delete messages?",
                    message = "Permanently delete ${uiState.selectedIds.size} selected messages? " +
                        "This can't be undone.",
                    onConfirm = {
                        confirmDeleteSelected = false
                        viewModel.deleteSelected()
                    },
                    onDismiss = { confirmDeleteSelected = false },
                    confirmLabel = "Delete"
                )
            }

            if (moveSelected) {
                xyz.desent.presentation.ui.email.components.MoveToFolderSheet(
                    folders = uiState.folders.map { it.folder },
                    assignedFolderId = null,
                    onDismiss = { moveSelected = false },
                    onMove = { folderId ->
                        moveSelected = false
                        viewModel.moveSelectedToFolder(folderId)
                    },
                    onCreateFolder = {
                        moveSelected = false
                        showCreateFolder = true
                    }
                )
            }
        }
    }
}

@Composable
private fun InboxHeader(
    uiState: EmailInboxUiState,
    onFilterChange: (EmailFilter) -> Unit,
    onFolderSelect: (String?) -> Unit,
    onNavigateToOutbox: () -> Unit,
    onCreateFolder: () -> Unit,
    onManageFolder: (MailFolder) -> Unit,
    onAliasFilterSelect: (AliasFilter) -> Unit,
    onResetView: () -> Unit,
    onRefreshAliases: () -> Unit,
    onNavigateToAliases: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InboxSegmentedControl(
                uiState = uiState,
                onFilterChange = onFilterChange,
                onFolderSelect = onFolderSelect,
                onNavigateToOutbox = onNavigateToOutbox,
                onCreateFolder = onCreateFolder,
                onManageFolder = onManageFolder,
                onAliasFilterSelect = onAliasFilterSelect,
                onResetView = onResetView,
                onRefreshAliases = onRefreshAliases,
                onNavigateToAliases = onNavigateToAliases
            )
        }
        if (uiState.selectedFolderId != null) {
            Text(
                text = "Filed messages — they also stay in All mail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        val aliasHint = when (val f = uiState.aliasFilter) {
            AliasFilter.None -> null
            AliasFilter.Primary -> "Showing mail delivered to your primary address"
            is AliasFilter.Alias -> "Showing mail delivered to ${f.email}"
        }
        aliasHint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        if (uiState.filter == EmailFilter.SPAM && uiState.lastSyncAt > 0L) {
            Text(
                text = "List updated " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                    .format(Date(uiState.lastSyncAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun InboxSegmentedControl(
    uiState: EmailInboxUiState,
    onFilterChange: (EmailFilter) -> Unit,
    onFolderSelect: (String?) -> Unit,
    onNavigateToOutbox: () -> Unit,
    onCreateFolder: () -> Unit,
    onManageFolder: (MailFolder) -> Unit,
    onAliasFilterSelect: (AliasFilter) -> Unit,
    onResetView: () -> Unit,
    onRefreshAliases: () -> Unit,
    onNavigateToAliases: () -> Unit
) {
    var viewsExpanded by remember { mutableStateOf(false) }
    val activeFolder = uiState.folders.firstOrNull { it.folder.id == uiState.selectedFolderId }
    val archivedActive = uiState.filter == EmailFilter.ARCHIVED && uiState.selectedFolderId == null
    val aliasActive = uiState.aliasFilter != AliasFilter.None
    val activeAliasRow = (uiState.aliasFilter as? AliasFilter.Alias)?.let { filter ->
        uiState.aliases.firstOrNull { it.alias.email == filter.email }
    }
    val firstSegmentLabel = when {
        activeFolder != null -> activeFolder.path
        activeAliasRow != null ->
            activeAliasRow.alias.label?.takeIf { it.isNotBlank() } ?: activeAliasRow.alias.localPart
        uiState.aliasFilter == AliasFilter.Primary -> "Primary"
        archivedActive -> "Archived"
        else -> "All"
    }
    val firstSegmentActive = activeFolder != null || archivedActive || aliasActive
    val firstSegmentColor = if (firstSegmentActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box {
                Surface(
                    onClick = {
                        // Two-stage chip: a tap while a view filter is active
                        // flips back to All mail; only a tap on All reopens
                        // the menu so the user can pick a filter again.
                        if (firstSegmentActive) onResetView() else viewsExpanded = true
                    },
                    shape = RoundedCornerShape(17.dp),
                    color = if (firstSegmentActive) {
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
                            text = firstSegmentLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = firstSegmentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Inbox views menu",
                            tint = firstSegmentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = viewsExpanded,
                    onDismissRequest = { viewsExpanded = false },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    FolderMenuRow(
                        label = "All mail",
                        badge = uiState.folders.sumOf { it.unread }.takeIf { it > 0 },
                        selected = uiState.filter == EmailFilter.ALL &&
                            uiState.selectedFolderId == null &&
                            !aliasActive,
                        onClick = {
                            viewsExpanded = false
                            onFolderSelect(null)
                            onFilterChange(EmailFilter.ALL)
                            onAliasFilterSelect(AliasFilter.None)
                        }
                    )
                    FolderMenuRow(
                        label = "Outbox",
                        leading = Icons.Default.Send,
                        selected = false,
                        onClick = {
                            viewsExpanded = false
                            onNavigateToOutbox()
                        }
                    )
                    FolderMenuRow(
                        label = "Archived",
                        leading = Icons.Default.Archive,
                        selected = archivedActive,
                        onClick = {
                            viewsExpanded = false
                            onFolderSelect(null)
                            onFilterChange(EmailFilter.ARCHIVED)
                        }
                    )
                    if (uiState.folders.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        uiState.folders.forEach { row ->
                            FolderMenuRow(
                                label = row.path,
                                badge = row.unread.takeIf { it > 0 },
                                selected = uiState.selectedFolderId == row.folder.id,
                                onClick = {
                                    viewsExpanded = false
                                    onFolderSelect(row.folder.id)
                                    onFilterChange(EmailFilter.ALL)
                                },
                                onManage = {
                                    viewsExpanded = false
                                    onManageFolder(row.folder)
                                }
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    AliasMenuSection(
                        uiState = uiState,
                        onAliasFilterSelect = onAliasFilterSelect,
                        onRefreshAliases = onRefreshAliases,
                        onNavigateToAliases = onNavigateToAliases,
                        onDismiss = { viewsExpanded = false }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    FolderMenuRow(
                        label = "New folder",
                        leading = Icons.Default.Add,
                        selected = false,
                        onClick = {
                            viewsExpanded = false
                            onCreateFolder()
                        }
                    )
                }
            }

            val spamSelected = uiState.filter == EmailFilter.SPAM
            val spamColor = if (spamSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                onClick = { onFilterChange(EmailFilter.SPAM) },
                shape = RoundedCornerShape(17.dp),
                color = if (spamSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Spam",
                        style = MaterialTheme.typography.labelMedium,
                        color = spamColor
                    )
                    if (uiState.spamCount > 0) {
                        Text(
                            text = uiState.spamCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = spamColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Filter by alias" section of the folders dropdown: the primary address
 * (mail with no alias tag) plus the account's server-side aliases. Selecting
 * a row filters the current view by delivered-to address (combines AND with
 * the selected folder).
 */
@Composable
private fun AliasMenuSection(
    uiState: EmailInboxUiState,
    onAliasFilterSelect: (AliasFilter) -> Unit,
    onRefreshAliases: () -> Unit,
    onNavigateToAliases: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Filter by alias",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        when {
            uiState.aliasesLoading -> {
                FolderMenuRow(
                    label = "Loading aliases…",
                    leading = Icons.Default.AlternateEmail,
                    selected = false,
                    onClick = {}
                )
            }
            uiState.aliasesError != null -> {
                FolderMenuRow(
                    label = "Couldn't load aliases — tap to retry",
                    leading = Icons.Default.AlternateEmail,
                    selected = false,
                    onClick = onRefreshAliases
                )
            }
            else -> {
                FolderMenuRow(
                    label = "Primary address",
                    leading = Icons.Default.Email,
                    badge = uiState.primaryUnread.takeIf { it > 0 },
                    selected = uiState.aliasFilter == AliasFilter.Primary,
                    onClick = {
                        onDismiss()
                        onAliasFilterSelect(AliasFilter.Primary)
                    }
                )
                uiState.aliases.forEach { row ->
                    FolderMenuRow(
                        label = row.alias.label?.takeIf { it.isNotBlank() }
                            ?: row.alias.localPart,
                        leading = Icons.Default.AlternateEmail,
                        badge = row.unread.takeIf { it > 0 },
                        selected = uiState.aliasFilter == AliasFilter.Alias(row.alias.email),
                        onClick = {
                            onDismiss()
                            onAliasFilterSelect(AliasFilter.Alias(row.alias.email))
                        }
                    )
                }
                if (uiState.aliases.isEmpty()) {
                    FolderMenuRow(
                        label = "Create an alias",
                        leading = Icons.Default.Add,
                        selected = false,
                        onClick = {
                            onDismiss()
                            onNavigateToAliases()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int? = null,
    leading: ImageVector? = null,
    onManage: (() -> Unit)? = null
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onManage != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onManage)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = leading ?: Icons.Default.Folder,
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
        if (onManage != null) {
            Icon(
                imageVector = Icons.Default.DriveFileRenameOutline,
                contentDescription = "Manage folder",
                tint = contentColor,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onManage)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailListItem(
    email: Email,
    senderPicture: String?,
    selectionMode: Boolean,
    selected: Boolean,
    mirrored: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            !email.isRead -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box {
                if (selectionMode) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (selected) "Selected" else "Not selected",
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    EmailSenderAvatar(
                        displayName = email.displaySender,
                        seed = email.senderEmail,
                        pictureUrl = senderPicture?.takeIf { it.isNotBlank() },
                        size = 40.dp
                    )
                    if (!email.isRead) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }
                    if (email.direction == EmailDirection.INBOUND || email.direction == EmailDirection.OUTBOUND) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (email.direction == EmailDirection.INBOUND) {
                                    Icons.Default.SouthWest
                                } else {
                                    Icons.Default.NorthEast
                                },
                                contentDescription = if (email.direction == EmailDirection.INBOUND) {
                                    "Inbound"
                                } else {
                                    "Outbound"
                                },
                                tint = if (email.direction == EmailDirection.INBOUND) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = email.displaySender,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (!email.isRead) FontWeight.SemiBold else null,
                        color = if (!email.isRead) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (email.attachments.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "${email.attachments.size} attachment(s)",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (email.isPgpEncrypted && email.direction != xyz.desent.domain.model.EmailDirection.OUTBOUND) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "PGP encrypted message",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (email.alias != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "alias",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    if (mirrored) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Mirrored to all your backup relays",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    SpfStatusBadge(email.spfStatus)
                    if (email.dkimStatus != DkimStatus.NONE) {
                        DkimStatusBadge(email.dkimStatus)
                    }
                    if (email.isSpam) {
                        Text(
                            text = "%.1f".format(email.spamScore),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (email.deletionRequested) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Deletion requested",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = formatTimestamp(email.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = email.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!email.isRead) FontWeight.SemiBold else null,
                    color = if (!email.isRead) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                emailPreview(email).takeIf { it.isNotEmpty() }?.let { preview ->
                    Text(
                        text = cappedPreview(preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DkimStatusBadge(dkimStatus: DkimStatus) {
    val tint = when (dkimStatus) {
        DkimStatus.PASS -> Color(0xFF4CAF50)
        DkimStatus.FAIL -> Color(0xFFF44336)
        DkimStatus.DISABLED -> MaterialTheme.colorScheme.outline
        DkimStatus.NONE -> MaterialTheme.colorScheme.outline
    }
    Icon(
        imageVector = Icons.Default.Check,
        contentDescription = "DKIM ${dkimStatus.name}",
        tint = tint,
        modifier = Modifier.size(16.dp)
    )
}

@Composable
private fun SpfStatusBadge(spfStatus: SpfStatus) {
    // UNKNOWN (tag absent on older messages) renders nothing to avoid noise.
    if (spfStatus == SpfStatus.UNKNOWN) return
    val tint = when (spfStatus) {
        SpfStatus.PASS -> Color(0xFF4CAF50)
        SpfStatus.FAIL -> Color(0xFFF44336)
        SpfStatus.DISABLED -> MaterialTheme.colorScheme.outline
        SpfStatus.UNKNOWN -> return
    }
    Text(
        text = "SPF",
        style = MaterialTheme.typography.labelSmall,
        color = tint
    )
}

private const val PREVIEW_CHAR_CAP = 50

private fun cappedPreview(preview: String): String =
    if (preview.length > PREVIEW_CHAR_CAP) {
        preview.take(PREVIEW_CHAR_CAP).trimEnd() + "…"
    } else {
        preview
    }

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

private fun formatFullTimestamp(timestamp: Long): String {
    return java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpamActionSheet(
    email: Email,
    onDismiss: () -> Unit,
    onNotSpam: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            StatsSection(email)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))

            SheetAction(
                icon = Icons.Default.MoveToInbox,
                label = "Not spam",
                onClick = onNotSpam
            )
            SheetAction(
                icon = Icons.Default.DeleteForever,
                label = "Delete forever",
                onClick = onDeleteForever,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmailActionSheet(
    email: Email,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onMarkAsSpam: () -> Unit,
    onMoveToFolder: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            StatsSection(email)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))

            SheetAction(
                icon = if (email.isRead) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead,
                label = if (email.isRead) "Mark as unread" else "Mark as read",
                onClick = onRead
            )
            SheetAction(icon = Icons.AutoMirrored.Filled.Reply, label = "Reply", onClick = onReply)
            SheetAction(icon = Icons.AutoMirrored.Filled.ForwardToInbox, label = "Forward", onClick = onForward)
            SheetAction(icon = Icons.Default.Folder, label = "Move to folder", onClick = onMoveToFolder)
            SheetAction(
                icon = Icons.Default.Report,
                label = "Mark as spam",
                onClick = onMarkAsSpam
            )
            SheetAction(
                icon = Icons.Default.Delete,
                label = "Delete",
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatsSection(email: Email) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (email.displaySender != email.senderEmail) {
            StatsRow("From", email.displaySender)
            StatsRow("Email", email.senderEmail)
        } else {
            StatsRow("From", email.senderEmail)
        }
        email.senderName?.let { StatsRow("Sender name", it) }
        email.replyTo?.let { StatsRow("Reply-To", it) }
        StatsRow("Subject", email.subject)
        StatsRow("Time", formatFullTimestamp(email.createdAt))
        email.senderDate?.let {
            // Show sender-claimed send time when it differs from relay time by >60s.
            if (kotlin.math.abs(it - email.createdAt) > 60_000L) {
                StatsRow("Sent", formatFullTimestamp(it))
            }
        }
        StatsRow("DKIM", email.dkimStatus.name.lowercase())
        if (email.spfStatus != xyz.desent.domain.model.SpfStatus.UNKNOWN) {
            StatsRow("SPF", email.spfStatus.name.lowercase())
        }
        if (email.dmarcStatus != xyz.desent.domain.model.DmarcStatus.UNKNOWN) {
            StatsRow("DMARC", email.dmarcStatus.name.lowercase())
        }
        email.alias?.let { StatsRow("Alias", it) }
        if (email.attachments.isNotEmpty()) {
            StatsRow("Attachments", "${email.attachments.size}")
        }
        email.toEmail?.let { StatsRow("To", it) }
        StatsRow("Format", email.bodyFormat.wireValue)
        StatsRow("Thread", email.threadKey)
        email.inReplyTo?.let { StatsRow("In-Reply-To", it) }
        StatsRow("Message ID", email.messageId ?: "—")
        StatsRow("Type", email.emailType.name.lowercase())
        StatsRow("Bridge", email.bridge)
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
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
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}
