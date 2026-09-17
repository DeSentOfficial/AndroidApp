package xyz.desent.data.spam

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import xyz.desent.data.RelayConfig
import xyz.desent.domain.model.SpamListManifest
import xyz.desent.domain.repository.RelayRepository

/**
 * Fetches the DeSent spam blocklist (NIP-51 kind 30000) from the trusted
 * publisher over the existing relay connections and parses it into a
 * [SpamListManifest]. See refs/SPAM_LIST_REFERENCE.md §"Client fetch & sync".
 *
 * The relay validates event signatures before forwarding; authenticity here is
 * established by pinning `pubKey` to [RelayConfig.SPAM_LIST_PUBKEY_HEX] — a
 * forged event would require a valid signature from a key whose nsec never
 * leaves the server.
 */
class SpamListSyncer(private val relayRepository: RelayRepository) {

    /**
     * Subscribe on the persistent + public relays, wait briefly for the list,
     * return the parsed manifest or null on miss/parse-error/timeout.
     */
    suspend fun fetch(): SpamListManifest? {
        val subId = "spam_list_${System.currentTimeMillis()}"
        val filter = mapOf(
            "authors" to listOf(RelayConfig.SPAM_LIST_PUBKEY_HEX),
            "kinds" to listOf(RelayConfig.SPAM_LIST_KIND),
            "#d" to listOf(RelayConfig.SPAM_LIST_D_TAG),
            "limit" to 1
        )

        val relays = listOf(RelayConfig.EMAIL_RELAY_URL)
        var accepted = false
        for (url in relays) {
            runCatching { relayRepository.subscribeToEventsOnRelay(listOf(filter), subId, url) }
                .onSuccess { accepted = true }
                .onFailure { Log.w(TAG, "subscribe failed on $url: ${it.message}") }
        }
        if (!accepted) {
            Log.w(TAG, "No relay accepted the spam-list subscription")
            return null
        }

        val event = try {
            withTimeout(FETCH_TIMEOUT_MS) {
                relayRepository.observeEvents().first { event -> matches(event) }
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "Spam-list fetch timed out (relays may not have it yet)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Spam-list fetch error: ${e.message}")
            null
        } finally {
            runCatching { relayRepository.unsubscribeFromEvents(subId) }
        }

        return event?.let { parse(it) }
    }

    private fun matches(event: GenericEvent): Boolean {
        if (event.kind != RelayConfig.SPAM_LIST_KIND) return false
        if (event.pubKey.toHexString() != RelayConfig.SPAM_LIST_PUBKEY_HEX) return false
        val d = event.tags.find { it.getCode() == "d" }?.let { (it as? GenericTag)?.getParams()?.firstOrNull() }
        return d == RelayConfig.SPAM_LIST_D_TAG
    }

    /** Parse + lowercase list entries. Returns null on malformed JSON. */
    fun parse(event: GenericEvent): SpamListManifest? = parseManifest(event.content)

    @Serializable
    private data class ManifestDto(
        val version: Long = 0,
        val updatedAt: Long = 0,
        val ttlHours: Int = 24,
        val blockedDomains: List<String> = emptyList(),
        val allowedDomains: List<String> = emptyList(),
        val blockedSenders: List<String> = emptyList(),
        val spamPatterns: List<String> = emptyList(),
        val trustedBridgeDomains: List<String> = emptyList()
    )

    private val manifestJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Pure JSON-string → [SpamListManifest] parser, exposed for testing. */
    fun parseManifest(content: String): SpamListManifest? {
        val dto = runCatching { manifestJson.decodeFromString<ManifestDto>(content) }.getOrElse {
            Log.w(TAG, "Manifest JSON parse failed: ${it.message}")
            return null
        }
        return SpamListManifest(
            version = dto.version,
            updatedAt = dto.updatedAt,
            ttlHours = dto.ttlHours,
            blockedDomains = dto.blockedDomains.map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            allowedDomains = dto.allowedDomains.map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            blockedSenders = dto.blockedSenders.map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            spamPatterns = dto.spamPatterns.filter { it.isNotEmpty() },
            trustedBridgeDomains = dto.trustedBridgeDomains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        )
    }

    companion object {
        private const val TAG = "SpamListSyncer"
        private const val FETCH_TIMEOUT_MS = 6_000L
    }
}
