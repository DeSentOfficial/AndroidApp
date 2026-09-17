package xyz.desent.crypto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * NIP-59 mail re-wrapping for key rotation
 * (refs/FROM_email.desent.xyz/KEY_ROTATION.md §4).
 *
 * A gift wrap `p`-tagged to the OLD key is unwrapped with the old private
 * key, the decrypted rumor is re-targeted (`pubkey` → new key, its own `p`
 * tag → new key; every other tag verbatim so RFC 5322 threading survives),
 * then re-sealed (kind 13, NIP-44 to the new key, signed by the new key) and
 * re-wrapped under a fresh one-time keypair with the NIP-40 `expiration` tag
 * the relay's `/key-rotate/restore` endpoint requires.
 *
 * Relay-authored seals (delivery receipts, security/badge notices) are
 * reported back via [Rewrapped.sealPubkeyHex] so callers can drop them —
 * re-sealing changes the seal signer, and the receiving processor would
 * reject a re-wrapped relay notice as a forgery.
 *
 * Pure crypto: no Android dependencies, unit-testable.
 */
object GiftRewrap {

    class RewrapException(message: String) : Exception(message)

    data class Rewrapped(
        /** The new kind-1059 event as standard Nostr event JSON. */
        val wrapEventJson: String,
        /** The ORIGINAL seal signer (the real sender of the rumor). */
        val sealPubkeyHex: String,
        /** The rumor kind (1010 email, 14 chat/bridge, …). */
        val rumorKind: Int,
        val rumorCreatedAt: Long
    )

    private val decodeJson = Json { ignoreUnknownKeys = true }
    private val encodeJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * @param wrapContent the old wrap's `content` (NIP-44 payload)
     * @param wrapAuthorPubkeyHex the old wrap's one-time author pubkey
     * @param oldPrivateKey raw 32 bytes of the retiring key (unwrap side)
     * @param oldPublicKey raw 32 bytes of the retiring key (retarget compare)
     * @param newPrivateKey raw 32 bytes of the new key (seal signer)
     * @param newPublicKey raw 32 bytes of the new key (seal/wrap recipient)
     * @param expirationEpochSeconds NIP-40 tag for the new wrap (server
     *        requires one — keep the original when still sane, else now+90d)
     * @throws RewrapException unwrap/decrypt failure (not addressed to the
     *         old key, tampered seal, malformed payload)
     */
    fun rewrap(
        wrapContent: String,
        wrapAuthorPubkeyHex: String,
        oldPrivateKey: ByteArray,
        oldPublicKey: ByteArray,
        newPrivateKey: ByteArray,
        newPublicKey: ByteArray,
        expirationEpochSeconds: Long
    ): Rewrapped {
        val oldHex = Nip44Encryption.bytesToHex(oldPublicKey)
        val newHex = Nip44Encryption.bytesToHex(newPublicKey)

        // Layer 1 — unwrap the 1059 with the OLD key → seal.
        val sealJson = try {
            val outerConv = Nip44Encryption.getConversationKey(
                oldPrivateKey,
                Nip44Encryption.hexToBytes(wrapAuthorPubkeyHex)
            )
            Nip44Encryption.decrypt(wrapContent, outerConv)
        } catch (e: Exception) {
            throw RewrapException("gift-wrap layer unwrap failed: ${e.message}")
        }
        val seal = try {
            decodeJson.decodeFromString<Seal>(sealJson)
        } catch (e: Exception) {
            throw RewrapException("malformed seal: ${e.message}")
        }
        if ((seal.id != null || seal.sig != null) && !verifySeal(seal)) {
            throw RewrapException("seal signature verification failed")
        }

        // Layer 2 — decrypt the rumor with the OLD key.
        val rumorJson = try {
            val innerConv = Nip44Encryption.getConversationKey(
                oldPrivateKey,
                Nip44Encryption.hexToBytes(seal.pubkey)
            )
            Nip44Encryption.decrypt(seal.content, innerConv)
        } catch (e: Exception) {
            throw RewrapException("rumor decrypt failed: ${e.message}")
        }
        val rumor = try {
            decodeJson.decodeFromString<Rumor>(rumorJson)
        } catch (e: Exception) {
            throw RewrapException("malformed rumor: ${e.message}")
        }

        // Re-target: author → new key; the rumor's own p tag (old key) → new
        // key. Everything else verbatim so threading/attachment tags survive.
        val retargetedTags = rumor.tags.map { tag ->
            if (tag.size >= 2 && tag[0] == "p" && tag[1] == oldHex) {
                listOf(tag[0], newHex) + tag.drop(2)
            } else {
                tag
            }
        }
        val retargeted = rumor.copy(pubkey = newHex, tags = retargetedTags)
        val retargetedJson = encodeJson.encodeToString(retargeted)

        // New seal — kind 13, NIP-44 keyed (new → new), signed by the new key.
        val sealContent = Nip44Encryption.encrypt(
            retargetedJson,
            Nip44Encryption.getConversationKey(newPrivateKey, newPublicKey)
        )
        val sealCreatedAt = NostrEventCrypto.randomizeTimestamp()
        val newSeal = Seal(
            pubkey = newHex,
            kind = 13,
            content = sealContent,
            created_at = sealCreatedAt,
            tags = emptyList(),
            id = NostrEventCrypto.computeEventId(newHex, sealCreatedAt, 13, emptyList(), sealContent),
            sig = NostrEventCrypto.signHex(
                NostrEventCrypto.canonicalEventBytes(newHex, sealCreatedAt, 13, emptyList(), sealContent),
                newPrivateKey
            )
        )
        val newSealJson = encodeJson.encodeToString(newSeal)

        // New wrap — fresh one-time keypair, NIP-44 keyed (ephemeral → new),
        // tags p + the required expiration.
        val ephemeralPriv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val ephemeralPubHex = Nip44Encryption.bytesToHex(
            Nip44Encryption.derivePublicKey(ephemeralPriv)
        )
        val wrapContent2 = Nip44Encryption.encrypt(
            newSealJson,
            Nip44Encryption.getConversationKey(ephemeralPriv, newPublicKey)
        )
        val wrapTags = listOf(
            listOf("p", newHex),
            listOf("expiration", expirationEpochSeconds.toString())
        )
        val wrapCreatedAt = NostrEventCrypto.randomizeTimestamp()
        val wrapEventJson = buildEventJson(
            id = NostrEventCrypto.computeEventId(ephemeralPubHex, wrapCreatedAt, 1059, wrapTags, wrapContent2),
            pubkey = ephemeralPubHex,
            createdAt = wrapCreatedAt,
            kind = 1059,
            tags = wrapTags,
            content = wrapContent2,
            sig = NostrEventCrypto.signHex(
                NostrEventCrypto.canonicalEventBytes(ephemeralPubHex, wrapCreatedAt, 1059, wrapTags, wrapContent2),
                ephemeralPriv
            )
        )

        return Rewrapped(
            wrapEventJson = wrapEventJson,
            sealPubkeyHex = seal.pubkey,
            rumorKind = rumor.kind,
            rumorCreatedAt = rumor.created_at
        )
    }

