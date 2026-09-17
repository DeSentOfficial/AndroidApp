package xyz.desent.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-level guarantees for the security slice of the kind-30079 payload
 * (refs/FromServer/USER_SETTINGS_PROTOCOL.md): partial publishes, closed
 * schema, and the mode wire mapping.
 */
class SecurityModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun payload_encodesSecurityFieldOnly() {
        val encoded = json.encodeToString(
            UserSettingsPayload.serializer(),
            UserSettingsPayload(securityAlerts = "new_device")
        )
        assertEquals("""{"security_alerts":"new_device"}""", encoded)
    }

    @Test
    fun payload_nullFieldEncodesToEmptyObject() {
        // A config-only publish never happens from this app, but the payload
        // class must still encode `{}` (valid: reverts nothing) rather than
        // emitting an explicit null the closed schema would reject.
        val encoded = json.encodeToString(
            UserSettingsPayload.serializer(),
            UserSettingsPayload()
        )
        assertEquals("{}", encoded)
    }

    @Test
    fun payload_decodesAlongsideAutoPurgeFields() {
        // Inbound events written by other clients carry auto-purge fields;
        // the security parser must ignore them, not choke.
        val payload = json.decodeFromString(
            UserSettingsPayload.serializer(),
            """{"auto_purge_enabled":false,"auto_purge_days":30,"security_alerts":"always"}"""
        )
        assertEquals("always", payload.securityAlerts)
    }

    @Test
    fun alertMode_roundTripsWireValues() {
        assertEquals(SecurityAlertMode.OFF, SecurityAlertMode.fromWire("off"))
        assertEquals(SecurityAlertMode.NEW_DEVICE, SecurityAlertMode.fromWire("new_device"))
        assertEquals(SecurityAlertMode.ALWAYS, SecurityAlertMode.fromWire("always"))
    }

    @Test
    fun alertMode_unknownOrNullFallsBackToServerDefault() {
        // Absent field in a stored event = the DB default (new_device).
        assertEquals(SecurityAlertMode.NEW_DEVICE, SecurityAlertMode.fromWire(null))
        assertEquals(SecurityAlertMode.NEW_DEVICE, SecurityAlertMode.fromWire("bogus"))
        assertEquals(SecurityAlertMode.DEFAULT, SecurityAlertMode.NEW_DEVICE)
    }

    @Test
    fun payload_encodesDmFanoutFieldOnly() {
        // ANDROID_DM_FANOUT.md §2: the opt-in is its own partial save —
        // `{"dm_fanout":true}` alone, absent fields unchanged.
        val encoded = json.encodeToString(
            UserSettingsPayload.serializer(),
            UserSettingsPayload(dmFanout = true)
        )
        assertEquals("""{"dm_fanout":true}""", encoded)
    }

    @Test
    fun payload_decodesDmFanoutAlongsideOtherFields() {
        val payload = json.decodeFromString(
            UserSettingsPayload.serializer(),
            """{"auto_purge_days":30,"security_alerts":"off","dm_fanout":true,
                "wot_enabled":false,"pgp_auto_encrypt":true}"""
        )
        assertEquals(true, payload.dmFanout)
        assertEquals("off", payload.securityAlerts)
        assertEquals(true, payload.pgpAutoEncrypt)
    }

    @Test
    fun payload_dmFanoutAbsentDecodesNull() {
        val payload = json.decodeFromString(
            UserSettingsPayload.serializer(),
            """{"security_alerts":"always"}"""
        )
        assertNull(payload.dmFanout)
    }
}
