package xyz.desent.presentation.ui.fanout

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import xyz.desent.presentation.ui.fanout.viewmodel.FanoutAvailability
import xyz.desent.presentation.ui.fanout.viewmodel.FanoutViewModel
import xyz.desent.presentation.ui.settings.component.SettingsCard
import xyz.desent.presentation.ui.settings.component.SettingsNavRow
import xyz.desent.presentation.ui.settings.component.SwitchSetting

/**
 * The Relay Mirroring master toggle, hosted inline on the Mail spoke
 * (ANDROID_DM_FANOUT.md §2–§3). Fail-closed on tier-info availability; the
 * premium gate surfaces as a dialog with the billing escape hatch; the
 * relay-list editor, delivery health, backup fetch and mirror repair stay
 * one drill-down away in [FanoutScreen]. The [FanoutViewModel] here runs its
 * light core only (tier-info + the dm_fanout config) — never the advanced
 * machinery.
 */
@Composable
fun RelayMirroringCard(
    viewModel: FanoutViewModel,
    onNavigateToAdvanced: () -> Unit,
    onNavigateToBilling: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    SettingsCard(
        icon = Icons.Default.CloudSync,
        title = "Relay Mirroring",
        subtitle = "Mirror inbound mail to your own backup relays"
    ) {
        when (uiState.availability) {
            FanoutAvailability.LOADING -> Text(
                "Checking availability…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FanoutAvailability.NEUTRAL_LOCKED ->
                // They hold the entitlement — the operator just hasn't
                // switched the feature on. NEVER an upsell.
                Text(
                    "Relay mirroring isn't enabled on this relay yet. Nothing " +
                        "to buy. Check back later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            FanoutAvailability.UPSELL -> {
                Text(
                    "Mirror your mail to your own relays. Included with paid " +
                        "plans, or a one-time add-on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsNavRow(
                    icon = Icons.Default.CloudSync,
                    title = "Mirroring options",
                    subtitle = "See plans or buy the add-on",
                    onClick = onNavigateToAdvanced
                )
            }

            FanoutAvailability.ENABLED -> {
                SwitchSetting(
                    title = "Mirror my mail to my relays",
                    description = "The relay dual-writes inbound mail and delivery " +
                        "receipts to your backup relays. Local delivery stays " +
                        "authoritative.",
                    checked = uiState.dmFanoutEnabled,
                    onCheckedChange = { viewModel.setDmFanout(it) }
                )
                uiState.toggleError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                SettingsNavRow(
                    icon = Icons.Default.CloudSync,
                    title = "Backup relays & health",
                    subtitle = "Relay list, delivery status, backup fetch & repair",
                    onClick = onNavigateToAdvanced
                )
            }
        }
    }

    if (uiState.premiumPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPremiumPrompt() },
            title = { Text("Premium feature") },
            text = {
                Text("Relay mirroring requires an active paid plan or the mirroring add-on.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissPremiumPrompt()
                    onNavigateToBilling()
                }) { Text("See plans") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPremiumPrompt() }) { Text("Not now") }
            }
        )
    }
}
