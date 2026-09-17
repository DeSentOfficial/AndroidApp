package xyz.desent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [VanityConfig.priceOf] against refs/FromServer/ANDROID_VANITY_PRICING.md §1:
 * `len < vanity_free_length ? ladder[String(len)] : 0`, a 0 entry = free.
 */
class VanityConfigTest {

    private val ladder = mapOf(
        1 to 250_000L, 2 to 100_000L, 3 to 50_000L, 4 to 25_000L,
        5 to 10_000L, 6 to 5_000L, 7 to 2_000L
    )
    private val config = VanityConfig(freeLength = 8, ladder = ladder)

    @Test
    fun `free at and above freeLength`() {
        assertNull(config.priceOf("abcdefgh"))   // exactly 8
        assertNull(config.priceOf("abcdefghijkl")) // > 8
    }

    @Test
    fun `priced below freeLength`() {
        assertEquals(50_000L, config.priceOf("abc"))
        assertEquals(250_000L, config.priceOf("a"))
        assertEquals(2_000L, config.priceOf("abcdefg")) // exactly 7
    }

    @Test
    fun `zero entry means free`() {
        val cfg = VanityConfig(freeLength = 8, ladder = mapOf(3 to 0L))
        assertNull(cfg.priceOf("abc"))
    }

    @Test
    fun `missing entry means free`() {
        val cfg = VanityConfig(freeLength = 8, ladder = mapOf(1 to 100L))
        assertNull(cfg.priceOf("abc"))
    }

    @Test
    fun `empty ladder hides pricing entirely`() {
        val cfg = VanityConfig(freeLength = 8, ladder = emptyMap())
        assertNull(cfg.priceOf("a"))
    }

    @Test
    fun `status parses wire values case-insensitively`() {
        assertEquals(VanityRequestStatus.PENDING, VanityRequestStatus.fromWire("pending"))
        assertEquals(VanityRequestStatus.APPROVED, VanityRequestStatus.fromWire("Approved"))
        assertEquals(VanityRequestStatus.DENIED, VanityRequestStatus.fromWire("DENIED"))
        assertEquals(VanityRequestStatus.CLAIMED, VanityRequestStatus.fromWire("claimed"))
        assertEquals(VanityRequestStatus.PENDING, VanityRequestStatus.fromWire(null))
        assertEquals(VanityRequestStatus.PENDING, VanityRequestStatus.fromWire("bogus"))
    }
}
