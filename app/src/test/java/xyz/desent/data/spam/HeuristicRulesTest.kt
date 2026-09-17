package xyz.desent.data.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.SpfStatus
import xyz.desent.domain.model.PersonalSpamRules
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamListManifest

/** A minimal in-memory [BundledSpamRules] for tests. */
private class FakeBundled(
    override val disposableDomains: Set<String> = emptySet(),
    override val subjectPatterns: List<String> = emptyList(),
    override val bodyPatterns: List<String> = emptyList(),
    override val highSignalKeywords: Set<String> = emptySet()
) : BundledSpamRules

/**
 * Unit tests for the deterministic Layer 1 heuristic scorer and the
 * [SpamClassifier] allowlist override + threshold boundary
 * (see refs/SPAM_FILTER_REFERENCE.md).
 */
class HeuristicRulesTest {

    private fun email(
        subject: String = "Hello",
        content: String = "Just checking in.",
        dkim: DkimStatus = DkimStatus.PASS,
        spf: SpfStatus = SpfStatus.PASS,
        senderEmail: String = "noreply@example.com",
        senderDomain: String? = "example.com",
        type: EmailType = EmailType.TRANSACTIONAL
    ) = Email(
        id = "id",
        recipientNpub = "npub",
        senderEmail = senderEmail,
        senderDomain = senderDomain,
        subject = subject,
        content = content,
        dkimStatus = dkim,
        spfStatus = spf,
        emailType = type,
        bridge = "email",
        messageId = null,
        threadToken = null,
        createdAt = 0L
    )

    private val emptyManifest = SpamListManifest.EMPTY

    @Test
    fun dkimFail_scoresThree() {
        val rules = HeuristicRules(FakeBundled())
        val result = rules.evaluate(email(dkim = DkimStatus.FAIL), emptyManifest)
        assertEquals(3.0, result.score, 0.001)
        assertTrue("dkim_fail" in result.reasons)
    }

    @Test
    fun spfFail_scoresOnePointFive() {
        val rules = HeuristicRules(FakeBundled())
        val result = rules.evaluate(email(spf = SpfStatus.FAIL), emptyManifest)
        assertEquals(1.5, result.score, 0.001)
    }

    @Test
    fun disposableDomain_scoresThree() {
        val rules = HeuristicRules(FakeBundled(disposableDomains = setOf("mailinator.com")))
        val result = rules.evaluate(email(senderDomain = "mailinator.com"), emptyManifest)
        assertTrue("disposable_domain" in result.reasons)
        assertEquals(3.0, result.score, 0.001)
    }

    @Test
    fun blockedDomainInManifest_scoresFive() {
        val rules = HeuristicRules(FakeBundled())
        val manifest = emptyManifest.copy(blockedDomains = listOf("bad.example"))
        val result = rules.evaluate(email(senderDomain = "bad.example"), manifest)
        assertEquals(5.0, result.score, 0.001)
        assertTrue("blocked_domain" in result.reasons)
    }

    @Test
    fun allowlistedDomain_forcesHam() {
        val rules = HeuristicRules(FakeBundled())
        // DKIM FAIL + blocked domain would normally score high.
        val manifest = emptyManifest.copy(
            blockedDomains = listOf("amazon.com"),
            allowedDomains = listOf("amazon.com")
        )
        val result = rules.evaluate(
            email(dkim = DkimStatus.FAIL, senderDomain = "amazon.com"),
            manifest
        )
        assertTrue(result.allowlisted)
        assertEquals(0.0, result.score, 0.001)
    }

    @Test
    fun subjectPatterns_areCappedAtOnePointFive() {
        // 5 subject patterns × 0.5 = 2.5, capped to 1.5.
        val rules = HeuristicRules(
            FakeBundled(subjectPatterns = listOf("viagra", "cialis", "lottery", "casino", "loan"))
        )
        val result = rules.evaluate(email(subject = "viagra cialis lottery casino loan"), emptyManifest)
        assertEquals(1.5, result.score, 0.001)
    }

