package xyz.desent.presentation.ui.email.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.desent.data.EmailBridgeTags
import xyz.desent.data.pgp.PgpFeatureGate
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.model.EmailBodyFormat
import xyz.desent.domain.model.EmailRecipient
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.PgpKeyRepository
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Which recipient line the user is editing (chips + typed text + suggestions). */
enum class RecipientField { TO, CC, BCC }

data class EmailComposeUiState(
    /** Committed chips per line — the full RFC 5322 recipient lists (END-01 §3.4). */
    val to: List<EmailRecipient> = emptyList(),
    val cc: List<EmailRecipient> = emptyList(),
    val bcc: List<EmailRecipient> = emptyList(),
    /** Uncommitted typed text per field (survives switching lines). */
    val fieldTexts: Map<RecipientField, String> = emptyMap(),
    val activeField: RecipientField = RecipientField.TO,
    /** Cc/Bcc rows revealed (hidden until the toggle is tapped). */
    val showCcBcc: Boolean = false,
    val subject: String = "",
    /** Body in [format]: HTML from the rich editor, or plain text. */
    val body: String = "",
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

/**
 * One autocomplete row in the recipient-field dropdown: a single emailable
 * address of a contact (row-per-address — a two-slot contact yields two rows).
 */
data class RecipientSuggestion(
    val contact: PrivateContact,
    val email: String,
    /** Email slot label; non-empty on the row only when the contact has 2+ addresses. */
    val label: String,
    val displayName: String,
    val pictureUrl: String?
)

class EmailComposeViewModel(
    private val emailUseCase: EmailUseCase,
    /** Optional recipient prefill (contacts detail Compose action). */
    prefillRecipient: String? = null,
    pgpKeyRepository: PgpKeyRepository? = null,
    pgpFeatureGate: PgpFeatureGate? = null,
    autoEncryptFlow: Flow<Boolean>? = null,
    /** Contacts address book; null (tests) leaves the suggestion pipeline inert. */
    private val privateStorageUseCase: PrivateStorageUseCase? = null,
    /** Room-only profile observer backing suggestion avatars; null disables pictures. */
    private val contactProfileResolver: ContactProfileResolver? = null
) : ViewModel() {

    /** To/Cc/Bcc chip editing — shared with the reply surface. */
    private val fields = RecipientFieldsController(
        initialTo = prefillRecipient?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { listOf(EmailRecipient(it)) }.orEmpty()
    )

    private val _uiState = MutableStateFlow(
        EmailComposeUiState(to = fields.value.to)
    )
    val uiState: StateFlow<EmailComposeUiState> = _uiState.asStateFlow()

    /** PGP compose lock (ANDROID_PGP.md §4.2); null in test constructions. */
    val pgpLock: PgpComposeLock? = pgpKeyRepository?.let { repo ->
        pgpFeatureGate?.let { gate ->
            PgpComposeLock(viewModelScope, repo, gate, autoEncryptFlow)
        }
    }

    /** Address book snapshot (Room cache only — composing never opens relay work). */
    private val contacts = MutableStateFlow<List<PrivateContact>>(emptyList())

    /** Recently emailed addresses, most recent first (ranking signal). */
    private val recentAddresses = MutableStateFlow<List<String>>(emptyList())

    /**
     * Room-cached profiles keyed like ContactsViewModel: hex pubkey, or
     * `email:<identifier>` for email-only contacts. Values may be null
     * (negative entries) — same semantics as the contacts screen.
     */
    private val profiles = MutableStateFlow<Map<String, ContactProfile?>>(emptyMap())

    private val observedProfileKeys = ConcurrentHashMap.newKeySet<String>()

    /** Whether the dropdown may show; armed by focus/typing, disarmed by selection/dismissal/send. */
    private val suggestionsActive = MutableStateFlow(false)

    /**
     * Raw recipient text as typed in the ACTIVE field. Kept separate from
     * [EmailComposeUiState] so keystrokes only touch this cheap flow — the
     * suggestion recompute (name/email/npub matching) runs on the debounced
     * shadow below, never synchronously on each keypress.
     */
    private val recipientQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    private val debouncedQuery = recipientQuery.debounce(QUERY_DEBOUNCE_MS)

    /** Live recipient-field suggestions: contacts filtered by the typed text, recency-ranked, capped. */
    val suggestions: StateFlow<List<RecipientSuggestion>> = combine(
        debouncedQuery, contacts, recentAddresses, profiles, suggestionsActive
    ) { query, contacts, recent, profiles, active ->
        if (!active) {
            emptyList()
        } else {
            buildSuggestions(query, contacts, recent, profiles)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Chip editing state (lists, texts, field errors) mirrors into the UI
        // state; the controller owns all mutation semantics — including the
        // error slot, so field errors and send failures share one pipeline.
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
        fields.onQueryChanged = { query ->
            recipientQuery.value = query
            suggestionsActive.value = true
        }
        pgpLock?.let { lock ->
            // PGP lock sees exactly one address or none: encryption targets a
            // single WKD key, so a multi-recipient send can never lock.
            fields.onListsChanged = { lock.onRecipientChange(fields.value.soleRecipientAddress) }
            lock.onRecipientChange(fields.value.soleRecipientAddress)
            viewModelScope.launch {
                lock.state.collect { s -> _uiState.value = _uiState.value.copy(pgp = s) }
            }
        }
        privateStorageUseCase?.let { storage ->
            viewModelScope.launch {
                val npub = storage.activeOwnerNpub() ?: return@launch
                launch {
                    storage.observeContacts(npub).collect { contacts.value = it }
                }
                launch {
                    emailUseCase.observeRecentOutboundRecipients(npub, RECENT_ADDRESS_LIMIT)
                        .collect { recent -> recentAddresses.value = recent.map { it.address } }
                }
                contacts.collect { list -> list.forEach { observeProfileFor(it) } }
            }
        }
    }

    fun onFieldFocusChanged(field: RecipientField, focused: Boolean) {
        fields.onFieldFocusChanged(field, focused)
        if (!focused && field == fields.value.activeField) suggestionsActive.value = false
        if (focused) suggestionsActive.value = true
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

    fun dismissSuggestions() {
        suggestionsActive.value = false
    }

    fun onSuggestionSelected(suggestion: RecipientSuggestion) {
        fields.addRecipient(
            field = fields.value.activeField,
            recipient = EmailRecipient(
                address = suggestion.email.trim().lowercase(),
                displayName = suggestion.displayName
                    .takeIf { it.isNotBlank() && !it.equals(suggestion.email, ignoreCase = true) }
            )
        )
        suggestionsActive.value = false
    }

    fun onSubjectChange(value: String) {
        _uiState.value = _uiState.value.copy(subject = value, error = null)
        fields.clearError()
    }

    fun onBodyChange(value: String) {
        _uiState.value = _uiState.value.copy(body = value, error = null)
        fields.clearError()
    }

    /**
     * Plain-text "add a link": the URL lands at the end of the draft (the
     * plain editor holds a String, so there is no cursor to target).
     */
    fun insertPlainText(text: String) {
        if (text.isBlank()) return
        val current = _uiState.value.body
        val joined = if (current.isBlank()) text else current + "\n" + text
        _uiState.value = _uiState.value.copy(body = joined, error = null)
    }

    /**
     * Switch the body format, converting the current body so nothing the user
     * typed is lost (rich markup survives a round-trip through plain mode as
     * visible text).
     */
    fun onFormatChange(format: EmailBodyFormat) {
        val state = _uiState.value
        if (state.format == format) return
        val converted = if (format == EmailBodyFormat.PLAIN) {
            htmlToPlain(state.body)
        } else {
            EmailBridgeTags.plainToHtml(state.body)
        }
        _uiState.value = state.copy(format = format, body = converted, error = null)
    }

    fun send() {
        val state = _uiState.value
        if (state.isSending) return
        suggestionsActive.value = false

        // Any typed-but-uncommitted text joins its field before validating.
        fields.commitFieldText(fields.value.activeField, onlyIfValid = false)
        val subject = state.subject.trim()
        val body = state.body
        val fieldState = fields.value
        if (subject.isEmpty()) {
            fields.setError("Subject is required")
            return
        }
        if (body.isBlank()) {
            fields.setError("Body is empty")
            return
        }
        if (!fields.validateForSend()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            val result = emailUseCase.sendColdEmail(
                to = fieldState.to,
                subject = subject,
                body = body,
                format = state.format,
                pgp = pgpLock?.shouldEncrypt() == true && fieldState.singleRecipient,
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

    /**
     * Expand contacts to one row per emailable address, filter by the typed
     * text, rank (recently emailed first, then named-first alphabetical) and
     * cap at [MAX_SUGGESTIONS]. Pubkey-only contacts are skipped — a cold
     * send needs an address. Duplicate addresses across contacts keep the
     * better-ranked row.
     */
    private fun buildSuggestions(
        query: String,
        contacts: List<PrivateContact>,
        recent: List<String>,
        profiles: Map<String, ContactProfile?>
    ): List<RecipientSuggestion> {
        val q = query.trim().lowercase(Locale.getDefault())
        val rank = recent.withIndex().associate { (index, address) -> address to index }
        val seen = HashSet<String>()
        data class Row(val contact: PrivateContact, val email: String, val label: String)

        return contacts.asSequence()
            .filter { contact -> contact.allEmails().isNotEmpty() }
            .filter { contact -> q.isEmpty() || matchesQuery(contact, q) }
            .flatMap { contact ->
                contact.emails.mapNotNull { slot ->
                    val email = slot.value.trim()
                    if (email.isEmpty()) null else Row(contact, email, slot.label)
                }.asSequence()
            }
            .filter { row -> seen.add(row.email.lowercase(Locale.getDefault())) }
            .sortedWith(
                compareBy(
                    { row -> rank[row.email.lowercase(Locale.getDefault())] ?: Int.MAX_VALUE },
                    { row -> row.contact.name.isBlank() },
                    { row -> row.contact.name.lowercase(Locale.getDefault()) }
                )
            )
            .take(MAX_SUGGESTIONS)
            .map { row ->
                val profile = profileFor(row.contact, profiles)
                RecipientSuggestion(
                    contact = row.contact,
                    email = row.email,
                    label = row.label,
                    displayName = displayNameOf(row.contact, profile, row.email),
                    pictureUrl = profile?.picture
                )
            }
            .toList()
    }

    /** Substring match scoped to autocomplete-relevant fields (name, emails, npub). */
    private fun matchesQuery(contact: PrivateContact, q: String): Boolean {
        if (contact.name.lowercase(Locale.getDefault()).contains(q)) return true
        if (contact.allEmails().any { it.lowercase(Locale.getDefault()).contains(q) }) return true
        val npub = contact.npubOrNull()?.lowercase(Locale.getDefault()) ?: return false
        return npub.contains(q)
    }

    /** Same lookup chain as the contacts screen (pubkey link first, then email link). */
    private fun profileFor(
        contact: PrivateContact,
        profiles: Map<String, ContactProfile?>
    ): ContactProfile? = contact.pubkey?.trim()?.lowercase(Locale.getDefault())
        ?.let { profiles[it] }
        ?: contact.allEmails().firstNotNullOfOrNull { profiles["email:${it.lowercase(Locale.getDefault())}"] }

    private fun displayNameOf(contact: PrivateContact, profile: ContactProfile?, email: String): String =
        contact.name.ifBlank {
            profile?.displayName?.takeIf { it.isNotBlank() }
                ?: profile?.name?.takeIf { it.isNotBlank() }
                ?: email
        }

    /**
     * Observe the Room-cached profile for a contact (no network — the relay
     * enrichment pipeline stays owned by the contacts screen).
     */
    private fun observeProfileFor(contact: PrivateContact) {
        val resolver = contactProfileResolver ?: return
        val hex = contact.pubkey?.trim()?.lowercase(Locale.getDefault())
        if (hex != null) {
            observeProfileKey(hex) { resolver.observeProfile(hex) }
        } else {
            contact.allEmails().forEach { email ->
                val identifier = email.lowercase(Locale.getDefault())
                observeProfileKey("email:$identifier") { resolver.observeProfileByIdentifier(identifier) }
            }
        }
    }

    private fun observeProfileKey(key: String, flowProvider: () -> Flow<ContactProfile?>) {
        if (!observedProfileKeys.add(key)) return
        viewModelScope.launch {
            flowProvider().collect { profile -> profiles.value = profiles.value + (key to profile) }
        }
    }

    private companion object {
        /** Dropdown rows shown at once (agreed UX: a short list of 3). */
        const val MAX_SUGGESTIONS = 3

        /** Recency ranking window — addresses from the last 50 outbound sends.
         *  Note: recency credits the first To addr-spec of each send (the
         *  legacy single-address column); cc/bcc do not feed autocomplete. */
        const val RECENT_ADDRESS_LIMIT = 50

        /** Pause before the typed text re-filters suggestions (async check, no per-keystroke recompute). */
        const val QUERY_DEBOUNCE_MS = 150L
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
