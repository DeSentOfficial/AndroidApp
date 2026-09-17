package xyz.desent.data.security

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import nostr.event.impl.GenericEvent
import nostr.event.tag.GenericTag
import nostr.id.Identity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.preferences.SecurityConfigStore
import xyz.desent.data.nostr.NostrKinds
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.model.SecurityConfig

class SecurityConfigHandlerTest {

    private val identity = Identity.create(
        "d5ca9d8c6a7e8f2b1c3d4e5f60718293a4b5c6d7e8f9012233445566778899aa"
    )
    private val ownerNpub = Bech32Utils.hexToNpub(identity.publicKey.toHexString())

    private lateinit var store: SecurityConfigStore
    private lateinit var secureKeyManager: SecureKeyManager
    private lateinit var handler: SecurityConfigHandler

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        secureKeyManager = mockk()
        coEvery { secureKeyManager.getIdentityFromStoredNSEC() } returns Result.success(identity)
        coEvery { store.getCached(ownerNpub) } returns null
        handler = SecurityConfigHandler(secureKeyManager, store)
    }

    @Test
    fun onInbound_parsesSecurityAlertsField() = runBlocking {
        handler.onInboundUserSettingsEvent(
            buildSettingsEvent("""{"security_alerts":"always"}""", createdAt = 1000L)
        )

        val configSlot = slot<SecurityConfig>()
        coVerify { store.save(ownerNpub, capture(configSlot), 1000L) }
        assertEquals(SecurityAlertMode.ALWAYS, configSlot.captured.alertMode)
    }

    @Test
    fun onInbound_tombstoneRevertsToDefault() = runBlocking {
        handler.onInboundUserSettingsEvent(buildSettingsEvent("", createdAt = 1000L))

        val configSlot = slot<SecurityConfig>()
        coVerify { store.save(ownerNpub, capture(configSlot), 1000L) }
        assertEquals(SecurityAlertMode.DEFAULT, configSlot.captured.alertMode)
    }

    @Test
    fun onInbound_absentSecurityFieldIsNoOp() = runBlocking {
        // A web-client publish that only touched auto-purge must not rewrite
        // the local security slice (partial-update semantics, client side).
        handler.onInboundUserSettingsEvent(
            buildSettingsEvent("""{"auto_purge_days":30}""", createdAt = 1000L)
        )

        coVerify(exactly = 0) { store.save(any(), any(), any()) }
    }

    @Test
    fun onInbound_skipsStaleEvent() = runBlocking {
        coEvery { store.getCached(ownerNpub) } returns SecurityConfigStore.Cached(
            SecurityConfig(SecurityAlertMode.ALWAYS), eventCreatedAtSeconds = 2000L
        )

        handler.onInboundUserSettingsEvent(
            buildSettingsEvent("""{"security_alerts":"off"}""", createdAt = 1500L)
        )

        coVerify(exactly = 0) { store.save(any(), any(), any()) }
    }

    @Test
    fun onInbound_skipsForeignAuthor() = runBlocking {
        val stranger = Identity.create(
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        )
        val event = GenericEvent.builder()
            .pubKey(stranger.publicKey)
            .kind(NostrKinds.USER_SETTINGS)
            .createdAt(1000L)
            .content("""{"security_alerts":"off"}""")
            .tags(listOf<nostr.event.BaseTag>(GenericTag("d", listOf("desent_user_settings"))))
            .build()

        handler.onInboundUserSettingsEvent(event)

        coVerify(exactly = 0) { store.save(any(), any(), any()) }
    }

    private fun buildSettingsEvent(content: String, createdAt: Long): GenericEvent {
        val tags = listOf<nostr.event.BaseTag>(
            GenericTag("d", listOf("desent_user_settings"))
        )
        return GenericEvent.builder()
            .pubKey(identity.publicKey)
            .kind(NostrKinds.USER_SETTINGS)
            .createdAt(createdAt)
            .content(content)
            .tags(tags)
            .build()
    }
}
