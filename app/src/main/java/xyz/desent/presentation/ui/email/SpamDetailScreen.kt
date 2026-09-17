package xyz.desent.presentation.ui.email

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.domain.model.Email
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.email.components.EmailTopBarTitle
import xyz.desent.presentation.ui.email.viewmodel.SpamDetailViewModel
import xyz.desent.presentation.ui.components.DeleteConfirmationDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: SpamDetailViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val senderAvatars by viewModel.senderProfiles.avatars.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // "Not spam" or "Delete forever" completed — local row is updated/removed; pop back to spam list.
    LaunchedEffect(uiState.notSpamHandled, uiState.deleted) {
        if (uiState.notSpamHandled || uiState.deleted) onNavigateBack()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { viewModel.clearError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val email = uiState.email
                    if (email == null) {
                        Text("Spam", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        EmailTopBarTitle(
                            displayName = email.displaySender,
                            seed = email.senderEmail,
                            subject = email.subject,
                            pictureUrl = senderAvatars[email.senderEmail.lowercase()],
                            spamTag = true
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.email == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Message not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                val email = uiState.email!!

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .verticalScroll(rememberScrollState())
                        .padding(paddingValues)
                ) {
                    SpamMetaHeader(email)
                    SpamVerdictCard(email)

                    Column(
                        modifier = Modifier.padding(
                            start = Spacing.md,
                            end = Spacing.md,
                            top = Spacing.xs,
                            bottom = Spacing.md
                        )
                    ) {
                        if (email.isPgpEncrypted &&
                            email.direction != xyz.desent.domain.model.EmailDirection.OUTBOUND
                        ) {
                            // ANDROID_PGP.md §3.5: the armor is never rendered
                            // as though it were the message body.
                            Text(
                                text = "🔒 PGP-encrypted message — open it from the inbox " +
                                    "to decrypt with this account's key.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (email.content.isBlank()) {
                            Text(
                                text = "This message has no content",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            xyz.desent.presentation.ui.email.components.QuoteAwareEmailBody(
                                email = email,
                                imagePolicy = viewModel.imagePolicy,
                                wrapContentHeight = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(Spacing.md))
                        SpamActions(
                            isDeleting = uiState.isDeleting,
                            onNotSpam = { viewModel.markNotSpam(email) },
                            onDeleteForever = { showDeleteConfirm = true }
                        )
                    }
                }
            }
        }

        uiState.error?.let { error ->
            Snackbar(modifier = Modifier.padding(Spacing.md)) {
                Text(text = error)
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmationDialog(
            title = "Delete forever?",
            message = "Permanently delete \"${uiState.email?.subject?.ifBlank { null } ?: "(no subject)"}\"? " +
                "This can't be undone.",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteForever()
            },
            onDismiss = { showDeleteConfirm = false },
            isDeleting = uiState.isDeleting,
            confirmLabel = "Delete forever"
        )
    }
}

/**
 * Compact meta band below the app bar: only what the top bar doesn't show —
 * the bare sender address and the date (avatar/name/subject live up top).
 */
@Composable
private fun SpamMetaHeader(email: Email) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = email.senderEmail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatDate(email.senderDate ?: email.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpamVerdictCard(email: Email) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = Spacing.md,
            vertical = Spacing.xs
        ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.Report,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Spam score %.1f".format(email.spamScore),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            email.spamReasons?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "· ${it.replace(",", ", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SpamActions(
    isDeleting: Boolean,
    onNotSpam: () -> Unit,
    onDeleteForever: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        OutlinedButton(
            onClick = onNotSpam,
            modifier = Modifier.weight(1f),
            enabled = !isDeleting
        ) {
            Icon(Icons.Default.Inbox, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text("Not spam")
        }
        OutlinedButton(
            onClick = onDeleteForever,
            modifier = Modifier.weight(1f),
            enabled = !isDeleting
        ) {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Text(
                "Delete forever",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(timestamp))
