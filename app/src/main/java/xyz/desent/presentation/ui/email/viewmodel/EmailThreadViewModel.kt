package xyz.desent.presentation.ui.email.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import xyz.desent.data.EmailBridgeTags
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.pgp.PgpFeatureGate
import xyz.desent.data.pgp.PgpMimeParser
import xyz.desent.data.spam.RemoteImagePolicyState
import xyz.desent.data.spam.RemoteImagePolicyStateFactory
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailOutboxEntry
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.PgpKeyRepository
import xyz.desent.domain.usecase.EmailUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

data class EmailThreadUiState(
    val messages: List<Email> = emptyList(),
    /** Outbox delivery state for this thread, keyed by the send's Message-ID. */
    val outbox: Map<String, EmailOutboxEntry> = emptyMap(),
    val replyText: String = "",
    /** Anchor the quick reply / expanded composer targets (inbound preferred). */
    val replyAnchorId: String? = null,
    val isSending: Boolean = false,
    val cooldown: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val toast: String? = null,
    /** PGP lock state for the quick-reply bar; null when no PGP stack (tests). */
    val pgp: PgpComposeLock.State? = null,
    /** Inbound PGP messages successfully decrypted (content swapped in-place). */
    val pgpDecryptedIds: Set<String> = emptySet(),
    /** Inbound PGP messages this device cannot decrypt (locked placeholders). */
    val pgpLockedIds: Set<String> = emptySet()
)

/**
 * Email id used to anchor a reply. Prefers an inbound message so the reply is
 * routed to the external sender (an outbound anchor would send back to the
 * `to` address, a receipt anchor has no address at all) — but any row works:
 * the repository maps whichever anchor it gets onto the outbound constructor.
 */
private fun replyAnchorId(messages: List<Email>, threadKey: String): String? =
    messages.firstOrNull {
        it.threadKey == threadKey && it.direction != EmailDirection.OUTBOUND && it.emailType != EmailType.SYSTEM
    }?.id
        ?: messages.firstOrNull { it.threadKey == threadKey }?.id
        ?: messages.firstOrNull()?.id

