package xyz.desent.data.spam

import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailType
import xyz.desent.domain.model.SpfStatus
import xyz.desent.domain.model.PersonalSpamRules
import xyz.desent.domain.model.SpamListManifest
import java.util.regex.Pattern

/**
 * Source of the bundled, APK-shipped spam lists (see refs/SPAM_FILTER_REFERENCE.md
 * §"Bundled public lists"). Implemented by [BundledSpamLists] in production and
 * by fakes in tests so the heuristics are unit-testable without an Android Context.
 */
interface BundledSpamRules {
    val disposableDomains: Set<String>
    val subjectPatterns: List<String>
    val bodyPatterns: List<String>
    val highSignalKeywords: Set<String>
}

/**
 * Layer 1 of the spam filter: deterministic heuristic scoring.
 *
 * Pure function of (email, manifest, bundledLists) → (score, reasons). Identical
 * output on every device for the same input. See refs/SPAM_FILTER_REFERENCE.md
 * §"Layer 1" for the scoring table.
 */
class HeuristicRules(private val bundled: BundledSpamRules) {

    /** Compiled manifest patterns, cached per call site (caller passes manifest). */
    private data class CompiledManifest(
        val blockedDomains: Set<String>,
        val allowedDomains: Set<String>,
        val blockedSenders: Set<String>,
        val trustedBridgeDomains: Set<String>,
        val bodyPatterns: List<Pattern>
    )

    /** Result of the heuristic pass. */
    data class HeuristicResult(val score: Double, val reasons: List<String>, val allowlisted: Boolean)

    fun evaluate(
        email: Email,
        manifest: SpamListManifest,
        personal: PersonalSpamRules = PersonalSpamRules.EMPTY
    ): HeuristicResult {
        val reasons = mutableListOf<String>()
        var score = 0.0

        val domain: String = email.senderDomain?.lowercase()?.trim().orEmpty()
        val sender: String = email.senderEmail.lowercase().trim()
        val compiled = compile(manifest)

        // --- Layer 2 overrides (evaluated here so the blend math stays single-pass) ---
        // Personal allows are the user's own trust decisions and win over everything,
        // including global blocks.
        if ((domain.isNotEmpty() && domain in personal.allowedDomains) ||
            (sender.isNotEmpty() && sender in personal.allowedSenders)
        ) {
            return HeuristicResult(0.0, listOf("allowlist_override:$sender"), allowlisted = true)
        }
        if (domain.isNotEmpty() && (domain in compiled.allowedDomains || domain in compiled.trustedBridgeDomains)) {
            return HeuristicResult(0.0, listOf("allowlist_override:$domain"), allowlisted = true)
        }

        // --- DKIM / SPF ---
        if (email.dkimStatus == DkimStatus.FAIL) { score += 3.0; reasons += "dkim_fail" }
        if (email.spfStatus == SpfStatus.FAIL) { score += 1.5; reasons += "spf_fail" }
        if (email.dkimStatus == DkimStatus.NONE && domain.isNotEmpty() && domain !in compiled.allowedDomains) {
            score += 1.0; reasons += "dkim_none_unknown_domain"
        }

        // --- Domain / sender reputation ---
        if (domain.isNotEmpty() && domain in compiled.blockedDomains) { score += 5.0; reasons += "blocked_domain" }
        if (sender.isNotEmpty() && sender in compiled.blockedSenders) { score += 5.0; reasons += "blocked_sender" }
        if (domain.isNotEmpty() && domain in bundled.disposableDomains) { score += 3.0; reasons += "disposable_domain" }
        // Personal rules from the user's own feedback — same +5.0 weight as the
        // global manifest entries.
        if (domain.isNotEmpty() && domain in personal.blockedDomains) { score += 5.0; reasons += "user_blocked_domain:$domain" }
        if (sender.isNotEmpty() && sender in personal.blockedSenders) { score += 5.0; reasons += "user_blocked_sender:$sender" }

        // --- Email type ---
        if (email.emailType == EmailType.PROMOTIONAL) { score += 0.5; reasons += "promotional_type" }

        // --- Subject regex (bundled, capped at +1.5) ---
        var subjectHits = 0
        var subjectScore = 0.0
        for (patternSource in bundled.subjectPatterns) {
            val p = compileOr(patternSource) ?: continue
            if (p.matcher(email.subject.lowercase()).find()) {
                subjectHits++
                subjectScore += 0.5
                reasons += "subject_pattern:${subjectHits}"
            }
        }
        if (subjectScore > 0) {
            score += subjectScore.coerceAtMost(1.5)
        }

        // --- Body regex from the manifest (capped at +4.0) ---
        var bodyScore = 0.0
        var bodyHits = 0
        val bodyLower = email.content.lowercase()
        for (p in compiled.bodyPatterns) {
            if (p.matcher(bodyLower).find()) {
                bodyHits++
                bodyScore += 2.0
                reasons += "body_pattern:${bodyHits}"
            }
        }
        if (bodyScore > 0) {
            score += bodyScore.coerceAtMost(4.0)
        }

        // --- Bundled body patterns (also capped within the same budget) ---
        var bundledBodyScore = 0.0
        for (patternSource in bundled.bodyPatterns) {
            val p = compileOr(patternSource) ?: continue
            if (p.matcher(bodyLower).find()) {
                bundledBodyScore += 2.0
                reasons += "body_pattern:bundled"
            }
        }
        score += bundledBodyScore.coerceAtMost(4.0)

        // --- Keyword density (≥3 distinct high-signal hits → +2.0) ---
        val keywordHits = bundled.highSignalKeywords.count { it in bodyLower }
        if (keywordHits >= 3) { score += 2.0; reasons += "keyword_density:$keywordHits" }

        return HeuristicResult(score, reasons, allowlisted = false)
    }

    private fun compile(manifest: SpamListManifest): CompiledManifest {
        val bodyPatterns = manifest.spamPatterns.mapNotNull { compileOr(it) }
        return CompiledManifest(
            blockedDomains = manifest.blockedDomains.toSet(),
            allowedDomains = manifest.allowedDomains.toSet(),
            blockedSenders = manifest.blockedSenders.toSet(),
            trustedBridgeDomains = manifest.trustedBridgeDomains.toSet(),
            bodyPatterns = bodyPatterns
        )
    }

    /** Compile a pattern case-insensitively; return null on failure (never throw). */
    private fun compileOr(source: String): Pattern? {
        return runCatching { Pattern.compile(source, Pattern.CASE_INSENSITIVE) }.getOrElse {
            android.util.Log.w("HeuristicRules", "Dropping invalid regex '$source': ${it.message}")
            null
        }
    }
}
