package xyz.desent.presentation.ui.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.usecase.SecurityConfigUseCase
import xyz.desent.presentation.theme.Amber
import xyz.desent.presentation.theme.Spacing

/**
 * Login-security alert preference — its OWN configuration section, published
 * as a partial kind-30079 payload (`{"security_alerts": "<mode>"}` only) via
 * [SecurityConfigUseCase]. Never folded into the kind-35050 MailboxConfig or
 * the 30078 spam-settings namespace (refs/FromServer/
 * ANDROID_SECURITY_ALERTS.md §6 + USER_SETTINGS_PROTOCOL.md).
 */
@Composable
fun SecurityAlertsSection(
    securityConfigUseCase: SecurityConfigUseCase,
    preferencesManager: PreferencesManager,
    /** False on the Security & privacy sub-screen, which renders the card header. */
    showHeader: Boolean = true
) {
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(SecurityAlertMode.DEFAULT) }
    var isPublishing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val npub = preferencesManager.npubKey.firstOrNull() ?: return@LaunchedEffect
        // Hydrate from the local cache (relay REQ refreshes it in the
        // background via subscribeToOwnUserSettings on connect).
        securityConfigUseCase.observe(npub).collect { config ->
            config?.let { mode = it.alertMode }
            loaded = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "Security alerts",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = "Get notified when someone signs in to your DeSent account, whether over WebSocket, the web APIs or the admin panel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!loaded) {
            CircularProgressIndicator(modifier = Modifier.padding(Spacing.sm).size(24.dp))
            return@Column
        }

        Text("Alert me", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecurityAlertMode.entries.forEach { candidate ->
                FilterChip(
                    selected = mode == candidate,
                    onClick = {
                        if (candidate == mode || isPublishing) return@FilterChip
                        mode = candidate
                        isPublishing = true
                        message = null
                        scope.launch {
                            val npub = preferencesManager.npubKey.firstOrNull()
                            val result = if (npub != null) {
                                securityConfigUseCase.setAlertMode(npub, candidate)
                            } else {
                                Result.failure(IllegalStateException("Not logged in"))
                            }
                            isPublishing = false
                            message = if (result.isSuccess) {
                                "Alert preference updated"
                            } else {
                                "Couldn't update. Check your connection"
                            }
                        }
                    },
                    label = {
                        Text(
                            when (candidate) {
                                SecurityAlertMode.OFF -> "Off"
                                SecurityAlertMode.NEW_DEVICE -> "New devices"
                                SecurityAlertMode.ALWAYS -> "Always"
                            }
                        )
                    }
                )
            }
        }

        Text(
            text = when {
                isPublishing -> "Saving to desent.xyz…"
                message != null -> message!!
                else -> "Synced to desent.xyz (doesn't count toward your storage quota)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
