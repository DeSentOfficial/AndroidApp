package xyz.desent.domain.repository

import kotlinx.coroutines.flow.StateFlow
import xyz.desent.domain.model.PgpDecryptFailure
import xyz.desent.domain.model.PgpKeyInfo
import xyz.desent.domain.model.PgpKeyState

/**
 * User-controlled OpenPGP key custody + mail encryption
 * (refs/FROM_email.desent.xyz/ANDROID_PGP.md).
 *
 * The private key lives ONLY as a kind-30078 `desent:pgp` event
 * (NIP-44-to-self) plus a Keystore-backed device mirror; it is NEVER sent
 * to any HTTP endpoint. Implementations must fail closed when the relay's
 * `pgp_enabled` gate is off.
 */
interface PgpKeyRepository {

    /** Live key state of the active account (mirror + 30078 sync). */
    val keyState: StateFlow<PgpKeyState>

    /**
     * Generate a fresh passphraseless curve25519 key (UID = "Name <addr>"),
     * persist it to 30078 + encrypted mirror, and publish the public half
     * to `PUT /api/pgp/key` for WKD discovery.
     */
    suspend fun generateKey(displayName: String, email: String): Result<PgpKeyInfo>

    /**
     * Import an armored private key; a passphrase-protected key is unlocked
     * once with [passphrase] and re-armed passphraseless before persisting.
     */
    suspend fun importKey(secretArmored: String, passphrase: String?): Result<PgpKeyInfo>

    /**
     * Remove the key: publish the 30078 empty-content tombstone AND
     * `DELETE /api/pgp/key` (both — the tombstone deletes the stored copy,
     * the DELETE unpublishes WKD).
     */
    suspend fun removeKey(): Result<Unit>

    /** WKD lookup via the relay proxy — does this recipient publish a key? */
    suspend fun hasRecipientKey(email: String): Boolean

    /**
     * Discover the recipient's key (WKD proxy) and encrypt [plaintext] to
     * it. Returns the armored PGP MESSAGE for the outbound rumor content.
     */
    suspend fun encryptForRecipient(email: String, plaintext: String): Result<String>

    /**
     * Decrypt an inbound armored PGP MESSAGE with this account's key.
     * Fails with [PgpDecryptFailure] semantics mapped onto the exception
     * message (no key / wrong key).
     */
    suspend fun decryptMessage(armor: String): Result<ByteArray>

    /** The relay-registered key (`GET /api/pgp/key`), or null for 404. */
    suspend fun fetchPublishedKey(): Result<PgpKeyInfo?>
}
