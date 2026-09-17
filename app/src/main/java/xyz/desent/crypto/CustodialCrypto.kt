package xyz.desent.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-custodied account crypto (refs/FROM_email.desent.xyz/
 * CUSTODIAL_ACCOUNTS.md §1).
 *
 * The user's password never leaves the device. Two independent derivations:
 *
 * ```
 * # --- v2 blob: NIP-49 ncryptsec (the blob's key half) ---
 * sym       = scrypt(NFKC(password), salt49=16 random bytes, N=2^16, r=8, p=1, out=32)
 * ct        = XChaCha20-Poly1305(sym, nonce=24, ad=0x02, plaintext=RAW 32-byte key)
 * ncryptsec = bech32("ncryptsec", 0x02‖16‖salt49‖nonce‖0x02‖ct)      # see [Nip49]
 *
 * # --- v2 verifier (the server's password proof — independent KDF) ---
 * salt     = UTF-8("desent-custodial-v2") ‖ 16 random bytes   (35 bytes)
 * verifier = hex(argon2id(NFKC(password), salt, m=65536, t=3, p=1, out=32))
 * ```
 *
 * The server stores only scrypt(verifier) and the opaque blob; it cannot
 * decrypt anything. KDF parameters and the salt travel inside the blob
 * envelope (and the login challenge), so they can be raised later without
 * invalidating old accounts — callers MUST use the envelope/challenge values
 * for existing users rather than the creation-time defaults below.
 *
 * Legacy v1 envelopes (accounts created before 2026-08-28) used an argon2id
 * 64-byte master split into an AES-GCM blob key + verifier, with the
 * `desent-custodial-v1` salt prefix. The web client re-wraps those as v2 at
 * their next web login; this class keeps the v1 *decrypt* path so existing
 * accounts can still sign in on Android, but only ever emits v2.
 *
 * [deriveVerifier]/[Nip49.encrypt] are CPU-heavy (2× 64 MiB KDFs): call them
 * from a background dispatcher only.
 */
object CustodialCrypto {

    /** v2 verifier salt prefix (domain separation). */
    const val SALT_PREFIX_V2 = "desent-custodial-v2"

    /** Legacy v1 salt prefix — only used when decrypting pre-flip envelopes. */
    const val SALT_PREFIX_V1 = "desent-custodial-v1"

    const val SALT_RANDOM_BYTES = 16
    const val SALT_TOTAL_BYTES = SALT_PREFIX_V2.length + SALT_RANDOM_BYTES // 35

    const val KDF_ARGON2ID = "argon2id"
    const val DEFAULT_MEMORY_KIB = 65536
    const val DEFAULT_ITERATIONS = 3
    const val DEFAULT_PARALLELISM = 1

    /** v2 verifier output (full 32 bytes, hex-encoded on the wire). */
    const val VERIFIER_BYTES = 32
    const val VERIFIER_HEX_CHARS = VERIFIER_BYTES * 2

    // ---- Legacy v1 constants (decrypt path only) ---------------------------
    const val MASTER_BYTES = 64
    const val BLOB_KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val random: SecureRandom = SecureRandom()

    /** Argon2id cost parameters as carried in the blob envelope / challenge. */
    @Serializable
    data class KdfParams(
        val m: Int = DEFAULT_MEMORY_KIB,
        val t: Int = DEFAULT_ITERATIONS,
        val p: Int = DEFAULT_PARALLELISM
    )

    /**
     * The opaque encrypted-nsec envelope stored server-side and returned
     * verbatim at login (~350 bytes for v2; server cap 8 KiB).
     */
    @Serializable
    data class Blob(
        val v: Int = 2,
        val kdf: String = KDF_ARGON2ID,
        @SerialName("kdf_params") val kdfParams: KdfParams = KdfParams(),
        /** base64(35-byte verifier salt — prefix ‖ 16 random bytes) */
        val salt: String,
        /** v2: the NIP-49 ncryptsec string (carries its own scrypt salt/log_n). */
        val ncryptsec: String? = null,
        /** v1 legacy: base64(12-byte AES-GCM nonce). */
        val nonce: String? = null,
        /** v1 legacy: base64(ciphertext ‖ 16-byte GCM tag). */
        val ct: String? = null
    ) {
        val isV2: Boolean get() = v >= 2

        /** True when this envelope was produced under the given challenge salt/params. */
        fun matchesChallenge(saltB64: String, params: KdfParams): Boolean =
            salt == saltB64 && kdfParams == params
    }

    /** Result of building a new blob at signup or password change. */
    class BuiltBlob(val blob: Blob, val verifier: String)

    class WrongPasswordException(message: String = "Wrong password (AEAD tag check failed)") :
        Exception(message)

    class UnsupportedKdfException(kdf: String) :
        Exception("Unsupported KDF: $kdf")

    /** Fresh 35-byte v2 verifier salt: domain-separation prefix ‖ 16 random bytes. */
    fun newSalt(): ByteArray =
        SALT_PREFIX_V2.toByteArray(Charsets.UTF_8) + ByteArray(SALT_RANDOM_BYTES).also(random::nextBytes)

