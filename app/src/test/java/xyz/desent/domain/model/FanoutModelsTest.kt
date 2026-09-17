package xyz.desent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DM-relay fan-out domain rules
 * (refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md): NIP-65 marker wire
 * mapping, the advisory NIP-support assessment, health pills, and the
 * premium-rejection detector.
 */
class FanoutModelsTest {

    // ---------------- RelayListMarker wire mapping ----------------

    @Test
    fun marker_fromWireMapsNullBlankWriteRead() {
        assertEquals(RelayListMarker.READ_WRITE, RelayListMarker.fromWire(null))
        assertEquals(RelayListMarker.READ_WRITE, RelayListMarker.fromWire(""))
        assertEquals(RelayListMarker.WRITE, RelayListMarker.fromWire("write"))
        assertEquals(RelayListMarker.READ, RelayListMarker.fromWire("read"))
        // Unknown markers stay permissive — server rules are authoritative.
        assertEquals(RelayListMarker.READ_WRITE, RelayListMarker.fromWire("wat"))
    }

    @Test
    fun marker_wireValuesRoundTrip() {
        assertNull(RelayListMarker.READ_WRITE.wire)
        assertEquals("write", RelayListMarker.WRITE.wire)
        assertEquals("read", RelayListMarker.READ.wire)
    }

    // ---------------- Advisory NIP support ----------------

    @Test
    fun nipSupport_nullNipsIsUnknown() {
        assertEquals(FanoutNipSupportStatus.UNKNOWN, FanoutNipSupport.assess(null))
        assertTrue(FanoutNipSupport.missing(null).isEmpty())
    }

    @Test
    fun nipSupport_checkListIsNip1Then91759() {
        assertEquals(listOf(1, 9, 17, 59), FanoutNipSupport.CHECK_LIST)
    }

    @Test
    fun nipSupport_supportedRequiresTheWholeCheckList() {
        // NIP-09 (deletion) is on the list since delete propagation (§6.4).
        assertEquals(
            FanoutNipSupportStatus.SUPPORTED,
            FanoutNipSupport.assess(setOf(1, 2, 9, 11, 17, 59))
        )
    }

    @Test
    fun nipSupport_missingNip9IsPossiblyUnsupported() {
        // Deletion support matters now: 1/17/59 without 9 highlights.
        val nips = setOf(1, 17, 59)
        assertEquals(FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED, FanoutNipSupport.assess(nips))
        assertEquals(setOf(9), FanoutNipSupport.missing(nips))
    }

    @Test
    fun nipSupport_nip1OnlyIsPossiblyUnsupported() {
        // A plain NIP-01 relay stores 1059s fine in practice but doesn't
        // advertise DM semantics — advisory highlight, never a block.
        assertEquals(
            FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED,
            FanoutNipSupport.assess(setOf(1, 2, 11))
        )
        assertEquals(setOf(9, 17, 59), FanoutNipSupport.missing(setOf(1, 2, 11)))
    }

    @Test
    fun nipSupport_missingNip1IsPossiblyUnsupported() {
        assertEquals(
            FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED,
            FanoutNipSupport.assess(setOf(9, 17, 59))
        )
        assertEquals(setOf(1), FanoutNipSupport.missing(setOf(9, 17, 59)))
    }

    @Test
    fun relayInfo_computedProps() {
        val full = FanoutRelayInfo(
            url = "wss://relay.example.com",
            name = "Example Relay",
            iconUrl = "https://relay.example.com/icon.png",
            rttOpenMs = 424,
            rttReadMs = 143,
            uptime7d = 0.98,
            supportedNips = setOf(1, 9, 17, 59)
        )
        assertEquals(FanoutNipSupportStatus.SUPPORTED, full.nipSupport)
        assertTrue(full.missingNips.isEmpty())

        val partial = full.copy(supportedNips = setOf(1, 9))
        assertEquals(FanoutNipSupportStatus.POSSIBLY_UNSUPPORTED, partial.nipSupport)
        assertEquals(setOf(17, 59), partial.missingNips)

        val unknown = full.copy(supportedNips = null)
        assertEquals(FanoutNipSupportStatus.UNKNOWN, unknown.nipSupport)
    }

    // ---------------- Health pills ----------------

    @Test
    fun healthPill_greenAmberRed() {
        assertEquals(
            FanoutRelayHealthPill.GREEN,
            FanoutRelayHealth(url = "wss://r", pending = 0, done = 12, dead = 0).pill
        )
        assertEquals(
            FanoutRelayHealthPill.AMBER,
            FanoutRelayHealth(url = "wss://r", pending = 2, done = 0, dead = 0).pill
        )
        // Dead-lettered wins even while other work is queued.
        assertEquals(
            FanoutRelayHealthPill.RED,
            FanoutRelayHealth(url = "wss://r", pending = 3, done = 5, dead = 1).pill
        )
    }

    // ---------------- Premium rejection ----------------

    @Test
    fun premiumRequired_detectedFromVerdictMessage() {
        val exception = PremiumRequiredException.fromVerdictMessage(
            "premium required: DM-relay fan-out requires an active paid plan or the fan-out add-on " +
                "(see https://desent.xyz/tiers)"
        )
        assertNotNull(exception)
        assertTrue(exception!!.message!!.contains("premium required"))
    }

    @Test
    fun premiumRequired_ignoresOtherRejections() {
        assertNull(PremiumRequiredException.fromVerdictMessage("invalid: bad event"))
        assertNull(PremiumRequiredException.fromVerdictMessage(null))
        assertNull(PremiumRequiredException.fromVerdictMessage(""))
    }

    // ---------------- Tier-info availability rendering rule (§3) ----------------

    private fun tier(
        tier: String,
        dmFanout: Boolean?,
        purchased: Boolean? = null
    ) = AliasTierInfo(
        tier = tier,
        cap = null,
        used = 0,
        emailDomain = "desent.xyz",
        dmFanout = dmFanout,
        dmFanoutPurchased = purchased
    )

    @Test
    fun availability_enabledOnlyWhenFlagTrue() {
        // Null (server didn't report) fails closed like key_rotation.
        assertFalse(tier("free", null).dmFanout == true)
        assertTrue(tier("paid", true).dmFanout == true)
    }

    @Test
    fun availability_upsellUnlessEntitled() {
        val freeNoAddon = tier("free", false, purchased = false)
        assertFalse(freeNoAddon.dmFanoutPurchased == true || freeNoAddon.isPaid)

        val freeWithAddon = tier("free", false, purchased = true)
        assertTrue(freeWithAddon.dmFanoutPurchased == true)

        val paid = tier("paid", false)
        assertTrue(paid.isPaid)

        val lifetime = tier("lifetime", false)
        assertTrue(lifetime.isPaid && lifetime.isLifetime)
    }
}
