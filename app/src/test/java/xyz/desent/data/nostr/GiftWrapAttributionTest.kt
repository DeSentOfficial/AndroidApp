package xyz.desent.data.nostr

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import nostr.event.BaseTag
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.SecureKeyManager
import java.security.SecureRandom

/**
 * Receiving-side NIP-59 attribution, mirroring the nostr-pearl report §2:
 * a conformant sender (nostr-sdk-style) authors the outer kind-1059 wrap with
 * a random one-time keypair and signs the kind-13 seal with their persistent
 * key. The unwrap must attribute the rumor to the SEAL signer — never the
 * one-time wrap author. This attribution drives every wrapped feature
 * (email, calendar shares, NIP-46 bridges).
 */
class GiftWrapAttributionTest {

    private val botPrivHex = "ab".repeat(32)
    private val userPrivHex = "cd".repeat(32)
    private val botIdentity = Identity.create(botPrivHex)
    private val userIdentity = Identity.create(userPrivHex)
    private val botNpub = Bech32Utils.hexToNpub(botIdentity.publicKey.toHexString())
    private val botPubHex = botIdentity.publicKey.toHexString()
    private val userPubHex = userIdentity.publicKey.toHexString()

    private lateinit var giftWrap: GiftWrapEncryptionService

    @Before
    fun setUp() {
        val secureKeyManager = mockk<SecureKeyManager>()
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(userIdentity)
        giftWrap = GiftWrapEncryptionService(secureKeyManager)
    }

    /**
     * Build a conformant (nostr-sdk-style) gift wrap:
     *  - kind-14 rumor with REAL created_at, authored by the bot
     *  - kind-13 seal: fully signed by the bot's persistent key, content
     *    NIP-44 encrypted with conv(bot, user)
     *  - kind-1059 wrap: authored + signed by a throwaway keypair, content
     *    NIP-44 encrypted with conv(throwaway, user)
     */
    private fun botGiftWrap(rumorContent: String, rumorTime: Long): GenericEvent {
        val userPub = Nip44Encryption.hexToBytes(userPubHex)

        val rumorJson = """{"pubkey":"$botPubHex","kind":14,"content":"$rumorContent","tags":[["p","$userPubHex"]],"created_at":$rumorTime}"""
        val sealConv = Nip44Encryption.getConversationKey(botIdentity.privateKey.rawData, userPub)

        val sealEvent = GenericEvent.builder()
            .pubKey(botIdentity.publicKey)
            .kind(13)
            .createdAt(rumorTime - 3600)
            .content(Nip44Encryption.encrypt(rumorJson, sealConv))
            .tags(emptyList<BaseTag>())
            .build()
        botIdentity.sign(sealEvent)

        val sealJson = """{"id":"${sealEvent.id}","pubkey":"$botPubHex","created_at":${sealEvent.createdAt},"kind":13,"tags":[],"content":"${sealEvent.content}","sig":"${sealEvent.signature?.toString()}"}"""

        val throwaway = Identity.create(
            Nip44Encryption.bytesToHex(ByteArray(32).also { SecureRandom().nextBytes(it) })
        )
        val wrapConv = Nip44Encryption.getConversationKey(throwaway.privateKey.rawData, userPub)
        val wrapContent = Nip44Encryption.encrypt(sealJson, wrapConv)

        val wrapEvent = GenericEvent.builder()
            .pubKey(throwaway.publicKey)
            .kind(1059)
            .createdAt(rumorTime - 7200)
            .content(wrapContent)
            .tags(listOf(GenericTag("p", listOf(userPubHex))) as List<BaseTag>)
            .build()
        throwaway.sign(wrapEvent)
        return wrapEvent
    }

    @Test
    fun `conformant gift wrap is attributed to the seal signer, not the wrap author`() = runBlocking {
        val rumorTime = System.currentTimeMillis() / 1000 - 60
        val event = botGiftWrap("Why can't you see my nip-17 messages", rumorTime)

        val unwrapped = giftWrap.unwrapGift(
            event.content,
            event.pubKey.toHexString()
        ).getOrThrow()

        assertEquals(
            "sender must be the bot's persistent key (seal signer)",
            botNpub,
            unwrapped.senderNpub
        )
        assertNotEquals(
            "sender must NOT be the one-time wrap author",
            event.pubKey.toHexString(),
            Bech32Utils.npubToHex(unwrapped.senderNpub)
        )
        assertEquals("Why can't you see my nip-17 messages", unwrapped.content)
        assertEquals(14, unwrapped.kind)
    }

    @Test
    fun `rumor time is surfaced, not the randomized wrap time`() = runBlocking {
        val rumorTime = System.currentTimeMillis() / 1000 - 120
        val event = botGiftWrap("ordering matters", rumorTime)

        val unwrapped = giftWrap.unwrapGift(
            event.content,
            event.pubKey.toHexString()
        ).getOrThrow()

        assertEquals("createdAt must come from the rumor", rumorTime, unwrapped.createdAt)
        assertNotEquals(
            "wrap time (randomized) must not be used",
            event.createdAt,
            unwrapped.createdAt
        )
    }

    @Test
    fun `two wraps from the same bot are attributed to the same sender`() = runBlocking {
        // The reported symptom: every bot reply arriving under a new npub.
        // Two conformant wraps from the same bot have different throwaway wrap
        // authors but must unwrap to the SAME sender npub.
        val now = System.currentTimeMillis() / 1000
        val first = botGiftWrap("first", now - 60)
        val second = botGiftWrap("second", now - 30)
        assertNotEquals("fixture sanity: distinct wrap authors", first.pubKey, second.pubKey)

        val unwrappedFirst = giftWrap.unwrapGift(first.content, first.pubKey.toHexString()).getOrThrow()
        val unwrappedSecond = giftWrap.unwrapGift(second.content, second.pubKey.toHexString()).getOrThrow()

        assertEquals(
            "both rumors must share one sender npub",
            unwrappedFirst.senderNpub,
            unwrappedSecond.senderNpub
        )
        assertEquals(botNpub, unwrappedFirst.senderNpub)
    }
}
