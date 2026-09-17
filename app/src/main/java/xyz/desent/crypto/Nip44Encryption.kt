package xyz.desent.crypto

import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * NIP-44 v2 Encrypted Payloads (spec-compliant).
 *
 * Spec: https://github.com/nostr-protocol/nips/blob/master/44.md
 *
 *  - Key agreement : secp256k1 ECDH (unhashed 32-byte x-coordinate)
 *  - Key derivation: HKDF-SHA256
 *  - Symmetric    : ChaCha20 (RFC 8439, counter starts at 0)
 *  - Authentication: HMAC-SHA256 over (nonce || ciphertext)
 *  - Encoding     : base64 (RFC 4648, with padding)
 *
 * The payload layout is: base64(version(1) | nonce(32) | ciphertext | mac(32)).
 */
object Nip44Encryption {

    private const val VERSION: Byte = 0x02
    private const val NONCE_SIZE = 32
    private const val MAC_SIZE = 32
    private const val MIN_PAYLOAD_LEN = 99          // decoded bytes
    private const val MIN_PAYLOAD_BASE64_LEN = 132  // encoded chars
    private const val CONVERSATION_KEY_LEN = 32
    private const val MESSAGE_KEYS_LEN = 76
    private const val CHACHA_KEY_LEN = 32
    private const val CHACHA_NONCE_LEN = 12
    private const val HMAC_KEY_LEN = 32
    private const val HKDF_SALT = "nip44-v2"
    private const val MIN_PLAINTEXT_SIZE = 1
    private const val EXTENDED_PREFIX_THRESHOLD = 65536

    private val secpParams = CustomNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(secpParams.curve, secpParams.g, secpParams.n, secpParams.h)

