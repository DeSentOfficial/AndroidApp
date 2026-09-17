package xyz.desent.presentation.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import xyz.desent.presentation.ui.settings.component.SettingsCard
import xyz.desent.presentation.ui.settings.component.SettingsNavRow
import xyz.desent.presentation.ui.settings.component.SettingsSubScreen

/**
 * Anti-spam spoke: surfaces the two policy screens that previously had no
 * Settings entry point (deep links / inbox menus only).
 */
@Composable
fun AntiSpamSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSpamPolicy: () -> Unit,
    onNavigateToImagePolicy: () -> Unit
) {
    SettingsSubScreen(title = "Anti-spam", onNavigateBack = onNavigateBack) {
        SettingsCard(
            icon = Icons.Default.FilterAlt,
            title = "Filtering",
            subtitle = "How junk mail is classified and what happens to it"
        ) {
            SettingsNavRow(
                icon = Icons.Default.FilterAlt,
                title = "Spam filter policy",
                subtitle = "Detection layers, sensitivity and training",
                onClick = onNavigateToSpamPolicy
            )
            SettingsNavRow(
                icon = Icons.Default.Image,
                title = "Remote images",
                subtitle = "When external images are allowed to load",
                onClick = onNavigateToImagePolicy
            )
        }
    }
}
