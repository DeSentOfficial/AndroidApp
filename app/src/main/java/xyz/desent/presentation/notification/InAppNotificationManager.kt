package xyz.desent.presentation.notification

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import xyz.desent.presentation.ui.components.InAppNotificationMessage

/**
 * Manager for in-app notifications
 * Single instance that can be observed from any screen
 */
class InAppNotificationManager {

    private val _notifications = MutableSharedFlow<InAppNotificationMessage>()
    val notifications: SharedFlow<InAppNotificationMessage> = _notifications

    /**
     * Show a new email notification in the app
     */
    suspend fun showNewEmailNotification(
        senderName: String,
        subject: String,
        threadKey: String,
        emailId: String,
        avatarUrl: String? = null,
        avatarSeed: String? = null
    ) {
        val title = senderName
        val truncatedSubject = if (subject.length > 100) subject.take(100) + "..." else subject

        val notification = InAppNotificationMessage(
            id = "email_${emailId}_${System.currentTimeMillis()}",
            title = title,
            body = truncatedSubject.ifBlank { "(no subject)" },
            icon = Icons.Default.Email,
            timestamp = System.currentTimeMillis(),
            data = mapOf(
                "type" to "new_email",
                "thread_key" to threadKey,
                "email_id" to emailId
            ),
            avatarUrl = avatarUrl,
            avatarSeed = avatarSeed
        )

        _notifications.emit(notification)
    }

    /**
     * Show a generic notification
     */
    suspend fun showNotification(
        title: String,
        message: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector = androidx.compose.material.icons.Icons.Default.Email,
        data: Map<String, String> = emptyMap()
    ) {
        val notification = InAppNotificationMessage(
            id = "notification_${System.currentTimeMillis()}",
            title = title,
            body = message,
            icon = icon,
            timestamp = System.currentTimeMillis(),
            data = data
        )

        _notifications.emit(notification)
    }
}
