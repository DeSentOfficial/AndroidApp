package xyz.desent.data.vanity

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.vanity.model.CreateVanityRequestRequest
import xyz.desent.data.vanity.model.VanityError
import xyz.desent.data.vanity.model.VanityErrorDetail
import xyz.desent.data.vanity.model.VanityRequestsResponse
import xyz.desent.data.vanity.model.VanityRequestDto
import xyz.desent.data.vanity.model.VanityErrorResponse

/**
 * OkHttp REST client for the DeSent vanity (short address) pricing API —
 * refs/FromServer/ANDROID_VANITY_PRICING.md §3. Auth is NIP-98 per request
 * via [NostrHttpAuth]; the exact URL + body bytes feed the `u`/`payload` tags.
 */
class VanityClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * POST / — request approval for a priced local part (one approval = one
     * claim).
     *
     * @param identity explicit signer for the signup flow, where the fresh
     * claiming key is memory-only and not yet the active account. Null — the
     * default — signs with the active account.
     */
    suspend fun createRequest(
        localPart: String,
        domain: String?,
        kind: String,
        identity: nostr.id.Identity? = null
    ): Result<VanityRequestDto> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(
            CreateVanityRequestRequest.serializer(),
            CreateVanityRequestRequest(localPart = localPart, domain = domain, kind = kind)
        )
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        execute(baseUrl, "POST", bodyBytes, identity).map { body ->
            json.decodeFromString<VanityRequestDto>(body)
        }
    }

    /** GET / — own requests, newest first (max 100). */
    suspend fun listRequests(
        identity: nostr.id.Identity? = null
    ): Result<List<VanityRequestDto>> = withContext(Dispatchers.IO) {
        execute(baseUrl, "GET", identity = identity).map { body -> parseRequestList(body) }
    }

    private fun parseRequestList(body: String): List<VanityRequestDto> = try {
        json.decodeFromString<VanityRequestsResponse>(body).requests
    } catch (e: Exception) {
        json.decodeFromString<List<VanityRequestDto>>(body)
    }

    private suspend fun execute(
        url: String,
        method: String,
        bodyBytes: ByteArray? = null,
        identity: nostr.id.Identity? = null
    ): Result<String> {
        return try {
            val header = auth.buildAuthHeader(url, method, bodyBytes, identity).getOrThrow()
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", header)

            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> {
                    requestBuilder.header("Content-Type", "application/json")
                    requestBuilder.post(
                        (bodyBytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType())
                    )
                }
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val error = parseError(response.code, responseBody)
                Log.w(TAG, "$method $url -> HTTP ${response.code}: ${error.message}")
                return Result.failure(error)
            }

            if (responseBody.isNullOrBlank()) {
                return Result.failure(VanityError.Unknown("Empty response body"))
            }

            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(responseBody)
        } catch (e: VanityError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(VanityError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): VanityError {
        val parsed: VanityErrorDetail? = try {
            Json.decodeFromString<VanityErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        val codeStr = parsed?.error ?: parsed?.detail ?: parsed?.message

        return when (code) {
            401 -> VanityError.Unauthorized
            403 -> if (codeStr == "registration_disabled_domain") {
                VanityError.RegistrationDisabledDomain
            } else {
                VanityError.Server(codeStr ?: "Forbidden", code)
            }
            409 -> when (codeStr) {
                "request_exists" -> VanityError.RequestExists
                "taken" -> VanityError.Taken
                else -> VanityError.Server(codeStr ?: "Conflict", code)
            }
            422 -> when (codeStr) {
                "not_priced" -> VanityError.NotPriced
                "reserved" -> VanityError.Reserved
                "invalid_local_part" -> VanityError.InvalidLocalPart
                "invalid_kind" -> VanityError.InvalidKind
                else -> VanityError.Unknown(codeStr ?: "Validation error")
            }
            429 -> VanityError.RateLimited
            else -> VanityError.Server(codeStr ?: "Server error", code)
        }
    }

    companion object {
        private const val TAG = "VanityClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/vanity/requests"
    }
}
