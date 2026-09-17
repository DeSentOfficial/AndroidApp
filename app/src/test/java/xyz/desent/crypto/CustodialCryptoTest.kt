package xyz.desent.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * v2 vectors: refs/FROM_email.desent.xyz/CUSTODIAL_ACCOUNTS.md §1 (the NIP-49
 * half is covered by [Nip49Test]; here we pin the argon2id verifier and the
 * envelope shape). v1 vectors cover the legacy decrypt path kept for
 * pre-2026-08-28 accounts (verified server-side against argon2-cffi).
 */
class CustodialCryptoTest {

    private val vectorPassword = "correct horse battery staple"
    private val vectorSaltV1 = CustodialCrypto.SALT_PREFIX_V1.toByteArray(Charsets.UTF_8) +
        ByteArray(16) { it.toByte() } // 0x00..0x0f

    private val vectorMaster =
        "12dba6ab561f838aa39e082d30603c6d943bc2d61d5620ce39809f1f33cdf7c5" +
            "8b1243a4fd9fb8dbcd04cde80fc9c4cf1c9be046c7bffc7b111ca3a3a5dd835f"

    private val vectorVerifierV1 =
        "8b1243a4fd9fb8dbcd04cde80fc9c4cf1c9be046c7bffc7b111ca3a3a5dd835f"

    // Cheap params for round-trip tests so the suite stays fast; the spec
    // vector below necessarily runs the real 64 MiB / 3-pass parameters.
    private val fastParams = CustodialCrypto.KdfParams(m = 1024, t = 1, p = 1)

    // A structurally valid bech32 nsec (the NIP-49 vector key) — v2 blobs
    // decode the nsec to encrypt the RAW key bytes.
    private val testNsec = Bech32Utils.hexToNsec(
        "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"
    )

    // ---- v2 verifier -------------------------------------------------------

