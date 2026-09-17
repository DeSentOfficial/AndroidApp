package xyz.desent.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.BadgeDao
import xyz.desent.data.local.database.dao.RelayDao
import xyz.desent.data.local.database.entity.BadgeAwardEntity
import xyz.desent.data.local.database.entity.BadgeDefinitionEntity
import xyz.desent.data.local.database.entity.BadgePinEntity
import xyz.desent.data.nip11.Nip11MetadataService
import xyz.desent.data.nostr.BadgeEventCodec
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.BadgeAward
import xyz.desent.domain.model.BadgeDefinition
import xyz.desent.domain.model.EarnedBadge
import xyz.desent.domain.repository.BadgeRepository
import xyz.desent.domain.repository.RelayRepository
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag

/**
 * NIP-58 badges on the DeSent relay (refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md
 * + ANDROID_BADGES.md).
 *
 * Every wire operation targets [RelayConfig.EMAIL_RELAY_URL] exclusively —
 * badge events are internal to that relay and are never broadcast to other
 * relays. Reads ride the pool's NIP-42-authenticated connection.
 */
class BadgeRepositoryImpl(
    private val badgeDao: BadgeDao,
    private val relayRepository: RelayRepository,
    private val secureKeyManager: SecureKeyManager,
    private val nip11MetadataService: Nip11MetadataService,
    private val relayDao: RelayDao
) : BadgeRepository {

    companion object {
        private const val TAG = "BadgeRepository"

        /** How long to await the relay's OK verdict after publishing a 30008. */
        private const val PUBLISH_VERDICT_TIMEOUT_MS = 5_000L

        /** Kind-5 revocation subscriptions are capped by filter length. */
        private const val MAX_REVOCATION_IDS = 500
    }

    /**
     * Relay npub (kind 30009/8 author), resolved per ANDROID_BADGES.md §3:
     * the cached NIP-11 `pubkey` column first, then a live NIP-11 fetch,
     * then the compiled-in fallback.
     */
    @Volatile
    private var cachedRelayNpubHex: String? = null

    /** User npubs whose per-user badge REQs were issued this process. */
    private val syncedNpubs = mutableSetOf<String>()

    @Volatile
    private var definitionsSubscribed = false

    // ---------------- Queries ----------------

    override fun observePinnedBadges(npub: String): Flow<List<EarnedBadge>> =
        badgeDao.observePinnedBadges(npub).map { rows -> rows.map { it.toEarnedBadge(pinned = true) } }

    override fun observeEarnedBadges(npub: String): Flow<List<EarnedBadge>> =
        badgeDao.observeEarnedBadges(npub).map { rows -> rows.map { it.toEarnedBadge() } }

    // ---------------- Sync ----------------

    override suspend fun requestBadgeSync(npub: String) {
        try {
            relayRepository.waitForRelayReady(RelayConfig.EMAIL_RELAY_URL)
            val relayNpub = relayNpubHex() ?: return

            // Definitions: one global subscription per process.
            if (!definitionsSubscribed) {
                definitionsSubscribed = true
                relayRepository.subscribeToEventsOnRelay(
                    listOf(
                        mapOf(
                            "kinds" to listOf(NostrKinds.BADGE_DEFINITION),
                            "authors" to listOf(relayNpub),
                            "limit" to 200
                        )
                    ),
                    "badges-defs",
                    RelayConfig.EMAIL_RELAY_URL,
                    persistent = true
                )
            }

            if (npub in syncedNpubs) return
            syncedNpubs.add(npub)
            subscribeUserBadges(npub, relayNpub)
            Log.d(TAG, " badge sync opened for ${npub.take(12)}")
        } catch (e: Exception) {
            Log.w(TAG, "badge sync failed for ${npub.take(12)}: ${e.message}")
        }
    }

    override suspend fun resyncUserBadges(npub: String) {
        try {
            relayRepository.waitForRelayReady(RelayConfig.EMAIL_RELAY_URL)
            val relayNpub = relayNpubHex() ?: return
            syncedNpubs.add(npub)
            subscribeUserBadges(npub, relayNpub)
            Log.d(TAG, " badge re-sync issued for ${npub.take(12)}")
        } catch (e: Exception) {
            Log.w(TAG, "badge re-sync failed for ${npub.take(12)}: ${e.message}")
        }
    }

    /**
     * The user-scoped REQ set: awards (`#p`), pin list (`authors`), and kind 5
     * revocations of that user's locally-known awards. Subscription ids are
     * deterministic per user, so a re-issue replaces the previous filters.
     */
    private suspend fun subscribeUserBadges(npub: String, relayNpub: String) {
        val hex = Bech32Utils.npubToHex(npub)
        if (hex.isEmpty()) return
        relayRepository.subscribeToEventsOnRelay(
            listOf(
                mapOf(
                    "kinds" to listOf(NostrKinds.BADGE_AWARD),
                    "#p" to listOf(hex),
                    "limit" to 200
                ),
                mapOf(
                    "kinds" to listOf(NostrKinds.PROFILE_BADGES),
                    "authors" to listOf(hex),
                    "limit" to 5
                )
            ),
            "badges-${hex.take(8)}",
            RelayConfig.EMAIL_RELAY_URL,
            persistent = true
        )
        refreshRevocationSubscription(npub, relayNpub)
    }

    /**
     * Subscribe to kind 5 deletions that revoke [npub]'s locally-known
     * awards (`#e` on the award event ids). Re-issued whenever a new award
     * for a synced user is stored so later revocations stay covered.
     */
    private suspend fun refreshRevocationSubscription(npub: String, relayNpubHex: String) {
        if (npub !in syncedNpubs) return
        val knownIds = badgeDao.getAwardIdsFor(npub).take(MAX_REVOCATION_IDS)
        if (knownIds.isEmpty()) return
        val hex = Bech32Utils.npubToHex(npub)
        relayRepository.subscribeToEventsOnRelay(
            listOf(
                mapOf(
                    "kinds" to listOf(NostrKinds.DELETION),
                    "authors" to listOf(relayNpubHex),
                    "#e" to knownIds,
                    "limit" to knownIds.size
                )
            ),
            "badges-rev-${hex.take(8)}",
            RelayConfig.EMAIL_RELAY_URL,
            persistent = true
        )
    }

    /**
     * The relay npub as a hex pubkey. Never throws; null only when every
     * resolution path fails (offline first-run before the fallback is
     * consulted — the fallback constant itself is non-null, so in practice
     * this always resolves).
     */
    suspend fun relayNpubHex(): String? {
        cachedRelayNpubHex?.let { return it }

        val cached = runCatching {
            relayDao.getRelayByUrl(RelayConfig.EMAIL_RELAY_URL)?.nip11Pubkey
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (cached != null) {
            cachedRelayNpubHex = cached
            return cached
        }

        val fetched = nip11MetadataService.fetchMetadata(RelayConfig.EMAIL_RELAY_URL)
            ?.pubkey?.takeIf { it.isNotBlank() }
        if (fetched != null) {
            cachedRelayNpubHex = fetched
            return fetched
        }

        return RelayConfig.RELAY_PUBKEY_HEX.also { cachedRelayNpubHex = it }
    }

    // ---------------- Inbound events (wired from NostrEventProcessor) ----------------

    /**
     * Handle an inbound badge event (kinds 8 / 30008 / 30009) from the DeSent
     * relay. Tag verification of the relay-authored kinds is implicit: only
     * the relay npub can store them there (RESTRICTED_WRITE_KINDS), and our
     * REQs already filter `authors` to the relay npub for definitions.
     */
    suspend fun onInboundBadgeEvent(event: GenericEvent) {
        val tags = event.tags.toPlainTags()
        when (event.kind) {
            NostrKinds.BADGE_DEFINITION -> onInboundDefinition(event, tags)
            NostrKinds.BADGE_AWARD -> onInboundAward(event, tags)
            NostrKinds.PROFILE_BADGES -> onInboundPinList(event, tags)
        }
    }

    private suspend fun onInboundDefinition(event: GenericEvent, tags: List<List<String>>) {
        try {
            // The d slug parses for both full definitions and tombstones.
            val slug = BadgeEventCodec.parseSlug(tags) ?: run {
                Log.w(TAG, "badge definition without a valid d slug: ${event.id?.take(12)}")
                return
            }
            // Retirement tombstone: empty-content 30009 removes the definition.
            if (BadgeEventCodec.isTombstone(event.content)) {
                badgeDao.deleteDefinition(slug)
                Log.d(TAG, " badge definition retired: $slug")
                return
            }
            val parsed = BadgeEventCodec.parseDefinition(tags, event.content ?: "") ?: return

            // Replaceable semantics: latest wins; skip strictly older echoes.
            val existing = badgeDao.getDefinition(slug)
            if (existing != null && event.createdAt < existing.updatedAt) return

            badgeDao.upsertDefinition(
                BadgeDefinitionEntity(
                    slug = slug,
                    eventId = event.id ?: "",
                    name = parsed.name,
                    description = parsed.description,
                    imageUrl = parsed.imageUrl,
                    thumbUrl = parsed.thumbUrl,
                    iconName = parsed.iconName,
                    color = parsed.color,
                    updatedAt = event.createdAt
                )
            )
            Log.d(TAG, " badge definition stored: $slug")
        } catch (e: Exception) {
            Log.e(TAG, "failed to process badge definition: ${e.message}", e)
        }
    }

    private suspend fun onInboundAward(event: GenericEvent, tags: List<List<String>>) {
        try {
            val parsed = BadgeEventCodec.parseAward(tags) ?: return
            val eventId = event.id ?: return
            val awardeeNpub = try {
                Bech32Utils.hexToNpub(parsed.awardeePubkeyHex)
            } catch (e: Exception) {
                return
            }
            val inserted = badgeDao.insertAward(
                BadgeAwardEntity(
                    eventId = eventId,
                    awardeeNpub = awardeeNpub,
                    slug = parsed.slug,
                    definitionAddress = parsed.definitionAddress,
                    awardedAt = event.createdAt
                )
            )
            if (inserted != -1L) {
                Log.d(TAG, " badge award stored: ${parsed.slug} -> ${awardeeNpub.take(12)}")
                // Extend the revocation (#e) filter to cover the new award.
                refreshRevocationSubscription(awardeeNpub, relayNpubHex() ?: return)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to process badge award: ${e.message}", e)
        }
    }

    private suspend fun onInboundPinList(event: GenericEvent, tags: List<List<String>>) {
        try {
            val ownerNpub = try {
                Bech32Utils.hexToNpub(event.pubKey.toHexString())
            } catch (e: Exception) {
                return
            }
            // Empty content = tombstone: unpins everything.
            if (BadgeEventCodec.isTombstone(event.content)) {
                badgeDao.replacePins(ownerNpub, emptyList())
                Log.d(TAG, " pin list cleared for ${ownerNpub.take(12)}")
                return
            }
            // Resolve each pair locally; stale pairs (revoked award, retired
            // definition, someone else's award) are dropped per §5 — the relay
            // already validated at publish time, so leftovers mean post-publish
            // revocation/retirement.
            val awardsByEventId = badgeDao.getAwardsByIds(
                BadgeEventCodec.parsePinPairs(tags).map { it.awardEventId }
            ).associateBy { it.eventId }

            val pins = BadgeEventCodec.parsePinPairs(tags).mapNotNull { pair ->
                val award = awardsByEventId[pair.awardEventId]
                if (award == null || award.awardeeNpub != ownerNpub) return@mapNotNull null
                val slug = BadgeEventCodec.definitionSlug(pair.definitionAddress)
                if (slug == null || slug != award.slug) return@mapNotNull null
                BadgePinEntity(
                    ownerNpub = ownerNpub,
                    awardEventId = award.eventId,
                    slug = award.slug,
                    position = 0 // set below
                )
            }.take(BadgeEventCodec.MAX_PIN_PAIRS)
                .mapIndexed { index, pin -> pin.copy(position = index) }

            badgeDao.replacePins(ownerNpub, pins)
            Log.d(TAG, " pin list stored for ${ownerNpub.take(12)} (${pins.size} badges)")
        } catch (e: Exception) {
            Log.e(TAG, "failed to process pin list: ${e.message}", e)
        }
    }

    /**
     * Handle a relay-authored kind 5 deletion: an `e` tag naming a stored
     * award revokes it (award + dependent pins removed); one naming a stored
     * definition event retires the badge.
     */
    suspend fun onInboundDeletionEvent(event: GenericEvent) {
        try {
            val deletedIds = event.tags.toPlainTags()
                .filter { it.isNotEmpty() && it[0] == "e" }
                .mapNotNull { it.getOrNull(1) }
            if (deletedIds.isEmpty()) return

            for (id in deletedIds) {
                val award = badgeDao.getAward(id)
                if (award != null) {
                    badgeDao.deleteAward(id)
                    badgeDao.deletePinsByAward(id)
                    Log.d(TAG, " badge award revoked: ${award.slug} (${id.take(12)})")
                } else {
                    badgeDao.deleteDefinitionByEventId(id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to process badge deletion: ${e.message}", e)
        }
    }

    // ---------------- Publishing (kind 30008) ----------------

    override suspend fun publishPinList(ownerNpub: String, awards: List<BadgeAward>): String? {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
            ?: return "Not logged in"
        if (Bech32Utils.hexToNpub(identity.publicKey.toHexString()) != ownerNpub) {
            return "Active account does not match this profile"
        }

        // Mirror the relay's pre-store validation so obvious mistakes fail
        // before hitting the wire (BADGES_PROTOCOL.md §Relay-side validation).
        if (awards.size > BadgeEventCodec.MAX_PIN_PAIRS) {
            return "invalid: at most ${BadgeEventCodec.MAX_PIN_PAIRS} badges may be pinned"
        }
        if (awards.isNotEmpty()) {
            val stored = badgeDao.getAwardsByIds(awards.map { it.eventId })
            if (stored.size != awards.distinctBy { it.eventId }.size ||
                stored.any { it.awardeeNpub != ownerNpub }
            ) {
                return "invalid: pin list references an unknown or revoked award"
            }
            for (award in awards) {
                if (badgeDao.getDefinition(award.slug) == null) {
                    return "invalid: badge '${award.slug}' is no longer available"
                }
            }
        }

        val pinPairs = awards.map { BadgeEventCodec.PinPair(it.definitionAddress, it.eventId) }
        val tagLists = BadgeEventCodec.buildPinTags(pinPairs)
        val tags = tagLists.map { nostr.event.tag.GenericTag(it[0], it.drop(1)) }
        val content = if (awards.isEmpty()) "" else BadgeEventCodec.PIN_CONTENT

        return try {
            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.PROFILE_BADGES)
                .content(content)
                .createdAt(System.currentTimeMillis() / 1000)
                .tags(tags as List<nostr.event.BaseTag>)
                .build()
            identity.sign(event)

            // Optimistic local update; snapshot restores on rejection.
            val previousPins = badgeDao.getPinsFor(ownerNpub)
            badgeDao.replacePins(
                ownerNpub,
                awards.mapIndexed { index, award ->
                    BadgePinEntity(
                        ownerNpub = ownerNpub,
                        awardEventId = award.eventId,
                        slug = award.slug,
                        position = index
                    )
                }
            )

            // Badge events stay internal to the DeSent relay — never fan out
            // to the user's write relays.
            relayRepository.publishEventToRelay(event, RelayConfig.EMAIL_RELAY_URL)

            val verdict = withTimeoutOrNull(PUBLISH_VERDICT_TIMEOUT_MS) {
                relayRepository.publishResults.first {
                    it.eventId == event.id && it.relayUrl == RelayConfig.EMAIL_RELAY_URL
                }
            }
            if (verdict != null && !verdict.success) {
                badgeDao.replacePins(ownerNpub, previousPins)
                Log.w(TAG, "pin list rejected by relay: ${verdict.message}")
                verdict.message.ifBlank { "invalid: the relay rejected the pin list" }
            } else {
                Log.d(TAG, " pin list published (${awards.size} badges)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "pin list publish failed: ${e.message}", e)
            "Publish failed: ${e.message}"
        }
    }

    // ---------------- Helpers ----------------

    private fun List<nostr.event.BaseTag>.toPlainTags(): List<List<String>> = mapNotNull { tag ->
        val params = (tag as? GenericTag)?.getParams() ?: return@mapNotNull null
        listOfNotNull(tag.getCode()) + params
    }

    private fun xyz.desent.data.local.database.dao.BadgeJoinRow.toEarnedBadge(pinned: Boolean) =
        EarnedBadge(
            definition = BadgeDefinition(
                slug = slug,
                name = name,
                description = description,
                imageUrl = imageUrl,
                thumbUrl = thumbUrl,
                iconName = iconName,
                color = color
            ),
            award = BadgeAward(
                eventId = awardEventId,
                awardeeNpub = awardeeNpub,
                slug = slug,
                definitionAddress = definitionAddress,
                awardedAt = awardedAt
            ),
            isPinned = pinned
        )

    private fun xyz.desent.data.local.database.dao.BadgeJoinRowWithPin.toEarnedBadge() =
        xyz.desent.data.local.database.dao.BadgeJoinRow(
            slug = slug,
            name = name,
            description = description,
            imageUrl = imageUrl,
            thumbUrl = thumbUrl,
            iconName = iconName,
            color = color,
            awardEventId = awardEventId,
            awardeeNpub = awardeeNpub,
            awardedAt = awardedAt,
            definitionAddress = definitionAddress
        ).toEarnedBadge(pinned = isPinned)
}
