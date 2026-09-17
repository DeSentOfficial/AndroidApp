package xyz.desent.data.payments

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.payments.model.InvoiceDto
import xyz.desent.data.payments.model.InvoiceListResponse
import xyz.desent.data.payments.model.MintInvoiceRequest
import xyz.desent.data.payments.model.PaymentHistoryResponse
import xyz.desent.data.payments.model.PaymentsConfigResponse
import xyz.desent.data.payments.model.PaymentsError
import xyz.desent.data.payments.model.PaymentsErrorResponse
import xyz.desent.data.payments.model.PurchaseFeatureRequest
import xyz.desent.data.payments.model.PurchaseTierRequest
import xyz.desent.domain.model.FeatureProduct
import xyz.desent.domain.model.PaymentTargetType

/**
 * OkHttp REST client for the DeSent Strike checkout API —
 * refs/FROM_email.desent.xyz/ANDROID_PAYMENTS.md §3, §6. All Strike
 * communication is server-side; this client only mints/polls invoices.
 * Auth (NIP-98) is built per-request via [NostrHttpAuth]; the exact URL +
 * body bytes feed the `u`/`payload` tags.
 */
class PaymentsClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** GET /config — public gate; no auth. Display configuration only. */
    suspend fun getConfig(): Result<PaymentsConfigResponse> = withContext(Dispatchers.IO) {
        runRequest<PaymentsConfigResponse>(
            url = "$baseUrl/config",
            method = "GET",
            authRequired = false
        )
    }

    /**
     * POST /invoice — mint (or return the still-live) invoice for a pending
     * purchase row. Idempotent per target while an invoice is live; after
     * expiry it mints a fresh one. Tier purchases use [purchaseTier].
     *
     * @param identity explicit signer for pre-account checkout (the signup
     * vanity flow, where the claiming key is not yet the active account).
     * Null — the default — signs with the active account.
     */
    suspend fun mintInvoice(
        targetType: PaymentTargetType,
        targetId: Long,
        identity: nostr.id.Identity? = null
    ): Result<InvoiceDto> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(
            MintInvoiceRequest.serializer(),
            MintInvoiceRequest(targetType = targetType.wire, targetId = targetId)
        )
        runRequest<InvoiceDto>(
            url = "$baseUrl/invoice",
            method = "POST",
            authRequired = true,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8),
            identity = identity
        )
    }

    /** GET /{id} — status polling; the live truth during checkout. */
    suspend fun getInvoice(
        id: Long,
        identity: nostr.id.Identity? = null
    ): Result<InvoiceDto> = withContext(Dispatchers.IO) {
        runRequest<InvoiceDto>(
            url = "$baseUrl/$id",
            method = "GET",
            authRequired = true,
            identity = identity
        )
    }

    /** GET / — the caller's invoices, newest first (max 100). */
    suspend fun listInvoices(): Result<List<InvoiceDto>> = withContext(Dispatchers.IO) {
        executeRaw(url = baseUrl, method = "GET").map { body -> parseInvoiceList(body) }
    }

    private fun parseInvoiceList(body: String): List<InvoiceDto> = try {
        json.decodeFromString<InvoiceListResponse>(body).invoices
    } catch (e: Exception) {
        json.decodeFromString<List<InvoiceDto>>(body)
    }

    private suspend fun executeRaw(
        url: String,
        method: String,
        bodyBytes: ByteArray? = null
    ): Result<String> {
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

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val error = parseError(response.code, responseBody)
                Log.w(TAG, "$method $url -> HTTP ${response.code}: ${error.message}")
                return Result.failure(error)
            }

            if (responseBody.isNullOrBlank()) {
                return Result.failure(PaymentsError.Unknown("Empty response body"))
            }

            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(responseBody)
        } catch (e: PaymentsError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(PaymentsError.Unknown(e.message ?: "Network error"))
        }
    }

    /**
     * POST /tier — open/reuse the caller's pending tier purchase + mint its
     * invoice (ANDROID_PAYMENTS.md §6). [plan] is `"yearly"` or `"lifetime"`;
     * the API 403s `lifetime_disabled` when the operator turned lifetime
     * sales off, and 409s `plan_switch_blocked` while the other plan's
     * invoice is still live.
     */
    suspend fun purchaseTier(plan: String = "yearly"): Result<InvoiceDto> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(
            PurchaseTierRequest.serializer(),
            PurchaseTierRequest(plan = plan)
        )
        runRequest<InvoiceDto>(
            url = "$baseUrl/tier",
            method = "POST",
            authRequired = true,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * POST /feature — the one-call checkout for ANY registered add-on
     * product (ANDROID_PAYMENTS.md §3.1b, migration 057): creates-or-reuses
     * the pending `feature_purchases` row with the price pinned, then mints
     * the invoice. `state == "paid"` IS the entitlement signal — re-fetch
     * tier-info afterwards. Extra errors: 403
     * [PaymentsError.FeaturePurchasesDisabled], 503
     * [PaymentsError.FeatureDisabled] (hide the buy path), 409
     * [PaymentsError.AlreadyEntitled] (nothing to buy).
     */
    suspend fun purchaseFeature(product: FeatureProduct): Result<InvoiceDto> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(
            PurchaseFeatureRequest.serializer(),
            PurchaseFeatureRequest(product = product.wire)
        )
        runRequest<InvoiceDto>(
            url = "$baseUrl/feature",
            method = "POST",
            authRequired = true,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        ).fold(
            onSuccess = { Result.success(it) },
            onFailure = { e ->
                // parseError lacks the product context; enrich 409s with it.
                val enriched = if (e is PaymentsError.AlreadyEntitled && e.product == null) {
                    PaymentsError.AlreadyEntitled(product.wire)
                } else {
                    e
                }
                Result.failure(enriched)
            }
        )
    }

    /**
     * GET /history — the unified purchase timeline for the billing screen
     * (§3.5): one item per purchase across vanity/slots/tier with the latest
     * invoice attached; admin-approved + orphan entries included. Read-only.
     */
    suspend fun getHistory(): Result<PaymentHistoryResponse> = withContext(Dispatchers.IO) {
        runRequest<PaymentHistoryResponse>(
            url = "$baseUrl/history",
            method = "GET",
            authRequired = true
        )
    }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        authRequired: Boolean,
        bodyBytes: ByteArray? = null,
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
                return Result.failure(PaymentsError.Unknown("Empty response body"))
            }

            val parsed = json.decodeFromString<T>(responseBody)
            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(parsed)
        } catch (e: PaymentsError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(PaymentsError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): PaymentsError {
        val parsed = try {
            Json.decodeFromString<PaymentsErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        val codeStr = parsed?.error

        return when (code) {
            401 -> PaymentsError.Unauthorized
            403 -> when {
                codeStr == "lifetime_disabled" -> PaymentsError.LifetimeDisabled
                // Parameterized: "<product>_purchases_disabled" (fanout, key_rotation, …).
                codeStr?.endsWith("_purchases_disabled") == true ->
                    PaymentsError.FeaturePurchasesDisabled(
                        codeStr.removeSuffix("_purchases_disabled")
                    )
                else -> PaymentsError.Server(codeStr ?: "Forbidden", code)
            }
            404 -> PaymentsError.TargetNotFound
            409 -> when (codeStr) {
                "not_pending" -> PaymentsError.NotPending(parsed?.status)
                "already_paid" -> PaymentsError.AlreadyPaid
                "plan_switch_blocked" -> PaymentsError.PlanSwitchBlocked
                "already_entitled" -> PaymentsError.AlreadyEntitled()
                else -> PaymentsError.Server(codeStr ?: "Conflict", code)
            }
            422 -> when (codeStr) {
                "invalid_target_type" -> PaymentsError.InvalidTargetType
                "zero_price" -> PaymentsError.ZeroPrice
                "invalid_product" -> PaymentsError.InvalidProduct
                else -> PaymentsError.Unknown(codeStr ?: "Validation error")
            }
            429 -> PaymentsError.RateLimited
            502 -> PaymentsError.StrikeUnavailable
            503 -> when {
                // Parameterized: "<feature>_disabled" — the feature's master
                // switch is off; nothing is sold, hide the buy path.
                codeStr?.endsWith("_disabled") == true && codeStr != "payments_disabled" ->
                    PaymentsError.FeatureDisabled(codeStr.removeSuffix("_disabled"))
                else -> PaymentsError.PaymentsDisabled
            }
            else -> PaymentsError.Server(codeStr ?: "Server error", code)
        }
    }

    companion object {
        private const val TAG = "PaymentsClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/payments"
    }
}
