package xyz.desent.presentation.ui.nip46

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.desent.R
import xyz.desent.data.nip46.Nip46BunkerService
import xyz.desent.data.nip46.Nip46SignPrompt
import xyz.desent.data.nip46.PROMPT_TIMEOUT_SECONDS

/**
 * Global overlay shown whenever a NIP-46 sign request requires the user's
 * decision (policy = PROMPT). Auto-denies after [PROMPT_TIMEOUT_SECONDS]
 * seconds. Place once at the navigation host so it can surface from any screen.
 */
@Composable
fun Nip46SignPromptDialog(bunkerService: Nip46BunkerService) {
    val prompt by bunkerService.pendingSignPrompt.collectAsState()
    val current = prompt ?: return
    var alwaysAllow by remember(current.requestId) { mutableStateOf(false) }

    // Countdown → auto-deny. The window is generous (not glanceable) because
    // the same prompt is pushed to the Wear companion, whose user is often
    // not looking at the phone.
    LaunchedEffect(current.requestId) {
        var remaining = PROMPT_TIMEOUT_SECONDS
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
        bunkerService.denyPending()
    }

    AlertDialog(
        onDismissRequest = { bunkerService.denyPending() },
        title = { Text(stringResource(R.string.nip46_sign_title)) },
        text = { PromptBody(current, alwaysAllow = alwaysAllow, onAlwaysAllowChange = { alwaysAllow = it }) },
        confirmButton = {
            TextButton(onClick = { bunkerService.approvePending(alwaysAllow) }) {
                Text(stringResource(R.string.nip46_sign_approve))
            }
        },
        dismissButton = {
            TextButton(onClick = { bunkerService.denyPending() }) {
                Text(stringResource(R.string.nip46_sign_deny))
            }
        }
    )
}

@Composable
private fun PromptBody(
    prompt: Nip46SignPrompt,
    alwaysAllow: Boolean,
    onAlwaysAllowChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("From: ${prompt.label}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Method: ${prompt.method}" + (prompt.eventKind?.let { " · kind $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        prompt.unsignedEventJson?.take(500)?.let { preview ->
            Card(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                    maxLines = 6
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = alwaysAllow,
                onCheckedChange = onAlwaysAllowChange
            )
            Text(
                text = stringResource(
                    R.string.nip46_sign_always_allow,
                    prompt.eventKind?.let { "kind $it" } ?: prompt.method
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            stringResource(R.string.nip46_sign_timeout),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
