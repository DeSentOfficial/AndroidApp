package xyz.desent.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import xyz.desent.wear.WearAppContainer

/** Watch settings: sync controls + the spam/bunker sync toggles. */
@Composable
fun SettingsScreen(appContainer: WearAppContainer) {
    val inbox by appContainer.inbox.collectAsState()
    // Phone default is "spam synced" — assume it until a payload says otherwise.
    val spamEnabled = inbox?.spamEnabled ?: true
    val bunker by appContainer.bunkerRequest.collectAsState()
    val bunkerEnabled = bunker?.bunkerEnabled ?: true

    TimeText()

    ScalingLazyColumn {
        item {
            Text(
                text = "DeSent pairs with your phone over the Wear Data Layer. " +
                    "Email syncs automatically; use Resync after reinstalling.",
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = spamEnabled,
                onCheckedChange = { appContainer.setSpamEnabled(it) },
                label = { Text("Sync spam folder") },
                toggleControl = {
                    Icon(
                        imageVector = ToggleChipDefaults.switchIcon(checked = spamEnabled),
                        contentDescription = if (spamEnabled) "On" else "Off"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = bunkerEnabled,
                onCheckedChange = { appContainer.setBunkerEnabled(it) },
                label = { Text("Bunker requests on watch") },
                toggleControl = {
                    Icon(
                        imageVector = ToggleChipDefaults.switchIcon(checked = bunkerEnabled),
                        contentDescription = if (bunkerEnabled) "On" else "Off"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            MenuChip(
                label = "Resync from phone",
                onClick = {
                    appContainer.requestConfigFromPhone()
                    appContainer.requestInboxFromPhone()
                    appContainer.requestCalendarFromPhone()
                    appContainer.requestBunkerFromPhone()
                }
            )
        }
    }
}
