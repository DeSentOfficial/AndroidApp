package xyz.desent.domain.model

/**
 * The account's personal spam rules, derived from their own "mark as spam" /
 * "mark as not spam" feedback (Room `personal_spam_rules`, synced via the
 * NIP-78 `desent:spam-settings` payload).
 *
 * Personal allows override everything (like the global allowlist forces ham);
 * personal blocks contribute a deterministic +5.0 and trip the blocklist
 * override in [xyz.desent.data.spam.HeuristicRules] /
 * [xyz.desent.data.spam.SpamClassifier].
 */
data class PersonalSpamRules(
    val blockedDomains: Set<String> = emptySet(),
    val allowedDomains: Set<String> = emptySet(),
    val blockedSenders: Set<String> = emptySet(),
    val allowedSenders: Set<String> = emptySet()
) {
    companion object {
        val EMPTY = PersonalSpamRules()
    }
}
