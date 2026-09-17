package xyz.desent.presentation.ui.files

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.desent.domain.usecase.PrivateStorageUseCase
import xyz.desent.presentation.ui.files.viewmodel.FilesRow
import xyz.desent.presentation.ui.files.viewmodel.FilesViewModel
import xyz.desent.presentation.ui.files.viewmodel.PendingPreview
import xyz.desent.presentation.ui.files.viewmodel.RowOpenState
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import xyz.desent.presentation.ui.components.StorageUsageBar
import xyz.desent.presentation.ui.components.SummaryStat
import xyz.desent.presentation.ui.components.TierBadge
import xyz.desent.presentation.ui.components.UpgradeBanner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sheetRow by remember { mutableStateOf<FilesRow?>(null) }
    var deleteRow by remember { mutableStateOf<FilesRow?>(null) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { viewModel.clearError() }
    }

    // allowDialog = picker path (one file, user is watching): images open the
    // preview-quality dialog. The share-intent drain uploads directly with the
    // stored default detail — there's no room for a per-file dialog there.
    fun handlePicked(uri: Uri?, allowDialog: Boolean) {
        if (uri != null) {
            scope.launch {
                val size = queryLong(context, uri, OpenableColumns.SIZE)
                if (size != null && size > PrivateStorageUseCase.MAX_USER_FILE_BYTES) {
                    Toast.makeText(context, "File exceeds the 25 MiB limit", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val name = queryString(context, uri, OpenableColumns.DISPLAY_NAME) ?: "file"
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) {
                    Toast.makeText(context, "Couldn't read the selected file", Toast.LENGTH_SHORT).show()
                } else if (allowDialog && mime.startsWith("image/")) {
                    viewModel.beginImageUpload(bytes, mime, name)
                } else {
                    viewModel.uploadFile(bytes, mime, name)
                }
            }
        }
    }

    // Share-intent target (END-23 §1): URIs stashed by MainActivity drain here.
    LaunchedEffect(Unit) {
        viewModel.pendingUploads.uploads.collect { uris ->
            if (uris.isNotEmpty()) {
                viewModel.consumePendingUploads()
                uris.forEach { handlePicked(it, allowDialog = false) }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        handlePicked(uri, allowDialog = true)
    }

    val orphaned = uiState.rows.count { it.isOrphan }
    val totalSize = uiState.rows.sumOf { it.serverFile.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AccountBarTitle() },
                navigationIcon = {
                    DesentLogoMark()
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!uiState.isUploading) filePicker.launch("*/*") }
            ) {
                if (uiState.isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(Icons.Default.UploadFile, contentDescription = "Upload encrypted file")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            uiState.tierInfo?.let { tier ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Plan",
                                style = MaterialTheme.typography.titleSmall
                            )
                            TierBadge(tier = tier.tier)
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        // storageUsed is the UNIFIED quota (blobs + mail +
                        // notes + …), not just files. Don't fall back to the
                        // local blob sum — render the bar only when the server
                        // has reported the aggregate number.
                        if (tier.storageUsed != null) {
                            StorageUsageBar(
                                used = tier.storageUsed,
                                cap = tier.storageCap
                            )
                        }
                        if (!tier.isPaid) {
                            Spacer(Modifier.height(Spacing.sm))
                            UpgradeBanner(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (uiState.rows.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SummaryStat("Files", "${uiState.rows.size}")
                        SummaryStat("Size", formatSize(totalSize))
                        if (orphaned > 0) {
                            SummaryStat("Orphaned", "$orphaned", highlight = true)
                        }
                    }
                }
            }

            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(Spacing.md),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(onClick = { viewModel.refresh() }) { Text("Retry") }
                    }
                }
                uiState.rows.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Folder, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                "No files stored",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.rows, key = { it.sha256 }) { row ->
                            RowCard(
                                row = row,
                                openState = uiState.openStates[row.sha256],
                                onClick = { sheetRow = row }
                            )
                        }
                    }
                }
            }
        }
    }

    sheetRow?.let { row ->
        RowDetailSheet(
            row = row,
            openState = uiState.openStates[row.sha256],
            onDismiss = { sheetRow = null },
            onOpen = { viewModel.openRow(row) },
            onDelete = {
                sheetRow = null
                deleteRow = row
            },
            onAdjustPreview = {
                sheetRow = null
                row.userFile?.let { viewModel.beginReencode(it) }
            }
        )
    }

    deleteRow?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteRow = null },
            title = { Text("Delete file?") },
            text = {
                Text(
                    if (row.isUpload) {
                        "Permanently delete ${row.filename ?: row.sha256.take(12)}? The encrypted " +
                            "blob and its key entry will be removed from the server — this cannot be undone."
                    } else {
                        "Permanently delete ${row.filename ?: row.sha256.take(12)}? " +
                            "Anything that still references it will fail to download afterwards."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRow(row)
                        deleteRow = null
                    },
                    enabled = !uiState.isDeleting
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteRow = null }) { Text("Cancel") }
            }
        )
    }

    uiState.pendingPreview?.let { pending ->
        when (pending) {
            is PendingPreview.ReencodeLoading -> AlertDialog(
                onDismissRequest = { viewModel.cancelPendingPreview() },
                title = { Text("Adjusting preview…") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.md))
                        Text("Decrypting ${pending.file.filename.ifBlank { "file" }}…")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.cancelPendingPreview() }) { Text("Cancel") }
                }
            )
            is PendingPreview.NewUpload, is PendingPreview.Reencode -> PreviewQualityDialog(
                pending = pending,
                onComponents = { viewModel.setPreviewComponents(it) },
                onConfirm = { viewModel.confirmPendingPreview() },
                onDismiss = { viewModel.cancelPendingPreview() }
            )
        }
    }
}

