package xyz.desent.crypto

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import nostr.crypto.schnorr.Schnorr
import nostr.event.BaseTag
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import nostr.util.NostrUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * NIP-59 conformance tests, mirroring the interoperability findings of an
 * external nostr-sdk operator's NIP-17 conformance report (2026):
 *
 *  1. Seals must be fully signed kind-13 events (id + sig + kind on the wire).
 *  2. The rumor keeps its REAL creation time; only seal/wrap times randomize.
 *  3. Rumors inside gift wraps are kind 14 by default (not legacy kind 4).
 *  4. The gift wrap is authored by a fresh one-time keypair, never the sender.
 *  5. Unwrap returns the seal signer as the sender, and verifies signed seals.
 */
class GiftWrapEncryptionServiceTest {

    private val senderPrivHex = "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    private val recipientPrivHex = "11".repeat(32)
    private val senderIdentity = Identity.create(senderPrivHex)
    private val recipientIdentity = Identity.create(recipientPrivHex)
    private val senderNpub = Bech32Utils.hexToNpub(senderIdentity.publicKey.toHexString())
    private val recipientNpub = Bech32Utils.hexToNpub(recipientIdentity.publicKey.toHexString())
    private val senderPubHex = senderIdentity.publicKey.toHexString()

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var service: GiftWrapEncryptionService

    @Before
    fun setUp() {
        secureKeyManager = mockk()
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(senderIdentity)
        service = GiftWrapEncryptionService(secureKeyManager)
    }

    private fun actingAs(identity: Identity) {
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
    }

    private fun bytes(hex: String) = Nip44Encryption.hexToBytes(hex)

    /** Decrypt the wrap layer (as the recipient) and return the seal JSON. */
    private fun decryptSealJson(event: GenericEvent): String {
        val recipientPriv = bytes(recipientPrivHex)
        val wrapConv = Nip44Encryption.getConversationKey(recipientPriv, bytes(event.pubKey.toHexString()))
        return Nip44Encryption.decrypt(event.content, wrapConv)
    }

    // ---------------------------------------------------------------
    // 1 + 4: wire shape of a DeSent-produced gift wrap
    // ---------------------------------------------------------------

    @Test
    fun `wrap produces a fully signed kind-13 seal on the wire`() = runBlocking {
        val event = service.wrapGift("hello", recipientNpub, senderNpub).getOrThrow()

        val seal = json.parseToJsonElement(decryptSealJson(event)).jsonObject

        // The three fields the nostr-pearl report found missing.
        assertNotNull("seal must carry id", seal["id"])
        assertNotNull("seal must carry sig", seal["sig"])
        assertEquals("seal must carry kind", 13, seal["kind"]!!.jsonPrimitive.content.toInt())
        assertEquals("seal pubkey must be the sender", senderPubHex, seal["pubkey"]!!.jsonPrimitive.content)

        // The seal id must commit to the seal fields, and the signature must
        // verify against the sender's pubkey — this is what authenticates the
        // sender to conformant clients. Recompute from the canonical NIP-01
        // serialization exactly like a receiving client would.
        val sealContent = seal["content"]!!.jsonPrimitive.content
        val sealCreatedAt = seal["created_at"]!!.jsonPrimitive.long
        val canonical = nostr.event.serializer.EventSerializer.serializeToBytes(
            nostr.base.PublicKey(senderPubHex),
            sealCreatedAt,
            13,
            emptyList(),
            sealContent
        )
        val idBytes = NostrUtil.sha256(canonical)
        assertEquals("seal id must match recomputed id", NostrUtil.bytesToHex(idBytes), seal["id"]!!.jsonPrimitive.content)
        assertTrue(
            "seal schnorr signature must verify",
            // Schnorr.verify(msg32, pubkey32, sig64)
            Schnorr.verify(
                idBytes,
                Nip44Encryption.hexToBytes(senderPubHex),
                Nip44Encryption.hexToBytes(seal["sig"]!!.jsonPrimitive.content)
            )
        )
    }

