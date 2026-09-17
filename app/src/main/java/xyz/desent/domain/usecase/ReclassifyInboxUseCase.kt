package xyz.desent.domain.usecase

import android.util.Log
import kotlinx.coroutines.flow.first
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.domain.repository.SpamFilterRepository

/**
 * One-shot first-run pass that scores every already-stored email for the
 * active account and stamps the verdict (see refs/SPAM_FILTER_REFERENCE.md
 * §"First-run retroactive reclassification").
 *
 * - SYSTEM messages are skipped (exempt from filtering).
 * - Anything the pass flags as spam gets `auto-retro` appended to its reasons
 *   so the user can distinguish it in the Spam screen.
 * - Nothing is deleted. The user reviews the Spam folder manually.
 *
 * Guarded by `SpamFilterRepository.observeRetroFirstRunDone()` so it runs once.
 */
class ReclassifyInboxUseCase(
    private val emailDao: EmailDao,
    private val emailMapper: EmailMapper,
    private val spamFilterRepository: SpamFilterRepository
) {
    companion object {
        private const val TAG = "ReclassifyInbox"
    }

    suspend fun runIfNeeded(recipientNpub: String) {
        if (spamFilterRepository.observeRetroFirstRunDone(recipientNpub).first()) {
            return
        }
        run(recipientNpub)
        spamFilterRepository.setRetroFirstRunDone(recipientNpub, true)
    }

    suspend fun run(recipientNpub: String): Int {
        var moved = 0
        val rows = emailDao.getEmailsForReclassification(recipientNpub)
        for (entity in rows) {
            val email = emailMapper.mapToDomain(entity)
            val verdict = spamFilterRepository.classify(email)
            val reasons = if (verdict.isSpam && "auto-retro" !in verdict.reasons) {
                verdict.reasons + "auto-retro"
            } else {
                verdict.reasons
            }
            emailDao.markSpam(
                id = email.id,
                isSpam = verdict.isSpam,
                score = verdict.score,
                reasons = if (reasons.isEmpty()) null else reasons.joinToString(",")
            )
            if (verdict.isSpam) moved++
        }
        Log.d(TAG, "Retroactive reclassification done: $moved of ${rows.size} messages moved to Spam")
        return moved
    }
}