/**
 * Blur-detail slider with a live preview. Blurhash detail is fixed at encode
 * time (the component grid is part of the hash), so the slider re-encodes:
 * fewer components = blurrier, more = sharper, 8 → the END-23 8×6 default.
 */
@Composable
private fun PreviewQualityDialog(
    pending: PendingPreview,
    onComponents: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isReencode = pending is PendingPreview.Reencode
    val components = when (pending) {
        is PendingPreview.NewUpload -> pending.components
        is PendingPreview.Reencode -> pending.components
        is PendingPreview.ReencodeLoading -> return
    }
    val blurhash = when (pending) {
        is PendingPreview.NewUpload -> pending.blurhash
        is PendingPreview.Reencode -> pending.blurhash
        is PendingPreview.ReencodeLoading -> return
    }
    val numY = maxOf(1, components * 3 / 4)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preview quality") },
        text = {
            Column {
                val bitmap = remember(blurhash) { Blurhash.decode(blurhash, 256, 256) }
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Live preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BlurOn, contentDescription = "Blurrier",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Slider(
                        value = components.toFloat(),
                        onValueChange = { onComponents(it.toInt()) },
                        valueRange = 1f..9f,
                        steps = 7,
                        modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm)
                    )
                    Icon(
                        Icons.Default.BlurOff, contentDescription = "Sharper",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Detail $components×$numY components — " +
                        when {
                            components <= 3 -> "very blurry"
                            components <= 6 -> "balanced"
                            else -> "sharpest available"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pending is PendingPreview.Reencode) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "Updates the stored preview for \"${pending.file.filename}\" — " +
                            "only the entry is republished, the file isn't re-uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (isReencode) "Save" else "Upload") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RowCard(
    row: FilesRow,
    openState: RowOpenState?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(row, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.filename ?: row.sha256.take(16),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(formatSize(row.serverFile.size))
                        rowDisplayMime(row)?.let { append(" · ").append(it) }
                        rowDisplayDate(row)?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (openState) {
                is RowOpenState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                )
                is RowOpenState.Ready, is RowOpenState.SavedFile -> Icon(
                    Icons.Default.DownloadDone, contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                )
                else -> KindChip(row)
            }
        }
    }
}

/** Display MIME with upload/note priority over the server label. */
private fun rowDisplayMime(row: FilesRow): String? =
    row.userFile?.mimeType?.ifBlank { null }
        ?: row.noteMeta?.mimeType?.ifBlank { null }
        ?: row.serverFile.mimeType

private fun rowDisplayDate(row: FilesRow): String? =
    row.userFile?.let { formatDateEpoch(it.uploadedAt) } ?: row.serverFile.createdAt?.let { formatDate(it) }

@Composable
private fun Thumbnail(row: FilesRow, modifier: Modifier = Modifier) {
    val mime = rowDisplayMime(row)
    val blurhash = row.userFile?.blurhash ?: row.serverFile.blurhash
    if (mime?.startsWith("image/") == true && !blurhash.isNullOrBlank()) {
        // Decode at thumbnail render resolution — the analytic decode is no
        // blurrier than the hash allows, but a 32 px raster upscaled to 48 dp
        // needlessly softens it on high-density screens.
        val bitmap = remember(row.sha256, blurhash) {
            Blurhash.decode(blurhash, 144, 144)
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Preview",
                modifier = modifier.clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            FallbackIcon(mime, modifier)
        }
    } else {
        FallbackIcon(mime, modifier)
    }
}

