package xyz.desent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.crypto.Bech32Utils

/**
 * Validates the DeSent service relay configuration and outbound routing tags.
 *
 * The critical invariant: [RelayConfig.RELAY_PUBKEY_NPUB] must decode back to
 * [RelayConfig.RELAY_PUBKEY_HEX] — if it doesn't, every outbound gift wrap is
 * addressed to the wrong key and the relay can never decrypt it.
 */
class RelayConfigTest {

    @Test
    fun relayPubkeyHex_isValidHex() {
        val hex = RelayConfig.RELAY_PUBKEY_HEX
        assertEquals("pubkey hex must be 32 bytes / 64 chars", 64, hex.length)
        assertTrue("pubkey hex must be lowercase hex", hex.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun relayPubkeyNpub_isBech32NpubForm() {
        val npub = RelayConfig.RELAY_PUBKEY_NPUB
        assertTrue("expected npub bech32 encoding: $npub", npub.startsWith("npub1"))
    }

    @Test
    fun relayPubkeyNpub_decodesBackToHex() {
        val decoded = Bech32Utils.npubToHex(RelayConfig.RELAY_PUBKEY_NPUB)
        assertEquals(
            "npub must round-trip to the configured hex (replies route to the wrong key otherwise)",
            RelayConfig.RELAY_PUBKEY_HEX,
            decoded
        )
    }

    @Test
    fun emailRelayUrl_targetsProductionApex() {
        assertEquals("wss://desent.xyz", RelayConfig.EMAIL_RELAY_URL)
    }

    @Test
    fun publicProfileRelays_includeApexAndExcludeDeadLegacyHost() {
        assertTrue(
            "the login profile bootstrap must include the apex relay",
            RelayConfig.PUBLIC_PROFILE_RELAYS.contains("wss://desent.xyz")
        )
        assertTrue(
            "the decommissioned email.desent.xyz host must not appear",
            RelayConfig.PUBLIC_PROFILE_RELAYS.none { it.contains("email.desent.xyz") }
        )
    }

    /**
     * Decommissioned DeSent hosts (pre-flip email/mail + the retired chat
     * relay) must be rewritten to the apex so stale references can't leak
     * traffic onto hosts that must receive nothing.
     */
    @Test
    fun normalizeLegacyRelayUrl_rewritesDecommissionedHostsToApex() {
        assertEquals(RelayConfig.EMAIL_RELAY_URL, RelayConfig.normalizeLegacyRelayUrl("wss://email.desent.xyz"))
        assertEquals(RelayConfig.EMAIL_RELAY_URL, RelayConfig.normalizeLegacyRelayUrl("wss://email.desent.xyz/"))
        assertEquals(RelayConfig.EMAIL_RELAY_URL, RelayConfig.normalizeLegacyRelayUrl("wss://mail.desent.xyz"))
        assertEquals(RelayConfig.EMAIL_RELAY_URL, RelayConfig.normalizeLegacyRelayUrl("wss://mail.desent.xyz/"))
        assertEquals(RelayConfig.EMAIL_RELAY_URL, RelayConfig.normalizeLegacyRelayUrl("wss://chat.desent.xyz"))
        assertEquals(RelayConfig.EMAIL_RELAY_URL, RelayConfig.normalizeLegacyRelayUrl("wss://chat.desent.xyz/"))
    }

    @Test
    fun normalizeLegacyRelayUrl_leavesLiveAndForeignHostsUntouched() {
        assertEquals("wss://desent.xyz", RelayConfig.normalizeLegacyRelayUrl("wss://desent.xyz"))
        assertEquals("wss://relay.primal.net", RelayConfig.normalizeLegacyRelayUrl("wss://relay.primal.net"))
        // Anchored exact-match: look-alike hosts must NOT be rewritten.
        assertEquals(
            "wss://email.desent.xyz.evil.com",
            RelayConfig.normalizeLegacyRelayUrl("wss://email.desent.xyz.evil.com")
        )
        assertEquals(
            "wss://notemail.desent.xyz",
            RelayConfig.normalizeLegacyRelayUrl("wss://notemail.desent.xyz")
        )
    }
}
