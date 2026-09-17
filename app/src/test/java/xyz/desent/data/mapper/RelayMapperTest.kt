package xyz.desent.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.desent.data.local.database.entity.RelayEntity
import xyz.desent.domain.model.ConnectionStatus
import xyz.desent.domain.model.Fee
import xyz.desent.domain.model.Fees
import xyz.desent.domain.model.Limitations
import xyz.desent.domain.model.Nip11Metadata
import xyz.desent.domain.model.Relay

class RelayMapperTest {

    private val mapper = RelayMapper()

    private fun sampleMetadata() = Nip11Metadata(
        name = "example relay",
        description = "a relay for testing",
        pubkey = "pubkey-hex",
        contact = "mailto:admin@example.com",
        supportedNips = listOf(1, 2, 11, 38),
        version = "1.2.3",
        icon = "https://example.com/icon.png",
        software = "git+https://github.com/example/relay",
        relayCountries = listOf("US", "DE"),
        languageTags = listOf("en"),
        postingPolicy = "https://example.com/policy",
        limitations = Limitations(
            maxMessageLength = 65536,
            maxEventTags = 100,
            minPowDifficulty = 0,
            authRequired = false,
            paymentRequired = false
        ),
        fees = Fees(
            admission = listOf(Fee(amount = 1000, unit = "msats")),
            subscription = emptyList(),
            publication = emptyList()
        ),
        payments = null
    )

    @Test
    fun relayWithNip11Metadata_roundTripsThroughEntity() {
        val domain = Relay(
            url = "wss://relay.example.com",
            isActive = true,
            connectionStatus = ConnectionStatus.CONNECTED,
            failureCount = 0,
            lastConnectedAt = 1_700_000_000_000L,
            isWrite = true,
            isPersistent = true,
            nip11Metadata = sampleMetadata(),
            nip11CachedAt = 1_700_000_000_000L
        )

        val restored = mapper.mapToDomain(mapper.mapToEntity(domain))

        assertEquals(domain, restored)
    }

    @Test
    fun relayWithoutMetadata_mapsWithNullMetadata() {
        val domain = Relay(url = "wss://relay.example.com")

        val restored = mapper.mapToDomain(mapper.mapToEntity(domain))

        assertNull(restored.nip11Metadata)
        assertNull(restored.nip11CachedAt)
    }

    @Test
    fun legacyDataClassToStringLimitationsJson_degradesToNullInsteadOfCrashing() {
        // Entities cached by older versions stored Limitations via toString().
        val entity = RelayEntity(
            url = "wss://relay.example.com",
            connectionStatus = "DISCONNECTED",
            lastConnectedAt = null,
            nip11Name = "example relay",
            nip11Description = null,
            nip11Pubkey = null,
            nip11Contact = null,
            nip11SupportedNips = "1,11",
            nip11Version = "1.2.3",
            nip11Icon = null,
            nip11Software = null,
            nip11RelayCountries = null,
            nip11LanguageTags = null,
            nip11PostingPolicy = null,
            nip11LimitationsJson = "Limitations(maxMessageLength=65536)",
            nip11FeesJson = null,
            nip11Payments = null,
            nip11CachedAt = 1L
        )

        val metadata = mapper.mapToNip11Metadata(entity)

        assertNotNull(metadata)
        assertEquals("example relay", metadata!!.name)
        assertNull(metadata.limitations)
    }
}