class EmailThreadViewModel(
    private val threadKey: String,
    private val emailUseCase: EmailUseCase,
    private val preferencesManager: PreferencesManager,
    imagePolicyFactory: RemoteImagePolicyStateFactory,
    contactProfileResolver: ContactProfileResolver,
    faviconResolver: xyz.desent.data.avatar.FaviconResolver,
    private val pgpKeyRepository: PgpKeyRepository? = null,
    pgpFeatureGate: PgpFeatureGate? = null,
    autoEncryptFlow: Flow<Boolean>? = null,
    private val pgpMimeParser: PgpMimeParser? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailThreadUiState())
    val uiState: StateFlow<EmailThreadUiState> = _uiState.asStateFlow()

    /** Remote-image blocking state for the messages in this thread. */
    val imagePolicy: RemoteImagePolicyState by lazy { imagePolicyFactory.create(viewModelScope) }

    /** Sender avatars for the messages in this thread. */
    val senderProfiles = SenderProfileStore(contactProfileResolver, faviconResolver, viewModelScope)

    /** PGP quick-reply lock; defaults ON when the thread carries PGP mail. */
    val pgpLock: PgpComposeLock? = pgpKeyRepository?.let { repo ->
        pgpFeatureGate?.let { gate ->
            PgpComposeLock(viewModelScope, repo, gate, autoEncryptFlow)
        }
    }

    /** Per-id decryption memo so re-emissions don't redo crypto work. */
    private val decryptCache = ConcurrentHashMap<String, Email?>()

    private var pgpAnchorSeen = false
    private var userNpub: String? = null

    init {
        pgpLock?.let { lock ->
            viewModelScope.launch {
                lock.state.collect { s -> _uiState.value = _uiState.value.copy(pgp = s) }
            }
        }
        viewModelScope.launch {
            userNpub = preferencesManager.npubKey.firstOrNull()
            observeThread()
        }
    }

    private fun observeThread() {
        val npub = userNpub ?: return
        // Sent-bubble footers are driven by the outbox ledger (delivery state
        // of truth) — the old 30 s "assume failure" timer is gone; stale
        // PENDING entries flip to TIMED_OUT via the reconcile sweep instead.
        viewModelScope.launch { emailUseCase.reconcileOutbox(npub) }
        combine(
            emailUseCase.observeThread(npub, threadKey),
            emailUseCase.observeOutboxByThread(npub, threadKey)
        ) { originals, outbox ->
            // Decrypt inbound PGP mail for rendering (in-place content swap;
            // locked messages keep the armor and render placeholders).
            val messages = originals.map { decryptIfPgp(it) }
            _uiState.value.copy(
                messages = messages,
                outbox = outbox.associateBy { it.messageId },
                replyAnchorId = replyAnchorId(messages, threadKey),
                isLoading = false,
                pgpDecryptedIds = originals
                    .filter { it.isPgpEncrypted && decryptCache[it.id] != null }
                    .map { it.id }
                    .toSet(),
                pgpLockedIds = originals
                    .filter {
                        it.isPgpEncrypted && it.direction == EmailDirection.INBOUND && decryptCache[it.id] == null
                    }
                    .map { it.id }
                    .toSet()
            )
        }.onEach { state ->
            state.messages.forEach { senderProfiles.ensure(it) }
            maybeApplyPgpAnchorDefault(state.messages)
            _uiState.value = state
        }.launchIn(viewModelScope)
    }

    /**
     * Swap the decrypted body into an inbound PGP message (rendering +
     * quoting use plaintext; the stored armor is never touched). Own sends
     * already carry their draft. Messages that can't be decrypted keep the
     * armor and surface as locked placeholders instead.
     */
    private suspend fun decryptIfPgp(email: Email): Email {
        if (!email.isPgpEncrypted || email.direction != EmailDirection.INBOUND) return email
        val keyRepo = pgpKeyRepository ?: return email
        decryptCache[email.id]?.let { return it ?: email }
        val decrypted = keyRepo.decryptMessage(email.content).getOrNull()
        val parsed = decrypted?.let { bytes ->
            runCatching {
                pgpMimeParser?.parse(bytes) ?: xyz.desent.domain.model.PgpDecryptedMessage(
                    body = String(bytes, Charsets.UTF_8),
                    isHtml = xyz.desent.crypto.OpenPgpCrypto.sniffHtml(String(bytes, Charsets.UTF_8))
                )
            }.getOrNull()
        }
        val result = parsed?.let {
            // The decrypted copy clears the armor flag — rendering chains
            // treat "isPgpEncrypted && inbound" as "content is armor, never
            // render as body" (the badge state lives in pgpDecryptedIds).
            email.copy(
                content = it.body,
                bodyFormat = if (it.isHtml) EmailBodyFormat.HTML else EmailBodyFormat.PLAIN,
                isPgpEncrypted = false
            )
        }
        decryptCache[email.id] = result
        return result ?: email
    }

    /** One-shot: default the quick-reply lock when PGP mail is present. */
    private fun maybeApplyPgpAnchorDefault(messages: List<Email>) {
        if (pgpAnchorSeen || pgpLock == null) return
        if (messages.any { it.isPgpEncrypted && it.direction == EmailDirection.INBOUND }) {
            pgpAnchorSeen = true
            pgpLock?.setAnchorWasPgp(true)
        }
    }

    fun onReplyTextChange(text: String) {
        _uiState.value = _uiState.value.copy(replyText = text, error = null)
    }

    fun sendReply() {
        val state = _uiState.value
        if (state.isSending || state.cooldown) return
        val text = state.replyText.trim()
        if (text.isBlank()) return

        val messages = state.messages
        val anchorId = replyAnchorId(messages, threadKey)
            ?: run {
                _uiState.value = state.copy(error = "No anchor message for this thread")
                return
            }

        // Reply in kind: the quick-reply bar is plain text; an HTML-format
        // thread gets it converted (escape + line breaks) so the wire format
        // matches the conversation. A plain thread sends it as-is.
        // PGP sends stay plain text (ANDROID_PGP.md §4.2 — the plaintext is
        // what gets encrypted; the relay ignores format for PGP anyway).
        val threadFormat = messages
            .firstOrNull { it.emailType != EmailType.SYSTEM }?.bodyFormat
            ?: EmailBodyFormat.HTML
        val encryptPgp = pgpLock?.shouldEncrypt() == true
        val (body, format) = if (threadFormat == EmailBodyFormat.HTML && !encryptPgp) {
            EmailBridgeTags.plainToHtml(text) to EmailBodyFormat.HTML
        } else {
            text to EmailBodyFormat.PLAIN
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            val result = emailUseCase.sendReply(anchorId, body, format, pgp = encryptPgp)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        replyText = "",
                        cooldown = true
                    )
                    startCooldown()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = "Failed to send: ${e.message}"
                    )
                }
            )
        }
    }

    /** One-tap retry of a failed/timed-out send shown in this thread. */
    fun retrySend(messageId: String) {
        viewModelScope.launch {
            emailUseCase.retrySend(messageId).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(toast = "Sending again…")
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = "Retry failed: ${e.message}")
                }
            )
        }
    }

    private var cooldownJob: Job? = null
    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            delay(COOLDOWN_MS)
            _uiState.value = _uiState.value.copy(cooldown = false)
        }
    }

    fun markRead() {
        val npub = userNpub ?: return
        viewModelScope.launch { emailUseCase.markThreadRead(npub, threadKey) }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    companion object {
        private const val COOLDOWN_MS = 5_000L
    }
}
