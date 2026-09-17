package xyz.desent.data.wearsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Shared phone↔watch bunker (NIP-46 remote-signer) sync contract.
 *
 * The phone owns the bunker: it receives the kind-24133 / gift-wrapped
 * requests, holds the keys, signs, and publishes. The watch only mirrors the
 * single pending prompt ([WearBunkerRequest], pushed as a DataItem) and sends
 * back the user's decision ([WearBunkerDecision], a watch→phone message).
 * No keys, request plaintext beyond the preview, or relay traffic ever reach
 * the watch.
 */
@Serializable
data class WearBunkerRequest(
    /**
     * Id of the pending NIP-46 request, or null when nothing is pending
     * (the cleared state the phone pushes after a decision / auto-deny).
     */
    val requestId: String? = null,
    /** Paired client label (e.g. "Corptelegraph"), or a raw session id fallback. */
    val label: String = "",
    /** NIP-46 method (e.g. sign_event, nip44_encrypt). */
    val method: String = "",
    /** Kind of the unsigned event for sign_event, when known. */
    val eventKind: Long? = null,
    /** Short preview of the unsigned event / params (already truncated by the phone). */
    val preview: String? = null,
    /** When the prompt was pushed (epoch ms) — anchors the watch countdown. */
    val promptedAt: Long = 0L,
    /** When the phone auto-denies (epoch ms); the watch expires at the same instant. */
    val expiresAt: Long = 0L,
    /** Whether the user wants bunker prompts synced to the watch (phone-persisted pref). */
    val bunkerEnabled: Boolean = true,
    /** Uniqueness stamp so identical content still triggers a Data Layer change event. */
    val syncedAt: Long = 0L
)

/** Watch→phone decision for the pending request shown on the watch. */
@Serializable
data class WearBunkerDecision(
    val requestId: String,
    val accept: Boolean,
    /** Persist a per-pairing grant so future calls of this method/kind skip the prompt. */
    val alwaysAllow: Boolean = false
)

/** Watch→phone prefs message body (currently: the bunker sync toggle). */
@Serializable
data class WearBunkerPrefs(
    val enabled: Boolean = true
)

/** Plain tolerant-JSON codecs — the payloads are tiny, no gzip needed. */
object WearBunkerCodec {
    private val json = WearSyncGzip.json

    fun encodeRequest(request: WearBunkerRequest): ByteArray =
        json.encodeToString(request).encodeToByteArray()

    fun decodeRequest(bytes: ByteArray): WearBunkerRequest? = try {
        json.decodeFromString<WearBunkerRequest>(bytes.decodeToString())
    } catch (e: Exception) {
        null
    }

    fun encodeDecision(decision: WearBunkerDecision): ByteArray =
        json.encodeToString(decision).encodeToByteArray()

    fun decodeDecision(bytes: ByteArray): WearBunkerDecision? = try {
        json.decodeFromString<WearBunkerDecision>(bytes.decodeToString())
    } catch (e: Exception) {
        null
    }

    fun encodePrefs(prefs: WearBunkerPrefs): ByteArray =
        json.encodeToString(prefs).encodeToByteArray()

    fun decodePrefs(bytes: ByteArray): WearBunkerPrefs? = try {
        json.decodeFromString<WearBunkerPrefs>(bytes.decodeToString())
    } catch (e: Exception) {
        null
    }
}
