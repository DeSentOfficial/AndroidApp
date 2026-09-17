package xyz.desent.presentation.notification

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.avatar.FaviconResolver
import xyz.desent.domain.model.ContactProfile
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.repository.ContactProfileResolver

class NotificationAvatarFactoryTest {

    private val hex = "ab".repeat(32)
    private val npub = Bech32Utils.hexToNpub(hex)

    private lateinit var resolver: ContactProfileResolver
    private lateinit var faviconResolver: FaviconResolver

    private val email = Email(
        id = "e1",
        recipientNpub = "npub1me",
        senderEmail = "someone@example.com",
        senderDomain = "example.com",
        senderName = "Someone",
        subject = "hi",
        content = "body",
        dkimStatus = xyz.desent.domain.model.DkimStatus.PASS,
        emailType = EmailType.OTHER,
        bridge = "bridge.example.com",
        messageId = "e1@example.com",
        threadToken = null,
        createdAt = 1_000L,
        threadSenderPubkey = npub
    )

    @Before
    fun setUp() {
        resolver = mockk(relaxed = true)
        faviconResolver = mockk()
    }

    private fun factory() = NotificationAvatarFactory(
        context = mockk<Context>(relaxed = true),
        profileResolver = resolver,
        faviconResolver = faviconResolver
    )

    @Test
    fun `thread sender pubkey picture wins`() = runBlocking {
        every { resolver.observeProfile(hex) } returns flowOf(
            ContactProfile(picture = "https://nostr/pic.png")
        )
        coEvery { faviconResolver.cachedUrlFor(any()) } throws AssertionError("favicon must not be consulted")

        assertEquals("https://nostr/pic.png", factory().avatarUrlFor(email))
    }

    @Test
    fun `nip05 link picture fills in when pubkey profile is empty`() = runBlocking {
        every { resolver.observeProfile(hex) } returns flowOf(null)
        every { resolver.observeProfileByIdentifier("someone@example.com") } returns flowOf(
            ContactProfile(picture = "https://nostr/linked.png")
        )

        assertEquals("https://nostr/linked.png", factory().avatarUrlFor(email))
    }

    @Test
    fun `cached favicon is the last URL fallback`() = runBlocking {
        every { resolver.observeProfile(hex) } returns flowOf(null)
        every { resolver.observeProfileByIdentifier("someone@example.com") } returns flowOf(null)
        coEvery { faviconResolver.cachedUrlFor("someone@example.com") } returns "https://example.com/favicon.ico"

        assertEquals("https://example.com/favicon.ico", factory().avatarUrlFor(email))
    }

    @Test
    fun `all layers empty yields null url (initials render)`() = runBlocking {
        every { resolver.observeProfile(hex) } returns flowOf(null)
        every { resolver.observeProfileByIdentifier("someone@example.com") } returns flowOf(null)
        coEvery { faviconResolver.cachedUrlFor("someone@example.com") } returns null

        assertNull(factory().avatarUrlFor(email))
    }

    @Test
    fun `invalid thread pubkey skips to the identifier path`() = runBlocking {
        val badKey = email.copy(threadSenderPubkey = "npub1notarealkey")
        every { resolver.observeProfileByIdentifier("someone@example.com") } returns flowOf(
            ContactProfile(picture = "https://nostr/via-email.png")
        )

        assertEquals("https://nostr/via-email.png", factory().avatarUrlFor(badKey))
    }

    @Test
    fun `warmUp resolves pubkey path and favicon without throwing`() = runBlocking {
        every { resolver.observeProfile(hex) } returns flowOf(null)
        coEvery { resolver.resolve(hex) } returns ContactProfile(picture = "https://nostr/pic.png")
        coEvery { faviconResolver.faviconUrlFor("someone@example.com") } returns null

        factory().warmUp(email) // must not throw

        io.mockk.coVerify(exactly = 1) { resolver.resolve(hex) }
        io.mockk.coVerify(exactly = 1) { faviconResolver.faviconUrlFor("someone@example.com") }
    }
}
