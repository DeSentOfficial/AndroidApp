package xyz.desent.data.nostr

import android.content.Context
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.data.RelayConfig
import xyz.desent.data.dto.UserMetadataDto
import xyz.desent.data.local.database.dao.FollowDao
import xyz.desent.data.local.database.dao.EmailDao
import xyz.desent.data.local.database.dao.UserDao
import xyz.desent.data.mapper.FollowMapper
import xyz.desent.data.mapper.UserMapper
import xyz.desent.data.mapper.EmailMapper
import xyz.desent.domain.model.Email
import xyz.desent.domain.model.DkimStatus
import xyz.desent.domain.model.EmailType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.ListSerializer
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag

class NostrEventProcessor(
    private val context: Context,
    private val userDao: UserDao,
    private val followDao: FollowDao,
    private val userMapper: UserMapper,
    private val followMapper: FollowMapper,
    private val giftWrapEncryptionService: GiftWrapEncryptionService,
    private val emailDao: xyz.desent.data.local.database.dao.EmailDao? = null,
    private val emailMapper: EmailMapper? = null,
    private val emailOutboxDao: xyz.desent.data.local.database.dao.EmailOutboxDao? = null,
    private val securityAlertDao: xyz.desent.data.local.database.dao.SecurityAlertDao? = null,
    // Relay-sync cursors (see RelaySyncWatermarks): null in unit tests.
    private val relaySyncWatermarks: xyz.desent.data.relay.RelaySyncWatermarks? = null
) {

    // NIP-46 bunker (remote signer) set separately to avoid circular dependency
    private var nip46BunkerService: xyz.desent.data.nip46.Nip46BunkerService? = null

    fun setNip46BunkerService(service: xyz.desent.data.nip46.Nip46BunkerService) {
        this.nip46BunkerService = service
        android.util.Log.d("NostrEventProcessor", " NIP-46 bunker service set")
    }

    // Spam filter set separately to avoid circular dependency
    private var spamFilterRepository: xyz.desent.domain.repository.SpamFilterRepository? = null

    /**
     * Set the spam filter so inbound emails can be stamped at ingestion time
     * (see refs/SPAM_FILTER_REFERENCE.md).
     */
    fun setSpamFilterRepository(repository: xyz.desent.domain.repository.SpamFilterRepository) {
        this.spamFilterRepository = repository
        android.util.Log.d("NostrEventProcessor", " Spam filter set")
    }

    // PGP key repository set separately to avoid circular dependency: lets
    // inbound PGP mail be transiently decrypted for spam scoring (the
    // decrypted body is scored, never stored — ANDROID_PGP.md §3 spam note).
    private var pgpKeyRepository: xyz.desent.domain.repository.PgpKeyRepository? = null

    fun setPgpKeyRepository(repository: xyz.desent.domain.repository.PgpKeyRepository) {
        this.pgpKeyRepository = repository
        android.util.Log.d("NostrEventProcessor", " PGP key repository set")
    }

    // NIP-78 private storage (kind 30078) set separately to avoid circular dependency.
    private var privateStorageRepository: xyz.desent.data.repository.PrivateStorageRepositoryImpl? = null

    /**
     * Set the private-storage repository so inbound kind-30078 events are
     * decrypted and cached (see refs/PRIVATE_STORAGE_PROTOCOL.md).
     */
    fun setPrivateStorageRepository(repository: xyz.desent.data.repository.PrivateStorageRepositoryImpl) {
        this.privateStorageRepository = repository
        android.util.Log.d("NostrEventProcessor", " Private storage repository set")
    }

    // Mail-state overlay (mail folders + synced read state) set separately to
    // avoid circular dependency: freshly ingested mail adopts a pre-existing
    // overlay entry (apply-on-arrival for entries that landed before the mail).
    private var mailFolderRepository: xyz.desent.data.repository.MailFolderRepositoryImpl? = null

    fun setMailFolderRepository(repository: xyz.desent.data.repository.MailFolderRepositoryImpl) {
        this.mailFolderRepository = repository
        android.util.Log.d("NostrEventProcessor", " Mail folder repository set")
    }

    // NIP-52 calendar (kinds 31922-31925) set separately to avoid circular dependency.
    private var calendarRepository: xyz.desent.data.repository.CalendarRepositoryImpl? = null

    /**
     * Set the calendar repository so inbound kind-31922..31925 events are
     * decrypted and cached (see refs/CALENDAR_PROTOCOL.md).
     */
    fun setCalendarRepository(repository: xyz.desent.data.repository.CalendarRepositoryImpl) {
        this.calendarRepository = repository
        android.util.Log.d("NostrEventProcessor", " Calendar repository set")
    }

    /**
     * NIP-EMAIL delivery-receipt processor. Lazily built from the DAOs the
     * processor already holds (both nullable during unit-test construction);
     * null until the email stack is wired, same contract as before.
     */
    private val deliveryReceiptHandler: DeliveryReceiptHandler? by lazy {
        val dao = emailDao ?: return@lazy null
        val outbox = emailOutboxDao ?: return@lazy null
        DeliveryReceiptHandler(dao, outbox)
    }

    // NIP-EMAIL mailbox configuration (kind 35050) set separately to avoid a
    // constructor dependency cycle. The handler owns the NIP-44 self-decrypt.
    private var mailboxConfigHandler: xyz.desent.data.mailbox.MailboxConfigHandler? = null

    fun setMailboxConfigHandler(handler: xyz.desent.data.mailbox.MailboxConfigHandler) {
        this.mailboxConfigHandler = handler
        android.util.Log.d("NostrEventProcessor", " Mailbox config handler set")
    }

    // DeSent user settings (kind 30079) set separately to avoid a constructor
    // dependency cycle. The handler parses the plaintext settings JSON.
    private var securityConfigHandler: xyz.desent.data.security.SecurityConfigHandler? = null

    fun setSecurityConfigHandler(handler: xyz.desent.data.security.SecurityConfigHandler) {
        this.securityConfigHandler = handler
        android.util.Log.d("NostrEventProcessor", " Security config handler set")
    }

    // NIP-58 badges (kinds 8/30008/30009/5) set separately to avoid circular
    // dependency. Cache + parse live in the repository.
    private var badgeRepository: xyz.desent.data.repository.BadgeRepositoryImpl? = null

    /**
     * Set the badge repository so inbound badge events are cached
     * (see refs/FromServer/BADGES_PROTOCOL.md).
     */
    fun setBadgeRepository(repository: xyz.desent.data.repository.BadgeRepositoryImpl) {
        this.badgeRepository = repository
        android.util.Log.d("NostrEventProcessor", " Badge repository set")
    }

    // Badge-award notices (kind-1010 direction:"badge") set separately to
    // avoid a constructor dependency cycle. Persistence + dedup live here.
    private var badgeNoticeHandler: BadgeNoticeHandler? = null

    fun setBadgeNoticeHandler(handler: BadgeNoticeHandler) {
        this.badgeNoticeHandler = handler
        android.util.Log.d("NostrEventProcessor", " Badge notice handler set")
    }

    /**
     * Score [entity] via the spam filter (if wired) and return a copy with the
     * verdict stamped. If no filter is wired (legacy/early init), the entity is
     * returned unchanged. SYSTEM emails are exempt by the classifier itself.
     *
     * PGP mail: content heuristics/Bayesian tokens would score meaningless
     * armor, so when the account key is available the message is decrypted
     * TRANSIENTLY and the plaintext is what gets classified. The stored
     * entity always keeps the armor; without a key only the header tags +
     * blocklist layers score (PGP_ENCRYPTION.md § Spam filter).
     */
    private suspend fun stampSpam(
        entity: xyz.desent.data.local.database.entity.EmailEntity
    ): xyz.desent.data.local.database.entity.EmailEntity {
        val repo = spamFilterRepository ?: return entity
        val mapper = emailMapper ?: return entity
        return try {
            var email = mapper.mapToDomain(entity)
            if (entity.isPgpEncrypted) {
                val decrypted = pgpKeyRepository?.decryptMessage(entity.content)?.getOrNull()
                email = if (decrypted != null) {
                    email.copy(content = String(decrypted, Charsets.UTF_8))
                } else {
                    // Undecryptable ciphertext must contribute zero content
                    // signal, not armor noise.
                    email.copy(content = "")
                }
            }
            val verdict = repo.classify(email)
            entity.copy(
                spamScore = verdict.score,
                isSpam = verdict.isSpam,
                spamReasons = if (verdict.reasons.isEmpty()) null else verdict.reasons.joinToString(",")
            )
        } catch (e: Exception) {
            android.util.Log.w("NostrEventProcessor", "spam stamp failed: ${e.message}")
            entity
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    
    private val _events = MutableSharedFlow<GenericEvent>()
    val events: SharedFlow<GenericEvent> = _events.asSharedFlow()

    private val _eventsWithRelay = MutableSharedFlow<Pair<GenericEvent, String?>>()
    
    private val _metadataArrivals = MutableSharedFlow<String>(replay = 0)
    val metadataArrivals: SharedFlow<String> = _metadataArrivals.asSharedFlow()

    private val _emailArrivals = MutableSharedFlow<Email>(replay = 0)
    val emailArrivals: SharedFlow<Email> = _emailArrivals.asSharedFlow()

    /**
     * Emits each newly stored login-security alert (kind-1010 rumor,
     * `direction: "security"`). Collected by the notification dispatcher —
     * only live arrivals fire a push; re-hydrated backlog is silent because
     * it never flows through here more than once per event id.
     */
    private val _securityAlertArrivals =
        MutableSharedFlow<xyz.desent.domain.model.SecurityAlert>(replay = 0)
    val securityAlertArrivals: SharedFlow<xyz.desent.domain.model.SecurityAlert> =
        _securityAlertArrivals.asSharedFlow()

    /**
     * Emits each relay-sealed badge award notice (kind-1010 rumor,
     * `direction: "badge"`; ANDROID_BADGES.md §7) on FIRST persistence of
     * its wrap event id. Collected by the notification dispatcher — never
     * routed to the inbox. Later re-downloads (login backlog) stay silent;
     * the durable record lives in `badge_notices` / the tray.
     */
    private val _badgeAwardArrivals =
        MutableSharedFlow<xyz.desent.domain.model.BadgeAwardNotice>(replay = 0)
    val badgeAwardArrivals: SharedFlow<xyz.desent.domain.model.BadgeAwardNotice> =
        _badgeAwardArrivals.asSharedFlow()

    private val _followArrivals = MutableSharedFlow<String>(replay = 0)
    val followArrivals: SharedFlow<String> = _followArrivals.asSharedFlow()

    /**
     * Emits every inbound kind-10002 relay-list event (raw, unparsed — the
     * consumer validates author and parses `r` tags). One-shot fetches of the
     * user's own NIP-65 list await this (fetchOwnRelayList mirrors
     * fetchUserContacts).
     */
    private val _relayListArrivals = MutableSharedFlow<GenericEvent>(replay = 0)
    val relayListArrivals: SharedFlow<GenericEvent> = _relayListArrivals.asSharedFlow()

    // Generous: a single reconnect can replay the whole limit-N backlog, and
    // anything that falls out of this LRU gets re-decrypted for nothing.
    private val MAX_SEEN_EVENTS = 20_000
    private val seenEventIds = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                size > MAX_SEEN_EVENTS
        }
    )

    private var isProcessing = false
    private var currentUserPubKeyHex: String? = null
    
    fun setCurrentUserPubKey(pubKeyHex: String?) {
        currentUserPubKeyHex = pubKeyHex
    }
    
    fun startProcessing() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            _eventsWithRelay.collect { (event, relayUrl) ->
                processEvent(event, relayUrl)
            }
        }
    }

    fun submitEvent(event: GenericEvent, relayUrl: String? = null) {
        scope.launch {
            _events.emit(event)
            _eventsWithRelay.emit(Pair(event, relayUrl))
        }
    }

    private suspend fun processEvent(event: GenericEvent, relayUrl: String? = null) {
        // Advance the relay-sync cursors BEFORE session dedup: after a cursor
        // reset (Settings "reset sync checkpoints") re-sent events must be
        // able to re-seed the cursor even though the seen-LRU would skip them.
        recordSyncWatermark(event)
        // Skip events we've already processed this session (relays resend the
        // same events on every subscription refresh / from multiple peers).
        if (seenEventIds.put(event.id, true) != null) return
        try {

            when (event.kind) {
                NostrKinds.SET_METADATA -> {
                    processMetadataEvent(event)
                }
                NostrKinds.CONTACT_LIST -> {
                    processContactsEvent(event)

                    // Emit signal that follows have arrived
                    val followerNpub = Bech32Utils.hexToNpub(event.pubKey.toHexString())
                    _followArrivals.emit(followerNpub)
                }
                NostrKinds.RELAY_LIST -> {
                    _relayListArrivals.emit(event)
                }
                NostrKinds.GIFT_WRAP -> {
                    processGiftWrapEvent(event, relayUrl)
                }
                NostrKinds.NIP46_REQUEST -> {
                    // Standard NIP-46 raw transport (kind 24133) from external
                    // clients. Decrypt + policy decisions live in the bunker
                    // service; unaddressed/foreign events are ignored there.
                    nip46BunkerService?.handleRawRequest(event, relayUrl)
                }
                NostrKinds.APPLICATION_SPECIFIC_DATA -> {
                    privateStorageRepository?.onInboundPrivateStorageEvent(event)
                }
                NostrKinds.MAILBOX_CONFIGURATION -> {
                    mailboxConfigHandler?.onInboundMailboxConfigEvent(event)
                }
                NostrKinds.USER_SETTINGS -> {
                    securityConfigHandler?.onInboundUserSettingsEvent(event)
                }
                NostrKinds.CALENDAR_DATE_BASED_EVENT,
                NostrKinds.CALENDAR_TIME_BASED_EVENT,
                NostrKinds.CALENDAR_EVENT,
                NostrKinds.CALENDAR_RSVP_EVENT -> {
                    calendarRepository?.onInboundCalendarEvent(event)
                }
                NostrKinds.DELETION -> {
                    // Badge-award revocations (e-tag naming a kind 8 we
                    // cached) route to the badge repo; other deletions have
                    // no handler.
                    badgeRepository?.onInboundDeletionEvent(event)
                }
                NostrKinds.BADGE_AWARD,
                NostrKinds.BADGE_DEFINITION,
                NostrKinds.PROFILE_BADGES -> {
                    badgeRepository?.onInboundBadgeEvent(event)
                }
                else -> {
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EventProcessor", " Failed to process event ${event.id}: ${e.message}", e)
        }
    }
    
    /**
     * Feed the raw event into the per-account relay-sync cursors so the next
     * REQ for the matching scope can use `since` instead of re-downloading
     * the full backlog. Only events belonging to the active account bump a
     * cursor (a foreign event must never advance ours, or `since` would skip
     * mail we have not seen).
     */
    private suspend fun recordSyncWatermark(event: GenericEvent) {
        val watermarks = relaySyncWatermarks ?: return
        val activePubkey = currentUserPubKeyHex ?: return
        val scope = syncWatermarkScopeFor(event, activePubkey) ?: return
        watermarks.record(activePubkey, scope, event.createdAt)
    }

    private fun syncWatermarkScopeFor(event: GenericEvent, activePubkey: String): String? {
        val isOwnEvent = event.pubKey.toHexString() == activePubkey
        return when (event.kind) {
            NostrKinds.GIFT_WRAP -> {
                // Wraps we sent (authors) or received (#p) — both match the
                // giftwrap subscription's two filters.
                val addressedToUs = (event.tags.find { (it as? GenericTag)?.getCode() == "p" }
                    as? GenericTag)?.getParams()?.firstOrNull() == activePubkey
                if (isOwnEvent || addressedToUs) {
                    xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_GIFT_WRAP
                } else null
            }
            NostrKinds.NIP46_REQUEST -> {
                val addressedToUs = (event.tags.find { (it as? GenericTag)?.getCode() == "p" }
                    as? GenericTag)?.getParams()?.firstOrNull() == activePubkey
                if (isOwnEvent || addressedToUs) {
                    xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_NIP46_RAW
                } else null
            }
            NostrKinds.APPLICATION_SPECIFIC_DATA ->
                if (isOwnEvent) xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_PRIVATE_STORAGE else null
            NostrKinds.MAILBOX_CONFIGURATION ->
                if (isOwnEvent) xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_MAILBOX else null
            NostrKinds.USER_SETTINGS ->
                if (isOwnEvent) xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_USER_SETTINGS else null
            NostrKinds.CALENDAR_DATE_BASED_EVENT,
            NostrKinds.CALENDAR_TIME_BASED_EVENT,
            NostrKinds.CALENDAR_EVENT,
            NostrKinds.CALENDAR_RSVP_EVENT ->
                if (isOwnEvent) xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_CALENDAR else null
            else -> null
        }
    }

    private suspend fun processMetadataEvent(event: GenericEvent) {
        try {
            val npub = Bech32Utils.hexToNpub(event.pubKey.toHexString())

            // Replaceable event (kind 0): skip only if stored metadata is strictly
            // newer. Equal-timestamp events must re-process so newly-added columns
            // (e.g. banner) get backfilled into already-cached rows.
            val existing = userDao.getUserByNpub(npub)

            // A login placeholder row carries no real profile data (only a
            // truncated npub as name). It must never win the replaceable-event
            // staleness comparison, otherwise a real kind-0 is discarded. This is
            // critical for upgraded installs whose placeholder was seeded with
            // createdAt = <login time> by older builds — that timestamp is newer
            // than the user's originally-published kind-0, so the real profile
            // would be silently dropped without this guard.
            val isPlaceholder = existing != null &&
                existing.picture == null &&
                existing.displayName == null &&
                existing.about == null &&
                existing.nip05 == null
            if (existing != null && !isPlaceholder && event.createdAt < existing.createdAt) return

            val metadata = json.decodeFromString<UserMetadataDto>(event.content)
            
            
            val user = xyz.desent.domain.model.User(
                npub = npub,
                name = metadata.name,
                displayName = metadata.display_name,
                about = metadata.about,
                picture = metadata.picture,
                banner = metadata.banner,
                website = metadata.website,
                lud06 = metadata.lud06,
                lud16 = metadata.lud16,
                nip05 = metadata.nip05,
                createdAt = event.createdAt
            )
            
            userDao.insertUser(userMapper.mapToEntity(user))

            android.util.Log.d("MetadataProcessor", " Stored metadata for ${npub.take(8)} (name=${metadata.name}, hasPicture=${metadata.picture != null}, eventCreatedAt=${event.createdAt})")

            // Signal that metadata arrived for this npub
            _metadataArrivals.emit(npub)
        } catch (e: Exception) {
            android.util.Log.e("MetadataProcessor", " Failed to parse metadata: ${e.message}", e)
        }
    }
    
    private suspend fun processContactsEvent(event: GenericEvent) {
        try {
            val followerNpub = Bech32Utils.hexToNpub(event.pubKey.toHexString())

            // Replaceable event (kind 3): skip if we already stored a contact list
            // for this pubkey that is at least as new as this event.
            val storedCreatedAt = followDao.getLatestContactListCreatedAt(followerNpub)
            if (storedCreatedAt != null && event.createdAt <= storedCreatedAt) return

            val follows = event.tags
                 .filter { tag -> tag.getCode() == "p" }
                 .mapNotNull { tag ->
                    val genTag = tag as? GenericTag
                    val params = genTag?.getParams() ?: run {
                        return@mapNotNull null
                    }
                    if (params.isEmpty()) run {
                        return@mapNotNull null
                    }

                    val followingNpubHex = params.getOrNull(0) ?: run {
                        return@mapNotNull null
                    }

                    try {
                        val followingNpub = Bech32Utils.hexToNpub(followingNpubHex)

                        // NIP-02 p-tag layout: ["p", hex, relayUrl?, petname?].
                        // Param 1 (relay hint) is advisory and intentionally
                        // discarded — the local relay pool is the source of
                        // truth. Param 2 (petname) is preserved so the kind 3
                        // publish path can round-trip it (see NostrRepository).
                        val petname = params.getOrNull(2)?.takeIf { it.isNotBlank() }

                        xyz.desent.domain.model.Follow(
                            followerNpub = followerNpub,
                            followingNpub = followingNpub,
                            isFavorite = false,  // Will be updated below to preserve existing favorites
                            createdAt = event.createdAt,
                            petname = petname
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("ContactsProcessor", "     Failed to parse following pubkey: ${e.message}")
                        null
                    }
                }


                if (follows.isNotEmpty()) {
                    // Get ALL existing follows involving this user (both as follower and following)
                    // This includes follows where we follow others AND follows where others follow us
                    val existingFollowsAsFollower = followDao.getFollowsByFollower(followerNpub)
                    val existingFollowsAsFollowing = followDao.observeFollowersByNpub(followerNpub).first()
                    val existingFollowsMap = (existingFollowsAsFollower + existingFollowsAsFollowing)
                        .associateBy { entity -> "${entity.followerNpub}-${entity.followingNpub}" }


                    // Update new follows to preserve favorite status. A contact
                    // present in the relay list is a REAL follow: the fresh row
                    // keeps isLocalOnly = false even if a prior local-only
                    // favorite row existed for it.
                    val preservedFollows = follows.map { follow ->
                        val key = "${follow.followerNpub}-${follow.followingNpub}"
                        val existing = existingFollowsMap[key]
                        follow.copy(isFavorite = existing?.isFavorite ?: false)
                    }

                    // Re-insert any follows where we are the following that weren't in the new contact list
                    // This preserves follows we created for favoriting followers
                    val preservedFollowingFollows = existingFollowsAsFollowing.filter { entity ->
                        follows.none { it.followingNpub == entity.followingNpub }
                    }

                    // Device-local favorite-only rows (created by favoriting a
                    // user not actually followed — see FollowRepositoryImpl.
                    // toggleFavorite) aren't on the relay, so the replace would
                    // delete them; re-insert them so favorites survive the
                    // account's own kind-3 sync.
                    val preservedLocalOnlyFollows = existingFollowsAsFollower.filter { entity ->
                        entity.isLocalOnly && follows.none { it.followingNpub == entity.followingNpub }
                    }

                    followDao.replaceFollows(
                        followerNpub,
                        followMapper.mapToEntityList(preservedFollows),
                        preservedFollowingFollows + preservedLocalOnlyFollows
                    )
                }
        } catch (e: Exception) {
            android.util.Log.e("ContactsProcessor", " Failed to process contacts: ${e.message}", e)
        }
    }
    
    /**
     * Process NIP-17 GiftWrap event (kind 1059). Internal for unit tests.
     */
    internal suspend fun processGiftWrapEvent(event: GenericEvent, relayUrl: String? = null) {
        try {
            // The outer wrap author is a ONE-TIME keypair under NIP-59 (and for
            // pre-conformance DeSent traffic, the sender). It is never used for
            // message attribution — the real sender is the seal/rumor pubkey
            // recovered during unwrap (unwrapped.senderNpub).
            val wrapAuthorHex = event.pubKey.toHexString()


            // Try to unwrap the GiftWrap
            val unwrapped = giftWrapEncryptionService.unwrapGift(
                event.content,
                event.pubKey.toHexString()
            ).getOrNull()
                ?: run {
                    return
                }


            // Extract receiver from tags if present
            val receiverTag = event.tags.find { (it as? GenericTag)?.getCode() == "p" }
            val receiverHex = (receiverTag as? GenericTag)?.getParams()?.firstOrNull()
            val receiverNpub = receiverHex?.let { Bech32Utils.hexToNpub(it) }

            // Only process if we're the author (pre-conformance self-authored
            // wraps) OR the receiver (the normal NIP-59 case — the wrap author
            // is a one-time key, so receiver-tag membership is the real test).
            val currentUserHex = currentUserPubKeyHex
            if (currentUserHex != null && wrapAuthorHex != currentUserHex && receiverHex != currentUserHex) {
                return
            }

            // Third-party-sourced wraps (the relay-mirroring backup fetch pulls
            // kind-1059s from the user's own NIP-65 relays): those relays
            // may also hold the user's other NIP-17 traffic. Only DeSent
            // email rumors may process — everything else (chats, NIP-46
            // bunker traffic, calendar shares, confirmations, unknown
            // kinds) is dumped. Necessarily post-unwrap: the rumor kind
            // lives inside the NIP-44 ciphertext. Desent-sourced wraps keep
            // the full routing below unchanged.
            if (relayUrl != null &&
                relayUrl != RelayConfig.EMAIL_RELAY_URL &&
                !isThirdPartyEmailRumor(unwrapped)
            ) {
                android.util.Log.i(
                    "GiftWrapProcessor",
                    "🗑️ Dumped third-party wrap ${event.id.take(8)}: rumor kind ${unwrapped.kind} is not DeSent email"
                )
                return
            }

            // Check if email (kind 14) or DM (kind 4)
            when (unwrapped.kind) {
                NostrKinds.EMAIL_MESSAGE -> {
                    // NIP-EMAIL (kind 1010): route on the `direction` tag.
                    // See refs/FromServer/NIP-EMAIL.md § Direction.
                    val direction = unwrapped.tags
                        .firstOrNull { it.isNotEmpty() && it[0] == "direction" }
                        ?.getOrNull(1)
                    when (direction ?: xyz.desent.data.EmailBridgeTags.DIRECTION_INBOUND) {
                        xyz.desent.data.EmailBridgeTags.DIRECTION_INBOUND -> {
                            processEmailEvent(unwrapped, event, receiverNpub)
                        }
                        xyz.desent.data.EmailBridgeTags.DIRECTION_DELIVERY_RECEIPT -> {
                            // Receipts are sealed by the relay's npub (the gift
                            // wrap itself is signed by a random one-time key,
                            // so the outer author proves nothing); only
                            // relay-sealed receipts may resolve outbox state.
                            val sealSignerHex = runCatching {
                                Bech32Utils.npubToHex(unwrapped.senderNpub)
                            }.getOrNull()
                            if (sealSignerHex == RelayConfig.RELAY_PUBKEY_HEX) {
                                processDeliveryReceiptEvent(unwrapped, event, receiverNpub)
                            } else {
                                android.util.Log.w(
                                    "GiftWrapProcessor",
                                    "⚠️ Ignoring delivery-receipt not sealed by relay npub"
                                )
                            }
                        }
                        xyz.desent.data.EmailBridgeTags.DIRECTION_SECURITY -> {
                            // Login-security alert (ANDROID_SECURITY_ALERTS.md):
                            // always relay-sealed, like delivery-receipts — an
                            // attacker wrapping their own "security" rumor must
                            // not be able to fabricate alerts.
                            val sealSignerHex = runCatching {
                                Bech32Utils.npubToHex(unwrapped.senderNpub)
                            }.getOrNull()
                            if (sealSignerHex == RelayConfig.RELAY_PUBKEY_HEX) {
                                processSecurityAlertEvent(unwrapped, event, receiverNpub)
                            } else {
                                android.util.Log.w(
                                    "GiftWrapProcessor",
                                    "⚠️ Ignoring security alert not sealed by relay npub"
                                )
                            }
                        }
                        xyz.desent.data.EmailBridgeTags.DIRECTION_BADGE -> {
                            // Badge award notice (ANDROID_BADGES.md §7): like
                            // security alerts, always relay-sealed — a forged
                            // "badge" rumor must not fabricate award notices.
                            val sealSignerHex = runCatching {
                                Bech32Utils.npubToHex(unwrapped.senderNpub)
                            }.getOrNull()
                            if (sealSignerHex == RelayConfig.RELAY_PUBKEY_HEX) {
                                processBadgeAwardNotice(unwrapped, event)
                            } else {
                                android.util.Log.w(
                                    "GiftWrapProcessor",
                                    "⚠️ Ignoring badge notice not sealed by relay npub"
                                )
                            }
                        }
                        else -> {
                            // direction: outbound — our own publish echo. The
                            // relay never stores outbound events; if one comes
                            // back anyway it is already in our local store.
                        }
                    }
                }
                NostrKinds.PRIVATE_DIRECT_MESSAGE -> {
                    // NIP-46 bunker traffic: rumor tagged ["bridge","nip46"]. Must be
                    // checked BEFORE email (email rumors also carry a "bridge" tag).
                    // The session pubkey is the seal signer (unwrapped.senderNpub),
                    // not the gift-wrap's one-time outer signer.
                    val isNip46 = unwrapped.tags.any { it.size >= 2 && it[0] == "bridge" && it[1] == "nip46" }
                    if (isNip46) {
                        val sessionHex = Bech32Utils.npubToHex(unwrapped.senderNpub)
                        if (nip46BunkerService == null) {
                            android.util.Log.w("Nip46Bunker", " NIP-46 gift wrap received but bunker service is not wired (session=${sessionHex.take(8)})")
                        } else {
                            android.util.Log.d("Nip46Bunker", " NIP-46 gift wrap received from session=${sessionHex.take(8)}; routing to bunker")
                        }
                        nip46BunkerService?.handleIncoming(unwrapped.content, sessionHex)
                        return@processGiftWrapEvent
                    }

                    // Calendar share / RSVP: rumor tagged ["bridge","calendar"].
                    // Checked BEFORE email (calendar rumors also carry a "bridge"
                    // tag, so the email test below would otherwise swallow them).
                    val isCalendar = unwrapped.tags.any { it.size >= 2 && it[0] == "bridge" && it[1] == "calendar" }
                    if (isCalendar) {
                        val receiver = receiverNpub ?: currentUserPubKeyHex?.let { Bech32Utils.hexToNpub(it) }
                        if (receiver != null) {
                            calendarRepository?.onInboundCalendarRumor(unwrapped, receiver)
                        } else {
                            android.util.Log.w("GiftWrapProcessor", "calendar rumor with no receiver")
                        }
                        return@processGiftWrapEvent
                    }

                    // Bridge confirmation: a relay-sealed rumor carrying a
                    // status line (✉/❌/⏳) + thread_token. Render as a system
                    // message in the relevant thread. Checked before email
                    // disambiguation because confirmations carry no
                    // email-specific tags. The check is on the SEAL SIGNER (the relay's npub) —
                    // the gift wrap itself is signed by a random one-time key,
                    // so the outer author proves nothing (same rule as the
                    // delivery-receipt branch above).
                    val first = unwrapped.content.trimStart().firstOrNull()
                    val isStatusLine = first == '✉' || first == '❌' || first == '⏳'
                    val confirmationSealSignerHex = runCatching {
                        Bech32Utils.npubToHex(unwrapped.senderNpub)
                    }.getOrNull()
                    val isConfirmation = confirmationSealSignerHex == RelayConfig.RELAY_PUBKEY_HEX &&
                        isStatusLine &&
                        unwrapped.tags.any { it.isNotEmpty() && it[0] == "thread_token" }
                    if (isConfirmation) {
                        processConfirmationEvent(unwrapped, event, receiverNpub)
                    } else {
                        // Disambiguate email: email rumors carry email-specific
                        // tags (sender, sender_domain, dkim, spf, message_id,
                        // bridge). Rumors without them are plain chat traffic
                        // this build no longer renders — ignored.
                        val emailTagNames = setOf("sender", "sender_domain", "dkim", "spf", "message_id", "bridge")
                        val isEmail = unwrapped.tags.any { tag -> tag.isNotEmpty() && tag[0] in emailTagNames }
                        if (isEmail) {
                            processEmailEvent(unwrapped, event, receiverNpub)
                        }
                    }
                }
                else -> {
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GiftWrapProcessor", " Failed to process GiftWrap: ${e.message}", e)
        }
    }

    private suspend fun processEmailEvent(
        unwrapped: xyz.desent.domain.model.UnwrappedContent,
        giftWrapEvent: GenericEvent,
        receiverNpub: String?
    ) {
        try {
            if (emailDao == null || emailMapper == null) {
                return
            }

            // Check if email already exists
            val existingEmail = emailDao!!.getEmailById(giftWrapEvent.id)
            if (existingEmail != null) {
                return
            }

            // Extract email tags. NIP-EMAIL (kind 1010) uses from/from_name/
            // from_domain; legacy kind-14 rumors used sender/sender_name/
            // sender_domain. Both spellings are accepted so legacy rows and a
            // not-yet-upgraded relay keep rendering during the transition.
            val subjectTag = unwrapped.tags.find { it[0] == "subject" }?.getOrNull(1) ?: "No Subject"
            val senderEmail = unwrapped.tags.find { it[0] == "from" }?.getOrNull(1)
                ?: unwrapped.tags.find { it[0] == "sender" }?.getOrNull(1)
                ?: "unknown"
            val senderDomain = unwrapped.tags.find { it[0] == "from_domain" }?.getOrNull(1)
                ?: unwrapped.tags.find { it[0] == "sender_domain" }?.getOrNull(1)
                ?: senderEmail.substringAfterLast('@', "").takeIf { senderEmail.contains('@') }
            val senderName = unwrapped.tags.find { it[0] == "from_name" }?.getOrNull(1)
                ?: unwrapped.tags.find { it[0] == "sender_name" }?.getOrNull(1)
            val replyTo = unwrapped.tags.find { it[0] == "reply_to" }?.getOrNull(1)
            val dkimStatusStr = unwrapped.tags.find { it[0] == "dkim" }?.getOrNull(1)?.lowercase() ?: "none"
            val spfStatusStr = unwrapped.tags.find { it[0] == "spf" }?.getOrNull(1)?.lowercase()
            val dmarcStatusStr = unwrapped.tags.find { it[0] == "dmarc" }?.getOrNull(1)?.lowercase()
            val dateStr = unwrapped.tags.find { it[0] == "date" }?.getOrNull(1)
            // RFC 5322 ids are normalized (angle brackets stripped) so that
            // `<a@x>` from one MUA and bare `a@x` from another group together.
            val inReplyTo = EmailThreadResolver.normalizeMessageId(
                unwrapped.tags.find { it[0] == "in_reply_to" }?.getOrNull(1)
            )
            val references = EmailThreadResolver.parseReferences(
                unwrapped.tags.find { it[0] == "references" }?.getOrNull(1)
            ).asReversed().joinToString(" ").takeIf { it.isNotEmpty() }
            // RFC 5322 recipient lists (END-01 §3.4): collect ALL to/cc tags
            // (multi-valued, display name in slot 3) and the envelope
            // delivered_to. Inbound bcc tags are deliberately not surfaced
            // (a conforming sender's MTA stripped them; END-01 §6).
            val parsedRecipients = EmailRecipientTags.parse(unwrapped.tags)
            val toRecipients = parsedRecipients.to
            val ccRecipients = parsedRecipients.cc
            val deliveredTo = parsedRecipients.deliveredTo
            // Legacy single-recipient field: the FIRST To mailbox, normalized
            // the same way as the list entries.
            val toEmail = toRecipients.firstOrNull()?.address
            // PGP E2E mail (ANDROID_PGP.md §3): ["pgp","encrypted"] marks the
            // message; inbound rumors also carry ["format","pgp"]. Content is
            // the armor — decrypted on-device only, at render/scoring time.
            val isPgpEncrypted = unwrapped.tags.any { tag ->
                tag.size >= 2 &&
                    tag[0] == xyz.desent.data.EmailBridgeTags.TAG_PGP &&
                    tag[1] == xyz.desent.data.EmailBridgeTags.PGP_VALUE_ENCRYPTED
            }
            val bodyFormat = if (isPgpEncrypted) {
                // The wire "pgp" format value is NOT a render format; PLAIN is
                // the placeholder until the decrypted body is sniffed.
                xyz.desent.domain.model.EmailBodyFormat.PLAIN
            } else {
                xyz.desent.domain.model.EmailBodyFormat.fromWire(
                    unwrapped.tags.find { it[0] == "format" }?.getOrNull(1)
                )
            }
            val emailTypeStr = unwrapped.tags.find { it[0] == "type" }?.getOrNull(1)?.lowercase() ?: "other"
            val bridge = unwrapped.tags.find { it[0] == "bridge" }?.getOrNull(1) ?: "email"
            val messageId = EmailThreadResolver.normalizeMessageId(
                unwrapped.tags.find { it[0] == "message_id" }?.getOrNull(1)
            )
            val threadToken = unwrapped.tags.find { it[0] == "thread_token" }?.getOrNull(1)
            val alias = unwrapped.tags.find { it[0] == "alias" }?.getOrNull(1)
            // AI-agent action proposal (ANDROID_AI_AGENTS.md §6): normal
            // inbound mail that additionally carries `["action",
            // "calendar.propose"]` + `["cal", <json>]`. Persisted so the
            // detail view can offer "Add to calendar".
            val actionTag = unwrapped.tags
                .find { it.isNotEmpty() && it[0] == xyz.desent.data.EmailBridgeTags.TAG_ACTION }
                ?.getOrNull(1)
            val calJson = unwrapped.tags
                .find { it.isNotEmpty() && it[0] == xyz.desent.data.EmailBridgeTags.TAG_CAL }
                ?.getOrNull(1)
            // NIP-EMAIL forwarding: provenance tag on mail re-delivered by
            // another Nostr key (mailbox migration). Value is the forwarder's
            // hex pubkey; stored as npub for display.
            val forwardedByNpub = unwrapped.tags
                .find { it.isNotEmpty() && it[0] == xyz.desent.data.EmailBridgeTags.TAG_FORWARDED_BY }
                ?.getOrNull(1)
                ?.let { runCatching { Bech32Utils.hexToNpub(it) }.getOrNull() }
            val recipientPubkey = unwrapped.tags.find { it[0] == "p" }?.getOrNull(1) ?: currentUserPubKeyHex ?: ""
            val recipientNpubFinal = Bech32Utils.hexToNpub(recipientPubkey)

            // Dedupe by Message-ID: forwarded mail carries the ORIGINAL
            // message_id (a migration re-run, or a forward racing the relay's
            // own download of the same message) must not insert a second copy.
            if (messageId != null &&
                emailDao!!.getEmailByMessageId(recipientNpubFinal, messageId) != null
            ) {
                return
            }

            // Parse attachment tags: ["attachment", sha256, mime_type, size, key_hex, filename]
            val attachments = unwrapped.tags
                .filter { it.isNotEmpty() && it[0] == "attachment" }
                .mapNotNull { tag ->
                    val sha = tag.getOrNull(1) ?: return@mapNotNull null
                    val mime = tag.getOrNull(2) ?: return@mapNotNull null
                    val size = tag.getOrNull(3)?.toLongOrNull() ?: 0L
                    val key = tag.getOrNull(4) ?: return@mapNotNull null
                    val filename = tag.getOrNull(5) ?: ""
                    xyz.desent.domain.model.EmailAttachment(
                        sha256 = sha,
                        mimeType = mime,
                        size = size,
                        keyHex = key,
                        filename = filename
                    )
                }

            // Parse enums
            val dkimStatus = when (dkimStatusStr) {
                "pass" -> DkimStatus.PASS
                "fail" -> DkimStatus.FAIL
                "disabled" -> DkimStatus.DISABLED
                else -> DkimStatus.NONE
            }

            val spfStatus = when (spfStatusStr) {
                "pass" -> xyz.desent.domain.model.SpfStatus.PASS
                "fail" -> xyz.desent.domain.model.SpfStatus.FAIL
                "disabled" -> xyz.desent.domain.model.SpfStatus.DISABLED
                null -> xyz.desent.domain.model.SpfStatus.UNKNOWN
                else -> xyz.desent.domain.model.SpfStatus.UNKNOWN
            }

            val emailType = when (emailTypeStr) {
                "transactional" -> EmailType.TRANSACTIONAL
                "promotional" -> EmailType.PROMOTIONAL
                "social" -> EmailType.SOCIAL
                "security" -> EmailType.SECURITY
                else -> EmailType.OTHER
            }

            val dmarcStatus = when (dmarcStatusStr) {
                "pass" -> xyz.desent.domain.model.DmarcStatus.PASS
                "fail" -> xyz.desent.domain.model.DmarcStatus.FAIL
                "none" -> xyz.desent.domain.model.DmarcStatus.NONE
                else -> xyz.desent.domain.model.DmarcStatus.UNKNOWN
            }

            // Sender-claimed date (seconds → millis). Null when absent.
            val senderDateMillis = dateStr?.toLongOrNull()?.times(1000L)

            // Client-owned threading (NIP-EMAIL): resolve the RFC 5322 chain
            // against stored messages. A resolved parent key may be a legacy
            // thread_token, which stitches new replies into old kind-14
            // threads. Unknown ancestry → own root (keyed by message_id).
            val threadRoot = if (unwrapped.kind == NostrKinds.EMAIL_MESSAGE) {
                val resolver = EmailThreadResolver { mid ->
                    emailDao!!.getEmailByMessageId(recipientNpubFinal, mid)?.threadKey
                }
                resolver.resolve(inReplyTo, references) ?: messageId
            } else {
                null
            }

            // Create email entity
            val emailEntity = xyz.desent.data.local.database.entity.EmailEntity(
                id = giftWrapEvent.id,
                recipientNpub = recipientNpubFinal,
                senderEmail = senderEmail,
                senderDomain = senderDomain,
                senderName = senderName,
                replyTo = replyTo,
                subject = subjectTag,
                content = unwrapped.content,
                bodyFormat = if (unwrapped.kind == NostrKinds.EMAIL_MESSAGE) bodyFormat.name else null,
                dkimStatus = dkimStatus.name,
                spfStatus = spfStatus.name,
                dmarcStatus = dmarcStatus.takeIf { it != xyz.desent.domain.model.DmarcStatus.UNKNOWN }?.name,
                emailType = emailType.name,
                bridge = bridge,
                messageId = messageId,
                inReplyTo = inReplyTo,
                referencesHeader = references,
                threadToken = threadToken,
                threadRoot = threadRoot,
                toEmail = toEmail,
                toRecipientsJson = emailMapper!!.encodeRecipients(toRecipients),
                ccRecipientsJson = emailMapper!!.encodeRecipients(ccRecipients),
                deliveredTo = deliveredTo,
                direction = if (unwrapped.kind == NostrKinds.EMAIL_MESSAGE) {
                    xyz.desent.domain.model.EmailDirection.INBOUND.name
                } else {
                    null
                },
                alias = alias,
                forwardedByNpub = forwardedByNpub,
                attachmentsJson = if (attachments.isEmpty()) null
                    else Json.encodeToString(
                        ListSerializer(xyz.desent.domain.model.EmailAttachment.serializer()),
                        attachments
                    ),
                senderDate = senderDateMillis,
                createdAt = giftWrapEvent.createdAt * 1000,
                threadSenderPubkey = unwrapped.senderNpub,
                isPgpEncrypted = isPgpEncrypted,
                actionTag = actionTag,
                calJson = calJson
            )

            val stamped = stampSpam(emailEntity)
            emailDao!!.insertEmail(stamped)

            // Mail-state overlay apply-on-arrival: an entry that synced before
            // this message (read on another device, filed before a restore)
            // adopts the row now (ANDROID_MAIL_FOLDERS.md §2).
            mailFolderRepository?.applyOnArrival(
                ownerNpub = recipientNpubFinal,
                messageKey = stamped.folderKey,
                isReadDefault = stamped.isRead
            )

            _emailArrivals.emit(emailMapper!!.mapToDomain(stamped))

        } catch (e: Exception) {
            android.util.Log.e("EmailProcessor", " Failed to process email: ${e.message}", e)
        }
    }

    /**
     * Process an email-bridge confirmation delivered from RELAY_PUBKEY. These
     * carry a status line ("✉ Email sent to …", "❌ Reply failed: …",
     * "⏳ … rate limit …") plus a `thread_token` tag. Stored as a SYSTEM email
     * so it renders inline in the relevant thread.
     */
    private suspend fun processConfirmationEvent(
        unwrapped: xyz.desent.domain.model.UnwrappedContent,
        giftWrapEvent: GenericEvent,
        receiverNpub: String?
    ) {
        try {
            if (emailDao == null || emailMapper == null) return

            val threadToken = unwrapped.tags
                .firstOrNull { it.isNotEmpty() && it[0] == "thread_token" }
                ?.getOrNull(1) ?: return

            // Dedup by event id.
            if (emailDao!!.getEmailById(giftWrapEvent.id) != null) return

            val recipientPubkey = unwrapped.tags
                .firstOrNull { it.isNotEmpty() && it[0] == "p" }?.getOrNull(1)
                ?: currentUserPubKeyHex ?: ""
            val recipientNpubFinal = Bech32Utils.hexToNpub(recipientPubkey)

            // Use wall-clock now: the gift wrap's created_at is NIP-59-randomized
            // (up to 2 days in the past) and would misorder the confirmation.
            val nowMs = System.currentTimeMillis()

            val entity = xyz.desent.data.local.database.entity.EmailEntity(
                id = giftWrapEvent.id,
                recipientNpub = recipientNpubFinal,
                senderEmail = "bridge@desent.xyz",
                senderDomain = "desent.xyz",
                senderName = "DeSent Email Bridge",
                replyTo = null,
                subject = "Delivery status",
                content = unwrapped.content,
                dkimStatus = DkimStatus.NONE.name,
                spfStatus = xyz.desent.domain.model.SpfStatus.UNKNOWN.name,
                emailType = EmailType.SYSTEM.name,
                bridge = "email",
                messageId = null,
                inReplyTo = null,
                threadToken = threadToken,
                alias = null,
                attachmentsJson = null,
                senderDate = nowMs,
                createdAt = nowMs,
                // Status info, not mail: never counts toward unread badges.
                isRead = true,
                deletionRequested = false,
                deletionEventId = null,
                threadSenderPubkey = unwrapped.senderNpub
            )

            val stamped = stampSpam(entity)
            emailDao!!.insertEmail(stamped)
            _emailArrivals.emit(emailMapper!!.mapToDomain(stamped))
            android.util.Log.d("EmailProcessor", "Confirmation stored for thread")
        } catch (e: Exception) {
            android.util.Log.e("EmailProcessor", " Failed to process confirmation: ${e.message}", e)
        }
    }

    /**
     * Process a NIP-EMAIL `direction: delivery-receipt` gift wrap (kind 1010)
     * from the relay's npub. Delegates to [DeliveryReceiptHandler]: the
     * receipt resolves a pending/timed-out outbox entry (its real subject and
     * sender tags are preserved), legacy OUTBOUND rows get an inline status
     * line, and receipts for sends made elsewhere reconstruct a synthetic
     * outbox entry. Matched receipts no longer insert email rows at all —
     * the sent bubble's status footer renders the delivery state, and bridge
     * receipts can no longer pile up in the inbox.
     */
    private suspend fun processDeliveryReceiptEvent(
        unwrapped: xyz.desent.domain.model.UnwrappedContent,
        giftWrapEvent: GenericEvent,
        receiverNpub: String?
    ) {
        try {
            val handler = deliveryReceiptHandler ?: return
            val applied = handler.process(
                unwrapped = unwrapped,
                giftWrapEventId = giftWrapEvent.id,
                receiverNpub = receiverNpub,
                currentUserPubKeyHex = currentUserPubKeyHex
            )
            if (applied) {
                android.util.Log.d(
                    "EmailProcessor",
                    "✓ Delivery receipt applied (${giftWrapEvent.id.take(12)}) for to=${unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == "to" }?.getOrNull(1)}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("EmailProcessor", " Failed to process delivery receipt: ${e.message}", e)
        }
    }

    /**
     * Process a NIP-EMAIL `direction: security` gift wrap (kind 1010) sealed
     * by the relay's npub — a login-security notification
     * (refs/FromServer/ANDROID_SECURITY_ALERTS.md §3-4). Stored in the
     * dedicated `security_alerts` table (never the emails table, never
     * spam-stamped) and emitted on [securityAlertArrivals] so the dispatcher
     * can push a first-sight notification.
     *
     * The relay dedups logins per (pubkey, device fingerprint) over 15 min —
     * 1 alert ≠ 1 auth event. `time` is the authoritative relay clock; ip /
     * ua / geo are absent when the relay doesn't know them.
     */
    private suspend fun processSecurityAlertEvent(
        unwrapped: xyz.desent.domain.model.UnwrappedContent,
        giftWrapEvent: GenericEvent,
        receiverNpub: String?
    ) {
        try {
            val dao = securityAlertDao ?: return

            // Dedup by event id (IGNORE insert below is the second line of
            // defence; this avoids emitting an arrival for a known row).
            if (dao.getById(giftWrapEvent.id) != null) return

            fun tag(name: String): String? =
                unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == name }?.getOrNull(1)

            val recipientPubkey = tag("p") ?: currentUserPubKeyHex ?: ""
            val ownerNpub = Bech32Utils.hexToNpub(recipientPubkey)

            // Wall-clock now: the wrap's created_at is NIP-59-randomized.
            val nowMs = System.currentTimeMillis()

            val alert = xyz.desent.domain.model.SecurityAlert(
                eventId = giftWrapEvent.id,
                ownerNpub = ownerNpub,
                subject = tag("subject") ?: "Security alert",
                surface = tag("surface") ?: "",
                time = tag("time") ?: "",
                ip = tag("ip"),
                geo = tag("geo"),
                ua = tag("ua"),
                device = tag("device") ?: "",
                body = unwrapped.content,
                receivedAt = nowMs,
                isSeen = false
            )

            val inserted = dao.insert(
                xyz.desent.data.local.database.entity.SecurityAlertEntity(
                    eventId = alert.eventId,
                    ownerNpub = alert.ownerNpub,
                    subject = alert.subject,
                    surface = alert.surface,
                    time = alert.time,
                    ip = alert.ip,
                    geo = alert.geo,
                    ua = alert.ua,
                    device = alert.device,
                    body = alert.body,
                    receivedAt = alert.receivedAt,
                    isSeen = alert.isSeen
                )
            )
            if (inserted != -1L) {
                _securityAlertArrivals.emit(alert)
                android.util.Log.d(
                    "EmailProcessor",
                    "✓ Security alert stored (${alert.surface}, ${alert.time}) for ${ownerNpub.take(12)}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("EmailProcessor", " Failed to process security alert: ${e.message}", e)
        }
    }

    /**
     * Process a `direction: "badge"` gift wrap (kind 1010) sealed by the
     * relay's npub — a NIP-58 award notification (ANDROID_BADGES.md §7).
     * Persisted into `badge_notices` (the notifications tray); the arrival
     * flow — and therefore the OS notification — fires ONLY on the first
     * insert of a wrap event id, so the relay's 30-day backlog re-downloaded
     * at each login never re-notifies. The award itself arrives separately
     * via the kind 8 badge subscription.
     */
    private suspend fun processBadgeAwardNotice(
        unwrapped: xyz.desent.domain.model.UnwrappedContent,
        giftWrapEvent: GenericEvent
    ) {
        try {
            val handler = badgeNoticeHandler ?: return

            fun tag(name: String): String? =
                unwrapped.tags.firstOrNull { it.isNotEmpty() && it[0] == name }?.getOrNull(1)

            val recipientPubkey = tag("p") ?: currentUserPubKeyHex ?: return
            val ownerNpub = try {
                Bech32Utils.hexToNpub(recipientPubkey)
            } catch (e: Exception) {
                return
            }

            val notice = xyz.desent.domain.model.BadgeAwardNotice(
                eventId = giftWrapEvent.id ?: return,
                ownerNpub = ownerNpub,
                slug = tag("badge") ?: "",
                subject = tag("subject") ?: "New badge",
                body = unwrapped.content
            )

            val firstSight = handler.store(notice)
            if (firstSight) {
                _badgeAwardArrivals.emit(notice)
                android.util.Log.d(
                    "GiftWrapProcessor",
                    "🎖️ Badge award notice (new): ${notice.slug} → ${ownerNpub.take(12)}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("GiftWrapProcessor", " Failed to process badge notice: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Email-rumor test for third-party-sourced gift wraps (see the
         * source gate in [processGiftWrapEvent]). Kind 1010 IS the email
         * rumor kind — inbound mail and delivery receipts are what mirroring
         * mirrors, so only those directions count (security/badge notices
         * are local-only traffic that never legitimately appears on a
         * third-party relay; outbound is our own echo). Legacy kind-14
         * counts only with email-specific tags, same disambiguation as the
         * desent-sourced path. Everything else is foreign NIP-17 traffic.
         * Internal for unit tests.
         */
        internal fun isThirdPartyEmailRumor(unwrapped: xyz.desent.domain.model.UnwrappedContent): Boolean {
            return when (unwrapped.kind) {
                NostrKinds.EMAIL_MESSAGE -> {
                    val direction = unwrapped.tags
                        .firstOrNull { it.isNotEmpty() && it[0] == "direction" }
                        ?.getOrNull(1)
                    direction == null ||
                        direction == xyz.desent.data.EmailBridgeTags.DIRECTION_INBOUND ||
                        direction == xyz.desent.data.EmailBridgeTags.DIRECTION_DELIVERY_RECEIPT
                }
                NostrKinds.PRIVATE_DIRECT_MESSAGE -> {
                    // Same precedence as the desent-sourced path: nip46 and
                    // calendar rumors carry `["bridge", …]` tags too, so they
                    // must be excluded BEFORE the email-tag test (otherwise
                    // the shared "bridge" tag would let them through).
                    val bridge = unwrapped.tags
                        .firstOrNull { it.size >= 2 && it[0] == "bridge" }
                        ?.getOrNull(1)
                    if (bridge == "nip46" || bridge == "calendar") {
                        false
                    } else {
                        val emailTagNames = setOf("sender", "sender_domain", "dkim", "spf", "message_id", "bridge")
                        unwrapped.tags.any { tag -> tag.isNotEmpty() && tag[0] in emailTagNames }
                    }
                }
                else -> false
            }
        }
    }
}
