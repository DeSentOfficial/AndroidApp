package xyz.desent.data.repository

import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.data.relay.withSince
import xyz.desent.domain.repository.RelayRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import xyz.desent.domain.repository.NostrPublisher
import xyz.desent.domain.repository.ContactListEntry

class NostrRepository(
    private val relayRepository: RelayRepository,
    private val secureKeyManager: SecureKeyManager,
    private val eventProcessor: NostrEventProcessor,
    // Relay-sync cursors driving the `since` in REQ filters (null in tests —
    // filters then always cover full history, the pre-cursor behavior).
    private val relaySyncWatermarks: xyz.desent.data.relay.RelaySyncWatermarks? = null
) : NostrPublisher {

    private var currentIdentity: Identity? = null
    private var currentUserNpub: String? = null

    /**
     * The `since` cursor for one sync scope, or null when the account has
     * never synced that scope (first run keeps the full-history fetch).
     */
    private suspend fun sinceFor(
        pubkeyHex: String,
        @Suppress("SameParameterValue") scope: String
    ): Long? = relaySyncWatermarks?.sinceFilterFor(pubkeyHex, scope)

    /**
     * The NIP-46 bunker (remote signer). Wired post-construction by
     * [xyz.desent.di.AppContainer] (same pattern as the processor's service
     * setters) to avoid a constructor dependency cycle. When set, every
     * identity-scoped subscription sweep ([subscribeToGiftWraps]) also
     * re-opens the RAW-transport NIP-46 subscriptions (kind 24133 `#p=[user]`)
     * for active pairings.
     */
    private var nip46BunkerService: xyz.desent.data.nip46.Nip46BunkerService? = null

    /**
     * The relay-mirroring orchestrator. Wired post-construction by
     * AppContainer (same late-wiring pattern as [setNip46BunkerService]) to
     * avoid a constructor dependency cycle — FanoutRepositoryImpl depends on
     * this repository. When set, every identity-scoped subscription sweep
     * ([subscribeToGiftWraps]) also triggers the backup fetch from the
     * user's NIP-65 relays (no-op unless the mirroring opt-in is on).
     */
    private var fanoutRepository: xyz.desent.domain.repository.FanoutRepository? = null

    fun setFanoutRepository(repository: xyz.desent.domain.repository.FanoutRepository) {
        fanoutRepository = repository
    }

    fun setNip46BunkerService(service: xyz.desent.data.nip46.Nip46BunkerService) {
        this.nip46BunkerService = service
    }

    /**
     * The NIP-58 badge repository. Wired post-construction by
     * [xyz.desent.di.AppContainer] to avoid a constructor dependency cycle.
     * When set, login also opens the badge subscriptions for the account.
     */
    private var badgeRepository: BadgeRepositoryImpl? = null

    fun setBadgeRepository(repository: BadgeRepositoryImpl) {
        this.badgeRepository = repository
    }

    suspend fun logout() {
        currentIdentity = null
        currentUserNpub = null
        eventProcessor.setCurrentUserPubKey(null)
        relayRepository.disconnectFromAllRelays()
    }

    /**
     * Swap the in-memory identity to [npub] WITHOUT touching relay state.
     *
     * The caller (typically [xyz.desent.domain.usecase.SwitchAccountUseCase])
     * is responsible for the surrounding disconnect/reconnect/subscribe
     * sequence — keeping this method focused on the identity swap makes it
     * composable and testable.
     *
     * The new identity's signing key must already be present in
     * [secureKeyManager] (placed there by `setActiveAccount` or
     * `storeNSECForAccount`); this method only reads it.
     */
    suspend fun switchIdentity(npub: String): Result<String> {
        return try {
            val identity = secureKeyManager.getIdentityForAccount(npub).getOrThrow()
            currentIdentity = identity
            currentUserNpub = npub
            eventProcessor.setCurrentUserPubKey(identity.publicKey.toHexString())
            android.util.Log.d(TAG, " Switched identity to ${npub.take(8)}")
            Result.success(npub)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to switch identity to $npub: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Restore identity from stored NSEC (for app restart scenarios)
     * Called during splash screen when user is already logged in
     */
    suspend fun restoreIdentity(): Result<String> {
        return try {
            android.util.Log.d("NostrRepository", " Restoring identity from stored NSEC...")

            // Get identity from stored NSEC
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
            android.util.Log.d("NostrRepository", "   Retrieved identity from keystore")

            val npub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())
            android.util.Log.d("NostrRepository", "   Converted to npub: ${npub.take(8)}")

            // Set current identity
            currentIdentity = identity
            currentUserNpub = npub
 
            // Set current user in event processor
            eventProcessor.setCurrentUserPubKey(identity.publicKey.toHexString())

            subscribeToOwnPrivateStorage()
            subscribeToOwnCalendar()
            subscribeToOwnMailboxConfig()
            subscribeToOwnUserSettings()

            android.util.Log.d("NostrRepository", " Identity restored successfully")
            Result.success(npub)
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", " Failed to restore identity: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    fun getCurrentUserNpub(): String? = currentUserNpub
    
    fun getCurrentPubKeyHex(): String? = currentIdentity?.publicKey?.toHexString()
    
    suspend fun fetchUserMetadata(npub: String) {
        val pubkeyHex = Bech32Utils.npubToHex(npub)

        android.util.Log.d("MetadataProcessor", " Requesting metadata for npub=${npub.take(8)} (pubkeyHex=${pubkeyHex.take(8)})")

            relayRepository.subscribeToEvents(
                listOf(mapOf(
                    "authors" to listOf(pubkeyHex),
                    "kinds" to listOf(
                        NostrKinds.SET_METADATA
                    ),
                    "limit" to 3
                )),
                "meta_${pubkeyHex.take(8)}",
                persistent = false
            )

        try {
            withTimeout(5000L) {
                eventProcessor.metadataArrivals.first { arrivingNpub ->
                    arrivingNpub == npub
                }
            }
            android.util.Log.d("MetadataProcessor", " Metadata successfully fetched and saved for ${npub.take(8)}")
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w("MetadataProcessor", " Metadata fetch timeout for $npub after 5 seconds")
        } catch (e: Exception) {
            android.util.Log.e("MetadataProcessor", " Metadata fetch error for $npub: ${e.message}")
        } finally {
            // One-shot fetch: CLOSE so the REQ is never replayed on reconnect.
            runCatching { relayRepository.unsubscribeFromEvents("meta_${pubkeyHex.take(8)}") }
        }
    }

    /**
     * Fetch the logged-in user's own NIP-01 kind-0 profile from a specific set of
     * relays (see RelayConfig.PUBLIC_PROFILE_RELAYS), keeping whichever kind-0 has
     * the newest createdAt. The newest-wins resolution is enforced by
     * NostrEventProcessor.processMetadataEvent.
     *
     * Used on login, cold-launch (splash) and when opening the profile-edit screen.
     * Non-fatal: returns success on timeout so callers never block on a missing profile.
     *
     * This is the ONLY place the app touches third-party relays: the bootstrap
     * profile fetch for an existing Nostr user. Relays other than the DeSent
     * service relay ([RelayConfig.EMAIL_RELAY_URL]) are connected temporarily
     * for the fetch and disconnected again afterwards so nothing stays pooled.
     *
     * Note: subscriptions persist on the relay WebSocket clients independent of this
     * coroutine, so even if the caller is cancelled after subscribing, events still
     * arrive and are upserted into Room (the UI observes Room and refreshes).
     */
    suspend fun fetchOwnProfileFromRelays(npub: String, relayUrls: List<String>): Result<Unit> {
        val pubkeyHex = try {
            Bech32Utils.npubToHex(npub)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " fetchOwnProfileFromRelays: bad npub: ${e.message}")
            return Result.failure(e)
        }

        val serviceRelayUrl = xyz.desent.data.RelayConfig.EMAIL_RELAY_URL
        val temporaryRelays = relayUrls.filter { it != serviceRelayUrl }

        // Connect the bootstrap relays that aren't part of the persistent pool.
        for (url in temporaryRelays) {
            try {
                relayRepository.connectToRelay(url)
            } catch (e: Exception) {
                android.util.Log.w(TAG, " Could not connect to bootstrap relay $url: ${e.message}")
            }
        }

        val filters = listOf(mapOf(
            "authors" to listOf(pubkeyHex),
            "kinds" to listOf(NostrKinds.SET_METADATA),
            "limit" to 3
        ))

        // Issue the REQ on every target relay, tolerating ones that failed to
        // connect. Persistent relays connect asynchronously; if none were ready
        // yet, give them a moment and retry once so the fetch actually runs.
        var accepted = subscribeOnTargetRelays(filters, relayUrls)
        if (accepted.isEmpty()) {
            android.util.Log.w(TAG, " No target relays connected for profile fetch; retrying in 2s")
            kotlinx.coroutines.delay(2000L)
            accepted = subscribeOnTargetRelays(filters, relayUrls)
        }
        if (accepted.isEmpty()) {
            android.util.Log.w(TAG, " Could not subscribe on any of $relayUrls - profile fetch skipped")
            disconnectBootstrapRelays(temporaryRelays)
            return Result.success(Unit)
        }

        try {
            withTimeout(5000L) {
                eventProcessor.metadataArrivals.first { it == npub }
            }
            android.util.Log.d(TAG, " Own profile fetched from relays $accepted for ${npub.take(8)}")
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, " Own profile fetch timed out for $npub (relays=$accepted); keeping cached value")
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Own profile fetch error for $npub: ${e.message}")
        } finally {
            disconnectBootstrapRelays(temporaryRelays)
        }
        return Result.success(Unit)
    }

    /** Sends [filters] to each connected relay in [relayUrls]; returns the ones that accepted. */
    private suspend fun subscribeOnTargetRelays(
        filters: List<Map<String, Any>>,
        relayUrls: List<String>
    ): List<String> {
        val accepted = mutableListOf<String>()
        relayUrls.forEach { url ->
            try {
                relayRepository.subscribeToEventsOnRelay(filters, "login_meta", url)
                accepted += url
            } catch (e: Exception) {
                android.util.Log.w(TAG, " Could not subscribe on $url: ${e.message}")
            }
        }
        return accepted
    }

    private suspend fun disconnectBootstrapRelays(temporaryRelays: List<String>) {
        // Tear the bootstrap relays back down; only the DeSent service relay
        // stays connected for app traffic.
        for (url in temporaryRelays) {
            runCatching { relayRepository.disconnectFromRelay(url) }
                .onFailure { android.util.Log.w(TAG, " Could not disconnect bootstrap relay $url: ${it.message}") }
        }
    }

    /**
     * Subscribe to gift wraps (kind 1059) addressed to the active user.
     *
     * Gift wraps carry every sealed message the app consumes besides chat:
     * email delivery, NIP-46 bunker bridges, calendar shares, thread-token
     * confirmations and delivery/security notices. The subscription lives on
     * the DeSent service relay only. Two filters per REQ: one for received
     * (#p), one for sent (authors); the relay ORs them. `persistent = true`
     * so the REQ is replayed after a relay reconnect (without this, a single
     * socket drop orphans the subscription and no further wrapped events
     * arrive until app restart).
     */
    suspend fun subscribeToGiftWraps() {
        val identity = currentIdentity ?: run {
            android.util.Log.e(TAG, "subscribeToGiftWraps: no identity")
            return
        }
        val pubkeyHex = identity.publicKey.toHexString()

        val relayUrl = xyz.desent.data.RelayConfig.EMAIL_RELAY_URL

        try {
            // `since` cursor: a resume/reconnect re-fetches only wraps newer
            // than the last ingested one instead of the full limit-100 pair.
            val since = sinceFor(pubkeyHex, xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_GIFT_WRAP)
            relayRepository.subscribeToEventsOnRelay(
                listOf(
                    mapOf("kinds" to listOf(NostrKinds.GIFT_WRAP), "#p" to listOf(pubkeyHex), "limit" to 100)
                        .withSince(since),
                    mapOf("kinds" to listOf(NostrKinds.GIFT_WRAP), "authors" to listOf(pubkeyHex), "limit" to 100)
                        .withSince(since)
                ),
                "giftwrap_${pubkeyHex.take(8)}",
                relayUrl,
                persistent = true
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not subscribe to gift wraps on $relayUrl: ${e.message}")
        }

        // Same lifecycle moment as the wrap subs: re-open the standard NIP-46
        // (kind 24133) subscriptions for RAW-transport pairings + the bunker://
        // inbound-connect listener on the desent relay. Fire-and-forget.
        runCatching { nip46BunkerService?.ensureRawTransport() }
            .onFailure { android.util.Log.w(TAG, "NIP-46 RAW re-subscribe failed: ${it.message}") }

        // Relay mirroring: when the opt-in is on, also pull the user's mail
        // from their NIP-65 backup relays at every identity-scoped sweep
        // (login, app start, account switch, foreground service). The call
        // launches its own scope and returns immediately.
        runCatching { fanoutRepository?.syncBackupIfEnabled() }
            .onFailure { android.util.Log.w(TAG, "relay mirroring backup sync failed: ${it.message}") }
    }

    suspend fun fetchAllMetadataDebug() {
        android.util.Log.d("Relay", " Fetching ALL metadata events for debugging")
        relayRepository.subscribeToEvents(
            listOf(mapOf("kinds" to listOf(NostrKinds.SET_METADATA), "limit" to 100)),
            "debug_all_metadata",
            persistent = false
        )

        android.util.Log.d("Relay", " Subscribed to all metadata events with id: debug_all_metadata")
    }

    suspend fun fetchUserContacts(npub: String) {
        val pubkeyHex = Bech32Utils.npubToHex(npub)
        val subId = "contacts_${pubkeyHex.take(8)}"  // Short ID to avoid relay limits (max 71 chars)
        relayRepository.subscribeToEvents(
            listOf(mapOf(
                "authors" to listOf(pubkeyHex),
                "kinds" to listOf(NostrKinds.CONTACT_LIST),
                "limit" to 1
            )),
            subId,
            persistent = false
        )
        // One-shot: CLOSE once the contact list (or EOSE/timeout) arrived, so
        // the REQ is never replayed on reconnect.
        try {
            withTimeout(5000L) {
                eventProcessor.followArrivals.first { it == npub }
            }
        } catch (e: Exception) {
            // Timeout = no contact list stored; nothing to wait for.
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(subId) }
        }
    }
    
    suspend fun fetchUserProfiles(npubs: List<String>) {
        if (npubs.isEmpty()) return

        val pubkeyHexes = npubs.mapNotNull { npub ->
            try {
                Bech32Utils.npubToHex(npub)
            } catch (e: Exception) {
                null
            }
        }
        
        if (pubkeyHexes.isEmpty()) return
        
        val subId = "batch_profiles"
        relayRepository.subscribeToEvents(
            listOf(mapOf(
                "authors" to pubkeyHexes,
                "kinds" to listOf(
                    NostrKinds.SET_METADATA
                ),
                "limit" to pubkeyHexes.size * 3
            )),
            subId,
            persistent = false
        )
        // One-shot: CLOSE at EOSE (or timeout) so the REQ is never replayed.
        try {
            withTimeout(8000L) {
                relayRepository.eoseEvents.first { (id, _) -> id == subId }
            }
        } catch (e: Exception) {
            // Timeout — the arrival flows (if any) already landed.
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(subId) }
        }
    }


    suspend fun publishUserProfile(
        name: String?,
        displayName: String?,
        about: String?,
        picture: String?,
        nip05: String? = null,
        website: String? = null,
        lud16: String? = null,
        banner: String? = null
    ): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val fields = mutableListOf<String>()
            name?.let { fields.add("\"name\":\"$it\"") }
            displayName?.let { fields.add("\"display_name\":\"$it\"") }
            about?.let { fields.add("\"about\":\"$it\"") }
            picture?.let { fields.add("\"picture\":\"$it\"") }
            nip05?.let { fields.add("\"nip05\":\"$it\"") }
            website?.let { fields.add("\"website\":\"$it\"") }
            lud16?.let { fields.add("\"lud16\":\"$it\"") }
            banner?.let { fields.add("\"banner\":\"$it\"") }

            val content = "{${fields.joinToString(",")}}"

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.SET_METADATA)
                .content(content)
                .build()

            identity.sign(event)

            // All publishes go to the DeSent service relay only.
            val targetRelays = listOf(xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)
            var successCount = 0
            targetRelays.forEach { relayUrl ->
                try {
                    relayRepository.publishEventToRelay(event, relayUrl)
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to publish profile to $relayUrl: ${e.message}")
                }
            }

            if (successCount == 0) {
                return Result.failure(Exception("Failed to publish profile to any relay"))
            }

            android.util.Log.d(TAG, " User profile published to relays")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish user profile", e)
            Result.failure(e)
        }
    }

    /**
     * Publish a NIP-02 contact list (kind 3, replaceable).
     *
     * Each entry becomes a `["p", hex, "", petname?]` tag — the empty 2nd
     * parameter keeps petname in NIP-02's positional 3rd slot (relay hint is
     * intentionally empty because the local relay pool is authoritative here).
     * Kind 3 is replaceable, so publishing an empty list is a valid "unfollow
     * everyone" operation and is NOT skipped.
     *
     * Callers MUST pass only the active user's *own* outgoing follows; the
     * `follows` table also holds reverse-direction rows stored by the inbound
     * kind-3 processor and device-local favorite-only rows created by
     * [FollowRepositoryImpl.toggleFavorite], which would otherwise leak into
     * the published event.
     */
    override suspend fun publishContactList(follows: List<ContactListEntry>): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val tags = follows.map { entry ->
                val hex = Bech32Utils.npubToHex(entry.followingNpub)
                val params = if (entry.petname.isNullOrBlank()) {
                    listOf(hex)
                } else {
                    // Position 2 = relay hint (empty), position 3 = petname.
                    listOf(hex, "", entry.petname)
                }
                GenericTag("p", params)
            } as List<nostr.event.BaseTag>

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.CONTACT_LIST)
                .content("")
                .createdAt(System.currentTimeMillis() / 1000)
                .tags(tags)
                .build()

            identity.sign(event)

            // All publishes go to the DeSent service relay only.
            val targetRelays = listOf(xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)
            var successCount = 0
            targetRelays.forEach { relayUrl ->
                try {
                    relayRepository.publishEventToRelay(event, relayUrl)
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to publish contact list to $relayUrl: ${e.message}")
                }
            }

            if (successCount == 0) {
                return Result.failure(Exception("Failed to publish contact list to any relay"))
            }

            android.util.Log.d(TAG, " Contact list published to relays (${follows.size} follows)")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish contact list", e)
            Result.failure(e)
        }
    }

    fun observeEvents(): SharedFlow<GenericEvent> {
        return relayRepository.observeEvents()
    }
    
    // ------------------------------------------------------------------
    // NIP-78 Private Storage (refs/PRIVATE_STORAGE_PROTOCOL.md)
    //
    // Kind 30078 events are NIP-44 self-encrypted (content confidentiality)
    // and force-scoped to the authenticated pubkey on the DeSent relays
    // (metadata confidentiality — another user cannot REQ someone else's
    // 30078 events). We therefore always scope `authors` to our own pubkey
    // and only publish/subscribe on the DeSent relays that enforce this.
    // ------------------------------------------------------------------

    /**
     * Publish a kind-30078 event. If [ciphertext] is empty the relay treats it
     * as a tombstone and hard-deletes the prior version for that `d` tag
     * (protocol Option A delete).
     */
    suspend fun publishPrivateStorage(dTag: String, ciphertext: String): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val tags = mutableListOf<GenericTag>()
            tags.add(GenericTag("d", listOf(dTag)))

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(KIND_PRIVATE_STORAGE)
                .createdAt(System.currentTimeMillis() / 1000)
                .content(ciphertext)
                .tags(tags as List<nostr.event.BaseTag>)
                .build()

            identity.sign(event)

            // Only publish to the email-bridge relay that enforces owner-only
            // read scoping; publishing elsewhere would leak 30078 metadata
            // (d-tags / timestamps) to other users on public relays.
            val targetRelays = listOf(xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)
            var successCount = 0
            targetRelays.forEach { relayUrl ->
                try {
                    relayRepository.publishEventToRelay(event, relayUrl)
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to publish private-storage to $relayUrl: ${e.message}")
                }
            }

            if (successCount == 0) {
                return Result.failure(Exception("Failed to publish private-storage to any relay"))
            }

            android.util.Log.d(TAG, " Private-storage event published (d=$dTag, ${if (ciphertext.isEmpty()) "tombstone" else "encrypted"})")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish private-storage event", e)
            Result.failure(e)
        }
    }

    /**
     * Subscribe to the active user's own kind-30078 events. Always scoped to
     * our own pubkey — the relay rewrites `authors` to this anyway, but we set
     * it explicitly so we never accidentally request someone else's data.
     */
    suspend fun subscribeToOwnPrivateStorage() {
        val pubkeyHex = currentIdentity?.publicKey?.toHexString() ?: return

        android.util.Log.d(TAG, " Subscribing to own private storage (30078) for pubkey=${pubkeyHex.take(16)}")

        val since = sinceFor(pubkeyHex, xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_PRIVATE_STORAGE)
        relayRepository.subscribeToEvents(
            listOf(mapOf(
                "authors" to listOf(pubkeyHex),
                "kinds" to listOf(KIND_PRIVATE_STORAGE),
                "limit" to 500
            ).withSince(since)),
            "ownpriv_${pubkeyHex.take(8)}"
        )
    }

    // ------------------------------------------------------------------
    // NIP-EMAIL Mailbox Configuration (kind 35050)
    //
    // Addressable (NIP-78-style): `d` = user pubkey, relay-readable policy
    // tags in plaintext (e.g. ["auto_purge_days","30"]), private rules
    // NIP-44 self-encrypted in the content. Stays on the email-bridge relay
    // only (relay isolation, refs/FromServer/NIP-EMAIL.md).
    // ------------------------------------------------------------------

    /**
     * Publish the user's kind-35050 Mailbox Configuration. [autoPurgeDays]
     * becomes the plaintext retention-TTL policy tag the relay enforces;
     * [encryptedRules] is the NIP-44 self-encrypted private-rules JSON.
     */
    suspend fun publishMailboxConfig(
        autoPurgeDays: Int?,
        encryptedRules: String
    ): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val pubkeyHex = identity.publicKey.toHexString()
            val tags = mutableListOf<GenericTag>()
            tags.add(GenericTag("d", listOf(pubkeyHex)))
            autoPurgeDays?.let { tags.add(GenericTag("auto_purge_days", listOf(it.toString()))) }

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.MAILBOX_CONFIGURATION)
                .createdAt(System.currentTimeMillis() / 1000)
                .content(encryptedRules)
                .tags(tags as List<nostr.event.BaseTag>)
                .build()

            identity.sign(event)

            val targetRelays = listOf(xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)
            var successCount = 0
            targetRelays.forEach { relayUrl ->
                try {
                    relayRepository.publishEventToRelay(event, relayUrl)
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to publish mailbox config to $relayUrl: ${e.message}")
                }
            }

            if (successCount == 0) {
                return Result.failure(Exception("Failed to publish mailbox config to any relay"))
            }

            android.util.Log.d(TAG, " Mailbox config published (d=${pubkeyHex.take(8)}, autoPurgeDays=$autoPurgeDays)")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish mailbox config", e)
            Result.failure(e)
        }
    }

    /**
     * Subscribe to the active user's own kind-35050 mailbox configuration.
     * Addressable + owner-scoped on the relay; `limit` 1 fetches the latest.
     */
    suspend fun subscribeToOwnMailboxConfig() {
        val pubkeyHex = currentIdentity?.publicKey?.toHexString() ?: return
        android.util.Log.d(TAG, " Subscribing to own mailbox config (35050) for pubkey=${pubkeyHex.take(16)}")
        val since = sinceFor(pubkeyHex, xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_MAILBOX)
        relayRepository.subscribeToEvents(
            listOf(mapOf(
                "authors" to listOf(pubkeyHex),
                "kinds" to listOf(NostrKinds.MAILBOX_CONFIGURATION),
                "limit" to 1
            ).withSince(since)),
            "ownmailbox_${pubkeyHex.take(8)}"
        )
    }

    // ------------------------------------------------------------------
    // DeSent user settings (kind 30079, refs/FromServer/USER_SETTINGS_PROTOCOL.md)
    //
    // Parameterized replaceable (`d = "desent_user_settings"`), PLAINTEXT
    // JSON content the relay reads to update the user's email_settings row.
    // Reads are owner-scoped (every REQ's `authors` is rewritten to the
    // caller), publishes are quota-exempt. Stays on the email-bridge relay
    // only, like 30078/35050.
    // ------------------------------------------------------------------

    /**
     * Publish the kind-30079 user-settings event. [contentJson] must be a
     * partial payload carrying ONLY the fields to change (e.g.
     * `{"security_alerts":"new_device"}`) — the relay's closed schema rejects
     * unknown fields and its UPSERT touches only the fields present.
     */
    suspend fun publishUserSettings(contentJson: String): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val tags = mutableListOf<GenericTag>()
            tags.add(GenericTag("d", listOf(USER_SETTINGS_D_TAG)))

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.USER_SETTINGS)
                .createdAt(System.currentTimeMillis() / 1000)
                .content(contentJson)
                .tags(tags as List<nostr.event.BaseTag>)
                .build()

            identity.sign(event)

            val targetRelays = listOf(xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)
            var successCount = 0
            targetRelays.forEach { relayUrl ->
                try {
                    relayRepository.publishEventToRelay(event, relayUrl)
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to publish user settings to $relayUrl: ${e.message}")
                }
            }

            if (successCount == 0) {
                return Result.failure(Exception("Failed to publish user settings to any relay"))
            }

            android.util.Log.d(TAG, " User settings published (d=$USER_SETTINGS_D_TAG)")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish user settings", e)
            Result.failure(e)
        }
    }

    /**
     * Publish a kind-30079 partial payload AND await the relay's `OK`
     * verdict (BadgeRepositoryImpl pattern). Used by the `dm_fanout` save:
     * the relay rejects an unentitled author carrying that field with
     * `OK false "premium required: …"` (ANDROID_DM_FANOUT.md §2) — callers
     * must surface the purchase surface instead of retrying. A verdict
     * timeout is a failure: the save did not confirm.
     */
    suspend fun publishUserSettingsAwaitingVerdict(contentJson: String): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val tags = mutableListOf<GenericTag>()
            tags.add(GenericTag("d", listOf(USER_SETTINGS_D_TAG)))

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.USER_SETTINGS)
                .createdAt(System.currentTimeMillis() / 1000)
                .content(contentJson)
                .tags(tags as List<nostr.event.BaseTag>)
                .build()

            identity.sign(event)

            relayRepository.publishEventToRelay(event, xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)

            val verdict = kotlinx.coroutines.withTimeoutOrNull(PUBLISH_VERDICT_TIMEOUT_MS) {
                relayRepository.publishResults.first {
                    it.eventId == event.id && it.relayUrl == xyz.desent.data.RelayConfig.EMAIL_RELAY_URL
                }
            }
            when {
                verdict == null -> {
                    android.util.Log.w(TAG, " No verdict for user-settings publish ${event.id.take(8)}")
                    Result.failure(Exception("No acknowledgment from the relay"))
                }
                !verdict.success -> {
                    android.util.Log.w(TAG, " User settings rejected: ${verdict.message}")
                    val premium = xyz.desent.domain.model.PremiumRequiredException
                        .fromVerdictMessage(verdict.message)
                    Result.failure(premium ?: Exception(verdict.message.ifBlank { "Rejected by the relay" }))
                }
                else -> {
                    android.util.Log.d(TAG, " User settings published + acknowledged (d=$USER_SETTINGS_D_TAG)")
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish user settings", e)
            Result.failure(e)
        }
    }

    /**
     * Subscribe to the active user's own kind-30079 settings event. The relay
     * force-scopes `authors` to the caller anyway; we set it explicitly so we
     * never request someone else's settings. `limit` 1 fetches the latest.
     */
    suspend fun subscribeToOwnUserSettings() {
        val pubkeyHex = currentIdentity?.publicKey?.toHexString() ?: return
        android.util.Log.d(TAG, " Subscribing to own user settings (30079) for pubkey=${pubkeyHex.take(16)}")
        val since = sinceFor(pubkeyHex, xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_USER_SETTINGS)
        relayRepository.subscribeToEvents(
            listOf(mapOf(
                "authors" to listOf(pubkeyHex),
                "kinds" to listOf(NostrKinds.USER_SETTINGS),
                "limit" to 1
            ).withSince(since)),
            "ownsettings_${pubkeyHex.take(8)}"
        )
    }

    // ------------------------------------------------------------------
    // NIP-65 relay list (kind 10002) — the relay-mirroring target list
    // (refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md §1). Published to and
    // read back from THIS relay only; no marker = read+write (a mirroring
    // target), "write" = target, "read" = excluded server-side.
    // ------------------------------------------------------------------

    /**
     * One-shot fetch of the active user's own kind-10002 relay list
     * (mirrors [fetchUserContacts]: targeted REQ, await arrival, CLOSE in
     * finally so the REQ is never replayed on reconnect).
     */
    suspend fun fetchOwnRelayList(): Result<List<xyz.desent.domain.model.FanoutRelayEntry>> = runCatching {
        val pubkeyHex = currentIdentity?.publicKey?.toHexString()
            ?: throw IllegalStateException("Not logged in")
        val subId = "ownrelays_${pubkeyHex.take(8)}"
        relayRepository.subscribeToEvents(
            listOf(mapOf(
                "authors" to listOf(pubkeyHex),
                "kinds" to listOf(NostrKinds.RELAY_LIST),
                "limit" to 1
            )),
            subId,
            persistent = false
        )
        try {
            val event = withTimeout(RELAY_LIST_FETCH_TIMEOUT_MS) {
                eventProcessor.relayListArrivals.first { it.pubKey.toHexString() == pubkeyHex }
            }
            parseRelayListTags(event.tags as List<nostr.event.BaseTag>)
        } catch (e: TimeoutCancellationException) {
            // Timeout = no list stored; that's an empty list, not a failure.
            emptyList()
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(subId) }
        }
    }

    /**
     * Publish the kind-10002 relay list (replaceable, no `d` tag) and await
     * the relay verdict — kind 10002 counts against quota server-side, so a
     * rejection must surface instead of silently succeeding.
     */
    suspend fun publishRelayList(entries: List<xyz.desent.domain.model.FanoutRelayEntry>): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val tags = entries.map { entry ->
                val params = mutableListOf(entry.url.trim().removeSuffix("/"))
                entry.marker.wire?.let { params.add(it) }
                GenericTag("r", params)
            } as List<nostr.event.BaseTag>

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(NostrKinds.RELAY_LIST)
                .createdAt(System.currentTimeMillis() / 1000)
                .content("")
                .tags(tags)
                .build()

            identity.sign(event)

            relayRepository.publishEventToRelay(event, xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)

            val verdict = kotlinx.coroutines.withTimeoutOrNull(PUBLISH_VERDICT_TIMEOUT_MS) {
                relayRepository.publishResults.first {
                    it.eventId == event.id && it.relayUrl == xyz.desent.data.RelayConfig.EMAIL_RELAY_URL
                }
            }
            when {
                verdict == null -> {
                    android.util.Log.w(TAG, " No verdict for relay-list publish ${event.id.take(8)}")
                    Result.failure(Exception("No acknowledgment from the relay"))
                }
                !verdict.success -> {
                    android.util.Log.w(TAG, " Relay list rejected: ${verdict.message}")
                    Result.failure(Exception(verdict.message.ifBlank { "Rejected by the relay" }))
                }
                else -> {
                    android.util.Log.d(TAG, " Relay list published (${entries.size} relays)")
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish relay list", e)
            Result.failure(e)
        }
    }

    /**
     * Backup-inbox fetch from ONE of the user's NIP-65 relays
     * (ANDROID_DM_FANOUT.md §5): connect read-only (non-DeSent sockets are
     * REQ-only by construction), targeted `{kinds:[1059], #p:[self]}` REQ
     * bounded by the gift-wrap watermark, race EOSE against a server-side
     * CLOSED (auth-required relays never answer our REQs — read-only
     * sockets never send AUTH), then unsubscribe and tear the socket down.
     *
     * Received wraps flow through the standard GIFT_WRAP pipeline
     * (decrypt → Room; session-LRU + IGNORE-insert dedup make re-receipt a
     * no-op) and — being third-party-sourced — are rumor-kind filtered by
     * the processor (non-email rumors are dumped).
     */
    private suspend fun fetchGiftWrapsFromBackupRelay(
        url: String
    ): xyz.desent.domain.model.BackupFetchResult {
        val pubkeyHex = currentIdentity?.publicKey?.toHexString()
            ?: return xyz.desent.domain.model.BackupFetchResult(
                url, xyz.desent.domain.model.BackupFetchStatus.FAILED, "not logged in"
            )
        val subId = "fanoutbk_${url.hashCode().toUInt().toString(36)}"

        return try {
            relayRepository.connectToRelay(url)
            relayRepository.waitForRelayReady(url, BACKUP_RELAY_READY_TIMEOUT_MS)

            val since = sinceFor(pubkeyHex, xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_GIFT_WRAP)
            relayRepository.subscribeToEventsOnRelay(
                listOf(mapOf(
                    "kinds" to listOf(NostrKinds.GIFT_WRAP),
                    "#p" to listOf(pubkeyHex),
                    "limit" to BACKUP_FETCH_LIMIT
                ).withSince(since)),
                subId,
                url,
                persistent = false
            )

            val closedReason: String? = try {
                kotlinx.coroutines.withTimeout(BACKUP_RELAY_EOSE_TIMEOUT_MS) {
                    kotlinx.coroutines.coroutineScope {
                        // Race the relay's EOSE against a server-side CLOSED
                        // (auth-required relays terminate the REQ instead of
                        // ever answering it). First signal wins; the losing
                        // watcher is cancelled so the scope can return.
                        val outcome = kotlinx.coroutines.CompletableDeferred<String?>()
                        val eoseWatcher = launch {
                            relayRepository.eoseEvents
                                .first { it.first == subId && it.second == url }
                            outcome.complete(null)
                        }
                        val closedWatcher = launch {
                            val closed = relayRepository.closedEvents
                                .first { it.subscriptionId == subId && it.relayUrl == url }
                            outcome.complete(closed.reason)
                        }
                        val reason = outcome.await()
                        eoseWatcher.cancel()
                        closedWatcher.cancel()
                        reason
                    }
                }
            } catch (e: TimeoutCancellationException) {
                return xyz.desent.domain.model.BackupFetchResult(
                    url, xyz.desent.domain.model.BackupFetchStatus.TIMED_OUT
                )
            }

            when {
                closedReason == null ->
                    xyz.desent.domain.model.BackupFetchResult(url, xyz.desent.domain.model.BackupFetchStatus.DONE)
                closedReason.contains("auth", ignoreCase = true) ->
                    xyz.desent.domain.model.BackupFetchResult(
                        url, xyz.desent.domain.model.BackupFetchStatus.AUTH_REQUIRED, closedReason
                    )
                else ->
                    xyz.desent.domain.model.BackupFetchResult(
                        url, xyz.desent.domain.model.BackupFetchStatus.FAILED, closedReason
                    )
            }
        } catch (e: Exception) {
            xyz.desent.domain.model.BackupFetchResult(
                url, xyz.desent.domain.model.BackupFetchStatus.FAILED, e.message
            )
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(subId) }
            // Bootstrap sockets are temporary — torn down after the fetch
            // (the desent relay is skipped by callers, but never disconnect
            // the home relay regardless).
            if (url != xyz.desent.data.RelayConfig.EMAIL_RELAY_URL) {
                runCatching { relayRepository.disconnectFromRelay(url) }
            }
        }
    }

    /** Sequential backup fetch across the user's relays, with a report. */
    suspend fun fetchGiftWrapsFromBackupRelays(urls: List<String>): List<xyz.desent.domain.model.BackupFetchResult> =
        urls.map { url ->
            fetchGiftWrapsFromBackupRelay(url).also { result ->
                android.util.Log.d(TAG, " backup fetch $url -> ${result.status}${result.detail ?: ""}")
            }
        }

    /**
     * §6.3 post-import re-fetch (ANDROID_DM_FANOUT.md): a targeted
     * `{"ids": […]}` REQ against the home relay for wraps the server just
     * re-stored, so they flow through the standard GIFT_WRAP pipeline into
     * Room — imported wraps are usually OLDER than the live subscription's
     * `since` watermark, so the regular sweep would never pick them up.
     */
    suspend fun fetchWrapsByIds(eventIds: List<String>): Result<Unit> {
        if (eventIds.isEmpty()) return Result.success(Unit)
        if (currentIdentity == null) return Result.failure(Exception("Not logged in"))
        val relayUrl = xyz.desent.data.RelayConfig.EMAIL_RELAY_URL
        val subId = "fanoutimp_${eventIds.hashCode().toUInt().toString(36)}"
        return try {
            relayRepository.waitForRelayReady(relayUrl, BACKUP_RELAY_READY_TIMEOUT_MS)
            relayRepository.subscribeToEventsOnRelay(
                listOf(mapOf("ids" to eventIds)),
                subId,
                relayUrl,
                persistent = false
            )
            try {
                kotlinx.coroutines.withTimeout(BACKUP_RELAY_EOSE_TIMEOUT_MS) {
                    relayRepository.eoseEvents.first { it.first == subId && it.second == relayUrl }
                }
            } catch (e: TimeoutCancellationException) {
                // Slow relay — the events may still land through the
                // standing pipeline; not fatal.
            }
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "wrap re-fetch by id failed: ${e.message}")
            Result.failure(e)
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(subId) }
        }
    }

    // ------------------------------------------------------------------
    // NIP-52 Encrypted Calendar (refs/CALENDAR_PROTOCOL.md)
    //
    // Kinds 31922/31923/31924/31925 are parameterized-replaceable and — on
    // the DeSent relays — owner-only read-scoped (the relay rewrites the
    // filter's `authors` to the authenticated pubkey). Content is NIP-44
    // self-encrypted; the outer event carries only `["d", <identifier>]`.
    // ------------------------------------------------------------------

    /**
     * Publish a kind-31922/31923 calendar event. If [ciphertext] is empty the
     * relay treats it as a tombstone and hard-deletes the prior version for
     * that `d` tag (protocol Option A delete).
     */
    suspend fun publishCalendarEvent(kind: Int, dTag: String, ciphertext: String): Result<Unit> {
        val identity = currentIdentity
            ?: return Result.failure(Exception("Not logged in"))

        return try {
            val tags = mutableListOf<GenericTag>()
            tags.add(GenericTag("d", listOf(dTag)))

            val event = GenericEvent.builder()
                .pubKey(identity.publicKey)
                .kind(kind)
                .createdAt(System.currentTimeMillis() / 1000)
                .content(ciphertext)
                .tags(tags as List<nostr.event.BaseTag>)
                .build()

            identity.sign(event)

            // Only publish to the email-bridge relay that enforces owner-only read
            // scoping; publishing elsewhere would leak calendar metadata
            // (d-tags / timestamps) to other users on public relays.
            val targetRelays = listOf(xyz.desent.data.RelayConfig.EMAIL_RELAY_URL)
            var successCount = 0
            targetRelays.forEach { relayUrl ->
                try {
                    relayRepository.publishEventToRelay(event, relayUrl)
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to publish calendar event to $relayUrl: ${e.message}")
                }
            }

            if (successCount == 0) {
                return Result.failure(Exception("Failed to publish calendar event to any relay"))
            }

            android.util.Log.d(TAG, " Calendar event published (kind=$kind, d=$dTag, ${if (ciphertext.isEmpty()) "tombstone" else "encrypted"})")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, " Failed to publish calendar event", e)
            Result.failure(e)
        }
    }

    /**
     * Subscribe to the active user's own NIP-52 calendar kinds. Always scoped
     * to our own pubkey — the relay rewrites `authors` to this anyway.
     */
    suspend fun subscribeToOwnCalendar() {
        val pubkeyHex = currentIdentity?.publicKey?.toHexString() ?: return

        android.util.Log.d(TAG, " Subscribing to own calendar (31922-31925) for pubkey=${pubkeyHex.take(16)}")

        val since = sinceFor(pubkeyHex, xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_CALENDAR)
        relayRepository.subscribeToEvents(
            listOf(mapOf(
                "authors" to listOf(pubkeyHex),
                "kinds" to listOf(
                    KIND_CALENDAR_DATE,
                    KIND_CALENDAR_TIME,
                    KIND_CALENDAR_LIST,
                    KIND_CALENDAR_RSVP
                 ),
                 "limit" to 500
             ).withSince(since)),
             "owncal_${pubkeyHex.take(8)}"
         )
     }

    companion object {
        private const val TAG = "NostrRepository"
        const val KIND_PRIVATE_STORAGE = NostrKinds.APPLICATION_SPECIFIC_DATA

        /** Fixed `d` tag of the kind-30079 user-settings event. */
        const val USER_SETTINGS_D_TAG = "desent_user_settings"

        /** How long a verdict-awaiting publish waits for the relay's `OK`. */
        private const val PUBLISH_VERDICT_TIMEOUT_MS = 5_000L

        /** How long the one-shot kind-10002 fetch waits for the event. */
        private const val RELAY_LIST_FETCH_TIMEOUT_MS = 5_000L

        /** Backup fetch: page size of the `{kinds:[1059], #p:[self]}` REQ. */
        private const val BACKUP_FETCH_LIMIT = 500

        /** Backup fetch: how long to wait for the read-only socket to be ready. */
        private const val BACKUP_RELAY_READY_TIMEOUT_MS = 5_000L

        /** Backup fetch: how long to wait for EOSE (or a CLOSED) per relay. */
        private const val BACKUP_RELAY_EOSE_TIMEOUT_MS = 10_000L

        /**
         * `r` tags → NIP-65 entries; malformed tags (no URL param) are
         * skipped. Internal for unit tests.
         */
        internal fun parseRelayListTags(tags: List<nostr.event.BaseTag>): List<xyz.desent.domain.model.FanoutRelayEntry> =
            tags.mapNotNull { tag ->
                val params = (tag as? GenericTag)?.takeIf { it.getCode() == "r" }?.getParams()
                    ?: return@mapNotNull null
                val url = params.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                xyz.desent.domain.model.FanoutRelayEntry(
                    url = url,
                    marker = xyz.desent.domain.model.RelayListMarker.fromWire(params.getOrNull(1))
                )
            }

        // NIP-52 calendar kinds (refs/CALENDAR_PROTOCOL.md).
        const val KIND_CALENDAR_DATE = NostrKinds.CALENDAR_DATE_BASED_EVENT      // all-day / multi day
        const val KIND_CALENDAR_TIME = NostrKinds.CALENDAR_TIME_BASED_EVENT      // time-based
        const val KIND_CALENDAR_LIST = NostrKinds.CALENDAR_EVENT                 // named calendar (collection)
        const val KIND_CALENDAR_RSVP = NostrKinds.CALENDAR_RSVP_EVENT            // RSVP response
    }
}
