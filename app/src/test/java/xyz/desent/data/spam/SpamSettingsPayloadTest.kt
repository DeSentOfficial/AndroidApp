package xyz.desent.data.spam

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.domain.model.SpamSettingsPayload

/**
 * Wire-compatibility for the `desent:spam-settings` NIP-78 payload. Builds
 * running older app versions publish JSON without the remote-image fields;
 * those payloads must decode with safe defaults, and new fields must
 * round-trip for cross-device sync.
 */
class SpamSettingsPayloadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `legacy payload without image fields decodes with defaults`() {
        val legacy = """
            {"enabled":true,"threshold":5.0,"heuristic_weight":0.6,"bayesian_weight":0.4,
             "layer_heuristics":true,"layer_blocklist":true,"layer_bayesian":true,
             "updated_at":1700000000}
        """.trimIndent()
        val payload = json.decodeFromString(SpamSettingsPayload.serializer(), legacy)
        assertTrue(payload.blockRemoteImages)
        assertTrue(payload.imageAllowedSenders.isEmpty())
        assertTrue(payload.imageAllowedDomains.isEmpty())
        assertTrue(payload.imagesAllowedForContacts)
    }

    @Test
    fun `image fields round-trip`() {
        val payload = SpamSettingsPayload(
            enabled = true,
            threshold = 6.0,
            heuristicWeight = 0.6,
            bayesianWeight = 0.4,
            layerHeuristicsEnabled = true,
            layerBlocklistEnabled = true,
            layerBayesianEnabled = false,
            blockRemoteImages = false,
            imageAllowedSenders = listOf("news@shop.example"),
            imageAllowedDomains = listOf("shop.example"),
            imagesAllowedForContacts = false,
            updatedAt = 1700000001L
        )
        val encoded = json.encodeToString(SpamSettingsPayload.serializer(), payload)
        val decoded = json.decodeFromString(SpamSettingsPayload.serializer(), encoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun `new payload uses snake_case wire names`() {
        val payload = SpamSettingsPayload(
            enabled = true, threshold = 5.0, heuristicWeight = 0.6, bayesianWeight = 0.4,
            layerHeuristicsEnabled = true, layerBlocklistEnabled = true, layerBayesianEnabled = true,
            blockRemoteImages = false,
            imageAllowedSenders = listOf("a@b.example"),
            imageAllowedDomains = listOf("b.example"),
            imagesAllowedForContacts = false,
            updatedAt = 1L
        )
        // Non-default values so they are encoded even with encodeDefaults=false
        // (mirrors how a config that opts out of blocking appears on the wire).
        val encoded = json.encodeToString(SpamSettingsPayload.serializer(), payload)
        assertTrue(encoded.contains("\"block_remote_images\""))
        assertTrue(encoded.contains("\"image_allowed_senders\""))
        assertTrue(encoded.contains("\"image_allowed_domains\""))
        assertTrue(encoded.contains("\"images_allowed_for_contacts\""))
    }

    @Test
    fun `legacy payload without personal rule fields decodes with defaults`() {
        val legacy = """
            {"enabled":true,"threshold":5.0,"heuristic_weight":0.6,"bayesian_weight":0.4,
             "layer_heuristics":true,"layer_blocklist":true,"layer_bayesian":true,
             "block_remote_images":true,"images_allowed_for_contacts":true,
             "updated_at":1700000000}
        """.trimIndent()
        val payload = json.decodeFromString(SpamSettingsPayload.serializer(), legacy)
        assertTrue(payload.blockedDomains.isEmpty())
        assertTrue(payload.allowedDomains.isEmpty())
        assertTrue(payload.blockedSenders.isEmpty())
        assertTrue(payload.allowedSenders.isEmpty())
    }

    @Test
    fun `personal rule fields round-trip with snake_case wire names`() {
        val payload = SpamSettingsPayload(
            enabled = true, threshold = 5.0, heuristicWeight = 0.6, bayesianWeight = 0.4,
            layerHeuristicsEnabled = true, layerBlocklistEnabled = true, layerBayesianEnabled = true,
            blockedDomains = listOf("spam.example"),
            allowedDomains = listOf("friend.example"),
            blockedSenders = listOf("evil@spam.example"),
            allowedSenders = listOf("pal@friend.example"),
            updatedAt = 2L
        )
        val encoded = json.encodeToString(SpamSettingsPayload.serializer(), payload)
        assertTrue(encoded.contains("\"blocked_domains\""))
        assertTrue(encoded.contains("\"allowed_domains\""))
        assertTrue(encoded.contains("\"blocked_senders\""))
        assertTrue(encoded.contains("\"allowed_senders\""))
        val decoded = json.decodeFromString(SpamSettingsPayload.serializer(), encoded)
        assertEquals(payload, decoded)
    }
}