    @Test
    fun deriveVerifier_isDeterministic_andHexLength() {
        val salt = CustodialCrypto.newSalt()
        val a = CustodialCrypto.deriveVerifier(vectorPassword, salt, fastParams)
        val b = CustodialCrypto.deriveVerifier(vectorPassword, salt, fastParams)
        assertEquals(CustodialCrypto.VERIFIER_HEX_CHARS, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertEquals(a, b)
    }

    @Test
    fun deriveVerifier_nfkcNormalizes() {
        val salt = CustodialCrypto.newSalt()
        // U+FB01 (ﬁ) NFKC-normalizes to "fi".
        assertEquals(
            CustodialCrypto.deriveVerifier("ﬁle", salt, fastParams),
            CustodialCrypto.deriveVerifier("file", salt, fastParams)
        )
    }

    @Test
    fun deriveVerifier_differsFromLegacySplit() {
        // v2 takes the FULL 32-byte output — not the v1 master[32:64] half.
        val master = CustodialCrypto.deriveMaster(vectorPassword, vectorSaltV1, fastParams)
        val saltV2 = CustodialCrypto.newSalt()
        assertNotEquals(
            CustodialCrypto.verifierFromMaster(master),
            CustodialCrypto.deriveVerifier(vectorPassword, saltV2, fastParams)
        )
        CustodialCrypto.zeroize(master)
    }

    @Test
    fun newSalt_hasV2PrefixAndCorrectLength() {
        val salt = CustodialCrypto.newSalt()
        assertEquals(CustodialCrypto.SALT_TOTAL_BYTES, salt.size)
        assertEquals(
            CustodialCrypto.SALT_PREFIX_V2,
            String(salt.copyOfRange(0, CustodialCrypto.SALT_PREFIX_V2.length), Charsets.UTF_8)
        )
    }

    @Test
    fun newSalt_isRandom() {
        assertNotEquals(CustodialCrypto.newSalt().toHex(), CustodialCrypto.newSalt().toHex())
    }

    // ---- v2 blob -----------------------------------------------------------

    @Test
    fun buildBlob_emitsV2Envelope() {
        val built = CustodialCrypto.buildBlob(testNsec, vectorPassword)

        assertEquals(2, built.blob.v)
        assertEquals(CustodialCrypto.KDF_ARGON2ID, built.blob.kdf)
        assertEquals(CustodialCrypto.KdfParams(), built.blob.kdfParams)
        assertTrue(built.blob.ncryptsec!!.startsWith("ncryptsec1"))
        assertEquals(null, built.blob.nonce)
        assertEquals(null, built.blob.ct)

        assertEquals(CustodialCrypto.VERIFIER_HEX_CHARS, built.verifier.length)
        val saltBytes = Base64.getDecoder().decode(built.blob.salt)
        assertEquals(CustodialCrypto.SALT_TOTAL_BYTES, saltBytes.size)
        assertEquals(
            CustodialCrypto.SALT_PREFIX_V2,
            String(saltBytes.copyOfRange(0, CustodialCrypto.SALT_PREFIX_V2.length), Charsets.UTF_8)
        )
    }

    @Test
    fun buildBlob_roundTrips() {
        val built = CustodialCrypto.buildBlob(testNsec, vectorPassword)
        assertEquals(testNsec, CustodialCrypto.decryptBlob(built.blob, vectorPassword))
    }

    @Test
    fun buildBlob_wrongPasswordFailsTag() {
        val built = CustodialCrypto.buildBlob(testNsec, vectorPassword)
        assertThrows(CustodialCrypto.WrongPasswordException::class.java) {
            CustodialCrypto.decryptBlob(built.blob, "not the right password at all")
        }
    }

    @Test
    fun v2BlobWithoutNcryptsecIsMalformed() {
        val built = CustodialCrypto.buildBlob(testNsec, vectorPassword)
        assertThrows(IllegalArgumentException::class.java) {
            CustodialCrypto.decryptBlob(built.blob.copy(ncryptsec = null), vectorPassword)
        }
    }

    // ---- legacy v1 (decrypt-only path) --------------------------------------

    @Test
    fun specVector_v1MasterMatches() {
        val master = CustodialCrypto.deriveMaster(vectorPassword, vectorSaltV1)
        assertEquals(vectorMaster, master.toHex())
        CustodialCrypto.zeroize(master)
    }

    @Test
    fun specVector_v1VerifierMatches() {
        val master = CustodialCrypto.deriveMaster(vectorPassword, vectorSaltV1)
        assertEquals(vectorVerifierV1, CustodialCrypto.verifierFromMaster(master))
        CustodialCrypto.zeroize(master)
    }

    @Test
    fun v1Blob_decryptsViaChallengeMasterOrEnvelope() {
        val salt = newSaltV1()
        val master = CustodialCrypto.deriveMaster(vectorPassword, salt, fastParams)
        val blob = buildV1Blob(testNsec, vectorPassword, salt, fastParams)

        assertEquals(testNsec, CustodialCrypto.decryptBlobWithMaster(blob, master))
        assertEquals(testNsec, CustodialCrypto.decryptBlob(blob, vectorPassword))
        CustodialCrypto.zeroize(master)
    }

    @Test
    fun v1Blob_wrongPasswordFails() {
        val blob = buildV1Blob(testNsec, vectorPassword, newSaltV1(), fastParams)
        assertThrows(CustodialCrypto.WrongPasswordException::class.java) {
            CustodialCrypto.decryptBlob(blob, "nope")
        }
    }

    @Test
    fun v1Blob_tamperedCiphertextFails() {
        val blob = buildV1Blob(testNsec, vectorPassword, newSaltV1(), fastParams)
        val ct = Base64.getDecoder().decode(blob.ct!!)
        ct[ct.size - 3] = (ct[ct.size - 3].toInt() xor 0x01).toByte()
        val tampered = blob.copy(ct = Base64.getEncoder().encodeToString(ct))
        assertThrows(CustodialCrypto.WrongPasswordException::class.java) {
            CustodialCrypto.decryptBlob(tampered, vectorPassword)
        }
    }

    @Test
    fun matchesChallenge_detectsSaltAndParamMismatch() {
        val salt = newSaltV1()
        val built = buildV1Blob(testNsec, vectorPassword, salt, fastParams)
        val saltB64 = Base64.getEncoder().encodeToString(salt)

        assertTrue(built.matchesChallenge(saltB64, fastParams))
        val otherSalt = newSaltV1().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(!built.matchesChallenge(Base64.getEncoder().encodeToString(otherSalt), fastParams))
        assertTrue(!built.matchesChallenge(saltB64, fastParams.copy(t = fastParams.t + 1)))
    }

    // ---- helpers ------------------------------------------------------------

    /** Deterministic v1-prefix salt for legacy fixtures. */
    private fun newSaltV1(): ByteArray =
        CustodialCrypto.SALT_PREFIX_V1.toByteArray(Charsets.UTF_8) +
            ByteArray(CustodialCrypto.SALT_RANDOM_BYTES) { (it * 17).toByte() }

    /** Builds a v1 envelope with explicit (possibly non-default) KDF params. */
    private fun buildV1Blob(
        nsec: String,
        password: String,
        salt: ByteArray,
        params: CustodialCrypto.KdfParams
    ): CustodialCrypto.Blob {
        val master = CustodialCrypto.deriveMaster(password, salt, params)
        val key = master.copyOfRange(0, CustodialCrypto.BLOB_KEY_BYTES)
        val nonce = ByteArray(CustodialCrypto.NONCE_BYTES) { (it * 3).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(CustodialCrypto.GCM_TAG_BITS, nonce)
        )
        val ct = cipher.doFinal(nsec.toByteArray(Charsets.UTF_8))
        key.fill(0)
        CustodialCrypto.zeroize(master)
        return CustodialCrypto.Blob(
            v = 1,
            kdfParams = params,
            salt = Base64.getEncoder().encodeToString(salt),
            nonce = Base64.getEncoder().encodeToString(nonce),
            ct = Base64.getEncoder().encodeToString(ct)
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
