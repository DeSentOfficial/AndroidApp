package xyz.desent.presentation.ui.email

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import xyz.desent.di.AppContainer
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.presentation.theme.Spacing
import xyz.desent.presentation.ui.components.BorderlessTextField
import xyz.desent.presentation.ui.email.components.EmailComposeBottomBar
import xyz.desent.presentation.ui.email.components.FormatChip
import xyz.desent.presentation.ui.email.components.RecipientChipField
import xyz.desent.presentation.ui.email.components.RichTextEditor
import xyz.desent.presentation.ui.email.components.rememberRichTextEditorState
import xyz.desent.presentation.ui.email.viewmodel.EmailReplyViewModel
import xyz.desent.presentation.ui.email.viewmodel.RecipientField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailReplyScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPgpSettings: () -> Unit = {},
    viewModel: EmailReplyViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activeAccount by remember {
        AppContainer.getInstance(context).sessionManager.activeAccount
    }.collectAsState()
    val editorState = rememberRichTextEditorState()
    val toInteractionSource = remember { MutableInteractionSource() }
    val ccInteractionSource = remember { MutableInteractionSource() }
    val bccInteractionSource = remember { MutableInteractionSource() }

    LaunchedEffect(uiState.sent) {
        if (uiState.sent) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    // Reply-in-kind: the format is pre-set from the anchor and
                    // can be flipped here.
                    FormatChip(
                        format = uiState.format,
                        enabled = !uiState.isSending,
                        onToggle = {
                            viewModel.onFormatChange(
                                if (uiState.format == EmailBodyFormat.HTML) {
                                    EmailBodyFormat.PLAIN
                                } else {
                                    EmailBodyFormat.HTML
                                }
                            )
                        }
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Button(
                        onClick = { viewModel.sendReply() },
                        enabled = !uiState.isSending && uiState.replyText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)
                    ) {
                        if (uiState.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Reply")
                        }
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }
            )
        },
        bottomBar = {
            EmailComposeBottomBar(
                account = activeAccount,
                format = uiState.format,
                editorState = editorState,
                pgp = uiState.pgp,
                // END-06/END-03 §6: PGP encrypts to exactly one WKD key — the
                // lock cannot engage on a multi-recipient send, so say why.
                pgpHint = when {
                    (uiState.pgp?.visible == true) && !uiState.singleRecipient ->
                        "PGP supports a single recipient"
                    else -> uiState.pgp?.hint
                },
                enabled = !uiState.isSending,
                onPgpToggle = { viewModel.pgpLock?.onToggle(!(uiState.pgp?.locked ?: false)) },
                onPgpNeedKey = onNavigateToPgpSettings,
                onInsertPlainText = viewModel::insertPlainText
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
        ) {
            // Recipient lines, prefilled by reply mode (plain reply → the
            // sender / Reply-To; reply-all → RFC 5322 §3.6.2 to+cc) and fully
            // editable before sending (END-01 §3.4 chip rows, same as compose).
            RecipientChipField(
                label = "To",
                recipients = uiState.to,
                text = uiState.fieldTexts[RecipientField.TO].orEmpty(),
                onTextChange = { viewModel.onFieldTextChange(RecipientField.TO, it) },
                onRemoveRecipient = { viewModel.removeRecipient(RecipientField.TO, it) },
                onFocusChanged = { viewModel.onFieldFocusChanged(RecipientField.TO, it) },
                placeholder = "recipient@example.com",
                enabled = !uiState.isSending,
                interactionSource = toInteractionSource,
                modifier = Modifier.fillMaxWidth()
            )
            if (uiState.showCcBcc) {
                RecipientChipField(
                    label = "Cc",
                    recipients = uiState.cc,
                    text = uiState.fieldTexts[RecipientField.CC].orEmpty(),
                    onTextChange = { viewModel.onFieldTextChange(RecipientField.CC, it) },
                    onRemoveRecipient = { viewModel.removeRecipient(RecipientField.CC, it) },
                    onFocusChanged = { viewModel.onFieldFocusChanged(RecipientField.CC, it) },
                    placeholder = "cc@example.com",
                    enabled = !uiState.isSending,
                    interactionSource = ccInteractionSource,
                    modifier = Modifier.fillMaxWidth()
                )
                RecipientChipField(
                    label = "Bcc",
                    recipients = uiState.bcc,
                    text = uiState.fieldTexts[RecipientField.BCC].orEmpty(),
                    onTextChange = { viewModel.onFieldTextChange(RecipientField.BCC, it) },
                    onRemoveRecipient = { viewModel.removeRecipient(RecipientField.BCC, it) },
                    onFocusChanged = { viewModel.onFieldFocusChanged(RecipientField.BCC, it) },
                    placeholder = "bcc@example.com",
                    enabled = !uiState.isSending,
                    interactionSource = bccInteractionSource,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                TextButton(
                    onClick = viewModel::toggleCcBcc,
                    enabled = !uiState.isSending,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cc/Bcc", style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Original email context (subject lives here, so the top bar can
            // stay chrome-free).
            uiState.email?.let { email ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm)
                ) {
                    Column(modifier = Modifier.padding(Spacing.sm)) {
                        Text(
                            text = "Re: ${email.subject}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            // Keyed on format so switching recreates the editor with the
            // converted body.
            key(uiState.format) {
                if (uiState.format == EmailBodyFormat.HTML) {
                    RichTextEditor(
                        state = editorState,
                        html = uiState.replyText,
                        onHtmlChange = viewModel::onReplyTextChange,
                        enabled = !uiState.isSending,
                        minHeightDp = 240,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    BorderlessTextField(
                        value = uiState.replyText,
                        onValueChange = viewModel::onReplyTextChange,
                        placeholder = "Type your reply…",
                        enabled = !uiState.isSending,
                        minLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                    )
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = Spacing.xs)
                )
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
