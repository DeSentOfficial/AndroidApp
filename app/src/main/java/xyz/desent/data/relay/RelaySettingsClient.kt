package xyz.desent.data.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.relay.model.RelaySettingsDto

/**
 * OkHttp REST client for `GET /api/relay-settings` (refs/CALENDAR_PROTOCOL.md
 * §Feature flag). Auth is NIP-98 (kind 27235), per-request via [NostrHttpAuth]
 * — same pattern as [xyz.desent.data.storage.StorageClient].
 *
 * On error / non-2xx the caller gets a failed `Result`; the repository maps
 * that to a graceful default so a missing or misbehaving endpoint never hides
 * the feature.
 */
class RelaySettingsClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun getRelaySettings(): Result<RelaySettingsDto> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBuilder = Request.Builder().url(baseUrl)
            val header = auth.buildAuthHeader(baseUrl, "GET", null).getOrThrow()
            requestBuilder.header("Authorization", header).get()

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.w(TAG, "GET $baseUrl -> HTTP ${response.code}")
                throw Exception("relay-settings HTTP ${response.code}")
            }
            if (body.isNullOrBlank()) throw Exception("Empty relay-settings body")
            json.decodeFromString<RelaySettingsDto>(body)
        }.onFailure { Log.w(TAG, "getRelaySettings failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "RelaySettingsClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/relay-settings"
    }
}
