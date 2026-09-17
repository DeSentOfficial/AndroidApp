package xyz.desent.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class AesGcmTest {

    @Test
    fun roundTrip_decryptsToOriginal() {
        val plaintext = "hello world 0123456789".toByteArray()
        val enc = AesGcm.encrypt(plaintext)
        val decrypted = AesGcm.decrypt(enc.wireBytes, enc.keyHex, enc.nonceHex)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun wireBytesStartWithNonce() {
        val enc = AesGcm.encrypt(byteArrayOf(1, 2, 3))
        assertEquals(24, enc.nonceHex.length) // 12 bytes = 24 hex chars
        val nonceBytesHex = enc.wireBytes.copyOfRange(0, 12)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(enc.nonceHex, nonceBytesHex)
    }

    @Test
    fun ciphertextLengthIsNoncePlusOverhead() {
        val pt = ByteArray(1000) { (it and 0xFF).toByte() }
        val enc = AesGcm.encrypt(pt)
        // nonce(12) + plaintext + GCM tag(16)
        assertEquals(12 + pt.size + 16, enc.wireBytes.size)
    }

    @Test
    fun wrongKeyFails() {
        val enc = AesGcm.encrypt("secret".toByteArray())
        val badKey = "00".repeat(32)
        assertThrows(AEADBadTagException::class.java) {
            AesGcm.decrypt(enc.wireBytes, badKey, enc.nonceHex)
        }
    }

    @Test
    fun keyAndNonceSizes() {
        val enc = AesGcm.encrypt(byteArrayOf(0))
        assertEquals(64, enc.keyHex.length)  // 32 bytes
        assertEquals(24, enc.nonceHex.length) // 12 bytes
    }

    @Test
    fun emptyInputRoundTrips() {
        val enc = AesGcm.encrypt(ByteArray(0))
        val out = AesGcm.decrypt(enc.wireBytes, enc.keyHex, enc.nonceHex)
        assertEquals(0, out.size)
    }
}