    /**
     * v2 login verifier: `hex(argon2id(NFKC(password), salt, out=32))`. The
     * salt/params MUST come from the login challenge (or the envelope being
     * written) — never hardcoded.
     */
    fun deriveVerifier(password: String, salt: ByteArray, params: KdfParams = KdfParams()): String {
        require(salt.isNotEmpty()) { "empty salt" }
        val passwordChars = Normalizer.normalize(password, Normalizer.Form.NFKC).toCharArray()
        try {
            val out = argon2(passwordChars, salt, params, VERIFIER_BYTES)
            try {
                return out.toHex()
            } finally {
                out.fill(0)
            }
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    /**
     * Build a fresh v2 blob + verifier for [nsec] (the bech32 `nsec1…` string)
     * under [password]: the raw 32-byte key goes into a NIP-49 ncryptsec and
     * the verifier is derived independently with a fresh salt.
     */
    fun buildBlob(nsec: String, password: String): BuiltBlob {
        val rawKey = Bech32Utils.nsecToHex(nsec).hexToBytes()
        try {
            val ncryptsec = Nip49.encrypt(rawKey, password)
            val salt = newSalt()
            return BuiltBlob(
                blob = Blob(salt = Base64.getEncoder().encodeToString(salt), ncryptsec = ncryptsec),
                verifier = deriveVerifier(password, salt)
            )
        } finally {
            rawKey.fill(0)
        }
    }

    /**
     * Decrypt a blob (either generation) back to the bech32 `nsec1…` string,
     * using KDF parameters parsed from the envelope itself.
     *
     * @throws WrongPasswordException wrong password or tampered ciphertext
     * @throws IllegalArgumentException malformed envelope
     */
    fun decryptBlob(blob: Blob, password: String): String =
        if (blob.isV2) decryptBlobV2(blob, password) else decryptBlobV1(blob, password)

    /** v2 path: the Poly1305 tag failure IS the password check. */
    private fun decryptBlobV2(blob: Blob, password: String): String {
        val ncryptsec = blob.ncryptsec
            ?: throw IllegalArgumentException("v2 blob is missing its ncryptsec")
        val rawKey = try {
            Nip49.decrypt(ncryptsec, password)
        } catch (e: Nip49.WrongPasswordException) {
            throw WrongPasswordException()
        } catch (e: Nip49.MalformedNcryptsecException) {
            throw IllegalArgumentException("Malformed ncryptsec: ${e.message}")
        }
        try {
            return Bech32Utils.hexToNsec(rawKey.toHex())
        } finally {
            rawKey.fill(0)
        }
    }

    // ------------------------------------------------------------------
    // Legacy v1 path (argon2id 64-byte split + AES-GCM). Decrypt only —
    // kept so pre-2026-08-28 accounts can sign in until the web re-wrap.
    // ------------------------------------------------------------------

    /**
     * v1 64-byte master key. The returned array is the caller's
     * responsibility (wipe with [zeroize] once key + verifier are extracted).
     */
    fun deriveMaster(password: String, salt: ByteArray, params: KdfParams = KdfParams()): ByteArray {
        require(salt.isNotEmpty()) { "empty salt" }
        // NOTE: no NFKC here on purpose — v1 accounts were created without
        // it; normalizing now would break their existing verifier hashes.
        val passwordChars = password.toCharArray()
        try {
            return argon2(passwordChars, salt, params, MASTER_BYTES)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    /** `hex(master[32:64])` — the 64-char v1 verifier. */
    fun verifierFromMaster(master: ByteArray): String {
        require(master.size == MASTER_BYTES) { "master must be $MASTER_BYTES bytes" }
        return master.copyOfRange(BLOB_KEY_BYTES, MASTER_BYTES).toHex()
    }

    /**
     * Decrypt a v1 envelope using a master already derived from the login
     * challenge (saves the second Argon2 pass).
     *
     * @throws WrongPasswordException wrong password or tampered ciphertext
     */
    fun decryptBlobWithMaster(blob: Blob, master: ByteArray): String {
        require(master.size == MASTER_BYTES) { "master must be $MASTER_BYTES bytes" }
        val nonce = blob.nonce ?: throw IllegalArgumentException("v1 blob is missing its nonce")
        val ct = blob.ct ?: throw IllegalArgumentException("v1 blob is missing its ct")
        val key = master.copyOfRange(0, BLOB_KEY_BYTES)
        try {
            return aesGcmDecrypt(
                key,
                Base64.getDecoder().decode(nonce),
                Base64.getDecoder().decode(ct)
            ).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw WrongPasswordException()
        } finally {
            key.fill(0)
        }
    }

    private fun decryptBlobV1(blob: Blob, password: String): String {
        require(blob.kdf == KDF_ARGON2ID) { "Unsupported KDF: ${blob.kdf}" }
        val master = deriveMaster(password, Base64.getDecoder().decode(blob.salt), blob.kdfParams)
        try {
            return decryptBlobWithMaster(blob, master)
        } finally {
            zeroize(master)
        }
    }

    private fun argon2(passwordChars: CharArray, salt: ByteArray, params: KdfParams, outBytes: Int): ByteArray {
        val bcParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.t)
            .withMemoryAsKB(params.m)
            .withParallelism(params.p)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(bcParams)
        val out = ByteArray(outBytes)
        generator.generateBytes(passwordChars, out)
        return out
    }

    fun zeroize(bytes: ByteArray) {
        bytes.fill(0)
    }

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ctWithTag: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ctWithTag) // throws AEADBadTagException on wrong key
    }

    private fun String.hexToBytes(): ByteArray {
        require(length == 64) { "expected a 64-char hex key" }
        return ByteArray(length / 2) { i ->
            ((hexNibble(this[i * 2]) shl 4) or hexNibble(this[i * 2 + 1])).toByte()
        }
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex character")
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
