package xyz.desent.presentation.ui.email.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.desent.data.EmailBridgeTags
import xyz.desent.data.pgp.PgpFeatureGate
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.repository.PgpKeyRepository
import xyz.desent.domain.usecase.EmailUseCase

data class EmailReplyUiState(
    val email: Email? = null,
    /** Committed recipient chips (RFC 5322 lists, END-01 §3.4) — prefilled by
     *  reply mode: plain reply → the sender (or Reply-To); reply-all → RFC
     *  5322 §3.6.2 (`to` = reply_to ∥ from; `cc` = original to ∪ cc minus own). */
    val to: List<EmailRecipient> = emptyList(),
    val cc: List<EmailRecipient> = emptyList(),
    val bcc: List<EmailRecipient> = emptyList(),
    val fieldTexts: Map<RecipientField, String> = emptyMap(),
    val activeField: RecipientField = RecipientField.TO,
    val showCcBcc: Boolean = false,
    /** Reply body in [format]: HTML from the rich editor, or plain text. */
    val replyText: String = "",
    /** Pre-set from the anchor's format ("reply in kind"), user-toggleable. */
    val format: EmailBodyFormat = EmailBodyFormat.HTML,
    val isSending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
    /** PGP lock state; null when the build has no PGP stack (tests). */
    val pgp: PgpComposeLock.State? = null
) {
    /** Total envelope recipients (to+cc+bcc) — the END-03 §6 cap input. */
    val recipientCount: Int get() = to.size + cc.size + bcc.size

    /** PGP encrypts to exactly one WKD key — the lock only engages solo (END-06). */
    val singleRecipient: Boolean get() = recipientCount == 1
}

class EmailReplyViewModel(
    private val emailId: String,
    private val emailUseCase: EmailUseCase,
    pgpKeyRepository: PgpKeyRepository? = null,
    pgpFeatureGate: PgpFeatureGate? = null,
    autoEncryptFlow: Flow<Boolean>? = null,
    /** Quick-reply draft carried over from the thread's reply bar. */
    initialDraft: String = "",
    /** Reply-all prefill (ANDROID_EMAIL_MIGRATION.md §7): to = reply_to ∥ from; cc = original to ∪ cc minus own addresses. */
    private val replyAll: Boolean = false
) : ViewModel() {

    /** To/Cc/Bcc chip editing — shared with the compose surface. */
    private val fields = RecipientFieldsController()

    private val _uiState = MutableStateFlow(EmailReplyUiState(replyText = initialDraft))
    val uiState: StateFlow<EmailReplyUiState> = _uiState.asStateFlow()

    /** PGP compose lock; default ON when the anchor arrived encrypted. */
    val pgpLock: PgpComposeLock? = pgpKeyRepository?.let { repo ->
        pgpFeatureGate?.let { gate ->
            PgpComposeLock(viewModelScope, repo, gate, autoEncryptFlow)
        }
    }

    init {
        // Chip editing state (lists, texts, field errors) mirrors into the UI
        // state; the controller owns all mutation semantics.
        viewModelScope.launch {
            fields.state.collect { fs ->
                _uiState.value = _uiState.value.copy(
                    to = fs.to,
                    cc = fs.cc,
                    bcc = fs.bcc,
                    fieldTexts = fs.fieldTexts,
                    activeField = fs.activeField,
                    error = fs.error
                )
            }
        }
        pgpLock?.let { lock ->
            // PGP lock sees exactly one address or none: encryption targets a
            // single WKD key, so a multi-recipient send can never lock.
            fields.onListsChanged = { lock.onRecipientChange(fields.value.soleRecipientAddress) }
            viewModelScope.launch {
                lock.state.collect { s -> _uiState.value = _uiState.value.copy(pgp = s) }
            }
        }
        loadEmail()
    }

    private fun loadEmail() {
        viewModelScope.launch {
            val email = emailUseCase.getEmailById(emailId)
            _uiState.value = _uiState.value.copy(
                email = email,
                // Reply in kind (NIP-EMAIL `format` tag): match the anchor.
                format = email?.bodyFormat ?: EmailBodyFormat.HTML
            )
            // Answering an encrypted message: the correspondent clearly has
            // PGP — default the lock ON (ANDROID_PGP.md §4.4).
            pgpLock?.setAnchorWasPgp(email?.isPgpEncrypted == true)

            // Recipient prefill (plain reply vs reply-all, RFC 5322 §3.6.2).
            val prefill = emailUseCase.computeReplyRecipients(emailId, replyAll)
            if (prefill == null) {
                fields.setError("No external recipient on this thread")
            } else {
                prefill.to.forEach { fields.addRecipient(RecipientField.TO, it) }
                prefill.cc.forEach { fields.addRecipient(RecipientField.CC, it) }
                if (prefill.cc.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(showCcBcc = true)
                }
            }
        }
    }

    fun onFieldFocusChanged(field: RecipientField, focused: Boolean) {
        fields.onFieldFocusChanged(field, focused)
    }

    fun onFieldTextChange(field: RecipientField, value: String) {
        fields.onFieldTextChange(field, value)
    }

    fun removeRecipient(field: RecipientField, recipient: EmailRecipient) {
        fields.removeRecipient(field, recipient)
    }

    fun toggleCcBcc() {
        _uiState.value = _uiState.value.copy(showCcBcc = !_uiState.value.showCcBcc)
    }

    fun onReplyTextChange(text: String) {
        _uiState.value = _uiState.value.copy(replyText = text)
        fields.clearError()
    }

    /**
     * Plain-text "add a link": the URL lands at the end of the draft (the
     * plain editor holds a String, so there is no cursor to target).
     */
    fun insertPlainText(text: String) {
        if (text.isBlank()) return
        val current = _uiState.value.replyText
        val joined = if (current.isBlank()) text else current + "\n" + text
        _uiState.value = _uiState.value.copy(replyText = joined, error = null)
    }

    /** Switch body format, converting the current body so no typing is lost. */
    fun onFormatChange(format: EmailBodyFormat) {
        val state = _uiState.value
        if (state.format == format) return
        val converted = if (format == EmailBodyFormat.PLAIN) {
            htmlToPlain(state.replyText)
        } else {
            EmailBridgeTags.plainToHtml(state.replyText)
        }
        _uiState.value = state.copy(format = format, replyText = converted, error = null)
    }

    fun sendReply() {
        val state = _uiState.value
        val text = state.replyText.trim()
        if (text.isBlank() || state.isSending) return

        // Any typed-but-uncommitted text joins its field before validating.
        fields.commitFieldText(fields.value.activeField, onlyIfValid = false)
        if (!fields.validateForSend()) return
        val fieldState = fields.value

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            val result = emailUseCase.sendReply(
                emailId = emailId,
                replyText = text,
                format = state.format,
                pgp = pgpLock?.shouldEncrypt() == true && fieldState.singleRecipient,
                to = fieldState.to,
                cc = fieldState.cc,
                bcc = fieldState.bcc
            )
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isSending = false, sent = true)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isSending = false)
                    fields.setError("Failed to send: ${e.message}")
                }
            )
        }
    }

    private fun htmlToPlain(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("(?i)<li[^>]*>"), "• ")
        .replace(Regex("(?i)</li>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}
