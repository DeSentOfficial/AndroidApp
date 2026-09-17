package xyz.desent.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.settings.component.SectionHeader
import xyz.desent.presentation.ui.settings.component.SwitchSetting
import xyz.desent.presentation.ui.settings.viewmodel.ImagePolicyViewModel
import xyz.desent.presentation.ui.components.DesentTextField

/**
 * "Safe senders" — the remote-image policy UI (see refs/SPAM_FILTER_REFERENCE.md).
 * Images in mail are blocked unless the sender is on one of these lists or is
 * a contact. Every change syncs privately to the user's devices via NIP-78.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePolicyScreen(
    onNavigateBack: () -> Unit,
    viewModel: ImagePolicyViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Safe senders") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            val config = state.config

            // --- Master toggle ---
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    SwitchSetting(
                        title = "Block remote images",
                        description = "Remote images stay unloaded until you trust the sender.",
                        checked = config.blockRemoteImages,
                        onCheckedChange = viewModel::setBlockRemoteImages
                    )
                }
            }

            // --- Contacts ---
            SectionHeader("Contacts")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    SwitchSetting(
                        title = "Always show from contacts",
                        description = when (state.contactCount) {
                            0 -> "Images load automatically for senders in your contacts."
                            else -> "Images load automatically for your ${state.contactCount} contact(s)."
                        },
                        checked = config.imagesAllowedForContacts,
                        onCheckedChange = viewModel::setImagesAllowedForContacts,
                        enabled = config.blockRemoteImages
                    )
                }
            }

            // --- Allowed senders ---
            SectionHeader("Allowed senders")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    ListEditor(
                        entries = config.imageAllowedSenders,
                        emptyText = "No allowed senders yet. Add an exact address.",
                        placeholder = "name@example.com",
                        onAdd = viewModel::addSender,
                        onRemove = viewModel::removeSender
                    )
                }
            }

            // --- Allowed domains ---
            SectionHeader("Allowed domains")
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    ListEditor(
                        entries = config.imageAllowedDomains,
                        emptyText = "No allowed domains yet. Every sender on an allowed domain is trusted.",
                        placeholder = "example.com",
                        onAdd = viewModel::addDomain,
                        onRemove = viewModel::removeDomain
                    )
                }
            }

            Text(
                text = "Synced privately to your devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ListEditor(
    entries: List<String>,
    emptyText: String,
    placeholder: String,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (entries.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onRemove(entry) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove $entry",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DesentTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    if (onAdd(input)) input = ""
                },
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    }
}
