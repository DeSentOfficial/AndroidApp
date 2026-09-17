package xyz.desent.presentation.ui.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.MailboxConfig
import xyz.desent.domain.usecase.MailboxConfigUseCase
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DesentTextField

/**
 * NIP-EMAIL Mailbox Configuration (kind 35050) editor: retention TTL
 * (`auto_purge_days`, relay-readable) + private rules (blocked senders,
 * forwarding targets, preferred alias — NIP-44 self-encrypted on the wire).
 * See refs/FromServer/NIP-EMAIL.md § Kind 35050.
 */
@Composable
fun MailboxSettingsSection(
    mailboxConfigUseCase: MailboxConfigUseCase,
    preferencesManager: PreferencesManager,
    /** False on the Mail sub-screen, whose card header carries the context. */
    showTitle: Boolean = true
) {
    val coroutineScope = rememberCoroutineScope()

    // Device-local preference (not part of the published kind-35050 event):
    // sender favicons via the desent.xyz cache (NIP-98) or direct origin probes.
    val faviconServerCache by preferencesManager.isFaviconServerCacheEnabled.collectAsState(initial = true)

    var loaded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var userNpub by remember { mutableStateOf<String?>(null) }

    var autoPurgeDays by remember { mutableStateOf<Int?>(null) }
    var blockedSenders by remember { mutableStateOf(listOf<String>()) }
    var forwardTargets by remember { mutableStateOf(listOf<String>()) }
    var preferredAlias by remember { mutableStateOf("") }
    var newBlocked by remember { mutableStateOf("") }
    var newForward by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val npub = preferencesManager.npubKey.firstOrNull() ?: return@LaunchedEffect
        userNpub = npub
        mailboxConfigUseCase.refresh()
        mailboxConfigUseCase.get(npub)?.let { config ->
            autoPurgeDays = config.autoPurgeDays
            blockedSenders = config.blockedSenders
            forwardTargets = config.forwardTargets
            preferredAlias = config.preferredAlias.orEmpty()
        }
        loaded = true
    }

    fun save() {
        val npub = userNpub
        if (npub == null) {
            message = "Not logged in"
            return
        }
        coroutineScope.launch {
            isSaving = true
            message = null
            val config = MailboxConfig(
                autoPurgeDays = autoPurgeDays,
                blockedSenders = blockedSenders,
                forwardTargets = forwardTargets,
                preferredAlias = preferredAlias.trim().takeIf { it.isNotBlank() }
            )
            val result = mailboxConfigUseCase.save(npub, config)
            isSaving = false
            message = if (result.isSuccess) "Mailbox settings published" else "Save failed: ${result.exceptionOrNull()?.message}"
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (showTitle) {
            Text(
                text = "Mailbox",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "Retention and private rules for your @desent.xyz mailbox. They're stored encrypted, and the relay enforces them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!loaded) {
            CircularProgressIndicator(modifier = Modifier.padding(Spacing.sm))
            return@Column
        }

        // --- Retention TTL (relay-readable policy tag) ---
        Text("Auto-purge (retention)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Stored messages older than this are deleted from the relay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Default", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (days, label) ->
                FilterChip(
                    selected = autoPurgeDays == days,
                    onClick = { autoPurgeDays = days },
                    label = { Text(label) }
                )
            }
        }

        // --- Blocked senders (private rule) ---
        Text("Blocked senders", style = MaterialTheme.typography.titleSmall)
        blockedSenders.forEach { sender ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(sender, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { blockedSenders = blockedSenders - sender }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesentTextField(
                value = newBlocked,
                onValueChange = { newBlocked = it },
                label = { Text("Block address") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            TextButton(
                onClick = {
                    val address = newBlocked.trim()
                    if (address.contains('@') && address !in blockedSenders) {
                        blockedSenders = blockedSenders + address
                    }
                    newBlocked = ""
                }
            ) { Text("Add") }
        }

        // --- Forwarding targets (private rule) ---
        Text("Forwarding targets", style = MaterialTheme.typography.titleSmall)
        forwardTargets.forEach { target ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(target, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { forwardTargets = forwardTargets - target }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesentTextField(
                value = newForward,
                onValueChange = { newForward = it },
                label = { Text("Forward to") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            TextButton(
                onClick = {
                    val address = newForward.trim()
                    if (address.contains('@') && address !in forwardTargets) {
                        forwardTargets = forwardTargets + address
                    }
                    newForward = ""
                }
            ) { Text("Add") }
        }

        // --- Preferred alias (private rule) ---
        DesentTextField(
            value = preferredAlias,
            onValueChange = { preferredAlias = it },
            label = { Text("Preferred outbound alias (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }, enabled = !isSaving) {
                Text(if (isSaving) "Publishing…" else "Publish settings")
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = Spacing.sm))

        // --- Sender avatars (device-local) ---
        SwitchSetting(
            title = "Use DeSent favicon cache",
            description = "Fetch sender favicons through desent.xyz. It's faster and keeps " +
                "your IP hidden from sender domains. When off, favicons load directly from each sender's domain.",
            checked = faviconServerCache,
            onCheckedChange = { enabled ->
                coroutineScope.launch {
                    preferencesManager.setFaviconServerCacheEnabled(enabled)
                }
            }
        )
    }
}
