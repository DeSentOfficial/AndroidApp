package xyz.desent.presentation.ui.email

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.EmailOutboxEntry
import xyz.desent.domain.model.OutboxStatus
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DeleteConfirmationDialog
import xyz.desent.presentation.ui.components.AccountBarTitle
import xyz.desent.presentation.ui.components.DesentLogoMark
import xyz.desent.presentation.ui.email.viewmodel.EmailOutboxViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Outbox: every outbound email this account has sent through the bridge,
 * with its delivery state (sending / delivered / failed / timed out). Failed
 * and timed-out sends retry with one tap; resolved entries can be cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailOutboxScreen(
    onNavigateToThread: (String) -> Unit,
    viewModel: EmailOutboxViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var deleteEntry by remember { mutableStateOf<EmailOutboxEntry?>(null) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { viewModel.clearError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AccountBarTitle() },
                navigationIcon = {
                    DesentLogoMark()
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }

                uiState.entries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "Nothing sent yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Emails you send appear here with their delivery status.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.entries, key = { it.messageId }) { entry ->
                            OutboxRow(
                                entry = entry,
                                onClick = {
                                    if (entry.hasThread) onNavigateToThread(entry.threadKey)
                                },
                                onRetry = { viewModel.retry(entry.messageId) },
                                onDelete = { deleteEntry = entry }
                            )
                        }
                    }
                }
            }

            uiState.error?.let { error ->
                Snackbar(modifier = Modifier.padding(16.dp)) { Text(error) }
            }
        }
    }

    deleteEntry?.let { entry ->
        DeleteConfirmationDialog(
            title = "Remove outbox entry?",
            message = "Remove the entry for \"${entry.toEmail}\"? " +
                "The sent message itself isn't affected.",
            onConfirm = {
                viewModel.delete(entry.messageId)
                deleteEntry = null
            },
            onDismiss = { deleteEntry = null },
            confirmLabel = "Remove"
        )
    }
}

@Composable
private fun OutboxRow(
    entry: EmailOutboxEntry,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .then(if (entry.hasThread) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(entry.status)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = entry.toEmail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.canRetry) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (entry.status != OutboxStatus.PENDING) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove from outbox",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = entry.subject,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            entry.errorMessage?.let { error ->
                Text(
                    text = error.lineSequence().firstOrNull() ?: error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.isSynthetic) {
                Text(
                    text = "Sent from another device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = timeFormat.format(Date(entry.sentAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun StatusBadge(status: OutboxStatus) {
    when (status) {
        OutboxStatus.PENDING -> Icon(
            Icons.Default.Schedule,
            contentDescription = "Sending",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        OutboxStatus.CONFIRMED -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Delivered",
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(18.dp)
        )
        OutboxStatus.FAILED -> Icon(
            Icons.Default.Error,
            contentDescription = "Send failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        OutboxStatus.TIMED_OUT -> Icon(
            Icons.Default.Schedule,
            contentDescription = "Not confirmed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
    }
}
