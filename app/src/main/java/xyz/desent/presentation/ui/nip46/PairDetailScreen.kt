package xyz.desent.presentation.ui.nip46

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.desent.R
import xyz.desent.presentation.theme.Spacing
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDetailScreen(
    sessionPubkey: String,
    viewModel: Nip46ViewModel,
    onBack: () -> Unit
) {
    val pairing = remember(sessionPubkey) { viewModel.pairingFor(sessionPubkey) }
    var confirmRevoke by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pairing?.label ?: "Pairing") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        val p = pairing
        if (p == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
                Text("Pairing not found.")
            }
            return@Scaffold
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    DetailLine("Profile", p.profile.name.lowercase().replace('_', '-'))
                    DetailLine("Transport", if (p.transport.isRaw) "Standard NIP-46 (kind 24133)" else "DeSent private (gift wrap)")
                    if (p.relays.isNotEmpty()) {
                        DetailLine("Relays", p.relays.joinToString("\n"))
                    }
                    DetailLine("Paired", fmt.format(Date(p.pairedAt * 1000)))
                    DetailLine("Last used", fmt.format(Date(p.lastUsedAt * 1000)))
                    DetailLine("Approved", "${p.signCount}")
                    DetailLine("Denied", "${p.denyCount}")
                    DetailLine("Status", if (p.revoked) "revoked" else "active")
                    DetailLine("Session", p.sessionPubkey.take(16) + "…")
                }
            }
            Spacer(Modifier.height(Spacing.md))
            OutlinedButton(
                onClick = { confirmRevoke = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !p.revoked
            ) {
                Text(stringResource(R.string.nip46_detail_revoke))
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(R.string.nip46_detail_revoke)) },
            text = {
                Text(stringResource(R.string.nip46_detail_revoke_confirm, pairing?.label ?: "this device"))
            },
            confirmButton = {
                Button(onClick = {
                    pairing?.sessionPubkey?.let { viewModel.revoke(it) }
                    confirmRevoke = false
                    onBack()
                }) { Text("Revoke") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmRevoke = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
