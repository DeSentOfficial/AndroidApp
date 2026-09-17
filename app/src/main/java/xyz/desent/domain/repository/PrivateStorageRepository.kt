package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import xyz.desent.domain.model.PgpKeyPayload
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateNote
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.UserFile

/**
 * Private encrypted storage backed by NIP-78 kind 30078 events
 * (refs/PRIVATE_STORAGE_PROTOCOL.md). Content is NIP-44 self-encrypted; the
 * relay force-scopes reads to the authenticated pubkey so other users cannot
 * see even metadata about someone else's storage.
 */
interface PrivateStorageRepository {

    fun observeNotes(ownerNpub: String): Flow<List<PrivateNote>>
    fun observeNote(ownerNpub: String, id: String): Flow<PrivateNote?>

    /** Replacing save: publishes a kind-30078 event with `d = "desent:note:<id>"`. */
    suspend fun saveNote(note: PrivateNote): Result<Unit>

    /** Publishes an empty-content tombstone (relay hard-deletes the prior version). */
    suspend fun deleteNote(ownerNpub: String, id: String): Result<Unit>

    fun observeContacts(ownerNpub: String): Flow<List<PrivateContact>>

    /** Replacing save: publishes a kind-30078 event with `d = "desent:contacts"`. */
    suspend fun saveContacts(ownerNpub: String, contacts: List<PrivateContact>): Result<Unit>

    // ------------------------------------------------------------------
    // User files (`desent:file:<sha256>`)
    // ------------------------------------------------------------------

    /**
     * Observe the Room-cached user-uploaded encrypted files for [ownerNpub].
     * Backed by one kind-30078 event per file; the file's AES key travels only
     * inside the NIP-44-encrypted payload.
     */
    fun observeUserFiles(ownerNpub: String): Flow<List<UserFile>>

    /** Replacing save: publishes a kind-30078 event with `d = "desent:file:<sha256>"`. */
    suspend fun saveUserFile(file: UserFile): Result<Unit>

    /** Publishes an empty-content tombstone (relay hard-deletes the prior version). */
    suspend fun deleteUserFile(ownerNpub: String, sha256: String): Result<Unit>

    /**
     * Publish the spam-filter config (and personal block/allow rules) as a
     * self-encrypted kind-30078 with `d = "desent:spam-settings"`. Drives
     * cross-device config sync; the inbound handler applies remote versions
     * under last-write-wins.
     */
    suspend fun saveSpamSettings(
        config: SpamFilterConfig,
        personal: xyz.desent.domain.model.PersonalSpamRules = xyz.desent.domain.model.PersonalSpamRules.EMPTY
    ): Result<Unit>

    /**
     * Build and publish a sharded snapshot of the local Bayesian token corpus
     * under `desent:spam-tokens:<i>` (+ a `desent:spam-tokens:manifest` index).
     * Merges across devices use element-wise `max` of spam/ham counts (a
     * convergent state-CRDT). No relay round-trip when there is no local delta
     * since the last snapshot unless [force] is true.
     */
    suspend fun snapshotAndPublishTokens(force: Boolean = false): Result<Unit>

    /** Open / refresh the relay subscription for the active user's 30078 events. */
    suspend fun subscribeToOwnPrivateStorage(): Result<Unit>

    // ------------------------------------------------------------------
    // PGP key (`desent:pgp`)
    // ------------------------------------------------------------------

    /**
     * Publish the PGP key payload as a self-encrypted kind-30078 with
     * `d = "desent:pgp"` — the roaming copy of the user's OpenPGP identity
     * (ANDROID_PGP.md §2.1). Inbound events (own echo or another device)
     * surface on [pgpKeyFlow].
     */
    suspend fun savePgpKey(payload: PgpKeyPayload): Result<Unit>

    /** Publish the empty-content tombstone for `desent:pgp` (relay hard-deletes). */
    suspend fun removePgpKey(): Result<Unit>

    /**
     * Latest decrypted `desent:pgp` payload for the active account, as seen
     * on the own-30078 subscription (LWW by `created_at`; a tombstone
     * resets it to null). Room is not involved — the device mirror lives in
     * Keystore-backed encrypted preferences.
     */
    val pgpKeyFlow: StateFlow<PgpKeyPayload?>
}

