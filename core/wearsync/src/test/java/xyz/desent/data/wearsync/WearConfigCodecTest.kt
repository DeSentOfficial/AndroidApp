package xyz.desent.data.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WearConfigCodecTest {

    @Test
    fun roundTripPreservesFields() {
        val config = WearConfig(themeMode = "LIGHT", syncedAt = 42L)
        val decoded = WearConfigCodec.decode(WearConfigCodec.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun defaultsApplyWhenFieldsMissing() {
        val decoded = WearConfigCodec.decode("""{"syncedAt":7}""".encodeToByteArray())
        assertEquals("DARK", decoded?.themeMode)
        assertEquals(7L, decoded?.syncedAt)
    }

    @Test
    fun unknownFieldsIgnored() {
        val json = """{"themeMode":"SYSTEM","syncedAt":1,"futureField":123}"""
        val decoded = WearConfigCodec.decode(json.encodeToByteArray())
        assertEquals(WearThemeMode.SYSTEM, WearThemeMode.fromName(decoded?.themeMode))
        assertEquals(1L, decoded?.syncedAt)
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(WearConfigCodec.decode(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun themeModeFallbackIsDark() {
        assertEquals(WearThemeMode.DARK, WearThemeMode.fromName("bogus"))
        assertEquals(WearThemeMode.DARK, WearThemeMode.fromName(null))
        assertSame(WearThemeMode.DARK, WearThemeMode.fromName("bogus"))
    }
}
