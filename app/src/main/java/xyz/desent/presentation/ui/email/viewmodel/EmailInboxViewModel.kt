package xyz.desent.presentation.ui.email.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.MailFolder
import xyz.desent.domain.model.MailStateEntry
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.MailFolderRepository
import xyz.desent.domain.repository.SpamFilterRepository
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.FanoutUseCase
import xyz.desent.domain.usecase.TrainSpamUseCase
import xyz.desent.presentation.ui.email.components.MailFolderRow
import xyz.desent.presentation.ui.email.components.withPathsSorted

data class EmailInboxUiState(
    val threads: List<Email> = emptyList(),
    val unreadCount: Int = 0,
    val spamCount: Int = 0,
    val isLoading: Boolean = true,
    val filter: EmailFilter = EmailFilter.ALL,
    val searchQuery: String = "",
    val lastSyncAt: Long = 0L,
    val error: String? = null,
    val toast: String? = null,
    /** Folder rows for the chip bar (empty = bar hidden). */
    val folders: List<MailFolderRow> = emptyList(),
    /** Selected folder view; null = All mail (labels: filed mail stays here too). */
    val selectedFolderId: String? = null,
    /** Alias rows for the folders-view "Filter by alias" section. */
    val aliases: List<AliasRow> = emptyList(),
    /** Unread mail delivered to the primary address (no alias tag). */
    val primaryUnread: Int = 0,
    val aliasesLoading: Boolean = false,
    val aliasesError: String? = null,
    /** Active alias view filter; [AliasFilter.None] = every address. */
    val aliasFilter: AliasFilter = AliasFilter.None,
    /** Ids of thread rows selected for bulk actions (empty = no selection mode). */
    val selectedIds: Set<String> = emptySet(),
    /**
     * §6.2 per-thread mirror verdicts: true = the wrap reached every mirror
     * relay. Absent ids have no data (30-day sweep / pre-entitlement) and
     * are NEVER rendered as failure.
     */
    val mirrored: Map<String, Boolean> = emptyMap()
)

enum class EmailFilter {
    ALL, UNREAD, ARCHIVED, SPAM
}

/**
 * Alias view filter for the inbox folders dropdown. Combines (AND) with the
 * selected folder and the All/Archived/Spam filter.
 */
sealed interface AliasFilter {
    /** No alias filter — mail via any address. */
    object None : AliasFilter

    /** Mail delivered to the primary address (alias tag absent). */
    object Primary : AliasFilter

    /** Mail delivered to a specific alias address. */
    data class Alias(val email: String) : AliasFilter
}

/** Alias menu row with the unread badge for the folders dropdown. */
data class AliasRow(
    val alias: Alias,
    val unread: Int
)

