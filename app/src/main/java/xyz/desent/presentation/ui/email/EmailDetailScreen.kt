package xyz.desent.presentation.ui.email

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ForwardToInbox
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import xyz.desent.R
import xyz.desent.data.spam.TrackingPixelDetector
import xyz.desent.domain.model.EmailAttachment
import xyz.desent.presentation.ui.email.components.ForwardToNpubDialog
import xyz.desent.presentation.ui.email.viewmodel.AttachmentDownloadState
import xyz.desent.presentation.ui.email.viewmodel.EmailDetailViewModel
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.DeleteConfirmationDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToReply: (String) -> Unit = {},
    onNavigateToReplyAll: (String) -> Unit = {},
    onAddToCalendar: (
        title: String,
        epochDay: Long,
        description: String,
        location: String,
        startSec: Long,
        endSec: Long
    ) -> Unit = { _, _, _, _, _, _ -> },
    viewModel: EmailDetailViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val senderAvatars by viewModel.senderProfiles.avatars.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current

    LaunchedEffect(uiState.toast) {
        uiState.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // Server confirmed a hard delete → local row is gone; pop back to inbox.
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            // Chrome-free: identity and subject live in the page header now.
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.email == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Email not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                val email = uiState.email!!

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // Frame D: the subject is the page title and the sender
                    // gets a proper avatar-anchored block — the top bar stays
                    // chrome-free.
                    DetailHeaderBlock(
                        email = email,
                        pictureUrl = senderAvatars[email.senderEmail.lowercase()],
                        folderPath = uiState.folderPath,
                        pgpEncrypted = email.isPgpEncrypted,
                        showPgpDecrypted = uiState.pgp is xyz.desent.presentation.ui.email.viewmodel.PgpBodyState.Decrypted
                    )

                    Column(modifier = Modifier.padding(Spacing.md)) {
                        val pgpDecrypted = uiState.pgp as? xyz.desent.presentation.ui.email.viewmodel.PgpBodyState.Decrypted
                        when {
                            uiState.pgp is xyz.desent.presentation.ui.email.viewmodel.PgpBodyState.Decrypting -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            }
                            uiState.pgp is xyz.desent.presentation.ui.email.viewmodel.PgpBodyState.Locked -> {
                                PgpLockedBody(
                                    reason = (uiState.pgp as xyz.desent.presentation.ui.email.viewmodel.PgpBodyState.Locked).reason
                                )
                            }
                            pgpDecrypted != null -> {
                                // Render the DECRYPTED body: sniffed format drives
                                // the plain/HTML switch in the normal render chain.
                                xyz.desent.presentation.ui.email.components.QuoteAwareEmailBody(
                                    email = email.copy(
                                        content = pgpDecrypted.message.body,
                                        bodyFormat = if (pgpDecrypted.message.isHtml) {
                                            xyz.desent.domain.model.EmailBodyFormat.HTML
                                        } else {
                                            xyz.desent.domain.model.EmailBodyFormat.PLAIN
                                        }
                                    ),
                                    imagePolicy = viewModel.imagePolicy,
                                    wrapContentHeight = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                            }
                            email.content.isBlank() -> {
                                Text(
                                    text = "This email has no content",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                            }
                            else -> {
                                xyz.desent.presentation.ui.email.components.QuoteAwareEmailBody(
                                    email = email,
                                    imagePolicy = viewModel.imagePolicy,
                                    wrapContentHeight = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                            }
                        }

                        // Attachments sit under the body, in the reading flow.
                        if (email.attachments.isNotEmpty()) {
                            AttachmentsSection(
                                attachments = email.attachments,
                                downloadStates = uiState.downloadStates,
                                onDownload = { viewModel.downloadAttachment(it) }
                            )
                        }
                        val pgpAttachments = (uiState.pgp as? xyz.desent.presentation.ui.email.viewmodel.PgpBodyState.Decrypted)
                            ?.attachmentFiles.orEmpty()
                        if (pgpAttachments.isNotEmpty()) {
                            PgpAttachmentsSection(pgpAttachments)
                        }

                        ActionButtons(email, viewModel, uiState, onNavigateToReply, onNavigateToReplyAll, onAddToCalendar)

                        TechnicalMetadataSection(email)
                        Spacer(Modifier.height(Spacing.lg))
                    }
                }
            }
        }

        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier.padding(Spacing.md)
            ) {
                Text(text = error)
            }
        }
    }
}

/**
 * Frame D header: the subject as the page title, then an avatar-anchored
 * sender block (name, address, to/from line) and a quiet chip row carrying
 * the folder + security verdicts. The top bar stays chrome-free.
 */
