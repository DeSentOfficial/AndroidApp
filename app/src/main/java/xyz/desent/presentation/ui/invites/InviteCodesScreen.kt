package xyz.desent.presentation.ui.invites

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import xyz.desent.crypto.Bech32Utils
import xyz.desent.domain.model.InviteCode
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.invites.viewmodel.InviteCodesViewModel
import android.widget.Toast

private const val INVITE_LINK_PREFIX = "https://desent.xyz/register?ref="

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteCodesScreen(
    onNavigateBack: () -> Unit,
    viewModel: InviteCodesViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    fun share(code: String) {
        val shareText = "Join me on DeSent: encrypted email over Nostr. " +
            "Invite code: $code\n$INVITE_LINK_PREFIX$code"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(send, "Share invite"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite Codes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }

            uiState.accountDisabled -> StatusCard(
                modifier = Modifier.padding(padding),
                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                title = "Account suspended",
                body = "Your account has been suspended. Contact support if you think this is a mistake."
            )

            uiState.notRegistered -> StatusCard(
                modifier = Modifier.padding(padding),
                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                title = "No DeSent address",
                body = "Claim an address before inviting others. Invite codes are minted when you register.",
                action = { onNavigateBack() }
            )

            uiState.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                item {
                    Column {
                        Text(
                            text = "Your invite codes",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        // Summary derived from the response — never hardcode the cap.
                        Text(
                            text = "${uiState.codes.count { !it.used }}/${uiState.cap} available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(uiState.codes, key = { it.code }) { code ->
                    InviteCodeRow(
                        invite = code,
                        onCopyCode = { copy("Code", code.code) },
                        onCopyLink = { copy("Invite link", INVITE_LINK_PREFIX + code.code) },
                        onShare = { share(code.code) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteCodeRow(
    invite: InviteCode,
    onCopyCode: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = invite.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                if (invite.used) {
                    AssistChip(
                        onClick = {},
                        label = { Text("used") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                } else {
                    AssistChip(
                        onClick = {},
                        label = { Text("available") }
                    )
                }
            }

            invite.usedAt?.let { usedAt ->
                val usedBy = renderUsedBy(invite.usedBy)
                Text(
                    text = buildString {
                        append("Redeemed ")
                        append(DateUtils.getRelativeTimeSpanString(usedAt).toString())
                        if (usedBy != null) append(" by $usedBy")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!invite.used) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(onClick = onCopyCode) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Code")
                    }
                    OutlinedButton(onClick = onCopyLink) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Link")
                    }
                    OutlinedButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text("Share")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (action != null) {
                    TextButton(onClick = action) { Text("Go back") }
                }
            }
        }
    }
}

/** used_by is a raw hex pubkey — render as a truncated npub, never full hex. */
private fun renderUsedBy(hex: String?): String? = hex?.let {
    runCatching { Bech32Utils.hexToNpub(it) }.getOrNull()
        ?.let { npub -> "${npub.take(10)}…${npub.takeLast(6)}" }
}
