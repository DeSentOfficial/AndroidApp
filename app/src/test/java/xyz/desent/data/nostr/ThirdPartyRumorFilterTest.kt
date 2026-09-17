package xyz.desent.data.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.UnwrappedContent

/**
 * The third-party source gate for gift wraps pulled from the user's NIP-65
 * backup relays (ANDROID_DM_FANOUT.md §5 + the DM_FANOUT scope): after
 * unwrap, anything that is not a DeSent email rumor is dumped — foreign
 * NIP-17 traffic never routes to the bunker, calendar, or inbox.
 */
class ThirdPartyRumorFilterTest {

    private fun rumor(kind: Int, vararg tags: List<String>) = UnwrappedContent(
        content = "hi",
        senderNpub = "npub1sender",
        kind = kind,
        tags = tags.toList()
    )

    // ---------------- Kind 1010: the email rumor kind ----------------

    @Test
    fun `kind 1010 inbound is email`() {
        assertTrue(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(1010, listOf("direction", "inbound"))
            )
        )
    }

    @Test
    fun `kind 1010 without a direction defaults to inbound email`() {
        assertTrue(NostrEventProcessor.isThirdPartyEmailRumor(rumor(1010)))
    }

    @Test
    fun `kind 1010 delivery receipts are email (mirroring covers them)`() {
        assertTrue(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(1010, listOf("direction", "delivery-receipt"))
            )
        )
    }

    @Test
    fun `kind 1010 security and badge notices are local-only - dumped`() {
        assertFalse(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(1010, listOf("direction", "security"))
            )
        )
        assertFalse(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(1010, listOf("direction", "badge"))
            )
        )
    }

    @Test
    fun `kind 1010 outbound echoes are dumped`() {
        assertFalse(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(1010, listOf("direction", "outbound"))
            )
        )
    }

    // ---------------- Kind 14: legacy envelope ----------------

    @Test
    fun `kind 14 with email tags is email`() {
        assertTrue(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(14, listOf("sender", "bob@example.com"), listOf("dkim", "pass"))
            )
        )
    }

    @Test
    fun `kind 14 nip46 bunker traffic is dumped`() {
        assertFalse(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(14, listOf("bridge", "nip46"))
            )
        )
    }

    @Test
    fun `kind 14 calendar shares are dumped`() {
        assertFalse(
            NostrEventProcessor.isThirdPartyEmailRumor(
                rumor(14, listOf("bridge", "calendar"))
            )
        )
    }

    @Test
    fun `kind 14 plain chat traffic is dumped`() {
        assertFalse(
            NostrEventProcessor.isThirdPartyEmailRumor(rumor(14))
        )
    }

    // ---------------- Everything else ----------------

    @Test
    fun `chat kinds and unknown kinds are dumped`() {
        // NIP-17 chat rumor kinds from other clients sharing the relay.
        assertFalse(NostrEventProcessor.isThirdPartyEmailRumor(rumor(15)))
        // Anything unknown.
        assertFalse(NostrEventProcessor.isThirdPartyEmailRumor(rumor(9)))
        assertFalse(NostrEventProcessor.isThirdPartyEmailRumor(rumor(30078)))
    }
}
