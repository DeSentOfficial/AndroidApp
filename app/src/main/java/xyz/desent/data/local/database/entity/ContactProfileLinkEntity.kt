package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable NIP-05 identifier → pubkey resolution for contact-profile lookups
 * (see ContactProfileResolverImpl). Persisted so an app open doesn't re-fetch
 * `.well-known/nostr.json` for email-only contacts; [resolvedAt] drives the
 * re-verification window.
 */
@Entity(tableName = "contact_profile_links")
data class ContactProfileLinkEntity(
    /** Lowercased identifier (`user@domain`). */
    @PrimaryKey val identifier: String,
    /** Resolved 64-char hex pubkey. */
    val hexPubkey: String,
    val resolvedAt: Long
)
