package xyz.desent.presentation.ui.email.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.data.avatar.FaviconResolver
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.Alias
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.MailFolder
import xyz.desent.domain.model.MailFoldersCodec
import xyz.desent.domain.model.MailStateEntry
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.MailFolderRepository
import xyz.desent.domain.repository.SpamFilterRepository
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.TrainSpamUseCase

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmailInboxViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var emailUseCase: EmailUseCase
    private lateinit var prefs: PreferencesManager
    private lateinit var trainSpamUseCase: TrainSpamUseCase
    private lateinit var spamRepo: SpamFilterRepository
    private lateinit var profileResolver: ContactProfileResolver
    private lateinit var faviconResolver: FaviconResolver

    private val npub = "npub1test"
    private val threads = MutableStateFlow<List<Email>>(emptyList())
    private val spamThreads = MutableStateFlow<List<Email>>(emptyList())
    private val unreadCount = MutableStateFlow(0)
    private val emails = MutableStateFlow<List<Email>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        emailUseCase = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        trainSpamUseCase = mockk(relaxed = true)
        spamRepo = mockk(relaxed = true)
        profileResolver = mockk(relaxed = true)
        faviconResolver = mockk(relaxed = true)

        every { prefs.npubKey } returns flowOf(npub)
        every { emailUseCase.observeThreads(npub) } returns threads
        every { emailUseCase.observeUnreadCount(npub) } returns unreadCount
        every { emailUseCase.observeEmails(npub) } returns emails
        every { spamRepo.observeSpamThreads(npub) } returns spamThreads
        every { spamRepo.observeLastSyncAt() } returns flowOf(0L)
        every { profileResolver.observeProfileByIdentifier(any()) } returns flowOf(null)
        coEvery { profileResolver.resolveByIdentifier(any()) } returns null
        coEvery { faviconResolver.faviconUrlFor(any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeVm(
        mailFolderRepository: MailFolderRepository? = null,
        aliasUseCase: AliasUseCase? = null
    ) =
        EmailInboxViewModel(
            emailUseCase,
            prefs,
            trainSpamUseCase,
            spamRepo,
            profileResolver,
            faviconResolver,
            mailFolderRepository,
            aliasUseCase
        )

    private fun email(
        id: String,
        subject: String = "Subject $id",
        isRead: Boolean = false,
        isSpam: Boolean = false,
        deletionRequested: Boolean = false,
        alias: String? = null,
        senderName: String? = null,
        content: String = "body"
    ) = Email(
        id = id,
        recipientNpub = npub,
        senderEmail = "sender-$id@example.com",
        senderDomain = "example.com",
        senderName = senderName,
        subject = subject,
        content = content,
        dkimStatus = DkimStatus.PASS,
        emailType = EmailType.OTHER,
        bridge = "bridge.example.com",
        messageId = "$id@example.com",
        threadToken = null,
        createdAt = 1_000L,
        isRead = isRead,
        deletionRequested = deletionRequested,
        isSpam = isSpam,
        alias = alias
    )

    private fun alias(localPart: String) = Alias(
        id = localPart.hashCode().toLong(),
        email = "$localPart@desent.xyz",
        localPart = localPart,
        label = null,
        isActive = true,
        createdAt = "2026-01-01T00:00:00Z"
    )

    private fun folder(id: String, name: String) = MailFolder(
        id = id,
        name = name,
        parent = null,
        color = null,
        sort = 0,
        raw = MailFoldersCodec.newEntry(id, name, null)
    )

    @Test
    fun onFilterChange_reemitsFilteredListWithoutDbChange() = runTest(mainDispatcher) {
        threads.value = listOf(email("a", isRead = true), email("b", isRead = false))
        val vm = makeVm()
        assertEquals(2, vm.uiState.value.threads.size)

        vm.onFilterChange(EmailFilter.UNREAD)

        // Regression: the list must update even though no Room emission occurred.
        assertEquals(listOf("b"), vm.uiState.value.threads.map { it.id })
        assertEquals(EmailFilter.UNREAD, vm.uiState.value.filter)
    }

    @Test
    fun archivedFilter_showsOnlyDeletionRequested() = runTest(mainDispatcher) {
        threads.value = listOf(
            email("a"),
            email("b", deletionRequested = true)
        )
        val vm = makeVm()

        vm.onFilterChange(EmailFilter.ARCHIVED)

        assertEquals(listOf("b"), vm.uiState.value.threads.map { it.id })
    }

    @Test
    fun spamFilter_showsQuarantinedThreadsAndCount() = runTest(mainDispatcher) {
        threads.value = listOf(email("inbox"))
        spamThreads.value = listOf(email("spam", isSpam = true), email("spam2", isSpam = true))
        val vm = makeVm()

        vm.onFilterChange(EmailFilter.SPAM)

        assertEquals(listOf("spam", "spam2"), vm.uiState.value.threads.map { it.id })
        assertEquals(2, vm.uiState.value.spamCount)
    }

    @Test
    fun threadsEmission_doesNotClobberSpamCount() = runTest(mainDispatcher) {
        spamThreads.value = listOf(email("spam", isSpam = true))
        val vm = makeVm()
        assertEquals(1, vm.uiState.value.spamCount)

        threads.value = listOf(email("a"), email("b"))

        // Regression: the combine result used to reset spamCount to its default.
        assertEquals(1, vm.uiState.value.spamCount)
    }

    @Test
    fun searchQuery_filtersBySubject() = runTest(mainDispatcher) {
        threads.value = listOf(email("a", subject = "Hello world"), email("b", subject = "Goodbye"))
        val vm = makeVm()

        vm.onSearchQueryChange("hello")

        assertEquals(listOf("a"), vm.uiState.value.threads.map { it.id })
    }

    @Test
    fun markNotSpam_trainsClassifierAsHam() = runTest(mainDispatcher) {
        val vm = makeVm()
        val spam = email("spam", isSpam = true)

        vm.markNotSpam(spam)

        coVerify { trainSpamUseCase(spam, isSpam = false) }
    }

    @Test
    fun aliasFilter_showsOnlyMailForSelectedAlias() = runTest(mainDispatcher) {
        threads.value = listOf(
            email("a", alias = "news@desent.xyz"),
            email("b"),
            email("c", alias = "shop@desent.xyz")
        )
        val vm = makeVm()

        vm.onAliasFilterSelected(AliasFilter.Alias("shop@desent.xyz"))

        assertEquals(listOf("c"), vm.uiState.value.threads.map { it.id })
        assertEquals(AliasFilter.Alias("shop@desent.xyz"), vm.uiState.value.aliasFilter)
    }

    @Test
    fun aliasFilter_primary_showsOnlyMailWithoutAlias() = runTest(mainDispatcher) {
        threads.value = listOf(
            email("a", alias = "news@desent.xyz"),
            email("b")
        )
        val vm = makeVm()

        vm.onAliasFilterSelected(AliasFilter.Primary)

        assertEquals(listOf("b"), vm.uiState.value.threads.map { it.id })
    }

    @Test
    fun aliasFilter_combinesWithSelectedFolder() = runTest(mainDispatcher) {
        val folderRepo = mockk<MailFolderRepository>()
        every { folderRepo.observeFolders(npub) } returns flowOf(listOf(folder("f1", "Receipts")))
        every { folderRepo.observeState(npub) } returns flowOf(
            mapOf(
                "a@example.com" to MailStateEntry(k = "a@example.com", f = "f1", ts = 1L),
                "b@example.com" to MailStateEntry(k = "b@example.com", f = "f1", ts = 1L)
            )
        )
        val filed = listOf(
            email("a", alias = "shop@desent.xyz"),
            email("b", alias = "news@desent.xyz")
        )
        threads.value = filed
        emails.value = filed
        val vm = makeVm(mailFolderRepository = folderRepo)

        vm.onFolderSelected("f1")
        vm.onAliasFilterSelected(AliasFilter.Alias("shop@desent.xyz"))

        // Folder view = a + b; alias filter narrows to a (AND semantics).
        assertEquals(listOf("a"), vm.uiState.value.threads.map { it.id })
    }

    @Test
    fun resetViewToAll_clearsFolderAliasAndFilter() = runTest(mainDispatcher) {
        threads.value = listOf(email("a"))
        val vm = makeVm()

        vm.onFilterChange(EmailFilter.ARCHIVED)
        vm.onFolderSelected("f1")
        vm.onAliasFilterSelected(AliasFilter.Primary)
        vm.resetViewToAll()

        assertEquals(EmailFilter.ALL, vm.uiState.value.filter)
        assertEquals(null, vm.uiState.value.selectedFolderId)
        assertEquals(AliasFilter.None, vm.uiState.value.aliasFilter)
        assertEquals(listOf("a"), vm.uiState.value.threads.map { it.id })
    }

    @Test
    fun aliasFetch_populatesRowsSortedWithUnreadBadges() = runTest(mainDispatcher) {
        val aliasUseCase = mockk<AliasUseCase>()
        coEvery { aliasUseCase.listAliases() } returns Result.success(
            listOf(alias("shop"), alias("news")) to mockk<AliasTierInfo>(relaxed = true)
        )
        threads.value = listOf(
            email("a", alias = "news@desent.xyz"),
            email("b", alias = "news@desent.xyz", isRead = true),
            email("c")
        )
        emails.value = threads.value

        val vm = makeVm(aliasUseCase = aliasUseCase)

        assertEquals(listOf("news", "shop"), vm.uiState.value.aliases.map { it.alias.localPart })
        assertEquals(1, vm.uiState.value.aliases.first { it.alias.localPart == "news" }.unread)
        assertEquals(1, vm.uiState.value.primaryUnread)
        assertEquals(null, vm.uiState.value.aliasesError)
    }

    @Test
    fun aliasFetch_failureSurfacesError() = runTest(mainDispatcher) {
        val aliasUseCase = mockk<AliasUseCase>()
        coEvery { aliasUseCase.listAliases() } returns Result.failure(RuntimeException("offline"))

        val vm = makeVm(aliasUseCase = aliasUseCase)

        assertEquals("offline", vm.uiState.value.aliasesError)
        assertEquals(true, vm.uiState.value.aliases.isEmpty())
    }

    // ==================== Search ====================

    @Test
    fun searchQuery_matchesSenderNameAndBody() = runTest(mainDispatcher) {
        threads.value = listOf(
            email("a", senderName = "Alice Ace"),
            email("b", content = "the invoice is attached"),
            email("c")
        )
        val vm = makeVm()

        vm.onSearchQueryChange("alice")
        assertEquals(listOf("a"), vm.uiState.value.threads.map { it.id })

        vm.onSearchQueryChange("invoice")
        assertEquals(listOf("b"), vm.uiState.value.threads.map { it.id })
    }

    @Test
    fun searchQuery_noMatchYieldsEmptyList() = runTest(mainDispatcher) {
        threads.value = listOf(email("a"))
        val vm = makeVm()

        vm.onSearchQueryChange("zzz-no-match")

        assertEquals(true, vm.uiState.value.threads.isEmpty())
        assertEquals("zzz-no-match", vm.uiState.value.searchQuery)
    }

    // ==================== Selection mode ====================

    @Test
    fun selection_enterToggleAndClear() = runTest(mainDispatcher) {
        threads.value = listOf(email("a"), email("b"))
        val vm = makeVm()

        vm.enterSelection(email("a"))
        assertEquals(setOf("a"), vm.uiState.value.selectedIds)

        vm.toggleSelection(email("b"))
        assertEquals(setOf("a", "b"), vm.uiState.value.selectedIds)

        vm.toggleSelection(email("a"))
        assertEquals(setOf("b"), vm.uiState.value.selectedIds)

        vm.clearSelection()
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun selection_prunesIdsThatFallOutOfTheList() = runTest(mainDispatcher) {
        threads.value = listOf(email("a"), email("b"))
        val vm = makeVm()
        vm.enterSelection(email("a"))
        vm.toggleSelection(email("b"))
        assertEquals(setOf("a", "b"), vm.uiState.value.selectedIds)

        threads.value = listOf(email("a"))

        assertEquals(setOf("a"), vm.uiState.value.selectedIds)
    }

    @Test
    fun selectAllVisible_selectsCurrentFilteredList() = runTest(mainDispatcher) {
        threads.value = listOf(email("a"), email("b", isRead = true), email("c"))
        val vm = makeVm()

        vm.onFilterChange(EmailFilter.UNREAD)
        vm.selectAllVisible()

        assertEquals(setOf("a", "c"), vm.uiState.value.selectedIds)
    }

    @Test
    fun onFilterChange_clearsSelection() = runTest(mainDispatcher) {
        threads.value = listOf(email("a"), email("b", isRead = true))
        val vm = makeVm()
        vm.enterSelection(email("a"))

        vm.onFilterChange(EmailFilter.UNREAD)

        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    // ==================== Bulk actions ====================

    @Test
    fun deleteSelected_requestsDeletionForEachMessage() = runTest(mainDispatcher) {
        coEvery { emailUseCase.requestDeletion(any()) } returns Result.success("ev")
        threads.value = listOf(email("a"), email("b"))
        val vm = makeVm()
        vm.selectAllVisible()

        vm.deleteSelected()

        coVerify {
            emailUseCase.requestDeletion("a")
            emailUseCase.requestDeletion("b")
        }
        assertEquals("Deleted 2 messages", vm.uiState.value.toast)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun deleteSelected_partialFailureReportsCount() = runTest(mainDispatcher) {
        coEvery { emailUseCase.requestDeletion("a") } returns Result.success("ev")
        coEvery { emailUseCase.requestDeletion("b") } returns Result.failure(RuntimeException("offline"))
        threads.value = listOf(email("a"), email("b"))
        val vm = makeVm()
        vm.selectAllVisible()

        vm.deleteSelected()

        assertEquals("Delete failed for 1 of 2", vm.uiState.value.toast)
    }

    @Test
    fun moveSelectedToFolder_movesEachFolderKey() = runTest(mainDispatcher) {
        val folderRepo = mockk<MailFolderRepository>()
        every { folderRepo.observeFolders(npub) } returns flowOf(emptyList<MailFolder>())
        every { folderRepo.observeState(npub) } returns flowOf(emptyMap<String, MailStateEntry>())
        coEvery { folderRepo.moveToFolder(any(), any(), any()) } returns Result.success(Unit)
        threads.value = listOf(email("a"), email("b"))
        val vm = makeVm(mailFolderRepository = folderRepo)
        vm.selectAllVisible()

        vm.moveSelectedToFolder("f1")

        coVerify {
            folderRepo.moveToFolder(npub, "a@example.com", "f1")
            folderRepo.moveToFolder(npub, "b@example.com", "f1")
        }
        assertEquals("Moved 2 messages to folder", vm.uiState.value.toast)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun markSelectedSpam_trainsForEachMessage() = runTest(mainDispatcher) {
        val a = email("a")
        val b = email("b")
        threads.value = listOf(a, b)
        val vm = makeVm()
        vm.selectAllVisible()

        vm.markSelectedSpam()

        coVerify {
            trainSpamUseCase(a, isSpam = true)
            trainSpamUseCase(b, isSpam = true)
        }
        assertEquals("Moved 2 messages to Spam", vm.uiState.value.toast)
        assertEquals(emptySet<String>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun markSelectedNotSpam_trainsEachMessageAsHam() = runTest(mainDispatcher) {
        val a = email("a", isSpam = true)
        val b = email("b", isSpam = true)
        threads.value = listOf(a, b)
        val vm = makeVm()
        vm.selectAllVisible()

        vm.markSelectedNotSpam()

        coVerify {
            trainSpamUseCase(a, isSpam = false)
            trainSpamUseCase(b, isSpam = false)
        }
        assertEquals("Moved 2 messages to inbox", vm.uiState.value.toast)
    }

    @Test
    fun markSelectedRead_marksWholeThreads() = runTest(mainDispatcher) {
        threads.value = listOf(email("a"), email("b"))
        val vm = makeVm()
        vm.selectAllVisible()

        vm.markSelectedRead()

        coVerify(exactly = 2) { emailUseCase.markThreadRead(npub, any()) }
        assertEquals("Marked 2 messages as read", vm.uiState.value.toast)
    }

    @Test
    fun markSelectedUnread_marksLatestMessages() = runTest(mainDispatcher) {
        threads.value = listOf(email("a", isRead = true), email("b", isRead = true))
        val vm = makeVm()
        vm.selectAllVisible()

        vm.markSelectedUnread()

        coVerify {
            emailUseCase.markAsUnread("a")
            emailUseCase.markAsUnread("b")
        }
        assertEquals("Marked 2 messages as unread", vm.uiState.value.toast)
    }
}
