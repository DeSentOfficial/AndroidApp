package xyz.desent.presentation.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.onEach
import xyz.desent.presentation.notification.InAppNotificationManager

/**
 * Container for in-app notifications
 * Place this at the top level of your app to show notifications everywhere
 */
@Composable
fun InAppNotificationContainer(
    notificationManager: InAppNotificationManager,
    onNotificationClick: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentNotification by remember { mutableStateOf<InAppNotificationMessage?>(null) }

    // Collect notifications from manager
    LaunchedEffect(notificationManager) {
        notificationManager.notifications
            .onEach { notification ->
                currentNotification = notification
            }
            .collect {}
    }

    Box(modifier = modifier) {
        // Notification banner (overlay)
        currentNotification?.let { notification ->
            InAppNotificationBanner(
                message = notification,
                onDismiss = { currentNotification = null },
                onClick = {
                    onNotificationClick(notification.data)
                    currentNotification = null
                },
                modifier = Modifier.zIndex(1f)
            )
        }
    }
}
