package xyz.desent.domain.usecase

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.MailboxConfig
import xyz.desent.domain.repository.MailboxConfigRepository

/**
 * Read / publish the NIP-EMAIL Mailbox Configuration (kind 35050): retention
 * TTL + private rules (blocked senders, forwarding targets, alias preference).
 */
class MailboxConfigUseCase(
    private val repository: MailboxConfigRepository
) {
    fun observe(ownerNpub: String): Flow<MailboxConfig?> = repository.observe(ownerNpub)

    suspend fun get(ownerNpub: String): MailboxConfig? = repository.get(ownerNpub)

    suspend fun save(ownerNpub: String, config: MailboxConfig): Result<Unit> =
        repository.save(ownerNpub, config)

    suspend fun refresh(): Result<Unit> = repository.refresh()
}
