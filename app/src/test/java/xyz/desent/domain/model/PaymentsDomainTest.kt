package xyz.desent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PurchaseHistoryItem] pill semantics + [AliasTierInfo] tier helpers against
 * refs/FROM_email.desent.xyz/ANDROID_PAYMENTS.md §3.5/§6 and
 * ALIAS_API_REFERENCE.md §"Tier rules".
 */
class PaymentsDomainTest {

    private fun invoice(state: InvoiceState, settledAt: String? = null) = Invoice(
        id = 1,
        targetType = PaymentTargetType.TIER_PURCHASE,
        targetId = 1,
        amountSatoshi = 1000L,
        amountUsd = "1.00",
        lnInvoice = "lnbc…",
        state = state,
        expiresAt = null,
        createdAt = null,
        paidAt = null,
        settledAt = settledAt
    )

    private fun item(status: String?, invoice: Invoice?) = PurchaseHistoryItem(
        kind = PaymentTargetType.TIER_PURCHASE,
        targetId = 1,
        status = status,
        amountSatoshi = 1000L,
        amountUsd = "1.00",
        requestedAt = null,
        decidedAt = null,
        decidedBy = null,
        note = null,
        detailJson = null,
        invoice = invoice
    )

    @Test
    fun `pill mirrors the admin ledger semantics`() {
        assertEquals(HistoryPill.CREDITED, item("approved", null).pill)
        assertEquals(HistoryPill.CREDITED, item("claimed", null).pill)
        // paid without settled_at = paid · processing
        assertEquals(HistoryPill.PROCESSING, item("pending", invoice(InvoiceState.PAID, settledAt = null)).pill)
        assertEquals(HistoryPill.AWAITING, item("pending", invoice(InvoiceState.UNPAID)).pill)
        assertEquals(HistoryPill.DENIED, item("denied", null).pill)
        assertEquals(HistoryPill.MUTED, item("cancelled", invoice(InvoiceState.EXPIRED)).pill)
        assertEquals(HistoryPill.MUTED, item(null, null).pill) // orphan without invoice state
    }

    @Test
    fun `kind labels cover the three flows plus orphans`() {
        assertEquals("Vanity address", item("approved", null).copy(kind = PaymentTargetType.VANITY_REQUEST).kindLabel)
        assertEquals("Alias slots", item("approved", null).copy(kind = PaymentTargetType.SLOT_PURCHASE).kindLabel)
        assertEquals("Paid plan", item("approved", null).kindLabel)
        assertEquals("Invoice", item(null, null).copy(kind = null).kindLabel)
    }

    @Test
    fun `feature_purchase target wires round-trip and labels per product`() {
        assertEquals(PaymentTargetType.FEATURE_PURCHASE, PaymentTargetType.fromWire("feature_purchase"))
        assertEquals(
            "Relay mirroring add-on",
            item("approved", null)
                .copy(kind = PaymentTargetType.FEATURE_PURCHASE, featureProduct = FeatureProduct.DM_FANOUT)
                .kindLabel
        )
        assertEquals(
            "Key rotation add-on",
            item("approved", null)
                .copy(kind = PaymentTargetType.FEATURE_PURCHASE, featureProduct = FeatureProduct.KEY_ROTATION)
                .kindLabel
        )
        // Registry product the app doesn't know (detail.product absent/foreign).
        assertEquals(
            "Feature add-on",
            item("approved", null).copy(kind = PaymentTargetType.FEATURE_PURCHASE).kindLabel
        )
        // Unknown wire values still map to null (renders as "Invoice").
        assertNull(PaymentTargetType.fromWire("mystery_purchase"))
    }

    @Test
    fun `feature products round-trip their wire names`() {
        assertEquals(FeatureProduct.DM_FANOUT, FeatureProduct.fromWire("dm_fanout"))
        assertEquals(FeatureProduct.KEY_ROTATION, FeatureProduct.fromWire("key_rotation"))
        assertNull(FeatureProduct.fromWire("telepathy"))
        assertNull(FeatureProduct.fromWire(null))
    }

    @Test
    fun `tier helpers treat lifetime as paid with an unlimited cap`() {
        val lifetime = AliasTierInfo(tier = "lifetime", cap = null, used = 12, emailDomain = "desent.xyz")
        assertTrue(lifetime.isPaid)
        assertTrue(lifetime.isLifetime)
        assertFalse(lifetime.isAtCap)

        val yearly = AliasTierInfo(tier = "paid", cap = 5, used = 5, emailDomain = "desent.xyz", paidUntil = "2027-08-25T00:00:00Z")
        assertTrue(yearly.isPaid)
        assertFalse(yearly.isLifetime)
        assertTrue(yearly.isAtCap)

        val free = AliasTierInfo(tier = "free", cap = 2, used = 1, emailDomain = "desent.xyz")
        assertFalse(free.isPaid)
        assertFalse(free.isAtCap)
    }
}
