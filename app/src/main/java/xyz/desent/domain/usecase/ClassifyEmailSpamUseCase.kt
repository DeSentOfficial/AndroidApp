package xyz.desent.domain.usecase

import xyz.desent.domain.model.Email
import xyz.desent.domain.model.SpamVerdict
import xyz.desent.domain.repository.SpamFilterRepository

/** Classify an email against the on-device filter (see refs/SPAM_FILTER_REFERENCE.md). */
class ClassifyEmailSpamUseCase(
    private val spamFilterRepository: SpamFilterRepository
) {
    suspend operator fun invoke(email: Email): SpamVerdict = spamFilterRepository.classify(email)
}
