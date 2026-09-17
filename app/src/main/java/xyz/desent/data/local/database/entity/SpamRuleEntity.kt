package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached copy of the DeSent spam blocklist manifest (see
 * `refs/SPAM_LIST_REFERENCE.md`). A single row keyed by [id] = 0; overwritten
 * whenever a newer manifest `version` arrives from the trusted publisher.
 *
 * The columns mirror the manifest JSON fields. Lists are stored as
 * newline-delimited strings to keep the schema flat; they're unpacked by the
 * repository into the domain [xyz.desent.domain.model.SpamListManifest].
 */
@Entity(tableName = "spam_rules")
data class SpamRuleEntity(
    @PrimaryKey
    val id: Int = 0,
    val version: Long,
    val updatedAt: Long,
    val ttlHours: Int,
    val blockedDomains: String,
    val allowedDomains: String,
    val blockedSenders: String,
    val spamPatterns: String,
    val trustedBridgeDomains: String,
    val fetchedAt: Long
)
