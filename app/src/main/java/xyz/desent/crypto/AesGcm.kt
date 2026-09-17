package xyz.desent.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side AES-256-GCM for note attachments
 * (refs/PRIVATE_STORAGE_PROTOCOL.md §"Per-attachment object").
 *
 * Wire format stored on the relay: `nonce(12) ‖ ciphertext ‖ tag(16)`.
 *
 * The AES key and nonce are generated client-side and carried inside the
 * NIP-44-encrypted note payload (see [xyz.desent.domain.model.AttachmentMeta]);
 * the relay only ever sees the opaque ciphertext bytes.
 */
object AesGcm {

    private const val KEY_SIZE_BYTES = 32
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Result of encrypting one attachment. [wireBytes] is what gets uploaded. */
    data class Encrypted(
        val wireBytes: ByteArray,
        val keyHex: String,
        val nonceHex: String
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Encrypted && keyHex == other.keyHex && nonceHex == other.nonceHex)

        override fun hashCode(): Int = 31 * keyHex.hashCode() + nonceHex.hashCode()
    }

    private val random: SecureRandom = SecureRandom()

    /** Encrypt [plaintext], returning the upload-ready wire bytes plus the key/nonce. */
    fun encrypt(plaintext: ByteArray): Encrypted {
        val key = ByteArray(KEY_SIZE_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
        // doFinal returns ciphertext ‖ GCM tag.
        val ciphertextWithTag = cipher.doFinal(plaintext)
        val wire = ByteArray(NONCE_SIZE_BYTES + ciphertextWithTag.size)
        System.arraycopy(nonce, 0, wire, 0, NONCE_SIZE_BYTES)
        System.arraycopy(ciphertextWithTag, 0, wire, NONCE_SIZE_BYTES, ciphertextWithTag.size)
        return Encrypted(wireBytes = wire, keyHex = key.toHex(), nonceHex = nonce.toHex())
    }

    /**
     * Decrypt [wireBytes] (`nonce ‖ ciphertext ‖ tag`) using the key from the
     * note's [xyz.desent.domain.model.AttachmentMeta]. The [nonceHex] argument
     * is optional — when present it is validated against the wire's prepended
     * nonce; when null the prepended nonce is used directly.
     */
    fun decrypt(wireBytes: ByteArray, keyHex: String, nonceHex: String? = null): ByteArray {
        require(wireBytes.size > NONCE_SIZE_BYTES) { "ciphertext too short: ${wireBytes.size} bytes" }
        val wireNonce = wireBytes.copyOfRange(0, NONCE_SIZE_BYTES)
        if (nonceHex != null) {
            require(wireNonce.toHex() == nonceHex) { "nonce mismatch: wire != meta" }
        }
        val key = hexToBytes(keyHex)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, wireNonce))
        return cipher.doFinal(wireBytes, NONCE_SIZE_BYTES, wireBytes.size - NONCE_SIZE_BYTES)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(HEX[b.toInt() ushr 4 and 0x0F]).append(HEX[b.toInt() and 0x0F])
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private const val HEX = "0123456789abcdef"
}
