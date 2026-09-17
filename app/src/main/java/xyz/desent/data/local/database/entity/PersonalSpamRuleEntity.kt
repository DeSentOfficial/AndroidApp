package xyz.desent.data.local.database.entity

import androidx.room.Entity

/**
 * Per-account personal spam rules created by the user's own "mark as spam" /
 * "mark as not spam" actions. Unlike the global NIP-51 manifest
 * ([SpamRuleEntity]), these rules are private to the account and sync via the
 * NIP-78 `desent:spam-settings` payload (see `SpamSettingsPayload`).
 *
 * Composite PK (ownerNpub, type, value): a rule is idempotent — re-marking the
 * same sender just refreshes `updatedAt`.
 */
@Entity(
    tableName = "personal_spam_rules",
    primaryKeys = ["ownerNpub", "type", "value"]
)
data class PersonalSpamRuleEntity(
    val ownerNpub: String,
    val type: String,
    val value: String,
    val updatedAt: Long
) {
    companion object {
        const val TYPE_BLOCK_DOMAIN = "BLOCK_DOMAIN"
        const val TYPE_BLOCK_SENDER = "BLOCK_SENDER"
        const val TYPE_ALLOW_DOMAIN = "ALLOW_DOMAIN"
        const val TYPE_ALLOW_SENDER = "ALLOW_SENDER"
    }
}
