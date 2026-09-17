package xyz.desent.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import kotlinx.coroutines.delay
import xyz.desent.data.wearsync.WearBunkerRequest
import xyz.desent.wear.WearAppContainer

/**
 * Bunker (NIP-46 remote-signer) prompt mirror. The phone receives the sign
 * request, holds the keys, and signs; this screen only shows what it asked
 * for and relays the user's accept/deny back over the Data Layer. The
 * countdown matches the phone's auto-deny instant ([WearBunkerRequest.expiresAt]),
 * so the two can never disagree about what was still decidable.
 */
@Composable
fun BunkerScreen(appContainer: WearAppContainer) {
    val request by appContainer.bunkerRequest.collectAsState()
    val pending = request?.takeIf { it.requestId != null }

    TimeText()

    when {
        pending == null -> IdleBunker(appContainer, hasPayload = request != null)
        else -> PendingBunker(appContainer, pending)
    }
}

@Composable
private fun IdleBunker(appContainer: WearAppContainer, hasPayload: Boolean) {
    ScalingLazyColumn {
        item {
            Text(
                text = "No pending bunker requests.",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                text = "When a paired app asks DeSent to sign, the request appears here " +
                    "(and on your phone). Deciding on the watch works even with the " +
                    "phone in your pocket.",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (!hasPayload) {
            item {
                MenuChip(label = "Resync now", onClick = { appContainer.requestBunkerFromPhone() })
            }
        }
    }
}

@Composable
private fun PendingBunker(appContainer: WearAppContainer, request: WearBunkerRequest) {
    val requestId = request.requestId.orEmpty()
    var alwaysAllow by remember(requestId) { mutableStateOf(false) }
    var sent by remember(requestId) { mutableStateOf(false) }

    // Ticking countdown to the phone's auto-deny instant.
    var nowMs by remember(requestId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(requestId) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val remainingSec = ((request.expiresAt - nowMs) / 1000).coerceAtLeast(0)
    val expired = request.expiresAt > 0 && remainingSec <= 0

    ScalingLazyColumn {
        item {
            Text(
                text = "Bunker request",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                text = request.label.ifBlank { "Paired app" },
                style = MaterialTheme.typography.body2,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                text = request.method + (request.eventKind?.let { " · kind $it" } ?: ""),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        request.preview?.takeIf { it.isNotBlank() }?.let { preview ->
            item {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            Text(
                text = when {
                    sent -> "Sent to phone…"
                    expired -> "Expired — auto-denied on phone"
                    else -> "Expires in ${formatCountdown(remainingSec)}"
                },
                style = MaterialTheme.typography.caption1,
                color = if (expired || remainingSec <= 15) {
                    MaterialTheme.colors.error
                } else {
                    MaterialTheme.colors.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (!expired) {
            item {
                ToggleChip(
                    checked = alwaysAllow,
                    onCheckedChange = { alwaysAllow = it },
                    label = {
                        Text(
                            "Always allow " +
                                (request.eventKind?.let { "kind $it" } ?: request.method)
                        )
                    },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(checked = alwaysAllow),
                            contentDescription = if (alwaysAllow) "On" else "Off"
                        )
                    },
                    enabled = !sent,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Chip(
                    onClick = {
                        sent = true
                        appContainer.sendBunkerDecision(
                            accept = true,
                            alwaysAllow = alwaysAllow,
                            requestId = requestId
                        )
                    },
                    enabled = !sent,
                    label = {
                        Text(
                            text = if (sent) "Approving…" else "✓ Accept & sign",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = MaterialTheme.colors.primary,
                        contentColor = MaterialTheme.colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                )
            }
            item {
                Chip(
                    onClick = {
                        sent = true
                        appContainer.sendBunkerDecision(
                            accept = false,
                            alwaysAllow = false,
                            requestId = requestId
                        )
                    },
                    enabled = !sent,
                    label = {
                        Text(
                            text = if (sent) "Denying…" else "✕ Deny",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = MaterialTheme.colors.error,
                        contentColor = MaterialTheme.colors.onError
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                )
            }
        }
    }
}

/** m:ss (e.g. "1:47"); plain seconds below a minute ("42s"). */
private fun formatCountdown(remainingSec: Long): String =
    if (remainingSec >= 60) "${remainingSec / 60}:${"%02d".format(remainingSec % 60)}" else "${remainingSec}s"
