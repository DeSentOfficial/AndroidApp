package xyz.desent.presentation.ui.nip46

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.desent.R
import xyz.desent.data.nip46.Nip46PermissionProfile
import xyz.desent.presentation.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairConfirmScreen(
    viewModel: Nip46ViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val pairing by viewModel.pendingPairing.collectAsState()
    val error by viewModel.pairError.collectAsState()
    val success by viewModel.pairSuccess.collectAsState()

    val uri = pairing
    var profile by remember(uri) {
        mutableStateOf(uri?.let { viewModel.suggestedProfile(it) } ?: Nip46PermissionProfile.DESENT_INBOX)
    }
    var duration by remember { mutableStateOf(Nip46PairDuration.MIN_5) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nip46_pair_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uri == null) {
            // Nothing to confirm (e.g. navigated directly). Bail out.
            Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
                Text("No pairing request.")
                Spacer(Modifier.height(Spacing.md))
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.nip46_pair_cancel)) }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(Spacing.md)) {
                    Text(uri.label ?: "Unknown app", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.nip46_pair_wants_to),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.nip46_pair_relays_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    uri.relayUrls.forEach { relay ->
                        Text(
                            relay,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uri.transport.isRaw) {
                        Text(
                            stringResource(R.string.nip46_pair_external_relay_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            stringResource(R.string.nip46_pair_desent_relay_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(stringResource(R.string.nip46_pair_profile_label), style = MaterialTheme.typography.titleSmall)
            profileOptions().forEach { (p, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = profile == p, onClick = { profile = p })
                    Text(label, modifier = Modifier.padding(start = Spacing.sm))
                }
            }
            if (profile == Nip46PermissionProfile.GENERAL_NOSTR) {
                Text(
                    "General Nostr grants broad signing power. Only choose this for Nostr clients you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(stringResource(R.string.nip46_pair_duration_label), style = MaterialTheme.typography.titleSmall)
            Nip46PairDuration.entries.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = duration == d, onClick = { duration = d })
                    Text(stringResource(d.labelRes), modifier = Modifier.padding(start = Spacing.sm))
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            success?.let {
                Text(
                    "Paired with ${it}.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { viewModel.cancelPairing(); onCancel() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nip46_pair_cancel))
                }
                Button(
                    onClick = {
                        viewModel.approvePairing(profile, duration)
                        // Wait for success/error to surface via state; navigate once paired.
                        onDone()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = success == null
                ) {
                    Text(stringResource(R.string.nip46_pair_approve))
                }
            }
        }
    }
}

private fun profileOptions(): List<Pair<Nip46PermissionProfile, String>> = listOf(
    Nip46PermissionProfile.DESENT_INBOX to "DeSent inbox (recommended for web login)",
    Nip46PermissionProfile.DESENT_MANAGE to "DeSent manage (alias + account admin)",
    Nip46PermissionProfile.GENERAL_NOSTR to "General Nostr (external clients)",
    Nip46PermissionProfile.IDENTITY_ONLY to "Identity only (no signing)"
)
