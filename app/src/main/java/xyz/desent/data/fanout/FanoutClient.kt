package xyz.desent.data.fanout

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.fanout.model.EventsStatusRequest
import xyz.desent.data.fanout.model.EventsStatusResponse
import xyz.desent.data.fanout.model.FanoutError
import xyz.desent.data.fanout.model.FanoutErrorResponse
import xyz.desent.data.fanout.model.FanoutStatusResponse
import xyz.desent.data.fanout.model.ImportRequest
import xyz.desent.data.fanout.model.ImportResponse
import xyz.desent.data.fanout.model.ReconcileResponse
import xyz.desent.data.fanout.model.RelayCheckResponse

/**
 * OkHttp REST client for the relay-mirroring (wire: fan-out) API —
 * refs/FROM_email.desent.xyz/ANDROID_DM_FANOUT.md §4 (health) and §6 (mirror
 * surface, migration 056). Auth (NIP-98) is built per-request via
 * [NostrHttpAuth]; the exact URL + body bytes feed the `u`/`payload` tags.
 */
class FanoutClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** GET /status — per-relay queue health for the mirroring section (§4). */
    suspend fun getStatus(): Result<FanoutStatusResponse> = withContext(Dispatchers.IO) {
        runRequest<FanoutStatusResponse>(url = "$baseUrl/status", method = "GET")
    }

    /**
     * GET /relay-check?url=… — advisory add-time check (§6.1): directory
     * data + NIP-11 + a capabilities verdict. NEVER blocks a save.
     */
    suspend fun relayCheck(url: String): Result<RelayCheckResponse> = withContext(Dispatchers.IO) {
        runRequest<RelayCheckResponse>(
            url = "$baseUrl/relay-check?url=" + java.net.URLEncoder.encode(
                url,
                Charsets.UTF_8.name()
            ),
            method = "GET"
        )
    }

    /** POST /events-status — per-event mirror delivery (§6.2); batch ≤ 200 ids. */
    suspend fun eventsStatus(eventIds: List<String>): Result<EventsStatusResponse> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                EventsStatusRequest.serializer(),
                EventsStatusRequest(eventIds = eventIds.take(EVENTS_STATUS_MAX_IDS))
            )
            runRequest<EventsStatusResponse>(
                url = "$baseUrl/events-status",
                method = "POST",
                bodyBytes = body.toByteArray(Charsets.UTF_8)
            )
        }

    /** POST /reconcile — gap detection (§6.3); explicit user action only. */
    suspend fun reconcile(): Result<ReconcileResponse> = withContext(Dispatchers.IO) {
        runRequest<ReconcileResponse>(url = "$baseUrl/reconcile", method = "POST")
    }

    /** POST /import — re-fetch + re-store missing wraps (§6.3); batch ≤ 100 ids. */
    suspend fun importWraps(eventIds: List<String>): Result<ImportResponse> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                ImportRequest.serializer(),
                ImportRequest(eventIds = eventIds.take(IMPORT_MAX_IDS))
            )
            runRequest<ImportResponse>(
                url = "$baseUrl/import",
                method = "POST",
                bodyBytes = body.toByteArray(Charsets.UTF_8)
            )
        }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        bodyBytes: ByteArray? = null
    ): Result<T> {
        return try {
            val requestBuilder = Request.Builder().url(url)
            val header = auth.buildAuthHeader(url, method, bodyBytes).getOrThrow()
            requestBuilder.header("Authorization", header)

            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> {
                    requestBuilder.header("Content-Type", "application/json")
                    requestBuilder.post(
                        (bodyBytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType())
                    )
                }
            }

            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    val error = parseError(response.code, body)
                    Log.w(TAG, "$method $url -> HTTP ${response.code}: ${error.message}")
                    Result.failure(error)
                } else if (body.isNullOrBlank()) {
                    Result.failure(FanoutError.Unknown("Empty response body"))
                } else {
                    Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
                    Result.success(json.decodeFromString<T>(body))
                }
            }
        } catch (e: FanoutError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(FanoutError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): FanoutError {
        val parsed = try {
            json.decodeFromString<FanoutErrorResponse>(body ?: "{}")
        } catch (e: Exception) {
            null
        }
        val errorCode = parsed?.errorCode
        val detailText = (parsed?.detail as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val message = errorCode ?: detailText ?: "Server error"
        return when (code) {
            401 -> FanoutError.Unauthorized
            403 -> when (errorCode) {
                "not_entitled" -> FanoutError.NotEntitled
                else -> FanoutError.Server(message, code)
            }
            429 -> FanoutError.RateLimited
            503 -> when (errorCode) {
                "fanout_disabled" -> FanoutError.FanoutDisabled
                else -> FanoutError.Server(message, code)
            }
            else -> FanoutError.Server(message, code)
        }
    }

    companion object {
        private const val TAG = "FanoutClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/fanout"
        /** §6.2 server cap — one visible list page per call. */
        const val EVENTS_STATUS_MAX_IDS = 200
        /** §6.3 server cap per import call. */
        const val IMPORT_MAX_IDS = 100
    }
}
