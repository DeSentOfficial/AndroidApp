package xyz.desent.crypto

import org.bouncycastle.crypto.generators.SCrypt
import java.security.SecureRandom

/**
 * Password-based key derivation via scrypt (BouncyCastle). Used by [BackupEnvelope]
 * to turn a user passphrase into a 256-bit AES key for the encrypted account
 * backup blob.
 *
 * The scrypt cost (`N = 2^[log2N]`, [r], [p]) is tuned to make offline brute
 * force of a recovered cloud-stored blob expensive while keeping a one-shot
 * unlock in the ~1–2 s range on modern hardware. Both [r] and [p] are stored
 * alongside [log2N] in the envelope header so future versions can tune them
 * without breaking older blobs.
 */
object ScryptKdf {

    /** Default scrypt parameters: `N = 2^17`, `r = 8`, `p = 1`. */
    data class Params(
        val log2N: Int = DEFAULT_LOG2_N,
        val r: Int = DEFAULT_R,
        val p: Int = DEFAULT_P,
    ) {
        /** Concrete scrypt cost factor `N = 2^[log2N]`. */
        val n: Int get() = 1 shl log2N
    }

    const val DEFAULT_LOG2_N = 17
    const val DEFAULT_R = 8
    const val DEFAULT_P = 1

    const val KEY_SIZE_BYTES = 32
    const val SALT_SIZE_BYTES = 16

    private val random: SecureRandom = SecureRandom()

    /** Fresh random salt for a new backup. */
    fun newSalt(): ByteArray = ByteArray(SALT_SIZE_BYTES).also(random::nextBytes)

    /**
     * Derive a 32-byte key from [passphrase] and [salt]. The UTF-8 byte form of
     * [passphrase] is wiped before returning.
     */
    fun deriveKey(passphrase: String, salt: ByteArray, params: Params = Params()): ByteArray {
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        return try {
            SCrypt.generate(passBytes, salt, params.n, params.r, params.p, KEY_SIZE_BYTES)
        } finally {
            passBytes.fill(0)
        }
    }
}
