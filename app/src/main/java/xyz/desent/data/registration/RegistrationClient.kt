package xyz.desent.data.registration

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.registration.model.AvailableResponse
import xyz.desent.data.registration.model.CustodialCredentialsOkResponse
import xyz.desent.data.registration.model.CustodialCredentialsRequest
import xyz.desent.data.registration.model.CustodialLoginChallengeRequest
import xyz.desent.data.registration.model.CustodialLoginChallengeResponse
import xyz.desent.data.registration.model.CustodialLoginRequest
import xyz.desent.data.registration.model.CustodialLoginResponse
import xyz.desent.data.registration.model.CustodialRegisterRequest
import xyz.desent.data.registration.model.CustodialRegisterResponse
import xyz.desent.data.registration.model.CustodialSuggestResponse
import xyz.desent.data.registration.model.MeResponse
import xyz.desent.data.registration.model.ProfilePictureResponse
import xyz.desent.data.registration.model.ReferralsResponse
import xyz.desent.data.registration.model.ReferralValidityResponse
import xyz.desent.data.registration.model.RegisterModeResponse
import xyz.desent.data.registration.model.RegisterRequest
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.data.registration.model.RegistrationErrorResponse
import xyz.desent.data.registration.model.UpdateProfileRequest

/**
 * OkHttp REST client for the DeSent registration API.
 *
 * Base URL defaults to `https://desent.xyz/api`. Auth is NIP-98 (kind 27235)
 * via [NostrHttpAuth]; the `u`/`method` tags match the exact request URL/method.
 */
class RegistrationClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        // v2 blobs leave the legacy v1 fields null — omit them on the wire
        // instead of sending explicit nulls the server may reject.
        explicitNulls = false
    }

    /**
     * GET /me — the caller's account, or 404 (NotRegistered) if not registered.
     *
     * @param identity explicit signer for per-account lookups (non-active
     * accounts); null signs with the active account's key.
     */
    suspend fun getMe(identity: nostr.id.Identity? = null): Result<MeResponse> = withContext(Dispatchers.IO) {
        runRequest<MeResponse>(
            url = "$baseUrl/me",
            method = "GET",
            authRequired = true,
            notFoundIs = RegistrationError.NotRegistered,
            identity = identity
        )
    }

    /** GET /register/available?local= — public, rate-limited. */
    suspend fun checkAvailable(local: String): Result<AvailableResponse> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(local.trim(), "UTF-8")
        runRequest<AvailableResponse>(
            url = "$baseUrl/register/available?local=$encoded",
            method = "GET",
            authRequired = false
        )
    }

    /**
     * GET /register/mode — current global registration mode (public).
     * Poll at the start of onboarding and whenever the signup screen reappears.
     */
    suspend fun getRegisterMode(): Result<RegisterModeResponse> = withContext(Dispatchers.IO) {
        runRequest<RegisterModeResponse>(
            url = "$baseUrl/register/mode",
            method = "GET",
            authRequired = false
        )
    }

    /**
     * GET /register/referral?code= — live invite-code validation (public,
     * 30 req/hour/IP). Normalizes to UPPERCASE: the server compares verbatim
     * after trimming and lowercase input fails.
     */
    suspend fun checkReferralCode(code: String): Result<ReferralValidityResponse> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(code.trim().uppercase(), "UTF-8")
            runRequest<ReferralValidityResponse>(
                url = "$baseUrl/register/referral?code=$encoded",
                method = "GET",
                authRequired = false
            )
        }

    /** GET /referrals — the caller's invite codes (NIP-98). Re-fetch, don't cache. */
    suspend fun getReferrals(): Result<ReferralsResponse> = withContext(Dispatchers.IO) {
        runRequest<ReferralsResponse>(
            url = "$baseUrl/referrals",
            method = "GET",
            authRequired = true
        )
    }

    /** POST /register — claim the primary `<local>@desent.xyz` address. */
    suspend fun register(request: RegisterRequest): Result<MeResponse> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(RegisterRequest.serializer(), request)
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        runRequest<MeResponse>(
            url = "$baseUrl/register",
            method = "POST",
            authRequired = true,
            bodyBytes = bodyBytes
        )
    }

    /** PUT /profile — update the user's profile (display_name, about, picture, website). */
    suspend fun updateProfile(request: UpdateProfileRequest): Result<MeResponse> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(UpdateProfileRequest.serializer(), request)
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        runRequest<MeResponse>(
            url = "$baseUrl/profile",
            method = "PUT",
            authRequired = true,
            bodyBytes = bodyBytes
        )
    }

    /**
     * POST /profile/picture — multipart upload. Per refs/ANDROID_CLIENT.md the
     * `payload` tag is not enforced for multipart, so the auth event signs
     * `u`+`method` only (bodyBytes = null). Returns the public picture URL.
     */
    suspend fun uploadProfilePicture(
        imageData: ByteArray,
        mimeType: String,
        fileName: String = "profile.jpg"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/profile/picture"
            // Multipart: sign u+method only (no payload tag for multipart).
            val header = auth.buildAuthHeader(url, "POST", bodyBytes = null).getOrThrow()

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, imageData.toRequestBody(mimeType.toMediaType()))
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", header)
                .post(multipart)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    val error = parseError(response.code, responseBody)
                    Log.w(TAG, "POST $url -> HTTP ${response.code}: ${error.message}")
                    return@use Result.failure(error)
                }
                val parsed = json.decodeFromString<ProfilePictureResponse>(responseBody ?: "{}")
                val resolved = parsed.resolvedUrl
                    ?: return@use Result.failure(RegistrationError.Unknown("No picture URL in response"))
                Result.success(resolved)
            }
        } catch (e: RegistrationError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "uploadProfilePicture failed: ${e.message}", e)
            Result.failure(RegistrationError.Unknown(e.message ?: "Upload failed"))
        }
    }

    // -----------------------------------------------------------------------
    // Client-custodied accounts (refs/FromServer/CUSTODIAL_ACCOUNTS.md)
    // -----------------------------------------------------------------------

    /**
     * GET /custodial/suggest — available adjective+noun username ideas,
     * always ≥ 8 chars (public, 30 req/hour/IP).
     */
    suspend fun suggestUsernames(count: Int = 5): Result<CustodialSuggestResponse> =
        withContext(Dispatchers.IO) {
            runRequest<CustodialSuggestResponse>(
                url = "$baseUrl/custodial/suggest?count=$count",
                method = "GET",
                authRequired = false
            )
        }

    /**
     * POST /custodial/register — NIP-98 authenticated with the freshly
     * generated local key (the caller must have stored it before this call,
     * exactly like [register]). Runs the same gates as /register plus the
     * ≥ 8-char username minimum; usernames ≥ 8 chars are always free.
     */
    suspend fun custodialRegister(request: CustodialRegisterRequest): Result<CustodialRegisterResponse> =
        withContext(Dispatchers.IO) {
            val bodyStr = json.encodeToString(CustodialRegisterRequest.serializer(), request)
            runRequest<CustodialRegisterResponse>(
                url = "$baseUrl/custodial/register",
                method = "POST",
                authRequired = true,
                bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            )
        }

    /**
     * POST /custodial/login/challenge — login phase 1. Always `200`, even for
     * unknown usernames (deterministic decoy salt), so it leaks nothing.
     */
    suspend fun custodialLoginChallenge(
        request: CustodialLoginChallengeRequest
    ): Result<CustodialLoginChallengeResponse> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(CustodialLoginChallengeRequest.serializer(), request)
        runRequest<CustodialLoginChallengeResponse>(
            url = "$baseUrl/custodial/login/challenge",
            method = "POST",
            authRequired = false,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        )
    }

    /** POST /custodial/login — login phase 2: verifier in, blob out. */
    suspend fun custodialLogin(request: CustodialLoginRequest): Result<CustodialLoginResponse> =
        withContext(Dispatchers.IO) {
            val bodyStr = json.encodeToString(CustodialLoginRequest.serializer(), request)
            runRequest<CustodialLoginResponse>(
                url = "$baseUrl/custodial/login",
                method = "POST",
                authRequired = false,
                bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            )
        }

    /**
     * POST /custodial/credentials — password change. NIP-98 authenticated
     * plus the OLD verifier (proof the caller knows the current password;
     * key ownership alone must not suffice).
     */
    suspend fun changeCustodialCredentials(
        request: CustodialCredentialsRequest
    ): Result<CustodialCredentialsOkResponse> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(CustodialCredentialsRequest.serializer(), request)
        runRequest<CustodialCredentialsOkResponse>(
            url = "$baseUrl/custodial/credentials",
            method = "POST",
            authRequired = true,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        )
    }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        authRequired: Boolean,
        bodyBytes: ByteArray? = null,
        notFoundIs: RegistrationError? = null,
        identity: nostr.id.Identity? = null
    ): Result<T> {
        return try {
            val requestBuilder = Request.Builder().url(url)

            if (authRequired) {
                val header = auth.buildAuthHeader(url, method, bodyBytes, identity).getOrThrow()
                requestBuilder.header("Authorization", header)
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "POST", "PUT" -> {
                    requestBuilder.header("Content-Type", "application/json")
                    if (method == "POST") {
                        requestBuilder.post(
                            (bodyBytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType())
                        )
                    } else {
                        requestBuilder.put(
                            (bodyBytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType())
                        )
                    }
                }
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                // 404 means different things per endpoint; let the caller map it.
                val error = if (response.code == 404 && notFoundIs != null) notFoundIs
                else parseError(response.code, responseBody)
                Log.w(TAG, "$method $url -> HTTP ${response.code}: ${error.message}")
                return Result.failure(error)
            }

            if (responseBody.isNullOrBlank()) {
                return Result.failure(RegistrationError.Unknown("Empty response body"))
            }

            val parsed = json.decodeFromString<T>(responseBody)
            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(parsed)
        } catch (e: RegistrationError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(RegistrationError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): RegistrationError {
        val parsed = try {
            json.decodeFromString<RegistrationErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        val codeStr = parsed?.error ?: parsed?.detail ?: parsed?.message

        return when (code) {
            401 -> if (codeStr == "invalid_credentials") RegistrationError.InvalidCredentials
            else RegistrationError.Unauthorized
            402 -> if (codeStr == "vanity_price") {
                RegistrationError.VanityPrice(
                    length = parsed?.length ?: 0,
                    priceSats = parsed?.priceSats ?: 0L,
                    ladder = parsed?.ladder ?: emptyMap(),
                    freeLength = parsed?.freeLength ?: 8
                )
            } else {
                RegistrationError.Server(codeStr ?: "Payment required", code)
            }
            403 -> when (codeStr) {
                "registration_disabled" -> RegistrationError.RegistrationDisabled
                "registration_disabled_domain" ->
                    RegistrationError.RegistrationDisabledDomain(parsed?.domain)
                "referral_required" -> RegistrationError.ReferralRequired
                "invalid_referral_code" -> RegistrationError.InvalidReferralCode
                "account_disabled" -> RegistrationError.AccountDisabled
                "pubkey_not_registered" -> RegistrationError.PubkeyNotRegistered
                "custodial_disabled" -> RegistrationError.CustodialDisabled
                else -> RegistrationError.Server(codeStr ?: "Forbidden", code)
            }
            404 -> if (codeStr == "not_custodial") RegistrationError.NotCustodial
            else RegistrationError.NotRegistered
            409 -> if (codeStr == "already_registered") RegistrationError.AlreadyRegistered
            else RegistrationError.Taken
            413 -> if (codeStr == "blob_too_large") RegistrationError.BlobTooLarge
            else RegistrationError.Server(codeStr ?: "Payload too large", code)
            422 -> when (codeStr) {
                "reserved" -> RegistrationError.Reserved
                "invalid_local_part" -> RegistrationError.InvalidLocalPart
                "invalid_domain" -> RegistrationError.InvalidDomain
                "too_short" -> RegistrationError.TooShort(parsed?.length ?: 8)
                "invalid_verifier" -> RegistrationError.InvalidVerifier
                else -> RegistrationError.Unknown(codeStr ?: "Validation error")
            }
            423 -> RegistrationError.AccountLocked(parsed?.retryAfterSeconds)
            429 -> RegistrationError.RateLimited(parsed?.retryAfterSeconds)
            else -> RegistrationError.Server(codeStr ?: "Server error", code)
        }
    }

    companion object {
        private const val TAG = "RegistrationClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api"
    }
}
