package xyz.desent.data.nip46

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nostr.encryption.MessageCipher04
import nostr.event.BaseTag
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import nostr.base.PublicKey
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.GiftWrapEncryptionService
import xyz.desent.crypto.Nip44Encryption
import xyz.desent.crypto.NostrSigner
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.UnsignedNostrEvent
import xyz.desent.domain.repository.RelayRepository
import java.security.SecureRandom
import java.util.UUID

/**
 * The Android app acting as a NIP-46 "bunker" (remote signer). Speaks BOTH
 * wire transports (refs/NIP46_BUNKER_CLIENT.md §16):
 *
 *  - [Nip46Transport.GIFT_WRAP]: DeSent-internal consumers (web inbox, relay-
 *    initiated signing). Requests arrive as kind-1059 gift wraps routed from
 *    [xyz.desent.data.nostr.NostrEventProcessor] (rumors tagged
 *    `["bridge","nip46"]`); responses are re-wrapped 3-layer via
 *    [GiftWrapEncryptionService] and published to desent.xyz.
 *  - [Nip46Transport.RAW]: standard NIP-46 kind-24133 events (Amethyst/NDK,
 *    Damus, iris, nsec.app…). Requests arrive via [handleRawRequest]; responses
 *    are kind-24133 events NIP-44-encrypted to the client pubkey, published on
 *    the pairing's relays.
 *
 * The per-profile policy (and per-pairing "always allow" grants) applies
 * identically to both transports. The user's `nsec` never leaves the device.
 */
