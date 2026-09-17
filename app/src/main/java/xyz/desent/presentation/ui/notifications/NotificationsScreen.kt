package xyz.desent.presentation.ui.notifications

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import xyz.desent.data.local.database.dao.BadgeNoticeJoinRow
import xyz.desent.domain.model.BadgeAward
import xyz.desent.domain.model.BadgeDefinition
import xyz.desent.domain.model.EarnedBadge
import xyz.desent.presentation.theme.Amber
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.BadgeArt
import xyz.desent.presentation.ui.components.BadgeDetailSheet
import xyz.desent.presentation.ui.notifications.viewmodel.NotificationRow
import xyz.desent.presentation.ui.notifications.viewmodel.NotificationsViewModel
import java.util.concurrent.TimeUnit

/**
 * The notifications tray: one chronological list of badge-award notices
 * (relay-sealed kind-1010 `direction:"badge"` wraps) and login-security
 * alerts for the active account. Opened from the Summary bell (and the
 * `desent://notifications` deep link used by the badge-award system
 * notification).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSecurityAlert: (eventId: String) -> Unit,
    viewModel: NotificationsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val selectedBadge = uiState.rows
        .filterIsInstance<NotificationRow.BadgeAward>()
        .firstOrNull { it.row.eventId == uiState.selectedBadgeEventId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.unseenBadgeCount + uiState.unseenSecurityCount > 0) {
                        TextButton(onClick = { viewModel.markAllSeen() }) {
                            Text("Mark all read")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = "No notifications yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items(uiState.rows, key = { it.eventId }) { row ->
                    when (row) {
                        is NotificationRow.BadgeAward -> BadgeNoticeRow(
                            row = row.row,
                            onClick = { viewModel.selectBadge(row.eventId) }
                        )
                        is NotificationRow.Security -> SecurityNoticeRow(
                            eventId = row.alert.eventId,
                            subject = row.alert.subject,
                            detail = listOfNotNull(
                                row.alert.geo ?: row.alert.ip,
                                row.alert.time.takeIf { it.isNotBlank() }
                            ).joinToString(" · ").ifBlank { row.alert.surface },
                            timestamp = row.alert.receivedAt,
                            isSeen = row.alert.isSeen,
                            onClick = { onNavigateToSecurityAlert(row.alert.eventId) }
                        )
                    }
                }
            }
        }
    }

    selectedBadge?.let { selected ->
        BadgeDetailSheet(
            badge = selected.row.toEarnedBadge(),
            onDismiss = { viewModel.selectBadge(null) }
        )
    }
}

@Composable
private fun BadgeNoticeRow(row: BadgeNoticeJoinRow, onClick: () -> Unit) {
    NoticeRow(
        leading = {
            BadgeArt(
                definition = row.toDefinition(),
                size = 24.dp,
                dense = true,
                contentDescription = null
            )
        },
        title = row.defName ?: row.subject,
        detail = row.body,
        timestamp = row.receivedAt,
        isSeen = row.isSeen,
        onClick = onClick
    )
}

@Composable
private fun SecurityNoticeRow(
    eventId: String,
    subject: String,
    detail: String,
    timestamp: Long,
    isSeen: Boolean,
    onClick: () -> Unit
) {
    NoticeRow(
        leading = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(22.dp)
            )
        },
        title = subject,
        detail = detail,
        timestamp = timestamp,
        isSeen = isSeen,
        onClick = onClick
    )
}

@Composable
private fun NoticeRow(
    leading: @Composable () -> Unit,
    title: String,
    detail: String,
    timestamp: Long,
    isSeen: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (!isSeen) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                SurfaceCircle(leading)
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!isSeen) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Text(
                    text = formatRelativeTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isSeen) {
                Spacer(modifier = Modifier.width(Spacing.sm))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Amber, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun SurfaceCircle(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun BadgeNoticeJoinRow.toDefinition() = BadgeDefinition(
    slug = slug,
    name = defName ?: subject,
    description = defDescription,
    imageUrl = defImageUrl,
    thumbUrl = defThumbUrl,
    iconName = defIconName,
    color = defColor
)

private fun BadgeNoticeJoinRow.toEarnedBadge() = EarnedBadge(
    definition = toDefinition(),
    award = BadgeAward(
        eventId = awardEventId ?: eventId,
        awardeeNpub = ownerNpub,
        slug = slug,
        definitionAddress = "30009:<relay>:$slug",
        awardedAt = awardedAt ?: receivedAt / 1000
    ),
    isPinned = false
)

internal fun formatRelativeTime(timestampMillis: Long): String {
    if (timestampMillis <= 0) return ""
    val diff = System.currentTimeMillis() - timestampMillis
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) ->
            "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) ->
            "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        diff < TimeUnit.DAYS.toMillis(7) ->
            "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestampMillis))
        }
    }
}
