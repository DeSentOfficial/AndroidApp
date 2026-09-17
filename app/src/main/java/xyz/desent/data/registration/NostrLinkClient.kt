package xyz.desent.data.registration

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.registration.model.NostrLinkChallengeResponse
import xyz.desent.data.registration.model.NostrLinkError
import xyz.desent.data.registration.model.NostrLinkLoginRequest
import xyz.desent.data.registration.model.NostrLinkLoginResponse
import xyz.desent.data.registration.model.NostrLinkVerifyRequest
import xyz.desent.data.registration.model.NostrLinkVerifyResponse
import xyz.desent.data.registration.model.RegistrationErrorResponse

/**
 * OkHttp REST client for the linked Nostr-identity endpoints
 * (refs/FROM_email.desent.xyz/NOSTR_CUSTODIAL.md §3 — the Android "old-key
 * login" path after a rotation that kept the previous key as an identity).
 *
 * All three endpoints are public (no NIP-98); the caller's identity is
 * proven by the kind-22242 schnorr signature inside `POST /nostr/login`.
 * The signed event is built by [probe] itself from the server's template,
 * so callers only hand over the private key once.
 */
class NostrLinkClient(
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
     * POST /nostr/challenge (public) → the HMAC nonce + the 22242 template.
     * The returned template's `challenge` tag already carries the nonce; the
     * caller does NOT need to inject it again.
     */
    suspend fun challenge(): Result<NostrLinkChallengeResponse> = withContext(Dispatchers.IO) {
        runRequest<NostrLinkChallengeResponse>(
            url = "$baseUrl/nostr/challenge",
            body = "{}"
        )
    }

    /**
     * POST /nostr/login — probe whether [privateKey]'s key is linked to a
     * custodial account. Signs the kind-22242 template from [challenge]
     * (login proofs omit the `account` tag — the client cannot know the
     * linked account before the lookup), then posts `{nonce, event}`.
     *
     * `404 not_linked` → [NostrLinkError.NotLinked] (fall back to the plain
     * key path); `200` → the salt + handoff for the password phase.
     */
    suspend fun login(privateKey: ByteArray): Result<NostrLinkLoginResponse> =
        withContext(Dispatchers.IO) {
            val challenge = challenge().getOrElse { return@withContext Result.failure(it) }

            val pubkeyHex = xyz.desent.crypto.Nip44Encryption.bytesToHex(
                xyz.desent.crypto.Nip44Encryption.derivePublicKey(privateKey)
            )
            val createdAt = System.currentTimeMillis() / 1000
            val eventJson = try {
                xyz.desent.crypto.GiftRewrap.buildEventJson(
                    id = xyz.desent.crypto.NostrEventCrypto.computeEventId(
                        pubkeyHex, createdAt, challenge.event.kind,
                        challenge.event.tags, challenge.event.content
                    ),
                    pubkey = pubkeyHex,
                    createdAt = createdAt,
                    kind = challenge.event.kind,
                    tags = challenge.event.tags,
                    content = challenge.event.content,
                    sig = xyz.desent.crypto.NostrEventCrypto.signHex(
                        xyz.desent.crypto.NostrEventCrypto.canonicalEventBytes(
                            pubkeyHex, createdAt, challenge.event.kind,
                            challenge.event.tags, challenge.event.content
                        ),
                        privateKey
                    )
                )
            } catch (e: Exception) {
                return@withContext Result.failure(NostrLinkError.Unknown("Proof signing failed: ${e.message}"))
            }

            val body = json.encodeToString(
                NostrLinkLoginRequest.serializer(),
                NostrLinkLoginRequest(
                    nonce = challenge.nonce,
                    event = json.parseToJsonElement(eventJson).jsonObject
                )
            )
            runRequest<NostrLinkLoginResponse>(
                url = "$baseUrl/nostr/login",
                body = body,
                notFoundIs = NostrLinkError.NotLinked
            )
        }

    /**
     * POST /nostr/login/verify — password phase: the argon2id verifier over
     * the salt from [NostrLinkLoginResponse] in, the encrypted-nsec blob out.
     */
    suspend fun verify(handoff: String, verifier: String): Result<NostrLinkVerifyResponse> =
        withContext(Dispatchers.IO) {
            runRequest<NostrLinkVerifyResponse>(
                url = "$baseUrl/nostr/login/verify",
                body = json.encodeToString(
                    NostrLinkVerifyRequest.serializer(),
                    NostrLinkVerifyRequest(handoff = handoff, verifier = verifier)
                )
            )
        }

    private suspend inline fun <reified T> runRequest(
        url: String,
        body: String,
        notFoundIs: NostrLinkError? = null
    ): Result<T> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    val error = if (response.code == 404 && notFoundIs != null) notFoundIs
                    else parseError(response.code, responseBody)
                    Log.w(TAG, "POST $url -> HTTP ${response.code}: ${error.message}")
                    return@use Result.failure(error)
                }
                if (responseBody.isNullOrBlank()) {
                    return@use Result.failure(NostrLinkError.Unknown("Empty response body"))
                }
                Result.success(json.decodeFromString<T>(responseBody))
            }
        } catch (e: NostrLinkError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "POST $url failed: ${e.message}", e)
            Result.failure(NostrLinkError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): NostrLinkError {
        val parsed = try {
            json.decodeFromString<RegistrationErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        return when (parsed?.error) {
            "not_linked" -> NostrLinkError.NotLinked
            "invalid_proof" -> NostrLinkError.InvalidProof
            "invalid_nonce" -> NostrLinkError.InvalidNonce
            "invalid_credentials" -> NostrLinkError.InvalidCredentials
            "account_disabled" -> NostrLinkError.AccountDisabled
            "nostr_link_disabled" -> NostrLinkError.NostrLinkDisabled
            "custodial_disabled" -> NostrLinkError.CustodialDisabled
            else -> when (code) {
                401 -> NostrLinkError.InvalidCredentials
                423 -> NostrLinkError.AccountLocked(parsed?.retryAfterSeconds)
                429 -> NostrLinkError.RateLimited(parsed?.retryAfterSeconds)
                else -> NostrLinkError.Server(parsed?.error ?: "HTTP $code", code)
            }
        }
    }

    companion object {
        private const val TAG = "NostrLinkClient"
        const val DEFAULT_BASE_URL = RegistrationClient.DEFAULT_BASE_URL
    }
}
