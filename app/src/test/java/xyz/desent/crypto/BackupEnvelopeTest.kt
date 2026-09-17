package xyz.desent.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupEnvelopeTest {

    // Cheap scrypt params so the unit suite runs in milliseconds instead of ~1s.
    private val fastParams = ScryptKdf.Params(log2N = 4, r = 8, p = 1)
    private val passphrase = "correct horse battery staple"

    @Test
    fun roundTrip_decryptsToOriginal() {
        val plaintext = """{"accounts":[],"relays":[]}""".toByteArray()
        val blob = BackupEnvelope.pack(plaintext, passphrase, fastParams)
        val out = BackupEnvelope.unpack(blob, passphrase)
        assertArrayEquals(plaintext, out)
    }

    @Test
    fun envelopeStartsWithMagic() {
        val blob = BackupEnvelope.pack("hi".toByteArray(), passphrase, fastParams)
        val magic = String(blob.copyOfRange(0, BackupEnvelope.MAGIC.length), Charsets.US_ASCII)
        assertEquals(BackupEnvelope.MAGIC, magic)
    }

    @Test
    fun ciphertextLengthIsHeaderPlusPlaintextPlusTag() {
        val plaintext = ByteArray(500) { (it and 0xFF).toByte() }
        val blob = BackupEnvelope.pack(plaintext, passphrase, fastParams)
        // header(37) + plaintext(500) + GCM tag(16)
        assertEquals(37 + 500 + 16, blob.size)
    }

    @Test
    fun wrongPassphraseThrows() {
        val blob = BackupEnvelope.pack("secret".toByteArray(), passphrase, fastParams)
        assertThrows(WrongPassphraseException::class.java) {
            BackupEnvelope.unpack(blob, "a completely wrong passphrase")
        }
    }

    @Test
    fun tamperedCiphertextThrows() {
        val blob = BackupEnvelope.pack("secret".toByteArray(), passphrase, fastParams)
        // Flip a byte deep in the ciphertext region (well past the 37-byte header).
        blob[blob.size - 5] = (blob[blob.size - 5].toInt() xor 0x01).toByte()
        assertThrows(WrongPassphraseException::class.java) {
            BackupEnvelope.unpack(blob, passphrase)
        }
    }

    @Test
    fun badMagicThrows() {
        val blob = BackupEnvelope.pack("secret".toByteArray(), passphrase, fastParams)
        blob[1] = 'X'.code.toByte() // corrupt the magic
        assertThrows(InvalidBackupFormatException::class.java) {
            BackupEnvelope.unpack(blob, passphrase)
        }
    }

    @Test
    fun truncatedBlobThrows() {
        val blob = BackupEnvelope.pack("secret".toByteArray(), passphrase, fastParams)
        val truncated = blob.copyOfRange(0, 20) // shorter than header + tag
        assertThrows(InvalidBackupFormatException::class.java) {
            BackupEnvelope.unpack(truncated, passphrase)
        }
    }

    @Test
    fun unknownVersionThrows() {
        val blob = BackupEnvelope.pack("secret".toByteArray(), passphrase, fastParams)
        blob[BackupEnvelope.MAGIC.length] = 99 // overwrite the version byte
        assertThrows(UnsupportedBackupVersionException::class.java) {
            BackupEnvelope.unpack(blob, passphrase)
        }
    }

    @Test
    fun headerCarriesCustomParams() {
        val custom = ScryptKdf.Params(log2N = 6, r = 4, p = 2)
        val blob = BackupEnvelope.pack("x".toByteArray(), passphrase, custom)
        // Params sit at offsets 6, 7, 8 (after magic(5) + ver(1)).
        assertEquals(6, blob[6].toInt() and 0xFF)
        assertEquals(4, blob[7].toInt() and 0xFF)
        assertEquals(2, blob[8].toInt() and 0xFF)
        // And the blob still round-trips with the same passphrase.
        assertArrayEquals("x".toByteArray(), BackupEnvelope.unpack(blob, passphrase))
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val blob = BackupEnvelope.pack(ByteArray(0), passphrase, fastParams)
        val out = BackupEnvelope.unpack(blob, passphrase)
        assertEquals(0, out.size)
    }
}
