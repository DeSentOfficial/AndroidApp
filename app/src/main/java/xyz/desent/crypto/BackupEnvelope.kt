package xyz.desent.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, password-encrypted envelope for the DeSent account backup blob.
 *
 * Wire layout (big-endian where applicable):
 * ```
 *   magic(5)   ASCII "DSBK1"
 *   ver(1)     envelope version (= [CURRENT_VERSION])
 *   log2_n(1)  scrypt N = 2^log2_n
 *   r(1)
 *   p(1)
 *   salt(16)
 *   nonce(12)
 *   ct ‖ tag   AES-256-GCM over the caller-supplied plaintext
 * ```
 *
 * The relay/file transports only ever see these opaque bytes. A wrong passphrase
 * fails the GCM tag check and is mapped to [WrongPassphraseException].
 *
 * This is intentionally byte-oriented (no JSON/serialization knowledge): the
 * repository layer is responsible for (de)serializing [xyz.desent.domain.model.BackupContents]
 * into the plaintext passed to [pack] / returned by [unpack].
 */
object BackupEnvelope {

    const val MAGIC = "DSBK1"
    const val CURRENT_VERSION: Int = 1

    private const val GCM_TAG_BITS = 128
    private const val NONCE_SIZE = 12
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val SALT_SIZE = ScryptKdf.SALT_SIZE_BYTES

    /** Bytes preceding the ciphertext: magic + ver + log2_n + r + p + salt + nonce. */
    private val HEADER_SIZE = MAGIC.length + 1 + 1 + 1 + 1 + SALT_SIZE + NONCE_SIZE

    private val random: SecureRandom = SecureRandom()

    /** Encrypt [plaintext] under [passphrase], returning the full envelope bytes. */
    fun pack(
        plaintext: ByteArray,
        passphrase: String,
        params: ScryptKdf.Params = ScryptKdf.Params(),
    ): ByteArray {
        val salt = ScryptKdf.newSalt()
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val key = ScryptKdf.deriveKey(passphrase, salt, params)
        val ctWithTag = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }

        val out = ByteArray(HEADER_SIZE + ctWithTag.size)
        val buf = ByteBuffer.wrap(out)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buf.put(CURRENT_VERSION.toByte())
        buf.put(params.log2N.toByte())
        buf.put(params.r.toByte())
        buf.put(params.p.toByte())
        buf.put(salt)
        buf.put(nonce)
        buf.put(ctWithTag)
        return out
    }

    /**
     * Decrypt the envelope bytes. Throws [WrongPassphraseException] on a bad
     * passphrase, [InvalidBackupFormatException] on a corrupt/truncated blob,
     * and [UnsupportedBackupVersionException] on an unknown future version.
     */
    fun unpack(blob: ByteArray, passphrase: String): ByteArray {
        // Need at least header + a 16-byte GCM tag to even attempt decryption.
        if (blob.size < HEADER_SIZE + 16) {
            throw InvalidBackupFormatException("blob too short (${blob.size} bytes)")
        }

        val magic = String(blob.copyOfRange(0, MAGIC.length), Charsets.US_ASCII)
        if (magic != MAGIC) throw InvalidBackupFormatException("bad magic: \"$magic\"")

        val version = blob[MAGIC.length].toInt() and 0xFF
        if (version != CURRENT_VERSION) throw UnsupportedBackupVersionException(version)

        var off = MAGIC.length + 1
        val log2N = blob[off++].toInt() and 0xFF
        val r = blob[off++].toInt() and 0xFF
        val p = blob[off++].toInt() and 0xFF
        val salt = blob.copyOfRange(off, off + SALT_SIZE).also { off += SALT_SIZE }
        val nonce = blob.copyOfRange(off, off + NONCE_SIZE).also { off += NONCE_SIZE }
        val ctWithTag = blob.copyOfRange(off, blob.size)

        val params = ScryptKdf.Params(log2N = log2N, r = r, p = p)
        val key = ScryptKdf.deriveKey(passphrase, salt, params)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ctWithTag)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassphraseException(cause = e)
        } finally {
            key.fill(0)
        }
    }
}

/** Base type for all backup envelope failures. */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The supplied passphrase did not validate against the blob's GCM tag. */
class WrongPassphraseException(cause: Throwable? = null) : BackupException("Wrong passphrase", cause)

/** The blob is not a `DSBK1` envelope or is structurally corrupt. */
class InvalidBackupFormatException(message: String) : BackupException(message)

/** The blob's version is newer than this client understands. */
class UnsupportedBackupVersionException(val version: Int) :
    BackupException("Unsupported backup version: $version")
