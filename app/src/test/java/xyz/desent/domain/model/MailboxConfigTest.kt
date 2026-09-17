package xyz.desent.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kind-35050 Mailbox Configuration payloads (refs/FromServer/NIP-EMAIL.md §
 * Kind 35050): relay-readable policy tag vs. NIP-44 self-encrypted private
 * rules that ride in the event `content`.
 */
class MailboxConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun privateRules_roundTrip() {
        val config = MailboxConfig(
            autoPurgeDays = 30,
            blockedSenders = listOf("spam@example.com"),
            forwardTargets = listOf("me@elsewhere.io"),
            preferredAlias = "hi@desent.xyz"
        )
        val rules = MailboxConfig.PrivateRules(config)
        val encoded = json.encodeToString(MailboxConfig.PrivateRules.serializer(), rules)
        val decoded = json.decodeFromString(MailboxConfig.PrivateRules.serializer(), encoded)

        assertEquals(rules, decoded)
        // The encrypted payload must never carry the policy tag — the relay
        // reads that from the plaintext tag, not from the content.
        assertFalse(encoded.contains("autoPurgeDays"))

        val restored = MailboxConfig.fromPrivateRules(30, decoded)
        assertEquals(config, restored)
    }

    @Test
    fun defaultConfig_serializesToEmptyRules() {
        val encoded = json.encodeToString(
            MailboxConfig.PrivateRules.serializer(),
            MailboxConfig.PrivateRules(MailboxConfig())
        )
        val decoded = json.decodeFromString(MailboxConfig.PrivateRules.serializer(), encoded)
        assertEquals(MailboxConfig.PrivateRules(), decoded)
        assertNull(decoded.preferredAlias)
    }
}
