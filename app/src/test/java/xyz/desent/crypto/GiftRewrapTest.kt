package xyz.desent.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nostr.util.NostrUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Round-trip coverage for the key-rotation mail re-wrap
 * (KEY_ROTATION.md §4): unwrap with the OLD key → re-target the rumor
 * (pubkey + own p tag; everything else verbatim) → re-seal to the new key
 * → fresh one-time wrap with the required NIP-40 expiration tag.
 */
class GiftRewrapTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val random = SecureRandom()

    private fun freshKey() = ByteArray(32).also { random.nextBytes(it) }

    /** Build a seal+wrap addressed to [recipientPub], signed by [senderPriv]. */
    private fun buildWrappedMail(
        senderPriv: ByteArray,
        recipientPub: ByteArray,
        rumorContent: String = "Hello from the old key"
    ): Pair<String, String> {
        val senderPub = Nip44Encryption.derivePublicKey(senderPriv)
        val senderHex = Nip44Encryption.bytesToHex(senderPub)
        val recipientHex = Nip44Encryption.bytesToHex(recipientPub)

        val rumorJson = kotlinx.serialization.json.buildJsonObject {
            put("pubkey", senderHex)
            put("kind", 1010)
            put("content", rumorContent)
            put(
                "tags",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("p")); add(kotlinx.serialization.json.JsonPrimitive(recipientHex))
                    })
                    add(kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("subject")); add(kotlinx.serialization.json.JsonPrimitive("Test"))
                    })
                    add(kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("message_id")); add(kotlinx.serialization.json.JsonPrimitive("<abc@desent.xyz>"))
                    })
                }
            )
            put("created_at", 1_700_000_000L)
        }.toString()

        val sealContent = Nip44Encryption.encrypt(
            rumorJson,
            Nip44Encryption.getConversationKey(senderPriv, recipientPub)
        )
        val sealCreatedAt = 1_700_000_050L
        val seal = Seal(
            pubkey = senderHex,
            kind = 13,
            content = sealContent,
            created_at = sealCreatedAt,
            tags = emptyList(),
            id = NostrEventCrypto.computeEventId(senderHex, sealCreatedAt, 13, emptyList(), sealContent),
            sig = NostrEventCrypto.signHex(
                NostrEventCrypto.canonicalEventBytes(senderHex, sealCreatedAt, 13, emptyList(), sealContent),
                senderPriv
            )
        )
        val sealJson = json.encodeToString(Seal.serializer(), seal)

        val ephPriv = freshKey()
        val ephPubHex = Nip44Encryption.bytesToHex(Nip44Encryption.derivePublicKey(ephPriv))
        val wrapContent = Nip44Encryption.encrypt(
            sealJson,
            Nip44Encryption.getConversationKey(ephPriv, recipientPub)
        )
        return wrapContent to ephPubHex
    }

    /** Standard NIP-59 double-unwrap as the NEW recipient. */
    private fun unwrapAsNewKey(wrapEventJson: String, newPriv: ByteArray): Map<String, String> {
        val event = json.parseToJsonElement(wrapEventJson).jsonObject
        val wrapAuthor = event["pubkey"]!!.jsonPrimitive.content
        val content = event["content"]!!.jsonPrimitive.content

        val sealJson = Nip44Encryption.decrypt(
            content,
            Nip44Encryption.getConversationKey(newPriv, Nip44Encryption.hexToBytes(wrapAuthor))
        )
        val seal = json.decodeFromString(Seal.serializer(), sealJson)
        val rumorJson = Nip44Encryption.decrypt(
            seal.content,
            Nip44Encryption.getConversationKey(newPriv, Nip44Encryption.hexToBytes(seal.pubkey))
        )
        val rumor = json.decodeFromString(Rumor.serializer(), rumorJson)
        return mapOf(
            "rumorJson" to rumorJson,
            "pubkey" to rumor.pubkey,
            "content" to rumor.content,
            "sealPubkey" to seal.pubkey,
            "kind" to rumor.kind.toString()
        )
    }

    @Test
    fun `rewrap round trip preserves content and threading tags verbatim`() {
        val oldPriv = freshKey()
        val oldPub = Nip44Encryption.derivePublicKey(oldPriv)
        val newPriv = freshKey()
        val newPub = Nip44Encryption.derivePublicKey(newPriv)
        val senderPriv = freshKey()
        val senderHex = Nip44Encryption.bytesToHex(Nip44Encryption.derivePublicKey(senderPriv))
        val newHex = Nip44Encryption.bytesToHex(newPub)

        val (wrapContent, wrapAuthor) = buildWrappedMail(senderPriv, oldPub)

        val outcome = GiftRewrap.rewrap(
            wrapContent = wrapContent,
            wrapAuthorPubkeyHex = wrapAuthor,
            oldPrivateKey = oldPriv,
            oldPublicKey = oldPub,
            newPrivateKey = newPriv,
            newPublicKey = newPub,
            expirationEpochSeconds = 1_900_000_000L
        )

        // The original seal signer is reported (real sender, not the wrap author).
        assertEquals(senderHex, outcome.sealPubkeyHex)
        assertEquals(1010, outcome.rumorKind)

        val unwrapped = unwrapAsNewKey(outcome.wrapEventJson, newPriv)
        assertEquals(newHex, unwrapped["pubkey"])
        assertEquals("Hello from the old key", unwrapped["content"])
        assertEquals(newHex, unwrapped["sealPubkey"]) // re-sealed BY the new key
        assertEquals("1010", unwrapped["kind"])

        // Threading tags survive verbatim; the recipient p tag was re-targeted.
        val rumorJson = unwrapped["rumorJson"]!!
        assertTrue(rumorJson.contains("\"message_id\",\"<abc@desent.xyz>\""))
        assertTrue(rumorJson.contains("\"subject\",\"Test\""))
        assertTrue("re-targeted p tag missing: $rumorJson", rumorJson.contains("[\"p\",\"$newHex\"]"))
        assertNotEquals(newHex, wrapAuthor)
    }

    @Test
    fun `rewrapped event json is well-formed and carries the expiration tag`() {
        val oldPriv = freshKey()
        val oldPub = Nip44Encryption.derivePublicKey(oldPriv)
        val newPriv = freshKey()
        val newPub = Nip44Encryption.derivePublicKey(newPriv)

        val (wrapContent, wrapAuthor) = buildWrappedMail(freshKey(), oldPub)

        val outcome = GiftRewrap.rewrap(
            wrapContent, wrapAuthor, oldPriv, oldPub, newPriv, newPub,
            expirationEpochSeconds = 1_900_000_000L
        )

        val event = json.parseToJsonElement(outcome.wrapEventJson).jsonObject
        assertEquals(1059, event["kind"]!!.jsonPrimitive.content.toInt())
        val tags = event["tags"]!!.jsonArray
        val flat = tags.map { it.jsonArray.map { t -> t.jsonPrimitive.content } }
        assertTrue(listOf("p", Nip44Encryption.bytesToHex(newPub)) in flat)
        assertTrue(listOf("expiration", "1900000000") in flat)
        // All required fields for the relay's /restore verification.
        listOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig").forEach {
            assertTrue("missing field $it", event.containsKey(it))
        }
        // The wrap id must commit to the published fields (canonical NIP-01).
        assertEquals(
            event["id"]!!.jsonPrimitive.content,
            NostrEventCrypto.computeEventId(
                event["pubkey"]!!.jsonPrimitive.content,
                event["created_at"]!!.jsonPrimitive.content.toLong(),
                1059,
                flat,
                event["content"]!!.jsonPrimitive.content
            )
        )
    }

    @Test
    fun `wrap not addressed to the old key fails closed`() {
        val oldPriv = freshKey()
        val oldPub = Nip44Encryption.derivePublicKey(oldPriv)
        val newPriv = freshKey()
        val newPub = Nip44Encryption.derivePublicKey(newPriv)

        // Wrapped for a THIRD party, not for the old key.
        val (wrapContent, wrapAuthor) = buildWrappedMail(
            freshKey(),
            Nip44Encryption.derivePublicKey(freshKey())
        )

        try {
            GiftRewrap.rewrap(wrapContent, wrapAuthor, oldPriv, oldPub, newPriv, newPub, 1L)
            throw AssertionError("expected RewrapException")
        } catch (e: GiftRewrap.RewrapException) {
            assertTrue(e.message!!.contains("unwrap failed"))
        }
    }

    @Test
    fun `expiration policy keeps sane originals and renews stale ones`() {
        val now = 1_800_000_000L
        // Still-far-out original is preserved.
        assertEquals(1_900_000_000L, GiftRewrap.nextExpiration(1_900_000_000L, now))
        // Missing / near / past originals are renewed to now + 90 days.
        val expected = now + 90L * 24 * 60 * 60
        assertEquals(expected, GiftRewrap.nextExpiration(null, now))
        assertEquals(expected, GiftRewrap.nextExpiration(now + 60, now))
        assertEquals(expected, GiftRewrap.nextExpiration(now - 1, now))
    }

    @Test
    fun `group rumor keeps other recipients p tags`() {
        val oldPriv = freshKey()
        val oldPub = Nip44Encryption.derivePublicKey(oldPriv)
        val newPriv = freshKey()
        val newPub = Nip44Encryption.derivePublicKey(newPriv)
        val senderPriv = freshKey()
        val senderHex = Nip44Encryption.bytesToHex(Nip44Encryption.derivePublicKey(senderPriv))
        val oldHex = Nip44Encryption.bytesToHex(oldPub)
        val newHex = Nip44Encryption.bytesToHex(newPub)
        val thirdHex = Nip44Encryption.bytesToHex(Nip44Encryption.derivePublicKey(freshKey()))

        // Rumor carries three p tags: old key, third party, sender.
        val rumorJson = kotlinx.serialization.json.buildJsonObject {
            put("pubkey", senderHex)
            put("kind", 14)
            put("content", "group")
            put(
                "tags",
                kotlinx.serialization.json.buildJsonArray {
                    listOf(oldHex, thirdHex, senderHex).forEach { hex ->
                        add(kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("p")); add(kotlinx.serialization.json.JsonPrimitive(hex))
                        })
                    }
                }
            )
            put("created_at", 1_700_000_000L)
        }.toString()

        val sealContent = Nip44Encryption.encrypt(
            rumorJson,
            Nip44Encryption.getConversationKey(senderPriv, oldPub)
        )
        val seal = Seal(
            pubkey = senderHex, kind = 13, content = sealContent, created_at = 1_700_000_050L,
            id = NostrEventCrypto.computeEventId(senderHex, 1_700_000_050L, 13, emptyList(), sealContent),
            sig = NostrEventCrypto.signHex(
                NostrEventCrypto.canonicalEventBytes(senderHex, 1_700_000_050L, 13, emptyList(), sealContent),
                senderPriv
            )
        )
        val ephPriv = freshKey()
        val ephPubHex = Nip44Encryption.bytesToHex(Nip44Encryption.derivePublicKey(ephPriv))
        val wrapContent = Nip44Encryption.encrypt(
            json.encodeToString(Seal.serializer(), seal),
            Nip44Encryption.getConversationKey(ephPriv, oldPub)
        )

        val outcome = GiftRewrap.rewrap(
            wrapContent, ephPubHex, oldPriv, oldPub, newPriv, newPub, 1_900_000_000L
        )
        val unwrapped = unwrapAsNewKey(outcome.wrapEventJson, newPriv)
        val rumor = unwrapped["rumorJson"]!!
        assertTrue(rumor.contains("[\"p\",\"$newHex\"]"))
        assertTrue("third-party p tag lost", rumor.contains("[\"p\",\"$thirdHex\"]"))
        assertTrue("sender p tag lost", rumor.contains("[\"p\",\"$senderHex\"]"))
        // exactly one occurrence of the re-targeted tag
        assertEquals(1, Regex("\\[\"p\",\"$newHex\"\\]").findAll(rumor).count())
    }
}
