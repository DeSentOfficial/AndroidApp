package xyz.desent.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import xyz.desent.data.wearsync.WearEmail
import xyz.desent.wear.WearAppContainer
import xyz.desent.wear.ui.theme.LocalWearSurfaceVariant
import java.util.concurrent.TimeUnit

/**
 * Email list screen — renders the inbox (or spam) portion of the phone's
 * synced snapshot, styled after the phone app's mailbox: unread rows sit on
 * the surfaceVariant band with a bold sender and amber unread dot, read rows
 * recede to surface/onSurfaceVariant. Read-only: the watch never decrypts,
 * mutates, or sends mail; "Open on phone" hands the thread to the phone app.
 */
@Composable
fun InboxScreen(
    appContainer: WearAppContainer,
    isSpam: Boolean = false,
    onOpenEmail: (String) -> Unit
) {
    val inbox by appContainer.inbox.collectAsState()
    val emails = if (isSpam) inbox?.spam.orEmpty() else inbox?.emails.orEmpty()
    val unread = inbox?.unreadCount ?: 0

    TimeText()

    ScalingLazyColumn {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isSpam) "Spam" else "Inbox",
                    style = MaterialTheme.typography.title2,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                val countLabel = when {
                    isSpam && emails.isNotEmpty() -> "${emails.size} quarantined"
                    !isSpam && unread > 0 -> "$unread unread"
                    else -> null
                }
                countLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.caption2,
                        color = if (isSpam) MaterialTheme.colors.error else MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (emails.isEmpty()) {
            item {
                Text(
                    text = if (inbox == null) {
                        "No email synced yet. Open DeSent on your phone, then Resync in Settings."
                    } else if (isSpam) {
                        "No spam. Nice."
                    } else {
                        "Inbox is empty."
                    },
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (inbox == null) {
                item {
                    MenuChip(label = "Resync now", onClick = { appContainer.requestInboxFromPhone() })
                }
            }
        } else {
            items(emails) { email ->
                EmailRow(email = email, isSpamList = isSpam, onClick = { onOpenEmail(email.id) })
            }
        }
    }
}

/**
 * One mailbox row, mirroring the phone's EmailListItem: avatar with the amber
 * unread dot, sender weight/color marking read state, inline PGP/attachment
 * badges, and a one-line snippet (the phone's 50-char cap).
 */
@Composable
private fun EmailRow(email: WearEmail, isSpamList: Boolean, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        colors = ChipDefaults.chipColors(
            // The phone's read/unread surface swap: Slate band vs DeepNavy.
            // Wear's Colors has no surfaceVariant slot, so it comes from the theme local.
            backgroundColor = if (!email.isRead) {
                LocalWearSurfaceVariant.current
            } else {
                MaterialTheme.colors.surface
            },
            contentColor = MaterialTheme.colors.onSurface
        ),
        modifier = Modifier.fillMaxWidth(),
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box {
                    EmailSenderAvatar(
                        displayName = email.senderName.ifBlank { email.senderEmail },
                        seed = email.senderEmail,
                        size = 24.dp
                    )
                    if (!email.isRead) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(9.dp)
                                .background(MaterialTheme.colors.primary, CircleShape)
                                .border(1.dp, MaterialTheme.colors.surface, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = email.senderName.ifBlank { email.senderEmail },
                            style = MaterialTheme.typography.caption1,
                            fontWeight = if (email.isRead) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (email.isRead) {
                                MaterialTheme.colors.onSurfaceVariant
                            } else {
                                MaterialTheme.colors.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (email.isPgp) {
                            Text(
                                text = "🔒",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.secondary
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        if (email.attachmentCount > 0) {
                            Text(
                                text = "📎${email.attachmentCount}",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        if (isSpamList) {
                            Text(
                                text = "SPAM",
                                style = MaterialTheme.typography.caption2,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colors.error
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(
                            text = formatTimeAgo(email.createdAt),
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
                    Text(
                        text = email.subject,
                        style = MaterialTheme.typography.body2,
                        fontWeight = if (email.isRead) FontWeight.Normal else FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = snippetFor(email),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    )
}

/** One-line preview: the phone's 50-char cap; PGP mail stays visibly locked. */
private fun snippetFor(email: WearEmail): String {
    if (email.isPgp) return "🔒 Encrypted"
    val body = email.body.trim()
    if (body.isEmpty()) return ""
    return if (body.length <= 50) body else body.take(50) + "…"
}

/** Compact watch-friendly relative time: now · 5m · 3h · 2d · Mar 3. */
internal fun formatTimeAgo(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0) return ""
    val delta = nowMs - epochMs
    return when {
        delta < TimeUnit.MINUTES.toMillis(1) -> "now"
        delta < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(delta)}m"
        delta < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(delta)}h"
        delta < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(delta)}d"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
            "${monthShort(cal.get(java.util.Calendar.MONTH))} ${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
        }
    }
}

private fun monthShort(month: Int): String =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .getOrElse(month) { "" }
