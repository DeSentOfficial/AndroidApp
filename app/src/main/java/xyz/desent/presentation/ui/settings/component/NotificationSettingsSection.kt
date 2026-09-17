package xyz.desent.presentation.ui.settings.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.presentation.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Notification settings section.
 *
 * System-tray notifications are posted by `SystemNotificationDispatcher` for
 * the life of the app process. "Keep running in background" starts a foreground
 * service that keeps the relay WebSockets alive much longer when backgrounded.
 */
@Composable
fun NotificationSettingsSection(
    preferencesManager: PreferencesManager,
    modifier: Modifier = Modifier,
    /** False on the Notifications sub-screen, whose app bar already says it. */
    showHeader: Boolean = true
) {
    val scope = rememberCoroutineScope()

    val emailNotifications by preferencesManager.areEmailNotificationsEnabled.collectAsState(initial = true)
    val inAppNotifications by preferencesManager.areInAppNotificationsEnabled.collectAsState(initial = true)
    val backgroundService by preferencesManager.isBackgroundServiceEnabled.collectAsState(initial = false)

    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        if (showHeader) {
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        SwitchSetting(
            title = "Emails",
            description = "Notify when you receive a new email",
            checked = emailNotifications,
            onCheckedChange = { enabled ->
                scope.launch { preferencesManager.setEmailNotificationsEnabled(enabled) }
            }
        )

        HorizontalDivider()

        SwitchSetting(
            title = "Keep running in background",
            description = "Keeps the connection alive when the app is closed so notifications keep coming. " +
                "Shows a persistent \"DeSent is connected\" notification and uses a bit more battery.",
            checked = backgroundService,
            onCheckedChange = { enabled ->
                scope.launch { preferencesManager.setBackgroundServiceEnabled(enabled) }
            }
        )

        SwitchSetting(
            title = "In-App Alerts",
            description = "Show banner notifications while you're using the app",
            checked = inAppNotifications,
            onCheckedChange = { enabled ->
                scope.launch { preferencesManager.setInAppNotificationsEnabled(enabled) }
            }
        )
    }
}

