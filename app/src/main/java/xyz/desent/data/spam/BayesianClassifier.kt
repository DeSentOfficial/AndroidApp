package xyz.desent.data.spam

import xyz.desent.data.local.database.dao.BayesianTokenDao
import xyz.desent.domain.model.Email
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln

/**
 * Layer 3 of the spam filter: a per-user, self-learning Bayesian classifier.
 *
 * Tokens are SHA-256-hashed before storage so the table does not become a
 * second copy of email plaintext. Probabilities are combined via the inverse
 * log-product (Fisher/Robinson) method, evaluated in log space for numerical
 * stability (see refs/SPAM_FILTER_REFERENCE.md §"Layer 3").
 */
class BayesianClassifier(private val tokenDao: BayesianTokenDao) {

    private val stopWords: Set<String> = setOf(
        "the", "and", "for", "with", "that", "this", "you", "your", "from",
        "have", "was", "are", "but", "not", "will", "can", "all", "any", "its",
        "into", "out", "use", "our", "who", "has", "had", "her", "his", "they",
        "them", "what", "when", "where", "which", "how", "than", "then", "just"
    )

    /**
     * Score the message against the owner's token corpus. Returns a value in
     * `[0, 10]` on the same scale as the heuristic layer. Returns 0.0 when the
     * corpus is empty (no training yet) so the blend collapses to heuristics.
     */
    suspend fun score(email: Email, ownerNpub: String): Double {
        val hashes = tokenHashes(email)
        if (hashes.isEmpty()) return 0.0
        val rows = tokenDao.get(hashes)
        if (rows.isEmpty()) return 0.0

        // Per-token probability with a 0.5 prior (one virtual ham + one virtual
        // spam count) so a single training hit doesn't saturate to 0/1. Tokens
        // with no training history carry no signal (they'd all sit at exactly
        // 0.5 and dilute the Fisher combine), so they are skipped entirely.
        val probs = hashes.mapNotNull { h ->
            val row = rows.firstOrNull { it.tokenHash == h } ?: return@mapNotNull null
            val spam = row.spamCount + 1
            val ham = row.hamCount + 1
            spam.toDouble() / (spam + ham)
        }
        if (probs.isEmpty()) return 0.0

        val p = combine(probs)
        return (p * 10.0).coerceIn(0.0, 10.0)
    }

    /** Train the corpus: increment spam or ham counts for every token. */
    suspend fun train(email: Email, ownerNpub: String, isSpam: Boolean) {
        val spamDelta = if (isSpam) 1 else 0
        val hamDelta = if (isSpam) 0 else 1
        for (hash in tokenHashes(email)) {
            tokenDao.bumpCounts(hash, ownerNpub, spamDelta, hamDelta)
        }
    }

    /** Tokenize → hash. Public for testing. Returns distinct SHA-256 hex strings. */
    internal fun tokenHashes(email: Email): List<String> {
        val raw = mutableListOf<String>()

        // 1. Subject (weight ×2 — counted twice so subject tokens weigh more).
        val subjectTokens = tokenizeText(email.subject)
        raw += subjectTokens
        raw += subjectTokens

        // 2. Sender domain as a single structured token.
        email.senderDomain?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            raw += "domain:$it"
        }

        // 3. Sender localpart, prefixed so it doesn't collide with body words.
        val localpart = email.senderEmail.substringBefore('@')
        raw += tokenizeText(localpart).map { "lp:$it" }

        // 4. Body, capped to keep huge messages bounded.
        raw += tokenizeText(email.content).take(MAX_BODY_TOKENS)

        return raw
            .filter { keepToken(it) }
            .distinct()
            .map { sha256Hex(it) }
    }

    private fun tokenizeText(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length in 3..24 }

    private fun keepToken(token: String): Boolean {
        val prefix = token.substringBefore(':', missingDelimiterValue = "")
        val plain = if (prefix == "domain" || prefix == "lp") token.substringAfter(':') else token
        val isStructured = prefix == "domain" || prefix == "lp"
        if (plain.length !in 3..60) return false
        // Stop-words only apply to free-text tokens, not structured ones.
        return isStructured || plain.lowercase() !in stopWords
    }

    /**
     * Inverse log-product combination (Fisher/Robinson). Computes, in log
     * space, `∏p / (∏p + ∏(1-p))` — i.e. the probability that the message is
     * spam given the product of independent token probabilities. Returns a
     * value in [0, 1].
     */
    private fun combine(probs: List<Double>): Double {
        if (probs.isEmpty()) return 0.5
        var logP = 0.0
        var logOneMinusP = 0.0
        for (p in probs) {
            // p is in (0,1) by construction (prior keeps it off the endpoints).
            logP += ln(p.coerceIn(MIN_PROB, 1.0 - MIN_PROB))
            logOneMinusP += ln((1.0 - p).coerceIn(MIN_PROB, 1.0 - MIN_PROB))
        }
        // spamProb = exp(logP) / (exp(logP) + exp(logOneMinusP))
        //          = 1 / (1 + exp(logOneMinusP - logP))
        val spamProb = 1.0 / (1.0 + exp(logOneMinusP - logP))
        return spamProb.coerceIn(0.0, 1.0)
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_BODY_TOKENS = 300
        private const val MIN_PROB = 1.0e-6
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
