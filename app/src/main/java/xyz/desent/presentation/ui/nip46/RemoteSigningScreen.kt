package xyz.desent.presentation.ui.nip46

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.desent.R
import xyz.desent.data.nip46.Nip46Pairing
import xyz.desent.presentation.theme.Spacing
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSigningScreen(
    viewModel: Nip46ViewModel,
    onScanQr: () -> Unit,
    onShowBunkerCode: () -> Unit,
    onOpenPairing: (String) -> Unit,
    onBack: () -> Unit
) {
    val pairings by viewModel.activePairings.collectAsState()
    val viewingOtherAccount by viewModel.isViewingOtherAccount.collectAsState()
    val nowSec = System.currentTimeMillis() / 1000

    val active = pairings.filter { !it.revoked && (it.expiresAt == null || it.expiresAt > nowSec) }
    val expired = pairings.filter { it.revoked || (it.expiresAt != null && it.expiresAt <= nowSec) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nip46_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.md)
        ) {
            Text(
                stringResource(R.string.nip46_settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.md))
            if (viewingOtherAccount) {
                // The bunker signs with the ACTIVE identity, so pairing and
                // bunker-code sharing are only offered for the active account.
                // Viewing and revoking the shown pairings still works.
                Text(
                    text = stringResource(R.string.nip46_view_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  " + stringResource(R.string.nip46_pair_new_device))
                }
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = onShowBunkerCode, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCode2, contentDescription = null)
                    Text("  " + stringResource(R.string.nip46_show_bunker_code))
                }
            }
            Spacer(Modifier.height(Spacing.md))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (active.isEmpty() && expired.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.nip46_no_pairings),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (active.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.nip46_section_active)) }
                    items(active, key = { it.sessionPubkey }) { p ->
                        PairingRow(p, expired = false, onClick = { onOpenPairing(p.sessionPubkey) })
                    }
                }
                if (expired.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.nip46_section_expired)) }
                    items(expired, key = { it.sessionPubkey }) { p ->
                        PairingRow(p, expired = true, onClick = { onOpenPairing(p.sessionPubkey) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
    )
}

@Composable
private fun PairingRow(p: Nip46Pairing, expired: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                if (p.transport.isRaw) {
                    Text(
                        "· NIP-46",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(p.profile.name.lowercase().replace('_', '-'), style = MaterialTheme.typography.labelSmall)
            }
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            Text(
                "paired ${fmt.format(Date(p.pairedAt * 1000))} · ${p.signCount} signs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
