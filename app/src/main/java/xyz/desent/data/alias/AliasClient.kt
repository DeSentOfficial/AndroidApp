package xyz.desent.data.alias

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.alias.model.AliasDto
import xyz.desent.data.alias.model.AliasError
import xyz.desent.data.alias.model.AliasListResponse
import xyz.desent.data.alias.model.ConfigResponse
import xyz.desent.data.alias.model.CreateAliasRequest
import xyz.desent.data.alias.model.DeleteAliasResponse
import xyz.desent.data.alias.model.SlotPurchaseDto
import xyz.desent.data.alias.model.SlotPurchaseRequest
import xyz.desent.data.alias.model.SlotsStatusResponse
import xyz.desent.data.alias.model.TierInfoResponse

/**
 * OkHttp REST client for the DeSent alias API.
 *
 * Base URL defaults to `https://desent.xyz/api/aliases`. Auth (NIP-98) is
 * built per-request via [NostrHttpAuth]; the exact URL + body bytes are passed
 * through so the `u` / `payload` tags match what goes on the wire.
 */
class AliasClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** GET /config — public, no auth. */
    suspend fun getConfig(): Result<ConfigResponse> = withContext(Dispatchers.IO) {
        runRequest<ConfigResponse>(
            url = "$baseUrl/config",
            method = "GET",
            authRequired = false
        )
    }

    /** GET / — list caller's aliases + tier info. */
    suspend fun listAliases(): Result<AliasListResponse> = withContext(Dispatchers.IO) {
        runRequest<AliasListResponse>(
            url = baseUrl,
            method = "GET",
            authRequired = true
        )
    }

    /** GET /tier-info — cheaper call when only used/cap/tier is needed. */
    suspend fun getTierInfo(): Result<TierInfoResponse> = withContext(Dispatchers.IO) {
        runRequest<TierInfoResponse>(
            url = "$baseUrl/tier-info",
            method = "GET",
            authRequired = true
        )
    }

    /** POST / — create a new alias. Server lowercases alias_local. */
    suspend fun createAlias(aliasLocal: String, label: String?): Result<AliasDto> =
        withContext(Dispatchers.IO) {
            val bodyStr = json.encodeToString(
                CreateAliasRequest.serializer(),
                CreateAliasRequest(aliasLocal = aliasLocal, label = label)
            )
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            runRequest<AliasDto>(
                url = baseUrl,
                method = "POST",
                authRequired = true,
                bodyBytes = bodyBytes
            )
        }

    /** DELETE /{id} — delete an alias (irreversible). */
    suspend fun deleteAlias(id: Long): Result<DeleteAliasResponse> =
        withContext(Dispatchers.IO) {
            runRequest<DeleteAliasResponse>(
                url = "$baseUrl/$id",
                method = "DELETE",
                authRequired = true
            )
        }

    /** POST /slots — request a slot-pack purchase (quantity 1..25, operator-approved). */
    suspend fun purchaseSlots(quantity: Int): Result<SlotPurchaseDto> =
        withContext(Dispatchers.IO) {
            val bodyStr = json.encodeToString(
                SlotPurchaseRequest.serializer(),
                SlotPurchaseRequest(quantity = quantity)
            )
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            runRequest<SlotPurchaseDto>(
                url = "$baseUrl/slots",
                method = "POST",
                authRequired = true,
                bodyBytes = bodyBytes
            )
        }

    /** GET /slots — purchase history + live cap math (`cap` is already effective). */
    suspend fun getSlots(): Result<SlotsStatusResponse> = withContext(Dispatchers.IO) {
        runRequest<SlotsStatusResponse>(
            url = "$baseUrl/slots",
            method = "GET",
            authRequired = true
        )
    }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        authRequired: Boolean,
        bodyBytes: ByteArray? = null
    ): Result<T> {
        return try {
            val requestBuilder = Request.Builder().url(url)

            if (authRequired) {
                val header = auth.buildAuthHeader(url, method, bodyBytes).getOrThrow()
                requestBuilder.header("Authorization", header)
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "DELETE" -> requestBuilder.delete()
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
                return Result.failure(AliasError.Unknown("Empty response body"))
            }

            val parsed = json.decodeFromString<T>(responseBody)
            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(parsed)
        } catch (e: AliasError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(AliasError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): AliasError {
        val parsed = try {
            Json.decodeFromString<xyz.desent.data.alias.model.AliasErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        val codeStr = parsed?.error

        return when (code) {
            401 -> AliasError.Unauthorized
            402 -> when (codeStr) {
                "vanity_price" -> AliasError.VanityPrice(
                    length = parsed?.length ?: 0,
                    priceSats = parsed?.priceSats ?: 0L,
                    ladder = parsed?.ladder ?: emptyMap(),
                    freeLength = parsed?.freeLength ?: 8
                )
                "slot_price" -> AliasError.SlotPrice(
                    tier = parsed?.tier,
                    freeCap = parsed?.freeCap,
                    slotsOwned = parsed?.slotsOwned,
                    cap = parsed?.cap,
                    used = parsed?.used,
                    priceSats = parsed?.priceSats ?: 0L
                )
                else -> AliasError.Server(codeStr ?: "Payment required", 402)
            }
            403 -> AliasError.Forbidden
            404 -> AliasError.NotFound
            409 -> AliasError.Taken
            413 -> AliasError.QuotaExceeded(
                used = parsed?.storageUsed,
                cap = parsed?.storageCap,
                incoming = parsed?.incoming,
                tier = parsed?.tier
            )
            422 -> when (codeStr) {
                "reserved" -> AliasError.Reserved
                "invalid_local_part" -> AliasError.InvalidLocalPart
                "matches_username" -> AliasError.MatchesUsername
                else -> AliasError.Unknown(codeStr ?: "Validation error")
            }
            429 -> AliasError.RateLimited
            else -> AliasError.Server(
                codeStr ?: "Server error",
                code
            )
        }
    }

    companion object {
        private const val TAG = "AliasClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/aliases"
    }
}
