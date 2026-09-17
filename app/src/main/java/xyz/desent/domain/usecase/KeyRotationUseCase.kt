package xyz.desent.domain.usecase

import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import nostr.event.impl.GenericEvent
import nostr.id.Identity
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.CustodialCrypto
import xyz.desent.crypto.GiftRewrap
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.NostrEventCrypto
import xyz.desent.crypto.PrivateStorageCrypto
import xyz.desent.crypto.SchnorrProof
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.data.registration.KeyRotationClient
import xyz.desent.data.registration.model.KeyRotateRequest
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.CustodialAccountRepository
import xyz.desent.domain.repository.RelayRepository

/**
 * "Roll Your Keys" — the client half of migration 040
 * (refs/FROM_email.desent.xyz/KEY_ROTATION.md + ANDROID_KEY_ROTATION.md).
 *
 * Sequence (§4 of the wire contract):
 *
 *  1. **Snapshot** the old key's data while the session still AUTHs as the
 *     old key: gift wraps `kinds:[1059] #p:[old]` (mail migration) and the
 *     own published events (`0, 3, 30008, 30078, 30079, 31922-25, 35050`).
 *  2. **Rotate** — atomic server re-key. NIP-98 by the OLD key, old verifier
 *     (custodial accounts), schnorr proof-of-possession by the NEW key, and
 *     the new password's verifier + NIP-49 blob (every Android rotation ends
 *     custodial). `keep_old_identity` = the migration-042 conversion branch.
 *  3. **Local switch** — re-own local data, store/activate the new key,
 *     reconnect the WebSocket so NIP-42 AUTHs as the NEW key.
 *  4. **Restore mail** — unwrap each old wrap with the old key, re-target the
 *     rumor, re-seal/re-wrap to the new key, POST to `/restore` in batches.
 *     Relay-sealed notices are dropped (re-sealing would forge the sender).
 *  5. **Republish** — kind 0/3/30008/30079 verbatim (re-signed);
 *     30078/31922-25/35050 decrypt-with-old → re-encrypt-to-new.
 *  6. **Cleanup** — purge everything the old key still owns server-side.
 *
 * The old [Identity] is captured up-front: the moment the new key is stored,
 * the legacy single-slot mirror flips and every single-slot consumer (NIP-42,
 * NIP-98, NIP-44, gift-wrap) switches to the new key.
 */
