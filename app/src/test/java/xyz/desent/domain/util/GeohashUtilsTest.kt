package xyz.desent.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.desent.domain.util.GeohashUtils.GeoPoint

class GeohashUtilsTest {

    // ------------------------------------------------------------------
    // Encode — published vectors
    // ------------------------------------------------------------------

    @Test
    fun `encodes the canonical wikipedia vector`() {
        // 42.605, -5.603 → "ezs42" (Wikipedia's worked example).
        assertEquals("ezs42", GeohashUtils.encode(42.605, -5.603, 5))
    }

    @Test
    fun `encodes the protocol example precision`() {
        // 7 chars is the DeSent calendar convention (protocol example "gbyu3k0").
        assertEquals(7, GeohashUtils.encode(51.5007, -0.1246).length)
    }

    @Test
    fun `encode rejects out-of-range precision`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GeohashUtils.encode(0.0, 0.0, 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GeohashUtils.encode(0.0, 0.0, 13)
        }
    }

    // ------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------

    @Test
    fun `decodes to the cell center`() {
        // Hand-computed center of "ezs42": lat [42.5830078125, 42.626953125],
        // lon [-5.625, -5.5810546875].
        val point = GeohashUtils.decode("ezs42")
        assertNotNull(point)
        assertEquals(42.60498046875, point!!.latitude, 1e-9)
        assertEquals(-5.60302734375, point.longitude, 1e-9)
    }

    @Test
    fun `decode accepts upper case and rejects garbage`() {
        assertEquals(GeohashUtils.decode("ezs42"), GeohashUtils.decode("EZS42"))
        assertNull(GeohashUtils.decode(""))
        assertNull(GeohashUtils.decode("   "))
        assertNull(GeohashUtils.decode("ezsa2")) // 'a' is not in the base32 alphabet
    }

    @Test
    fun `encode-decode round-trips within the cell`() {
        val vectors = listOf(
            57.64911 to 10.40744,
            -33.865143 to 151.209900,
            51.5007 to -0.1246,
            40.7128 to -74.0060,
            -54.8019 to -68.3030
        )
        for ((lat, lon) in vectors) {
            val decoded = GeohashUtils.decode(GeohashUtils.encode(lat, lon))!!
            // 7 chars ≈ 153 m cells; the cell center is within ~0.001° of the input.
            assertEquals(lat, decoded.latitude, 0.002)
            assertEquals(lon, decoded.longitude, 0.002)
        }
    }

    // ------------------------------------------------------------------
    // parseCoordinates
    // ------------------------------------------------------------------

    @Test
    fun `parses pasted lat lon pairs`() {
        val p = GeohashUtils.parseCoordinates("57.64911, 10.40744")
        assertEquals(GeoPoint(57.64911, 10.40744), p)
        assertEquals(GeoPoint(-33.865143, 151.2099), GeohashUtils.parseCoordinates("-33.865143,151.2099"))
        assertEquals(GeoPoint(1.5, -2.25), GeohashUtils.parseCoordinates(" 1.5 ; -2.25 "))
    }

    @Test
    fun `parses bare geo uris`() {
        assertEquals(GeoPoint(51.5, -0.12), GeohashUtils.parseCoordinates("geo:51.5,-0.12"))
    }

    @Test
    fun `prefers the q pair in shared geo uris`() {
        // Maps apps share a dummy pair plus the real coordinates in `q`.
        assertEquals(GeoPoint(51.6, -0.2), GeohashUtils.parseCoordinates("geo:0,0?q=51.6,-0.2"))
        assertNull(GeohashUtils.parseCoordinates("GEO:51.5,-0.12?q=poi")) // non-coordinate query
    }

    @Test
    fun `rejects non-coordinate and out-of-range text`() {
        assertNull(GeohashUtils.parseCoordinates("120 Market St, San Francisco"))
        assertNull(GeohashUtils.parseCoordinates("57.64911"))
        assertNull(GeohashUtils.parseCoordinates("91.0, 10.0"))    // latitude out of range
        assertNull(GeohashUtils.parseCoordinates("57.0, 181.0"))  // longitude out of range
        assertNull(GeohashUtils.parseCoordinates("57.0, 10.0 extra"))
    }
}
