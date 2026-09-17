package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.desent.data.local.database.dao.BayesianTokenDao
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.PersonalSpamRuleDao
import xyz.desent.data.local.database.dao.SpamRuleDao
import xyz.desent.data.local.database.entity.PersonalSpamRuleEntity
import xyz.desent.data.local.database.entity.SpamRuleEntity
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.data.spam.SpamClassifier
import xyz.desent.data.spam.SpamListSyncer
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.PersonalSpamRules
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamListManifest
import xyz.desent.domain.model.SpamVerdict
import xyz.desent.domain.repository.SpamFilterRepository

class SpamFilterRepositoryImpl(
    private val spamRuleDao: SpamRuleDao,
    private val personalSpamRuleDao: PersonalSpamRuleDao,
    private val bayesianTokenDao: BayesianTokenDao,
    private val emailDao: EmailDao,
    private val emailMapper: EmailMapper,
    private val classifier: SpamClassifier,
    private val syncer: SpamListSyncer,
    private val preferencesManager: PreferencesManager
) : SpamFilterRepository {

    companion object {
        private const val TAG = "SpamFilterRepo"
        private const val LIST_DELIMITER = "\n"
        private const val BLOCK_DOMAIN = PersonalSpamRuleEntity.TYPE_BLOCK_DOMAIN
        private const val BLOCK_SENDER = PersonalSpamRuleEntity.TYPE_BLOCK_SENDER
        private const val ALLOW_DOMAIN = PersonalSpamRuleEntity.TYPE_ALLOW_DOMAIN
        private const val ALLOW_SENDER = PersonalSpamRuleEntity.TYPE_ALLOW_SENDER
    }

    override suspend fun classify(email: Email): SpamVerdict {
        val ownerNpub = email.recipientNpub
        val config = preferencesManager.spamFilterConfig.first()
        val manifest = currentManifest()
        return classifier.classify(email, ownerNpub, config, manifest, personalRules(ownerNpub))
    }

    override suspend fun stamp(email: Email): Email {
        val verdict = classify(email)
        emailDao.markSpam(
            id = email.id,
            isSpam = verdict.isSpam,
            score = verdict.score,
            reasons = if (verdict.reasons.isEmpty()) null else verdict.reasons.joinToString(",")
        )
        return email.copy(spamScore = verdict.score, isSpam = verdict.isSpam, spamReasons = verdict.reasons.joinToString(","))
    }

    override suspend fun trainAndMark(email: Email, isSpam: Boolean) {
        // Train the Bayesian layer with the user's signal.
        classifier.train(email, email.recipientNpub, isSpam)
        // Record personal block/allow rules so future mail from this sender is
        // caught (or trusted) deterministically, independent of corpus state.
        upsertPersonalRules(email, isSpam)
        // Persist the flag; keep the classification score for display but
        // override the spam decision with the user's explicit choice.
        val verdict = classify(email)
        emailDao.markSpam(
            id = email.id,
            isSpam = isSpam,
            score = verdict.score,
            reasons = listOfNotNull(
                *verdict.reasons.toTypedArray(),
                if (isSpam) "user_marked_spam" else "user_marked_ham"
            ).joinToString(",").takeIf { it.isNotEmpty() }
        )
    }

    private suspend fun upsertPersonalRules(email: Email, isSpam: Boolean) {
        val owner = email.recipientNpub
        val now = System.currentTimeMillis()
        val domain = email.senderDomain?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }
        val sender = email.senderEmail.lowercase().trim().takeIf { it.isNotEmpty() }
        if (isSpam) {
            // Marking spam: drop any stale personal allow, then block sender + domain.
            if (sender != null) personalSpamRuleDao.delete(owner, ALLOW_SENDER, sender)
            if (domain != null) personalSpamRuleDao.delete(owner, ALLOW_DOMAIN, domain)
            sender?.let {
                personalSpamRuleDao.upsert(PersonalSpamRuleEntity(owner, BLOCK_SENDER, it, now))
            }
            domain?.let {
                personalSpamRuleDao.upsert(PersonalSpamRuleEntity(owner, BLOCK_DOMAIN, it, now))
            }
        } else {
            // Marking not-spam: drop any personal block, then allow sender + domain.
            if (sender != null) personalSpamRuleDao.delete(owner, BLOCK_SENDER, sender)
            if (domain != null) personalSpamRuleDao.delete(owner, BLOCK_DOMAIN, domain)
            sender?.let {
                personalSpamRuleDao.upsert(PersonalSpamRuleEntity(owner, ALLOW_SENDER, it, now))
            }
            domain?.let {
                personalSpamRuleDao.upsert(PersonalSpamRuleEntity(owner, ALLOW_DOMAIN, it, now))
            }
        }
    }

    private suspend fun personalRules(ownerNpub: String): PersonalSpamRules {
        val rows = personalSpamRuleDao.getForOwner(ownerNpub)
        return PersonalSpamRules(
            blockedDomains = rows.valuesOfType(BLOCK_DOMAIN),
            allowedDomains = rows.valuesOfType(ALLOW_DOMAIN),
            blockedSenders = rows.valuesOfType(BLOCK_SENDER),
            allowedSenders = rows.valuesOfType(ALLOW_SENDER)
        )
    }

    private fun List<PersonalSpamRuleEntity>.valuesOfType(type: String): Set<String> =
        filter { it.type == type }.map { it.value }.toSet()

    override fun observeManifest(): Flow<SpamListManifest> =
        spamRuleDao.observe().map { entity -> entity?.toDomain() ?: SpamListManifest.EMPTY }

    override suspend fun syncManifest(): Boolean {
        val fetched = try {
            syncer.fetch()
        } catch (e: Exception) {
            Log.w(TAG, "Manifest fetch failed: ${e.message}")
            null
        } ?: return false

        val stored = spamRuleDao.get()
        if (stored != null && fetched.version <= stored.version) {
            Log.d(TAG, "Manifest v${fetched.version} not newer than stored v${stored.version}; skipping")
            // Still record the sync attempt time.
            preferencesManager.setSpamListLastSyncAt(System.currentTimeMillis())
            return false
        }

        spamRuleDao.upsert(
            SpamRuleEntity(
                version = fetched.version,
                updatedAt = fetched.updatedAt,
                ttlHours = fetched.ttlHours,
                blockedDomains = fetched.blockedDomains.joinToString(LIST_DELIMITER),
                allowedDomains = fetched.allowedDomains.joinToString(LIST_DELIMITER),
                blockedSenders = fetched.blockedSenders.joinToString(LIST_DELIMITER),
                spamPatterns = fetched.spamPatterns.joinToString(LIST_DELIMITER),
                trustedBridgeDomains = fetched.trustedBridgeDomains.joinToString(LIST_DELIMITER),
                fetchedAt = System.currentTimeMillis()
            )
        )
        preferencesManager.setSpamListLastSyncAt(System.currentTimeMillis())
        Log.d(TAG, "Manifest updated to v${fetched.version} (${fetched.blockedDomains.size} domains)")
        return true
    }

    override fun observeConfig(): Flow<SpamFilterConfig> = preferencesManager.spamFilterConfig

    override suspend fun setConfig(config: SpamFilterConfig) = preferencesManager.setSpamFilterConfig(config)

    override fun observeSpamThreads(recipientNpub: String): Flow<List<Email>> =
        emailDao.observeSpamThreads(recipientNpub).map { rows -> rows.map { emailMapper.mapToDomain(it) } }

    override fun observeSpamCount(recipientNpub: String): Flow<Int> =
        emailDao.observeSpamCount(recipientNpub)

    override fun observeTokenCount(ownerNpub: String): Flow<Int> =
        bayesianTokenDao.countForOwner(ownerNpub)

    override fun observeLastSyncAt(): Flow<Long> = preferencesManager.spamListLastSyncAt

    override fun observeRetroFirstRunDone(ownerNpub: String): Flow<Boolean> =
        preferencesManager.spamRetroFirstRunDone(ownerNpub)

    override suspend fun setRetroFirstRunDone(ownerNpub: String, done: Boolean) =
        preferencesManager.setSpamRetroFirstRunDone(ownerNpub, done)

    override suspend fun clearForAccount(ownerNpub: String) {
        bayesianTokenDao.clearForOwner(ownerNpub)
        personalSpamRuleDao.clearForOwner(ownerNpub)
    }

    private suspend fun currentManifest(): SpamListManifest =
        spamRuleDao.get()?.toDomain() ?: SpamListManifest.EMPTY

    private fun SpamRuleEntity.toDomain(): SpamListManifest = SpamListManifest(
        version = version,
        updatedAt = updatedAt,
        ttlHours = ttlHours,
        blockedDomains = blockedDomains.split(LIST_DELIMITER).filter { it.isNotEmpty() },
        allowedDomains = allowedDomains.split(LIST_DELIMITER).filter { it.isNotEmpty() },
        blockedSenders = blockedSenders.split(LIST_DELIMITER).filter { it.isNotEmpty() },
        spamPatterns = spamPatterns.split(LIST_DELIMITER).filter { it.isNotEmpty() },
        trustedBridgeDomains = trustedBridgeDomains.split(LIST_DELIMITER).filter { it.isNotEmpty() }
    )
}