    data class MessageKeys(
        val chachaKey: ByteArray,
        val chachaNonce: ByteArray,
        val hmacKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MessageKeys) return false
            return chachaKey.contentEquals(other.chachaKey) &&
                chachaNonce.contentEquals(other.chachaNonce) &&
                hmacKey.contentEquals(other.hmacKey)
        }

        override fun hashCode(): Int {
            var r = chachaKey.contentHashCode()
            r = 31 * r + chachaNonce.contentHashCode()
            r = 31 * r + hmacKey.contentHashCode()
            return r
        }
    }

    /**
     * Long-term conversation key shared between two users: conv(a, B) == conv(b, A).
     *
     *  - shared_x = secp256k1_ecdh(privateKey, publicKey)  (unhashed 32-byte x-coordinate)
     *  - conversation_key = HKDF-Extract(IKM=shared_x, salt="nip44-v2")
     *
     * @param privateKey 32-byte private key
     * @param publicKey  32-byte x-only public key
     */
    fun getConversationKey(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == CONVERSATION_KEY_LEN) { "Private key must be 32 bytes" }
        require(publicKey.size == CONVERSATION_KEY_LEN) { "Public key must be 32 bytes (x-only)" }

        val sharedX = computeEcdh(privateKey, publicKey)
        return hkdfExtract(HKDF_SALT.toByteArray(Charsets.UTF_8), sharedX)
    }

    /**
     * Per-message keys derived from the conversation key and a 32-byte nonce.
     * HKDF-Expand(PRK=conversationKey, info=nonce, L=76) -> chacha_key | chacha_nonce | hmac_key.
     */
    fun getMessageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        require(conversationKey.size == CONVERSATION_KEY_LEN) { "Conversation key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "Nonce must be 32 bytes" }

        val keys = hkdfExpand(conversationKey, nonce, MESSAGE_KEYS_LEN)
        return MessageKeys(
            chachaKey = keys.copyOfRange(0, CHACHA_KEY_LEN),
            chachaNonce = keys.copyOfRange(CHACHA_KEY_LEN, CHACHA_KEY_LEN + CHACHA_NONCE_LEN),
            hmacKey = keys.copyOfRange(CHACHA_KEY_LEN + CHACHA_NONCE_LEN, MESSAGE_KEYS_LEN)
        )
    }

    /**
     * Encrypt plaintext to a NIP-44 v2 base64 payload string.
     */
    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray? = null): String {
        require(conversationKey.size == CONVERSATION_KEY_LEN) { "Conversation key must be 32 bytes" }

        val n = if (nonce != null) {
            require(nonce.size == NONCE_SIZE) { "Nonce must be 32 bytes" }
            nonce
        } else {
            ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        }

        val keys = getMessageKeys(conversationKey, n)
        val padded = pad(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = chacha20(padded, keys.chachaKey, keys.chachaNonce)
        val mac = hmacAad(keys.hmacKey, ciphertext, n)

        val payload = ByteArray(1 + NONCE_SIZE + ciphertext.size + MAC_SIZE)
        payload[0] = VERSION
        System.arraycopy(n, 0, payload, 1, NONCE_SIZE)
        System.arraycopy(ciphertext, 0, payload, 1 + NONCE_SIZE, ciphertext.size)
        System.arraycopy(mac, 0, payload, 1 + NONCE_SIZE + ciphertext.size, MAC_SIZE)

        return Base64.getEncoder().encodeToString(payload)
    }

    /**
     * Decrypt a NIP-44 v2 base64 payload string to plaintext.
     */
    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(conversationKey.size == CONVERSATION_KEY_LEN) { "Conversation key must be 32 bytes" }
        require(payload.isNotEmpty()) { "Payload is empty" }
        require(payload[0] != '#') { "Unknown version: non-base64 encoding not supported" }
        require(payload.length >= MIN_PAYLOAD_BASE64_LEN) { "Invalid payload: too short" }

        val data = Base64.getDecoder().decode(payload)
        require(data.size >= MIN_PAYLOAD_LEN) { "Invalid payload: decoded data too short" }
        require(data[0] == VERSION) { "Unknown version: ${data[0]}" }

        val nonce = data.copyOfRange(1, 1 + NONCE_SIZE)
        val ciphertext = data.copyOfRange(1 + NONCE_SIZE, data.size - MAC_SIZE)
        val mac = data.copyOfRange(data.size - MAC_SIZE, data.size)

        val keys = getMessageKeys(conversationKey, nonce)
        val calculatedMac = hmacAad(keys.hmacKey, ciphertext, nonce)
        if (!constantTimeEquals(calculatedMac, mac)) {
            throw SecurityException("Invalid MAC")
        }

        val padded = chacha20(ciphertext, keys.chachaKey, keys.chachaNonce)
        return String(unpad(padded), Charsets.UTF_8)
    }

    /**
     * Derive the 32-byte x-only public key for a private key (secp256k1).
     */
    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == CONVERSATION_KEY_LEN) { "Private key must be 32 bytes" }
        val point = domain.g.multiply(BigInteger(1, privateKey)).normalize()
        return bigIntegerTo32Bytes(point.affineXCoord.toBigInteger())
    }

    // ------------------------------------------------------------------
    // secp256k1 ECDH
    // ------------------------------------------------------------------

    private fun computeEcdh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        // Lift x-only (32-byte) pubkey to a compressed SEC1 point using even-y (BIP340 convention).
        val compressed = ByteArray(33)
        compressed[0] = 0x02
        System.arraycopy(publicKey, 0, compressed, 1, CONVERSATION_KEY_LEN)

        val point = domain.curve.decodePoint(compressed)
        val privParams = ECPrivateKeyParameters(BigInteger(1, privateKey), domain)
        val pubParams = ECPublicKeyParameters(point, domain)

        val agreement = ECDHBasicAgreement()
        agreement.init(privParams)
        val sharedX = agreement.calculateAgreement(pubParams)
        return bigIntegerTo32Bytes(sharedX)
    }

    private fun bigIntegerTo32Bytes(value: BigInteger): ByteArray {
        val out = ByteArray(CONVERSATION_KEY_LEN)
        val bytes = value.toByteArray()
        val length = min(CONVERSATION_KEY_LEN, bytes.size)
        val srcPos = max(0, bytes.size - CONVERSATION_KEY_LEN)
        System.arraycopy(bytes, srcPos, out, CONVERSATION_KEY_LEN - length, length)
        return out
    }

    // ------------------------------------------------------------------
    // HKDF-SHA256 (RFC 5869)
    // ------------------------------------------------------------------

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val output = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(counter.toByte()))
            t = mac.doFinal()
            val take = min(t.size, length - pos)
            System.arraycopy(t, 0, output, pos, take)
            pos += take
            counter++
        }
        return output
    }

    // ------------------------------------------------------------------
    // ChaCha20 (RFC 8439)
    // ------------------------------------------------------------------

    private fun chacha20(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = ChaCha7539Engine()
        cipher.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val output = ByteArray(data.size)
        cipher.processBytes(data, 0, data.size, output, 0)
        return output
    }

    // ------------------------------------------------------------------
    // HMAC-SHA256 (with AAD)
    // ------------------------------------------------------------------

    private fun hmacAad(key: ByteArray, message: ByteArray, aad: ByteArray): ByteArray {
        require(aad.size == NONCE_SIZE) { "AAD must be 32 bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(aad)
        return mac.doFinal(message)
    }

    // ------------------------------------------------------------------
    // Padding
    // ------------------------------------------------------------------

    private fun calcPaddedLen(unpaddedLen: Int): Int {
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(unpaddedLen - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    private fun pad(unpadded: ByteArray): ByteArray {
        val unpaddedLen = unpadded.size
        require(unpaddedLen >= MIN_PLAINTEXT_SIZE) { "Invalid plaintext length" }

        val prefix: ByteArray
        if (unpaddedLen >= EXTENDED_PREFIX_THRESHOLD) {
            prefix = ByteArray(6)
            // first two bytes are already 0x00; then a big-endian u32 length
            writeU32Be(prefix, unpaddedLen.toLong(), 2)
        } else {
            prefix = ByteArray(2)
            writeU16Be(prefix, unpaddedLen, 0)
        }

        val suffixLen = calcPaddedLen(unpaddedLen) - unpaddedLen
        val result = ByteArray(prefix.size + unpaddedLen + suffixLen)
        System.arraycopy(prefix, 0, result, 0, prefix.size)
        System.arraycopy(unpadded, 0, result, prefix.size, unpaddedLen)
        return result
    }

    private fun unpad(padded: ByteArray): ByteArray {
        val firstTwo = readU16Be(padded, 0)
        val prefixLen: Int
        val unpaddedLen: Int
        if (firstTwo == 0) {
            val len = readU32Be(padded, 2)
            require(len >= EXTENDED_PREFIX_THRESHOLD) { "Invalid padding" }
            unpaddedLen = len.toInt()
            prefixLen = 6
        } else {
            unpaddedLen = firstTwo
            prefixLen = 2
        }

        require(unpaddedLen > 0) { "Invalid padding: zero length" }
        require(padded.size == prefixLen + calcPaddedLen(unpaddedLen)) { "Invalid padding: bad total length" }

        val unpadded = padded.copyOfRange(prefixLen, prefixLen + unpaddedLen)
        require(unpadded.size == unpaddedLen) { "Invalid padding: length mismatch" }
        return unpadded
    }

    private fun writeU16Be(target: ByteArray, value: Int, offset: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32Be(target: ByteArray, value: Long, offset: Int) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readU16Be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readU32Be(data: ByteArray, offset: Int): Long =
        ((data[offset].toInt() and 0xFF).toLong() shl 24) or
            ((data[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((data[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (data[offset + 3].toInt() and 0xFF).toLong()

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    // ------------------------------------------------------------------
    // Hex helpers (used by callers that work with hex-encoded keys)
    // ------------------------------------------------------------------

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) or Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
