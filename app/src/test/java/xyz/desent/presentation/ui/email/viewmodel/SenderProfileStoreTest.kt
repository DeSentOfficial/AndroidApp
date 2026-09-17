package xyz.desent.presentation.ui.email.viewmodel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.data.avatar.FaviconResolver
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.model.Email
import xyz.desent.domain.repository.ContactProfileResolver

class SenderProfileStoreTest {

    private val emailKey = "someone@example.com"

    private lateinit var resolver: ContactProfileResolver
    private lateinit var faviconResolver: FaviconResolver
    private lateinit var scope: CoroutineScope

    private val email = mockk<Email>()

    @Before
    fun setUp() {
        resolver = mockk(relaxed = true)
        faviconResolver = mockk()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        every { email.senderEmail } returns emailKey
        every { email.threadSenderPubkey } returns null
        coEvery { faviconResolver.faviconUrlFor(any()) } returns null
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store() = SenderProfileStore(resolver, faviconResolver, scope)

    private fun emailFlow(initial: ContactProfile?) = MutableStateFlow(initial)

    private suspend fun SenderProfileStore.awaitAvatar(key: String): String? =
        withTimeout(2_000L) { avatars.first { it.containsKey(key) }[key] }

    @Test
    fun `kind-0 picture wins over favicon`() = runBlocking {
        val profile = ContactProfile(picture = "https://nostr/pic.png")
        val flow = emailFlow(profile)
        every { resolver.observeProfileByIdentifier(emailKey) } returns flow
        coEvery { resolver.resolveByIdentifier(emailKey) } returns profile
        coEvery { faviconResolver.faviconUrlFor(emailKey) } returns "https://example.com/favicon.ico"

        val store = store()
        store.ensure(email)

        assertEquals("https://nostr/pic.png", store.awaitAvatar(emailKey))
    }

    @Test
    fun `favicon fills in when no picture resolves`() = runBlocking {
        every { resolver.observeProfileByIdentifier(emailKey) } returns emailFlow(null)
        coEvery { resolver.resolveByIdentifier(emailKey) } returns null
        coEvery { faviconResolver.faviconUrlFor(emailKey) } returns "https://example.com/favicon.ico"

        val store = store()
        store.ensure(email)

        assertEquals("https://example.com/favicon.ico", store.awaitAvatar(emailKey))
    }

    @Test
    fun `both missing yields null avatar (initials render)`() = runBlocking {
        every { resolver.observeProfileByIdentifier(emailKey) } returns emailFlow(null)
        coEvery { resolver.resolveByIdentifier(emailKey) } returns null
        coEvery { faviconResolver.faviconUrlFor(emailKey) } returns null

        val store = store()
        store.ensure(email)

        assertEquals(null, store.awaitAvatar(emailKey))
    }

    @Test
    fun `late-arriving picture replaces the favicon`() = runBlocking {
        val flow = emailFlow(null)
        every { resolver.observeProfileByIdentifier(emailKey) } returns flow
        coEvery { resolver.resolveByIdentifier(emailKey) } returns null
        coEvery { faviconResolver.faviconUrlFor(emailKey) } returns "https://example.com/favicon.ico"

        val store = store()
        store.ensure(email)
        assertEquals("https://example.com/favicon.ico", store.awaitAvatar(emailKey))

        // The kind-0 picture lands after the favicon was shown.
        flow.value = ContactProfile(picture = "https://nostr/late.png")
        assertEquals("https://nostr/late.png", store.awaitAvatar(emailKey))
    }
}
