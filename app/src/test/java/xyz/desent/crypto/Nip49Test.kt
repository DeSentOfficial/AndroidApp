package xyz.desent.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-49 acceptance criteria (refs/FROM_email.desent.xyz/
 * CUSTODIAL_ACCOUNTS.md §1): the implementation must decrypt the official
 * NIP-49 test vector and round-trip its own encryption.
 */
class Nip49Test {

    // Official NIP-49 vector (password "nostr", log_n = 16).
    private val vectorNcryptsec =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd" +
            "67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r" +
            "89qk5z2zxfmu5gv8th8wclt0h4p"
    private val vectorPassword = "nostr"
    private val vectorKeyHex =
        "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"

    @Test
    fun officialVector_decrypts() {
        val key = Nip49.decrypt(vectorNcryptsec, vectorPassword)
        assertEquals(vectorKeyHex, key.toHex())
    }

    @Test
    fun officialVector_wrongPasswordThrows() {
        assertThrows(Nip49.WrongPasswordException::class.java) {
            Nip49.decrypt(vectorNcryptsec, "wrong password")
        }
    }

    @Test
    fun roundTrip_randomKey() {
        val key = ByteArray(32) { (it * 11 + 3).toByte() }
        val ncryptsec = Nip49.encrypt(key, "a decent passphrase")
        assertTrue(ncryptsec.startsWith("ncryptsec1"))
        assertArrayEquals(key, Nip49.decrypt(ncryptsec, "a decent passphrase"))
    }

    @Test
    fun roundTrip_producesFreshCiphertexts() {
        val key = ByteArray(32) { (it * 5).toByte() }
        val a = Nip49.encrypt(key, "same password")
        val b = Nip49.encrypt(key, "same password")
        assertNotEquals(a, b) // fresh salt + nonce every time
    }

    @Test
    fun roundTrip_nfkcNormalizesPassword() {
        val key = ByteArray(32) { (it * 9).toByte() }
        // U+FB01 (ﬁ) NFKC-normalizes to "fi": the two must be interchangeable.
        val ncryptsec = Nip49.encrypt(key, "ﬁle")
        assertArrayEquals(key, Nip49.decrypt(ncryptsec, "file"))
    }

    @Test
    fun tamperedCiphertextThrows() {
        val key = ByteArray(32) { (it * 13).toByte() }
        val ncryptsec = Nip49.encrypt(key, "pass")
        val (hrp, payload) = Bech32Utils.decodeGeneric(ncryptsec)
        payload[payload.size - 2] = (payload[payload.size - 2].toInt() xor 0x01).toByte()
        val tampered = Bech32Utils.encodeGeneric(hrp, payload)
        assertThrows(Nip49.WrongPasswordException::class.java) {
            Nip49.decrypt(tampered, "pass")
        }
    }

    @Test
    fun malformedInputsThrow() {
        // Wrong HRP.
        val nsec = Bech32Utils.encodeGeneric("nsec", ByteArray(Nip49.PAYLOAD_BYTES))
        assertThrows(Nip49.MalformedNcryptsecException::class.java) {
            Nip49.decrypt(nsec, "pass")
        }
        // Truncated payload.
        val (hrp, payload) = Bech32Utils.decodeGeneric(
            Nip49.encrypt(ByteArray(32), "pass")
        )
        val short = Bech32Utils.encodeGeneric(hrp, payload.copyOf(payload.size - 1))
        assertThrows(Nip49.MalformedNcryptsecException::class.java) {
            Nip49.decrypt(short, "pass")
        }
        // Bad version byte (0x02 → 0x03).
        payload[0] = 0x03
        val badVersion = Bech32Utils.encodeGeneric(hrp, payload)
        assertThrows(Nip49.MalformedNcryptsecException::class.java) {
            Nip49.decrypt(badVersion, "pass")
        }
        // Absurd log_n.
        payload[0] = Nip49.VERSION
        payload[1] = 0x30
        val absurdLogN = Bech32Utils.encodeGeneric(hrp, payload)
        assertThrows(Nip49.MalformedNcryptsecException::class.java) {
            Nip49.decrypt(absurdLogN, "pass")
        }
    }

    @Test
    fun isValidNcryptsec_acceptsVector_andStructuralVariants() {
        assertTrue(Nip49.isValidNcryptsec(vectorNcryptsec))
        // Leading/trailing whitespace is tolerated (decrypt trims too).
        assertTrue(Nip49.isValidNcryptsec("  $vectorNcryptsec\n"))

        // Same structural rejections as decrypt, but without the KDF.
        assertFalse(Nip49.isValidNcryptsec("ncryptsec1garbage"))
        assertFalse(Nip49.isValidNcryptsec("not-a-key"))
        val nsecShaped = Bech32Utils.encodeGeneric("nsec", ByteArray(Nip49.PAYLOAD_BYTES))
        assertFalse(Nip49.isValidNcryptsec(nsecShaped))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
