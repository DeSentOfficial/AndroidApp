package xyz.desent.domain.repository

import xyz.desent.domain.util.GeohashUtils.GeoPoint

/** One geocoding candidate for a free-text location query. */
data class ResolvedLocation(
    val displayName: String,
    val point: GeoPoint
)

/**
 * Resolves free-text location queries (addresses, venues, landmarks) to
 * candidate coordinates. Implementations wrap a platform geocoding service;
 * the editor combines this with the offline [xyz.desent.domain.util.GeohashUtils.parseCoordinates]
 * path so coordinate pastes never leave the device.
 */
interface LocationResolver {
    /**
     * Geocodes [query]. Success with an empty list = no matches; failure =
     * geocoder unavailable or lookup error (surface `message` to the user).
     */
    suspend fun resolve(query: String): Result<List<ResolvedLocation>>
}
