package xyz.desent.domain.util

/**
 * Minimal geohash codec — the interleave-and-bisect base32 scheme used by
 * NIP-52-style calendar locations (see refs/CALENDAR_PROTOCOL.md §Decrypted
 * payload shapes, `geohash` field). Pure math, no platform APIs, no
 * dependencies: safe for JVM unit tests and every call site.
 *
 * Longitude occupies the even bits, latitude the odd ones (the first bit of
 * the stream is longitude) — the de-facto standard every geohash
 * implementation (geohash.org, PostGIS, nostr clients) agrees on.
 */
object GeohashUtils {

    private const val ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Default precision: 7 chars ≈ 153 m × 153 m — venue-level resolution,
     * matching the protocol's example value ("gbyu3k0").
     */
    const val DEFAULT_PRECISION = 7

    /** A WGS-84 coordinate pair. */
    data class GeoPoint(val latitude: Double, val longitude: Double)

    /** Encodes [latitude]/[longitude] to a geohash of [precision] characters. */
    fun encode(latitude: Double, longitude: Double, precision: Int = DEFAULT_PRECISION): String {
        require(precision in 1..12) { "precision must be in 1..12" }
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        var isEvenBit = true
        var bit = 0
        var ch = 0
        val sb = StringBuilder()
        while (sb.length < precision) {
            if (isEvenBit) {
                val mid = (lonLo + lonHi) / 2
                if (longitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonLo = mid
                } else {
                    lonHi = mid
                }
            } else {
                val mid = (latLo + latHi) / 2
                if (latitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latLo = mid
                } else {
                    latHi = mid
                }
            }
            isEvenBit = !isEvenBit
            if (bit < 4) {
                bit++
            } else {
                sb.append(ALPHABET[ch])
                bit = 0
                ch = 0
            }
        }
        return sb.toString()
    }

    /**
     * Decodes a geohash to the center of its bounding cell; null for blank or
     * malformed input (unknown characters). Accepts upper case.
     */
    fun decode(geohash: String): GeoPoint? {
        if (geohash.isBlank()) return null
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        var isEvenBit = true
        for (c in geohash.lowercase()) {
            val ch = ALPHABET.indexOf(c)
            if (ch < 0) return null
            for (bit in 4 downTo 0) {
                val set = (ch shr bit) and 1 == 1
                if (isEvenBit) {
                    val mid = (lonLo + lonHi) / 2
                    if (set) lonLo = mid else lonHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (set) latLo = mid else latHi = mid
                }
                isEvenBit = !isEvenBit
            }
        }
        return GeoPoint(latitude = (latLo + latHi) / 2, longitude = (lonLo + lonHi) / 2)
    }

    private val COORDINATES = Regex(
        """^\s*(?:geo:)?\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** `geo:0,0?q=lat,lon` — maps apps share this form; only the `q` pair is real. */
    private val GEO_QUERY = Regex(
        """^\s*geo:\s*-?\d{1,3}(?:\.\d+)?\s*,\s*-?\d{1,3}(?:\.\d+)?\?q=(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Recognizes pasted `"lat, lon"` pairs (also `geo:lat,lon` and
     * `geo:0,0?q=lat,lon` URIs as shared by maps apps) so coordinates can be
     * resolved fully offline — no geocoder, no network. Null when [text] is
     * not a well-formed, in-range pair.
     */
    fun parseCoordinates(text: String): GeoPoint? {
        val t = text.trim()
        GEO_QUERY.find(t)?.let { m ->
            return pair(m.groupValues[1], m.groupValues[2])
        }
        val m = COORDINATES.find(t) ?: return null
        return pair(m.groupValues[1], m.groupValues[2])
    }

    private fun pair(latText: String, lonText: String): GeoPoint? {
        val lat = latText.toDoubleOrNull() ?: return null
        val lon = lonText.toDoubleOrNull() ?: return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return GeoPoint(lat, lon)
    }
}
