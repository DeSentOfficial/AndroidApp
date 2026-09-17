package xyz.desent.data.repository

import nostr.event.tag.GenericTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.FanoutRelayEntry
import xyz.desent.domain.model.RelayListMarker

/**
 * NIP-65 relay-list wire handling for the relay-mirroring feature
 * (refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md §1): `r` tag ⇄ entry
 * parsing and the canonical-URL normalizer built on the app's nestled WSS
 * check ([xyz.desent.data.nip46.Nip46PairingUriParser.normalizeRelay]).
 */
class Nip65RelayListParsingTest {

    @Test
    fun `r tags parse with markers and skip malformed entries`() {
        val tags = listOf(
            GenericTag("r", listOf("wss://relay.example.com")),
            GenericTag("r", listOf("wss://other.example.com", "read")),
            GenericTag("r", listOf("wss://write.example.com", "write")),
            GenericTag("r", listOf("wss://unknown.example.com", "wat")),
            GenericTag("r", listOf(" ")),                     // blank URL — skipped
            GenericTag("p", listOf("aabb")),                  // not an r tag — skipped
            GenericTag("d", listOf("irrelevant"))
        )

        val entries = NostrRepository.parseRelayListTags(tags)

        assertEquals(4, entries.size)
        assertEquals(
            FanoutRelayEntry("wss://relay.example.com", RelayListMarker.READ_WRITE),
            entries[0]
        )
        assertEquals(
            FanoutRelayEntry("wss://other.example.com", RelayListMarker.READ),
            entries[1]
        )
        assertEquals(
            FanoutRelayEntry("wss://write.example.com", RelayListMarker.WRITE),
            entries[2]
        )
        // Unknown markers stay permissive — server rules are authoritative.
        assertEquals(RelayListMarker.READ_WRITE, entries[3].marker)
    }

    @Test
    fun `canonicalRelayUrl reuses the nestled WSS normalizer`() {
        assertEquals(
            "wss://relay.example.com",
            FanoutRepositoryImpl.canonicalRelayUrl("relay.example.com")
        )
        assertEquals(
            "wss://relay.example.com",
            FanoutRepositoryImpl.canonicalRelayUrl("wss://relay.example.com/")
        )
        assertEquals(
            "wss://relay.example.com",
            FanoutRepositoryImpl.canonicalRelayUrl("ws://relay.example.com")
        )
        assertEquals(
            "wss://relay.example.com",
            FanoutRepositoryImpl.canonicalRelayUrl("https://relay.example.com")
        )
        // Port + path are preserved, matching the server-side validation.
        assertEquals(
            "wss://relay.example.com:7777/path",
            FanoutRepositoryImpl.canonicalRelayUrl("wss://relay.example.com:7777/path")
        )
    }

    @Test
    fun `canonicalRelayUrl rejects input without a usable host`() {
        assertNull(FanoutRepositoryImpl.canonicalRelayUrl(""))
        assertNull(FanoutRepositoryImpl.canonicalRelayUrl("   "))
        assertNull(FanoutRepositoryImpl.canonicalRelayUrl("wss:// "))
        assertNull(FanoutRepositoryImpl.canonicalRelayUrl("wss://a b.example.com"))
    }

    @Test
    fun `published event carries marker wire values`() {
        // The publish path builds one GenericTag per entry; the wire form is
        // `["r", url]`, `["r", url, "read"]`, `["r", url, "write"]`.
        fun wireParams(marker: RelayListMarker): List<String> {
            val params = mutableListOf("wss://relay.example.com")
            marker.wire?.let { params.add(it) }
            return params
        }
        assertEquals(listOf("wss://relay.example.com"), wireParams(RelayListMarker.READ_WRITE))
        assertEquals(listOf("wss://relay.example.com", "read"), wireParams(RelayListMarker.READ))
        assertEquals(listOf("wss://relay.example.com", "write"), wireParams(RelayListMarker.WRITE))

        // GenericTag round-trips the params the relay expects.
        val tag = GenericTag("r", wireParams(RelayListMarker.WRITE))
        assertEquals("r", tag.code)
        assertEquals(2, tag.params.size)
        assertTrue(tag.params.contains("write"))

        assertEquals(10002, xyz.desent.data.nostr.NostrKinds.RELAY_LIST)
    }
}
