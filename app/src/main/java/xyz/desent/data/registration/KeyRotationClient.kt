package xyz.desent.data.registration

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.registration.model.KeyRotateChallengeResponse
import xyz.desent.data.registration.model.KeyRotateCleanupResponse
import xyz.desent.data.registration.model.KeyRotateRequest
import xyz.desent.data.registration.model.KeyRotateResponse
import xyz.desent.data.registration.model.KeyRotateRestoreResponse
import xyz.desent.data.registration.model.KeyRotationError
import xyz.desent.data.registration.model.RegistrationErrorResponse

/**
 * OkHttp REST client for the key-rotation endpoints
 * (refs/FROM_email.desent.xyz/KEY_ROTATION.md §3).
 *
 * Challenge + rotate are NIP-98 signed by the **OLD** key; restore + cleanup
 * by the **NEW** key — every method therefore takes an explicit
 * [nostr.id.Identity] signer, exactly like `RegistrationClient.getMe`.
 *
 * Restore bodies carry pre-serialized 1059 event JSON (built by
 * [xyz.desent.crypto.GiftRewrap]); the client parses each element so the
 * request body is a well-formed `{"events": [...]}` JSON array.
 */
class KeyRotationClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** POST /account/key-rotate/challenge — NIP-98 by the OLD key. */
    suspend fun challenge(
        identity: nostr.id.Identity
    ): Result<KeyRotateChallengeResponse> = withContext(Dispatchers.IO) {
        runRequest<KeyRotateChallengeResponse>(
            url = "$baseUrl/account/key-rotate/challenge",
            method = "POST",
            identity = identity,
            bodyBytes = "{}".toByteArray(Charsets.UTF_8)
        )
    }

    /** POST /api/account/key-rotate — the atomic re-key. NIP-98 by the OLD key. */
    suspend fun rotate(
        request: KeyRotateRequest,
        identity: nostr.id.Identity
    ): Result<KeyRotateResponse> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(KeyRotateRequest.serializer(), request)
        runRequest<KeyRotateResponse>(
            url = "$baseUrl/account/key-rotate",
            method = "POST",
            identity = identity,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * POST /account/key-rotate/restore — persist re-wrapped mail. NIP-98 by
     * the NEW key. [events] are standard Nostr event JSON strings, batched
     * by the caller (≤ 200 per call, each ≤ 64 KiB serialized).
     */
    suspend fun restore(
        events: List<String>,
        identity: nostr.id.Identity
    ): Result<KeyRotateRestoreResponse> = withContext(Dispatchers.IO) {
        val array = JsonArray(events.map { eventJson ->
            try {
                json.parseToJsonElement(eventJson)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    KeyRotationError.Unknown("Malformed re-wrapped event JSON: ${e.message}")
                )
            }
        })
        val body = kotlinx.serialization.json.buildJsonObject { put("events", array) }
        runRequest<KeyRotateRestoreResponse>(
            url = "$baseUrl/account/key-rotate/restore",
            method = "POST",
            identity = identity,
            bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        )
    }

    /** POST /account/key-rotate/cleanup — purge the old key. NIP-98 by the NEW key. */
    suspend fun cleanup(
        identity: nostr.id.Identity
    ): Result<KeyRotateCleanupResponse> = withContext(Dispatchers.IO) {
        runRequest<KeyRotateCleanupResponse>(
            url = "$baseUrl/account/key-rotate/cleanup",
            method = "POST",
            identity = identity,
            bodyBytes = "{}".toByteArray(Charsets.UTF_8)
        )
    }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        identity: nostr.id.Identity,
        bodyBytes: ByteArray? = null
    ): Result<T> {
        return try {
            val header = auth.buildAuthHeader(url, method, bodyBytes, identity).getOrThrow()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", header)
                .header("Content-Type", "application/json")
                .post((bodyBytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    val error = parseError(response.code, responseBody)
                    Log.w(TAG, "$method $url -> HTTP ${response.code}: ${error.message}")
                    return@use Result.failure(error)
                }
                if (responseBody.isNullOrBlank()) {
                    return@use Result.failure(KeyRotationError.Unknown("Empty response body"))
                }
                Result.success(json.decodeFromString<T>(responseBody))
            }
        } catch (e: KeyRotationError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(KeyRotationError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): KeyRotationError {
        val parsed = try {
            json.decodeFromString<RegistrationErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        // Slug first (the contract keys errors by `error`), HTTP code second.
        return when (parsed?.error) {
            "premium_required" -> KeyRotationError.PremiumRequired(parsed.tier)
            "rotation_cooldown" -> KeyRotationError.RotationCooldown
            "pubkey_taken" -> KeyRotationError.PubkeyTaken
            "invalid_credentials" -> KeyRotationError.InvalidCredentials
            "invalid_nonce" -> KeyRotationError.InvalidNonce
            "invalid_new_key_proof" -> KeyRotationError.InvalidNewKeyProof
            "key_rotation_disabled" -> KeyRotationError.KeyRotationDisabled
            "keep_identity_not_allowed" -> KeyRotationError.KeepIdentityNotAllowed
            "keep_identity_needs_conversion" -> KeyRotationError.KeepIdentityNeedsConversion
            "nostr_link_disabled" -> KeyRotationError.NostrLinkDisabled
            "identity_already_linked" -> KeyRotationError.IdentityAlreadyLinked
            "custodial_disabled" -> KeyRotationError.CustodialDisabled
            else -> when (code) {
                402 -> KeyRotationError.PremiumRequired(null)
                423 -> KeyRotationError.AccountLocked(parsed?.retryAfterSeconds)
                429 -> KeyRotationError.RateLimited(parsed?.retryAfterSeconds)
                else -> KeyRotationError.Server(parsed?.error ?: "HTTP $code", code)
            }
        }
    }

    companion object {
        private const val TAG = "KeyRotationClient"
        const val DEFAULT_BASE_URL = RegistrationClient.DEFAULT_BASE_URL
    }
}