    @Test
    fun `wrap is authored by a fresh one-time keypair, never the sender`() = runBlocking {
        val event1 = service.wrapGift("hello", recipientNpub, senderNpub).getOrThrow()
        val event2 = service.wrapGift("again", recipientNpub, senderNpub).getOrThrow()

        assertEquals(1059, event1.kind)
        assertNotEquals("wrap author must not be the sender", senderPubHex, event1.pubKey.toHexString())
        assertNotEquals("wrap author must not be the recipient", recipientIdentity.publicKey.toHexString(), event1.pubKey.toHexString())
        assertNotEquals("each wrap uses its own one-time key", event1.pubKey, event2.pubKey)
        assertNotNull("wrap must be signed (id)", event1.id)
        assertNotNull("wrap must be signed (sig)", event1.signature)
    }

    // ---------------------------------------------------------------
    // 2 + 3: timestamps and rumor kind
    // ---------------------------------------------------------------

    @Test
    fun `rumor keeps real time while seal and wrap randomize up to 2 days back`() = runBlocking {
        val before = System.currentTimeMillis() / 1000
        val event = service.wrapGift("hello", recipientNpub, senderNpub).getOrThrow()
        val after = System.currentTimeMillis() / 1000

        val seal = json.parseToJsonElement(decryptSealJson(event)).jsonObject
        val sealCreatedAt = seal["created_at"]!!.jsonPrimitive.long

        // Rumor time: real (within the test's own execution window).
        actingAs(recipientIdentity)
        val unwrapped = service.unwrapGift(event.content, event.pubKey.toHexString()).getOrThrow()
        assertTrue("rumor time must be real", unwrapped.createdAt in before..after)

        // Seal + wrap times: randomized within the 2-day window (and not
        // deterministically 0h/48h like the old broken randomizer).
        assertTrue("seal time within [now-48h, now]", sealCreatedAt in (before - 172_800)..after)
        assertTrue("wrap time within [now-48h, now]", event.createdAt in (before - 172_800)..after)
    }

    @Test
    fun `default rumor kind is 14, not legacy kind 4`() = runBlocking {
        val event = service.wrapGift("hello", recipientNpub, senderNpub).getOrThrow()
        actingAs(recipientIdentity)
        val unwrapped = service.unwrapGift(event.content, event.pubKey.toHexString()).getOrThrow()
        assertEquals(14, unwrapped.kind)
        assertEquals("hello", unwrapped.content)
    }

    // ---------------------------------------------------------------
    // Round trip + attribution
    // ---------------------------------------------------------------

    @Test
    fun `unwrap attributes the message to the seal signer, not the wrap author`() = runBlocking {
        val event = service.wrapGift("hello", recipientNpub, senderNpub).getOrThrow()
        val wrapAuthorHex = event.pubKey.toHexString()

        actingAs(recipientIdentity)
        val unwrapped = service.unwrapGift(event.content, wrapAuthorHex).getOrThrow()

        assertEquals("hello", unwrapped.content)
        assertEquals("sender must be the seal signer", senderNpub, unwrapped.senderNpub)
        assertNotEquals("sender must NOT be the wrap author", wrapAuthorHex, Bech32Utils.npubToHex(unwrapped.senderNpub))
    }

    @Test
    fun `group wraps decrypt for every recipient and the sender self-copy`() = runBlocking {
        val memberBPriv = "22".repeat(32)
        val memberCPriv = "33".repeat(32)
        val memberB = Bech32Utils.hexToNpub(Identity.create(memberBPriv).publicKey.toHexString())
        val memberC = Bech32Utils.hexToNpub(Identity.create(memberCPriv).publicKey.toHexString())

        val gifts = service.wrapGiftForRecipients(
            content = "hi team",
            members = listOf(memberB, memberC),
            senderNpub = senderNpub,
            subject = "trip"
        ).getOrThrow()

        assertEquals("members + self-copy", 3, gifts.size)
        assertEquals("each wrap has its own one-time key", 3, gifts.map { it.second.pubKey }.distinct().size)

        val byNpub = gifts.toMap()
        for ((npub, event) in byNpub) {
            val holderPriv = when (npub) {
                memberB -> memberBPriv
                memberC -> memberCPriv
                else -> senderPrivHex
            }
            actingAs(Identity.create(holderPriv))
            val unwrapped = service.unwrapGift(event.content, event.pubKey.toHexString()).getOrThrow()
            assertEquals("hi team", unwrapped.content)
            assertEquals(14, unwrapped.kind)
            assertEquals("seal signer is the group sender", senderNpub, unwrapped.senderNpub)
        }
    }

