package xyz.desent.crypto

import nostr.util.NostrUtil

/**
 * Key-rotation proof-of-possession signatures
 * (refs/FROM_email.desent.xyz/KEY_ROTATION.md §2, ANDROID_KEY_ROTATION.md §3).
 *
 * `/api/account/key-rotate` requires a BIP-340 schnorr signature by the NEW
 * private key over `sha256("desent-key-rotate-v1:" + <nonce>)`. The server
 * verifies it exactly like a NIP-01 event signature, so this reuses the same
 * schnorr primitive ([NostrEventCrypto]).
 */
object SchnorrProof {

    const val PROOF_PREFIX = "desent-key-rotate-v1"

    /** The signed digest for a rotation [nonce]: sha256(prefix + ":" + nonce). */
    fun keyRotateDigest(nonce: String): ByteArray =
        NostrUtil.sha256("$PROOF_PREFIX:$nonce".toByteArray(Charsets.UTF_8))

    /**
     * Hex-encoded schnorr proof for [nonce] signed by [newPrivateKey]
     * (raw 32 bytes) — the `new_key_proof` field of the rotate request.
     */
    fun keyRotateProof(nonce: String, newPrivateKey: ByteArray): String =
        NostrEventCrypto.signDigestHex(keyRotateDigest(nonce), newPrivateKey)
}
