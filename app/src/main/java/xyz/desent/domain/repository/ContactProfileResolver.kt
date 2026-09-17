package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.ContactProfile

/**
 * Kind-0 profile enrichment for contact-linked nostr identities
 * (ANDROID_CONTACTS.md §5). Room-backed: cached profiles render instantly
 * from the `users` table and relays are only consulted (read-only, one-shot)
 * when the cache is missing or stale. Results are additionally TTL-cached
 * in memory including negative entries; profiles are never persisted into
 * the contacts payload.
 */
interface ContactProfileResolver {
    /**
     * Resolve the kind-0 profile for a 64-char hex pubkey, or null when no
     * profile exists on any lookup relay. Serves the Room cache immediately
     * when fresh; a stale row is served instantly and refreshed from the
     * relays in the background.
     */
    suspend fun resolve(pubkeyHex: String): ContactProfile?

    /**
     * Resolve a profile for a NIP-05-style identifier (`user@domain`) — the
     * contact-email case. The identifier→pubkey resolution is persisted in
     * Room (`contact_profile_links`) and re-verified at most once per window.
     */
    suspend fun resolveByIdentifier(identifier: String): ContactProfile?

    /** Reactive view of the Room cache for a hex pubkey — never hits the network. */
    fun observeProfile(pubkeyHex: String): Flow<ContactProfile?>

    /** Reactive view of the Room cache for an identifier (via its linked pubkey). */
    fun observeProfileByIdentifier(identifier: String): Flow<ContactProfile?>
}
