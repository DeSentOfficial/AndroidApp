package xyz.desent.domain.usecase

import xyz.desent.domain.repository.SpamFilterRepository

/** Fetch the latest NIP-51 spam blocklist from the trusted publisher. */
class SyncSpamListUseCase(
    private val spamFilterRepository: SpamFilterRepository
) {
    suspend operator fun invoke(): Boolean = spamFilterRepository.syncManifest()
}
