package xyz.desent.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.desent.presentation.theme.Spacing

@Composable
fun ErrorBadge(
    visible: Boolean,
    message: String? = null,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (visible) {
        Row(
            modifier = modifier
                .clip(CircleShape)
                .background(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    shape = CircleShape
                )
                .clickable { onRetry() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ProfileErrorBadge(
    visible: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    ErrorBadge(
        visible = visible,
        message = "Sync error",
        onRetry = onRetry,
        modifier = modifier
    )
}

@Composable
fun NetworkErrorBanner(
    visible: Boolean,
    message: String = "Network error. Showing cached data.",
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (visible) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
                .clickable { onRetry() }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            Text(
                text = "Tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
