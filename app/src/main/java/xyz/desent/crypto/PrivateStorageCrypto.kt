package xyz.desent.crypto

/**
 * NIP-78 private storage self-encryption helper (see refs/PRIVATE_STORAGE_PROTOCOL.md).
 *
 * Kind 30078 content is NIP-44 v2 encrypted to the user's *self-conversation
 * key* — ECDH between the user's own private key and their own public key.
 * This is the same pattern NIP-51 prescribes for private list entries, and the
 * same ECDH self→self computation that [GiftWrapEncryptionService] performs
 * when a sender is included as a recipient of their own group message.
 *
 * The relay only ever sees opaque ciphertext; content confidentiality holds
 * even against the relay operator. Per-user metadata isolation (another user
 * cannot see your 30078 events at all) is enforced relay-side by rewriting the
 * REQ filter's `authors` to the authenticated pubkey.
 */
object PrivateStorageCrypto {

    /**
     * The self-conversation key: `ECDH(self_priv, self_pub)` pushed through
     * NIP-44's HKDF-Extract. Identical on every device that holds the private
     * key, so ciphertexts sync across a user's devices.
     */
    fun selfConversationKey(privateKey: ByteArray): ByteArray {
        val selfPub = Nip44Encryption.derivePublicKey(privateKey)
        return Nip44Encryption.getConversationKey(privateKey, selfPub)
    }

    /** Encrypt [plaintext] to the user's self-conversation key → base64 payload. */
    fun encryptToSelf(plaintext: String, privateKey: ByteArray): String {
        return Nip44Encryption.encrypt(plaintext, selfConversationKey(privateKey))
    }

    /** Decrypt a self-encrypted base64 payload → plaintext. */
    fun decryptFromSelf(payload: String, privateKey: ByteArray): String {
        return Nip44Encryption.decrypt(payload, selfConversationKey(privateKey))
    }
}
