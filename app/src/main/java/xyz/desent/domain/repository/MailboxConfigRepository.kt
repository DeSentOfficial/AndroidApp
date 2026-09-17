package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.MailboxConfig

/**
 * NIP-EMAIL Mailbox Configuration (kind 35050): per-user, per-bridge settings.
 * See refs/FromServer/NIP-EMAIL.md § Kind 35050.
 */
interface MailboxConfigRepository {

    /** The cached configuration for [ownerNpub], or null when none published. */
    fun observe(ownerNpub: String): Flow<MailboxConfig?>

    suspend fun get(ownerNpub: String): MailboxConfig?

    /**
     * Publish a new configuration: plaintext `auto_purge_days` policy tag +
     * NIP-44 self-encrypted private rules. Mirrors locally on success so the
     * UI reflects the change immediately (the relay echo confirms it later).
     */
    suspend fun save(ownerNpub: String, config: MailboxConfig): Result<Unit>

    /** Re-subscribe to the config (called on login / account switch). */
    suspend fun refresh(): Result<Unit>
}
