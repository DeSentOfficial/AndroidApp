package xyz.desent.presentation.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DesentTextField
import xyz.desent.presentation.ui.settings.viewmodel.PgpPublishedState
import xyz.desent.presentation.ui.settings.viewmodel.PgpSettingsViewModel

/**
 * PGP key management (ANDROID_PGP.md §2): one key per account. Generate a
 * passphraseless curve25519 key or import an existing one; the public half
 * is registered with the relay and served over WKD for every address the
 * user owns. Hidden entirely when the relay's `pgp_enabled` switch is off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgpSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: PgpSettingsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PGP Encryption") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = "End-to-end OpenPGP encryption for your mail. Your private key " +
                    "never leaves this device unencrypted. It syncs only as your own " +
                    "private Nostr storage, sealed to your account key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                !uiState.featureEnabled -> {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    "PGP is disabled on this relay",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                    }
                }

                uiState.keyInfo != null -> {
                    KeyInfoCard(
                        viewModel = viewModel,
                        onRemove = { showRemoveDialog = true }
                    )
                }

                else -> {
                    NoKeyCard(
                        viewModel = viewModel,
                        onImport = { showImportDialog = true }
                    )
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showImportDialog) {
        ImportKeyDialog(
            isWorking = uiState.isWorking,
            onDismiss = { showImportDialog = false },
            onImport = { armor, passphrase ->
                viewModel.importKey(armor, passphrase)
                showImportDialog = false
            }
        )
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove PGP key?") },
            text = {
                Text(
                    "Your address stops publishing a PGP key and encrypted mail " +
                        "can no longer be decrypted on any device. Messages already " +
                        "stored encrypted become unreadable."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        viewModel.removeKey()
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NoKeyCard(
    viewModel: PgpSettingsViewModel,
    onImport: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text("No PGP key on this account", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = "Generate a key here, or import an existing one. External " +
                    "PGP mailers (Proton, Thunderbird, GnuPG) discover it automatically " +
                    "via WKD and can send you encrypted mail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = viewModel::generateKey,
                    enabled = !uiState.isWorking
                ) {
                    if (uiState.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text("Generate key")
                }
                OutlinedButton(onClick = onImport, enabled = !uiState.isWorking) {
                    Text("Import")
                }
            }
        }
    }
}

@Composable
private fun KeyInfoCard(
    viewModel: PgpSettingsViewModel,
    onRemove: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val info = uiState.keyInfo ?: return

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "PGP key ${if (info.source == "imported") "(imported)" else "(generated)"}",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                text = "Fingerprint",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = info.prettyFingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (uiState.published) {
                        PgpPublishedState.Published -> "Published via WKD"
                        PgpPublishedState.NotPublished -> "Not published (key stored locally)"
                        PgpPublishedState.Checking -> "Checking publish state…"
                        PgpPublishedState.Unknown -> "Checking publish state…"
                        is PgpPublishedState.Error -> "Publish state unknown"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::refresh) { Text("Refresh") }
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-encrypt replies", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Pre-check the lock when the recipient has a discoverable key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.autoEncrypt,
                    onCheckedChange = viewModel::setAutoEncrypt
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !uiState.isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Remove key", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ImportKeyDialog(
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onImport: (armor: String, passphrase: String?) -> Unit
) {
    var armor by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import PGP key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Paste an armored private key block. If it is protected by a " +
                        "passphrase it is unlocked once here and stored without it.",
                    style = MaterialTheme.typography.bodySmall
                )
                DesentTextField(
                    value = armor,
                    onValueChange = { armor = it },
                    label = { Text("Private key block") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                )
                DesentTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(armor, passphrase.takeIf { it.isNotBlank() }) },
                enabled = !isWorking && armor.contains("BEGIN")
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