class KeyRotationUseCase(
    private val secureKeyManager: SecureKeyManager,
    private val preferencesManager: PreferencesManager,
    private val accountDao: AccountDao,
    private val accountRepository: AccountRepository,
    private val custodialAccountRepository: CustodialAccountRepository,
    private val relayRepository: RelayRepository,
    private val keyRotationClient: KeyRotationClient,
    private val switchAccountUseCase: SwitchAccountUseCase
) {

    enum class Step { SNAPSHOT, ROTATE, LOCAL_SWITCH, RESTORE_MAIL, REPUBLISH, CLEANUP }

    data class Params(
        /** Pre-generated in the wizard (nsec backup screen) — must derive to a fresh npub. */
        val newNsec: String,
        val newPassword: String,
        /** Current account password — required when the account is custodial. */
        val currentPassword: String? = null,
        val migrateMail: Boolean = true,
        /** Migration 042 conversion: keep the old key as a linked login identity. */
        val keepOldIdentity: Boolean = false
    )

    data class Result(
        val oldNpub: String,
        val newNpub: String,
        val newNsec: String,
        val wrapsRestored: Int = 0,
        val wrapsDropped: Int = 0,
        val eventsRepublished: Int = 0,
        val eventsFailed: Int = 0,
        val identityLinked: Boolean = false,
        /** True when cleanup could not run — old-key data is still on the server. */
        val cleanupPending: Boolean = false
    )

    /**
     * Rotation failure. [committed] = the server re-key already happened —
     * the account IS the new key now; the wizard must re-auth/finish rather
     * than offer a plain retry. [partial] carries state when available.
     */
    class RotationException(
        val step: Step,
        val committed: Boolean,
        val partial: Result?,
        cause: Throwable
    ) : Exception("Key rotation failed at $step: ${cause.message}", cause)

    private data class Snapshot(val wraps: List<GenericEvent>, val ownEvents: List<GenericEvent>)

    suspend fun rotate(
        params: Params,
        onProgress: (Step) -> Unit = {}
    ): kotlin.Result<Result> {
        return try {
            kotlin.Result.success(rotateInner(params, onProgress))
        } catch (e: RotationException) {
            kotlin.Result.failure(e)
        } catch (e: Exception) {
            kotlin.Result.failure(RotationException(Step.ROTATE, committed = false, partial = null, cause = e))
        }
    }

    private suspend fun rotateInner(params: Params, onProgress: (Step) -> Unit): Result {
        // ---- Resolve old + new identities --------------------------------
        val oldNpub = preferencesManager.getActiveNpub()
            ?: throw RotationException(Step.ROTATE, false, null, IllegalStateException("No active account"))
        val oldIdentity = secureKeyManager.getIdentityFromStoredNSEC().getOrElse {
            throw RotationException(Step.ROTATE, false, null, it)
        }
        val oldPriv = oldIdentity.privateKey.rawData
        val oldPub = Nip44Encryption.derivePublicKey(oldPriv)

        val newNpub = secureKeyManager.validateNSEC(params.newNsec).getOrElse {
            throw RotationException(Step.ROTATE, false, null, IllegalArgumentException("Invalid new key"))
        }
        if (newNpub == oldNpub) {
            throw RotationException(
                Step.ROTATE, false, null, IllegalArgumentException("The new key must differ from the current key")
            )
        }
        val newIdentity = Identity.create(Bech32Utils.nsecToHex(params.newNsec))
        val newPriv = newIdentity.privateKey.rawData
        val newPub = Nip44Encryption.derivePublicKey(newPriv)
        val newHex = newIdentity.publicKey.toHexString()

        val custodialUsername = accountDao.getCustodialUsername(oldNpub)
        val isCustodial = custodialUsername != null
        if (isCustodial && params.currentPassword.isNullOrBlank()) {
            throw RotationException(
                Step.ROTATE, false, null,
                IllegalArgumentException("Current password required for a password account")
            )
        }

        // ---- 1. Snapshot while AUTHed as the old key ---------------------
        onProgress(Step.SNAPSHOT)
        val snapshot = snapshotOldKey(oldIdentity.publicKey.toHexString(), includeWraps = params.migrateMail)
        Log.i(TAG, "Snapshot: ${snapshot.wraps.size} wraps, ${snapshot.ownEvents.size} own events")

        // ---- 2. Build proofs (Argon2/scrypt off the main thread) ---------
        val built = withContext(Dispatchers.Default) {
            CustodialCrypto.buildBlob(params.newNsec, params.newPassword)
        }
        val oldVerifier = if (isCustodial) {
            custodialAccountRepository.deriveCurrentVerifier(custodialUsername!!, params.currentPassword!!)
                .getOrElse { throw RotationException(Step.ROTATE, false, null, it) }
        } else {
            null
        }

        // ---- 3. The atomic re-key ----------------------------------------
        onProgress(Step.ROTATE)
        val challenge = keyRotationClient.challenge(oldIdentity)
            .getOrElse { throw RotationException(Step.ROTATE, false, null, it) }
        val proof = SchnorrProof.keyRotateProof(challenge.nonce, newPriv)
        val rotateResponse = keyRotationClient.rotate(
            KeyRotateRequest(
                nonce = challenge.nonce,
                newPubkey = newHex,
                newKeyProof = proof,
                migrateMail = params.migrateMail,
                oldVerifier = oldVerifier,
                newVerifier = built.verifier,
                newBlob = built.blob,
                keepOldIdentity = if (params.keepOldIdentity) true else null
            ),
            oldIdentity
        ).getOrElse { throw RotationException(Step.ROTATE, true, null, it) }
        Log.i(TAG, "Rotation committed -> ${rotateResponse.npub} (custodial=${rotateResponse.custodial})")

        var wrapsRestored = 0
        var wrapsDropped = 0
        var eventsRepublished = 0
        var eventsFailed = 0
        var cleanupPending = false
        fun partial() = Result(
            oldNpub = oldNpub, newNpub = newNpub, newNsec = params.newNsec,
            wrapsRestored = wrapsRestored, wrapsDropped = wrapsDropped,
            eventsRepublished = eventsRepublished, eventsFailed = eventsFailed,
            identityLinked = rotateResponse.identityLinked == true
        )

        // ---- 4. Local switch (new key active, WS re-AUTHs as new) --------
        onProgress(Step.LOCAL_SWITCH)
        try {
            accountRepository.rotateLocalAccount(
                oldNpub = oldNpub,
                newNpub = newNpub,
                newNsec = params.newNsec,
                username = custodialUsername,
                keepOldKeyMaterial = params.keepOldIdentity
            ).getOrThrow()
            switchAccountUseCase.switchTo(newNpub).getOrThrow()
        } catch (e: Exception) {
            throw RotationException(Step.LOCAL_SWITCH, true, partial(), e)
        }

        // ---- 5. Re-wrap + restore mail ------------------------------------
        if (params.migrateMail) {
            onProgress(Step.RESTORE_MAIL)
            try {
                // Straggler wraps delivered between snapshot and rotate
                // (KEY_ROTATION.md §5): re-scan right before re-wrapping.
                val stragglers = snapshotOldKey(oldIdentity.publicKey.toHexString(), includeWraps = true)
                val allWraps = (snapshot.wraps + stragglers.wraps).distinctBy { it.id }
                val (restored, dropped) = rewrapAndRestore(allWraps, oldPriv, oldPub, newPriv, newPub, newIdentity)
                wrapsRestored = restored
                wrapsDropped = dropped
            } catch (e: Exception) {
                // Mail history incomplete — do NOT purge the old key yet.
                cleanupPending = true
                throw RotationException(
                    Step.RESTORE_MAIL, committed = true, partial = partial().copy(cleanupPending = true), cause = e
                )
            }
        }

        // ---- 6. Republish own events under the new key --------------------
        onProgress(Step.REPUBLISH)
        try {
            val counts = republishOwnEvents(snapshot.ownEvents, oldPriv, newPriv, newHex)
            eventsRepublished = counts.first
            eventsFailed = counts.second
        } catch (e: Exception) {
            Log.e(TAG, "Republish failed: ${e.message}", e)
            eventsFailed = snapshot.ownEvents.size
        }

        // ---- 7. Cleanup ----------------------------------------------------
        onProgress(Step.CLEANUP)
        try {
            keyRotationClient.cleanup(newIdentity).getOrThrow()
        } catch (e: Exception) {
            // Not fatal: the account is fully live on the new key. Old-key
            // rows remain until a later cleanup — surface it prominently.
            Log.e(TAG, "Cleanup failed - old-key data still on server: ${e.message}", e)
            cleanupPending = true
        }

        return Result(
            oldNpub = oldNpub,
            newNpub = newNpub,
            newNsec = params.newNsec,
            wrapsRestored = wrapsRestored,
            wrapsDropped = wrapsDropped,
            eventsRepublished = eventsRepublished,
            eventsFailed = eventsFailed,
            identityLinked = rotateResponse.identityLinked == true,
            cleanupPending = cleanupPending
        )
    }

    /**
     * Standalone `/cleanup` retry (also usable after [rotate] reported
     * `cleanupPending`, within the 24 h audit window). Signs with the
     * currently-active key — i.e. must run after the local switch.
     */
    suspend fun finishCleanup(): kotlin.Result<Unit> {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrElse {
            return kotlin.Result.failure(it)
        }
        return keyRotationClient.cleanup(identity).map { Unit }
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    private suspend fun snapshotOldKey(oldHex: String, includeWraps: Boolean): Snapshot {
        val wraps = mutableListOf<GenericEvent>()
        val own = mutableListOf<GenericEvent>()
        val wrapsSubId = "rotw_${oldHex.take(8)}"
        val ownSubId = "roto_${oldHex.take(8)}"

        try {
            if (relayRepository.getConnectedRelays().isEmpty()) {
                relayRepository.connectToPersistentRelays()
                relayRepository.waitForRelayReady(RelayConfig.EMAIL_RELAY_URL, 5_000L)
            }
            val relayCount = maxOf(1, relayRepository.getConnectedRelays().size)

            withTimeout(SNAPSHOT_TIMEOUT_MS) {
                kotlinx.coroutines.coroutineScope {
                    val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        relayRepository.observeEvents().collect { event ->
                            if (includeWraps && event.kind == NostrKinds.GIFT_WRAP) {
                                wraps += event
                            } else if (event.pubKey?.toHexString() == oldHex && event.kind in OWN_EVENT_KINDS) {
                                own += event
                            }
                        }
                    }
                    val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        val seen = mutableMapOf<String, MutableSet<String>>()
                        relayRepository.eoseEvents.first { (subId, url) ->
                            if (subId != wrapsSubId && subId != ownSubId) return@first false
                            seen.getOrPut(subId) { mutableSetOf() }.add(url)
                            val wrapsDone = !includeWraps ||
                                (seen[wrapsSubId]?.size ?: 0) >= relayCount
                            val ownDone = (seen[ownSubId]?.size ?: 0) >= relayCount
                            wrapsDone && ownDone
                        }
                    }
                    try {
                        if (includeWraps) {
                            relayRepository.subscribeToEventsOnRelay(
                                listOf(
                                    mapOf(
                                        "kinds" to listOf(NostrKinds.GIFT_WRAP),
                                        "#p" to listOf(oldHex),
                                        "limit" to 500
                                    )
                                ),
                                wrapsSubId,
                                RelayConfig.EMAIL_RELAY_URL,
                                persistent = false
                            )
                        }
                        relayRepository.subscribeToEventsOnRelay(
                            listOf(
                                mapOf(
                                    "kinds" to OWN_EVENT_KINDS.toList(),
                                    "authors" to listOf(oldHex),
                                    "limit" to 1000
                                )
                            ),
                            ownSubId,
                            RelayConfig.EMAIL_RELAY_URL,
                            persistent = false
                        )
                        eoseJob.join()
                    } finally {
                        collectJob.cancel()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Snapshot timed out - proceeding with partial data (${wraps.size} wraps)")
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot failed (${e.message}) - proceeding with partial data")
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(wrapsSubId) }
            runCatching { relayRepository.unsubscribeFromEvents(ownSubId) }
        }
        return Snapshot(wraps = wraps.distinctBy { it.id }, ownEvents = own.distinctBy { it.id })
    }

    // ------------------------------------------------------------------
    // Mail re-wrap + restore
    // ------------------------------------------------------------------

    /** Wire-deserialized tags are always GenericTag (nostr-java TagDeserializer). */
    private fun tagParams(tag: nostr.event.BaseTag): List<String> =
        (tag as? nostr.event.tag.GenericTag)?.getParams() ?: emptyList()

    private fun tagList(event: GenericEvent): List<List<String>> =
        event.tags.map { listOf(it.getCode()) + tagParams(it) }

    private suspend fun rewrapAndRestore(
        wraps: List<GenericEvent>,
        oldPriv: ByteArray,
        oldPub: ByteArray,
        newPriv: ByteArray,
        newPub: ByteArray,
        newIdentity: Identity
    ): Pair<Int, Int> {
        val rewrapped = mutableListOf<String>()
        var dropped = 0
        for (wrap in wraps) {
            val expirationTag = wrap.tags
                .firstOrNull { it.getCode() == "expiration" }
                ?.let { tagParams(it).firstOrNull() }?.toLongOrNull()
            try {
                val outcome = GiftRewrap.rewrap(
                    wrapContent = wrap.content ?: continue,
                    wrapAuthorPubkeyHex = wrap.pubKey.toHexString(),
                    oldPrivateKey = oldPriv,
                    oldPublicKey = oldPub,
                    newPrivateKey = newPriv,
                    newPublicKey = newPub,
                    expirationEpochSeconds = GiftRewrap.nextExpiration(expirationTag)
                )
                if (outcome.sealPubkeyHex == RelayConfig.RELAY_PUBKEY_HEX) {
                    // Relay-sealed notice (receipt/security/badge): re-sealing
                    // would forge the relay's signature — drop, never migrate.
                    dropped++
                } else {
                    rewrapped += outcome.wrapEventJson
                }
            } catch (e: Exception) {
                Log.w(TAG, "Dropping un-re-wrappable wrap ${wrap.id.take(8)}: ${e.message}")
                dropped++
            }
        }

        rewrapped.chunked(RESTORE_BATCH_SIZE).forEach { batch ->
            val response = keyRotationClient.restore(batch, newIdentity).getOrElse {
                throw it
            }
            Log.i(TAG, "Restored ${response.restored} wraps (${response.skipped} skipped)")
        }
        return rewrapped.size to dropped
    }

    // ------------------------------------------------------------------
    // Own-events republish
    // ------------------------------------------------------------------

    private suspend fun republishOwnEvents(
        ownEvents: List<GenericEvent>,
        oldPriv: ByteArray,
        newPriv: ByteArray,
        newHex: String
    ): Pair<Int, Int> {
        // Replaceable kinds: keep only the newest version per (kind, d-tag).
        val newest = mutableListOf<GenericEvent>()
        OWN_EVENT_KINDS.sorted().forEach { kind ->
            val ofKind = ownEvents.filter { it.kind == kind }
            if (kind == NostrKinds.SET_METADATA || kind == NostrKinds.CONTACT_LIST) {
                ofKind.maxByOrNull { it.createdAt }?.let { newest += it }
            } else {
                ofKind
                    .groupBy { event -> event.tags.firstOrNull { it.getCode() == "d" }?.let { tagParams(it).firstOrNull() } ?: "" }
                    .values
                    .mapNotNull { versions -> versions.maxByOrNull { it.createdAt } }
                    .forEach { newest += it }
            }
        }

        var ok = 0
        var failed = 0
        for (event in newest) {
            try {
                val tags: List<List<String>> = tagList(event)
                val content = event.content ?: ""
                val built = when (event.kind) {
                    NostrKinds.APPLICATION_SPECIFIC_DATA,
                    NostrKinds.CALENDAR_DATE_BASED_EVENT,
                    NostrKinds.CALENDAR_TIME_BASED_EVENT,
                    NostrKinds.CALENDAR_EVENT,
                    NostrKinds.CALENDAR_RSVP_EVENT -> {
                        if (content.isBlank()) continue // tombstone — nothing to carry
                        val plain = PrivateStorageCrypto.decryptFromSelf(content, oldPriv)
                        NostrEventCrypto.buildSignedEvent(
                            newHex, System.currentTimeMillis() / 1000, event.kind, tags,
                            PrivateStorageCrypto.encryptToSelf(plain, newPriv), newPriv
                        )
                    }
                    NostrKinds.MAILBOX_CONFIGURATION -> {
                        if (content.isBlank()) continue
                        val plain = PrivateStorageCrypto.decryptFromSelf(content, oldPriv)
                        // d tag = the account pubkey → re-point at the new key.
                        val retagged = tags.map { tag ->
                            if (tag.size >= 2 && tag[0] == "d") listOf("d", newHex) + tag.drop(2) else tag
                        }
                        NostrEventCrypto.buildSignedEvent(
                            newHex, System.currentTimeMillis() / 1000, event.kind, retagged,
                            PrivateStorageCrypto.encryptToSelf(plain, newPriv), newPriv
                        )
                    }
                    // kind 0, kind 3, kind 30008, kind 30079 — plaintext, verbatim.
                    else -> NostrEventCrypto.buildSignedEvent(
                        newHex, System.currentTimeMillis() / 1000, event.kind, tags, content, newPriv
                    )
                }
                relayRepository.publishEventToRelay(built, RelayConfig.EMAIL_RELAY_URL)
                val verdict = withTimeoutOrNull(5_000L) {
                    relayRepository.publishResults.first { it.eventId == built.id }
                }
                if (verdict == null || !verdict.success) {
                    Log.w(TAG, "Republish ${built.id.take(8)} (kind=${event.kind}) not confirmed")
                    failed++
                } else {
                    ok++
                }
                delay(PUBLISH_PACE_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Republish kind=${event.kind} failed: ${e.message}")
                failed++
            }
        }
        return ok to failed
    }

    companion object {
        private const val TAG = "KeyRotationUseCase"

        /** Server cap: ≤ 200 events per /restore call (KEY_ROTATION.md §3.3). */
        private const val RESTORE_BATCH_SIZE = 200
        private const val SNAPSHOT_TIMEOUT_MS = 25_000L
        private const val PUBLISH_PACE_MS = 100L

        private val OWN_EVENT_KINDS = intArrayOf(
            NostrKinds.SET_METADATA,          // 0
            NostrKinds.CONTACT_LIST,          // 3
            NostrKinds.PROFILE_BADGES,        // 30008
            NostrKinds.APPLICATION_SPECIFIC_DATA, // 30078
            NostrKinds.USER_SETTINGS,         // 30079
            NostrKinds.MAILBOX_CONFIGURATION, // 35050
            NostrKinds.CALENDAR_DATE_BASED_EVENT,
            NostrKinds.CALENDAR_TIME_BASED_EVENT,
            NostrKinds.CALENDAR_EVENT,
            NostrKinds.CALENDAR_RSVP_EVENT
        )
    }
}