    @Test
    fun invalidRegex_isDroppedNotThrown() {
        val rules = HeuristicRules(FakeBundled(subjectPatterns = listOf("[invalid(")))
        // Should not throw — invalid regex is skipped.
        rules.evaluate(email(subject = "anything"), emptyManifest)
    }

    @Test
    fun classifier_allowlistOverrideProducesHamVerdict() {
        val classifier = SpamClassifier(
            HeuristicRules(FakeBundled()),
            BayesianClassifier(NoOpTokenDao())
        )
        val manifest = SpamListManifest.EMPTY.copy(
            allowedDomains = listOf("github.com"),
            blockedDomains = listOf("github.com")
        )
        val email = email(dkim = DkimStatus.FAIL, senderDomain = "github.com")
        val verdict = kotlinx.coroutines.runBlocking {
            classifier.classify(email, "npub", SpamFilterConfig(), manifest)
        }
        assertFalse(verdict.isSpam)
        assertEquals("allowlist", verdict.layer)
    }

    @Test
    fun classifier_thresholdBoundary_flagsSpamAtOrAbove() {
        // DKIM fail (3.0) + blocked domain (5.0) = 8.0 with no bayesian signal.
        val classifier = SpamClassifier(
            HeuristicRules(FakeBundled()),
            BayesianClassifier(NoOpTokenDao())
        )
        val manifest = SpamListManifest.EMPTY.copy(blockedDomains = listOf("spammer.example"))
        val email = email(dkim = DkimStatus.FAIL, senderDomain = "spammer.example")
        val verdict = kotlinx.coroutines.runBlocking {
            classifier.classify(email, "npub", SpamFilterConfig(threshold = 5.0), manifest)
        }
        assertTrue(verdict.isSpam)
        assertTrue(verdict.score >= 5.0)
    }

    @Test
    fun classifier_disabledFilter_neverSpam() {
        val classifier = SpamClassifier(
            HeuristicRules(FakeBundled()),
            BayesianClassifier(NoOpTokenDao())
        )
        val email = email(dkim = DkimStatus.FAIL, senderDomain = "spammer.example")
        val manifest = SpamListManifest.EMPTY.copy(blockedDomains = listOf("spammer.example"))
        val verdict = kotlinx.coroutines.runBlocking {
            classifier.classify(email, "npub", SpamFilterConfig(enabled = false), manifest)
        }
        assertFalse(verdict.isSpam)
    }

    @Test
    fun personalBlockedSender_scoresFive() {
        val rules = HeuristicRules(FakeBundled())
        val personal = PersonalSpamRules(blockedSenders = setOf("evil@example.com"))
        val result = rules.evaluate(email(senderEmail = "evil@example.com"), emptyManifest, personal)
        assertEquals(5.0, result.score, 0.001)
        assertTrue(result.reasons.any { it.startsWith("user_blocked_sender") })
    }

    @Test
    fun personalAllowOverridesGlobalBlock() {
        val rules = HeuristicRules(FakeBundled())
        val manifest = emptyManifest.copy(blockedDomains = listOf("amazon.com"))
        val personal = PersonalSpamRules(allowedSenders = setOf("orders@amazon.com"))
        val result = rules.evaluate(
            email(dkim = DkimStatus.FAIL, senderEmail = "orders@amazon.com", senderDomain = "amazon.com"),
            manifest,
            personal
        )
        assertTrue(result.allowlisted)
        assertEquals(0.0, result.score, 0.001)
    }

