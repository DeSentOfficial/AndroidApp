package xyz.desent.presentation.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.presentation.ui.settings.component.NotificationSettingsSection
import xyz.desent.presentation.ui.settings.component.SettingsCard
import xyz.desent.presentation.ui.settings.component.SettingsSubScreen
import androidx.compose.runtime.Composable

/**
 * Notifications spoke: the three delivery toggles (system tray, background
 * service, in-app banners), unchanged from the old inline section — now
 * carded on their own screen.
 */
@Composable
fun NotificationsSettingsScreen(
    onNavigateBack: () -> Unit,
    preferencesManager: PreferencesManager
) {
    SettingsSubScreen(title = "Notifications", onNavigateBack = onNavigateBack) {
        SettingsCard(
            icon = Icons.Default.Notifications,
            title = "Delivery",
            subtitle = "Where and how DeSent notifies you"
        ) {
            NotificationSettingsSection(
                preferencesManager = preferencesManager,
                showHeader = false
            )
        }
    }
}
