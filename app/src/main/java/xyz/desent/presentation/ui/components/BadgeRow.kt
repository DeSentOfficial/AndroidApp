package xyz.desent.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.desent.di.AppContainer
import xyz.desent.domain.model.EarnedBadge
import xyz.desent.presentation.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The pinned-badge row for a profile (ANDROID_BADGES.md §4): each displayed
 * badge as a compact ICON-ONLY circular chip (no name — the name appears in
 * the detail sheet when pressed). Self-contained — resolves the badge
 * repository from [AppContainer] (same pattern as
 * StatusEditorDialog) and triggers an on-demand badge sync for the
 * profile's npub, so it works on the active account's header and on other
 * users' profile dialogs alike.
 *
 * Renders nothing while the user has no pinned badges.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeRow(
    npub: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val badgeRepository = remember { AppContainer.getInstance(context).badgeRepository }
    val badges by remember(npub) {
        badgeRepository.observePinnedBadges(npub)
    }.collectAsState(initial = emptyList())

    // On-demand sync: definitions (once per process) + this user's
    // awards / pin list / revocations, on the authenticated relay.
    LaunchedEffect(npub) {
        badgeRepository.requestBadgeSync(npub)
    }

    if (badges.isEmpty()) return

    var selected by remember { mutableStateOf<EarnedBadge?>(null) }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        badges.forEach { badge ->
            BadgeChip(badge = badge, onClick = { selected = badge })
        }
    }

    selected?.let { badge ->
        BadgeDetailSheet(badge = badge, onDismiss = { selected = null })
    }
}

/**
 * Icon-only circular chip. The badge name is the accessibility label
 * (TalkBack announces it) and shows visually only inside the detail sheet.
 */
@Composable
private fun BadgeChip(badge: EarnedBadge, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClickLabel = badge.definition.name, onClick = onClick)
            .semantics { contentDescription = badge.definition.name },
        contentAlignment = Alignment.Center
    ) {
        BadgeArt(
            definition = badge.definition,
            size = 18.dp,
            dense = true,
            // The chip container labels itself via onClickLabel + semantics;
            // the glyph itself stays decoration.
            contentDescription = null
        )
    }
}

/** Full badge detail sheet: image art, name, description, awarded date. */
@Composable
fun BadgeDetailSheet(badge: EarnedBadge, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    BadgeArt(
                        definition = badge.definition,
                        size = 56.dp,
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.md))
                Text(
                    text = badge.definition.name,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Awarded ${formatAwardedDate(badge.award.awardedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                badge.definition.description?.let { description ->
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/** Kind 8 `created_at` (seconds) → "Mar 4, 2026". */
internal fun formatAwardedDate(awardedAtSeconds: Long): String {
    if (awardedAtSeconds <= 0) return ""
    return try {
        val date = Instant.ofEpochSecond(awardedAtSeconds)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    } catch (e: Exception) {
        ""
    }
}
