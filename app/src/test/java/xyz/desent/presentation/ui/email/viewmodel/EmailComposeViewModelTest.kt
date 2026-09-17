package xyz.desent.presentation.ui.email.viewmodel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.domain.model.ContactEmailAddress
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.domain.repository.RecentCorrespondent
import xyz.desent.domain.usecase.EmailUseCase
import xyz.desent.domain.usecase.PrivateStorageUseCase

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmailComposeViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var emailUseCase: EmailUseCase
    private lateinit var storageUseCase: PrivateStorageUseCase
    private lateinit var profileResolver: ContactProfileResolver

    private val npub = "npub1test"
    private val contacts = MutableStateFlow<List<PrivateContact>>(emptyList())
    private val recent = MutableStateFlow<List<RecentCorrespondent>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        emailUseCase = mockk(relaxed = true)
        storageUseCase = mockk(relaxed = true)
        profileResolver = mockk(relaxed = true)

        coEvery { storageUseCase.activeOwnerNpub() } returns npub
        every { storageUseCase.observeContacts(npub) } returns contacts
        every { emailUseCase.observeRecentOutboundRecipients(npub, any()) } returns recent
        every { profileResolver.observeProfile(any()) } returns flowOf(null)
        every { profileResolver.observeProfileByIdentifier(any()) } returns flowOf(null)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun contact(name: String, vararg emails: String) = PrivateContact(
        name = name,
        emails = emails.map { ContactEmailAddress(label = "", value = it) }
    )

    /** Subscribe the WhileSubscribed suggestions flow and settle the initial emission. */
    private fun kotlinx.coroutines.test.TestScope.subscribe(vm: EmailComposeViewModel) {
        backgroundScope.launch { vm.suggestions.collect {} }
        // Advance past the debounce's initial emission so later focus-arm
        // triggers are synchronous again.
        testScheduler.advanceTimeBy(200)
        runCurrent()
    }

    /** Type into the To field and let the debounce window elapse. */
    private fun kotlinx.coroutines.test.TestScope.type(vm: EmailComposeViewModel, text: String) {
        vm.onFieldTextChange(RecipientField.TO, text)
        testScheduler.advanceTimeBy(200)
        runCurrent()
    }

    @Test
    fun `focus with empty query ranks recently emailed first`() = runTest {
        contacts.value = listOf(
            contact("Alice", "alice@x.com"),
            contact("Bob", "bob@y.org"),
            contact("Carol", "carol@z.net"),
            contact("Dave", "dave@w.io")
        )
        recent.value = listOf(
            RecentCorrespondent("carol@z.net", 200L),
            RecentCorrespondent("alice@x.com", 100L)
        )
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)

        vm.onFieldFocusChanged(RecipientField.TO, true)

        assertEquals(
            listOf("carol@z.net", "alice@x.com", "bob@y.org"),
            vm.suggestions.value.map { it.email }
        )
    }

    @Test
    fun `typing does not re-filter until the debounce window passes`() = runTest {
        contacts.value = listOf(
            contact("Alice", "alice@x.com"),
            contact("Bob", "bob@y.org")
        )
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)
        vm.onFieldFocusChanged(RecipientField.TO, true)
        assertEquals(listOf("alice@x.com", "bob@y.org"), vm.suggestions.value.map { it.email })

        vm.onFieldTextChange(RecipientField.TO, "bob")
        runCurrent() // debounce window not yet elapsed — list must not change

        assertEquals(listOf("alice@x.com", "bob@y.org"), vm.suggestions.value.map { it.email })

        testScheduler.advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf("bob@y.org"), vm.suggestions.value.map { it.email })
    }

    @Test
    fun `without send history falls back to named-first alphabetical`() = runTest {
        contacts.value = listOf(
            contact("", "unnamed@x.com"),
            contact("Carol", "carol@z.net"),
            contact("Alice", "alice@x.com"),
            contact("Bob", "bob@y.org")
        )
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)

        vm.onFieldFocusChanged(RecipientField.TO, true)

        assertEquals(
            listOf("alice@x.com", "bob@y.org", "carol@z.net"),
            vm.suggestions.value.map { it.email }
        )
    }

    @Test
    fun `typing filters by name or email substring`() = runTest {
        contacts.value = listOf(
            contact("Alice", "alice@x.com"),
            contact("Bob", "bob@y.org")
        )
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)

        vm.onFieldTextChange(RecipientField.TO, "Ali")
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("alice@x.com"), vm.suggestions.value.map { it.email })

        vm.onFieldTextChange(RecipientField.TO, "y.org")
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("bob@y.org"), vm.suggestions.value.map { it.email })

        vm.onFieldTextChange(RecipientField.TO, "nobody")
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertTrue(vm.suggestions.value.isEmpty())
    }

    @Test
    fun `multi-email contact expands to one row per address`() = runTest {
        contacts.value = listOf(
            PrivateContact(
                name = "Marcus",
                emails = listOf(
                    ContactEmailAddress(label = "Work", value = "marcus@webb.dev"),
                    ContactEmailAddress(label = "Personal", value = "marcus.webb@proton.me")
                )
            )
        )
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)

        vm.onFieldTextChange(RecipientField.TO, "marcus")
        testScheduler.advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf("marcus@webb.dev", "marcus.webb@proton.me"), vm.suggestions.value.map { it.email })
        assertEquals(listOf("Work", "Personal"), vm.suggestions.value.map { it.label })
    }

    @Test
    fun `pubkey-only contact is not suggested`() = runTest {
        contacts.value = listOf(
            PrivateContact(name = "NoAddress", pubkey = "ab".repeat(32)),
            contact("Alice", "alice@x.com")
        )
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)

        vm.onFieldFocusChanged(RecipientField.TO, true)

        assertEquals(listOf("alice@x.com"), vm.suggestions.value.map { it.email })
    }

    @Test
    fun `suggestions are capped at three rows`() = runTest {
        contacts.value = listOf(
            contact("A", "a@x.com"),
            contact("B", "b@x.com"),
            contact("C", "c@x.com"),
            contact("D", "d@x.com"),
            contact("E", "e@x.com")
        )
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)

        vm.onFieldFocusChanged(RecipientField.TO, true)

        assertEquals(3, vm.suggestions.value.size)
    }

    @Test
    fun `selection inserts the address and closes the dropdown`() = runTest {
        contacts.value = listOf(contact("Alice", "alice@x.com"))
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)
        vm.onFieldFocusChanged(RecipientField.TO, true)
        val suggestion = vm.suggestions.value.single()

        vm.onSuggestionSelected(suggestion)

        // Chip committed with the contact's display name (END-01 §3.4 slot 3).
        assertEquals(
            listOf(xyz.desent.domain.model.EmailRecipient("alice@x.com", "Alice")),
            vm.uiState.value.to
        )
        assertEquals("", vm.uiState.value.fieldTexts[RecipientField.TO])
        assertTrue(vm.suggestions.value.isEmpty())
    }

    @Test
    fun `prefilled recipient alone shows no suggestions`() = runTest {
        contacts.value = listOf(contact("Alice", "alice@x.com"))
        val vm = EmailComposeViewModel(
            emailUseCase,
            prefillRecipient = "alice@x.com",
            privateStorageUseCase = storageUseCase,
            contactProfileResolver = profileResolver
        )
        subscribe(vm)

        assertTrue(vm.suggestions.value.isEmpty())
    }

    @Test
    fun `dismiss closes the dropdown and typing re-arms it`() = runTest {
        contacts.value = listOf(contact("Alice", "alice@x.com"))
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)
        vm.onFieldFocusChanged(RecipientField.TO, true)
        assertTrue(vm.suggestions.value.isNotEmpty())

        vm.dismissSuggestions()
        assertTrue(vm.suggestions.value.isEmpty())

        vm.onFieldTextChange(RecipientField.TO, "al")
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertTrue(vm.suggestions.value.isNotEmpty())
    }

    @Test
    fun `sending hides the suggestions`() = runTest {
        contacts.value = listOf(contact("Alice", "alice@x.com"))
        coEvery {
            emailUseCase.sendColdEmail(any(), any(), any(), any(), any(), any(), any())
        } coAnswers { awaitCancellation() }
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        subscribe(vm)
        vm.onFieldTextChange(RecipientField.TO, "alice@x.com,")
        runCurrent()
        vm.onSubjectChange("Hello")
        vm.onBodyChange("Body")
        assertTrue(vm.suggestions.value.isNotEmpty())

        vm.send()
        runCurrent()

        assertTrue(vm.suggestions.value.isEmpty())
        assertTrue(vm.uiState.value.isSending)
    }

    // ---------------------------------------------------------------
    // Recipient chip editing (END-01 §3.4 / END-03 §6): delimiter commits,
    // cross-line dedupe, addr-spec validation, the 20-recipient cap.
    // ---------------------------------------------------------------

    @Test
    fun `comma commits a chip and clears the typed text`() = runTest {
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)

        vm.onFieldTextChange(RecipientField.TO, "alice@x.com,")

        assertEquals(
            listOf(xyz.desent.domain.model.EmailRecipient("alice@x.com")),
            vm.uiState.value.to
        )
        assertEquals("", vm.uiState.value.fieldTexts[RecipientField.TO])
    }

    @Test
    fun `chips are removable and dedupe across lines case-insensitively`() = runTest {
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)

        vm.onFieldTextChange(RecipientField.TO, "alice@x.com,")
        vm.onFieldTextChange(RecipientField.CC, "ALICE@x.com,")

        assertEquals(1, vm.uiState.value.recipientCount)
        vm.removeRecipient(RecipientField.TO, xyz.desent.domain.model.EmailRecipient("alice@x.com"))
        assertEquals(0, vm.uiState.value.recipientCount)
    }

    @Test
    fun `invalid typed address blocks send with an error`() = runTest {
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        vm.onFieldTextChange(RecipientField.TO, "alice@x.com,")
        vm.onSubjectChange("S")
        vm.onBodyChange("b")
        vm.onFieldTextChange(RecipientField.CC, "not an address")

        vm.send()
        runCurrent()

        assertEquals("Recipient is not a valid email address", vm.uiState.value.error)
        io.mockk.coVerify(exactly = 0) { emailUseCase.sendColdEmail(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `more than twenty envelope recipients is refused`() = runTest {
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        (1..20).forEach { i -> vm.onFieldTextChange(RecipientField.TO, "r$i@x.io,") }
        vm.onSubjectChange("S")
        vm.onBodyChange("b")

        vm.onFieldTextChange(RecipientField.CC, "extra@x.io,")

        vm.send()
        runCurrent()

        assertEquals(
            "Too many recipients (21; max 20)",
            vm.uiState.value.error
        )
        io.mockk.coVerify(exactly = 0) { emailUseCase.sendColdEmail(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `send passes the full recipient lists`() = runTest {
        val lists = slot<List<xyz.desent.domain.model.EmailRecipient>>()
        val ccLists = slot<List<xyz.desent.domain.model.EmailRecipient>>()
        coEvery {
            emailUseCase.sendColdEmail(capture(lists), any(), any(), any(), any(), capture(ccLists), any())
        } returns Result.success(Unit)
        val vm = EmailComposeViewModel(emailUseCase, privateStorageUseCase = storageUseCase, contactProfileResolver = profileResolver)
        vm.onFieldTextChange(RecipientField.TO, "alice@x.com,")
        vm.onFieldTextChange(RecipientField.CC, "carol@foo.io,")
        vm.onFieldTextChange(RecipientField.BCC, "secret@hidden.io,")
        vm.onSubjectChange("S")
        vm.onBodyChange("b")

        vm.send()
        runCurrent()

        assertEquals(listOf(xyz.desent.domain.model.EmailRecipient("alice@x.com")), lists.captured)
        assertEquals(listOf(xyz.desent.domain.model.EmailRecipient("carol@foo.io")), ccLists.captured)
        assertTrue(vm.uiState.value.sent)
    }
}
