package xyz.desent.crypto

import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.text.Normalizer

/**
 * NIP-49 private-key encryption (`ncryptsec`), used both by the custodial
 * v2 key blob (refs/FROM_email.desent.xyz/CUSTODIAL_ACCOUNTS.md §1) and by
 * the "export encrypted key" backup flow.
 *
 * Wire format (91-byte bech32 payload, HRP `ncryptsec`):
 * ```
 * 0x02 (version) || log_n (1) || salt (16) || nonce (24) || key_security (0x02) || ct (32+16)
 * sym = scrypt(NFKC(password), salt, N=2^log_n, r=8, p=1, out=32)
 * ct  = XChaCha20-Poly1305(sym, nonce, aad=key_security, plaintext=RAW 32-byte key)
 * ```
 *
 * XChaCha20-Poly1305 is composed per draft-irtf-cfrg-xchacha (the Bouncy
 * Castle ≥ 1.60 route from ANDROID_CUSTODIAL_ACCOUNTS.md §4): HChaCha20
 * derives a subkey from the first 16 nonce bytes, then ChaCha20-Poly1305
 * runs with the remaining 8 bytes after a 4-zero-byte pad. A wrong
 * password fails the Poly1305 tag — that IS the password check.
 *
 * All KDFs are CPU/memory heavy (scrypt N=2^16 = 64 MiB); call from a
 * background dispatcher only.
 */
object Nip49 {

    const val HRP = "ncryptsec"
    const val VERSION: Byte = 0x02
    const val KEY_SECURITY: Byte = 0x02
    const val DEFAULT_LOG_N = 16
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 24
    const val KEY_BYTES = 32
    const val TAG_BYTES = 16
    const val PAYLOAD_BYTES = 1 + 1 + SALT_BYTES + NONCE_BYTES + 1 + KEY_BYTES + TAG_BYTES // 91

    /** Upper bound accepted when parsing (guards against OOM-by-malicious-log_n). */
    private const val MAX_LOG_N = 20

    private val random: SecureRandom = SecureRandom()

    class WrongPasswordException(message: String = "Wrong password (Poly1305 tag check failed)") :
        Exception(message)

    class MalformedNcryptsecException(message: String) : Exception(message)

    /** Encrypt the RAW 32-byte private key under [password], returning `ncryptsec1…`. */
    fun encrypt(rawKey: ByteArray, password: String, logN: Int = DEFAULT_LOG_N): String {
        require(rawKey.size == KEY_BYTES) { "raw key must be $KEY_BYTES bytes" }
        require(logN in 1..MAX_LOG_N) { "log_n out of range: $logN" }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val payload = encryptToPayload(rawKey, password, logN, salt, nonce)
        return Bech32Utils.encodeGeneric(HRP, payload)
    }

    /**
     * Structural check without the KDF: does [ncryptsec] parse as a v2
     * ncryptsec payload? Used to gate a password prompt before the
     * (expensive) decrypt is attempted.
     */
    fun isValidNcryptsec(ncryptsec: String): Boolean =
        runCatching { parse(ncryptsec) }.isSuccess

    /**
     * Decrypt an `ncryptsec1…` string back to the RAW 32-byte private key.
     *
     * @throws WrongPasswordException wrong password or tampered ciphertext
     * @throws MalformedNcryptsecException structural problems (bad HRP,
     * checksum, version, length, or log_n)
     */
    fun decrypt(ncryptsec: String, password: String): ByteArray {
        val payload = parse(ncryptsec)
        val nonce = payload.copyOfRange(2 + SALT_BYTES, 2 + SALT_BYTES + NONCE_BYTES)
        val ciphertext = payload.copyOfRange(PAYLOAD_BYTES - KEY_BYTES - TAG_BYTES, PAYLOAD_BYTES)

        val sym = scryptKey(password, payload.copyOfRange(2, 2 + SALT_BYTES), payload[1].toInt() and 0xFF)
        try {
            return aead(encrypt = false, sym, nonce, payload[2 + SALT_BYTES + NONCE_BYTES], ciphertext)
        } catch (e: Exception) {
            throw WrongPasswordException()
        } finally {
            sym.fill(0)
        }
    }

