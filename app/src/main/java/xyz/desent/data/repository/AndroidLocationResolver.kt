package xyz.desent.data.repository

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import xyz.desent.domain.repository.LocationResolver
import xyz.desent.domain.repository.ResolvedLocation
import xyz.desent.domain.util.GeohashUtils.GeoPoint
import java.io.IOException
import kotlin.coroutines.resume

/**
 * [LocationResolver] backed by the platform `android.location.Geocoder`.
 *
 * On Android 13+ the lookup uses the listener-based API, which resolves
 * on-device on modern hardware; on older versions it falls back to the
 * (blocking) network-backed call moved to [Dispatchers.IO]. No permissions
 * are needed — this is text geocoding, not device location.
 */
class AndroidLocationResolver(
    private val context: Context
) : LocationResolver {

    override suspend fun resolve(query: String): Result<List<ResolvedLocation>> {
        if (!Geocoder.isPresent()) {
            return Result.failure(IllegalStateException("No geocoder available on this device"))
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolveOnTiramisu(query)
        } else {
            runCatching {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    Geocoder(context).getFromLocationName(query, MAX_RESULTS)
                }.orEmpty().mapNotNull { it.toResolvedLocation() }
            }
        }
    }

    /** API-33+ listener path — only invoked behind the [resolve] version guard. */
    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.TIRAMISU)
    private suspend fun resolveOnTiramisu(query: String): Result<List<ResolvedLocation>> =
        suspendCancellableCoroutine { cont ->
            val geocoder = Geocoder(context)
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    cont.resume(Result.success(addresses.mapNotNull { it.toResolvedLocation() }))
                }

                override fun onError(errorMessage: String?) {
                    cont.resume(
                        Result.failure(IOException(errorMessage ?: "Location lookup failed"))
                    )
                }
            }
            geocoder.getFromLocationName(query, MAX_RESULTS, listener)
        }

    private fun Address.toResolvedLocation(): ResolvedLocation? {
        if (!hasLatitude() || !hasLongitude()) return null
        val fallback = listOfNotNull(featureName, locality, adminArea, countryName)
            .joinToString(", ")
        val name = getAddressLine(0)?.ifBlank { null } ?: fallback.ifBlank { null } ?: return null
        return ResolvedLocation(name, GeoPoint(latitude, longitude))
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}
