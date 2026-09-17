package xyz.desent.domain.usecase

import xyz.desent.domain.model.Email
import xyz.desent.domain.repository.SpamFilterRepository

/**
 * Record a user training signal ("Mark as spam" / "Not spam") and persist the
 * resulting flag. Drives the Layer 3 Bayesian classifier.
 */
class TrainSpamUseCase(
    private val spamFilterRepository: SpamFilterRepository
) {
    suspend operator fun invoke(email: Email, isSpam: Boolean) {
        spamFilterRepository.trainAndMark(email, isSpam)
    }
}
