package xyz.desent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Precedence chain of [Account.desentAddress]: the cached registered
 * `primaryAddress` is authoritative, then a desent-domain kind-0 nip05, then
 * the custodial username; anything else yields null ("No address" in the UI).
 */
class AccountDesentAddressTest {

    private fun account(
        primaryAddress: String? = null,
        nip05: String? = null,
        custodialUsername: String? = null
    ) = Account(
        npub = "npub1x",
        displayName = null,
        picture = null,
        nip05 = nip05,
        lastActiveAt = 0,
        addedAt = 0,
        custodialUsername = custodialUsername,
        primaryAddress = primaryAddress
    )

    @Test
    fun `cached primary address wins over everything`() {
        assertEquals(
            "ada@desent.xyz",
            account(
                primaryAddress = "ada@desent.xyz",
                nip05 = "ada@external.example",
                custodialUsername = "other"
            ).desentAddress
        )
    }

    @Test
    fun `desent nip05 is used when no cached address exists`() {
        assertEquals("bob@desent.xyz", account(nip05 = "bob@desent.xyz").desentAddress)
    }

    @Test
    fun `external nip05 never becomes the desent address`() {
        assertNull(account(nip05 = "carol@example.com").desentAddress)
    }

    @Test
    fun `custodial username fills in without a cached address`() {
        assertEquals("dave@desent.xyz", account(custodialUsername = "dave").desentAddress)
    }

    @Test
    fun `blank cached address falls through instead of rendering empty`() {
        assertEquals(
            "erin@desent.xyz",
            account(primaryAddress = " ", nip05 = "erin@desent.xyz").desentAddress
        )
    }

    @Test
    fun `nothing derivable yields null`() {
        assertNull(account().desentAddress)
    }
}
