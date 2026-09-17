package xyz.desent.presentation.ui.contacts.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.desent.data.contacts.VCardCodec
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.usecase.PrivateStorageUseCase
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A contact paired with its index into the stored list + resolved profile. */
data class DisplayedContact(
    val index: Int,
    val contact: PrivateContact,
    val profile: ContactProfile?
)

data class ContactsUiState(
    val contacts: List<PrivateContact> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val toast: String? = null
)

class ContactsViewModel(
    private val useCase: PrivateStorageUseCase,
    private val profileResolver: ContactProfileResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    /**
     * Enrichment results keyed by hex pubkey (linked contacts) or
     * `email:<identifier>` (email-only contacts resolved via NIP-05).
     * Null values are negative entries.
     */
    private val profiles = MutableStateFlow<Map<String, ContactProfile?>>(emptyMap())

    /** Room observers already collected — one collector per lookup key. */
    private val observedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Sorted + searched render list; stored order is never touched. */
    val display: StateFlow<List<DisplayedContact>> =
        combine(_uiState, profiles) { state, profiles ->
            state.contacts
                .mapIndexed { index, contact ->
                    DisplayedContact(
                        index = index,
                        contact = contact,
                        profile = profileFor(contact, profiles)
                    )
                }
                .filter { it.matches(state.query) }
                .sortedWith(
                    compareBy(
                        { it.contact.name.isBlank() },
                        { it.contact.name.lowercase(Locale.getDefault()) }
                    )
                )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub()
            if (npub == null) {
                _uiState.value = ContactsUiState(isLoading = false)
                return@launch
            }
            useCase.subscribeToOwnPrivateStorage()
            useCase.observeContacts(npub).collect { contacts ->
                // Kick enrichment for every linked key; the resolver's caches
                // dedupe, and nothing here blocks list rendering.
                contacts.forEach { enrich(it) }
                _uiState.update { it.copy(contacts = contacts, isLoading = false) }
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    /**
     * Trigger profile resolution for [pubkeyHex]. Serves the Room cache
     * instantly when fresh; stale/missing rows refresh from the relays in
     * the background — the Room observer in [observeKey] swaps in updates.
     */
    fun ensureProfile(pubkeyHex: String) {
        val hex = pubkeyHex.trim().lowercase()
        viewModelScope.launch {
            val profile = runCatching { profileResolver.resolve(hex) }.getOrNull()
            profiles.update { it + (hex to profile) }
        }
    }

    private fun enrich(contact: PrivateContact) {
        val hex = contact.pubkey?.trim()?.lowercase()
        if (hex != null) {
            ensureProfile(hex)
            observeKey(hex) { profileResolver.observeProfile(hex) }
        } else {
            viewModelScope.launch {
                // Email-only contact: try each address as a NIP-05 identifier,
                // first hit wins (negative results skip to the next slot).
                val winner = contact.allEmails().firstNotNullOfOrNull { email ->
                    runCatching { profileResolver.resolveByIdentifier(email) }.getOrNull()
                        ?.let { email.trim().lowercase() }
                } ?: return@launch
                observeKey("email:$winner") {
                    profileResolver.observeProfileByIdentifier(winner)
                }
            }
        }
    }

    /** Collect the Room-backed profile flow into [profiles] (renders + refresh swaps). */
    private fun observeKey(key: String, flowProvider: () -> Flow<ContactProfile?>) {
        if (!observedKeys.add(key)) return
        viewModelScope.launch {
            flowProvider().collect { profile ->
                profiles.update { it + (key to profile) }
            }
        }
    }

    private fun profileFor(
        contact: PrivateContact,
        profiles: Map<String, ContactProfile?>
    ): ContactProfile? = contact.pubkey?.let { profiles[it] }
        ?: contact.allEmails().firstNotNullOfOrNull { profiles["email:${it.lowercase()}"] }

    /**
     * Insert or replace an entry. `index` addresses the stored list (edit =
     * mutate the existing entry object, preserving its unknown-key extras);
     * null appends. Enforces the v2 save validation (name OR email OR pubkey).
     */
    fun upsertContact(index: Int?, updated: PrivateContact) {
        if (!updated.isSaveValid) {
            _uiState.update { it.copy(toast = "Add a name, email, or nostr key") }
            return
        }
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            val current = _uiState.value.contacts.toMutableList()
            if (index != null && index in current.indices) current[index] = updated else current.add(updated)
            persist(npub, current)
        }
    }

    fun deleteContact(index: Int) {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            val current = _uiState.value.contacts.toMutableList()
            if (index in current.indices) current.removeAt(index) else return@launch
            persist(npub, current)
        }
    }

    /**
     * "Save to contacts" from the message viewer: dedup against every stored
     * email slot (case-insensitive), then append a minimal entry with a
     * `From: <subject>` origin note (ANDROID_CONTACTS.md §4).
     */
    fun saveFromEmail(senderName: String?, senderEmail: String, subject: String?) {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: run {
                _uiState.update { it.copy(toast = "No active account") }
                return@launch
            }
            val email = senderEmail.trim().lowercase()
            if (email.isEmpty()) {
                _uiState.update { it.copy(toast = "No sender address to save") }
                return@launch
            }
            if (_uiState.value.contacts.any { c -> c.allEmails().any { it.equals(email, ignoreCase = true) } }) {
                _uiState.update { it.copy(toast = "Already in contacts") }
                return@launch
            }
            val entry = PrivateContact(
                name = senderName?.trim().orEmpty(),
                emails = listOf(xyz.desent.domain.model.ContactEmailAddress(label = "", value = senderEmail.trim())),
                domain = email.substringAfterLast("@", ""),
                notes = subject?.takeIf { it.isNotBlank() }?.let { "From: $it" }
            )
            persist(npub, _uiState.value.contacts + entry, successToast = "Saved to contacts")
        }
    }

    /** Import a parsed `.vcf` payload; dedup on any-email or pubkey equality. */
    fun importVCard(text: String) {
        viewModelScope.launch {
            val npub = useCase.activeOwnerNpub() ?: return@launch
            val imported = VCardCodec.import(text)
            if (imported.isEmpty()) {
                _uiState.update { it.copy(toast = "No contacts found in file") }
                return@launch
            }
            val existing = _uiState.value.contacts
            val existingEmails = existing.flatMap { it.allEmails().map { e -> e.lowercase() } }.toSet()
            val existingPubkeys = existing.mapNotNull { it.pubkey }.toSet()
            val (dupe, fresh) = imported.partition { entry ->
                entry.allEmails().any { it.lowercase() in existingEmails } ||
                    (entry.pubkey != null && entry.pubkey in existingPubkeys)
            }
            if (fresh.isEmpty()) {
                _uiState.update { it.copy(toast = "All ${dupe.size} contacts already exist") }
                return@launch
            }
            persist(
                npub,
                existing + fresh,
                successToast = "Imported ${fresh.size}" + if (dupe.isNotEmpty()) ", skipped ${dupe.size} duplicates" else ""
            )
        }
    }

    /** Full vCard 3.0 export of the current list (stored order). */
    fun exportVCard(): String = VCardCodec.exportAll(_uiState.value.contacts)

    private suspend fun persist(npub: String, contacts: List<PrivateContact>, successToast: String = "Saved") {
        _uiState.update { it.copy(isSaving = true) }
        val result = useCase.saveContacts(npub, contacts)
        _uiState.update {
            it.copy(
                isSaving = false,
                toast = if (result.isSuccess) successToast
                else "Save failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toast = null) }
    }

    /** Substring search across every v2 field (name, domain, notes, pubkey, all slots). */
    private fun DisplayedContact.matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val haystack = buildList {
            add(contact.name)
            add(contact.domain)
            add(contact.notes.orEmpty())
            add(contact.pubkey.orEmpty())
            contact.pubkey?.let { add(contact.npubOrNull().orEmpty()) }
            contact.emails.forEach { add(it.label); add(it.value) }
            contact.phones.forEach { add(it.label); add(it.value) }
            contact.wallets.forEach { add(it.label); add(it.value); add(it.network) }
            contact.anniversaries.forEach { add(it.label); add(it.date) }
        }
        return haystack.any { it.lowercase().contains(q) }
    }

    companion object {
        /** Label for the next anniversary chip, e.g. `🎂/💍/🔔 Mar 05`. */
        fun nextAnniversaryLabel(contact: PrivateContact): String? {
            val next = contact.nextAnniversary(LocalDate.now()) ?: return null
            val glyph = when (next.kind) {
                xyz.desent.domain.model.AnniversaryKind.BIRTHDAY -> "🎂"
                xyz.desent.domain.model.AnniversaryKind.ANNIVERSARY -> "💍"
                xyz.desent.domain.model.AnniversaryKind.OTHER -> "🔔"
            }
            // Formatter built per call so a locale change mid-session is honored.
            val format = DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault())
            val date = next.parsedDate()?.format(format) ?: return null
            return "$glyph $date"
        }
    }
}