class EmailInboxViewModel(
    private val emailUseCase: EmailUseCase,
    private val preferencesManager: PreferencesManager,
    private val trainSpamUseCase: TrainSpamUseCase,
    private val spamFilterRepository: SpamFilterRepository,
    contactProfileResolver: ContactProfileResolver,
    faviconResolver: xyz.desent.data.avatar.FaviconResolver,
    private val mailFolderRepository: MailFolderRepository? = null,
    private val aliasUseCase: AliasUseCase? = null,
    /** §6.2 mirror-status source (optional: tests / surfaces without mirroring). */
    private val fanoutUseCase: FanoutUseCase? = null,
    securityConfigRepository: xyz.desent.domain.repository.SecurityConfigRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailInboxUiState())
    val uiState: StateFlow<EmailInboxUiState> = _uiState

    /** Sender avatars for the visible threads (inbox and spam list). */
    val senderProfiles = SenderProfileStore(contactProfileResolver, faviconResolver, viewModelScope)

    // Filter/search/folder selection are combine sources so changing them
    // re-emits immediately.
    private val filter = MutableStateFlow(EmailFilter.ALL)
    private val searchQuery = MutableStateFlow("")
    private val selectedFolderId = MutableStateFlow<String?>(null)
    private val aliasFilter = MutableStateFlow<AliasFilter>(AliasFilter.None)
    private val aliasRows = MutableStateFlow<List<AliasRow>>(emptyList())

    private var currentUserNpub: String? = null

    /** §6.2: the 30079 dm_fanout opt-in gates the mirrored-indicator fetch. */
    private var fanoutOn: Boolean = false

    /** §6.2 session cache — one events-status verdict per wrap id. */
    private val mirrorStatusCache = HashMap<String, Boolean>()
    private var mirrorFetchInFlight: Boolean = false

    init {
        viewModelScope.launch {
            currentUserNpub = preferencesManager.npubKey.firstOrNull()
            // Flip stale PENDING sends to TIMED_OUT before the list settles,
            // so the Outbox never shows a dead send as "Sending…".
            currentUserNpub?.let { emailUseCase.reconcileOutbox(it) }
            loadThreads()
        }
        spamFilterRepository.observeLastSyncAt()
            .onEach { _uiState.value = _uiState.value.copy(lastSyncAt = it) }
            .launchIn(viewModelScope)
        loadAliases()
        if (securityConfigRepository != null) {
            viewModelScope.launch {
                val npub = preferencesManager.npubKey.firstOrNull() ?: return@launch
                securityConfigRepository.observe(npub).collect { config: xyz.desent.domain.model.SecurityConfig? ->
                    val nowOn = config?.dmFanout == true
                    if (nowOn != fanoutOn) {
                        fanoutOn = nowOn
                        if (nowOn) refreshMirrorStatus(_uiState.value.threads)
                    }
                }
            }
        }
    }

    /** Aliases are a server-side resource (no local cache); one fetch + retry. */
    private fun loadAliases() {
        val useCase = aliasUseCase ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(aliasesLoading = true, aliasesError = null)
            useCase.listAliases()
                .onSuccess { (list, _) ->
                    aliasRows.value = list
                        .sortedBy { it.localPart.lowercase() }
                        .map { AliasRow(it, 0) }
                    _uiState.value = _uiState.value.copy(aliasesLoading = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        aliasesLoading = false,
                        aliasesError = it.message ?: "Unknown error"
                    )
                }
        }
    }

    /** Retry the alias fetch (folders-dropdown error row). */
    fun refreshAliases() {
        if (!_uiState.value.aliasesLoading) loadAliases()
    }

    private fun loadThreads() {
        val npub = currentUserNpub ?: return

        val folderFlow = mailFolderRepository?.observeFolders(npub)
            ?: kotlinx.coroutines.flow.flowOf(emptyList<MailFolder>())
        val stateFlow = mailFolderRepository?.observeState(npub)
            ?: kotlinx.coroutines.flow.flowOf(emptyMap<String, MailStateEntry>())

        combine(
            emailUseCase.observeThreads(npub),
            spamFilterRepository.observeSpamThreads(npub),
            emailUseCase.observeUnreadCount(npub),
            emailUseCase.observeEmails(npub),
            combine(
                folderFlow,
                stateFlow,
                filter,
                searchQuery,
                selectedFolderId,
                aliasFilter,
                aliasRows
            ) { arr -> arr }
        ) { threads, spamThreads, unreadCount, allEmails, extras ->
            @Suppress("UNCHECKED_CAST")
            val folders = extras[0] as List<MailFolder>
            @Suppress("UNCHECKED_CAST")
            val overlay = extras[1] as Map<String, MailStateEntry>
            val selectedFilter = extras[2] as EmailFilter
            val query = extras[3] as String
            val folder = extras[4] as String?
            val selectedAliasFilter = extras[5] as AliasFilter
            @Suppress("UNCHECKED_CAST")
            val loadedAliases = extras[6] as List<AliasRow>

            // Folder rows with unread/total badges from the joined overlay.
            val folderRows = folders.withPathsSorted().map { row ->
                val filed = allEmails.filter { overlay[it.folderKey]?.f == row.folder.id }
                row.copy(unread = filed.count { !it.isRead }, total = filed.size)
            }

            // Alias rows with unread badges (primary = alias tag absent).
            val unreadByAlias = allEmails.filter { !it.isRead }.groupBy { it.alias }
            val aliasRowsWithBadges = loadedAliases.map { row ->
                row.copy(unread = unreadByAlias[row.alias.email]?.size ?: 0)
            }
            val primaryUnread = unreadByAlias[null]?.size ?: 0

            // Folder selected → flat saved view of filed messages (labels
            // model: they also remain in All mail). Otherwise the thread list.
            val baseList = if (folder != null) {
                allEmails.filter { overlay[it.folderKey]?.f == folder }
            } else {
                if (selectedFilter == EmailFilter.SPAM) spamThreads else threads
            }

            // Alias view filter combines (AND) with folder and All/Spam views.
            val aliasFilteredList = when (selectedAliasFilter) {
                AliasFilter.None -> baseList
                AliasFilter.Primary -> baseList.filter { it.alias == null }
                is AliasFilter.Alias -> baseList.filter { it.alias == selectedAliasFilter.email }
            }

            _uiState.value.copy(
                threads = filterThreads(
                    aliasFilteredList,
                    if (folder != null) EmailFilter.ALL else selectedFilter,
                    query
                ),
                unreadCount = unreadCount,
                spamCount = spamThreads.size,
                isLoading = false,
                filter = selectedFilter,
                searchQuery = query,
                folders = folderRows,
                selectedFolderId = folder,
                aliases = aliasRowsWithBadges,
                primaryUnread = primaryUnread,
                aliasFilter = selectedAliasFilter
            )
        }.onEach { emitted ->
            emitted.threads.forEach { senderProfiles.ensure(it) }
            // Keep the selection pinned to the visible list: ids that fell
            // out (deleted, moved, or replaced by a newer thread
            // representative) are dropped so the count never goes stale.
            val visibleIds = emitted.threads.mapTo(HashSet()) { it.id }
            val state = if (emitted.selectedIds.all(visibleIds::contains)) {
                emitted
            } else {
                emitted.copy(
                    selectedIds = emitted.selectedIds.filterTo(HashSet()) { it in visibleIds }
                )
            }
            _uiState.value = state
            // §6.2: batch the visible page's wrap ids for mirror status.
            refreshMirrorStatus(state.threads)
        }.launchIn(viewModelScope)
    }

    /**
     * §6.2 per-message "did it reach my relays?": one events-status batch
     * per visible list page (≤ 200 ids), cached per session. Absent ids
     * stay badge-less — never rendered as failure.
     */
    private fun refreshMirrorStatus(threads: List<Email>) {
        val useCase = fanoutUseCase ?: return
        if (!fanoutOn || mirrorFetchInFlight) return
        val pageIds = threads.take(200).map { it.id }
        val uncached = pageIds.filter { it !in mirrorStatusCache }
        if (uncached.isEmpty()) {
            publishMirrorState(pageIds)
            return
        }
        mirrorFetchInFlight = true
        viewModelScope.launch {
            try {
                useCase.eventsStatus(uncached).onSuccess { statuses ->
                    pageIds.forEach { id ->
                        statuses.isMirrored(id)?.let { mirrorStatusCache[id] = it }
                    }
                    publishMirrorState(pageIds)
                }
            } finally {
                mirrorFetchInFlight = false
            }
        }
    }

    private fun publishMirrorState(pageIds: List<String>) {
        val current = _uiState.value.mirrored
        val updated = pageIds.filter { mirrorStatusCache.containsKey(it) }
            .associateWith { mirrorStatusCache.getValue(it) }
        if (updated != current) {
            _uiState.value = _uiState.value.copy(mirrored = updated)
        }
    }

    fun onFilterChange(filter: EmailFilter) {
        clearSelection()
        this.filter.value = filter
    }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onFolderSelected(folderId: String?) {
        clearSelection()
        selectedFolderId.value = folderId
    }

    fun onAliasFilterSelected(filter: AliasFilter) {
        clearSelection()
        aliasFilter.value = filter
    }

    /**
     * Two-stage "All" chip: while any view filter is active (folder, alias,
     * Archived) the first tap resets to All mail; the next tap opens the menu.
     */
    fun resetViewToAll() {
        clearSelection()
        selectedFolderId.value = null
        aliasFilter.value = AliasFilter.None
        filter.value = EmailFilter.ALL
    }

    /** Create a root folder; the manifest republish + sync happen in the repository. */
    fun createFolder(name: String) {
        val npub = currentUserNpub ?: return
        val repo = mailFolderRepository ?: return
        viewModelScope.launch {
            val result = repo.createFolder(npub, name)
            _uiState.value = _uiState.value.copy(
                toast = result.fold(
                    onSuccess = { "Folder \"${it.name}\" created" },
                    onFailure = { "Create failed: ${it.message}" }
                )
            )
        }
    }

    fun renameFolder(id: String, newName: String) {
        val npub = currentUserNpub ?: return
        val repo = mailFolderRepository ?: return
        viewModelScope.launch {
            val result = repo.renameFolder(npub, id, newName)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "Folder renamed" else "Rename failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    fun deleteFolder(id: String) {
        val npub = currentUserNpub ?: return
        val repo = mailFolderRepository ?: return
        viewModelScope.launch {
            val result = repo.deleteFolder(npub, id)
            if (selectedFolderId.value == id) selectedFolderId.value = null
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "Folder deleted" else "Delete failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    /** File one message (labels model: it stays in All mail too). */
    fun moveToFolder(email: Email, folderId: String?) {
        val npub = currentUserNpub ?: return
        val repo = mailFolderRepository ?: return
        viewModelScope.launch {
            val result = repo.moveToFolder(npub, email.folderKey, folderId)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) {
                    if (folderId == null) "Removed from folder" else "Moved to folder"
                } else {
                    "Move failed: ${result.exceptionOrNull()?.message}"
                }
            )
        }
    }

    private fun filterThreads(
        threads: List<Email>,
        filter: EmailFilter,
        query: String
    ): List<Email> {
        var filtered = threads

        when (filter) {
            EmailFilter.UNREAD -> filtered = filtered.filter { !it.isRead }
            EmailFilter.ARCHIVED -> filtered = filtered.filter { it.deletionRequested }
            EmailFilter.SPAM, EmailFilter.ALL -> {}
        }

        if (query.isNotEmpty()) {
            val lower = query.lowercase()
            filtered = filtered.filter {
                it.senderEmail.lowercase().contains(lower) ||
                    it.senderName?.lowercase()?.contains(lower) == true ||
                    it.subject.lowercase().contains(lower) ||
                    it.content.lowercase().contains(lower)
            }
        }

        return filtered
    }

    fun markThreadRead(threadKey: String) {
        val npub = currentUserNpub ?: return
        viewModelScope.launch {
            emailUseCase.markThreadRead(npub, threadKey)
        }
    }

    fun toggleRead(email: Email) {
        viewModelScope.launch {
            if (email.isRead) {
                emailUseCase.markAsUnread(email.id)
                _uiState.value = _uiState.value.copy(toast = "Marked as unread")
            } else {
                emailUseCase.markAsRead(email.id)
                _uiState.value = _uiState.value.copy(toast = "Marked as read")
            }
        }
    }

    fun deleteEmail(emailId: String) {
        viewModelScope.launch {
            val result = emailUseCase.requestDeletion(emailId)
            _uiState.value = _uiState.value.copy(
                toast = if (result.isSuccess) "Message deleted" else "Delete failed: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    /** Mark a thread's latest message as spam and train the Bayesian classifier. */
    fun markAsSpam(email: Email) {
        viewModelScope.launch {
            trainSpamUseCase(email, isSpam = true)
            _uiState.value = _uiState.value.copy(toast = "Moved to Spam")
        }
    }

    /** "Not spam" — trains the classifier and moves the message back to the inbox. */
    fun markNotSpam(email: Email) {
        viewModelScope.launch {
            trainSpamUseCase(email, isSpam = false)
            _uiState.value = _uiState.value.copy(toast = "Moved to inbox")
        }
    }

    // ==================== Selection mode ====================

    /** Enter selection mode with [email] as the first selected row. */
    fun enterSelection(email: Email) {
        _uiState.value = _uiState.value.copy(selectedIds = setOf(email.id))
    }

    /** Add/remove [email] from the selection; an empty result exits selection mode. */
    fun toggleSelection(email: Email) {
        val current = _uiState.value.selectedIds
        val next = if (email.id in current) current - email.id else current + email.id
        _uiState.value = _uiState.value.copy(selectedIds = next)
    }

    fun clearSelection() {
        if (_uiState.value.selectedIds.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(selectedIds = emptySet())
        }
    }

    /** Select every row currently visible (after filter/search). */
    fun selectAllVisible() {
        val next = _uiState.value.threads.mapTo(HashSet()) { it.id }
        _uiState.value = _uiState.value.copy(selectedIds = next)
    }

    /** The thread rows backing the current selection. */
    private fun selectedEmails(): List<Email> {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty()) return emptyList()
        return _uiState.value.threads.filter { it.id in ids }
    }

    /** Permanently delete every selected message (authenticated HTTP delete per id). */
    fun deleteSelected() {
        val emails = selectedEmails()
        if (emails.isEmpty()) return
        viewModelScope.launch {
            var failures = 0
            emails.forEach { email ->
                if (emailUseCase.requestDeletion(email.id).isFailure) failures++
            }
            clearSelection()
            _uiState.value = _uiState.value.copy(
                toast = if (failures == 0) {
                    "Deleted ${messageCount(emails.size)}"
                } else {
                    "Delete failed for $failures of ${emails.size}"
                }
            )
        }
    }

    /** File every selected message into [folderId] (null = remove from folder). */
    fun moveSelectedToFolder(folderId: String?) {
        val npub = currentUserNpub ?: return
        val repo = mailFolderRepository ?: return
        val emails = selectedEmails()
        if (emails.isEmpty()) return
        viewModelScope.launch {
            var failures = 0
            emails.forEach { email ->
                if (repo.moveToFolder(npub, email.folderKey, folderId).isFailure) failures++
            }
            clearSelection()
            _uiState.value = _uiState.value.copy(
                toast = when {
                    failures > 0 -> "Move failed for $failures of ${emails.size}"
                    folderId == null -> "Removed ${messageCount(emails.size)} from folder"
                    else -> "Moved ${messageCount(emails.size)} to folder"
                }
            )
        }
    }

    /** Train the classifier and quarantine every selected message. */
    fun markSelectedSpam() {
        val emails = selectedEmails()
        if (emails.isEmpty()) return
        viewModelScope.launch {
            emails.forEach { trainSpamUseCase(it, isSpam = true) }
            clearSelection()
            _uiState.value = _uiState.value.copy(toast = "Moved ${messageCount(emails.size)} to Spam")
        }
    }

    /** "Not spam" for the whole selection — trains and returns mail to the inbox. */
    fun markSelectedNotSpam() {
        val emails = selectedEmails()
        if (emails.isEmpty()) return
        viewModelScope.launch {
            emails.forEach { trainSpamUseCase(it, isSpam = false) }
            clearSelection()
            _uiState.value = _uiState.value.copy(toast = "Moved ${messageCount(emails.size)} to inbox")
        }
    }

    /** Mark each selected thread fully read (same semantics as tap-to-open). */
    fun markSelectedRead() {
        val npub = currentUserNpub ?: return
        val emails = selectedEmails()
        if (emails.isEmpty()) return
        viewModelScope.launch {
            emails.forEach { emailUseCase.markThreadRead(npub, it.threadKey) }
            clearSelection()
            _uiState.value = _uiState.value.copy(toast = "Marked ${messageCount(emails.size)} as read")
        }
    }

    /** Mark each selected thread's latest message unread (same semantics as the sheet action). */
    fun markSelectedUnread() {
        val emails = selectedEmails()
        if (emails.isEmpty()) return
        viewModelScope.launch {
            emails.forEach { emailUseCase.markAsUnread(it.id) }
            clearSelection()
            _uiState.value = _uiState.value.copy(toast = "Marked ${messageCount(emails.size)} as unread")
        }
    }

    /** Mark every thread currently listed in the inbox read (overflow-menu action). */
    fun markAllRead() {
        val npub = currentUserNpub ?: return
        val threads = _uiState.value.threads
        if (threads.isEmpty()) return
        viewModelScope.launch {
            threads.forEach { emailUseCase.markThreadRead(npub, it.threadKey) }
            _uiState.value = _uiState.value.copy(toast = "Marked ${messageCount(threads.size)} as read")
        }
    }

    private fun messageCount(count: Int): String =
        if (count == 1) "1 message" else "$count messages"

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}
