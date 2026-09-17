package xyz.desent.crypto

import nostr.base.Signature
import nostr.crypto.schnorr.Schnorr
import nostr.event.impl.GenericEvent
import nostr.event.serializer.EventSerializer
import nostr.event.tag.GenericTag
import nostr.event.BaseTag
import nostr.util.NostrUtil
import java.security.SecureRandom

/**
 * Canonical NIP-01 event serialization / id / schnorr-signing primitives
 * shared by the gift-wrap pipeline and the key-rotation re-publish path.
 *
 * Signing is manual (canonical bytes + [Schnorr.sign]) instead of via
 * `Identity.sign()`: nostr-java's `update()` overwrites `created_at` with
 * `Instant.now()`, which would discard NIP-59 timestamp randomization and
 * any verbatim timestamp a caller wants to preserve.
 */
object NostrEventCrypto {

    private val random = SecureRandom()

    /**
     * Canonical NIP-01 event serialization `[0, pubkey, created_at, kind,
     * tags, content]` using nostr-java's own serializer, so our bytes match
     * what relays and other clients recompute.
     */
    fun canonicalEventBytes(
        pubKeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val baseTags: List<BaseTag> = tags
            .filter { it.isNotEmpty() }
            .map { GenericTag(it[0], it.drop(1)) } as List<BaseTag>
        return EventSerializer.serializeToBytes(
            nostr.base.PublicKey(pubKeyHex),
            createdAt,
            kind,
            baseTags,
            content
        )
    }

    /** The NIP-01 event id: sha256 over the canonical serialization. */
    fun computeEventId(
        pubKeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String =
        NostrUtil.bytesToHex(
            NostrUtil.sha256(canonicalEventBytes(pubKeyHex, createdAt, kind, tags, content))
        )

    /** Schnorr-sign the sha256 of the canonical serialization; returns hex. */
    fun signHex(serializedEventBytes: ByteArray, privateKey: ByteArray): String = try {
        val idBytes = NostrUtil.sha256(serializedEventBytes)
        val aux = ByteArray(32).also { random.nextBytes(it) }
        NostrUtil.bytesToHex(Schnorr.sign(idBytes, privateKey, aux))
    } catch (e: Exception) {
        throw IllegalStateException("Failed to sign event: ${e.message}", e)
    }

    /** Schnorr-sign an arbitrary 32-byte digest directly; returns hex. */
    fun signDigestHex(digest32: ByteArray, privateKey: ByteArray): String = try {
        val aux = ByteArray(32).also { random.nextBytes(it) }
        NostrUtil.bytesToHex(Schnorr.sign(digest32, privateKey, aux))
    } catch (e: Exception) {
        throw IllegalStateException("Failed to sign digest: ${e.message}", e)
    }

    /** Schnorr-verify a 64-byte signature over a 32-byte digest. */
    fun verifyDigest(digest32: ByteArray, publicKey32: ByteArray, signature64: ByteArray): Boolean =
        try {
            Schnorr.verify(digest32, publicKey32, signature64)
        } catch (e: Exception) {
            false
        }

    /**
     * Uniformly random timestamp up to two days in the past (NIP-59: seal and
     * wrap `created_at` SHOULD be randomized to hide timing metadata).
     */
    fun randomizeTimestamp(maxSkewSeconds: Long = 172_800L): Long {
        val now = System.currentTimeMillis() / 1000
        val offset = random.nextInt((maxSkewSeconds + 1).toInt()).toLong()
        return now - offset
    }

    /**
     * Build a fully signed [GenericEvent] from raw fields (id set manually,
     * signature set from [signHex]). Used to re-publish an existing event's
     * content verbatim under a different key.
     */
    fun buildSignedEvent(
        pubKeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        privateKey: ByteArray
    ): GenericEvent {
        @Suppress("UNCHECKED_CAST")
        val baseTags: List<BaseTag> = tags
            .filter { it.isNotEmpty() }
            .map { GenericTag(it[0], it.drop(1)) } as List<BaseTag>

        val event = GenericEvent.builder()
            .pubKey(nostr.base.PublicKey(pubKeyHex))
            .kind(kind)
            .createdAt(createdAt)
            .content(content)
            .tags(baseTags)
            .build()

        event.setId(computeEventId(pubKeyHex, createdAt, kind, tags, content))
        event.setSignature(
            Signature.fromString(
                signHex(canonicalEventBytes(pubKeyHex, createdAt, kind, tags, content), privateKey)
            )
        )
        return event
    }
}
