package xyz.desent.domain.model

import kotlinx.serialization.Serializable

/**
 * Mailbox Configuration (NIP-EMAIL kind 35050, addressable).
 * See refs/FromServer/NIP-EMAIL.md § Kind 35050.
 *
 * Split by visibility:
 *  - [autoPurgeDays] rides as a **plaintext** `["auto_purge_days","N"]` tag so
 *    the relay can enforce the retention TTL without decrypting anything.
 *  - the private rules (blocked senders, forwarding targets, alias
 *    preference) are NIP-44 self-encrypted into the event `content`; only the
 *    user can read them.
 */
@Serializable
data class MailboxConfig(
    /** Retention TTL (days) the relay applies to stored gift wraps, or null = operator default. */
    val autoPurgeDays: Int? = null,
    val blockedSenders: List<String> = emptyList(),
    val forwardTargets: List<String> = emptyList(),
    /** Preferred outbound alias (must be owned by the user). */
    val preferredAlias: String? = null
) {
    /** Wire form of the self-encrypted `content` payload. */
    @Serializable
    data class PrivateRules(
        val blockedSenders: List<String> = emptyList(),
        val forwardTargets: List<String> = emptyList(),
        val preferredAlias: String? = null
    ) {
        constructor(config: MailboxConfig) : this(
            blockedSenders = config.blockedSenders,
            forwardTargets = config.forwardTargets,
            preferredAlias = config.preferredAlias
        )
    }

    companion object {
        const val AUTO_PURGE_TAG = "auto_purge_days"

        fun fromPrivateRules(autoPurgeDays: Int?, rules: PrivateRules): MailboxConfig =
            MailboxConfig(
                autoPurgeDays = autoPurgeDays,
                blockedSenders = rules.blockedSenders,
                forwardTargets = rules.forwardTargets,
                preferredAlias = rules.preferredAlias
            )
    }
}
