package xyz.desent.data.spam

import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.PersonalSpamRules
import xyz.desent.domain.model.SpamFilterConfig
import xyz.desent.domain.model.SpamListManifest
import xyz.desent.domain.model.SpamVerdict

/**
 * Orchestrates the three filter layers into a single [SpamVerdict].
 *
 * Layers are evaluated locally only; no email content leaves the device
 * (see refs/SPAM_FILTER_REFERENCE.md).
 */
class SpamClassifier(
    private val heuristics: HeuristicRules,
    private val bayesian: BayesianClassifier
) {

    suspend fun classify(
        email: Email,
        ownerNpub: String,
        config: SpamFilterConfig,
        manifest: SpamListManifest,
        personal: PersonalSpamRules = PersonalSpamRules.EMPTY
    ): SpamVerdict {
        // SYSTEM / bridge-origin messages are exempt.
        if (email.emailType == EmailType.SYSTEM) return SpamVerdict.NOT_SPAM
        if (!config.enabled) {
            return SpamVerdict(0.0, isSpam = false, reasons = listOf("filter_disabled"), layer = "disabled")
        }

        val heuristic = if (config.layerHeuristicsEnabled || config.layerBlocklistEnabled) {
            heuristics.evaluate(email, manifest, personal)
        } else {
            HeuristicRules.HeuristicResult(0.0, emptyList(), allowlisted = false)
        }

        // Layer 2 allowlist override forces ham regardless of any other signal.
        if (config.layerBlocklistEnabled && heuristic.allowlisted) {
            return SpamVerdict.allowlisted(email.senderDomain ?: "")
        }

        // Blocklist override: a deterministic block hit (global manifest or the
        // user's own personal rules) forces spam regardless of the Bayesian
        // blend — otherwise a partially-trained corpus dilutes the +5.0 signal
        // below the threshold and blocked senders land in the inbox.
        val blockHit = heuristic.reasons.any {
            it == "blocked_domain" || it == "blocked_sender" || it.startsWith("user_blocked_")
        }
        if (config.layerBlocklistEnabled && blockHit) {
            return SpamVerdict(
                score = 10.0,
                isSpam = true,
                reasons = heuristic.reasons + "blocklist_override",
                layer = "blocklist"
            )
        }

        val scoreH = if (config.layerHeuristicsEnabled || config.layerBlocklistEnabled) {
            heuristic.score
        } else {
            0.0
        }

        // Layer 3 Bayesian (per-user). When the corpus is empty it returns 0,
        // so the blend collapses to heuristics for fresh installs — we skip the
        // blend entirely so an empty corpus can't drag a spammy heuristic below
        // the threshold.
        val scoreB = if (config.layerBayesianEnabled) {
            bayesian.score(email, ownerNpub)
        } else {
            0.0
        }

        val (hW, bW) = renormalizeWeights(config)
        val finalScore = if (scoreB <= 0.0) {
            scoreH
        } else {
            scoreH * hW + scoreB * bW
        }

        val reasons = heuristic.reasons.toMutableList()
        if (scoreB > 0.0) reasons += "bayesian:${(scoreB / 10.0 * 100).toInt()}"

        val isSpam = finalScore >= config.threshold
        val layer = if (config.layerBayesianEnabled && bW > 0.0) "blend" else "heuristic"

        return SpamVerdict(
            score = finalScore,
            isSpam = isSpam,
            reasons = reasons,
            layer = layer
        )
    }

    /**
     * Renormalize the enabled layers' weights to sum to 1.0. If only one layer
     * is enabled it gets full weight; if both, they keep their configured ratio.
     */
    private fun renormalizeWeights(config: SpamFilterConfig): Pair<Double, Double> {
        val hEnabled = config.layerHeuristicsEnabled || config.layerBlocklistEnabled
        val bEnabled = config.layerBayesianEnabled
        return when {
            hEnabled && bEnabled -> {
                val sum = config.heuristicWeight + config.bayesianWeight
                if (sum <= 0) 1.0 to 0.0
                else config.heuristicWeight / sum to config.bayesianWeight / sum
            }
            bEnabled -> 0.0 to 1.0
            else -> 1.0 to 0.0
        }
    }

    /** Delegate training to the Bayesian layer. */
    suspend fun train(email: Email, ownerNpub: String, isSpam: Boolean) {
        bayesian.train(email, ownerNpub, isSpam)
    }
}