    /** Validate and decode the 91-byte payload (everything except the KDF/AEAD). */
    private fun parse(ncryptsec: String): ByteArray {
        val payload = try {
            val (hrp, data) = Bech32Utils.decodeGeneric(ncryptsec.trim())
            if (hrp != HRP) throw MalformedNcryptsecException("Expected HRP '$HRP', got '$hrp'")
            data
        } catch (e: IllegalArgumentException) {
            throw MalformedNcryptsecException("Invalid bech32: ${e.message}")
        }
        if (payload.size != PAYLOAD_BYTES) {
            throw MalformedNcryptsecException("Unexpected payload length ${payload.size} (want $PAYLOAD_BYTES)")
        }
        if (payload[0] != VERSION) {
            throw MalformedNcryptsecException("Unsupported version ${payload[0]}")
        }
        val logN = payload[1].toInt() and 0xFF
        if (logN !in 1..MAX_LOG_N) {
            throw MalformedNcryptsecException("log_n out of range: $logN")
        }
        return payload
    }

    private fun encryptToPayload(
        rawKey: ByteArray,
        password: String,
        logN: Int,
        salt: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val sym = scryptKey(password, salt, logN)
        try {
            val ct = aead(encrypt = true, sym, nonce, KEY_SECURITY, rawKey)
            return ByteArray(PAYLOAD_BYTES).also {
                it[0] = VERSION
                it[1] = logN.toByte()
                salt.copyInto(it, 2)
                nonce.copyInto(it, 2 + SALT_BYTES)
                it[2 + SALT_BYTES + NONCE_BYTES] = KEY_SECURITY
                ct.copyInto(it, 2 + SALT_BYTES + NONCE_BYTES + 1)
            }
        } finally {
            sym.fill(0)
        }
    }

    /** scrypt(NFKC(password), salt16, N=2^logN, r=8, p=1, out=32). */
    private fun scryptKey(password: String, salt: ByteArray, logN: Int): ByteArray =
        SCrypt.generate(nfkc(password), salt, 1 shl logN, 8, 1, KEY_BYTES)

    private fun nfkc(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    /**
     * draft-irtf-cfrg-xchacha XChaCha20-Poly1305: HChaCha20 subkey from
     * nonce[0..15], then ChaCha20-Poly1305 with `0x00000000 ‖ nonce[16..24]`.
     */
    private fun aead(
        encrypt: Boolean,
        key: ByteArray,
        nonce24: ByteArray,
        associatedData: Byte,
        input: ByteArray
    ): ByteArray {
        val subkey = hchacha20(key, nonce24.copyOfRange(0, 16))
        try {
            val nonce12 = ByteArray(12)
            nonce24.copyOfRange(16, 24).copyInto(nonce12, 4)
            val aeadCipher = ChaCha20Poly1305()
            aeadCipher.init(
                encrypt,
                AEADParameters(
                    KeyParameter(subkey),
                    TAG_BYTES * 8,
                    nonce12,
                    byteArrayOf(associatedData)
                )
            )
            val out = ByteArray(input.size + if (encrypt) TAG_BYTES else 0)
            var written = aeadCipher.processBytes(input, 0, input.size, out, 0)
            written += aeadCipher.doFinal(out, written)
            return out.copyOf(written)
        } finally {
            subkey.fill(0)
        }
    }

    // ---- HChaCha20 (RFC draft, §2.3) --------------------------------------

    private const val SIGMA_0 = 0x61707865
    private const val SIGMA_1 = 0x3320646e
    private const val SIGMA_2 = 0x79622d32
    private const val SIGMA_3 = 0x6b206574

    /** Derives the 32-byte subkey — 20 rounds, no final state addition. */
    private fun hchacha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "key must be 32 bytes" }
        require(nonce16.size == 16) { "nonce must be 16 bytes" }
        val x = IntArray(16)
        x[0] = SIGMA_0; x[1] = SIGMA_1; x[2] = SIGMA_2; x[3] = SIGMA_3
        for (i in 0 until 8) x[4 + i] = leInt(key, i * 4)
        for (i in 0 until 4) x[12 + i] = leInt(nonce16, i * 4)
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        for (i in 0 until 4) leBytes(x[i], out, i * 4)
        for (i in 0 until 4) leBytes(x[12 + i], out, 16 + i * 4)
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leBytes(value: Int, out: ByteArray, offset: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
