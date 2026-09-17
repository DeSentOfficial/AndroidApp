package xyz.desent.presentation.ui.email

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ForwardToInbox
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.data.mail.MailTransferManager
import xyz.desent.domain.model.Email
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.email.viewmodel.EmailForwardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import xyz.desent.presentation.ui.components.DesentTextField

/**
 * Forward / Migrate screen (NIP-EMAIL forwarding): re-deliver stored mail to
 * another Nostr key — selected threads, or every thread (full mailbox
 * migration, e.g. moving to a new pubkey). Each message is rebuilt as a fresh
 * kind-1010 rumor (headers, DKIM verdicts and attachment keys preserved) with
 * a `forwarded_by` provenance tag, and gift-wrapped to the target key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailForwardScreen(
    onNavigateBack: () -> Unit,
    viewModel: EmailForwardViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Offline transfer (.dsme): passphrase captured first, then the SAF
    // launcher hands the chosen document to the VM together with it.
    var transferPassphrase by remember { mutableStateOf("") }
    var pendingTransfer by remember { mutableStateOf<PendingTransfer?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.writeExportTo(uri, transferPassphrase)
        }
        transferPassphrase = ""
        pendingTransfer = null
    }
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFrom(uri, transferPassphrase)
        }
        transferPassphrase = ""
        pendingTransfer = null
    }

    LaunchedEffect(uiState.transferToast) {
        uiState.transferToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearTransferToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Forward / migrate mail", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
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
                .padding(horizontal = Spacing.md)
        ) {
            Text(
                text = "Re-deliver your mail to another Nostr key — for moving to a " +
                    "new pubkey or sharing a thread. Messages are re-encrypted for the " +
                    "recipient and marked as forwarded by you. Threading and sender " +
                    "verification are preserved. New mail keeps arriving here until the " +
                    "address itself is moved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            DesentTextField(
                value = uiState.targetNpub,
                onValueChange = viewModel::onTargetChange,
                label = { Text("Target npub (migrate to)") },
                placeholder = { Text("npub1…") },
                singleLine = true,
                isError = uiState.targetNpub.isNotBlank() && !uiState.isTargetValid,
                enabled = !uiState.isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            if (uiState.isRunning) {
                LinearProgressIndicator(
                    progress = {
                        if (uiState.total > 0) uiState.processed.toFloat() / uiState.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Forwarding… ${uiState.processed}/${uiState.total}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = Spacing.sm)
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Threads (${uiState.selectedThreadKeys.size}/${uiState.threads.size} selected)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row {
                        TextButton(onClick = viewModel::selectAll, enabled = uiState.threads.isNotEmpty()) {
                            Text("Select all")
                        }
                        TextButton(
                            onClick = viewModel::clearSelection,
                            enabled = uiState.selectedThreadKeys.isNotEmpty()
                        ) {
                            Text("None")
                        }
                    }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = Spacing.xs)
                )
            }

            when {
                uiState.isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                uiState.threads.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No mail to forward",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(uiState.threads, key = { it.threadKey }) { thread ->
                        ForwardThreadRow(
                            thread = thread,
                            selected = thread.threadKey in uiState.selectedThreadKeys,
                            enabled = !uiState.isRunning,
                            onToggle = { viewModel.toggleThread(thread.threadKey) }
                        )
                        HorizontalDivider()
                    }
                }
            }

            Button(
                onClick = viewModel::startForward,
                enabled = !uiState.isRunning &&
                    uiState.isTargetValid &&
                    uiState.selectedThreadKeys.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.md)
            ) {
                if (uiState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.ForwardToInbox, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Forward ${uiState.selectedThreadKeys.size} thread(s)")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))

            // Offline fallback: encrypted file export/import, no relay needed.
            Text(
                text = "Offline transfer",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Export the same selection to an encrypted file and import it on the " +
                    "other device — no relay round-trip.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedButton(
                    onClick = { pendingTransfer = PendingTransfer.EXPORT },
                    enabled = !uiState.isTransferring && !uiState.isRunning && uiState.threads.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export")
                }
                OutlinedButton(
                    onClick = { pendingTransfer = PendingTransfer.IMPORT },
                    enabled = !uiState.isTransferring,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import")
                }
            }
        }

        pendingTransfer?.let { mode ->
            PassphraseDialog(
                title = if (mode == PendingTransfer.EXPORT) "Encrypt mail export" else "Decrypt mail import",
                isWorking = uiState.isTransferring,
                onDismiss = {
                    pendingTransfer = null
                    transferPassphrase = ""
                },
                onConfirm = { passphrase ->
                    transferPassphrase = passphrase
                    when (mode) {
                        PendingTransfer.EXPORT -> {
                            val name = "desent-mail-${
                                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                            }.${MailTransferManager.FILE_EXTENSION}"
                            createDocument.launch(name)
                        }
                        PendingTransfer.IMPORT -> openDocument.launch(
                            arrayOf("application/octet-stream", "*/*")
                        )
                    }
                }
            )
        }

        uiState.result?.let { summary ->
            AlertDialog(
                onDismissRequest = viewModel::dismissResult,
                title = { Text("Forwarding complete") },
                text = {
                    Text(
                        buildString {
                            append("${summary.sent} of ${summary.total} message(s) delivered")
                            if (summary.alreadyDelivered > 0) {
                                append("\n${summary.alreadyDelivered} already there (skipped)")
                            }
                            if (summary.failed > 0) append("\n${summary.failed} failed — re-run to retry")
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissResult) { Text("OK") }
                }
            )
        }
    }
}

@Composable
private fun ForwardThreadRow(
    thread: Email,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.displaySender,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = thread.subject,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatDate(thread.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
}

/** Which offline transfer the passphrase dialog is arming. */
private enum class PendingTransfer { EXPORT, IMPORT }

@Composable
private fun PassphraseDialog(
    title: String,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = { Text(title) },
        text = {
            DesentTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = passphrase.isNotEmpty() && passphrase.length < 4,
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.length >= 4 && !isWorking
            ) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) { Text("Cancel") }
        }
    )
}
