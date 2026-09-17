package xyz.desent.data.nip11

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.desent.domain.model.Fee
import xyz.desent.domain.model.Fees
import xyz.desent.domain.model.Limitations
import xyz.desent.domain.model.Nip11Metadata
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.toKotlinDuration
import kotlin.time.toJavaDuration
import kotlin.time.toDuration

class Nip11MetadataService(
    private val okHttpClient: OkHttpClient
) {
    
    companion object {
        private const val TAG = "Nip11MetadataService"
        private val json = Json { 
            ignoreUnknownKeys = true 
            coerceInputValues = true
        }
    }
    
    /**
     * Fetch NIP-11 metadata from a relay URL
     * Returns null if fetch fails or no metadata available
     */
    suspend fun fetchMetadata(relayUrl: String): Nip11Metadata? {
        return try {
            val hostname = extractHostname(relayUrl)
            // NIP-11: the relay information document is served at the HTTP
            // root of the relay host with an application/nostr+json Accept.
            val nip11Url = "https://$hostname/"

            android.util.Log.d(TAG, "Fetching NIP-11 metadata from: $nip11Url")

            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(nip11Url)
                    .header("Accept", "application/nostr+json")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.w(TAG, "HTTP ${response.code} when fetching NIP-11 from $hostname")
                        return@withContext null
                    }

                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        android.util.Log.w(TAG, "Empty response body from $hostname")
                        return@withContext null
                    }

                    val metadata = json.decodeFromString<Nip11Response>(responseBody).toDomain()
                    android.util.Log.d(TAG, "Successfully fetched NIP-11 metadata from $hostname")
                    metadata
                }
            }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "Network error fetching NIP-11 metadata: ${e.message}")
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error parsing NIP-11 metadata: ${e.message}", e)
            null
        }
    }
    
    private fun extractHostname(relayUrl: String): String {
        return relayUrl
            .replace("wss://", "")
            .replace("ws://", "")
            .removePrefix("http://")
            .removePrefix("https://")
            .split("/")[0] // Remove any path components
    }
    
    /**
     * NIP-11 response DTO for JSON deserialization
     */
    @Serializable
    private data class Nip11Response(
        val name: String? = null,
        val description: String? = null,
        val pubkey: String? = null,
        val contact: String? = null,
        @SerialName("supported_nips")
        val supportedNips: List<Int>? = null,
        val version: String? = null,
        val icon: String? = null,
        val software: String? = null,
        @SerialName("relay_countries")
        val relayCountries: List<String>? = null,
        @SerialName("language_tags")
        val languageTags: List<String>? = null,
        @SerialName("posting_policy")
        val postingPolicy: String? = null,
        val limitations: Nip11LimitationsResponse? = null,
        val fees: Nip11FeesResponse? = null,
        val payments: String? = null
    ) {
        fun toDomain(): Nip11Metadata {
            return Nip11Metadata(
                name = name,
                description = description,
                pubkey = pubkey,
                contact = contact,
                supportedNips = supportedNips,
                version = version,
                icon = icon,
                software = software,
                relayCountries = relayCountries,
                languageTags = languageTags,
                postingPolicy = postingPolicy,
                limitations = limitations?.toDomain(),
                fees = fees?.toDomain(),
                payments = payments
            )
        }
    }
    
    @Serializable
    private data class Nip11LimitationsResponse(
        @SerialName("max_message_length")
        val maxMessageLength: Long? = null,
        @SerialName("max_subscription_id_length")
        val maxSubscriptionIdLength: Long? = null,
        @SerialName("max_filter_tags")
        val maxFilterTags: Int? = null,
        @SerialName("max_limit")
        val maxLimit: Long? = null,
        @SerialName("max_subtotal")
        val maxSubtotal: Long? = null,
        @SerialName("min_prefix")
        val minPrefix: Int? = null,
        @SerialName("max_event_tags")
        val maxEventTags: Int? = null,
        @SerialName("max_content_length")
        val maxContentLength: Long? = null,
        @SerialName("min_pow_difficulty")
        val minPowDifficulty: Int? = null,
        @SerialName("auth_required")
        val authRequired: Boolean? = null,
        @SerialName("payment_required")
        val paymentRequired: Boolean? = null
    ) {
        fun toDomain(): Limitations {
            return Limitations(
                maxMessageLength = maxMessageLength,
                maxSubscriptionIdLength = maxSubscriptionIdLength,
                maxFilterTags = maxFilterTags,
                maxLimit = maxLimit,
                maxSubtotal = maxSubtotal,
                minPrefix = minPrefix,
                maxEventTags = maxEventTags,
                maxContentLength = maxContentLength,
                minPowDifficulty = minPowDifficulty,
                authRequired = authRequired,
                paymentRequired = paymentRequired
            )
        }
    }
    
    @Serializable
    private data class Nip11FeesResponse(
        val admission: List<Nip11FeeResponse>? = null,
        val subscription: List<Nip11FeeResponse>? = null,
        val publication: List<Nip11FeeResponse>? = null
    ) {
        fun toDomain(): Fees {
            return Fees(
                admission = admission?.map { it.toDomain() },
                subscription = subscription?.map { it.toDomain() },
                publication = publication?.map { it.toDomain() }
            )
        }
    }
    
    @Serializable
    private data class Nip11FeeResponse(
        val amount: Long,
        val unit: String,
        val period: String? = null
    ) {
        fun toDomain(): Fee {
            return Fee(
                amount = amount,
                unit = unit,
                period = period
            )
        }
    }
}