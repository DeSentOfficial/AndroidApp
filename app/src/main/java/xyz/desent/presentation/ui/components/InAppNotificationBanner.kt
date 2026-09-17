package xyz.desent.presentation.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.desent.presentation.theme.Spacing

/**
 * In-app notification banner that appears at the top of the screen
 * Similar to toast notifications but more prominent and interactive
 */
@Composable
fun InAppNotificationBanner(
    message: InAppNotificationMessage,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(message.id) {
        // Animate in
        isVisible = true
        // Auto-dismiss after 5 seconds
        delay(5000)
        isVisible = false
        delay(300) // Wait for animation to finish
        onDismiss()
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(durationMillis = 300)
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(durationMillis = 300)
        ) + fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .clickable(onClick = onClick)
                .clip(RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading avatar (sender) or the generic vector icon
                if (message.avatarSeed != null) {
                    BannerAvatar(
                        url = message.avatarUrl,
                        seed = message.avatarSeed!!,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Icon(
                        imageVector = message.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                // Content
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = message.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (message.timestamp > 0) {
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }

                // Dismiss button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Data class for in-app notification message
 */
data class InAppNotificationMessage(
    val id: String,
    val title: String,
    val body: String,
    val icon: ImageVector = Icons.Default.Notifications,
    val timestamp: Long = 0L,
    val data: Map<String, String> = emptyMap(),
    /** Sender avatar URL (kind-0 picture or favicon) — renders when [avatarSeed] is set. */
    val avatarUrl: String? = null,
    /** Identity seed (sender email) driving the initials/tint fallback. */
    val avatarSeed: String? = null
)

/**
 * Miniature of the notification avatar: sender picture (or initials on the
 * deterministic tint) with the small DeSent badge — matching the system-tray
 * large icon. Loads async via Coil; failures reveal the initials underneath.
 */
@Composable
private fun BannerAvatar(url: String?, seed: String, modifier: Modifier = Modifier) {
    val initials = remember(seed) { senderInitials(seed, seed) }
    val tint = remember(seed) { avatarTintFor(seed) }
    Box(
        modifier = modifier.background(tint, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color.White
        )
        if (url != null) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }
        // DeSent badge: white disc + brand-amber glyph, bottom-end
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(14.dp)
                .background(androidx.compose.ui.graphics.Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(xyz.desent.R.drawable.ic_notification),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                    androidx.compose.ui.res.colorResource(xyz.desent.R.color.desent_amber)
                ),
                modifier = Modifier.size(9.dp)
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> "${diff / 86400000}d ago"
    }
}
