package xyz.desent.presentation.ui.email

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.desent.R
import xyz.desent.data.spam.RemoteImagePolicyState
import xyz.desent.di.AppContainer
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailOutboxEntry
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.OutboxStatus
import xyz.desent.presentation.theme.InputWellDark
import xyz.desent.presentation.theme.InputWellLight
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.AccountAvatar
import xyz.desent.presentation.ui.email.components.EmailSenderAvatar
import xyz.desent.presentation.ui.email.components.EmailTopBarTitle
import xyz.desent.presentation.ui.email.components.PgpLockButton
import xyz.desent.presentation.ui.email.components.QuoteAwareEmailBody
import xyz.desent.presentation.ui.email.components.emailPreview
import xyz.desent.presentation.ui.email.viewmodel.EmailThreadViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailThreadScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPgpSettings: () -> Unit = {},
    onNavigateToReply: (anchorId: String, draft: String) -> Unit = { _, _ -> },
    viewModel: EmailThreadViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val senderAvatars by viewModel.senderProfiles.avatars.collectAsState()
    val context = LocalContext.current
    val activeAccount by remember {
        AppContainer.getInstance(context).sessionManager.activeAccount
    }.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val anchorSubject = remember(uiState.messages) {
        uiState.messages.firstOrNull { it.emailType != EmailType.SYSTEM }?.subject
            ?: uiState.messages.firstOrNull()?.subject
            ?: "Thread"
    }

    // Gmail-style collapse: older messages render as one-line summaries until
    // tapped. Survives recomposition and process death; a newly arrived
    // message becomes the expanded one and its predecessors re-collapse.
    val defaultExpanded = remember(uiState.messages) { defaultExpandedIds(uiState.messages) }
    var userExpandedIds by rememberSaveable { mutableStateOf(listOf<String>()) }

    // Reading mode vs conversation mode: until the user replies there is no
    // per-message avatar column — broadcast mail (Dominos, Chase …) reads
    // full width like a document. The first send flips this for the thread.
    val conversation = remember(uiState.messages) { hasUserResponse(uiState.messages) }

    // The external party this thread is with: the first inbound sender, or —
    // for a thread of our own sends — the address we sent to.
    val threadAnchor = remember(uiState.messages) {
        uiState.messages.firstOrNull { it.direction != EmailDirection.OUTBOUND && it.emailType != EmailType.SYSTEM }
            ?: uiState.messages.firstOrNull { it.direction == EmailDirection.OUTBOUND }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val isOutboundAnchor = threadAnchor?.direction == EmailDirection.OUTBOUND
                    EmailTopBarTitle(
                        displayName = when {
                            isOutboundAnchor -> "You"
                            threadAnchor != null -> threadAnchor.displaySender
                            else -> "Thread"
                        },
                        seed = if (isOutboundAnchor) {
                            threadAnchor?.toEmail ?: threadAnchor?.senderEmail.orEmpty()
                        } else {
                            threadAnchor?.senderEmail.orEmpty()
                        },
                        subject = anchorSubject,
                        pictureUrl = threadAnchor?.let {
                            senderAvatars[it.senderEmail.lowercase()]
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            ReplyBar(
                account = activeAccount,
                text = uiState.replyText,
                isSending = uiState.isSending,
                cooldown = uiState.cooldown,
                error = uiState.error,
                pgp = uiState.pgp,
                onPgpToggle = { locked -> viewModel.pgpLock?.onToggle(locked) },
                onPgpNeedKey = onNavigateToPgpSettings,
                onTextChange = viewModel::onReplyTextChange,
                onSend = { viewModel.sendReply() },
                onExpand = {
                    uiState.replyAnchorId?.let { anchorId ->
                        onNavigateToReply(anchorId, uiState.replyText)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(vertical = Spacing.sm, horizontal = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    itemsIndexed(uiState.messages, key = { _, email -> email.id }) { index, email ->
                        // Frame D language: a day header when the calendar day
                        // changes, else a hairline inset past the avatar.
                        val previous = uiState.messages.getOrNull(index - 1)
                        if (previous == null || !sameEffectiveDay(email, previous)) {
                            DayHeaderRow(email)
                        } else {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = if (conversation) 52.dp else 0.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        if (email.id !in defaultExpanded && email.id !in userExpandedIds) {
                            CollapsedMessageRow(
                                email = email,
                                onExpand = { userExpandedIds = userExpandedIds + email.id }
                            )
                        } else {
                            ThreadMessageCard(
                                email = email,
                                pictureUrl = senderAvatars[email.senderEmail.lowercase()],
                                showAvatar = conversation,
                                outboxEntry = email.messageId?.let { uiState.outbox[it] },
                                imagePolicy = viewModel.imagePolicy,
                                pgpDecrypted = email.id in uiState.pgpDecryptedIds,
                                pgpLocked = email.id in uiState.pgpLockedIds,
                                onRetry = { messageId -> viewModel.retrySend(messageId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One-line summary of an older thread message (Gmail-style collapsed state):
 * attribution, snippet (own words only — quoted tails stripped) and time.
 * Tapping expands the full card in place.
 */
@Composable
private fun CollapsedMessageRow(
    email: Email,
    onExpand: () -> Unit
) {
    val isSent = email.direction == EmailDirection.OUTBOUND
    val timeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        email.emailType == EmailType.SYSTEM -> "Delivery status"
                        isSent -> "You → ${sentRecipientSummary(email) ?: email.senderEmail}"
                        else -> email.displaySender
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = emailPreview(email),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = timeFormat.format(Date(email.senderDate ?: email.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm)
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = "Expand message",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Flat Frame D message: no card surface — the avatar anchors identity, the
 * name/time/badges row anchors provenance, and the body reads straight off
 * the background. Delivery state is a quiet line under the sender row of
 * your own sends.
 *
 * [showAvatar] is false in reading mode (no user reply yet): the avatar
 * column drops out and the body takes the full width — broadcast mail reads
 * like a document instead of a chat.
 */
@Composable
private fun ThreadMessageCard(
    email: Email,
    pictureUrl: String?,
    showAvatar: Boolean,
    outboxEntry: EmailOutboxEntry?,
    imagePolicy: RemoteImagePolicyState,
    pgpDecrypted: Boolean,
    pgpLocked: Boolean,
    onRetry: (String) -> Unit
) {
    val isSystem = email.emailType == EmailType.SYSTEM
    val isSent = email.direction == EmailDirection.OUTBOUND
    val timeFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val timestamp = email.senderDate ?: email.createdAt

    if (isSystem) {
        // Short bridge status line — plain text on a quiet surface.
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(Spacing.sm)) {
                Text(
                    text = "Delivery status · ${timeFormat.format(Date(timestamp))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = email.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (showAvatar) {
            EmailSenderAvatar(
                displayName = if (isSent) "You" else email.displaySender,
                seed = email.senderEmail,
                pictureUrl = pictureUrl,
                size = 40.dp
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isSent) "You" else email.displaySender,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isSent) {
                    sentRecipientSummary(email)?.let {
                        Text(
                            text = "→ $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(start = Spacing.xs)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (pgpDecrypted || email.isPgpEncrypted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = Spacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "PGP",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(start = 3.dp)
                        )
                    }
                }
                if (email.dkimStatus == DkimStatus.PASS || email.dkimStatus == DkimStatus.FAIL) {
                    Text(
                        text = if (email.dkimStatus == DkimStatus.PASS) "DKIM ✓" else "DKIM ✗",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (email.dkimStatus == DkimStatus.FAIL) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(end = Spacing.sm)
                    )
                }
                Text(
                    text = timeFormat.format(Date(timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Delivery state rides with the header (Gmail idiom) so it stays
            // anchored to the sender line whatever height the body view ends
            // up settling on.
            if (isSent) {
                DeliveryStatusFooter(entry = outboxEntry, onRetry = onRetry)
            }
            Spacer(Modifier.height(Spacing.xs))
            if (pgpLocked) {
                // ANDROID_PGP.md §3.5: never render the raw armor as though it
                // were the message body.
                PgpLockedBody(reason = "This message is PGP-encrypted")
            } else {
                QuoteAwareEmailBody(
                    email = email,
                    imagePolicy = imagePolicy,
                    wrapContentHeight = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Sent-message attribution: the To list (END-01 §3.4), first two display
 * names/addresses then `+N`; legacy single-address rows fall back to toEmail.
 */
private fun sentRecipientSummary(email: Email): String? {
    val list = email.toRecipients.ifEmpty {
        email.toEmail?.let { listOf(EmailRecipient(it)) }.orEmpty()
    }
    if (list.isEmpty()) return null
    val shown = list.take(2).joinToString(", ") {
        it.displayName?.takeIf { d -> d.isNotBlank() } ?: it.address
    }
    val extra = list.size - 2
    return if (extra > 0) "$shown (+$extra)" else shown
}

/** Amber day label with a fading rule — the notes list's date-header idiom. */
@Composable
private fun DayHeaderRow(email: Email) {
    val timestamp = email.senderDate ?: email.createdAt
    val label = remember(timestamp) { dayLabel(timestamp) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs, bottom = Spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(Spacing.sm))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}

/** Today / Yesterday / "Wed, Sep 10, 2026" bucket for a timestamp. */
internal fun dayLabel(timestamp: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    return when {
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
        isYesterday(now, then) -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun isYesterday(now: java.util.Calendar, then: java.util.Calendar): Boolean {
    val y = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return y.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        y.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
}

/** Same calendar day per the effective (sender-claimed, else relay) stamp. */
internal fun sameEffectiveDay(a: Email, b: Email): Boolean {
    val day = java.util.Calendar.getInstance().apply { timeInMillis = a.senderDate ?: a.createdAt }
    val other = java.util.Calendar.getInstance().apply { timeInMillis = b.senderDate ?: b.createdAt }
    return day.get(java.util.Calendar.YEAR) == other.get(java.util.Calendar.YEAR) &&
        day.get(java.util.Calendar.DAY_OF_YEAR) == other.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * Locked placeholder for undecryptable PGP mail (banner pattern from
 * RemoteImagesBanner): a short reason plus an actionable hint.
 */
@Composable
private fun PgpLockedBody(reason: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Text(
                text = "$reason — it can't be decrypted on this device. " +
                    "Add this account's PGP key (Settings → PGP Encryption) to read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Delivery state of one sent message, from the outbox ledger. Legacy sends
 * that predate the ledger render a plain "Sent" (no entry exists to track).
 */
@Composable
private fun DeliveryStatusFooter(
    entry: EmailOutboxEntry?,
    onRetry: (String) -> Unit
) {
    when (entry?.status) {
        null -> Text(
            text = "Sent",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutboxStatus.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "Sending…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutboxStatus.CONFIRMED -> Text(
            text = "Delivered",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4CAF50)
        )
        OutboxStatus.FAILED, OutboxStatus.TIMED_OUT -> Column {
            val reason = entry.errorMessage
                ?: "No confirmation from the bridge was received"
            Text(
                text = if (entry.status == OutboxStatus.TIMED_OUT) {
                    "Not confirmed — $reason"
                } else {
                    reason.lineSequence().firstOrNull() ?: "Send failed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.canRetry) {
                TextButton(
                    onClick = { onRetry(entry.messageId) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text("Retry send")
                }
            }
        }
    }
}

/**
 * The thread quick-reply bar: the active account's avatar outside on the
 * left, then a rounded field carrying the PGP lock inline (tap to toggle,
 * teal = on) and the reply text. The square send button only enables once
 * there's something to send.
 */
@Composable
private fun ReplyBar(
    account: xyz.desent.domain.model.Account?,
    text: String,
    isSending: Boolean,
    cooldown: Boolean,
    error: String?,
    pgp: xyz.desent.presentation.ui.email.viewmodel.PgpComposeLock.State?,
    onPgpToggle: (Boolean) -> Unit,
    onPgpNeedKey: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onExpand: () -> Unit
) {
    val canSend = !isSending && !cooldown && text.isNotBlank()
    // The input well token flips with the theme (dark: near-black inset,
    // light: white).
    val wellColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        InputWellLight
    } else {
        InputWellDark
    }
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }
            pgp?.hint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountAvatar(account = account, size = 38.dp, contentDescription = "Replying as")
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = wellColor,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Expand into the full composer: everything the new-email
                        // screen offers (formatting, link insert, PGP), with the
                        // typed draft carried over.
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Open full reply",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = Spacing.xs)
                                .size(16.dp)
                                .clickable(enabled = !isSending, onClick = onExpand)
                        )
                        pgp?.takeIf { it.visible }?.let { pgpState ->
                            PgpLockButton(
                                state = pgpState,
                                enabled = !isSending,
                                onToggle = { onPgpToggle(!pgpState.locked) },
                                onNeedKey = onPgpNeedKey
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = Spacing.md, horizontal = Spacing.xs),
                            enabled = !isSending,
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box {
                                    if (text.isEmpty()) {
                                        Text(
                                            text = "Reply…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_desent_send),
                            contentDescription = "Send reply",
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
