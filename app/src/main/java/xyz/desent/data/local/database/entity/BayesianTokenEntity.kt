package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-account Bayesian token counts used by Layer 3 of the spam filter
 * (see `refs/SPAM_FILTER_REFERENCE.md` §"Layer 3").
 *
 * [tokenHash] is the SHA-256 of the lowercased token — plaintext is never
 * stored, so the table does not become a second copy of email content. Rows
 * are scoped to [ownerNpub] so multi-account isolation matches the rest of
 * the schema (see migration 20→21).
 */
@Entity(tableName = "bayesian_tokens")
data class BayesianTokenEntity(
    @PrimaryKey
    val tokenHash: String,
    val ownerNpub: String,
    val spamCount: Int,
    val hamCount: Int
)