class Nip46BunkerService(
    private val pairingStore: Nip46PairingStore,
    private val secureKeyManager: SecureKeyManager,
    private val giftWrap: GiftWrapEncryptionService,
    private val relayRepository: RelayRepository,
    private val preferencesManager: PreferencesManager,
    private val scope: CoroutineScope,
    // Relay-sync cursor for the `since` in the RAW REQ filter (null in tests).
    private val relaySyncWatermarks: xyz.desent.data.relay.RelaySyncWatermarks? = null
) {
    companion object {
        private const val TAG = "Nip46Bunker"
        private const val BUNKER_SECRET_TTL_SECONDS = 600L
    }

    /**
     * Where and how to send a response for a given request.
     *  - [GiftWrap]: re-wrap to the session pubkey on the desent relay.
     *  - [Raw]: kind-24133 to the client pubkey, preferring the relay the
     *    request arrived on, falling back to the pairing's relays.
     */
    sealed interface ReplyRoute {
        val clientPubkeyHex: String
        val transport: Nip46Transport

        data class GiftWrap(override val clientPubkeyHex: String) : ReplyRoute {
            override val transport get() = Nip46Transport.GIFT_WRAP
        }

        data class Raw(
            override val clientPubkeyHex: String,
            val relays: List<String>,
            val originRelayUrl: String?
        ) : ReplyRoute {
            override val transport get() = Nip46Transport.RAW
        }
    }

    /**
     * A not-yet-consumed single-use secret behind a displayed `bunker://` URI
     * (signer-initiated pairing, NIP-46 "Direct connection initiated by
     * remote-signer"). Kept in memory only; generating a new URI invalidates
     * the previous one.
     */
    private data class PendingBunkerSecret(
        val secret: String,
        val secretHash: String,
        val createdAt: Long,
        val expiresAt: Long
    )

    /** The current prompt-required request awaiting the user, or null. */
    private val _pending = MutableStateFlow<PendingRequest?>(null)
    private val _pendingPrompt = MutableStateFlow<Nip46SignPrompt?>(null)
    val pendingSignPrompt: StateFlow<Nip46SignPrompt?> = _pendingPrompt.asStateFlow()

    private var pendingBunkerSecret: PendingBunkerSecret? = null

    init {
        // Mirror the internal pending request into the UI-facing prompt.
        scope.launch { _pending.collect { pending -> _pendingPrompt.value = pending?.toPrompt() } }
    }

    val pairings: StateFlow<List<Nip46Pairing>> get() = pairingStore.pairings

    // ------------------------------------------------------------------
    // Inbound — gift-wrapped (DeSent internal consumers)
    // ------------------------------------------------------------------

    /**
     * @param rumorContent the kind-14 rumor's content = JSON NIP-46 request
     * @param sessionPubkeyHex the consumer's session pubkey (from the seal signer)
     */
    suspend fun handleIncoming(rumorContent: String, sessionPubkeyHex: String) {
        // Never log the decrypted request payload — it carries NIP-46 commands.
        Log.d(TAG, "handleIncoming: session=${sessionPubkeyHex.take(8)} bytes=${rumorContent.length}")
        val route = ReplyRoute.GiftWrap(sessionPubkeyHex)
        val request = try {
            nip46Json.decodeFromString(Nip46Request.serializer(), rumorContent)
        } catch (e: Exception) {
            Log.w(TAG, "Bad NIP-46 request from ${sessionPubkeyHex.take(8)}: ${e.message}")
            respond(route, nip46PayloadError("unknown", Nip46ErrorCode.BAD_REQUEST, "Malformed request"))
            return
        }
        Log.d(TAG, "handleIncoming: method=${request.method} id=${request.id} from session=${sessionPubkeyHex.take(8)}")
        dispatch(request, route)
    }

    // ------------------------------------------------------------------
    // Inbound — raw kind 24133 (standard NIP-46, external clients)
    // ------------------------------------------------------------------

    /**
     * Handle a standard kind-24133 request event. Verifies the p-tag addresses
     * the active user, NIP-44-decrypts the content (conversation key =
     * user ↔ client), then routes through the same policy pipeline as the
     * gift-wrapped path. Responses (acks for our outbound `connect`, etc.)
     * carry no `method` and are ignored.
     */
    suspend fun handleRawRequest(event: GenericEvent, relayUrl: String?) {
        val identity = activeIdentity() ?: return
        val userHex = identity.publicKey.toHexString()

        val pTag = event.tags.find { (it as? GenericTag)?.getCode() == "p" } as? GenericTag
        val addressedTo = pTag?.getParams()?.firstOrNull()
        if (addressedTo != userHex) {
            Log.d(TAG, "handleRawRequest: p-tag ${addressedTo?.take(8)} != user; ignoring")
            return
        }

        val clientPubkeyHex = event.pubKey.toHexString()
        val requestJson = try {
            val conv = Nip44Encryption.getConversationKey(identity.privateKey.rawData, Nip44Encryption.hexToBytes(clientPubkeyHex))
            Nip44Encryption.decrypt(event.content ?: "", conv)
        } catch (e: Exception) {
            Log.w(TAG, "handleRawRequest: decrypt failed from ${clientPubkeyHex.take(8)}: ${e.message}")
            return
        }

        val parsed = try {
            nip46Json.parseToJsonElement(requestJson).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "handleRawRequest: bad JSON from ${clientPubkeyHex.take(8)}")
            return
        }
        val method = parsed["method"]?.jsonPrimitive?.contentOrNull
        if (method == null) {
            // A response (e.g. the ack to our outbound connect) — nothing to do.
            Log.d(TAG, "handleRawRequest: response frame from ${clientPubkeyHex.take(8)}; ignoring")
            return
        }
        val request = Nip46Request(
            id = parsed["id"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            method = method,
            params = (parsed["params"] as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()
        )
        Log.d(TAG, "handleRawRequest: method=${request.method} id=${request.id} from client=${clientPubkeyHex.take(8)}")

        val route = ReplyRoute.Raw(
            clientPubkeyHex = clientPubkeyHex,
            relays = pairingStore.findBySession(clientPubkeyHex)?.relays ?: emptyList(),
            originRelayUrl = relayUrl
        )
        dispatch(request, route)
    }

    // ------------------------------------------------------------------
    // Shared request pipeline (both transports)
    // ------------------------------------------------------------------

    private suspend fun dispatch(request: Nip46Request, route: ReplyRoute) {
        // Inbound connect (bunker:// direction) is the pairing-establishing
        // path; it must run BEFORE the pairing lookup.
        if (request.method == Nip46Method.CONNECT) {
            handleInboundConnect(request, route)
            return
        }

        val pairing = pairingStore.findBySession(route.clientPubkeyHex)
        if (pairing == null) {
            Log.w(TAG, "dispatch: no pairing for client=${route.clientPubkeyHex.take(8)} -> unpaired")
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.UNPAIRED, "No active pairing"))
            return
        }
        if (pairing.revoked) {
            // Revoked/expired pairings stop responding.
            Log.d(TAG, "Ignoring request ${request.id} from revoked pairing ${route.clientPubkeyHex.take(8)}")
            return
        }
        // Lazy expiry.
        if (pairing.expiresAt != null && pairing.expiresAt <= nowSec()) {
            pairingStore.update(route.clientPubkeyHex) { it.copy(revoked = true) }
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.EXPIRED, "Pairing expired"))
            return
        }

        when (request.method) {
            Nip46Method.DISCONNECT, Nip46Method.LOGOUT -> {
                pairingStore.update(route.clientPubkeyHex) { it.copy(revoked = true) }
                Log.i(TAG, "Pairing ${route.clientPubkeyHex.take(8)} ${request.method}ed by consumer")
                if (request.method == Nip46Method.LOGOUT) {
                    respond(route, nip46PayloadString(request.id, "ack"))
                }
            }

            Nip46Method.GET_PUBLIC_KEY -> {
                val userHex = activeUserHex() ?: run {
                    respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "No active identity"))
                    return
                }
                respond(route, nip46PayloadString(request.id, userHex))
            }

            Nip46Method.PING -> respond(route, nip46PayloadString(request.id, "pong"))

            Nip46Method.GET_RELAYS, Nip46Method.SWITCH_RELAYS ->
                // The pairing's negotiated relays are fine; nothing to switch.
                respond(route, nip46PayloadNull(request.id))

            else -> decideAndAct(request, pairing, route)
        }
    }

    /**
     * Inbound `connect` (the `bunker://` paste direction): a client holding our
     * displayed URI asks to pair. Params: `[remote-signer-pubkey (== our user),
     * secret, requested_perms?, client_metadata?]`. A valid single-use secret
     * creates the pairing; already-paired clients get an idempotent "ack".
     */
    private suspend fun handleInboundConnect(request: Nip46Request, route: ReplyRoute) {
        val userHex = activeUserHex() ?: return
        val targetSigner = request.params.getOrNull(0)?.jsonPrimitive?.contentOrNull
        if (targetSigner != userHex) {
            Log.w(TAG, "handleInboundConnect: params[0] ${targetSigner?.take(8)} != user; ignoring")
            return
        }

        val existing = pairingStore.findBySession(route.clientPubkeyHex)
        if (existing != null && !existing.revoked) {
            // Re-connect of a live pairing: idempotent ack.
            respond(route, nip46PayloadString(request.id, "ack"))
            return
        }

        val secret = request.params.getOrNull(1)?.jsonPrimitive?.contentOrNull
        val pending = pendingBunkerSecret
        val now = nowSec()
        if (pending == null || now > pending.expiresAt) {
            Log.w(TAG, "handleInboundConnect: no live bunker URI secret; ignoring")
            return
        }
        if (secret == null || Nip46PairingStore.secretHash(secret) != pending.secretHash) {
            // Don't reveal whether a secret exists — just ignore.
            Log.w(TAG, "handleInboundConnect: secret mismatch from ${route.clientPubkeyHex.take(8)}; ignoring")
            return
        }

        // Consume the secret (single-use).
        pendingBunkerSecret = null

        val rawRoute = route as? ReplyRoute.Raw
        val label = clientLabelFromConnect(request)
            ?: rawRoute?.originRelayUrl?.removePrefix("wss://")?.removeSuffix("/")
            ?: "External app"
        // The bunker only ever speaks RAW NIP-46 on the DeSent relay, so the
        // recorded route relays are fixed regardless of where the request
        // arrived (the listener is desent.xyz-only anyway).
        val relays = if (route.transport.isRaw) {
            listOf(Nip46PairingUriParser.normalizeRelay(RelayConfig.EMAIL_RELAY_URL))
        } else {
            emptyList()
        }

        pairingStore.upsert(
            Nip46Pairing(
                sessionPubkey = route.clientPubkeyHex,
                userPubkey = userHex,
                label = label,
                profile = Nip46PermissionProfile.GENERAL_NOSTR,
                pairedAt = now,
                lastUsedAt = now,
                expiresAt = null,
                pairingSecretHash = pending.secretHash,
                relays = relays,
                transport = route.transport
            )
        )
        Log.i(TAG, "bunker:// pairing established with ${route.clientPubkeyHex.take(8)} (label=$label)")
        respond(route, nip46PayloadString(request.id, "ack"))
    }

    /** Client-supplied name from connect params[3] (JSON metadata) or params[2] fallback. */
    private fun clientLabelFromConnect(request: Nip46Request): String? {
        val metadataJson = request.params.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: return null
        val name = runCatching {
            nip46Json.parseToJsonElement(metadataJson).jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return name?.take(120)?.ifBlank { null }
    }

    private suspend fun decideAndAct(
        request: Nip46Request,
        pairing: Nip46Pairing,
        route: ReplyRoute
    ) {
        val kind = request.params.firstOrNull()?.readKind()
        val decision = Nip46Policy.decide(pairing.profile, request.method, kind, pairing.grants)
        Log.d(TAG, "decideAndAct: method=${request.method} kind=$kind profile=${pairing.profile} -> $decision")

        when (decision) {
            Nip46Decision.DENY -> {
                pairingStore.update(route.clientPubkeyHex) { it.copy(denyCount = it.denyCount + 1, lastUsedAt = nowSec()) }
                respond(
                    route,
                    nip46PayloadError(
                        request.id,
                        Nip46ErrorCode.POLICY,
                        "${request.method} not allowed in ${pairing.profile.name.lowercase().replace('_', '-')} profile"
                    )
                )
            }
            Nip46Decision.ALLOW -> executeAllowed(request, pairing, route)
            Nip46Decision.PROMPT -> queuePrompt(request, pairing, route)
        }
    }

    private suspend fun executeAllowed(
        request: Nip46Request,
        pairing: Nip46Pairing,
        route: ReplyRoute
    ) {
        when (request.method) {
            Nip46Method.SIGN_EVENT -> signAndRespond(request, pairing, route)
            Nip46Method.NIP59_UNWRAP -> unwrapAndRespond(request, route)
            Nip46Method.NIP44_ENCRYPT -> nip44EncryptAndRespond(request, route)
            Nip46Method.NIP44_DECRYPT -> nip44DecryptAndRespond(request, route)
            Nip46Method.NIP04_ENCRYPT -> nip04EncryptAndRespond(request, route)
            Nip46Method.NIP04_DECRYPT -> nip04DecryptAndRespond(request, route)
            else -> respond(route, nip46PayloadError(request.id, Nip46ErrorCode.UNSUPPORTED, "Method not supported"))
        }
    }

    /**
     * Sign the pending request (UI tapped Approve). No-op if nothing pending.
     * @param alwaysAllow persist a per-pairing grant so future calls of this
     *        method/kind skip the prompt ("remember my choice").
     */
    fun approvePending(alwaysAllow: Boolean = false) {
        scope.launch {
            val pending = _pending.value ?: return@launch
            if (alwaysAllow) {
                pairingStore.update(pending.route.clientPubkeyHex) {
                    it.copy(grants = it.grants + (Nip46Grant.key(pending.request.method, pending.request.params.firstOrNull()?.readKind()) to true))
                }
            }
            // Dispatch by method so a prompted nip59_unwrap resolves via unwrap,
            // not the sign_event path.
            when (pending.request.method) {
                Nip46Method.NIP59_UNWRAP -> unwrapAndRespond(pending.request, pending.route)
                Nip46Method.NIP44_ENCRYPT -> nip44EncryptAndRespond(pending.request, pending.route)
                Nip46Method.NIP44_DECRYPT -> nip44DecryptAndRespond(pending.request, pending.route)
                Nip46Method.NIP04_ENCRYPT -> nip04EncryptAndRespond(pending.request, pending.route)
                Nip46Method.NIP04_DECRYPT -> nip04DecryptAndRespond(pending.request, pending.route)
                else -> signAndRespond(pending.request, pending.pairing, pending.route)
            }
            _pending.value = null
        }
    }

    /** Deny the pending request (UI tapped Deny / notification dismiss). */
    fun denyPending() {
        scope.launch {
            val pending = _pending.value ?: return@launch
            respond(
                pending.route,
                nip46PayloadError(pending.request.id, Nip46ErrorCode.DENIED, "user denied")
            )
            pairingStore.update(pending.route.clientPubkeyHex) { it.copy(denyCount = it.denyCount + 1, lastUsedAt = nowSec()) }
            _pending.value = null
        }
    }

    private suspend fun queuePrompt(
        request: Nip46Request,
        pairing: Nip46Pairing,
        route: ReplyRoute
    ) {
        // Only one prompt at a time; deny extras so the consumer isn't left hanging.
        if (_pending.value != null) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.DENIED, "Another request is already pending"))
            return
        }
        val userHex = activeUserHex() ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "No active identity"))
            return
        }
        val paramJson = request.params.firstOrNull()?.toPreview()
        _pending.value = PendingRequest(
            request = request,
            pairing = pairing,
            route = route,
            userPubkeyHex = userHex
        )
        Log.d(TAG, "Queued prompt for ${request.method} from ${pairing.label} (id=${request.id})")
    }

    private suspend fun signAndRespond(
        request: Nip46Request,
        pairing: Nip46Pairing,
        route: ReplyRoute
    ) {
        val unsigned = try {
            parseUnsignedEvent(request.params.firstOrNull())
        } catch (e: Exception) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Malformed event"))
            return
        }
        val identity = activeIdentity() ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "No active identity"))
            return
        }
        val signed = NostrSigner.sign(unsigned, identity).getOrNull() ?: run {
            Log.e(TAG, "signAndRespond: NostrSigner.sign failed for id=${request.id}")
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Signing failed"))
            return
        }
        Log.d(TAG, "signAndRespond: signed kind=${unsigned.kind} id=${request.id} eventId=${signed.id.take(8)}; publishing response")
        respond(
            route,
            // GIFT_WRAP path returns the object verbatim; RAW path stringifies
            // (both handled by encodeResponse).
            nip46PayloadResult(request.id, nip46Json.parseToJsonElement(signed.serialized))
        )
        pairingStore.update(route.clientPubkeyHex) {
            it.copy(signCount = it.signCount + 1, lastUsedAt = nowSec(), lastKindSigned = unsigned.kind)
        }
    }

    /**
     * Handle `nip59_unwrap`: unwrap a kind-1059 gift wrap addressed to the user
     * (the same 3-layer NIP-59 unwrap used to receive sign requests) and return
     * the rumor + seal pubkey. Lets the web inbox decrypt emails in one round
     * trip instead of many nip44_decrypt calls.
     */
    private suspend fun unwrapAndRespond(request: Nip46Request, route: ReplyRoute) {
        val param = request.params.firstOrNull()?.jsonObject ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Missing gift wrap param"))
            return
        }
        val content = param["content"]?.jsonPrimitive?.contentOrNull ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Missing gift wrap content"))
            return
        }
        val giftWrapPubkey = param["pubkey"]?.jsonPrimitive?.contentOrNull ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Missing gift wrap pubkey"))
            return
        }

        val unwrapped = giftWrap.unwrapGiftRaw(content, giftWrapPubkey).getOrNull()
        if (unwrapped == null) {
            Log.w(TAG, "unwrapAndRespond: unwrap failed for id=${request.id}")
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Unwrap failed"))
            return
        }

        val result = kotlinx.serialization.json.buildJsonObject {
            put("rumor", nip46Json.parseToJsonElement(unwrapped.rumorJson))
            put("seal_pubkey", unwrapped.sealPubkeyHex)
        }
        Log.d(TAG, "unwrapAndRespond: unwrapped id=${request.id} seal=${unwrapped.sealPubkeyHex.take(8)}; publishing response")
        respond(route, nip46PayloadResult(request.id, result))
        pairingStore.update(route.clientPubkeyHex) { it.copy(signCount = it.signCount + 1, lastUsedAt = nowSec()) }
    }

    /**
     * Handle `nip44_encrypt`: params = [peer_pubkey_hex, plaintext]. The peer
     * defaults to the active user's own pubkey (self-encryption) when only the
     * plaintext is supplied — this is the case used by thin clients reading
     * NIP-78 private-storage notes (refs/PRIVATE_STORAGE_PROTOCOL.md).
     */
    private suspend fun nip44EncryptAndRespond(request: Nip46Request, route: ReplyRoute) {
        val identity = activeIdentity() ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "No active identity"))
            return
        }
        val params = request.params
        val (peerHex, plaintext) = when (params.size) {
            2 -> params[0].jsonPrimitive.content to params[1].jsonPrimitive.content
            1 -> identity.publicKey.toHexString() to params[0].jsonPrimitive.content
            else -> {
                respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Expected [peer_pubkey, plaintext]"))
                return
            }
        }
        val ciphertext = try {
            val conv = Nip44Encryption.getConversationKey(identity.privateKey.rawData, Nip44Encryption.hexToBytes(peerHex))
            Nip44Encryption.encrypt(plaintext, conv)
        } catch (e: Exception) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "encrypt failed: ${e.message}"))
            return
        }
        Log.d(TAG, "nip44EncryptAndRespond: encrypted id=${request.id} peer=${peerHex.take(8)}")
        respond(route, nip46PayloadString(request.id, ciphertext))
        pairingStore.update(route.clientPubkeyHex) { it.copy(signCount = it.signCount + 1, lastUsedAt = nowSec()) }
    }

    /**
     * Handle `nip44_decrypt`: params = [peer_pubkey_hex, ciphertext]. The peer
     * defaults to the active user's own pubkey (self-decryption) when only the
     * ciphertext is supplied.
     */
    private suspend fun nip44DecryptAndRespond(request: Nip46Request, route: ReplyRoute) {
        val identity = activeIdentity() ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "No active identity"))
            return
        }
        val params = request.params
        val (peerHex, ciphertext) = when (params.size) {
            2 -> params[0].jsonPrimitive.content to params[1].jsonPrimitive.content
            1 -> identity.publicKey.toHexString() to params[0].jsonPrimitive.content
            else -> {
                respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Expected [peer_pubkey, ciphertext]"))
                return
            }
        }
        val plaintext = try {
            val conv = Nip44Encryption.getConversationKey(identity.privateKey.rawData, Nip44Encryption.hexToBytes(peerHex))
            Nip44Encryption.decrypt(ciphertext, conv)
        } catch (e: Exception) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "decrypt failed: ${e.message}"))
            return
        }
        Log.d(TAG, "nip44DecryptAndRespond: decrypted id=${request.id} peer=${peerHex.take(8)}")
        respond(route, nip46PayloadString(request.id, plaintext))
        pairingStore.update(route.clientPubkeyHex) { it.copy(signCount = it.signCount + 1, lastUsedAt = nowSec()) }
    }

    /**
     * Handle `nip04_encrypt` (legacy NIP-04, still requested by some external
     * clients): params = [third_party_pubkey, plaintext].
     */
    private suspend fun nip04EncryptAndRespond(request: Nip46Request, route: ReplyRoute) {
        val identity = activeIdentity() ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "No active identity"))
            return
        }
        val params = request.params
        if (params.size < 2) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Expected [peer_pubkey, plaintext]"))
            return
        }
        val ciphertext = try {
            MessageCipher04(identity.privateKey.rawData, PublicKey(params[0].jsonPrimitive.content).rawData)
                .encrypt(params[1].jsonPrimitive.content)
        } catch (e: Exception) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "encrypt failed: ${e.message}"))
            return
        }
        respond(route, nip46PayloadString(request.id, ciphertext))
        pairingStore.update(route.clientPubkeyHex) { it.copy(signCount = it.signCount + 1, lastUsedAt = nowSec()) }
    }

    /**
     * Handle `nip04_decrypt`: params = [third_party_pubkey, ciphertext].
     */
    private suspend fun nip04DecryptAndRespond(request: Nip46Request, route: ReplyRoute) {
        val identity = activeIdentity() ?: run {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "No active identity"))
            return
        }
        val params = request.params
        if (params.size < 2) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "Expected [peer_pubkey, ciphertext]"))
            return
        }
        val plaintext = try {
            MessageCipher04(identity.privateKey.rawData, PublicKey(params[0].jsonPrimitive.content).rawData)
                .decrypt(params[1].jsonPrimitive.content)
        } catch (e: Exception) {
            respond(route, nip46PayloadError(request.id, Nip46ErrorCode.BAD_REQUEST, "decrypt failed: ${e.message}"))
            return
        }
        respond(route, nip46PayloadString(request.id, plaintext))
        pairingStore.update(route.clientPubkeyHex) { it.copy(signCount = it.signCount + 1, lastUsedAt = nowSec()) }
    }

    // ------------------------------------------------------------------
    // Pairing lifecycle (called by the pair-confirm UI)
    // ------------------------------------------------------------------

    /**
     * Approve a scanned `nostrconnect://` pairing URI: persist the record and
     * send the `connect` handshake to the consumer on its transport. Returns
     * failure on identity/publish errors.
     */
    suspend fun approvePairing(
        uri: Nip46PairingUri,
        profile: Nip46PermissionProfile,
        durationSeconds: Long?
    ): Result<Unit> = runCatching {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
        val userHex = identity.publicKey.toHexString()
        val now = nowSec()

        pairingStore.upsert(
            Nip46Pairing(
                sessionPubkey = uri.sessionPubkey,
                userPubkey = userHex,
                label = uri.label ?: "Unknown app",
                profile = profile,
                pairedAt = now,
                lastUsedAt = now,
                expiresAt = durationSeconds?.let { now + it },
                pairingSecretHash = Nip46PairingStore.secretHash(uri.secret),
                relays = if (uri.transport.isRaw) uri.relayUrls else emptyList(),
                transport = uri.transport
            )
        )

        // Send the connect request (§6.2): params = [client_pubkey, secret, user_pubkey].
        val connect = Nip46Request(
            id = "connect-${UUID.randomUUID()}",
            method = Nip46Method.CONNECT,
            params = listOf(
                JsonPrimitive(uri.sessionPubkey),
                JsonPrimitive(uri.secret),
                JsonPrimitive(userHex)
            )
        )
        val connectJson = nip46Json.encodeToString(Nip46Request.serializer(), connect)

        if (uri.transport.isRaw) {
            connectRawRelays(uri.relayUrls)
            publishRawEvent(connectJson, uri.sessionPubkey, uri.relayUrls, originRelayUrl = null).getOrThrow()
            activeUserHex()?.let { subscribeRawOn(uri.relayUrls, it) }
        } else {
            publishRumor(connectJson, uri.sessionPubkey, userHex, connect.id)
        }
        Log.i(TAG, "Pairing approved + connect sent to ${uri.sessionPubkey.take(8)} (${uri.transport})")
    }

    /**
     * Generate a fresh `bunker://` connection URI (signer-initiated pairing):
     * the user shows/pastes it into an external app, which then sends us an
     * inbound `connect`. The embedded secret is single-use and expires after
     * [BUNKER_SECRET_TTL_SECONDS]; generating a new URI invalidates the old one.
     */
    suspend fun generateBunkerUri(): Result<String> = runCatching {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
        val userHex = identity.publicKey.toHexString()
        val now = nowSec()

        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        pendingBunkerSecret = PendingBunkerSecret(
            secret = secret,
            secretHash = Nip46PairingStore.secretHash(secret),
            createdAt = now,
            expiresAt = now + BUNKER_SECRET_TTL_SECONDS
        )

        val relay = Nip46PairingUriParser.normalizeRelay(RelayConfig.EMAIL_RELAY_URL)
        "bunker://$userHex?relay=${urlEncode(relay)}&secret=$secret"
    }

    /** Drop any unconsumed bunker:// secret (e.g. the user closed the screen). */
    fun clearPendingBunkerSecret() {
        pendingBunkerSecret = null
    }

    /**
     * Reconnect + re-subscribe the relays needed for RAW-transport NIP-46 —
     * always the DeSent service relay (the app never speaks RAW NIP-46 on
     * third-party relays), so inbound `bunker://` connects reach us even
     * before the first RAW pairing exists. Idempotent; persistent
     * subscriptions survive relay reconnects via the relay repo.
     */
    suspend fun ensureRawTransport() {
        val userHex = activeUserHex() ?: return
        val relays = listOf(RelayConfig.EMAIL_RELAY_URL)
        connectRawRelays(relays)
        subscribeRawOn(relays, userHex)
    }

    private fun rawSubscriptionId(userHex: String) = "nip46raw_${userHex.take(8)}"

    /** Open the persistent kind-24133 `#p=[user]` subscription on each relay. */
    private suspend fun subscribeRawOn(relays: List<String>, userHex: String) {
        val subId = rawSubscriptionId(userHex)
        val since = relaySyncWatermarks?.sinceFilterFor(
            userHex,
            xyz.desent.data.relay.RelaySyncWatermarks.SCOPE_NIP46_RAW
        )
        for (url in relays) {
            try {
                relayRepository.subscribeToEventsOnRelay(
                    listOf(
                        mapOf(
                            "kinds" to listOf(NostrKinds.NIP46_REQUEST),
                            "#p" to listOf(userHex),
                            "limit" to 50
                        ).let { filter ->
                            if (since == null) filter else filter + ("since" to since)
                        }
                    ),
                    subId,
                    url,
                    persistent = true
                )
                Log.d(TAG, "subscribeRawOn: subscribed $subId on $url")
            } catch (e: Exception) {
                Log.w(TAG, "subscribeRawOn: subscribe failed on $url: ${e.message}")
            }
        }
    }

    /** Connect (and keep connected) each relay needed by RAW pairings. */
    private suspend fun connectRawRelays(relays: List<String>) {
        for (url in relays) {
            try {
                relayRepository.addPersistentRelay(url)
                relayRepository.connectToRelay(url)
                relayRepository.waitForRelayReady(url, timeoutMs = 3000L)
            } catch (e: Exception) {
                Log.w(TAG, "connectRawRelays: $url: ${e.message}")
            }
        }
    }

    fun revoke(sessionPubkeyHex: String) {
        pairingStore.update(sessionPubkeyHex) { it.copy(revoked = true) }
    }

    fun removePairing(sessionPubkeyHex: String) {
        pairingStore.remove(sessionPubkeyHex)
    }

    /** Clear the "always allow" grants a user accumulated on a pairing. */
    fun resetGrants(sessionPubkeyHex: String) {
        pairingStore.update(sessionPubkeyHex) { it.copy(grants = emptyMap()) }
    }

    // ------------------------------------------------------------------
    // Publish helpers
    // ------------------------------------------------------------------

    /** Encode + send a response on the request's transport. */
    private suspend fun respond(route: ReplyRoute, payload: Nip46Payload) {
        val responseJson = encodeResponse(payload, route.transport)
        when (route) {
            is ReplyRoute.GiftWrap -> {
                val identity = activeIdentity() ?: return
                val reqId = payload.id.ifBlank { "response" }
                publishRumor(responseJson, route.clientPubkeyHex, identity.publicKey.toHexString(), reqId)
            }
            is ReplyRoute.Raw -> {
                publishRawEvent(responseJson, route.clientPubkeyHex, route.relays, route.originRelayUrl)
                    .onFailure { Log.e(TAG, "respond: raw publish failed to ${route.clientPubkeyHex.take(8)}: ${it.message}") }
            }
        }
    }

    /**
     * Gift-wrap [rumorContent] (a NIP-46 request/response JSON) addressed to
     * [sessionPubkeyHex], signed by the active user, and publish the kind-1059
     * event to desent.xyz.
     */
    private suspend fun publishRumor(
        rumorContent: String,
        sessionPubkeyHex: String,
        userHex: String,
        requestId: String
    ) {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull() ?: return
        val userNpub = Bech32Utils.hexToNpub(userHex)
        val sessionNpub = Bech32Utils.hexToNpub(sessionPubkeyHex)

        val event = giftWrap.wrapGift(
            content = rumorContent,
            recipientNpub = sessionNpub,
            senderNpub = userNpub,
            kind = 14,
            extraTags = listOf(
                listOf("bridge", "nip46"),
                listOf("nip46_id", requestId),
                listOf("nip46_session", sessionPubkeyHex)
            )
        ).getOrThrow()

        try {
            relayRepository.publishEventToRelay(event, RelayConfig.EMAIL_RELAY_URL)
            Log.d(TAG, "publishRumor: published NIP-46 message to session=${sessionPubkeyHex.take(8)} on ${RelayConfig.EMAIL_RELAY_URL} (eventId=${event.id.take(8)})")
        } catch (e: Exception) {
            Log.e(TAG, "publishRumor: failed to publish to ${RelayConfig.EMAIL_RELAY_URL}: ${e.message}", e)
            throw e
        }
    }

    /**
     * Publish a standard kind-24133 NIP-46 message: NIP-44 v2 encrypted to the
     * client pubkey, signed by the user, `p`-tagged to the client. Prefers the
     * relay the request arrived on; falls back to the pairing's relays.
     *
     * Hard-scoped to the DeSent service relay: any foreign relay in the route
     * (e.g. a legacy pairing recorded before the lockdown) is dropped — the
     * app never publishes NIP-46 traffic anywhere else.
     */
    private suspend fun publishRawEvent(
        payloadJson: String,
        clientPubkeyHex: String,
        relays: List<String>,
        originRelayUrl: String?
    ): Result<Unit> = runCatching {
        val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()

        val conv = Nip44Encryption.getConversationKey(
            identity.privateKey.rawData,
            Nip44Encryption.hexToBytes(clientPubkeyHex)
        )
        val content = Nip44Encryption.encrypt(payloadJson, conv)

        @Suppress("UNCHECKED_CAST")
        val tags: List<BaseTag> = listOf(GenericTag("p", listOf(clientPubkeyHex))) as List<BaseTag>
        val event = GenericEvent.builder()
            .pubKey(identity.publicKey)
            .kind(NostrKinds.NIP46_REQUEST)
            .createdAt(System.currentTimeMillis() / 1000)
            .content(content)
            .tags(tags)
            .build()
        identity.sign(event)

        val desentNormalized = Nip46PairingUriParser.normalizeRelay(RelayConfig.EMAIL_RELAY_URL)
        val targets = (listOfNotNull(originRelayUrl) + relays).distinct()
            .filter { Nip46PairingUriParser.normalizeRelay(it) == desentNormalized }
            .ifEmpty { listOf(desentNormalized) }
        var lastError: Exception? = null
        for (url in targets) {
            try {
                relayRepository.publishEventToRelay(event, RelayConfig.EMAIL_RELAY_URL)
                Log.d(TAG, "publishRawEvent: published to ${RelayConfig.EMAIL_RELAY_URL} (eventId=${event.id.take(8)})")
                return@runCatching
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "publishRawEvent: ${RelayConfig.EMAIL_RELAY_URL} failed: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("No relays to publish NIP-46 response (client=${clientPubkeyHex.take(8)})")
    }

    // ------------------------------------------------------------------
    // Utils
    // ------------------------------------------------------------------

    private suspend fun activeIdentity(): Identity? =
        secureKeyManager.getIdentityFromStoredNSEC().getOrNull()

    private suspend fun activeUserHex(): String? =
        activeIdentity()?.publicKey?.toHexString()

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun parseUnsignedEvent(element: JsonElement?): UnsignedNostrEvent {
        val obj = parseUnsignedEventParam(element) ?: throw IllegalArgumentException("Missing/invalid event param")
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing kind")
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val tags: List<List<String>> = (obj["tags"] as? kotlinx.serialization.json.JsonArray)?.map { row ->
            (row as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } ?: emptyList()
        // created_at: 0 (or missing) means "now" for some clients; epoch-0
        // events would be rejected by relays as far-past.
        val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
        return UnsignedNostrEvent(kind = kind, content = content, tags = tags, createdAt = createdAt)
    }

    private data class PendingRequest(
        val request: Nip46Request,
        val pairing: Nip46Pairing,
        val route: ReplyRoute,
        val userPubkeyHex: String
    )

    private fun PendingRequest.toPrompt(): Nip46SignPrompt {
        val param = request.params.firstOrNull()
        return Nip46SignPrompt(
            requestId = request.id,
            sessionPubkey = route.clientPubkeyHex,
            label = pairing.label,
            userPubkey = userPubkeyHex,
            method = request.method,
            unsignedEventJson = param?.toPreview(),
            eventKind = param?.readKind()
        )
    }
}
