package xyz.desent.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * NIP-44 v2 implementation tests.
 *
 * Authoritative vectors from the spec:
 * https://github.com/nostr-protocol/nips/blob/master/44.md
 */
class Nip44EncryptionTest {

    private val sec1Hex = "0000000000000000000000000000000000000000000000000000000000000001"
    private val sec2Hex = "0000000000000000000000000000000000000000000000000000000000000002"
    private val expectedConvKeyHex = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d"
    private val specNonceHex = "0000000000000000000000000000000000000000000000000000000000000001"
    private val specPayload =
        "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"

    private fun hex(s: String) = Nip44Encryption.hexToBytes(s)

    @Test
    fun testConversationKey_matchesSpecVector() {
        val sec1 = hex(sec1Hex)
        val pub2 = Nip44Encryption.derivePublicKey(hex(sec2Hex))

        val conv = Nip44Encryption.getConversationKey(sec1, pub2)
        assertArrayEquals(hex(expectedConvKeyHex), conv)
    }

    @Test
    fun testConversationKey_isSymmetric() {
        // conv(a, B) == conv(b, A)
        val privA = hex("d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa")
        val privB = hex("0011223344556677889900112233445566778899001122334455667788990011")
        val pubA = Nip44Encryption.derivePublicKey(privA)
        val pubB = Nip44Encryption.derivePublicKey(privB)

        val convAB = Nip44Encryption.getConversationKey(privA, pubB)
        val convBA = Nip44Encryption.getConversationKey(privB, pubA)
        assertArrayEquals(convAB, convBA)
    }

    @Test
    fun testDecrypt_specVector() {
        val sec1 = hex(sec1Hex)
        val pub2 = Nip44Encryption.derivePublicKey(hex(sec2Hex))
        val conv = Nip44Encryption.getConversationKey(sec1, pub2)

        val plaintext = Nip44Encryption.decrypt(specPayload, conv)
        assertEquals("a", plaintext)
    }

    @Test
    fun testEncrypt_decrypt_roundTrip() {
        val privA = hex("d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa")
        val privB = hex("0011223344556677889900112233445566778899001122334455667788990011")
        val pubB = Nip44Encryption.derivePublicKey(privB)
        val conv = Nip44Encryption.getConversationKey(privA, pubB)

        val messages = listOf(
            "a",
            "hello world",
            "Hello, DeSent! 🚀 email over nostr.",
            "x".repeat(32),
            "y".repeat(33),
            "z".repeat(100),
            "long ".repeat(10000)
        )

        for (msg in messages) {
            val payload = Nip44Encryption.encrypt(msg, conv)
            val decrypted = Nip44Encryption.decrypt(payload, conv)
            assertEquals("round-trip failed for length ${msg.length}", msg, decrypted)
        }
    }

    @Test
    fun testCrossParty_roundTrip() {
        // A encrypts with conv(privA, pubB); B decrypts with conv(privB, pubA).
        val privA = hex("d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa")
        val privB = hex("0011223344556677889900112233445566778899001122334455667788990011")
        val pubA = Nip44Encryption.derivePublicKey(privA)
        val pubB = Nip44Encryption.derivePublicKey(privB)

        val senderConv = Nip44Encryption.getConversationKey(privA, pubB)
        val recipientConv = Nip44Encryption.getConversationKey(privB, pubA)

        val msg = "the eagle flies at midnight"
        val payload = Nip44Encryption.encrypt(msg, senderConv)
        assertEquals(msg, Nip44Encryption.decrypt(payload, recipientConv))
    }

    @Test
    fun testTamperedPayload_isRejected() {
        val privA = hex("d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa")
        val pubB = Nip44Encryption.derivePublicKey(hex(sec2Hex))
        val conv = Nip44Encryption.getConversationKey(privA, pubB)

        val payload = Nip44Encryption.encrypt("secret", conv).toCharArray()
        // flip one character near the end (the MAC region)
        payload[payload.size - 1] = if (payload.last() == 'A') 'B' else 'A'
        val tampered = String(payload)

        assertThrows(SecurityException::class.java) {
            Nip44Encryption.decrypt(tampered, conv)
        }
    }

    @Test
    fun testWrongKey_isRejected() {
        val privA = hex("d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa")
        val privWrong = hex("9988776655443322110099887766554433221100998877665544332211009988")
        val pubB = Nip44Encryption.derivePublicKey(hex(sec2Hex))
        val rightConv = Nip44Encryption.getConversationKey(privA, pubB)
        val wrongConv = Nip44Encryption.getConversationKey(privWrong, pubB)

        val payload = Nip44Encryption.encrypt("secret", rightConv)
        assertThrows(SecurityException::class.java) {
            Nip44Encryption.decrypt(payload, wrongConv)
        }
    }

    @Test
    fun testEncryptWithFixedNonce_matchesSpecPayload() {
        // Encrypt "a" with the spec conversation key + nonce -> must reproduce the spec payload.
        val conv = hex(expectedConvKeyHex)
        val nonce = hex(specNonceHex)
        val payload = Nip44Encryption.encrypt("a", conv, nonce)
        assertEquals(specPayload, payload)
    }

    /**
     * Simulate the NIP-59 double-wrap (gift wrap 1059 -> seal 13 -> rumor) purely at the
     * NIP-44 layer, to validate the two-step unwrap that GiftWrapEncryptionService performs.
     */
    @Test
    fun testDoubleWrap_roundTrip() {
        val recipientPriv = hex("7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a")
        val recipientPub = Nip44Encryption.derivePublicKey(recipientPriv)
        val senderPriv = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val senderPub = Nip44Encryption.derivePublicKey(senderPriv)
        val randomWrapPriv = hex("5511223344556677889900112233445566778899001122334455667788990011")
        val randomWrapPub = Nip44Encryption.derivePublicKey(randomWrapPriv)

        // inner rumor
        val rumor = """{"pubkey":"${Nip44Encryption.bytesToHex(senderPriv).let { Nip44Encryption.bytesToHex(senderPub) }}","kind":14,"content":"hello email","tags":[["p","${Nip44Encryption.bytesToHex(recipientPub)}"]],"created_at":1700000000}"""

        // sender seals the rumor to the recipient using ECDH(sender, recipient)
        val sealConv = Nip44Encryption.getConversationKey(senderPriv, recipientPub)
        val sealPayload = Nip44Encryption.encrypt(rumor, sealConv)

        // gift wrap: random key seals the seal to the recipient using ECDH(random, recipient)
        val wrapConv = Nip44Encryption.getConversationKey(randomWrapPriv, recipientPub)
        val giftWrapPayload = Nip44Encryption.encrypt(sealPayload, wrapConv)

        // --- recipient unwraps ---
        val outerConv = Nip44Encryption.getConversationKey(recipientPriv, randomWrapPub)
        val recoveredSeal = Nip44Encryption.decrypt(giftWrapPayload, outerConv)
        assertEquals(sealPayload, recoveredSeal)

        val innerConv = Nip44Encryption.getConversationKey(recipientPriv, senderPub)
        val recoveredRumor = Nip44Encryption.decrypt(recoveredSeal, innerConv)
        assertEquals(rumor, recoveredRumor)

        // sanity: rumor content round-trips
        assertNotEquals(0, recoveredRumor.length)
    }
}
