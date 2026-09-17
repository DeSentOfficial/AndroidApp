package xyz.desent.crypto

import nostr.util.NostrUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Key-rotation proof contract (KEY_ROTATION.md §2 / ANDROID_KEY_ROTATION.md
 * §3): a BIP-340 signature by the NEW key over
 * `sha256("desent-key-rotate-v1:" + nonce)`, hex-encoded — the server
 * verifies exactly like a NIP-01 event signature.
 */
class SchnorrProofTest {

    @Test
    fun `digest matches the wire prefix construction`() {
        val expected = NostrUtil.sha256("desent-key-rotate-v1:abc".toByteArray(Charsets.UTF_8))
        val actual = SchnorrProof.keyRotateDigest("abc")
        assertTrue(expected.contentEquals(actual))
        // matches tests/test_key_rotation.py's vector nonce
        assertEquals(32, actual.size)
    }

    @Test
    fun `proof verifies against the new public key`() {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = Nip44Encryption.derivePublicKey(priv)

        val proof = SchnorrProof.keyRotateProof("nonce-123", priv)

        assertEquals(128, proof.length) // 64-byte schnorr sig, hex
        val ok = NostrEventCrypto.verifyDigest(
            SchnorrProof.keyRotateDigest("nonce-123"),
            pub,
            Nip44Encryption.hexToBytes(proof)
        )
        assertTrue(ok)
    }

    @Test
    fun `proof fails against a different digest or key`() {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val otherPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub = Nip44Encryption.derivePublicKey(priv)
        val otherPub = Nip44Encryption.derivePublicKey(otherPriv)

        val proof = SchnorrProof.keyRotateProof("nonce-123", priv)
        val sig = Nip44Encryption.hexToBytes(proof)

        // Wrong nonce → digest mismatch.
        assertEquals(
            false,
            NostrEventCrypto.verifyDigest(
                SchnorrProof.keyRotateDigest("nonce-124"), pub, sig
            )
        )
        // Wrong key.
        assertEquals(
            false,
            NostrEventCrypto.verifyDigest(
                SchnorrProof.keyRotateDigest("nonce-123"), otherPub, sig
            )
        )
    }

    @Test
    fun `different nonces produce different digests`() {
        assertNotEquals(
            NostrUtil.bytesToHex(SchnorrProof.keyRotateDigest("a")),
            NostrUtil.bytesToHex(SchnorrProof.keyRotateDigest("b"))
        )
    }
}
