package xyz.desent.presentation.ui.email.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.MailFolder
import xyz.desent.presentation.theme.Spacing

/**
 * Mail-folder UI atoms (mail folders + synced read state,
 * refs/FROM_email.desent.xyz/ANDROID_MAIL_FOLDERS.md §4): the move-to-folder
 * sheet and the create/rename/delete dialogs. Folders are labels — filed mail
 * still shows in All mail. The inbox folder picker lives in
 * [xyz.desent.presentation.ui.email.EmailInboxScreen] as a dropdown chip.
 */

/** One folder row for the inbox bar: display path plus unread/total badges. */
data class MailFolderRow(
    val folder: MailFolder,
    val path: String,
    val unread: Int,
    val total: Int
)

/** Display order: roots first, then parents before children, then by name. */
fun List<MailFolder>.withPathsSorted(): List<MailFolderRow> {
    val byId = associateBy { it.id }
    val depth = mutableMapOf<String, Int>()
    fun depthOf(folder: MailFolder): Int {
        depth[folder.id]?.let { return it }
        var d = 0
        var cursor = folder
        val visited = mutableSetOf(folder.id)
        while (cursor.parent != null && visited.add(cursor.parent!!)) {
            d++
            cursor = byId[cursor.parent] ?: break
        }
        depth[folder.id] = d
        return d
    }
    return map { folder -> MailFolderRow(folder, folder.displayPath(this), 0, 0) }
        .sortedWith(
            compareBy(
                { depthOf(it.folder) },
                { it.path.lowercase() }
            )
        )
}

/** Create / rename dialog (slash-free single-segment name, spec §4). */
@Composable
fun FolderNameDialog(
    title: String,
    initialValue: String = "",
    confirmLabel: String = "Save",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialValue) }
    val valid = remember(name) { name.isNotBlank() && !name.contains('/') }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                supportingText = { Text("Single name, no slashes — nest via the folder tree.") },
                singleLine = true,
                isError = name.isNotEmpty() && !valid
            )
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(name.trim()) }, enabled = valid) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Delete confirmation: mail stays in All mail, only the folder goes. */
@Composable
fun DeleteFolderConfirmDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$folderName\"?") },
        text = {
            Text(
                "Messages filed here stay in All mail — only the folder " +
                    "and its assignments are removed, on this device and your others."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Move-to-folder sheet (per-message action): the folder list, "— No folder —"
 * to unfile, and "+ New folder…" for filing into a folder that doesn't exist
 * yet. Mirrors the web `#mfMoveModal`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToFolderSheet(
    folders: List<MailFolder>,
    assignedFolderId: String?,
    onDismiss: () -> Unit,
    onMove: (folderId: String?) -> Unit,
    onCreateFolder: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Move to folder", style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))

            MoveRow(
                icon = Icons.Default.FolderOff,
                label = "No folder",
                selected = assignedFolderId == null,
                onClick = { onMove(null) }
            )
            folders.forEach { folder ->
                MoveRow(
                    icon = Icons.AutoMirrored.Filled.Label,
                    label = folder.displayPath(folders),
                    selected = assignedFolderId == folder.id,
                    onClick = { onMove(folder.id) }
                )
            }
            MoveRow(
                icon = Icons.Default.CreateNewFolder,
                label = "New folder…",
                selected = false,
                onClick = onCreateFolder
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MoveRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Folder chip shown on the detail screen when the message is filed. */
@Composable
fun FolderAssignmentChip(
    folderPath: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = folderPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** Rename / delete picker for a long-pressed folder chip. */
@Composable
fun FolderManageDialog(
    folder: MailFolder,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = { Text("What would you like to do with this folder?") },
        confirmButton = {
            Row {
                TextButton(onClick = { onDismiss(); onDelete() }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
                TextButton(onClick = { onDismiss(); onRename() }) {
                    Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rename")
                }
            }
        }
    )
}

/** Dropdown items for a folder chip's long-press (rename / delete). */
@Composable
fun FolderManageDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
            onClick = { onDismiss(); onRename() }
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}
