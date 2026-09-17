package xyz.desent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom

/**
 * Mail folders + synced read state (refs/FROM_email.desent.xyz/
 * ANDROID_MAIL_FOLDERS.md, wire contract PRIVATE_STORAGE_PROTOCOL.md
 * §Mail folders). A client-owned overlay over kind-1010 mail: user folders
 * (labels model — filed mail still shows in All mail) and cross-device
 * read/unread, both NIP-44-encrypted to self inside kind 30078 namespaces:
 *
 *  - `desent:mail-folders`        — folder manifest, LWW by `created_at`.
 *  - `desent:mail-state:<i>`      — per-message state shard `i` (16 shards),
 *                                   per-entry LWW by `ts` (LWW-element-set).
 *  - `desent:mail-state:manifest` — informational shard index (ignored).
 *
 * The relay stores only ciphertext: it cannot learn folder names, counts,
 * assignments, or what has been read.
 */

/** One user folder (typed view over a verbatim wire entry — see [raw]). */
data class MailFolder(
    /** Opaque client-generated id (`f_` + ≥ 8 random chars, never a reserved role word). */
    val id: String,
    /** Slash-free display name; hierarchy comes from [parent] ids. */
    val name: String,
    /** Parent folder id, or null for a root folder. */
    val parent: String?,
    /** Cosmetic hint. */
    val color: String?,
    /** Cosmetic sort hint. */
    val sort: Int,
    /**
     * The verbatim wire entry. The round-trip rule (PRIVATE_STORAGE_PROTOCOL
     * §Mail folders) requires unknown keys on folder entries to be preserved
     * on ingest and re-emitted on publish, so mutations rebuild from this
     * object rather than from the typed fields.
     */
    val raw: JsonObject
) {
    /**
     * Display path (RFC 3501 convention): this folder's name appended to the
     * parent chain joined with `/`. Cycles are flattened by visiting each
     * folder at most once.
     */
    fun displayPath(all: List<MailFolder>): String {
        val byId = all.associateBy { it.id }
        val chain = ArrayDeque<String>()
        var cursor: MailFolder? = this
        val visited = mutableSetOf<String>()
        while (cursor != null && visited.add(cursor.id)) {
            chain.addFirst(cursor.name)
            cursor = cursor.parent?.let { byId[it] }
        }
        return chain.joinToString("/")
    }
}

// ---------------------------------------------------------------------------
// Wire payloads (`desent:mail-state:*`)
// ---------------------------------------------------------------------------

/** One per-message state entry on the wire. */
@Serializable
data class MailStateEntry(
    /** Pinned message key: rumor `message_id` tag, else `"ev:" + wrap event id`. */
    val k: String,
    /** Folder id, or null (= unfiled tombstone when it arrives with newer `ts`). */
    val f: String? = null,
    /** 1 = read, 0 = unread. Absent entry = unread (new-mail default). */
    val r: Int = 0,
    /** Unix seconds — the per-entry LWW clock. */
    val ts: Long
)

/** One shard of the per-message state map (`d = "desent:mail-state:<i>"`). */
@Serializable
data class MailStateShard(
    val index: Int,
    val entries: List<MailStateEntry>
)

/** Informational shard index (`d = "desent:mail-state:manifest"`); readers ignore it. */
@Serializable
data class MailStateManifestInfo(
    @SerialName("shard_count") val shardCount: Int,
    @SerialName("total_entries") val totalEntries: Int,
    @SerialName("updated_at") val updatedAt: Long
)

// ---------------------------------------------------------------------------
// Folder manifest codec (`desent:mail-folders`)
// ---------------------------------------------------------------------------
// The manifest is handled as a JsonElement tree (not a typed payload) so
// unknown keys on folder entries survive decode → cache → republish verbatim.

object MailFoldersCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** Decode a manifest ciphertext-plaintext into typed folders (unknown keys ride along in [MailFolder.raw]). */
    fun decode(plaintext: String): List<MailFolder> = runCatching {
        val folders = json.parseToJsonElement(plaintext)
            .jsonObject["folders"] as? JsonArray
            ?: JsonArray(emptyList())
        folders.mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            MailFolder(
                id = id,
                name = name,
                // contentOrNull: a JSON null is a JsonPrimitive whose content
                // is the string "null" — it must decode to Kotlin null.
                parent = obj["parent"]?.jsonPrimitive?.contentOrNull,
                color = obj["color"]?.jsonPrimitive?.contentOrNull,
                sort = obj["sort"]?.jsonPrimitive?.int ?: 0,
                raw = obj
            )
        }
    }.getOrDefault(emptyList())

    /** Build a new wire entry (the typed fields only — extras are attached by decode). */
    fun newEntry(id: String, name: String, parent: String?): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("parent", parent)
        put("color", null as String?)
        put("sort", 0)
    }

    /** Re-encode the manifest. Entries are emitted from [MailFolder.raw] so extras round-trip. */
    fun encode(folders: List<MailFolder>, updatedAt: Long): String = buildJsonObject {
        put("folders", JsonArray(folders.map { it.raw }))
        put("updated_at", updatedAt)
    }.toString()
}

// ---------------------------------------------------------------------------
// Keys, sharding, ids
// ---------------------------------------------------------------------------

object MailStateKeys {

    /** Folder manifest namespace (LWW by event `created_at`). */
    const val FOLDERS_D_TAG = "desent:mail-folders"

    /** Per-message state shard namespace prefix. */
    const val STATE_SHARD_PREFIX = "desent:mail-state:"

    /** Informational shard index — exact-match before prefix-matching shards. */
    const val STATE_MANIFEST_D_TAG = "desent:mail-state:manifest"

    /** Fixed 16-shard keyspace (every device computes the same shard for the same key). */
    const val SHARD_COUNT = 16

    /** Shard cap: 16 × 400 = 6400 tracked messages max. */
    const val MAX_ENTRIES_PER_SHARD = 400

    /** Reserved RFC 6154 / JMAP role words — MUST NOT be used as user folder ids. */
    val RESERVED_FOLDER_IDS = setOf("inbox", "sent", "drafts", "junk", "trash", "archive")

    /**
     * Pinned message key (ANDROID_MAIL_FOLDERS.md §1): the RFC 5322
     * Message-ID is stable across SMTP-retry duplicate wraps and NIP-40
     * expiry; the wrap event id is not, so it is only the fallback.
     */
    fun pinnedKey(messageId: String?, wrapEventId: String): String =
        messageId?.takeIf { it.isNotBlank() } ?: "ev:$wrapEventId"

    /**
     * Shard index — byte-for-byte compatible with the web client: a 32-bit
     * multiply-add hash over the key's UTF-16 code units, reduced mod 16
     * (JS: `h = (Math.imul(h, 31) + key.charCodeAt(i)) >>> 0`).
     */
    fun shardOf(key: String): Int {
        var h = 0u
        for (ch in key) {
            h = (h * 31u + ch.code.toUInt()) and 0xFFFFFFFFu
        }
        return (h % SHARD_COUNT.toUInt()).toInt()
    }

    fun shardDTag(index: Int): String = "$STATE_SHARD_PREFIX$index"

    fun isStateShardDTag(dTag: String): Boolean =
        dTag.startsWith(STATE_SHARD_PREFIX) && dTag != STATE_MANIFEST_D_TAG

    private val random = SecureRandom()

    /** New opaque folder id: `f_` + 10 random hex chars (spec: ≥ 8). */
    fun newFolderId(): String {
        val bytes = ByteArray(5)
        random.nextBytes(bytes)
        return "f_" + bytes.joinToString("") { "%02x".format(it) }
    }

    /** Validity check for a folder id generated elsewhere (e.g. the web client). */
    fun isReservedFolderId(id: String): Boolean = id.lowercase() in RESERVED_FOLDER_IDS
}