    // ---------------------------------------------------------------
    // 5: verification + legacy tolerance
    // ---------------------------------------------------------------

    @Test
    fun `tampered seal is rejected by signature verification`() = runBlocking {
        val event = service.wrapGift("hello", recipientNpub, senderNpub).getOrThrow()

        // Tamper: flip the seal's encrypted content, then re-wrap with a fresh
        // throwaway key exactly like a man-in-the-middle would have to.
        val sealJson = json.parseToJsonElement(decryptSealJson(event)).jsonObject
        val originalContent = sealJson["content"]!!.jsonPrimitive.content
        val tamperedContent = originalContent.dropLast(2) +
            if (originalContent.takeLast(2) == "AA") "BB" else "AA"
        val tamperedSeal = buildString {
            append('{')
            append("\"pubkey\":\"${sealJson["pubkey"]!!.jsonPrimitive.content}\"")
            append(",\"kind\":13")
            append(",\"content\":\"$tamperedContent\"")
            append(",\"created_at\":${sealJson["created_at"]!!.jsonPrimitive.long}")
            append(",\"tags\":[]")
            append(",\"id\":\"${sealJson["id"]!!.jsonPrimitive.content}\"")
            append(",\"sig\":\"${sealJson["sig"]!!.jsonPrimitive.content}\"")
            append('}')
        }
        val throwaway = Identity.create(Nip44Encryption.bytesToHex(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        val tamperedWrapContent = Nip44Encryption.encrypt(
            tamperedSeal,
            Nip44Encryption.getConversationKey(throwaway.privateKey.rawData, bytes(recipientIdentity.publicKey.toHexString()))
        )
        val tamperedEvent = GenericEvent.builder()
            .pubKey(throwaway.publicKey)
            .kind(1059)
            .createdAt(System.currentTimeMillis() / 1000)
            .content(tamperedWrapContent)
            .tags(listOf(GenericTag("p", listOf(recipientIdentity.publicKey.toHexString()))) as List<BaseTag>)
            .build()
        throwaway.sign(tamperedEvent)

        actingAs(recipientIdentity)
        val result = service.unwrapGift(tamperedEvent.content, tamperedEvent.pubKey.toHexString())
        assertTrue("tampered seal must be rejected", result.isFailure)
    }

    @Test
    fun `legacy unsigned seal from pre-conformance DeSent builds is still accepted`() = runBlocking {
        // Reproduce the OLD wire format exactly: seal without id/sig/kind,
        // BOTH layers keyed conv(sender, recipient), wrap authored by the
        // sender's own identity.
        val now = System.currentTimeMillis() / 1000
        val recipientPubHex = recipientIdentity.publicKey.toHexString()
        val convKey = Nip44Encryption.getConversationKey(senderIdentity.privateKey.rawData, bytes(recipientPubHex))

        val rumorJson = """{"pubkey":"$senderPubHex","kind":4,"content":"legacy hello","tags":[["p","$recipientPubHex"]],"created_at":$now}"""
        val legacySealJson = json.encodeToString(
            kotlinx.serialization.serializer<Seal>(),
            Seal(
                pubkey = senderPubHex,
                content = Nip44Encryption.encrypt(rumorJson, convKey),
                created_at = now
            )
        )
        val legacyWrapContent = Nip44Encryption.encrypt(legacySealJson, convKey)
        val legacyEvent = GenericEvent.builder()
            .pubKey(senderIdentity.publicKey)
            .kind(1059)
            .createdAt(now)
            .content(legacyWrapContent)
            .tags(listOf(GenericTag("p", listOf(recipientPubHex))) as List<BaseTag>)
            .build()
        senderIdentity.sign(legacyEvent)

        actingAs(recipientIdentity)
        val unwrapped = service.unwrapGift(legacyEvent.content, senderPubHex).getOrThrow()

        assertEquals("legacy hello", unwrapped.content)
        assertEquals(4, unwrapped.kind)
        assertEquals("legacy attribution is still the seal pubkey", senderNpub, unwrapped.senderNpub)
        assertEquals("legacy rumor time survives", now, unwrapped.createdAt)
    }
}
