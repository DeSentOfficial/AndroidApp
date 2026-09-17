package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.BadgeAward
import xyz.desent.domain.model.EarnedBadge

/**
 * NIP-58 badges on the DeSent relay (refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md).
 *
 * All badge traffic is internal to `wss://desent.xyz`: reads ride the
 * NIP-42-authenticated connection and the kind 30008 pin list is published
 * ONLY to that relay (never broadcast to other relays).
 */
interface BadgeRepository {

    /** The badges a user currently displays (pins that still resolve), in pin order. */
    fun observePinnedBadges(npub: String): Flow<List<EarnedBadge>>

    /** All live awards for a user joined with their definitions + pin state. */
    fun observeEarnedBadges(npub: String): Flow<List<EarnedBadge>>

    /**
     * Ensure the badge subscriptions for [npub] are open on the DeSent relay
     * (definitions once per process; that user's awards, pin list, and any
     * kind 5 revocations of their locally-known awards). Throttled per npub.
     */
    suspend fun requestBadgeSync(npub: String)

    /**
     * Force a re-issue of [npub]'s badge REQs, bypassing the sync throttle.
     * Used after a rejected kind 30008 publish so freshly-revoked awards are
     * re-fetched before the user re-pins (ANDROID_BADGES.md §6).
     */
    suspend fun resyncUserBadges(npub: String)

    /**
     * Publish the user's kind 30008 pin list naming exactly [awards] (in
     * order). An empty list publishes the empty-content tombstone, which
     * unpins everything. Optimistic: local pins are replaced first and
     * restored if the relay rejects the event.
     *
     * @return the relay's message when rejected, else null.
     */
    suspend fun publishPinList(ownerNpub: String, awards: List<BadgeAward>): String?
}
