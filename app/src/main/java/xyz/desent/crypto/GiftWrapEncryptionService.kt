package xyz.desent.crypto

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nostr.base.Signature
import nostr.crypto.schnorr.Schnorr
import nostr.event.BaseTag
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import nostr.util.NostrUtil
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.UnwrappedContent
import java.security.SecureRandom

/**
 * NIP-17/59: GiftWrap Encryption Service
 *
 * Produces and consumes NIP-59 gift wraps (kind 1059) using **real NIP-44 v2**
 * encryption ([Nip44Encryption]) for both the seal and the gift-wrap layers.
 *
 * 3-layer construction (per NIP-59):
 *  - Rumor (unsigned, kind 14 chat / other kinds on request) — real `created_at`
 *  - Seal (kind 13) — a **fully signed event** whose content is the NIP-44
 *    encrypted rumor; `created_at` randomized up to 2 days back
 *  - Gift wrap (kind 1059) — authored by a **fresh one-time keypair generated
 *    per message** (NIP-59 requirement: the wrap author is never the sender);
 *    content = NIP-44 encrypted seal keyed to *(ephemeral wrap key, recipient)*;
 *    `created_at` randomized up to 2 days back
 *
 * For group chats, one gift wrap is produced per recipient.
 */
class GiftWrapEncryptionService(
    private val secureKeyManager: SecureKeyManager
) {
    companion object {
        private const val TAG = "GiftWrapEncryption"

        /** NIP-59: seal and wrap timestamps may be randomized up to 2 days back. */
        private const val MAX_TIMESTAMP_SKEW_SECONDS = 172_800L
    }

    /** Decode stays lenient: legacy seals without id/sig/kind must still parse. */
    private val decodeJson = Json { ignoreUnknownKeys = true }

    /** Encode must emit every field (kind/tags/id/sig) so the seal is a full event. */
    private val encodeJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------------
    // Wrap (encrypt)
    // ------------------------------------------------------------------

    /**
     * Wrap content in a NIP-59 gift-wrap for a single recipient. Returns the
     * **fully signed kind-1059 event** authored by a fresh one-time keypair,
     * ready to publish.
     */
    suspend fun wrapGift(
        content: String,
        recipientNpub: String,
        senderNpub: String,
        kind: Int = NostrKinds.PRIVATE_DIRECT_MESSAGE,
        extraTags: List<List<String>> = emptyList(),
        expiration: Long? = null
    ): Result<GenericEvent> {
        return try {
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
            val senderPriv = identity.privateKey.rawData
            val senderHex = Bech32Utils.npubToHex(senderNpub)
            val recipientHex = Bech32Utils.npubToHex(recipientNpub)
            val recipientPub = Nip44Encryption.hexToBytes(recipientHex)

            val rumorJson = encodeJson.encodeToString(
                buildRumor(content, kind, listOf(listOf("p", recipientHex)) + extraTags, senderHex)
            )
            val sealJson = buildSignedSealJson(identity, senderPriv, senderHex, rumorJson, recipientPub)

            val event = buildEphemeralGiftWrapEvent(recipientHex, sealJson, expiration)

            Log.d(TAG, " GiftWrap event ${event.id.take(8)} created for ${recipientNpub.take(16)}")
            Result.success(event)
        } catch (e: Exception) {
            Log.e(TAG, " Failed to wrap GiftWrap: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Wrap content in NIP-59 gift-wraps for **multiple recipients** (group chat).
     * Returns one (recipientNpub, signed 1059 event) pair per member — each
     * wrapped with its own one-time keypair — plus the sender's self-copy.
     *
     * @param members   recipient npubs (NOT including the sender)
     * @param kind      rumor kind (14 for chat, 15 for file)
     * @param subject   optional NIP-17 conversation subject
     */
    suspend fun wrapGiftForRecipients(
        content: String,
        members: List<String>,
        senderNpub: String,
        kind: Int = NostrKinds.PRIVATE_DIRECT_MESSAGE,
        subject: String? = null,
        extraTags: List<List<String>> = emptyList(),
        expiration: Long? = null
    ): Result<List<Pair<String, GenericEvent>>> {
        return try {
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
            val senderPriv = identity.privateKey.rawData
            val senderHex = Bech32Utils.npubToHex(senderNpub)

            // Build the rumor once — carries every member's p tag + subject.
            val allMemberHexes = (members.map { Bech32Utils.npubToHex(it) } + senderHex).distinct()
            val tags = allMemberHexes.map { listOf("p", it) }.toMutableList()
            if (subject != null) tags.add(listOf("subject", subject))
            tags.addAll(extraTags)

            val rumorJson = encodeJson.encodeToString(
                buildRumor(content, kind, tags.toList(), senderHex)
            )
            Log.d(TAG, "Group rumor: kind=$kind, ${members.size} members")

            // One gift-wrap per recipient (members + sender self-copy), each with
            // its own one-time wrap keypair.
            val recipients = (members + senderNpub).distinct()
            val results = recipients.map { recipientNpub ->
                val recipientHex = Bech32Utils.npubToHex(recipientNpub)
                val recipientPub = Nip44Encryption.hexToBytes(recipientHex)
                val sealJson = buildSignedSealJson(identity, senderPriv, senderHex, rumorJson, recipientPub)
                val event = buildEphemeralGiftWrapEvent(recipientHex, sealJson, expiration)
                recipientNpub to event
            }

            Log.d(TAG, " Created ${results.size} gift wraps for group")
            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, " Failed to wrap gifts for recipients: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** The unsigned rumor (plaintext chat message) — real creation time. */
    private fun buildRumor(
        content: String,
        kind: Int,
        tags: List<List<String>>,
        senderHex: String
    ): Rumor = Rumor(
        pubkey = senderHex,
        kind = kind,
        content = content,
        tags = tags,
        created_at = System.currentTimeMillis() / 1000
    )

    /**
     * Build the kind-13 seal as a **fully signed event** (NIP-59: the seal
     * signature is what authenticates the sender). Content is the NIP-44
     * encrypted rumor keyed to *(sender, recipient)*; `created_at` is
     * randomized up to two days back per spec.
     *
     * Signed manually (canonical serialization + schnorr) instead of via
     * [Identity.sign]: nostr-java's `update()` — which `sign()` calls —
     * overwrites `created_at` with `Instant.now()`, discarding the
     * randomization NIP-59 asks for.
     */
    private fun buildSignedSealJson(
        identity: Identity,
        senderPriv: ByteArray,
        senderHex: String,
        rumorJson: String,
        recipientPub: ByteArray
    ): String {
        val convKey = Nip44Encryption.getConversationKey(senderPriv, recipientPub)
        val sealContent = Nip44Encryption.encrypt(rumorJson, convKey)
        val sealCreatedAt = randomizeTimestamp()

        val seal = Seal(
            pubkey = senderHex,
            kind = NostrKinds.SEAL,
            content = sealContent,
            created_at = sealCreatedAt,
            tags = emptyList(),
            id = computeEventId(senderHex, sealCreatedAt, NostrKinds.SEAL, emptyList(), sealContent),
            sig = signHex(
                canonicalEventBytes(senderHex, sealCreatedAt, NostrKinds.SEAL, emptyList(), sealContent),
                senderPriv
            )
        )
        return encodeJson.encodeToString(seal)
    }

    /**
     * Build the kind-1059 gift wrap: authored and signed by a **fresh one-time
     * keypair** (never persisted, discarded after publish), `created_at`
     * randomized up to two days back. The wrap content is keyed to
     * *(ephemeral wrap key, recipient)*, which is what a recipient uses to
     * unwrap layer 1.
     */
    private fun buildEphemeralGiftWrapEvent(
        recipientHex: String,
        sealJson: String,
        expiration: Long?
    ): GenericEvent {
        val ephemeral = Identity.create(
            Nip44Encryption.bytesToHex(ByteArray(32).also { SecureRandom().nextBytes(it) })
        )
        val wrapConvKey = Nip44Encryption.getConversationKey(
            ephemeral.privateKey.rawData,
            Nip44Encryption.hexToBytes(recipientHex)
        )
        val giftWrapContent = Nip44Encryption.encrypt(sealJson, wrapConvKey)
        val wrapCreatedAt = randomizeTimestamp()

        val tagList: MutableList<List<String>> = mutableListOf(listOf("p", recipientHex))
        if (expiration != null) tagList.add(listOf("expiration", expiration.toString()))
        @Suppress("UNCHECKED_CAST")
        val tags: List<BaseTag> = tagList.map { GenericTag(it[0], it.drop(1)) } as List<BaseTag>

        val event = GenericEvent.builder()
            .pubKey(ephemeral.publicKey)
            .kind(NostrKinds.GIFT_WRAP)
            .createdAt(wrapCreatedAt)
            .content(giftWrapContent)
            .tags(tags)
            .build()
        // Sign manually (see buildSignedSealJson) so the randomized created_at
        // survives signing.
        event.setId(computeEventId(ephemeral.publicKey.toHexString(), wrapCreatedAt, NostrKinds.GIFT_WRAP, tagList, giftWrapContent))
        event.setSignature(
            Signature.fromString(
                signHex(
                    canonicalEventBytes(ephemeral.publicKey.toHexString(), wrapCreatedAt, NostrKinds.GIFT_WRAP, tagList, giftWrapContent),
                    ephemeral.privateKey.rawData
                )
            )
        )
        return event
    }

    /**
     * Canonical NIP-01 event serialization — see [NostrEventCrypto].
     */
    private fun canonicalEventBytes(
        pubKeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): ByteArray = NostrEventCrypto.canonicalEventBytes(pubKeyHex, createdAt, kind, tags, content)

    private fun computeEventId(
        sealPubKeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String = NostrEventCrypto.computeEventId(sealPubKeyHex, createdAt, kind, tags, content)

    /** Schnorr-sign the sha256 of the canonical serialization; returns hex. */
    private fun signHex(serializedEventBytes: ByteArray, privateKey: ByteArray): String =
        NostrEventCrypto.signHex(serializedEventBytes, privateKey)

    // ------------------------------------------------------------------
    // Unwrap (decrypt)
    // ------------------------------------------------------------------

    /**
     * Unwrap a NIP-17 GiftWrap (kind 1059) using the standard NIP-59 double-unwrap.
     * The returned [UnwrappedContent.senderNpub] is the **seal signer** (the real
     * sender) — never the gift-wrap's one-time outer author.
     */
    suspend fun unwrapGift(content: String, giftWrapPubkeyHex: String): Result<UnwrappedContent> {
        return unwrapCore(content, giftWrapPubkeyHex).mapCatching { (rumorJson, sealPubkeyHex) ->
            val rumor = decodeJson.decodeFromString<Rumor>(rumorJson)
            Log.d(TAG, " GiftWrap unwrapped (kind=${rumor.kind}, tags=${rumor.tags.size})")
            UnwrappedContent(
                content = rumor.content,
                senderNpub = Bech32Utils.hexToNpub(sealPubkeyHex),
                kind = rumor.kind,
                tags = rumor.tags,
                createdAt = rumor.created_at
            )
        }.recoverCatching { e ->
            Log.e(TAG, " Failed to unwrap GiftWrap: ${e.message}", e)
            throw e
        }
    }

    /**
     * NIP-59 double-unwrap that returns the **raw decrypted rumor JSON** and the
     * seal signer's pubkey hex. Used by the NIP-46 `nip59_unwrap` handler to hand
     * the consumer the exact wire rumor (no field loss). Shares [unwrapCore] with
     * [unwrapGift].
     */
    suspend fun unwrapGiftRaw(content: String, giftWrapPubkeyHex: String): Result<GiftUnwrapResult> =
        unwrapCore(content, giftWrapPubkeyHex)

    private suspend fun unwrapCore(content: String, giftWrapPubkeyHex: String): Result<GiftUnwrapResult> {
        return try {
            Log.d(TAG, "Unwrapping GiftWrap (NIP-59 double-unwrap)")
            val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrThrow()
            val ourPrivKey = identity.privateKey.rawData
            val giftWrapPubkey = Nip44Encryption.hexToBytes(giftWrapPubkeyHex)

            // Layer 1: gift wrap (1059, one-time author) -> seal (13)
            val outerConv = Nip44Encryption.getConversationKey(ourPrivKey, giftWrapPubkey)
            val sealJson = Nip44Encryption.decrypt(content, outerConv)
            val seal = decodeJson.decodeFromString<Seal>(sealJson)

            // Signed seals are verified strictly (id + schnorr sig over the
            // sender's pubkey). Seals without id/sig are accepted only as a
            // legacy fallback for pre-conformance DeSent traffic.
            if (seal.id != null || seal.sig != null) {
                if (!verifySeal(seal)) {
                    Log.w(TAG, " Seal signature verification failed for seal author ${seal.pubkey.take(16)}")
                    return Result.failure(SecurityException("Seal signature verification failed"))
                }
            }

            // Layer 2: seal (13) -> rumor (unsigned)
            val sealPubkey = Nip44Encryption.hexToBytes(seal.pubkey)
            val innerConv = Nip44Encryption.getConversationKey(ourPrivKey, sealPubkey)
            val rumorJson = Nip44Encryption.decrypt(seal.content, innerConv)

            Result.success(GiftUnwrapResult(rumorJson = rumorJson, sealPubkeyHex = seal.pubkey))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Verify a seal the way any conformant client must: recompute the event id
     * over the canonical serialization and schnorr-verify the signature against
     * the seal's claimed pubkey.
     */
    private fun verifySeal(seal: Seal): Boolean {
        val id = seal.id ?: run {
            Log.w(TAG, " Seal carries sig but no id")
            return false
        }
        val sig = seal.sig ?: run {
            Log.w(TAG, " Seal carries id but no sig")
            return false
        }
        return try {
            val serialized = canonicalEventBytes(seal.pubkey, seal.created_at, seal.kind, seal.tags, seal.content)
            val idBytes = NostrUtil.sha256(serialized)
            if (NostrUtil.bytesToHex(idBytes) != id) {
                Log.w(
                    TAG,
                    "⚠️ Seal id mismatch: wire=${id.take(16)} recomputed=${NostrUtil.bytesToHex(idBytes).take(16)} " +
                        "(kind=${seal.kind}, tags=${seal.tags.size}, created_at=${seal.created_at})"
                )
                return false
            }
            // NostrUtil.hexToBytes only accepts 32-byte hex — the schnorr sig is
            // 64 bytes, so decode with the length-agnostic helper.
            // Schnorr.verify(msg32, pubkey32, sig64).
            if (!Schnorr.verify(
                    idBytes,
                    Nip44Encryption.hexToBytes(seal.pubkey),
                    Nip44Encryption.hexToBytes(sig)
                )
            ) {
                Log.w(TAG, " Seal schnorr signature invalid for pubkey ${seal.pubkey.take(16)}")
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Seal verification threw: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * NIP-59: seal and gift-wrap events SHOULD have `created_at` randomized
     * uniformly up to two days in the past to hide timing metadata. (The rumor
     * keeps its real creation time.)
     */
    private fun randomizeTimestamp(): Long =
        NostrEventCrypto.randomizeTimestamp(MAX_TIMESTAMP_SKEW_SECONDS)
}

/**
 * Result of a raw NIP-59 unwrap: the decrypted rumor JSON (verbatim) and the
 * seal signer's pubkey hex.
 */
data class GiftUnwrapResult(
    val rumorJson: String,
    val sealPubkeyHex: String
)

/**
 * Seal event structure (NIP-59 kind 13). Fully signed on send (`id`/`sig` set,
 * `kind` always present). Decoding tolerates legacy unsigned seals emitted by
 * older DeSent builds.
 */
@kotlinx.serialization.Serializable
data class Seal(
    val pubkey: String,
    val kind: Int = 13,
    val content: String,
    val created_at: Long = 0,
    val tags: List<List<String>> = emptyList(),
    val id: String? = null,
    val sig: String? = null
)

/**
 * Rumor (plaintext unsigned event) structure
 */
@kotlinx.serialization.Serializable
data class Rumor(
    val pubkey: String,
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
    val created_at: Long
)
