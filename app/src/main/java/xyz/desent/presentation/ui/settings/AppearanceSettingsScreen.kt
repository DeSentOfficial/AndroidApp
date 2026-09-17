package xyz.desent.presentation.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.local.preferences.ThemeMode
import xyz.desent.presentation.ui.settings.component.SettingsCard
import xyz.desent.presentation.ui.settings.component.SettingsSubScreen

/**
 * Appearance spoke: the theme selector, as a compact segmented control
 * (System / Light / Dark) replacing the old full-page radio list.
 */
@Composable
fun AppearanceSettingsScreen(
    onNavigateBack: () -> Unit,
    preferencesManager: PreferencesManager
) {
    val scope = rememberCoroutineScope()
    val themeMode by preferencesManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    SettingsSubScreen(title = "Appearance", onNavigateBack = onNavigateBack) {
        SettingsCard(
            icon = Icons.Default.Palette,
            title = "Theme",
            subtitle = "Applies across the app immediately"
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { scope.launch { preferencesManager.setThemeMode(mode) } },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size
                        )
                    ) {
                        Text(mode.displayName)
                    }
                }
            }
            Text(
                text = "System follows the device's dark-mode setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
