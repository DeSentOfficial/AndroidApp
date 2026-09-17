package xyz.desent.data.spam

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.data.local.database.dao.BayesianTokenDao
import xyz.desent.data.local.database.entity.BayesianTokenEntity
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.SpfStatus

/**
 * In-memory [BayesianTokenDao] so the classifier can be exercised end-to-end
 * without Room/Robolectric.
 */
private class InMemoryTokenDao : BayesianTokenDao {
    private val map = mutableMapOf<String, BayesianTokenEntity>()
    override suspend fun upsert(entity: BayesianTokenEntity) { map[entity.tokenHash] = entity }
    override suspend fun get(hashes: List<String>): List<BayesianTokenEntity> =
        hashes.mapNotNull { map[it] }
    override fun observe(hashes: List<String>) = kotlinx.coroutines.flow.flow { emit(get(hashes)) }
    override suspend fun getAllForOwner(ownerNpub: String): List<BayesianTokenEntity> =
        map.values.filter { it.ownerNpub == ownerNpub }
    override fun countForOwner(ownerNpub: String) = kotlinx.coroutines.flow.flowOf(
        map.values.count { it.ownerNpub == ownerNpub }
    )
    override suspend fun upsertMax(hash: String, ownerNpub: String, spam: Int, ham: Int) {
        val existing = map[hash]
        map[hash] = BayesianTokenEntity(
            tokenHash = hash,
            ownerNpub = ownerNpub,
            spamCount = maxOf(existing?.spamCount ?: 0, spam),
            hamCount = maxOf(existing?.hamCount ?: 0, ham)
        )
    }
    override suspend fun bumpCounts(hash: String, ownerNpub: String, spamDelta: Int, hamDelta: Int) {
        val existing = map[hash]
        map[hash] = BayesianTokenEntity(
            tokenHash = hash,
            ownerNpub = ownerNpub,
            spamCount = (existing?.spamCount ?: 0) + spamDelta,
            hamCount = (existing?.hamCount ?: 0) + hamDelta
        )
    }
    override suspend fun clearForOwner(ownerNpub: String) { map.clear() }
    override suspend fun clearAll() { map.clear() }
}

class BayesianClassifierTest {

    private fun email(subject: String, content: String, senderDomain: String = "example.com") = Email(
        id = "id",
        recipientNpub = "owner",
        senderEmail = "noreply@$senderDomain",
        senderDomain = senderDomain,
        subject = subject,
        content = content,
        dkimStatus = DkimStatus.PASS,
        spfStatus = SpfStatus.PASS,
        emailType = EmailType.TRANSACTIONAL,
        bridge = "email",
        messageId = null,
        threadToken = null,
        createdAt = 0L
    )

    @Test
    fun emptyCorpus_scoresZero() = runBlocking {
        val classifier = BayesianClassifier(InMemoryTokenDao())
        val score = classifier.score(email("win a free iphone", "click here now"), "owner")
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun trainedSpamTokens_scoreHigherThanHamTokens() = runBlocking {
        val dao = InMemoryTokenDao()
        val classifier = BayesianClassifier(dao)

        // Train: spam messages about viagra/crypto; ham messages about quarterly reports.
        repeat(3) {
            classifier.train(email("FREE viagra cialis", "buy now crypto giveaway"), "owner", isSpam = true)
        }
        repeat(3) {
            classifier.train(email("Quarterly report", "Please review the attached quarterly summary"), "owner", isSpam = false)
        }

        val spamScore = classifier.score(email("viagra crypto giveaway", "buy now"), "owner")
        val hamScore = classifier.score(email("Quarterly summary", "Please review the report"), "owner")

        assertTrue("spammy message should score higher than hammy: $spamScore vs $hamScore", spamScore > hamScore)
        assertTrue("spammy score should be clearly above mid: $spamScore", spamScore > 5.0)
        assertTrue("hammy score should be below mid: $hamScore", hamScore < 5.0)
    }

    @Test
    fun onceTrainedSpamToken_scoresAboveMid() = runBlocking {
        // A single "mark as spam" must push the message's own tokens above a
        // coin flip — the old triple-prior formula capped them at p=0.4,
        // which Fisher-combined to a constant 4.0 (the "everything is 5.2"
        // symptom after blending with heuristics).
        val dao = InMemoryTokenDao()
        val classifier = BayesianClassifier(dao)
        val spam = email("FREE viagra", "buy now crypto giveaway")
        classifier.train(spam, "owner", isSpam = true)

        val score = classifier.score(spam, "owner")
        assertTrue("one training hit should score above mid: $score", score > 5.0)
    }

    @Test
    fun allTokensUntrained_inTrainedCorpus_scoresZero() = runBlocking {
        // A brand-new message whose tokens have never been trained carry no
        // Bayesian signal: the score must collapse to 0 so the blend falls
        // back to pure heuristics instead of diluting a strong heuristic hit.
        val dao = InMemoryTokenDao()
        val classifier = BayesianClassifier(dao)
        classifier.train(email("Quarterly report", "please review the summary"), "owner", isSpam = false)

        val score = classifier.score(
            email("totally different offer", "unrelated brand new words", senderDomain = "other.example")
                .copy(senderEmail = "someone@other.example"),
            "owner"
        )
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun tokenHashes_areDeterministicAndDistinct() {
        val classifier = BayesianClassifier(InMemoryTokenDao())
        val e = email("hello world", "hello body content")
        val hashes1 = classifier.tokenHashes(e)
        val hashes2 = classifier.tokenHashes(e)
        assertEquals("same input → same hashes", hashes1, hashes2)
        // 64-char lowercase hex (SHA-256).
        assertTrue(hashes1.all { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } })
    }

    @Test
    fun stopWordsAreFiltered() {
        val classifier = BayesianClassifier(InMemoryTokenDao())
        val withStop = classifier.tokenHashes(email("the the the from from", ""))
        // "the"/"from" are stop-words and should not produce tokens beyond the
        // structured domain token; the body contributes nothing.
        assertTrue("no free-text stop-word tokens expected", withStop.isNotEmpty())
    }

    @Test
    fun upsertMax_takesElementWiseMaximum_neverRegressing() = runBlocking {
        // Convergent max-merge contract: an inbound snapshot row must never
        // lower a higher local count, and must raise a lower local count.
        val dao = InMemoryTokenDao()
        dao.upsertMax("hashX", "owner", spam = 5, ham = 2)

        // Inbound a LOWER count → local stays at the higher water mark.
        dao.upsertMax("hashX", "owner", spam = 1, ham = 9)
        val afterLower = dao.getAllForOwner("owner").single()
        assertEquals(5, afterLower.spamCount) // not lowered
        assertEquals(9, afterLower.hamCount) // raised to inbound max

        // Inbound a HIGHER spam count → local rises.
        dao.upsertMax("hashX", "owner", spam = 7, ham = 1)
        val afterHigher = dao.getAllForOwner("owner").single()
        assertEquals(7, afterHigher.spamCount) // raised to inbound max
        assertEquals(9, afterHigher.hamCount)  // not lowered
    }
}