@Composable
private fun FallbackIcon(mimeType: String?, modifier: Modifier = Modifier) {
    val icon = when {
        mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
        mimeType == "application/zip" -> Icons.Default.FolderZip
        mimeType?.startsWith("image/") == true -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * END-23 §1 pills — where the row's decryption key lives:
 * upload (`desent:file:` entry), note (note attachments[]), attachment
 * (email gift-wrap), or orphan (key gone).
 */
@Composable
private fun KindChip(row: FilesRow) {
    val (text, color) = when {
        row.isUpload -> "Upload" to MaterialTheme.colorScheme.primary
        row.isNote -> "Note" to MaterialTheme.colorScheme.tertiary
        row.isOrphan -> "Orphaned" to MaterialTheme.colorScheme.error
        else -> "Attachment" to MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowDetailSheet(
    row: FilesRow,
    openState: RowOpenState?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onAdjustPreview: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Text(
                text = row.filename ?: "(unnamed)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            DetailRow(
                "Status",
                when {
                    row.isUpload -> "Upload"
                    row.isNote -> "Note attachment"
                    row.isOrphan -> "Orphaned"
                    else -> "Attachment"
                }
            )
            DetailRow("Type", rowDisplayMime(row) ?: "—")
            DetailRow("Size", formatSize(row.serverFile.size))
            val width = row.userFile?.width?.takeIf { it > 0 } ?: row.serverFile.width
            val height = row.userFile?.height?.takeIf { it > 0 } ?: row.serverFile.height
            if (width != null && height != null) DetailRow("Dimensions", "${width}×$height")
            DetailRow("SHA256", row.sha256.take(24) + "…")
            if (row.isUpload) {
                DetailRow("Uploaded", formatDateEpoch(row.userFile!!.uploadedAt))
                DetailRow("Encryption", "AES-256-GCM · key held by your Nostr key")
            } else {
                row.serverFile.createdAt?.let { DetailRow("Created", formatDate(it)) }
            }
            HorizontalDivider(Modifier.padding(vertical = Spacing.md))

            when {
                row.isUpload -> Text(
                    text = "End-to-end encrypted: the server stores only ciphertext. " +
                        "The file key syncs privately with your account and is unavailable without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                row.isNote -> Text(
                    text = "Attached to a private note — the key lives inside your " +
                        "encrypted note storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                row.isOrphan -> Text(
                    text = "Orphaned: the message that delivered this file was deleted, so the " +
                        "decryption key is gone. The bytes can't be recovered — only deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (row.isUpload || row.isNote || row.isOrphan) Spacer(Modifier.height(8.dp))

            // We hold the key for uploads → the stored preview can be
            // re-encoded at a different blurhash detail level.
            if (row.isUpload) {
                OutlinedButton(
                    onClick = onAdjustPreview,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Adjust preview quality")
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.canDownload) {
                    OutlinedButton(
                        onClick = {
                            when (openState) {
                                is RowOpenState.Ready -> launchUri(context, openState)
                                is RowOpenState.SavedFile -> openFile(context, openState.file, openState.mimeType)
                                else -> onOpen()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (openState is RowOpenState.Ready || openState is RowOpenState.SavedFile) "Open"
                            else "Download"
                        )
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = if (row.canDownload) Modifier.weight(1f) else Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun openFile(context: android.content.Context, file: File, mimeType: String?) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType?.ifBlank { "*/*" } ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open file"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Launch the system viewer for an already-decrypted user/note file. */
private fun launchUri(context: android.content.Context, state: RowOpenState.Ready) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(state.uri, state.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open file"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun queryString(context: android.content.Context, uri: Uri, column: String): String? =
    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(column)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }

private fun queryLong(context: android.content.Context, uri: Uri, column: String): Long? =
    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(column)
        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
    }

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
private fun formatDate(iso: String): String {
    return try {
        val d = isoFormat.parse(iso.substringBefore('.'))
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(d ?: Date())
    } catch (e: Exception) {
        iso.take(10)
    }
}

private val epochFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private fun formatDateEpoch(epochSeconds: Long): String = epochFormat.format(Date(epochSeconds * 1000))
