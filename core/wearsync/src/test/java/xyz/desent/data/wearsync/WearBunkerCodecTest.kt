package xyz.desent.data.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearBunkerCodecTest {

    @Test
    fun requestRoundTripPreservesFields() {
        val request = WearBunkerRequest(
            requestId = "req-42",
            label = "Corptelegraph",
            method = "sign_event",
            eventKind = 1L,
            preview = """{"kind":1,"content":"hello"}""",
            promptedAt = 1_000L,
            expiresAt = 121_000L,
            bunkerEnabled = true,
            syncedAt = 999L
        )
        val decoded = WearBunkerCodec.decodeRequest(WearBunkerCodec.encodeRequest(request))
        assertEquals(request, decoded)
    }

    @Test
    fun clearedRequestRoundTrip() {
        val cleared = WearBunkerRequest(bunkerEnabled = false, syncedAt = 7L)
        val decoded = WearBunkerCodec.decodeRequest(WearBunkerCodec.encodeRequest(cleared))
        assertEquals(cleared, decoded)
        assertNull(decoded?.requestId)
        assertFalse(decoded!!.bunkerEnabled)
    }

    @Test
    fun requestDefaultsApplyWhenFieldsMissing() {
        val decoded = WearBunkerCodec.decodeRequest(
            """{"requestId":"abc","method":"ping"}""".encodeToByteArray()
        )
        assertEquals("abc", decoded?.requestId)
        assertEquals("ping", decoded?.method)
        assertEquals("", decoded?.label)
        assertEquals(0L, decoded?.promptedAt)
        assertEquals(0L, decoded?.expiresAt)
        assertTrue(decoded!!.bunkerEnabled)
    }

    @Test
    fun requestUnknownFieldsIgnored() {
        val json = """{"requestId":"abc","futureField":123}"""
        val decoded = WearBunkerCodec.decodeRequest(json.encodeToByteArray())
        assertEquals("abc", decoded?.requestId)
    }

    @Test
    fun decisionRoundTrip() {
        val decision = WearBunkerDecision(requestId = "req-42", accept = true, alwaysAllow = true)
        val decoded = WearBunkerCodec.decodeDecision(WearBunkerCodec.encodeDecision(decision))
        assertEquals(decision, decoded)
    }

    @Test
    fun decisionAlwaysAllowDefaultsFalse() {
        val decoded = WearBunkerCodec.decodeDecision(
            """{"requestId":"abc","accept":false}""".encodeToByteArray()
        )
        assertEquals("abc", decoded?.requestId)
        assertFalse(decoded!!.accept)
        assertFalse(decoded.alwaysAllow)
    }

    @Test
    fun prefsRoundTrip() {
        val prefs = WearBunkerPrefs(enabled = false)
        val decoded = WearBunkerCodec.decodePrefs(WearBunkerCodec.encodePrefs(prefs))
        assertEquals(prefs, decoded)
    }

    @Test
    fun garbageReturnsNull() {
        val junk = byteArrayOf(0x00, 0x01, 0x02)
        assertNull(WearBunkerCodec.decodeRequest(junk))
        assertNull(WearBunkerCodec.decodeDecision(junk))
        assertNull(WearBunkerCodec.decodePrefs(junk))
    }
}
