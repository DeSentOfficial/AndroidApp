package xyz.desent.crypto

import nostr.event.BaseTag
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import xyz.desent.domain.model.UnsignedNostrEvent

/**
 * The result of schnorr-signing an [UnsignedNostrEvent] with a Nostr [Identity].
 *
 * [serialized] is the canonical JSON object form ready to hand back to a
 * consumer (NIP-46 bunker, `desent://sign` callback, etc.):
 * `{"id","pubkey","created_at","kind","tags","content","sig"}`.
 */
data class SignedNostrEvent(
    val id: String,
    val pubkey: String,
    val signature: String,
    val kind: Long,
    val createdAt: Long,
    val content: String,
    val tags: List<List<String>>,
    val serialized: String
)

/**
 * Schnorr-signs an [UnsignedNostrEvent] (which carries no `id`/`sig`/`pubkey`)
 * with the given [Identity]. Computes the event id, sets the pubkey, signs, and
 * returns the canonical serialization. Used by the NIP-46 bunker and the
 * `desent://sign` flow.
 */
object NostrSigner {

    fun sign(unsigned: UnsignedNostrEvent, identity: Identity): Result<SignedNostrEvent> = runCatching {
        val pubKey = identity.publicKey
        val createdAt = unsigned.createdAt ?: (System.currentTimeMillis() / 1000)

        @Suppress("UNCHECKED_CAST")
        val tags: List<BaseTag> = unsigned.tags
            .filter { it.isNotEmpty() }
            .map { GenericTag(it[0], it.drop(1)) } as List<BaseTag>

        val event = GenericEvent.builder()
            .pubKey(pubKey)
            .kind(unsigned.kind.toInt())
            .createdAt(createdAt)
            .content(unsigned.content)
            .tags(tags)
            .build()

        identity.sign(event)

        val id = event.id
        val pubkeyHex = pubKey.toHexString()
        val sig = event.signature?.toString()
            ?: throw IllegalStateException("Signature missing after sign()")
        val actualCreatedAt = event.createdAt

        SignedNostrEvent(
            id = id,
            pubkey = pubkeyHex,
            signature = sig,
            kind = unsigned.kind,
            createdAt = actualCreatedAt,
            content = unsigned.content,
            tags = unsigned.tags,
            serialized = serialize(id, pubkeyHex, actualCreatedAt, unsigned.kind, unsigned.tags, unsigned.content, sig)
        )
    }

    /** Canonical Nostr event JSON object (NIP-01). */
    private fun serialize(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Long,
        tags: List<List<String>>,
        content: String,
        sig: String
    ): String {
        val tagsJson = tags.joinToString(separator = ",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"" + escapeJson(it) + "\"" }
        }
        return "{\"id\":\"${escapeJson(id)}\",\"pubkey\":\"${escapeJson(pubkey)}\",\"created_at\":$createdAt,\"kind\":$kind,\"tags\":$tagsJson,\"content\":\"${escapeJson(content)}\",\"sig\":\"${escapeJson(sig)}\"}"
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
