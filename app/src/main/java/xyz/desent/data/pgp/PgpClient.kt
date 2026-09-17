package xyz.desent.data.pgp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.pgp.model.PgpConfigResponse
import xyz.desent.data.pgp.model.PgpError
import xyz.desent.data.pgp.model.PgpErrorResponse
import xyz.desent.data.pgp.model.PgpKeyResponse
import xyz.desent.data.pgp.model.PgpPutKeyRequest
import xyz.desent.data.pgp.model.PgpWkdLookupResponse
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp REST client for the relay's PGP endpoints —
 * refs/FROM_email.desent.xyz/PGP_ENCRYPTION.md § Key registry + WKD
 * discovery. Key registration uses NIP-98 auth; the config gate and the
 * WKD lookup proxy are public. The client NEVER sends private key material
 * anywhere — only the armored public half.
 */
class PgpClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** GET /config — public feature gate, cached 5 min at nginx. */
    suspend fun getConfig(): Result<PgpConfigResponse> = withContext(Dispatchers.IO) {
        runRequest<PgpConfigResponse>(
            url = "$baseUrl/config",
            method = "GET",
            authRequired = false
        )
    }

    /** GET /key — the caller's registered key or 404 no_pgp_key. */
    suspend fun getKey(): Result<PgpKeyResponse> = withContext(Dispatchers.IO) {
        runRequest<PgpKeyResponse>(
            url = "$baseUrl/key",
            method = "GET",
            authRequired = true
        )
    }

    /** PUT /key — register/replace the WKD-published public key. */
    suspend fun putKey(publicKeyArmored: String): Result<PgpKeyResponse> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            PgpPutKeyRequest.serializer(),
            PgpPutKeyRequest(publicKey = publicKeyArmored)
        ).toByteArray(Charsets.UTF_8)
        runRequest<PgpKeyResponse>(
            url = "$baseUrl/key",
            method = "PUT",
            authRequired = true,
            bodyBytes = body
        )
    }

    /** DELETE /key — unpublish WKD (idempotent). */
    suspend fun deleteKey(): Result<Unit> = withContext(Dispatchers.IO) {
        execute(
            url = "$baseUrl/key",
            method = "DELETE",
            authRequired = true
        ).map { }
    }

    /**
     * GET /wkd-lookup?email= — recipient key discovery via the relay's WKD
     * proxy (uniform, cached, and handles the direct-method URL shape).
     * Returns the recipient's armored PUBLIC key, decoding the external
     * `key_base64` variant (binary OpenPGP → armor) so callers always see
     * armor, or null when no key is discoverable.
     */
    suspend fun lookupRecipientKey(email: String): Result<String?> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/wkd-lookup?email=${java.net.URLEncoder.encode(email, "UTF-8")}"
        runRequest<PgpWkdLookupResponse>(
            url = url,
            method = "GET",
            authRequired = false
        ).map { response ->
            when {
                !response.found -> null
                response.armored != null -> response.armored
                response.keyBase64 != null -> armorBinaryKey(response.keyBase64)
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Convert a binary (packet-level) OpenPGP key from the external-domain
     * WKD path into canonical armor so downstream parsing is uniform.
     * Memoized: WKD keys are immutable per fingerprint.
     */
    private fun armorBinaryKey(base64: String): String? {
        binaryKeyCache[base64]?.let { return it }
        return try {
            // Mime decoder: tolerates line wrapping some proxies add.
            val decoded = Base64.getMimeDecoder().decode(base64)
            val out = java.io.ByteArrayOutputStream(decoded.size + 256)
            org.bouncycastle.bcpg.ArmoredOutputStream(out).use { it.write(decoded) }
            out.toString("UTF-8").trim().also { binaryKeyCache[base64] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to armor binary WKD key: ${e.message}")
            null
        }
    }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        authRequired: Boolean,
        bodyBytes: ByteArray? = null
    ): Result<T> {
        val raw = execute(url, method, authRequired, bodyBytes)
        return raw.mapCatching { body ->
            if (body.isNullOrBlank()) throw PgpError.Unknown("Empty response body")
            json.decodeFromString<T>(body)
        }
    }

    /** Execute the HTTP call; returns the response body (null when empty). */
    private suspend fun execute(
        url: String,
        method: String,
        authRequired: Boolean,
        bodyBytes: ByteArray? = null
    ): Result<String?> {
        return try {
            val requestBuilder = Request.Builder().url(url)

            if (authRequired) {
                val header = auth.buildAuthHeader(url, method, bodyBytes).getOrThrow()
                requestBuilder.header("Authorization", header)
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "PUT" -> {
                    requestBuilder.header("Content-Type", "application/json")
                    requestBuilder.put(
                        (bodyBytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType())
                    )
                }
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

            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(responseBody)
        } catch (e: PgpError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(PgpError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): PgpError {
        val parsed = try {
            Json.decodeFromString<PgpErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        val codeStr = parsed?.error

        return when (code) {
            401 -> PgpError.Unauthorized
            403 -> when (codeStr) {
                "pgp_disabled" -> PgpError.PgpDisabled
                else -> PgpError.Server(codeStr ?: "Forbidden", code)
            }
            404 -> when (codeStr) {
                "no_pgp_key" -> PgpError.NoPgpKey
                else -> PgpError.Server(codeStr ?: "Not found", code)
            }
            422 -> PgpError.InvalidKey(parsed?.message)
            429 -> PgpError.RateLimited
            else -> PgpError.Server(codeStr ?: "Server error", code)
        }
    }

    companion object {
        private const val TAG = "PgpClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/pgp"

        /** base64(binary key) → armor; bounded by distinct WKD recipients. */
        private val binaryKeyCache = ConcurrentHashMap<String, String>()
    }
}
