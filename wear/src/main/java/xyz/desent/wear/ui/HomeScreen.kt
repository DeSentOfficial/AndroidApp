package xyz.desent.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import java.time.Instant
import java.time.ZoneId
import xyz.desent.data.wearsync.WearCalendar
import xyz.desent.wear.WearAppContainer
import xyz.desent.wear.ui.theme.LocalWearHeroColors
import xyz.desent.wear.ui.theme.PressStartFontFamily

/**
 * DeSent hub: branded identity card up top, then feature chips. Watch
 * features (email, calendar) arrive here as they land on the platform.
 */
@Composable
fun HomeScreen(
    appContainer: WearAppContainer,
    onNavigate: (String) -> Unit
) {
    val configLoaded by appContainer.initiallyLoaded.collectAsState()
    val inbox by appContainer.inbox.collectAsState()
    val calendar by appContainer.calendar.collectAsState()
    val bunkerRequest by appContainer.bunkerRequest.collectAsState()
    val unread = inbox?.unreadCount ?: 0
    val spamCount = inbox?.spam?.size ?: 0
    val bunkerPending = bunkerRequest?.requestId != null
    val todayEventCount = remember(calendar) { todayEventCount(calendar) }

    TimeText()

    // Startup gate: the first read of the persisted store is async — show a
    // spinner instead of a false "set up on phone" empty-state flash.
    if (!configLoaded) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(strokeWidth = 3.dp)
        }
        return
    }

    ScalingLazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        // ---- Branded identity hero ----
        item {
            HeroCard(
                borderColor = LocalWearHeroColors.current.ownBorder,
                surfaceTop = LocalWearHeroColors.current.surfaceTop,
                surfaceBottom = LocalWearHeroColors.current.surfaceBottom
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "DeSent",
                        style = MaterialTheme.typography.title3.copy(
                            fontFamily = PressStartFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Private email & productivity",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // ---- Feature chips ----
        item {
            MenuChip(
                label = if (unread > 0) "✉ Inbox ($unread)" else "✉ Inbox",
                onClick = { onNavigate("inbox") }
            )
        }
        item {
            MenuChip(
                label = if (todayEventCount > 0) "📅 Today ($todayEventCount)" else "📅 Calendar",
                onClick = { onNavigate("agenda") }
            )
        }
        if (inbox?.spamEnabled == true && spamCount > 0) {
            item {
                MenuChip(
                    label = "⚠ Spam ($spamCount)",
                    onClick = { onNavigate("spam") }
                )
            }
        }
        item {
            MenuChip(
                label = if (bunkerPending) "🔐 Bunker (1)" else "🔐 Bunker",
                onClick = { onNavigate("bunker") }
            )
        }
        item { MenuChip(label = "⚙ Settings", onClick = { onNavigate("settings") }) }
    }
}

/** Shared hero-surface card: gradient + accent border ring, theme-aware. */
@Composable
private fun HeroCard(
    borderColor: androidx.compose.ui.graphics.Color,
    surfaceTop: androidx.compose.ui.graphics.Color,
    surfaceBottom: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(surfaceTop, surfaceBottom)),
                RoundedCornerShape(12.dp)
            )
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
internal fun MenuChip(label: String, onClick: () -> Unit) {
    androidx.wear.compose.material.Chip(
        onClick = onClick,
        label = { Text(label) },
        colors = androidx.wear.compose.material.ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Events starting today (Home chip badge) — same day-key logic as the agenda. */
private fun todayEventCount(calendar: WearCalendar?): Int {
    if (calendar == null) return 0
    val zone = ZoneId.systemDefault()
    val today = Instant.now().atZone(zone).toLocalDate().toEpochDay()
    return calendar.events.count { it.startDay(zone) == today }
}
