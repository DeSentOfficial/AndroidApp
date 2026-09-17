package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import xyz.desent.data.registration.RegistrationClient
import xyz.desent.data.registration.model.MeResponse
import xyz.desent.data.registration.model.ReferralCodeResponse
import xyz.desent.data.registration.model.ReferralsResponse
import xyz.desent.data.registration.model.RegisterModeResponse
import xyz.desent.data.registration.model.RegisterRequest
import xyz.desent.domain.model.RegistrationMode

class RegistrationRepositoryReferralTest {

    private lateinit var client: RegistrationClient
    private lateinit var repository: RegistrationRepositoryImpl

    @Before
    fun setUp() {
        client = mockk()
        repository = RegistrationRepositoryImpl(client)
    }

    @Test
    fun getRegistrationMode_mapsWireValues() = runBlocking {
        for ((wire, expected) in mapOf(
            "open" to RegistrationMode.OPEN,
            "referral" to RegistrationMode.REFERRAL,
            "disabled" to RegistrationMode.DISABLED
        )) {
            coEvery { client.getRegisterMode() } returns
                Result.success(RegisterModeResponse(wire))
            assertEquals(expected, repository.getRegistrationMode().getOrNull())
        }
    }

    @Test
    fun getRegistrationMode_unknownFallsBackToOpen() = runBlocking {
        coEvery { client.getRegisterMode() } returns
            Result.success(RegisterModeResponse(null))
        assertEquals(RegistrationMode.OPEN, repository.getRegistrationMode().getOrNull())
    }

    @Test
    fun register_normalizesReferralCodeToUppercase() = runBlocking {
        val requestSlot = slot<RegisterRequest>()
        coEvery { client.register(capture(requestSlot)) } returns
            Result.success(MeResponse(local = "alice"))

        repository.register(
            local = "alice",
            displayName = "Alice",
            picture = null,
            about = null,
            referralCode = " ds-armxh2-mh7yfc "
        ).getOrThrow()

        assertEquals("DS-ARMXH2-MH7YFC", requestSlot.captured.referralCode)
    }

    @Test
    fun register_blankReferralCodeIsOmitted() = runBlocking {
        val requestSlot = slot<RegisterRequest>()
        coEvery { client.register(capture(requestSlot)) } returns
            Result.success(MeResponse(local = "alice"))

        repository.register("alice", "Alice", null, null, referralCode = "   ").getOrThrow()

        assertNull(requestSlot.captured.referralCode)
    }

    @Test
    fun getInviteCodes_mapsDtoAndParsesIsoDates() = runBlocking {
        val dto = ReferralsResponse(
            codes = listOf(
                ReferralCodeResponse(
                    code = "DS-ARMXH2-MH7YFC",
                    used = false,
                    createdAt = "2026-08-03T20:28:31.075+00:00"
                ),
                ReferralCodeResponse(
                    code = "DS-K2M9X4-P8QW3R",
                    used = true,
                    usedBy = "879f1560458ae059b84c0d09fc3170239bd6d0ed18d63283f38050ae4f1b8a63",
                    createdAt = "2026-08-03T20:28:31.075+00:00",
                    usedAt = "1970-01-02T00:00:01.000+00:00" // 86401000L exactly
                ),
                ReferralCodeResponse(
                    code = "DS-GARBAGE",
                    used = false,
                    createdAt = "not-a-date"
                )
            ),
            cap = 3
        )
        coEvery { client.getReferrals() } returns Result.success(dto)

        val invites = repository.getInviteCodes().getOrThrow()

        assertEquals(3, invites.codes.size)
        assertEquals(3, invites.cap)
        assertEquals(2, invites.availableCount)
        // Real-world ISO string parses to a sane 2026 epoch (post-2024 floor).
        assertNotNull(invites.codes[0].createdAt)
        assertEquals(true, invites.codes[0].createdAt!! > 1_700_000_000_000L)
        // Exact arithmetic on a trivially verifiable date.
        assertEquals(86401000L, invites.codes[1].usedAt)
        // Unparseable date maps to null rather than throwing.
        assertNull(invites.codes[2].createdAt)
        assertEquals(
            "879f1560458ae059b84c0d09fc3170239bd6d0ed18d63283f38050ae4f1b8a63",
            invites.codes[1].usedBy
        )
    }

    @Test
    fun meResponse_prefersDocumentedFieldNames_overLegacyAliases() {
        val me = MeResponse(
            local = "legacy",
            nip05Username = "alice",
            nip05Domain = "yadha.net",
            picture = "legacy.png",
            pictureUrl = "new.png",
            tier = "legacy-tier",
            userlevel = "user"
        )
        assertEquals("alice", me.resolvedLocal)
        assertEquals("new.png", me.resolvedPicture)
        assertEquals("user", me.resolvedTier)
        assertEquals("alice@yadha.net", me.nip05)
    }

    @Test
    fun meResponse_legacyShape_stillResolves() {
        val me = MeResponse(local = "bob")
        assertEquals("bob", me.resolvedLocal)
        assertEquals("bob@desent.xyz", me.nip05)
    }
}
