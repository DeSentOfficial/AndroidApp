package xyz.desent.presentation.ui.markdown

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.components.MarkdownDocument
import xyz.desent.presentation.ui.markdown.viewmodel.MarkdownViewMode
import xyz.desent.presentation.ui.markdown.viewmodel.MarkdownViewerEvent
import xyz.desent.presentation.ui.markdown.viewmodel.MarkdownViewerViewModel
import xyz.desent.presentation.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownViewerScreen(
    onNavigateBack: () -> Unit,
    viewModel: MarkdownViewerViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // The source location is read-only (ACTION_VIEW grants carry no write
    // permission) — the ViewModel asks us to launch the system's
    // create-document picker for an edited copy instead.
    val saveCopyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) viewModel.saveCopyTo(uri.toString())
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is MarkdownViewerEvent.LaunchSaveCopy) {
                saveCopyPicker.launch(uiState.fileName.ifBlank { "document.md" })
            }
        }
    }

    val handleBack: () -> Unit = {
        if (uiState.mode == MarkdownViewMode.EDIT && uiState.loadError == null) {
            viewModel.cancelEdit()
        } else {
            onNavigateBack()
        }
    }
    BackHandler(enabled = uiState.mode == MarkdownViewMode.EDIT) { handleBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.fileName.ifBlank { "Markdown" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val documentReady = !uiState.isLoading && uiState.loadError == null
                    if (uiState.mode == MarkdownViewMode.VIEW) {
                        IconButton(onClick = viewModel::enterEdit, enabled = documentReady) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        if (documentReady) {
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save as private note") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Description, contentDescription = null)
                                    },
                                    enabled = !uiState.isSavingAsNote,
                                    onClick = {
                                        menuOpen = false
                                        viewModel.saveAsNote()
                                    }
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = viewModel::save,
                            enabled = !uiState.isSaving && uiState.isDirty
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.loadError != null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = uiState.loadError ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = viewModel::retry, modifier = Modifier.padding(top = Spacing.md)) {
                        Text("Retry")
                    }
                }
            }
            uiState.mode == MarkdownViewMode.VIEW -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md, vertical = Spacing.md)
                ) {
                    MarkdownDocument(
                        content = uiState.body,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.md)
                ) {
                    if (uiState.isSaving) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    DesentTextField(
                        value = uiState.body,
                        onValueChange = viewModel::onBodyChange,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        minLines = 6
                    )
                }
            }
        }
    }
}
