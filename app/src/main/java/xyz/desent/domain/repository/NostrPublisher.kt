package xyz.desent.domain.repository

/**
 * A single entry for a NIP-02 kind 3 (contact list) publish. Lives in the
 * domain layer so [FollowRepository] and its implementation can publish
 * without depending on the concrete [xyz.desent.data.repository.NostrRepository].
 *
 * NIP-02 p-tag layout: `["p", hex, relayUrl?, petname?]`. The relay hint here
 * is intentionally left out — the local relay pool is authoritative; only the
 * petname round-trips.
 */
data class ContactListEntry(
    val followingNpub: String,
    val petname: String? = null
)

/**
 * Narrow outbound-publish contract the data layer can depend on without
 * coupling directly to the concrete [xyz.desent.data.repository.NostrRepository]
 * (which would risk a circular init in [xyz.desent.di.AppContainer] since both
 * are `by lazy`). Implemented by [xyz.desent.data.repository.NostrRepository].
 * Declared in the domain layer so repositories such as [FollowRepository] can
 * depend on it and stay unit-testable.
 */
interface NostrPublisher {
    suspend fun publishContactList(follows: List<ContactListEntry>): Result<Unit>
}
