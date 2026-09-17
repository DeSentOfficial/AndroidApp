package xyz.desent.domain.repository

import xyz.desent.domain.model.BackupContents

/**
 * Encrypted account-backup blob read/write. The blob is a `DSBK1` envelope
 * (scrypt + AES-256-GCM) produced/consumed by [xyz.desent.crypto.BackupEnvelope];
 * this repository owns gathering the key material for [export] and the pure
 * decrypt for [decrypt]. Persisting restored accounts/relays back into the app
 * is orchestrated by `ImportBackupUseCase`, which calls [decrypt] and then
 * routes each account through the normal login/add-account plumbing.
 */
interface KeyBackupRepository {

    /**
     * Build an encrypted backup blob containing the nsec + profile metadata for
     * [selectedNpubs] plus the full persistent relay list, encrypted under
     * [passphrase].
     */
    suspend fun export(selectedNpubs: Set<String>, passphrase: String): kotlin.Result<ByteArray>

    /** Decrypt and deserialize a blob, returning its contents without persisting them. */
    suspend fun decrypt(blob: ByteArray, passphrase: String): kotlin.Result<BackupContents>
}
