package xyz.desent.presentation.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import xyz.desent.presentation.ui.settings.component.SettingsCard
import xyz.desent.presentation.ui.settings.component.SettingsSubScreen

/**
 * Markdown files spoke: explains the .md file association and routes to the
 * system "Open by default" page (Android offers no API for an app to set
 * itself as the default handler — the user grants it there).
 */
@Composable
fun MarkdownFilesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    SettingsSubScreen(title = "Markdown files", onNavigateBack = onNavigateBack) {
        SettingsCard(
            icon = Icons.Default.Description,
            title = "Open .md files as encrypted notes"
        ) {
            Text(
                text = "DeSent opens Markdown (.md) documents from your device. " +
                    "View, edit, or save them as encrypted private notes. " +
                    "Open one from any file manager, or from the folder icon on the Notes screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                try {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    android.widget.Toast.makeText(
                        context,
                        "Not available on this device",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }) {
                Text("Choose defaults for opening files")
            }
        }
    }
}
