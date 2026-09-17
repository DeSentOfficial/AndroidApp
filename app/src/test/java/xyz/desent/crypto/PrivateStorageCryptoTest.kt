package xyz.desent.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateStorageCryptoTest {

    private val priv = Nip44Encryption.hexToBytes(
        "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    )

    @Test
    fun selfConversationKey_equalsExplicitEcdhWithSelf() {
        val selfPub = Nip44Encryption.derivePublicKey(priv)
        val expected = Nip44Encryption.getConversationKey(priv, selfPub)
        assertArrayEquals(expected, PrivateStorageCrypto.selfConversationKey(priv))
    }

    @Test
    fun encryptToSelf_then_decryptFromSelf_roundTrips() {
        val plaintext = """{"title":"Verify codes","body":"847291 expires soon"}"""
        val ciphertext = PrivateStorageCrypto.encryptToSelf(plaintext, priv)
        assertEquals(plaintext, PrivateStorageCrypto.decryptFromSelf(ciphertext, priv))
    }

    @Test
    fun encryptToSelf_isNotThePlaintext() {
        val ciphertext = PrivateStorageCrypto.encryptToSelf("secret backup codes", priv)
        assertNotEquals("secret backup codes", ciphertext)
    }

    @Test
    fun encryptToSelf_twoCallsProduceDifferentCiphertexts() {
        // Random nonce per encryption → distinct payloads for identical plaintext.
        val a = PrivateStorageCrypto.encryptToSelf("same", priv)
        val b = PrivateStorageCrypto.encryptToSelf("same", priv)
        assertNotEquals(a, b)
    }

    @Test
    fun decryptFromSelf_wrongKey_fails() {
        val ciphertext = PrivateStorageCrypto.encryptToSelf("hello", priv)
        val otherPriv = Nip44Encryption.hexToBytes(
            "0011223344556677889900112233445566778899001122334455667788990011"
        )
        assertThrows(SecurityException::class.java) {
            PrivateStorageCrypto.decryptFromSelf(ciphertext, otherPriv)
        }
    }

    @Test
    fun crossDevice_sameKeyDecrypts() {
        // A second device holding the same private key can decrypt a note
        // produced by the first device (multi-device sync premise).
        val plaintext = "synced note body"
        val ciphertext = PrivateStorageCrypto.encryptToSelf(plaintext, priv)
        assertEquals(plaintext, PrivateStorageCrypto.decryptFromSelf(ciphertext, priv))
    }
}
