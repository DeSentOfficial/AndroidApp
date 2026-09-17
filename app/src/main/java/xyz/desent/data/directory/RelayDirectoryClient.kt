package xyz.desent.data.directory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.desent.data.RelayConfig
import xyz.desent.data.directory.model.DirectoryError
import xyz.desent.data.directory.model.DirectoryErrorResponse
import xyz.desent.data.directory.model.DirectoryRelayDto
import xyz.desent.data.directory.model.DirectoryRelaysResponse
import java.net.URLEncoder

/**
 * OkHttp REST client for the public Nostr relay directory
 * (refs/FROM_directory.desent.xyz/API.md), pulled from
 * [RelayConfig.RELAY_DIRECTORY_BASE_URL]. No auth; per-IP rate limits are
 * enforced nginx-side (5 rps, burst 10) — the picker fetches once per open.
 *
 * Powers the relay-mirroring relay picker: suggested online clearnet
 * free relays, each carrying the NIP-11-harvested `supported_nips` used for
 * the advisory NIP-support highlight.
 */
class RelayDirectoryClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = RelayConfig.RELAY_DIRECTORY_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * `GET /api/relays` — relays usable as mirroring targets: clearnet
     * (third-party sockets must resolve via plain DNS), online per the
     * directory's own probes, free (`plan=free`; the mirroring drainer cannot
     * pay admission fees). [query] is the free-text search parameter.
     */
    suspend fun searchFanoutRelays(
        query: String? = null,
        limit: Int = 100
    ): Result<List<DirectoryRelayDto>> = withContext(Dispatchers.IO) {
        val httpUrl = ("$baseUrl/api/relays").toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("network", "clearnet")
            addQueryParameter("status", "online")
            addQueryParameter("plan", "free")
            addQueryParameter("limit", limit.coerceIn(1, 500).toString())
            query?.takeIf { it.isNotBlank() }?.let { q ->
                addQueryParameter("q", URLEncoder.encode(q.trim(), "UTF-8"))
            }
        }?.build()

        if (httpUrl == null) {
            return@withContext Result.failure(DirectoryError.Unknown("Invalid directory URL"))
        }

        try {
            val request = Request.Builder().url(httpUrl).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    val error = parseError(response.code, body)
                    Log.w(TAG, "GET $httpUrl -> HTTP ${response.code}: ${error.message}")
                    return@withContext Result.failure(error)
                }
                if (body.isNullOrBlank()) {
                    return@withContext Result.failure(DirectoryError.Unknown("Empty response body"))
                }
                val parsed = json.decodeFromString<DirectoryRelaysResponse>(body)
                Log.d(TAG, "GET $httpUrl -> ${parsed.relays.size} relays")
                Result.success(parsed.relays)
            }
        } catch (e: DirectoryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "GET $httpUrl failed: ${e.message}", e)
            Result.failure(DirectoryError.Unknown(e.message ?: "Network error"))
        }
    }

    /**
     * `GET /api/relays/{id}` — full detail for one relay (API.md §Detail).
     * `{id}` is the relay URL base64url-encoded with padding stripped; 404
     * (unknown **or** malformed id) → null. Used to enrich manually added
     * mirroring relays with the directory's ping/uptime/icon/NIP harvest in
     * one lookup.
     */
    suspend fun getRelayDetail(url: String): Result<DirectoryRelayDto?> = withContext(Dispatchers.IO) {
        val id = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(url.toByteArray(Charsets.UTF_8))
        val httpUrl = ("$baseUrl/api/relays/$id").toHttpUrlOrNull()
            ?: return@withContext Result.failure(DirectoryError.Unknown("Invalid relay URL"))

        try {
            val request = Request.Builder().url(httpUrl).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.code == 404) {
                    Log.d(TAG, "GET detail $url -> not in the directory")
                    return@withContext Result.success(null)
                }
                if (!response.isSuccessful) {
                    val error = parseError(response.code, body)
                    Log.w(TAG, "GET $httpUrl -> HTTP ${response.code}: ${error.message}")
                    return@withContext Result.failure(error)
                }
                if (body.isNullOrBlank()) {
                    return@withContext Result.failure(DirectoryError.Unknown("Empty response body"))
                }
                Result.success(json.decodeFromString<DirectoryRelayDto>(body))
            }
        } catch (e: DirectoryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "GET $httpUrl failed: ${e.message}", e)
            Result.failure(DirectoryError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): DirectoryError {
        val detail = try {
            json.decodeFromString<DirectoryErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        return when (code) {
            401 -> DirectoryError.Unauthorized
            429 -> DirectoryError.RateLimited
            else -> DirectoryError.Server(detail ?: "Directory error", code)
        }
    }

    companion object {
        private const val TAG = "RelayDirectoryClient"
    }
}