@Composable
private fun DetailHeaderBlock(
    email: xyz.desent.domain.model.Email,
    pictureUrl: String?,
    folderPath: String?,
    pgpEncrypted: Boolean,
    showPgpDecrypted: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
    ) {
        Text(
            text = email.subject.ifBlank { "(no subject)" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.sm)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            xyz.desent.presentation.ui.email.components.EmailSenderAvatar(
                displayName = email.displaySender,
                seed = email.senderEmail,
                pictureUrl = pictureUrl,
                size = 44.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = email.displaySender,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = email.senderEmail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // RFC 5322 recipient lines (END-01 §3.4): To under From, Cc
                // when present. Legacy single-address rows fall back to toEmail.
                val effectiveTo = email.toRecipients.ifEmpty {
                    email.toEmail?.let { listOf(xyz.desent.domain.model.EmailRecipient(it)) }.orEmpty()
                }
                if (effectiveTo.isNotEmpty()) {
                    Text(
                        text = "To: ${recipientSummary(effectiveTo)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (email.ccRecipients.isNotEmpty()) {
                    Text(
                        text = "Cc: ${recipientSummary(email.ccRecipients)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                val toLine = if (email.direction == xyz.desent.domain.model.EmailDirection.OUTBOUND) {
                    "from you" + (email.toEmail?.let { " → $it" } ?: "")
                } else {
                    "to you"
                }
                Text(
                    text = "$toLine · ${formatDate(email.senderDate ?: email.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (pgpEncrypted) {
                PgpBadge(decrypted = showPgpDecrypted, modifier = Modifier.padding(top = Spacing.xs))
            }
        }

        // Quiet verdict chips: folder assignment + DKIM/SPF/tracking state —
        // and, when this copy arrived via the SMTP envelope only, the muted
        // BCC chip (`delivered_to` ∉ to ∪ cc, END-01 §3.4).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md, bottom = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (email.bccHint) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "BCC — delivered to ${email.deliveredTo}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
                    )
                }
            }
            folderPath?.let { path ->
                xyz.desent.presentation.ui.email.components.FolderAssignmentChip(folderPath = path)
            }
            DkimLabel(email.dkimStatus)
            SpfLabel(email.spfStatus)
            TrackingPixelLabel(email)
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

/** Compact mono verdict badge (DKIM ✓ / SPF ✓ / PGP) on a hairline border. */
@Composable
private fun MetaBadge(
    text: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun PgpBadge(decrypted: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
        ),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(9.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "PGP",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * Locked placeholder for undecryptable PGP mail (ANDROID_PGP.md §3.5): the
 * armor is NEVER rendered as though it were the message body.
 */
@Composable
private fun PgpLockedBody(reason: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "This message is end-to-end encrypted. Add this account's " +
                    "PGP key (Settings → PGP Encryption) or import the matching key " +
                    "to read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Attachments extracted from inside the PGP envelope: already decrypted and
 * cached locally — "Open" only, no download path.
 */
@Composable
private fun PgpAttachmentsSection(files: List<File>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${files.size} decrypted attachment${if (files.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        files.forEach { file ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                RoundedCornerShape(9.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = { openAttachment(context, file, "") }
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open")
                    }
                }
            }
        }
    }
}

@Composable
private fun DkimLabel(dkimStatus: xyz.desent.domain.model.DkimStatus) {    val text = when (dkimStatus) {
        xyz.desent.domain.model.DkimStatus.PASS -> "DKIM ✓"
        xyz.desent.domain.model.DkimStatus.FAIL -> "DKIM ✗"
        xyz.desent.domain.model.DkimStatus.DISABLED -> "DKIM off"
        xyz.desent.domain.model.DkimStatus.NONE -> return
    }
    MetaBadge(text = text, isError = dkimStatus == xyz.desent.domain.model.DkimStatus.FAIL)
}

@Composable
private fun SpfLabel(spfStatus: xyz.desent.domain.model.SpfStatus) {
    val text = when (spfStatus) {
        xyz.desent.domain.model.SpfStatus.PASS -> "SPF ✓"
        xyz.desent.domain.model.SpfStatus.FAIL -> "SPF ✗"
        xyz.desent.domain.model.SpfStatus.DISABLED -> "SPF off"
        xyz.desent.domain.model.SpfStatus.UNKNOWN -> return
    }
    MetaBadge(text = text, isError = spfStatus == xyz.desent.domain.model.SpfStatus.FAIL)
}

@Composable
private fun TrackingPixelLabel(email: xyz.desent.domain.model.Email) {
    val hasPixels = remember(email.id, email.content) {
        TrackingPixelDetector.hasTrackingPixels(email.content)
    }
    if (!hasPixels) return
    Text(
        text = stringResource(R.string.tracking_pixel_chip),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary
    )
}

/**
 * Collapsed-by-default technical metadata (emailType · bridge, alias,
 * reply-to, diverging sender clock) behind a quiet toggle row — useful for
 * debugging provenance, noise for reading.
 */
@Composable
private fun TechnicalMetadataSection(email: xyz.desent.domain.model.Email) {
    var expanded by rememberSaveable(email.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "Technical metadata",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = email.emailType.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = email.bridge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                val detailRows = buildList {
                    email.alias?.let { add("Delivered via alias" to it) }
                    email.replyTo?.let {
                        if (it != email.senderEmail) add("Reply-To" to it)
                    }
                    email.toRecipients.takeIf { it.isNotEmpty() }?.let {
                        add("To" to it.joinToString(", ") { r -> r.displayLabel })
                    }
                    email.ccRecipients.takeIf { it.isNotEmpty() }?.let {
                        add("Cc" to it.joinToString(", ") { r -> r.displayLabel })
                    }
                    email.bccRecipients.takeIf { it.isNotEmpty() }?.let {
                        add("Bcc" to it.joinToString(", ") { r -> r.address })
                    }
                    email.deliveredTo?.let { add("Delivered to" to it) }
                    email.senderDate?.let {
                        if (kotlin.math.abs(it - email.createdAt) > 60_000L) {
                            add("Sender time" to formatDate(it))
                        }
                    }
                }
                if (detailRows.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(Spacing.sm)) {
                            detailRows.forEach { (label, value) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(110.dp)
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentsSection(
    attachments: List<EmailAttachment>,
    downloadStates: Map<String, AttachmentDownloadState>,
    onDownload: (EmailAttachment) -> Unit
) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${attachments.size} attachment${if (attachments.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        attachments.forEach { attachment ->
            val state = downloadStates[attachment.sha256]
            AttachmentRow(
                attachment = attachment,
                state = state,
                onDownload = { onDownload(attachment) },
                onOpen = {
                    val saved = (state as? AttachmentDownloadState.Saved)?.file
                    if (saved != null) openAttachment(context, saved, attachment.mimeType)
                }
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: EmailAttachment,
    state: AttachmentDownloadState?,
    onDownload: () -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded-square icon tile in the primary tint — the 28% corner
            // family applied to attachment chrome.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        attachment.mimeType.startsWith("image/") -> Icons.Default.Image
                        attachment.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.filename.ifBlank { attachment.sha256.take(16) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSize(attachment.size) + " · " + attachment.mimeType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (state) {
                is AttachmentDownloadState.Saved -> {
                    TextButton(onClick = onOpen) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open")
                    }
                }
                AttachmentDownloadState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                is AttachmentDownloadState.Failed -> {
                    TextButton(onClick = onDownload) { Text("Retry") }
                }
                else -> {
                    TextButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}

private fun openAttachment(context: android.content.Context, file: File, mimeType: String) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open attachment"))
    } catch (e: Exception) {
        android.util.Log.e("EmailDetail", "Failed to open attachment: ${e.message}", e)
        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

@Composable
private fun ActionButtons(
    email: xyz.desent.domain.model.Email,
    viewModel: EmailDetailViewModel,
    uiState: xyz.desent.presentation.ui.email.viewmodel.EmailDetailUiState,
    onNavigateToReply: (String) -> Unit = {},
    onNavigateToReplyAll: (String) -> Unit = {},
    onAddToCalendar: (
        title: String,
        epochDay: Long,
        description: String,
        location: String,
        startSec: Long,
        endSec: Long
    ) -> Unit = { _, _, _, _, _, _ -> }
) {
    var showMenu by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val folders by viewModel.folders.collectAsState()
    val assignedFolderId by viewModel.assignedFolderId.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { onNavigateToReply(email.id) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Reply, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Reply", maxLines = 1)
        }

        // Reply-all (RFC 5322 §3.6.2): to = reply_to ∥ from, cc = original
        // to ∪ cc minus own addresses — prefilled on the reply screen.
        OutlinedButton(
            onClick = { onNavigateToReplyAll(email.id) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = "Reply all", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("All", maxLines = 1)
        }

        OutlinedButton(
            onClick = { showForwardDialog = true },
            enabled = !uiState.isForwarding,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (uiState.isForwarding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.ForwardToInbox, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text("Forward", maxLines = 1)
            }
        }

        Box(
            modifier = Modifier.weight(1f)
        ) {
            OutlinedButton(
                onClick = { showMenu = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // AI-agent calendar proposal (ANDROID_AI_AGENTS.md §6): open
                // the NIP-52 editor prefilled from the `cal` tag. Saving
                // publishes under the USER's key like a hand-made entry —
                // never on the agent's behalf.
                if (email.isCalendarProposal) {
                    DropdownMenuItem(
                        text = { Text("Add to calendar") },
                        leadingIcon = {
                            Icon(Icons.Default.Event, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onAddProposalToCalendar(email, onAddToCalendar)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Move to folder…") },
                    leadingIcon = {
                        Icon(Icons.Default.Folder, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        showMoveSheet = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Save to contacts") },
                    leadingIcon = {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                    },
                    onClick = {
                        viewModel.saveSenderToContacts()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete message") },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    },
                    onClick = {
                        showDeleteConfirm = true
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Report as spam") },
                    leadingIcon = {
                        Icon(Icons.Default.Report, contentDescription = null)
                    },
                    onClick = {
                        viewModel.reportSpam()
                        showMenu = false
                    }
                )
            }
        }
    }

    if (showForwardDialog) {
        ForwardToNpubDialog(
            isSending = uiState.isForwarding,
            onDismiss = { showForwardDialog = false },
            onConfirm = { npub ->
                showForwardDialog = false
                viewModel.forwardToNpub(npub)
            }
        )
    }

    if (showMoveSheet) {
        xyz.desent.presentation.ui.email.components.MoveToFolderSheet(
            folders = folders,
            assignedFolderId = assignedFolderId,
            onDismiss = { showMoveSheet = false },
            onMove = { folderId ->
                showMoveSheet = false
                viewModel.moveToFolder(folderId)
            },
            onCreateFolder = {
                showMoveSheet = false
                showCreateFolder = true
            }
        )
    }

    if (showCreateFolder) {
        xyz.desent.presentation.ui.email.components.FolderNameDialog(
            title = "New folder",
            confirmLabel = "Create",
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                // Create, then file this message into the new folder.
                viewModel.createFolderAndFile(name)
            }
        )
    }

    if (showDeleteConfirm) {
        DeleteConfirmationDialog(
            title = "Delete message?",
            message = "Permanently delete \"${email.subject.ifBlank { "(no subject)" }}\"? " +
                "This can't be undone.",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.onRequestDeletion()
            },
            onDismiss = { showDeleteConfirm = false },
            isDeleting = uiState.isDeleting
        )
    }
}

/**
 * Parse the `cal` tag of an agent proposal (ANDROID_AI_AGENTS.md §6) and
 * hand it to the calendar editor prefill. Unknown JSON keys are ignored;
 * a missing/unparseable payload is a no-op (the mail itself stays normal
 * mail — the user can still copy details by hand).
 */
private fun onAddProposalToCalendar(
    email: xyz.desent.domain.model.Email,
    onAddToCalendar: (
        title: String,
        epochDay: Long,
        description: String,
        location: String,
        startSec: Long,
        endSec: Long
    ) -> Unit
) {
    val payload = try {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(xyz.desent.domain.model.AgentCalPayload.serializer(), email.calJson ?: return)
    } catch (e: Exception) {
        android.util.Log.w("EmailDetail", "unparseable cal tag: ${e.message}")
        return
    }

    val startSec = payload.startUnix.takeIf { it > 0 } ?: 0L
    val endSec = payload.endUnix.takeIf { it > startSec } ?: 0L
    // All-day fallback day: the proposed start, else today (UTC epoch day —
    // the editor resolves the floating date in the local zone).
    val reference = if (startSec > 0) startSec else System.currentTimeMillis() / 1000L
    val epochDay = Math.floorDiv(reference, 24L * 60 * 60)

    onAddToCalendar(
        payload.title.ifBlank { email.subject },
        epochDay,
        payload.details.orEmpty(),
        payload.location.orEmpty(),
        startSec,
        endSec
    )
}

/**
 * Compact recipient-list summary for the header lines: `Name <addr>` per
 * mailbox (END-01 §3.4 slot-3 display names), first two shown then `+N`.
 */
private fun recipientSummary(recipients: List<xyz.desent.domain.model.EmailRecipient>): String {
    val shown = recipients.take(2).joinToString(", ") { it.displayLabel }
    val extra = recipients.size - 2
    return if (extra > 0) "$shown (+$extra)" else shown
}

private fun formatDate(timestamp: Long): String {    val date = Date(timestamp)
    return SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(date)
}
