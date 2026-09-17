package xyz.desent.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One private encrypted note synced via NIP-78 kind 30078
 * (`d = "desent:note:<uuid>"`). The body is free-form markdown; attachments are
 * client-side AES-256-GCM-encrypted blobs whose metadata (and AES keys) live
 * inside this NIP-44-encrypted payload.
 *
 * See refs/PRIVATE_STORAGE_PROTOCOL.md §"Note".
 */
@Serializable
data class PrivateNote(
    /** The uuid portion of the `d` tag (`desent:note:<id>`). Stable across edits. */
    val id: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    /** Slash-separated folder path (e.g. `Work/Projects/Q3`); empty = root. */
    val folder: String = "",
    @Serializable(with = LegacyAttachmentsSerializer::class)
    val attachments: List<AttachmentMeta> = emptyList(),
    /** The owner's npub (multi-account scoping). */
    val ownerNpub: String,
    /** The full `d` tag value (`desent:note:<id>`). */
    val dTag: String
)

/** Wire shape inside the 30078 ciphertext for a note. */
@Serializable
data class NotePayload(
    val title: String,
    val body: String,
    val updated_at: Long,
    val folder: String = "",
    @Serializable(with = LegacyAttachmentsSerializer::class)
    val attachments: List<AttachmentMeta> = emptyList()
)

/**
 * Per-attachment metadata stored inside the encrypted note payload. The AES
 * key + nonce never leave the client in plaintext — the relay only ever stores
 * the opaque ciphertext blob referenced by [sha256].
 */
@Serializable
data class AttachmentMeta(
    /** Hash of the stored ciphertext bytes (server-computed over what we uploaded). */
    val sha256: String,
    @SerialName("key_hex") val keyHex: String,
    @SerialName("nonce_hex") val nonceHex: String,
    @SerialName("mime_type") val mimeType: String,
    val filename: String,
    /** Plaintext byte count (for display). */
    val size: Long
)

/**
 * Decodes a note's `attachments` array whether it is the current rich-object
 * form (`[{sha256,key_hex,…}]`) or the legacy bare-string form (`["<sha>"]`)
 * emitted by older clients. Legacy string entries decode to an [AttachmentMeta]
 * carrying only the sha256 (the rest empty) so they survive in the cache and
 * don't crash the reader; they are not downloadable via the new client-side
 * flow. Encode always emits the new rich-object form.
 */
object LegacyAttachmentsSerializer : KSerializer<List<AttachmentMeta>> {
    private val delegate = AttachmentMeta.serializer()
    private val listSerializer = ListSerializer(delegate)

    override val descriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: List<AttachmentMeta>) =
        listSerializer.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<AttachmentMeta> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("LegacyAttachmentsSerializer can only read JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> element.map { item ->
                when (item) {
                    // Legacy "<sha>" string → metadata stub.
                    is JsonPrimitive -> AttachmentMeta(
                        sha256 = item.content,
                        keyHex = "",
                        nonceHex = "",
                        mimeType = "",
                        filename = item.content.take(12),
                        size = 0L
                    )
                    is JsonObject -> jsonDecoder.json.decodeFromJsonElement(delegate, item)
                    else -> throw SerializationException("Unexpected attachment element: $item")
                }
            }
            else -> emptyList()
        }
    }
}
