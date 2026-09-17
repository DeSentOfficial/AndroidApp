package xyz.desent.presentation.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.pgp.PgpFeatureGate
import xyz.desent.data.relay.RelaySyncWatermarks
import xyz.desent.domain.usecase.MailboxConfigUseCase
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.fanout.RelayMirroringCard
import xyz.desent.presentation.ui.fanout.viewmodel.FanoutViewModel
import xyz.desent.presentation.ui.settings.component.MailboxSettingsSection
import xyz.desent.presentation.ui.settings.component.SettingsCard
import xyz.desent.presentation.ui.settings.component.SettingsNavRow
import xyz.desent.presentation.ui.settings.component.SettingsSubScreen

/**
 * Mail spoke: the PGP drill-down (still gated on the relay's pgp_enabled
 * flag), the relay-published mailbox rules, the Relay Mirroring opt-in
 * (drill-down into the mirroring machinery), and the sync-checkpoint safety
 * valve.
 */
@Composable
fun MailSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPgpSettings: () -> Unit,
    mailboxConfigUseCase: MailboxConfigUseCase,
    preferencesManager: PreferencesManager,
    fanoutViewModel: FanoutViewModel,
    onNavigateToFanout: () -> Unit,
    onNavigateToBilling: () -> Unit,
    pgpFeatureGate: PgpFeatureGate? = null,
    relaySyncWatermarks: RelaySyncWatermarks? = null
) {
    // Heal a stale fail-closed pgp_enabled gate so the PGP row reflects the
    // relay toggle without an app restart (throttled; nginx caches 5 min).
    LaunchedEffect(Unit) { pgpFeatureGate?.maybeRefresh() }
    val pgpEnabled by (pgpFeatureGate?.enabled ?: MutableStateFlow(false)).collectAsState()

    var showResetDialog by remember { mutableStateOf(false) }
    var checkpointsReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SettingsSubScreen(title = "Mail", onNavigateBack = onNavigateBack) {
        if (pgpEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    SettingsNavRow(
                        icon = Icons.Default.VpnKey,
                        title = "PGP Encryption",
                        subtitle = "End-to-end encrypted mail with OpenPGP keys. Generate, " +
                            "import, and publish your key.",
                        onClick = onNavigateToPgpSettings
                    )
                }
            }
        }

        SettingsCard(
            icon = Icons.Default.Tune,
            title = "Mailbox rules",
            subtitle = "Encrypted, and enforced by the relay"
        ) {
            MailboxSettingsSection(
                mailboxConfigUseCase = mailboxConfigUseCase,
                preferencesManager = preferencesManager,
                showTitle = false
            )
        }

        RelayMirroringCard(
            viewModel = fanoutViewModel,
            onNavigateToAdvanced = onNavigateToFanout,
            onNavigateToBilling = onNavigateToBilling
        )

        // Safety valve for the relay-sync `since` cursors: if mail ever looks
        // missed, wiping the cursors forces one full catch-up fetch.
        relaySyncWatermarks?.let {
            SettingsCard(
                icon = Icons.Default.Sync,
                title = "Sync checkpoints",
                subtitle = "Relay syncs fetch only events newer than the last one seen"
            ) {
                SettingsNavRow(
                    icon = Icons.Default.Refresh,
                    title = "Reset sync checkpoints",
                    subtitle = "Force one full re-download from the relay on next sync",
                    onClick = { showResetDialog = true }
                )
                if (checkpointsReset) {
                    Text(
                        text = "Checkpoints cleared. Your next sync will re-download everything to catch up.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset sync checkpoints?") },
            text = {
                Text(
                    "The next relay sync will re-download your full event history " +
                        "from the server (mail, notes, calendar). Use this only if " +
                        "content appears to be missing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    checkpointsReset = true
                    scope.launch { relaySyncWatermarks?.reset() }
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
