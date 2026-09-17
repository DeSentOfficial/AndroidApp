package xyz.desent.presentation.ui.contacts

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.PrivateContact
import xyz.desent.presentation.ui.contacts.viewmodel.ContactsViewModel
import xyz.desent.presentation.ui.contacts.viewmodel.DisplayedContact
import xyz.desent.presentation.ui.contacts.viewmodel.ContactsViewModel.Companion.nextAnniversaryLabel
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import xyz.desent.presentation.ui.components.DesentTextField

/**
 * Contacts screen (ANDROID_CONTACTS.md §3): searchable card list with
 * profile-enriched avatars and per-contact chips, vCard import/export, and
 * the detail/editor sheets. Whole card is clickable → detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onComposeEmail: (String) -> Unit,
    onAddAnniversaryToCalendar: (title: String, epochDay: Long, description: String) -> Unit,
    viewModel: ContactsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val display by viewModel.display.collectAsState()
    val context = LocalContext.current

    var showSearch by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DisplayedContact?>(null) }
    var editing by remember { mutableStateOf<DisplayedContact?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DisplayedContact?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { viewModel.importVCard(it) }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(viewModel.exportVCard())
                }
            }.onSuccess {
                Toast.makeText(context, "Contacts exported", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showSearch) {
                        DesentTextField(
                            value = uiState.query,
                            onValueChange = { viewModel.setQuery(it) },
                            placeholder = { Text("Search contacts") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        AccountBarTitle()
                    }
                },
                navigationIcon = {
                    if (showSearch) {
                        IconButton(onClick = { showSearch = false; viewModel.setQuery("") }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    } else {
                        DesentLogoMark()
                    }
                },
                actions = {
                    if (!showSearch) {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search contacts")
                        }
                        IconButton(onClick = { importLauncher.launch(arrayOf("text/vcard", "text/x-vcard", "text/*")) }) {
                            Icon(Icons.Default.Upload, contentDescription = "Import vCard")
                        }
                        IconButton(onClick = { exportLauncher.launch("desent-contacts.vcf") }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Export vCard")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add contact")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (display.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = if (uiState.query.isBlank()) "No contacts yet — add one or import a .vcf file"
                    else "No contacts match your search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(display, key = { "${it.index}-${it.contact.primaryEmail}" }) { item ->
                    ContactCard(
                        item = item,
                        onClick = { detail = item }
                    )
                }
            }
        }
    }

    detail?.let { item ->
        ContactDetailSheet(
            contact = item.contact,
            profile = item.profile,
            onCompose = onComposeEmail,
            onEdit = {
                detail = null
                editing = item
            },
            onAddAnniversaryToCalendar = onAddAnniversaryToCalendar,
            onDismiss = { detail = null }
        )
    }

    editing?.let { item ->
        ContactEditorSheet(
            existing = item.contact,
            onSave = {
                viewModel.upsertContact(item.index, it)
                editing = null
            },
            onDelete = {
                deleteTarget = item
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    if (showAdd) {
        ContactEditorSheet(
            existing = null,
            onSave = {
                viewModel.upsertContact(null, it)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete contact?") },
            text = { Text("This removes the contact from your encrypted address book on all devices.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContact(item.index)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ContactCard(item: DisplayedContact, onClick: () -> Unit) {
    val contact = item.contact
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(
                contact = contact,
                pictureUrl = item.profile?.picture?.takeIf { it.isNotBlank() },
                size = 44.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = contact.name.ifBlank {
                    item.profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: item.profile?.name?.takeIf { it.isNotBlank() }
                        ?: contact.primaryEmail.ifBlank { contact.npubOrNull() ?: "Unnamed" }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (contact.primaryEmail.isNotBlank()) {
                    val extra = contact.emails.size - 1
                    Text(
                        text = contact.primaryEmail + if (extra > 0) " (+$extra)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val chips = buildList {
                    if (contact.pubkey != null) add("⚡ nostr")
                    if (contact.phones.isNotEmpty()) add("📞 ${contact.phones.size}")
                    if (contact.wallets.isNotEmpty()) add("🪙 ${contact.wallets.size}")
                    nextAnniversaryLabel(contact)?.let { add(it) }
                }
                if (chips.isNotEmpty()) {
                    Text(
                        text = chips.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
