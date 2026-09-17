package xyz.desent.data.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.SpamFilterConfig

/**
 * Matrix for the remote-image policy (see refs/SPAM_FILTER_REFERENCE.md).
 * A sender's images load only via the user's own allowlists (exact sender,
 * domain) or — when enabled — their contacts. The global curated
 * allowedDomains deliberately plays no part here.
 */
class RemoteImagePolicyTest {

    private val contacts = setOf("friend@example.com")

    @Test
    fun `master off allows everyone`() {
        val config = SpamFilterConfig(blockRemoteImages = false)
        assertTrue(RemoteImagePolicy.isSenderAllowed("stranger@unknown.example", config))
    }

    @Test
    fun `master on blocks unknown sender`() {
        val config = SpamFilterConfig()
        assertFalse(RemoteImagePolicy.isSenderAllowed("stranger@unknown.example", config))
    }

    @Test
    fun `exact sender allowlist entry allows`() {
        val config = SpamFilterConfig(imageAllowedSenders = listOf("news@shop.example"))
        assertTrue(RemoteImagePolicy.isSenderAllowed("news@shop.example", config))
        // Other senders on the same domain stay blocked.
        assertFalse(RemoteImagePolicy.isSenderAllowed("deals@shop.example", config))
    }

    @Test
    fun `domain allowlist entry allows whole domain`() {
        val config = SpamFilterConfig(imageAllowedDomains = listOf("shop.example"))
        assertTrue(RemoteImagePolicy.isSenderAllowed("news@shop.example", config))
        assertTrue(RemoteImagePolicy.isSenderAllowed("deals@shop.example", config))
        assertFalse(RemoteImagePolicy.isSenderAllowed("news@other.example", config))
    }

    @Test
    fun `contact address allows when contacts enabled`() {
        val config = SpamFilterConfig(imagesAllowedForContacts = true)
        assertTrue(RemoteImagePolicy.isSenderAllowed("friend@example.com", config, contacts))
    }

    @Test
    fun `contact address blocked when contacts toggle off`() {
        val config = SpamFilterConfig(imagesAllowedForContacts = false)
        assertFalse(RemoteImagePolicy.isSenderAllowed("friend@example.com", config, contacts))
    }

    @Test
    fun `contacts match by exact address only`() {
        val config = SpamFilterConfig(imagesAllowedForContacts = true)
        // Same domain as the contact, different mailbox → still blocked.
        assertFalse(RemoteImagePolicy.isSenderAllowed("evil@example.com", config, contacts))
    }

    @Test
    fun `entries are matched case- and whitespace-insensitively`() {
        val config = SpamFilterConfig(imageAllowedSenders = listOf("News@Shop.Example"))
        assertTrue(RemoteImagePolicy.isSenderAllowed("  news@shop.example ", config))
    }

    @Test
    fun `blank sender is blocked defensively`() {
        val config = SpamFilterConfig()
        assertFalse(RemoteImagePolicy.isSenderAllowed("   ", config))
    }

    @Test
    fun `normalizers lowercase trim and strip leading at`() {
        assertEquals("user@example.com", RemoteImagePolicy.normalizeSender(" User@Example.COM "))
        assertEquals("example.com", RemoteImagePolicy.normalizeDomain(" @Example.COM "))
        assertEquals("example.com", RemoteImagePolicy.domainOf("someone@Example.COM"))
        assertEquals("", RemoteImagePolicy.domainOf("no-domain"))
    }

    @Test
    fun `snapshot isBlocked composes config and contacts`() {
        val snapshot = RemoteImagePolicySnapshot(
            config = SpamFilterConfig(),
            contactEmails = contacts
        )
        assertTrue(snapshot.isBlocked("stranger@unknown.example"))
        assertFalse(snapshot.isBlocked("friend@example.com"))
    }
}
