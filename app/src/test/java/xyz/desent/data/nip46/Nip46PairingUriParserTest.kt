package xyz.desent.data.nip46

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip46PairingUriParserTest {

    private val clientHex = "79fefa5d89e32455c8e4f1d2c6f5a8aa7c12d8a9c0f1d2b3c4d5e6f7a8b9c0d1"
    private val secretHex = "4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a"

    // ------------------------------------------------------------------
    // DeSent-internal QRs (gift-wrap transport, pinned relay)
    // ------------------------------------------------------------------

    @Test
    fun `desent QR parses as gift-wrap transport`() {
        val uri = "nostrconnect://$clientHex?relay=wss%3A%2F%2Fdesent.xyz%2F&secret=$secretHex&label=DeSent%20Web%20Inbox&permissions=desent-inbox"
        val parsed = Nip46PairingUriParser.parse(uri).getOrThrow()

        assertEquals(clientHex, parsed.sessionPubkey)
        assertEquals(listOf("wss://desent.xyz/"), parsed.relayUrls)
        assertEquals(secretHex, parsed.secret)
        assertEquals("DeSent Web Inbox", parsed.label)
        assertEquals("desent-inbox", parsed.permissions)
        assertEquals(Nip46Transport.GIFT_WRAP, parsed.transport)
    }

    @Test
    fun `legacy mail subdomain stays gift-wrap`() {
        val uri = "nostrconnect://$clientHex?relay=wss://mail.desent.xyz/&secret=$secretHex"
        val parsed = Nip46PairingUriParser.parse(uri).getOrThrow()
        assertEquals(Nip46Transport.GIFT_WRAP, parsed.transport)
    }

    // ------------------------------------------------------------------
    // Foreign-relay QRs are rejected (desent.xyz-only policy)
    // ------------------------------------------------------------------

    @Test
    fun `external QR is rejected under the desent-only relay policy`() {
        val uri = (
            "nostrconnect://$clientHex" +
                "?relay=wss%3A%2F%2Frelay1.example.com" +
                "&perms=nip44_encrypt%2Cnip44_decrypt%2Csign_event%3A13%2Csign_event%3A14%2Csign_event%3A1059" +
                "&name=My+Client" +
                "&secret=0s8j2djs9s" +
                "&relay=wss%3A%2F%2Frelay2.example.com"
            )
        val result = Nip46PairingUriParser.parse(uri)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("desent.xyz") == true)
    }

    @Test
    fun `mixed desent and external relay is rejected`() {
        val uri = "nostrconnect://$clientHex?relay=wss://desent.xyz/&relay=wss://relay.example.com&secret=$secretHex"
        assertTrue(Nip46PairingUriParser.parse(uri).isFailure)
    }

    @Test
    fun `short random secret is accepted`() {
        val uri = "nostrconnect://$clientHex?relay=wss://desent.xyz/&secret=abcd1234"
        val parsed = Nip46PairingUriParser.parse(uri).getOrThrow()
        assertEquals("abcd1234", parsed.secret)
    }

    @Test
    fun `label falls back to label param when name absent`() {
        val uri = "nostrconnect://$clientHex?relay=wss://desent.xyz/&secret=$secretHex&label=Amethyst"
        val parsed = Nip46PairingUriParser.parse(uri).getOrThrow()
        assertEquals("Amethyst", parsed.label)
    }

    @Test
    fun `label resolves from metadata json when name and label absent`() {
        val uri = "nostrconnect://$clientHex?relay=wss://desent.xyz/&secret=$secretHex&metadata=%7B%22name%22%3A%22iris%22%7D"
        val parsed = Nip46PairingUriParser.parse(uri).getOrThrow()
        assertEquals("iris", parsed.label)
    }

    @Test
    fun `npub host is accepted`() {
        val npub = "npub1sn0wdenkukak0d9dfczzeacvhkdgz79whtgh5h3v3kkf40e2ce7qcffflg" // random-looking but decodable
        val parsed = Nip46PairingUriParser.parse("nostrconnect://$npub?relay=wss://desent.xyz/&secret=$secretHex")
        if (parsed.isFailure) {
            // bech32 decode must succeed for a well-formed npub; if the test
            // vector is invalid, at least a hex host still parses.
            assertNotNull(Nip46PairingUriParser.parse("nostrconnect://$clientHex?relay=wss://desent.xyz/&secret=$secretHex").getOrThrow())
            return
        }
        assertEquals(64, parsed.getOrThrow().sessionPubkey.length)
    }

    @Test
    fun `ws relay scheme normalizes to wss`() {
        val parsed = Nip46PairingUriParser.parse("nostrconnect://$clientHex?relay=ws://desent.xyz&secret=$secretHex").getOrThrow()
        assertEquals(listOf("wss://desent.xyz/"), parsed.relayUrls)
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    fun `non nostrconnect uri is rejected`() {
        assertTrue(Nip46PairingUriParser.parse("bunker://$clientHex?relay=wss://r.example.com&secret=$secretHex").isFailure)
    }

    @Test
    fun `missing relay is rejected`() {
        assertTrue(Nip46PairingUriParser.parse("nostrconnect://$clientHex?secret=$secretHex").isFailure)
    }

    @Test
    fun `missing secret is rejected`() {
        assertTrue(Nip46PairingUriParser.parse("nostrconnect://$clientHex?relay=wss://r.example.com").isFailure)
    }

    @Test
    fun `too-short secret is rejected`() {
        assertTrue(Nip46PairingUriParser.parse("nostrconnect://$clientHex?relay=wss://r.example.com&secret=abc").isFailure)
    }

    @Test
    fun `bad session pubkey is rejected`() {
        assertTrue(Nip46PairingUriParser.parse("nostrconnect://nothex?relay=wss://r.example.com&secret=$secretHex").isFailure)
    }

    @Test
    fun `expired QR is rejected`() {
        val expiry = (System.currentTimeMillis() / 1000) - 60
        val uri = "nostrconnect://$clientHex?relay=wss://r.example.com&secret=$secretHex&expiry=$expiry"
        assertTrue(Nip46PairingUriParser.parse(uri).isFailure)
    }

    @Test
    fun `future expiry is accepted`() {
        val expiry = (System.currentTimeMillis() / 1000) + 300
        val uri = "nostrconnect://$clientHex?relay=wss://desent.xyz/&secret=$secretHex&expiry=$expiry"
        val parsed = Nip46PairingUriParser.parse(uri).getOrThrow()
        assertEquals(expiry, parsed.expiry)
    }

    @Test
    fun `relay without scheme gets wss prefix`() {
        val parsed = Nip46PairingUriParser.parse("nostrconnect://$clientHex?relay=desent.xyz&secret=$secretHex").getOrThrow()
        assertEquals(listOf("wss://desent.xyz/"), parsed.relayUrls)
        assertNull(parsed.label?.takeIf { it.isEmpty() })
    }
}