    /** Expiration policy from KEY_ROTATION.md §5 / ANDROID_KEY_ROTATION.md §5. */
    fun nextExpiration(original: Long?, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Long {
        val tenMinutes = 10 * 60L
        val ninetyDays = 90L * 24 * 60 * 60
        return if (original != null && original > nowEpochSeconds + tenMinutes) {
            original
        } else {
            nowEpochSeconds + ninetyDays
        }
    }

    /**
     * Verify a seal the way any conformant client must: recompute the event
     * id over the canonical serialization and schnorr-verify the signature
     * against the seal's claimed pubkey.
     */
    private fun verifySeal(seal: Seal): Boolean {
        val id = seal.id ?: return false
        val sig = seal.sig ?: return false
        return try {
            val serialized = NostrEventCrypto.canonicalEventBytes(
                seal.pubkey, seal.created_at, seal.kind, seal.tags, seal.content
            )
            val idBytes = nostr.util.NostrUtil.sha256(serialized)
            nostr.util.NostrUtil.bytesToHex(idBytes) == id &&
                NostrEventCrypto.verifyDigest(
                    idBytes,
                    Nip44Encryption.hexToBytes(seal.pubkey),
                    Nip44Encryption.hexToBytes(sig)
                )
        } catch (e: Exception) {
            false
        }
    }

    /** Standard Nostr event JSON with full escaping (safe for arbitrary content). */
    internal fun buildEventJson(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        sig: String
    ): String {
        val root: JsonObject = buildJsonObject {
            put("id", id)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            putJsonArray("tags") {
                tags.forEach { tag ->
                    add(kotlinx.serialization.json.buildJsonArray {
                        tag.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                }
            }
            put("content", content)
            put("sig", sig)
        }
        return root.toString()
    }
}
