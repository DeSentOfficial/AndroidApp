package xyz.desent.presentation.ui.notes

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.desent.di.AppContainer
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.presentation.ui.notes.viewmodel.NoteEditorUiState
import xyz.desent.presentation.ui.notes.viewmodel.NoteEditorViewModel
import xyz.desent.presentation.ui.notes.viewmodel.NoteViewMode
import xyz.desent.presentation.ui.components.BorderlessTextField
import xyz.desent.presentation.ui.components.EditorBarIcon
import xyz.desent.presentation.ui.components.EditorBottomBar
import xyz.desent.presentation.ui.components.MarkdownDocument
import xyz.desent.presentation.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: NoteEditorViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount by remember {
        AppContainer.getInstance(context).sessionManager.activeAccount
    }.collectAsState()

    // The body as an editable field value, hoisted so the bottom bar's
    // markdown actions (bold / italic / list) can transform the selection.
    // External body changes (note load, draft restore) sync in cursor-to-end;
    // typing flows straight through, so the caret is never stolen.
    val bodyField = remember { mutableStateOf(TextFieldValue(uiState.body)) }
    LaunchedEffect(uiState.body) {
        if (uiState.body != bodyField.value.text) {
            bodyField.value = TextFieldValue(uiState.body, selection = TextRange(uiState.body.length))
        }
    }
    val applyBodyEdit: (TextFieldValue) -> Unit = { transformed ->
        bodyField.value = transformed
        viewModel.onBodyChange(transformed.text)
    }

    // Flush the in-progress draft to disk when the screen stops (backgrounded,
    // screen lock, etc.) so it can be recovered even if the process is killed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.persistDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    // A decrypted attachment is ready — hand it to the system viewer.
    LaunchedEffect(Unit) {
        viewModel.openEvents.collect { event ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(event.uri, event.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Open attachment"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val handlePicked: (Uri?, String) -> Unit = { uri, fallbackMime ->
        if (uri != null) {
            scope.launch {
                val mime = context.contentResolver.getType(uri) ?: fallbackMime
                val name = uri.lastPathSegment ?: "attachment"
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                viewModel.addAttachment(bytes, mime, name)
            }
        }
    }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        handlePicked(uri, "application/octet-stream")
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        handlePicked(uri, "image/jpeg")
    }

    // In edit mode for an existing note, the system back / arrow returns to the rendered
    // view instead of popping the screen.
    val handleBack: () -> Unit = {
        if (uiState.mode == NoteViewMode.EDIT && uiState.noteId != null) {
            viewModel.cancelEdit()
        } else {
            onNavigateBack()
        }
    }
    BackHandler(enabled = uiState.mode == NoteViewMode.EDIT && uiState.noteId != null) { handleBack() }

    Scaffold(
        // Lift the whole editor above the IME while typing. adjustResize no
        // longer resizes the window under enforced edge-to-edge (targetSdk 36).
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.mode == NoteViewMode.VIEW) {
                        Text(if (uiState.title.isNotBlank()) uiState.title else "(untitled)")
                    }
                },
                navigationIcon = {
                    // Editing an existing note: close returns to the rendered
                    // view; otherwise it pops the screen.
                    if (uiState.mode == NoteViewMode.VIEW) {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                },
                actions = {
                    if (uiState.mode == NoteViewMode.VIEW) {
                        IconButton(onClick = { viewModel.enterEdit() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.save() },
                            enabled = !uiState.isSaving,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Save")
                            }
                        }
                        Spacer(Modifier.width(Spacing.xs))
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.mode == NoteViewMode.EDIT) {
                EditorBottomBar(account = activeAccount) {
                    EditorBarIcon(
                        onClick = { applyBodyEdit(bodyField.value.wrapSelection("**", "**")) },
                        enabled = !uiState.isSaving
                    ) {
                        Text(
                            text = "B",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    EditorBarIcon(
                        onClick = { applyBodyEdit(bodyField.value.wrapSelection("*", "*")) },
                        enabled = !uiState.isSaving
                    ) {
                        Text(
                            text = "I",
                            style = MaterialTheme.typography.titleMedium,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif
                        )
                    }
                    EditorBarIcon(
                        onClick = { applyBodyEdit(bodyField.value.prefixSelectedLines("- ")) },
                        enabled = !uiState.isSaving
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                            contentDescription = "Bullet list",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    EditorBarIcon(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = !uiState.isSaving && !uiState.isUploading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Add image",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    EditorBarIcon(
                        onClick = { attachmentPicker.launch("*/*") },
                        enabled = !uiState.isSaving && !uiState.isUploading
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Add attachment",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (uiState.mode == NoteViewMode.VIEW) {
            NoteViewContent(
                state = uiState,
                openingSha = uiState.openingSha,
                onOpenAttachment = viewModel::openAttachment,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            NoteEditContent(
                state = uiState,
                bodyField = bodyField,
                onTitleChange = viewModel::onTitleChange,
                onFolderChange = viewModel::onFolderChange,
                onBodyChange = viewModel::onBodyChange,
                onRemoveAttachment = viewModel::removeAttachment,
                onOpenAttachment = viewModel::openAttachment,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteViewContent(
    state: NoteEditorUiState,
    openingSha: String?,
    onOpenAttachment: (AttachmentMeta) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = state.title.ifBlank { "(untitled)" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (state.folder.isNotBlank() || state.updatedAt > 0L) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.folder.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        leadingIcon = {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(state.folder, style = MaterialTheme.typography.labelMedium) }
                    )
                }
                if (state.updatedAt > 0L) {
                    val editedDate = remember(state.updatedAt) {
                        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                            .format(Date(state.updatedAt * 1000))
                    }
                    Text(
                        text = "Edited $editedDate",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        if (state.body.isBlank()) {
            Text(
                text = "No content",
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Inline `![alt](attachment:<sha>)` refs are rewritten to their
            // locally decrypted image URIs before rendering (failures fall
            // back to the alt text); non-images stay on the chips below.
            val renderedBody = remember(state.body, state.inlineImages) {
                var out = state.body
                state.inlineImages.forEach { (sha, uri) ->
                    out = out.replace("attachment:$sha", uri)
                }
                out
            }
            MarkdownDocument(
                content = renderedBody,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (state.attachments.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Attachments (${state.attachments.size})",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.attachments.forEach { meta ->
                    AttachmentChip(
                        meta = meta,
                        isOpening = openingSha == meta.sha256,
                        onOpen = { onOpenAttachment(meta) }
                    )
                }
            }
        }
    }
}

/**
 * Edit layout: borderless title / folder / body over the same open-canvas
 * pattern as the mail editors. Attachment picking lives in the bottom bar
 * ([NoteEditorScreen]); the chips below only manage what's already attached.
 */
@Composable
private fun NoteEditContent(
    state: NoteEditorUiState,
    bodyField: MutableState<TextFieldValue>,
    onTitleChange: (String) -> Unit,
    onFolderChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (AttachmentMeta) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = Spacing.lg)) {
        Spacer(Modifier.height(Spacing.sm))
        BorderlessTextField(
            value = state.title,
            onValueChange = onTitleChange,
            placeholder = "Title",
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth()
        )
        BorderlessTextField(
            value = state.folder,
            onValueChange = onFolderChange,
            placeholder = "Folder (e.g. Work/Projects)",
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        BorderlessTextField(
            value = bodyField.value,
            onValueChange = {
                bodyField.value = it
                onBodyChange(it.text)
            },
            placeholder = "Write your note…",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Text(
            text = "Attachments (${state.attachments.size})",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(state.attachments, key = { it.sha256 }) { meta ->
                AttachmentChip(
                    meta = meta,
                    isOpening = state.openingSha == meta.sha256,
                    onOpen = { onOpenAttachment(meta) },
                    onRemove = { onRemoveAttachment(meta.sha256) }
                )
            }
        }

        if (state.isSaving) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Spacing.sm))
    }
}

/**
 * Markdown bold/italic-style wraps around the current selection; with no
 * selection the tokens are inserted for typing straight into.
 */
private fun TextFieldValue.wrapSelection(prefix: String, suffix: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    return if (start == end) {
        val newText = text.substring(0, start) + prefix + suffix + text.substring(start)
        TextFieldValue(newText, TextRange(start + prefix.length))
    } else {
        val selected = text.substring(start, end)
        val newText = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
        TextFieldValue(newText, TextRange(start + prefix.length, end + prefix.length))
    }
}

/** Prefixes every line touched by the selection (used for "- " bullets). */
private fun TextFieldValue.prefixSelectedLines(prefix: String): TextFieldValue {
    if (text.isEmpty()) {
        return TextFieldValue(prefix, TextRange(prefix.length))
    }
    val selStart = selection.min.coerceIn(0, text.length)
    val selEnd = selection.max.coerceIn(0, text.length)
    val blockStart = if (selStart == 0) 0 else text.lastIndexOf('\n', selStart - 1) + 1
    val blockEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }
    val prefixed = text
        .substring(blockStart, blockEnd)
        .lineSequence()
        .joinToString("\n") { line -> if (line.isBlank()) line else prefix + line }
    val newText = text.substring(0, blockStart) + prefixed + text.substring(blockEnd)
    return TextFieldValue(newText, TextRange(blockStart + prefixed.length))
}

@Composable
private fun AttachmentChip(
    meta: AttachmentMeta,
    isOpening: Boolean,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    InputChip(
        selected = false,
        onClick = onOpen,
        enabled = !isOpening,
        shape = RoundedCornerShape(50),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isOpening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = meta.filename.ifBlank { meta.sha256.take(12) },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingIcon = if (onRemove != null) {
            {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier
                        .clickable { onRemove() }
                        .padding(6.dp)
                        .size(16.dp)
                )
            }
        } else null
    )
}