    @Test
    fun classifier_blocklistOverrideForcesSpamDespiteBayesianBlend() {
        // A ham-leaning corpus used to dilute the +5.0 block hit below the
        // threshold (0.6·5 + 0.4·lowB < 5.0) — blocked senders landed in the
        // inbox. The override must short-circuit the blend entirely.
        val classifier = SpamClassifier(
            HeuristicRules(FakeBundled()),
            BayesianClassifier(HamSaturatedTokenDao())
        )
        val manifest = SpamListManifest.EMPTY.copy(blockedDomains = listOf("spammer.example"))
        val email = email(senderDomain = "spammer.example")
        val verdict = kotlinx.coroutines.runBlocking {
            classifier.classify(email, "npub", SpamFilterConfig(threshold = 5.0), manifest)
        }
        assertTrue(verdict.isSpam)
        assertEquals("blocklist", verdict.layer)
        assertEquals(10.0, verdict.score, 0.001)
    }

    @Test
    fun classifier_personalBlockOverrideForcesSpam() {
        val classifier = SpamClassifier(
            HeuristicRules(FakeBundled()),
            BayesianClassifier(NoOpTokenDao())
        )
        val personal = PersonalSpamRules(blockedSenders = setOf("nuisance@example.com"))
        val email = email(senderEmail = "nuisance@example.com")
        val verdict = kotlinx.coroutines.runBlocking {
            classifier.classify(email, "npub", SpamFilterConfig(), SpamListManifest.EMPTY, personal)
        }
        assertTrue(verdict.isSpam)
        assertEquals("blocklist", verdict.layer)
    }
}

/** Returns a heavily ham-trained row for every hash so the Bayesian layer
 *  produces a low-but-nonzero score (blend path stays active). */
private class HamSaturatedTokenDao : xyz.desent.data.local.database.dao.BayesianTokenDao {
    private val row = xyz.desent.data.local.database.entity.BayesianTokenEntity(
        tokenHash = "", ownerNpub = "npub", spamCount = 0, hamCount = 9
    )
    override suspend fun upsert(entity: xyz.desent.data.local.database.entity.BayesianTokenEntity) {}
    override suspend fun get(hashes: List<String>) = hashes.map { row.copy(tokenHash = it) }
    override fun observe(hashes: List<String>) =
        kotlinx.coroutines.flow.flowOf(hashes.map { row.copy(tokenHash = it) })
    override suspend fun getAllForOwner(ownerNpub: String) = emptyList<xyz.desent.data.local.database.entity.BayesianTokenEntity>()
    override fun countForOwner(ownerNpub: String) = kotlinx.coroutines.flow.flowOf(0)
    override suspend fun upsertMax(hash: String, ownerNpub: String, spam: Int, ham: Int) {}
    override suspend fun bumpCounts(hash: String, ownerNpub: String, spamDelta: Int, hamDelta: Int) {}
    override suspend fun clearForOwner(ownerNpub: String) {}
    override suspend fun clearAll() {}
}

/** A [BayesianTokenDao] that always returns no rows → bayesian score collapses to 0. */
private class NoOpTokenDao : xyz.desent.data.local.database.dao.BayesianTokenDao {
    override suspend fun upsert(entity: xyz.desent.data.local.database.entity.BayesianTokenEntity) {}
    override suspend fun get(hashes: List<String>): List<xyz.desent.data.local.database.entity.BayesianTokenEntity> = emptyList()
    override fun observe(hashes: List<String>) = kotlinx.coroutines.flow.flowOf(emptyList<xyz.desent.data.local.database.entity.BayesianTokenEntity>())
    override suspend fun getAllForOwner(ownerNpub: String): List<xyz.desent.data.local.database.entity.BayesianTokenEntity> = emptyList()
    override fun countForOwner(ownerNpub: String) = kotlinx.coroutines.flow.flowOf(0)
    override suspend fun upsertMax(hash: String, ownerNpub: String, spam: Int, ham: Int) {}
    override suspend fun bumpCounts(hash: String, ownerNpub: String, spamDelta: Int, hamDelta: Int) {}
    override suspend fun clearForOwner(ownerNpub: String) {}
    override suspend fun clearAll() {}
}
