package xyz.desent.wear.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import xyz.desent.data.wearsync.WearEmail
import xyz.desent.wear.WearAppContainer
import xyz.desent.wear.ui.theme.LocalWearSurfaceVariant

/**
 * Read-only email reader: the phone already decrypted and stripped the
 * body; the watch just renders it, styled after the phone's detail screen
 * (avatar header block, teal-bordered locked card for PGP, surfaceVariant
 * attachment row). "Open on phone" launches the phone app's thread view via
 * its existing `desent://email?threadKey=` deep link.
 */
@Composable
fun EmailDetailScreen(appContainer: WearAppContainer, emailId: String) {
    val inbox by appContainer.inbox.collectAsState()
    val email = remember(inbox, emailId) {
        (inbox?.emails.orEmpty() + inbox?.spam.orEmpty()).firstOrNull { it.id == emailId }
    }

    TimeText()

    ScalingLazyColumn {
        if (email == null) {
            item {
                Text(
                    text = "This email is no longer in the synced snapshot — it may have been " +
                        "moved or deleted on the phone. Resync from Settings.",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            return@ScalingLazyColumn
        }

        // ---- Header block: avatar over sender + address/date (phone layout) ----
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                EmailSenderAvatar(
                    displayName = email.senderName.ifBlank { email.senderEmail },
                    seed = email.senderEmail,
                    size = 28.dp
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = email.senderName.ifBlank { email.senderEmail },
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = email.senderEmail,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            Text(
                text = formatDate(email.createdAt) + if (!email.isRead) "  ·  unread" else "",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            // Hairline divider under the header (the phone's outlineVariant band).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .height(1.dp)
                    .background(
                        MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
        item {
            Text(
                text = email.subject,
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            if (email.isPgp) {
                // The phone's locked-PGP card: teal-bordered surface, lock glyph.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colors.secondary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "🔒",
                        style = MaterialTheme.typography.title3
                    )
                    Text(
                        text = "Encrypted message",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSurface
                    )
                    Text(
                        text = "Open on phone to decrypt",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = email.body,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (email.attachmentCount > 0) {
            item {
                // Attachment row: surfaceVariant tile with primary-tinted glyph.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(LocalWearSurfaceVariant.current, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "📎",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${email.attachmentCount} attachment(s) — open on phone",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }
        }
        item {
            OpenOnPhoneChip(email)
        }
    }
}

@Composable
private fun OpenOnPhoneChip(email: WearEmail) {
    val context = LocalContext.current
    MenuChip(
        label = "Open on phone",
        onClick = {
            val uri = Uri.parse("desent://email?threadKey=${Uri.encode(email.threadKey)}")
            runCatching {
                RemoteActivityHelper(context, Runnable::run)
                    .startRemoteActivity(Intent(Intent.ACTION_VIEW, uri))
                Toast.makeText(context, "Opening on phone…", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Phone not reachable", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

private fun formatDate(epochMs: Long): String =
    if (epochMs <= 0) "" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
