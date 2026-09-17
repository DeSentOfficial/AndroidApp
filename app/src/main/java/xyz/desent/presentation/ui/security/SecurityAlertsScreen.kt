package xyz.desent.presentation.ui.security

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.data.local.database.entity.SecurityAlertEntity
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.presentation.theme.Amber
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.security.viewmodel.SecurityAlertsUiState
import xyz.desent.presentation.ui.security.viewmodel.SecurityAlertsViewModel

/**
 * Login-security alerts (refs/FromServer/ANDROID_SECURITY_ALERTS.md §7):
 * a shield-styled list + detail, deliberately distinct from ordinary inbox
 * mail — styled on `direction == "security"`, never on the sender address.
 * Hosts the `security_alerts` mode chips (kind-30079 publish, partial
 * payload) so a user arriving from the account switcher can adjust the
 * frequency without digging through Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityAlertsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SecurityAlertsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val selected = uiState.alerts.firstOrNull { it.eventId == uiState.selectedEventId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) viewModel.selectAlert(null) else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selected == null && uiState.unseenCount > 0) {
                        TextButton(onClick = { viewModel.markAllSeen() }) {
                            Text("Mark all read")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (selected != null) {
            SecurityAlertDetail(
                alert = selected,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            SecurityAlertList(
                uiState = uiState,
                onOpenAlert = { viewModel.selectAlert(it.eventId) },
                onModeChange = { viewModel.setAlertMode(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        }
    }
}

// ---------------- List ----------------

@Composable
private fun SecurityAlertList(
    uiState: SecurityAlertsUiState,
    onOpenAlert: (SecurityAlertEntity) -> Unit,
    onModeChange: (SecurityAlertMode) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        // Settings first: the mode chips are the screen's primary control, so
        // they're visible on entry instead of buried under the alert list.
        item {
            Column {
                Text(
                    text = "Alert me",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "When someone signs in to your DeSent account. Synced via kind 30079 on desent.xyz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecurityAlertMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.alertMode == mode,
                            onClick = { onModeChange(mode) },
                            label = { Text(modeLabel(mode)) },
                            enabled = !uiState.isPublishing && uiState.modeEditable
                        )
                    }
                }
                if (!uiState.modeEditable) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "Sign-in alert settings can only be changed for the active account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.isPublishing) {
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = "Publishing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
            }
        }

        if (uiState.alerts.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = "No sign-in alerts. You're all clear.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(uiState.alerts, key = { it.eventId }) { alert ->
                SecurityAlertRow(alert = alert, onClick = { onOpenAlert(alert) })
            }
        }
    }
}

private fun modeLabel(mode: SecurityAlertMode): String = when (mode) {
    SecurityAlertMode.OFF -> "Off"
    SecurityAlertMode.NEW_DEVICE -> "New devices"
    SecurityAlertMode.ALWAYS -> "Always"
}

@Composable
private fun SecurityAlertRow(alert: SecurityAlertEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (!alert.isSeen) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!alert.isSeen) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Amber, CircleShape)
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Security alert",
                tint = Amber,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alert.subject.ifBlank { "Security alert" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (alert.isSeen) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val line2 = listOfNotNull(
                    alert.geo ?: alert.ip,
                    summarizeUa(alert.ua)
                ).joinToString(" · ")
                if (line2.isNotBlank()) {
                    Text(
                        text = line2,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val line3 = listOfNotNull(
                    alert.time.takeIf { it.isNotBlank() },
                    surfaceLabel(alert.surface).takeIf { alert.surface.isNotEmpty() }
                ).joinToString(" · ")
                if (line3.isNotBlank()) {
                    Text(
                        text = line3,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------- Detail ----------------

@Composable
private fun SecurityAlertDetail(alert: SecurityAlertEntity, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Security alert",
                tint = Amber,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = alert.subject.ifBlank { "Security alert" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                DetailLine("WHEN", alert.time.takeIf { it.isNotBlank() } ?: "Unknown")
                val where = listOfNotNull(alert.geo, alert.ip).joinToString(" (") +
                    if (alert.ip != null && alert.geo != null) ")" else ""
                DetailLine("WHERE", where.takeIf { it.isNotBlank() } ?: "Unknown")
                DetailLine(
                    "DEVICE",
                    listOfNotNull(summarizeUa(alert.ua), alert.device.takeIf { it.isNotBlank() })
                        .joinToString(" · ").ifBlank { "Unknown" }
                )
                DetailLine("VIA", surfaceLabel(alert.surface).takeIf { alert.surface.isNotEmpty() } ?: "Unknown")
            }
        }

        if (alert.body.isNotBlank()) {
            Text(
                text = alert.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "Alerts auto-expire after 30 days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Surface tag → human label (ANDROID_SECURITY_ALERTS.md §2). */
private fun surfaceLabel(surface: String): String = when (surface) {
    "ws" -> "relay connection (NIP-42)"
    "http" -> "web / API (NIP-98)"
    "admin" -> "admin panel"
    else -> surface
}

/** Short human summary of a raw User-Agent ("Firefox on Linux"). */
private fun summarizeUa(ua: String?): String? {
    if (ua == null) return null
    val browser = when {
        ua.contains("Firefox", ignoreCase = true) -> "Firefox"
        ua.contains("Edg/", ignoreCase = true) -> "Edge"
        ua.contains("Chrome", ignoreCase = true) -> "Chrome"
        ua.contains("Safari", ignoreCase = true) -> "Safari"
        ua.contains("OkHttp", ignoreCase = true) -> "DeSent app"
        else -> null
    }
    val os = when {
        ua.contains("Android", ignoreCase = true) -> "Android"
        ua.contains("iPhone", ignoreCase = true) ||
            ua.contains("iPad", ignoreCase = true) -> "iOS"
        ua.contains("Windows", ignoreCase = true) -> "Windows"
        ua.contains("Mac OS", ignoreCase = true) -> "macOS"
        ua.contains("Linux", ignoreCase = true) -> "Linux"
        else -> null
    }
    return when {
        browser != null && os != null -> "$browser on $os"
        else -> browser ?: os
    }
}
