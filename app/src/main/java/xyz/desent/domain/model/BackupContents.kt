package xyz.desent.domain.model

import kotlinx.serialization.Serializable

/**
 * Plaintext payload encrypted inside a `DSBK1` backup envelope (see
 * [xyz.desent.crypto.BackupEnvelope]). Holds every selected account's key
 * material plus the relay list, so a fresh install can be fully restored from
 * a single password-protected blob.
 */
@Serializable
data class BackupContents(
    /** Backup-format version (independent of the envelope version). */
    val version: Int = 1,
    val createdAt: Long,
    val accounts: List<BackupAccount>,
    /** Always captured, regardless of which accounts were selected. */
    val relays: List<BackupRelay>,
)

@Serializable
data class BackupAccount(
    val npub: String,
    val nsec: String,
    val displayName: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val requiresBiometrics: Boolean = false,
)

@Serializable
data class BackupRelay(
    val url: String,
    val isWrite: Boolean = false,
)
